package com.mychatbot.knowledge;

import com.mychatbot.chat.RequestLimiter;
import com.mychatbot.security.AccessContext;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
public class SourceDownloadController {
    private final KnowledgeService knowledge;
    private final RequestLimiter limiter;

    public SourceDownloadController(KnowledgeService knowledge, RequestLimiter limiter) {
        this.knowledge = knowledge;
        this.limiter = limiter;
    }

    @GetMapping("/api/sources/{documentId}")
    public Mono<ResponseEntity<byte[]>> download(@PathVariable String documentId,
            @RequestParam(required = false) String version, Authentication authentication) {
        AccessContext access = AccessContext.from(authentication);
        return Mono.using(() -> limiter.acquire(access.userId()),
                lease -> Mono.fromCallable(() -> fileResponse(knowledge.download(documentId, version, access.projects())))
                        .subscribeOn(Schedulers.boundedElastic()),
                RequestLimiter.Lease::close);
    }

    static ResponseEntity<byte[]> fileResponse(java.util.Optional<KnowledgeService.SourceFile> fileResult) {
        return fileResult.map(file -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(file.bytes().length)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                        .body(file.bytes()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build());
    }
}
