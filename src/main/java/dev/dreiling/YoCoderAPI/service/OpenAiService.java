package dev.dreiling.YoCoderAPI.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dreiling.YoCoderAPI.config.OpenAiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class OpenAiService implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);

    private final OpenAiConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient;

    public OpenAiService(OpenAiConfig config) {
        this.config = config;
        // OpenAI uses Bearer token auth instead of x-api-key
        this.webClient = WebClient.builder()
                .baseUrl(config.getApiUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    /**
     * OpenAI streaming SSE format is slightly different from Claude:
     *
     *   data: {"choices":[{"delta":{"content":"chunk here"},"finish_reason":null}]}
     *   data: [DONE]
     *
     * We extract choices[0].delta.content from each chunk.
     */
    @Override
    public Flux<String> streamRefactorRequest(String userPrompt) {
        validateApiKey();

        String requestBody = buildRequestBody(userPrompt);

        return webClient.post()
                .uri("")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    log.error("OpenAI API error body: {}", errorBody);
                                    return Mono.error(new RuntimeException("OpenAI API error: " + errorBody));
                                })
                )
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .takeWhile(data -> !data.equals("[DONE]"))
                .flatMap(this::extractTextDelta)
                .doOnError(e -> log.error("OpenAI streaming error: {}", e.getMessage()))
                .doOnComplete(() -> log.info("OpenAI stream completed"));
    }

    private Flux<String> extractTextDelta(String data) {
        try {
            JsonNode root = mapper.readTree(data);
            JsonNode choices = root.path("choices");

            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).path("delta");
                String content = delta.path("content").asText();
                if (!content.isEmpty()) {
                    return Flux.just(content);
                }

                // Log finish reason when stream ends
                String finishReason = choices.get(0).path("finish_reason").asText();
                if (!finishReason.isEmpty() && !finishReason.equals("null")) {
                    log.info("OpenAI finish reason: {}", finishReason);
                }
            }

        } catch (Exception e) {
            log.debug("Could not parse OpenAI SSE chunk: {}", data);
        }

        return Flux.empty();
    }

    private String buildRequestBody(String userPrompt) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", config.getModel());
            body.put("max_tokens", config.getMaxTokens());
            body.put("stream", true);

            ArrayNode messages = mapper.createArrayNode();

            ObjectNode systemMsg = mapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content",
                    "You are an expert software engineer. " +
                            "When asked to refactor code, output each affected file preceded by a marker on its own line: ##FILE: <relative/path/to/file>. " +
                            "If only one file needs changes, output just that one file with its ##FILE: marker. " +
                            "If the instruction requires changes to multiple files, output all of them with their respective ##FILE: markers. " +
                            "After all files, add a section starting with ## EXPLANATION followed by a numbered list of changes. " +
                            "If asked to keep the code intact, output the file as-is with its ##FILE: marker and only generate the explanation. " +
                            "In the EXPLANATION, list each point as a separate numbered item on its own line. " +
                            "Never wrap code in markdown fences unless explicitly asked.");

            messages.add(systemMsg);

            ObjectNode userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);

            body.set("messages", messages);

            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build OpenAI request body", e);
        }
    }

    private void validateApiKey() {
        String key = config.getApiKey();
        if (key == null || key.isBlank() || key.equals("YOUR_OPENAI_KEY_HERE")) {
            throw new IllegalStateException(
                    "OpenAI API key not configured. Set YOCODER_OPENAI_API_KEY env var " +
                            "or edit application.yml"
            );
        }
    }
}