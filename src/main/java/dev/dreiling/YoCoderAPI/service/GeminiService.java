package dev.dreiling.YoCoderAPI.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dreiling.YoCoderAPI.config.GeminiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class GeminiService implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    private final GeminiConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient;

    public GeminiService(GeminiConfig config) {
        this.config = config;
        // Gemini's streaming endpoint uses alt=sse query param
        // and requires the API key as a query parameter, not a header
        this.webClient = WebClient.builder()
                .baseUrl(config.getApiUrl())
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    /**
     * Gemini streaming SSE format differs from Claude and OpenAI.
     * Each chunk looks like:
     *
     *   data: {
     *     "candidates": [{
     *       "content": {
     *         "parts": [{ "text": "chunk here" }]
     *       }
     *     }]
     *   }
     *
     * The API key is passed as a query parameter: ?key=YOUR_KEY&alt=sse
     * alt=sse tells Gemini to stream SSE instead of returning one big response.
     */
    @Override
    public Flux<String> streamRefactorRequest(String userPrompt) {
        validateApiKey();

        String requestBody = buildRequestBody(userPrompt);

        return webClient.post()
                // API key and alt=sse go in the query string for Gemini
                .uri(uriBuilder -> uriBuilder
                        .queryParam("key", config.getApiKey())
                        .queryParam("alt", "sse")
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    log.error("Gemini API error body: {}", errorBody);
                                    return Mono.error(new RuntimeException("Gemini API error: " + errorBody));
                                })
                )
                .bodyToFlux(String.class)
                //.doOnNext(line -> log.debug("RAW FROM GEMINI: [{}]", line)) // DEBUG
                .filter(line -> !line.isBlank())
                .flatMap(this::extractTextDelta)
                .doOnError(e -> log.error("Gemini streaming error: {}", e.getMessage()))
                .doOnComplete(() -> log.info("Gemini stream completed"));
    }

    private Flux<String> extractTextDelta(String data) {
        // log.debug("RAW GEMINI CHUNK: [{}]", data);
        try {
            JsonNode root = mapper.readTree(data);
            // log.debug("PARSED JSON: {}", root.toPrettyString());
            JsonNode candidates = root.path("candidates");

            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    String text = parts.get(0).path("text").asText();
                    if (!text.isEmpty()) {
                        return Flux.just(text);
                    }
                }

                String finishReason = candidates.get(0).path("finishReason").asText();
                if (!finishReason.isEmpty() && !finishReason.equals("null")) {
                    log.info("Gemini finish reason: {}", finishReason);
                }
            }

        } catch (Exception e) {
            log.debug("Could not parse Gemini SSE chunk: {}", data);
        }

        return Flux.empty();
    }

    private String buildRequestBody(String userPrompt) {
        try {
            ObjectNode body = mapper.createObjectNode();

            // System instruction — Gemini uses a separate systemInstruction field
            ObjectNode systemInstruction = mapper.createObjectNode();
            ArrayNode systemParts = mapper.createArrayNode();
            ObjectNode systemPart = mapper.createObjectNode();
            systemPart.put("text",
                    "You are an expert software engineer. " +
                            "When asked to refactor code, output each affected file preceded by a marker on its own line: ##FILE: <relative/path/to/file>. " +
                            "If only one file needs changes, output just that one file with its ##FILE: marker. " +
                            "If the instruction requires changes to multiple files, output all of them with their respective ##FILE: markers. " +
                            "After all files, add a section starting with ## EXPLANATION followed by a numbered list of changes. " +
                            "If asked to keep the code intact, output the file as-is with its ##FILE: marker and only generate the explanation. " +
                            "In the EXPLANATION, list each point as a separate numbered item on its own line. " +
                            "Never wrap code in markdown fences unless explicitly asked.");

            systemParts.add(systemPart);
            systemInstruction.set("parts", systemParts);
            body.set("systemInstruction", systemInstruction);

            // User message
            ArrayNode contents = mapper.createArrayNode();
            ObjectNode userContent = mapper.createObjectNode();
            userContent.put("role", "user");
            ArrayNode userParts = mapper.createArrayNode();
            ObjectNode userPart = mapper.createObjectNode();
            userPart.put("text", userPrompt);
            userParts.add(userPart);
            userContent.set("parts", userParts);
            contents.add(userContent);
            body.set("contents", contents);

            // Generation config
            ObjectNode generationConfig = mapper.createObjectNode();
            generationConfig.put("maxOutputTokens", config.getMaxTokens());
            body.set("generationConfig", generationConfig);

            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Gemini request body", e);
        }
    }

    private void validateApiKey() {
        String key = config.getApiKey();
        if (key == null || key.isBlank() || key.equals("YOUR_GEMINI_KEY_HERE")) {
            throw new IllegalStateException(
                    "Gemini API key not configured. Set YOCODER_GEMINI_API_KEY env var " +
                            "or edit application.yml"
            );
        }
    }
}