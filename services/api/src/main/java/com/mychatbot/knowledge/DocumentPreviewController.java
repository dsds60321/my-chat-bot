package com.mychatbot.knowledge;

import com.mychatbot.chat.RequestLimiter;
import com.mychatbot.security.AccessContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class DocumentPreviewController {
    private final DocumentPreviewService preview;
    private final RequestLimiter limiter;

    public DocumentPreviewController(DocumentPreviewService preview,
            @Value("${knowledge.preview-requests-per-minute:60}") int requestsPerMinute) {
        this.preview = preview;
        this.limiter = new RequestLimiter(requestsPerMinute);
    }

    @GetMapping("/api/knowledge/documents/{documentId}/preview")
    public Mono<ResponseEntity<DocumentPreviewService.Preview>> preview(@PathVariable String documentId,
            @RequestParam String version, Authentication auth) {
        var access = AccessContext.from(auth);
        return Mono.using(() -> limiter.acquire(access.userId()), lease -> Mono.fromCallable(() -> preview.preview(documentId, version, access)
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseGet(() -> ResponseEntity.notFound().build())).subscribeOn(Schedulers.boundedElastic()), RequestLimiter.Lease::close);
    }

    @GetMapping("/api/knowledge/documents/{documentId}/preview/pdf")
    public Mono<ResponseEntity<byte[]>> pdf(@PathVariable String documentId, @RequestParam String version, Authentication auth) {
        var access = AccessContext.from(auth);
        return Mono.using(() -> limiter.acquire(access.userId()), lease -> Mono.fromCallable(() -> preview.pdf(documentId, version, access)
                .map(bytes -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("preview.pdf").build().toString())
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "sandbox; default-src 'none'")
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build())).subscribeOn(Schedulers.boundedElastic()), RequestLimiter.Lease::close);
    }
}
