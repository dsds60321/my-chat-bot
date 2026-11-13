package com.mychatbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;

class GroqAiProviderTest {
    private HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    private AiProperties properties;

    private GroqAiProvider start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.setExecutor(executor);
        server.start();
        properties = new AiProperties();
        properties.getGroq().setApiKey("test-only-placeholder");
        properties.getGroq().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setIdleTimeout(Duration.ofSeconds(2));
        properties.setRequestTimeout(Duration.ofSeconds(5));
        return new GroqAiProvider(properties, new ObjectMapper(), WebClient.builder().build());
    }
    @AfterEach void stop() { if (server != null) server.stop(0); executor.shutdownNow(); }

    @Test void readsSplitSseChunksAndCompletion() throws Exception {
        GroqAiProvider provider = start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"안\"}}]}\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write("\ndata: {\"choices\":[{\"delta\":{\"content\":\"녕\"}}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        StepVerifier.create(provider.stream(new AiRequest("질문", ""))).expectNext("안", "녕").verifyComplete();
    }

    @Test void shortRetryAfterDoesNotReplayTheRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        GroqAiProvider provider = start(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Retry-After", "1");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        StepVerifier.create(provider.stream(new AiRequest("질문", "")))
                .expectErrorSatisfies(error -> assertThat(((AiException) error).retryAfterSeconds()).isEqualTo(1))
                .verify(Duration.ofSeconds(5));
        assertThat(requests).hasValue(1);
    }

    @Test void lengthLimitKeepsLastTextAndFailsInsteadOfCompleting() throws Exception {
        GroqAiProvider provider = start(exchange -> {
            var body = new ObjectMapper().readTree(exchange.getRequestBody());
            assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(4096);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"마지막 부분\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        StepVerifier.create(provider.stream(new AiRequest("질문", ""))).expectNext("마지막 부분")
                .expectErrorSatisfies(error -> assertThat(((AiException) error).code()).isEqualTo("ANSWER_LENGTH_LIMIT"))
                .verify(Duration.ofSeconds(6));
    }

    @Test void reportsRetryAfterWithoutUnboundedWait() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        GroqAiProvider provider = start(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Retry-After", "30");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        StepVerifier.create(provider.stream(new AiRequest("질문", ""))).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(AiException.class);
            assertThat(((AiException) error).code()).isEqualTo("RATE_LIMITED");
            assertThat(((AiException) error).retryAfterSeconds()).isEqualTo(30);
        }).verify();
        assertThat(requests).hasValue(1);
    }

    @Test void timesOutAndDoesNotRetryAfterDelta() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        GroqAiProvider provider = start(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        });
        properties.setIdleTimeout(Duration.ofMillis(300));
        StepVerifier.create(provider.stream(new AiRequest("질문", ""))).expectNext("partial")
                .expectErrorSatisfies(error -> assertThat(((AiException) error).code()).isEqualTo("UPSTREAM_TIMEOUT")).verify();
        assertThat(requests).hasValue(1);
    }

    @Test void cancellationCancelsUpstreamWithoutRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        var disconnected = new java.util.concurrent.CountDownLatch(1);
        GroqAiProvider provider = start(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    writes.incrementAndGet();
                    try { Thread.sleep(40); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                }
            } catch (java.io.IOException closed) { disconnected.countDown(); }
        });
        StepVerifier.create(provider.stream(new AiRequest("질문", ""))).expectNext("part").thenCancel().verify();
        assertThat(disconnected.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(requests).hasValue(1);
        assertThat(writes.get()).isLessThan(100);
    }

    @Test void cleanEofAfterPartialDeltaIsAnErrorWithoutRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        GroqAiProvider provider = start(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) { out.write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8)); }
        });
        StepVerifier.create(provider.stream(new AiRequest("질문", ""))).expectNext("partial")
                .expectErrorSatisfies(error -> assertThat(((AiException) error).code()).isEqualTo("TRUNCATED_RESPONSE")).verify();
        assertThat(requests).hasValue(1);
    }

    @Test void missingKeyFailsExplicitlyAndNettyTimeoutIsNormalized() {
        AiProperties settings = new AiProperties();
        GroqAiProvider provider = new GroqAiProvider(settings, new ObjectMapper(), WebClient.create());
        StepVerifier.create(provider.stream(new AiRequest("질문", "")))
                .expectErrorSatisfies(error -> assertThat(((AiException) error).code()).isEqualTo("PROVIDER_NOT_CONFIGURED")).verify();
        assertThat(GroqAiProvider.standardize(new RuntimeException(io.netty.handler.timeout.ReadTimeoutException.INSTANCE)).code()).isEqualTo("UPSTREAM_TIMEOUT");
    }

    @Test void rateLimitWaitUsesRetryAfterOrExhaustedQuotasOnly() {
        Instant now = Instant.parse("2026-11-12T00:00:00.500Z");
        HttpHeaders headers = new HttpHeaders();
        assertThat(GroqAiProvider.retryAfter(headers, now)).isNull();
        headers.set("x-ratelimit-remaining-requests", "1");
        headers.set("x-ratelimit-reset-requests", "2m59.56s");
        headers.set("x-ratelimit-remaining-tokens", "0");
        headers.set("x-ratelimit-reset-tokens", "7.66s");
        assertThat(GroqAiProvider.retryAfter(headers, now)).isEqualTo(8);
        headers.set("x-ratelimit-remaining-requests", "0");
        assertThat(GroqAiProvider.retryAfter(headers, now)).isEqualTo(180);
        headers.set(HttpHeaders.RETRY_AFTER, "7200");
        assertThat(GroqAiProvider.retryAfter(headers, now)).isEqualTo(7200);
        headers.set(HttpHeaders.RETRY_AFTER, "Thu, 12 Nov 2026 00:00:03 GMT");
        assertThat(GroqAiProvider.retryAfter(headers, now)).isEqualTo(3);
        headers.set(HttpHeaders.RETRY_AFTER, "invalid");
        assertThat(GroqAiProvider.retryAfter(headers, now)).isEqualTo(180);
        headers.remove("x-ratelimit-reset-requests");
        assertThat(GroqAiProvider.retryAfter(headers, now)).isNull();
    }
}
