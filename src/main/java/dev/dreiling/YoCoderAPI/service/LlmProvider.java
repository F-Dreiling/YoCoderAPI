package dev.dreiling.YoCoderAPI.service;

import reactor.core.publisher.Flux;

public interface LlmProvider {
    Flux<String> streamRefactorRequest(String prompt);
}
