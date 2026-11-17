package com.mychatbot.knowledge;

import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class KnowledgeFolderController {
    private final KnowledgeFolders folders;
    public KnowledgeFolderController(KnowledgeFolders folders) { this.folders = folders; }
    public record CreateRequest(String parentPath, String name) {}

    @GetMapping("/api/knowledge/folders")
    public Mono<ResponseEntity<KnowledgeFolders.Snapshot>> list(Authentication auth) {
        return respond(() -> folders.list(auth));
    }
    @PostMapping("/api/knowledge/folders")
    public Mono<ResponseEntity<KnowledgeFolders.FolderRequest>> submit(@RequestBody CreateRequest request, Authentication auth) {
        return respond(() -> folders.submit(request.parentPath(), request.name(), auth));
    }
    @PostMapping("/api/knowledge/admin/folders/{id}/approve")
    public Mono<ResponseEntity<KnowledgeFolders.FolderRequest>> approve(@PathVariable String id, Authentication auth) {
        return respond(() -> folders.review(id, true, auth));
    }
    @PostMapping("/api/knowledge/admin/folders/{id}/reject")
    public Mono<ResponseEntity<KnowledgeFolders.FolderRequest>> reject(@PathVariable String id, Authentication auth) {
        return respond(() -> folders.review(id, false, auth));
    }
    private static <T> Mono<ResponseEntity<T>> respond(Supplier<T> action) {
        return Mono.fromSupplier(() -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action.get()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
