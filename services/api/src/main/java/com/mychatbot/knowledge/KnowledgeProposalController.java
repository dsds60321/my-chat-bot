package com.mychatbot.knowledge;

import com.mychatbot.security.AccessContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class KnowledgeProposalController {
    private final KnowledgeService knowledge;

    public KnowledgeProposalController(KnowledgeService knowledge) { this.knowledge = knowledge; }

    public record DraftRequest(String instruction, String project, String sourceDocumentId, String sourceVersion) {
        @Override public String toString() { return "DraftRequest[content omitted]"; }
    }
    public record SubmitRequest(String project, String sourceDocumentId, String sourceVersion, String title,
                                String content, JsonNode consent) {
        @Override public String toString() { return "SubmitRequest[content omitted]"; }
    }

    @PostMapping(value = "/api/knowledge/proposals/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<KnowledgeProposal.Draft>> draft(@RequestBody DraftRequest request, Authentication auth) {
        AccessContext access = AccessContext.from(auth);
        return respond(() -> knowledge.draft(request, access));
    }

    @PostMapping(value = "/api/knowledge/proposals", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<KnowledgeProposal>> submit(@RequestBody SubmitRequest request, Authentication auth) {
        AccessContext access = AccessContext.from(auth);
        return respond(() -> knowledge.submit(request, access));
    }

    @GetMapping("/api/knowledge/proposals")
    public Mono<ResponseEntity<List<KnowledgeProposal>>> own(Authentication auth) {
        return respond(() -> knowledge.ownProposals(auth.getName()));
    }

    @PostMapping(value = "/api/knowledge/proposals/{id}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<KnowledgeProposal>> withdraw(@PathVariable String id, Authentication auth) {
        return respond(() -> knowledge.withdrawProposal(id, auth.getName()));
    }

    @GetMapping("/api/knowledge/admin/proposals")
    public Mono<ResponseEntity<List<KnowledgeProposal>>> pending(Authentication auth) {
        return respond(() -> knowledge.pendingProposals(adminAccess(auth)));
    }

    @PostMapping(value = "/api/knowledge/admin/proposals/{id}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<KnowledgeProposal>> approve(@PathVariable String id, Authentication auth) {
        return respond(() -> knowledge.reviewProposal(id, adminAccess(auth), KnowledgeProposal.State.APPROVED));
    }

    @PostMapping(value = "/api/knowledge/admin/proposals/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<KnowledgeProposal>> reject(@PathVariable String id, Authentication auth) {
        return respond(() -> knowledge.reviewProposal(id, adminAccess(auth), KnowledgeProposal.State.REJECTED));
    }

    private static AccessContext adminAccess(Authentication auth) {
        if (auth.getAuthorities().stream().noneMatch(a -> "ROLE_KNOWLEDGE_ADMIN".equals(a.getAuthority()))) {
            throw new com.mychatbot.ai.AiException("PROPOSAL_FORBIDDEN", "관리자 권한이 필요해요.");
        }
        return AccessContext.from(auth);
    }

    private static <T> Mono<ResponseEntity<T>> respond(Supplier<T> task) {
        return Mono.fromSupplier(() -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(task.get()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
