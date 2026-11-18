package com.mychatbot.chat;

import com.mychatbot.security.AccessContext;
import com.mychatbot.logging.ContentLog;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {
    private final ChatService chat;
    private final RequestLimiter limiter;
    public ChatController(ChatService chat, RequestLimiter limiter) { this.chat = chat; this.limiter = limiter; }
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@Valid @RequestBody ChatRequest request, Authentication authentication,
            ServerWebExchange exchange) {
        AccessContext access = AccessContext.from(authentication);
        RequestLimiter.Lease lease = limiter.acquire(access.userId());
        String requestId = ApiRequestLog.requestId(exchange);
        var peer = exchange.getRequest().getRemoteAddress();
        var logRequest = new ContentLog.Request(requestId, java.time.Instant.now().toString(), access.userId(),
                exchange.getRequest().getMethod().name(), exchange.getRequest().getPath().value(),
                peer == null || peer.getAddress() == null ? "-" : peer.getAddress().getHostAddress());
        return chat.stream(request, access, requestId)
                .doOnNext(event -> ApiRequestLog.streamTerminal(requestId, event))
                .map(event -> ServerSentEvent.builder(event.data()).event(event.event()).build())
                .doFinally(signal -> lease.close())
                .contextWrite(context -> context.put(ContentLog.REQUEST, logRequest));
    }
}
