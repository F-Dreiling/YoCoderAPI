package dev.dreiling.YoCoderAPI.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LlmProviderFactory {

    private final ClaudeService claudeService;
    private final OpenAiService openAiService;
    private final GeminiService geminiService;

    @Value("${yocoderapi.active-provider:gemini}")
    private String activeProvider;

    public LlmProviderFactory(ClaudeService claudeService,
                              OpenAiService openAiService,
                              GeminiService geminiService) {
        this.claudeService = claudeService;
        this.openAiService = openAiService;
        this.geminiService = geminiService;
    }

    public LlmProvider getProvider(String override) {
        String provider = (override != null && !override.isBlank())
                ? override
                : activeProvider;

        return switch (provider.toLowerCase()) {
            case "claude" -> claudeService;
            case "openai" -> openAiService;
            case "gemini" -> geminiService;
            default -> throw new IllegalStateException(
                    "Unknown provider '" + provider + "'. Use 'claude', 'openai', or 'gemini'."
            );
        };
    }

    public String getActiveProviderName() {
        return activeProvider;
    }
}