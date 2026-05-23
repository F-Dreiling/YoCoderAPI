package dev.dreiling.YoCoderAPI.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dreiling.YoCoderAPI.config.ClaudeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ClaudeService implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);

    private final ClaudeConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient;

    public ClaudeService(ClaudeConfig config) {
        this.config = config;
        this.webClient = WebClient.builder()
                .baseUrl(config.getApiUrl())
                .defaultHeader("x-api-key", config.getApiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024)) // 10MB buffer for large responses
                .build();
    }

    /**
     * Streams the refactored code back as a Flux<String>.
     *
     * Claude's streaming API sends Server-Sent Events in this format:
     *
     *   event: content_block_delta
     *   data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"chunk here"}}
     *
     * We filter only content_block_delta events and extract the text field from each.
     * The Flux completes when Claude sends the final "message_stop" event.
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
                                    log.error("Claude API error body: {}", errorBody);
                                    return Mono.error(new RuntimeException("Claude API error: " + errorBody));
                                })
                )
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .takeWhile(data -> !data.equals("[DONE]"))
                .flatMap(this::extractTextDelta)
                .doOnError(e -> log.error("Claude streaming error: {}", e.getMessage()))
                .doOnComplete(() -> log.info("Claude stream completed"));
    }

    /**
     * Parses one SSE data chunk and returns the text content if present.
     * Returns empty Flux for non-text events (ping, message_start, etc.)
     */
    private Flux<String> extractTextDelta(String data) {
        try {
            JsonNode root = mapper.readTree(data);
            String type = root.path("type").asText();

            // We only want content_block_delta events with text_delta type
            if ("content_block_delta".equals(type)) {
                JsonNode delta = root.path("delta");
                if ("text_delta".equals(delta.path("type").asText())) {
                    String text = delta.path("text").asText();
                    if (!text.isEmpty()) {
                        return Flux.just(text);
                    }
                }
            }

            // Log usage stats from message_delta (end of stream)
            if ("message_delta".equals(type)) {
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode()) {
                    log.info("Claude token usage — output: {}", usage.path("output_tokens").asInt());
                }
            }

        } catch (Exception e) {
            log.debug("Could not parse SSE chunk: {}", data);
        }

        // Return empty for events we don't care about — flatMap drops these
        return Flux.empty();
    }

    private String buildRequestBody(String userPrompt) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", config.getModel());
            body.put("max_tokens", config.getMaxTokens());
            // stream: true tells Claude to send SSE chunks instead of one big response
            body.put("stream", true);

            body.put("system",
                    "You are an expert software engineer. " +
                            "When asked to refactor code, output each affected file preceded by a marker on its own line: ##FILE: <relative/path/to/file>. " +
                            "If only one file needs changes, output just that one file with its ##FILE: marker. " +
                            "If the instruction requires changes to multiple files, output all of them with their respective ##FILE: markers. " +
                            "After all files, add a section starting with ## EXPLANATION followed by a numbered list of changes. " +
                            "If asked to keep the code intact, output the file as-is with its ##FILE: marker and only generate the explanation. " +
                            "In the EXPLANATION, list each point as a separate numbered item on its own line. " +
                            "Never wrap code in markdown fences unless explicitly asked.");

            ArrayNode messages = mapper.createArrayNode();
            ObjectNode userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);
            body.set("messages", messages);

            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Claude request body", e);
        }
    }

    private void validateApiKey() {
        String key = config.getApiKey();
        if (key == null || key.isBlank() || key.equals("YOUR_CLAUDE_KEY_HERE")) {
            throw new IllegalStateException(
                    "Claude API key not configured. Set YOCODER_CLAUDE_API_KEY env var " +
                            "or edit application.yml"
            );
        }
    }
}