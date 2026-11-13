package com.mychatbot.knowledge;

import com.mychatbot.security.AccessContext;
import com.mychatbot.security.SessionController;
import com.mychatbot.ai.AiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.io.IOException;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ObjectMapper json = new ObjectMapper();

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

    public record UploadRequest(SubmitRequest proposal, List<String> descriptions) {}
    public record ProjectRequest(String id) {}

    @PostMapping(value = "/api/knowledge/admin/projects", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ProjectRequest>> createProject(@RequestBody ProjectRequest request, Authentication auth) {
        return respond(() -> {
            if (!ProjectCatalog.validId(request.id())) throw new AiException("PROPOSAL_INVALID", "프로젝트 ID를 확인해 주세요.");
            if (!SessionController.canCreateProject(auth, request.id())) {
                throw new AiException("PROPOSAL_FORBIDDEN", "이 프로젝트를 생성할 권한이 없어요.");
            }
            knowledge.createProject(request.id());
            return request;
        });
    }

    @PostMapping(value = "/api/knowledge/proposals", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<KnowledgeProposal>> upload(@RequestPart("metadata") String metadata,
            @RequestPart("files") List<Part> parts, Authentication auth) {
        if (parts.isEmpty() || parts.size() > 3 || parts.stream().anyMatch(part -> !(part instanceof FilePart))) {
            throw new AiException("PROPOSAL_INVALID", "파일은 최대 3개까지 올릴 수 있어요.");
        }
        UploadRequest request;
        try { request = json.readValue(metadata, UploadRequest.class); }
        catch (IOException ex) { throw new AiException("PROPOSAL_INVALID", "첨부파일 설명을 확인해 주세요."); }
        if (request == null || request.proposal() == null || request.descriptions() == null || request.descriptions().size() != parts.size()) {
            throw new AiException("PROPOSAL_INVALID", "첨부파일마다 설명을 확인해 주세요.");
        }
        var files = parts.stream().map(FilePart.class::cast).toList();
        return Mono.usingWhen(Mono.fromCallable(() -> Files.createTempDirectory("my-chat-bot-upload-"))
                        .subscribeOn(Schedulers.boundedElastic()), directory -> {
            var uploads = java.util.stream.IntStream.range(0, files.size())
                    .mapToObj(i -> new KnowledgeService.Upload(directory.resolve(Integer.toString(i)),
                            files.get(i).filename(), request.descriptions().get(i))).toList();
            return reactor.core.publisher.Flux.range(0, files.size())
                    .concatMap(i -> files.get(i).transferTo(uploads.get(i).path())).then(
                            respond(() -> knowledge.submit(request.proposal(), AccessContext.from(auth), uploads)));
        }, directory -> Mono.fromRunnable(() -> {
            try {
                for (int i = 0; i < files.size(); i++) Files.deleteIfExists(directory.resolve(Integer.toString(i)));
                Files.deleteIfExists(directory);
            } catch (IOException ex) {
                org.slf4j.LoggerFactory.getLogger(KnowledgeProposalController.class).warn("Upload staging cleanup failed");
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/api/knowledge/proposals/{id}/attachments/{documentId}")
    public Mono<ResponseEntity<byte[]>> reviewAttachment(@PathVariable String id, @PathVariable String documentId,
            @RequestParam String version, Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_KNOWLEDGE_ADMIN".equals(a.getAuthority()));
        return Mono.fromCallable(() -> SourceDownloadController.fileResponse(
                knowledge.proposalAttachment(id, documentId, version, AccessContext.from(auth), admin)))
                .subscribeOn(Schedulers.boundedElastic());
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
