package dev.dreiling.YoCoderAPI.service;

import dev.dreiling.YoCoderAPI.model.ProjectScanResult;
import dev.dreiling.YoCoderAPI.model.RefactorRequest;
import dev.dreiling.YoCoderAPI.model.RefactorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;

@Service
public class RefactorService {

    private static final Logger log = LoggerFactory.getLogger(RefactorService.class);

    private final FileService fileService;
    private final ContextService contextService;
    private final LlmProviderFactory providerFactory;

    public RefactorService(FileService fileService,
                           ContextService contextService,
                           LlmProviderFactory providerFactory) {
        this.fileService = fileService;
        this.contextService = contextService;
        this.providerFactory = providerFactory;
    }

    public Flux<String> streamRefactor(RefactorRequest req) {
        return Mono.fromCallable(() -> {
                    log.info("Scanning project: {}", req.getProjectRoot());
                    ProjectScanResult scan = fileService.scanProject(req.getProjectRoot());
                    if (!scan.isSuccess()) {
                        throw new RuntimeException("Project scan failed: " + scan.getErrorMessage());
                    }

                    log.info("Building context for: {}", req.getTargetFile());
                    ContextService.BuiltContext ctx = contextService.buildContext(req, scan.getFiles());
                    log.info("Prompt ready: {} chars, {} context files", ctx.promptCharCount, ctx.filesUsed.size());

                    return ctx;
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMapMany(ctx -> {
                    LlmProvider provider = providerFactory.getProvider(req.getProviderOverride());
                    return provider.streamRefactorRequest(ctx.prompt);
                })
                .doOnError(e -> log.error("Streaming pipeline error: {}", e.getMessage()));
    }

    public Mono<RefactorResponse> saveRefactoredFile(String projectRoot, String relativePath, String content) {
        return Mono.fromCallable(() -> {
            try {
                fileService.saveRefactoredFile(projectRoot, relativePath, content);
                return RefactorResponse.success(content, "File saved.", List.of(relativePath), 0, List.of());
            } catch (IOException e) {
                return RefactorResponse.error("Failed to save: " + e.getMessage());
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}