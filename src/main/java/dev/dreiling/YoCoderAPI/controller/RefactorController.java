package dev.dreiling.YoCoderAPI.controller;

import dev.dreiling.YoCoderAPI.model.ProjectScanResult;
import dev.dreiling.YoCoderAPI.model.RefactorRequest;
import dev.dreiling.YoCoderAPI.model.RefactorResponse;
import dev.dreiling.YoCoderAPI.service.FileService;
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
    private final FileService fileService;

    public RefactorController(RefactorService refactorService, FileService fileService) {
        this.refactorService = refactorService;
        this.fileService = fileService;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public Mono<Map<String, String>> health() {
        return Mono.just(Map.of(
                "status", "UP",
                "service", "YoCoderAPI",
                "version", "1.0"
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/project/scan")
    public Mono<ProjectScanResult> scanProject(@RequestBody Map<String, String> body) {
        String projectRoot = body.get("projectRoot");

        if (projectRoot == null || projectRoot.isBlank()) {
            return Mono.just(ProjectScanResult.error("Missing 'projectRoot'"));
        }

        return Mono.fromCallable(() -> fileService.scanProject(projectRoot))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/project/file")
    public Mono<Map<String, String>> readFile(@RequestParam String projectRoot, @RequestParam String filePath) {
        return Mono.fromCallable(() -> {
            String content = fileService.readFileContent(projectRoot, filePath);
            return Map.of("content", content, "path", filePath);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping(value = "/refactor/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamRefactor(@RequestBody RefactorRequest req) {
        if (req.getProjectRoot() == null || req.getTargetFile() == null || req.getPrompt() == null) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("[ERROR] Missing projectRoot, targetFile, or prompt")
                    .build());
        }

        log.info("Stream refactor: project={} file={} provider={}",
                req.getProjectRoot(), req.getTargetFile(), req.getProviderOverride());

        return refactorService.streamRefactor(req)
                // .doOnNext(chunk -> log.debug("Emitting chunk: [{}]", chunk.substring(0, Math.min(50, chunk.length()))))
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

    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/refactor/save")
    public Mono<RefactorResponse> saveFile(@RequestBody Map<String, String> body) {
        String projectRoot = body.get("projectRoot");
        String filePath    = body.get("filePath");
        String content     = body.get("content");

        if (projectRoot == null || filePath == null || content == null) {
            return Mono.just(RefactorResponse.error("Missing projectRoot, filePath, or content"));
        }

        return refactorService.saveRefactoredFile(projectRoot, filePath, content);
    }
}