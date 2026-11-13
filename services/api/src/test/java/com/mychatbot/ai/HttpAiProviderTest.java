package com.mychatbot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class HttpAiProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiProperties settings = new AiProperties();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonNode> body = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> version = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        settings.setIdleTimeout(Duration.ofSeconds(3));
        settings.setRequestTimeout(Duration.ofSeconds(5));
    }

    @AfterEach void stop() { server.stop(0); }

    private void respond(int status, String response) {
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("x-api-key") != null
                    ? exchange.getRequestHeaders().getFirst("x-api-key")
                    : exchange.getRequestHeaders().getFirst("Authorization"));
            version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            body.set(mapper.readTree(exchange.getRequestBody()));
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.getResponseHeaders().set("Retry-After", "9");
            exchange.sendResponseHeaders(status, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
    }

    private HttpAiProvider provider(String id) {
        var endpoint = switch (id) {
            case "codex" -> settings.getCodex();
            case "claude" -> settings.getClaude();
            case "gemini" -> settings.getGemini();
            case "kimi" -> settings.getKimi();
            default -> settings.getGroq();
        };
        endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        endpoint.setApiKey("test-key");
        return new HttpAiProvider(id, endpoint, settings, mapper, HttpAiProvider.client(settings));
    }

    @ParameterizedTest
    @CsvSource({"codex, /v1/responses, max_output_tokens", "claude, /v1/messages, max_tokens",
            "gemini, /v1/chat/completions, max_tokens", "kimi, /v1/chat/completions, max_completion_tokens"})
    void sendsProviderSpecificRequestAndStreamsOnlyAnswer(String id, String expectedPath, String limitField) {
        String response = switch (id) {
            case "codex" -> sse("""
                    {"type":"response.reasoning_text.delta","delta":"hidden"}
                    {"type":"response.output_text.delta","delta":"답변"}
                    {"type":"response.completed"}
                    """);
            case "claude" -> sse("""
                    {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hidden"}}
                    {"type":"content_block_delta","delta":{"type":"text_delta","text":"답변"}}
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
                    {"type":"message_stop"}
                    """);
            default -> sse("""
                    {"choices":[{"delta":{"reasoning_content":"hidden"}}]}
                    {"choices":[{"delta":{"content":"답변"},"finish_reason":"stop"}]}
                    [DONE]
                    """);
        };
        respond(200, response);
        StepVerifier.create(provider(id).stream(new AiRequest("질문", "업무 근거")))
                .expectNext("답변").expectComplete().verify(Duration.ofSeconds(6));
        assertThat(requests).hasValue(1);
        assertThat(path.get()).isEqualTo(expectedPath);
        assertThat(auth.get()).isEqualTo(id.equals("claude") ? "test-key" : "Bearer test-key");
        assertThat(body.get().path(limitField).asInt()).isEqualTo(4096);
        assertThat(body.get().toString()).contains("<untrusted_documents>", "업무 근거");
        if (id.equals("claude")) {
            assertThat(version.get()).isEqualTo("2023-06-01");
            assertThat(body.get().path("system").asText()).isNotBlank();
        }
        if (id.equals("codex")) {
            assertThat(body.get().path("store").asBoolean(true)).isFalse();
            assertThat(body.get().path("instructions").asText()).isNotBlank();
        }
    }

    @ParameterizedTest
    @CsvSource({"codex, 429, insufficient_quota, RATE_LIMITED", "claude, 402, billing_error, RATE_LIMITED",
            "claude, 400, invalid_request_error, RATE_LIMITED", "gemini, 429, RESOURCE_EXHAUSTED, RATE_LIMITED",
            "kimi, 403, exceeded_current_quota_error, RATE_LIMITED", "kimi, 429, engine_overloaded_error, UPSTREAM_UNAVAILABLE",
            "groq, 401, invalid_api_key, UPSTREAM_AUTH", "claude, 403, permission_error, UPSTREAM_UNAVAILABLE"})
    void mapsProviderErrorsWithoutExposingRawDetails(String id, int status, String type, String code) {
        String message = status == 400 ? "Your credit balance is too low. private upstream detail" : "private upstream detail";
        respond(status, "{\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}");
        StepVerifier.create(provider(id).stream(new AiRequest("질문", "")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(AiException.class).hasMessageNotContaining("private upstream detail");
                    assertThat(((AiException) error).code()).isEqualTo(code);
                    if (code.equals("RATE_LIMITED")) assertThat(((AiException) error).retryAfterSeconds()).isEqualTo(9);
                }).verify(Duration.ofSeconds(6));
        assertThat(requests).hasValue(1);
    }

    @Test void anInStreamQuotaErrorBeforeTextUsesNextProviderOnce() {
        respond(200, sse("""
                {"type":"response.failed","response":{"error":{"code":"rate_limit_exceeded"}}}
                """));
        new Binder(new MapConfigurationPropertySource(java.util.Map.of(
                "ai.provider", "claude,codex", "ai.codex.order", "10", "ai.claude.order", "20")))
                .bind("ai", Bindable.ofInstance(settings));
        AtomicInteger next = new AtomicInteger();
        AiProvider backup = new AiProvider() {
            public String id() { return "claude"; }
            public reactor.core.publisher.Flux<String> stream(AiRequest request) {
                next.incrementAndGet();
                return reactor.core.publisher.Flux.just("답변");
            }
        };
        var router = new AiProviderRouter(settings, List.of(provider("codex"), backup));
        StepVerifier.create(router.route(new AiRequest("질문", ""), text -> text, id -> id))
                .expectNext("답변", "claude").expectComplete().verify(Duration.ofSeconds(6));
        assertThat(requests).hasValue(1);
        assertThat(next).hasValue(1);
    }

    private static String sse(String lines) {
        return lines.lines().map(line -> "data: " + line + "\n\n").reduce("", String::concat);
    }
}
