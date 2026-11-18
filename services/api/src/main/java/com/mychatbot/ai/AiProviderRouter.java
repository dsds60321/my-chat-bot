package com.mychatbot.ai;

import com.mychatbot.logging.ContentLog;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class AiProviderRouter {
    private static final Set<String> FALLBACK_CODES = Set.of(
            "PROVIDER_NOT_CONFIGURED", "RATE_LIMITED", "UPSTREAM_AUTH");
    private final AiProperties properties;
    private final List<AiProvider> providers;
    public AiProviderRouter(AiProperties properties, List<AiProvider> providers) {
        this.properties = properties;
        this.providers = List.copyOf(providers);
    }
    public <T> Flux<T> route(AiRequest request, Function<String, T> delta, Function<String, T> done) {
        List<String> ids = Arrays.stream(properties.getProvider().split(",", -1)).map(String::strip).toList();
        if (new HashSet<>(ids).size() != ids.size() || (ids.contains("mock") && ids.size() > 1)) {
            throw new AiException("PROVIDER_NOT_CONFIGURED", "AI 공급자 중복 설정을 제거하고 Mock은 단독으로 설정해 주세요.");
        }
        List<AiProvider> ordered = ids.stream().map(id -> providers.stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new AiException("PROVIDER_NOT_CONFIGURED", "사용할 AI 공급자를 설정해 주세요.")))
                .sorted(Comparator.comparingInt(p -> properties.orderOf(p.id()))).toList();
        return attempt(ordered, 0, request, delta, done, null)
                .takeUntilOther(Mono.delay(properties.getRequestTimeout()).then(Mono.error(
                        new AiException("UPSTREAM_TIMEOUT", "AI 응답 시간이 초과됐어요. 다시 시도해 주세요."))));
    }

    private <T> Flux<T> attempt(List<AiProvider> ordered, int index, AiRequest request,
                                Function<String, T> delta, Function<String, T> done, AiException previous) {
        return Flux.deferContextual(context -> {
            AiProvider provider = ordered.get(index);
            ContentLog.Request logRequest = context.getOrDefault(ContentLog.REQUEST,
                    new ContentLog.Request("-", java.time.Instant.now().toString(), "-", "POST", "/api/chat/stream", "-"));
            var response = new ContentLog.Response("ai", logRequest, provider.id());
            if (!(provider instanceof HttpAiProvider)) {
                ContentLog.request("ai", logRequest, provider.id(),
                        "question=" + request.question() + " context=" + request.context());
            }
            AtomicBoolean emitted = new AtomicBoolean();
            return Flux.defer(() -> provider.stream(request))
                    .doOnNext(text -> { emitted.set(true); response.append(text); })
                    .doOnComplete(() -> response.finish("done", "-"))
                    .doOnError(error -> response.finish("error", error instanceof AiException ai ? ai.code() : "INTERNAL_ERROR"))
                    .doFinally(signal -> response.finish(signal.toString(), "-"))
                    .map(delta)
                    .concatWith(Mono.fromSupplier(() -> done.apply(provider.id())))
                    .onErrorResume(AiException.class, error -> {
                        if (emitted.get() || !FALLBACK_CODES.contains(error.code())) return Flux.error(error);
                        AiException failure = previous != null && error.code().equals("PROVIDER_NOT_CONFIGURED")
                                ? previous : error;
                        return index + 1 < ordered.size() ? attempt(ordered, index + 1, request, delta, done, failure)
                                : Flux.error(failure);
                    });
        });
    }
}
