package dev.dreiling.YoCoderAPI.controller;

import dev.dreiling.YoCoderAPI.model.RefactorRequest;
import dev.dreiling.YoCoderAPI.service.RefactorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RefactorController {

    private static final Logger log = LoggerFactory.getLogger(RefactorController.class);

    private final RefactorService refactorService;

    public RefactorController(RefactorService refactorService) {
        this.refactorService = refactorService;
    }

    @GetMapping("/health")
    public Mono<Map<String, String>> health() {
        return Mono.just(Map.of(
                "status", "UP",
                "service", "YoCoderAPI",
                "version", "2.0"
        ));
    }

    @PostMapping(value = "/refactor", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamRefactor(@RequestBody RefactorRequest req) {
        if (req.getTargetFilePath() == null || req.getTargetFileContent() == null || req.getPrompt() == null) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("[ERROR] Missing targetFilePath, targetFileContent, or prompt")
                    .build());
        }

        log.info("Stream refactor: file={} provider={} contextFiles={}",
                req.getTargetFilePath(), req.getProviderOverride(),
                req.getContextFileContents() != null ? req.getContextFileContents().size() : 0);

        return refactorService.streamRefactor(req)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("chunk")
                        .data(chunk)
                        .build())
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("[DONE]")
                        .build()))
                .onErrorResume(e -> {
                    log.error("Stream error: {}", e.getMessage());
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("[ERROR] " + e.getMessage())
                            .build());
                });
    }
}