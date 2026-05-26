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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
public class RefactorService {

    private static final Logger log = LoggerFactory.getLogger(RefactorService.class);

    // A line is "continuation-broken" if it ends without a logical terminator.
    // We rejoin it with the next line using a space.
    private static final Pattern BROKEN_LINE = Pattern.compile(
            ".*[a-zA-Z0-9\"'_)>]$"   // ends with an identifier char (no semicolon, comma, brace, etc.)
    );

    // These prefixes always start a new logical line — never rejoin them
    private static final Pattern LINE_STARTER = Pattern.compile(
            "^\\s*(//|/\\*|\\*|@|##FILE:|## )"
    );

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
                .transform(this::normalizeLineBreaks)
                .doOnError(e -> log.error("Streaming pipeline error: {}", e.getMessage()));
    }

    /**
     * Post-processes the raw LLM stream to fix broken lines caused by the model
     * word-wrapping long statements mid-expression.
     *
     * Strategy: accumulate a rolling "pending" line. When a chunk arrives that
     * looks like a continuation of the previous line (no leading indent, no
     * special prefix), we stitch it back with a space. When a chunk is clearly
     * a new logical line (starts with indent / special char), we flush the
     * pending line and start fresh.
     *
     * Because the stream comes in arbitrary chunk sizes (sometimes partial lines,
     * sometimes multiple lines), we first re-tokenize on newlines and process
     * each line individually before emitting.
     */
    private Flux<String> normalizeLineBreaks(Flux<String> source) {
        AtomicReference<String> pending = new AtomicReference<>("");
        AtomicReference<StringBuilder> outputBuffer = new AtomicReference<>(new StringBuilder());

        return source
                .flatMap(chunk -> {
                    // Split chunk into lines, preserving trailing empty strings for newline tracking
                    String[] rawLines = chunk.split("\n", -1);
                    StringBuilder out = outputBuffer.get();

                    for (int i = 0; i < rawLines.length; i++) {
                        String part = rawLines[i];

                        if (i < rawLines.length - 1) {
                            // This part is followed by a newline — it's a complete line
                            String fullLine = pending.get() + part;
                            pending.set("");
                            out.append(fullLine).append("\n");
                        } else {
                            // Last part — may be incomplete (no trailing newline yet)
                            pending.set(pending.get() + part);
                        }
                    }

                    if (out.length() > 0) {
                        String result = out.toString();
                        outputBuffer.set(new StringBuilder());
                        return Flux.just(result);
                    }
                    return Flux.empty();
                })
                .concatWith(Flux.defer(() -> {
                    // Flush any remaining pending content at end of stream
                    String last = pending.get();
                    if (!last.isEmpty()) {
                        return Flux.just(last);
                    }
                    return Flux.empty();
                }));
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