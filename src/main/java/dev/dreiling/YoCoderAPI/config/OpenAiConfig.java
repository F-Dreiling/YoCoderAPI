package dev.dreiling.YoCoderAPI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "yocoderapi.openai")
public class OpenAiConfig {

    private String apiKey;
    private String model;
    private int maxTokens;
    private String apiUrl;
}