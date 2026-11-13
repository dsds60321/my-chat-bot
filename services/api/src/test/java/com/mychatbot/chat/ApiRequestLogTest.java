package com.mychatbot.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.net.URI;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ApiRequestLogTest {
    @Test void recordsSafeRouteStatusAndStreamOutcomeWithoutPayloads(CapturedOutput output) {
        ApiRequestLog filter = new ApiRequestLog();
        var chat = MockServerWebExchange.from(MockServerHttpRequest.post("/api/chat/stream?q=private-query")
                .header("Authorization", "private-header").body("private-body"));
        filter.filter(chat, exchange -> {
            String id = exchange.getResponse().getHeaders().getFirst(ApiRequestLog.HEADER);
            assertThat(id).isNotBlank().isEqualTo(ApiRequestLog.requestId(exchange));
            ApiRequestLog.streamTerminal(id, new StreamEvent("error", new StreamEvent.Failure(
                    "RATE_LIMITED", "private-error-message", 30)));
            return exchange.getResponse().setComplete();
        }).block();
        var unknown = MockServerWebExchange.from(MockServerHttpRequest.method(
                HttpMethod.valueOf("PRIVATE-METHOD"), URI.create("/api/private-path")));
        StepVerifier.create(filter.filter(unknown, exchange -> Mono.error(new ResponseStatusException(
                HttpStatus.NOT_FOUND, "private-error-details")))).expectError(ResponseStatusException.class).verify();
        var cancelled = MockServerWebExchange.from(MockServerHttpRequest.get("/api/search"));
        StepVerifier.create(filter.filter(cancelled, exchange -> Mono.never())).thenCancel().verify();

        assertThat(output.getOut())
                .contains("route=/api/chat/stream status=200", "outcome=error code=RATE_LIMITED",
                        "method=OTHER route=/api/** status=404", "route=/api/search status=-", "outcome=cancel")
                .doesNotContain("private-query", "private-header", "private-body", "private-error-message",
                        "private-path", "private-error-details", "PRIVATE-METHOD");
    }
}
