package com.mychatbot.chat;

import com.mychatbot.ai.AiProperties;
import com.mychatbot.ai.AiProviderRouter;
import com.mychatbot.ai.MockAiProvider;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ChatControllerLogTest {
    @Test void usesAuthenticatedLoginAndPeerAddressWithoutTrustingForwardedHeader(CapturedOutput output) {
        var settings = new AiProperties();
        settings.setProvider("mock");
        var service = new ChatService(new AiProviderRouter(settings, List.of(new MockAiProvider())), (q, p, l) -> List.of());
        var controller = new ChatController(service, new RequestLimiter(12));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/chat/stream?private-query")
                .header("X-Forwarded-For", "spoofed-ip")
                .remoteAddress(new InetSocketAddress("127.0.0.7", 1234)));
        var login = new UsernamePasswordAuthenticationToken("logged-in-user", "private-password", List.of());
        new ApiRequestLog().filter(exchange, current -> controller.stream(new ChatRequest("질문", null), login, current).then()).block();
        String id = exchange.getResponse().getHeaders().getFirst(ApiRequestLog.HEADER);
        assertThat(output.getOut()).contains("[" + id + "] user_request", "[" + id + "] user_response",
                "requestedAt=", "userId=logged-in-user method=POST url=/api/chat/stream clientIp=127.0.0.7")
                .doesNotContain("spoofed-ip", "private-password", "private-query");
    }
}
