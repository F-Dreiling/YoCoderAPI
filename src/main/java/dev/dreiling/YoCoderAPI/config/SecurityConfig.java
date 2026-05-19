package dev.dreiling.YoCoderAPI.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${yocoderapi.security.api-key}")
    private String expectedApiKey;

    @Value("${yocoderapi.security.api-header}")
    private String API_KEY_HEADER;

    @Bean
    public WebFilter apiKeyFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {

            String path = exchange.getRequest().getPath().value();

            // Let the health endpoint through without a key
            if (path.equals("/api/health")) {
                return chain.filter(exchange);
            }

            // All other /api/** endpoints require the key
            if (path.startsWith("/api/")) {
                String providedKey = exchange.getRequest()
                        .getHeaders()
                        .getFirst(API_KEY_HEADER);

                if (providedKey == null || !providedKey.equals(expectedApiKey)) {
                    log.warn("Rejected request to {} — missing or invalid API key", path);
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }
            }

            return chain.filter(exchange);
        };
    }
}