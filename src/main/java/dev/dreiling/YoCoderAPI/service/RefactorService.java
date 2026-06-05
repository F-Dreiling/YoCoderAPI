package dev.dreiling.YoCoderAPI.service;

import dev.dreiling.YoCoderAPI.model.RefactorRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class RefactorService {

    private static final Logger log = LoggerFactory.getLogger(RefactorService.class);

    private final ContextService contextService;
    private final LlmProviderFactory providerFactory;

    public RefactorService(ContextService contextService, LlmProviderFactory providerFactory) {
        this.contextService = contextService;
        this.providerFactory = providerFactory;
    }

    public Flux<String> streamRefactor(RefactorRequest req) {
        return Mono.fromCallable(() -> {
                    log.info("Building context for: {}", req.getTargetFile());
                    ContextService.BuiltContext ctx = contextService.buildContext(req);
                    log.info("Prompt ready: {} chars, {} files in context", ctx.promptCharCount, ctx.filesUsed.size());
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
     * Post-processes the raw LLM stream to reassemble lines that were split
     * mid-expression by the model. Accumulates a rolling pending fragment;
     * on each newline boundary the complete line is flushed to output.
     */
    private Flux<String> normalizeLineBreaks(Flux<String> source) {
        AtomicReference<String> pending = new AtomicReference<>("");
        AtomicReference<StringBuilder> outputBuffer = new AtomicReference<>(new StringBuilder());

        return source
                .flatMap(chunk -> {
                    String[] rawLines = chunk.split("\n", -1);
                    StringBuilder out = outputBuffer.get();

                    for (int i = 0; i < rawLines.length; i++) {
                        String part = rawLines[i];
                        if (i < rawLines.length - 1) {
                            String fullLine = pending.get() + part;
                            pending.set("");
                            out.append(fullLine).append("\n");
                        } else {
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
                    String last = pending.get();
                    return last.isEmpty() ? Flux.empty() : Flux.just(last);
                }));
    }
}