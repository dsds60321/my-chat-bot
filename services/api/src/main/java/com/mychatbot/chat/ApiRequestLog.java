package com.mychatbot.chat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiRequestLog implements WebFilter {
    private static final Logger LOG = LoggerFactory.getLogger(ApiRequestLog.class);
    private static final Set<HttpMethod> HTTP_METHODS = Set.of(HttpMethod.values());
    private static final String REQUEST_ID = ApiRequestLog.class.getName() + ".requestId";
    public static final String HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.equals("/api") && !path.startsWith("/api/")) return chain.filter(exchange);

        String requestId = UUID.randomUUID().toString();
        exchange.getAttributes().put(REQUEST_ID, requestId);
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        long started = System.nanoTime();
        return chain.filter(exchange)
                .doOnError(error -> log(exchange, path, requestId, started, SignalType.ON_ERROR,
                        error instanceof ErrorResponse response ? response.getStatusCode().value() : 500))
                .doFinally(signal -> {
                    if (signal != SignalType.ON_ERROR) log(exchange, path, requestId, started, signal,
                            signal == SignalType.CANCEL ? null : 200);
                });
    }

    static String requestId(ServerWebExchange exchange) {
        return exchange.getRequiredAttribute(REQUEST_ID);
    }

    static void streamTerminal(String requestId, StreamEvent event) {
        if (event.data() instanceof StreamEvent.Failure failure) {
            LOG.info("api_stream requestId={} outcome=error code={}", requestId, failure.code());
        } else if (event.data() instanceof StreamEvent.Done) {
            LOG.info("api_stream requestId={} outcome=done", requestId);
        }
    }

    private static void log(ServerWebExchange exchange, String path, String requestId, long started,
            SignalType signal, Integer fallbackStatus) {
        var status = exchange.getResponse().getStatusCode();
        Object statusCode = status != null ? status.value() : fallbackStatus == null ? "-" : fallbackStatus;
        HttpMethod method = exchange.getRequest().getMethod();
        LOG.info("api_request requestId={} method={} route={} status={} elapsedMs={} outcome={}",
                requestId, HTTP_METHODS.contains(method) ? method : "OTHER", route(exchange, path), statusCode,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), signal);
    }

    private static String route(ServerWebExchange exchange, String path) {
        Object matched = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (matched instanceof PathPattern pattern) return pattern.getPatternString();
        if (path.startsWith("/api/sources/")) return "/api/sources/{documentId}";
        return switch (path) {
            case "/api/chat/stream" -> "/api/chat/stream";
            case "/api/search" -> "/api/search";
            case "/api/session" -> "/api/session";
            default -> "/api/**";
        };
    }
}
