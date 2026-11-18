package com.mychatbot.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mychatbot.ai.AiException;
import com.mychatbot.security.AccessContext;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class DocumentBrowserController {
    private final KnowledgeService knowledge;
    private final ObjectMapper json = new ObjectMapper();
    public DocumentBrowserController(KnowledgeService knowledge) { this.knowledge = knowledge; }

    @GetMapping("/api/knowledge/documents")
    public Mono<ResponseEntity<List<KnowledgeService.DocumentInfo>>> list(@RequestParam(defaultValue = "") String folderPath,
            @RequestParam(defaultValue = "") String query, Authentication auth) {
        return respond(() -> knowledge.documents(folderPath, query, AccessContext.from(auth)));
    }

    @GetMapping("/api/knowledge/documents/{id}/versions")
    public Mono<ResponseEntity<List<DocumentHistory.Version>>> versions(@PathVariable String id,
            @RequestParam(required = false) String version, Authentication auth) {
        return respond(() -> knowledge.versions(id, version, AccessContext.from(auth)));
    }

    @GetMapping("/api/knowledge/documents/{id}/versions/{version}")
    public Mono<ResponseEntity<byte[]>> previous(@PathVariable String id, @PathVariable String version, Authentication auth) {
        return Mono.fromCallable(() -> SourceDownloadController.fileResponse(knowledge.previousVersion(id, version, AccessContext.from(auth))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record RestoreRequest(String version, String restoreVersion, com.fasterxml.jackson.databind.JsonNode consent) {}
    @PostMapping(value = "/api/knowledge/documents/{id}/restore", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<KnowledgeService.DocumentInfo>> restore(@PathVariable String id,
            @RequestBody RestoreRequest request, Authentication auth) {
        if (auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_KNOWLEDGE_ADMIN"))) {
            throw new AiException("PROPOSAL_FORBIDDEN", "관리자 권한이 필요해요.");
        }
        return respond(() -> knowledge.restore(id, request.version(), request.restoreVersion(), request.consent() != null && request.consent().isBoolean() && request.consent().booleanValue(), AccessContext.from(auth)));
    }

    @PostMapping(value = "/api/knowledge/replacements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<KnowledgeProposal>> replacement(@RequestPart("metadata") String metadata,
            @RequestPart("file") FilePart file, Authentication auth) {
        KnowledgeProposalController.SubmitRequest request;
        try { request = json.readValue(metadata, KnowledgeProposalController.SubmitRequest.class); }
        catch (IOException ex) { throw new AiException("PROPOSAL_INVALID", "수정 파일 정보를 확인해 주세요."); }
        if (request == null) throw new AiException("PROPOSAL_INVALID", "수정 파일 정보를 확인해 주세요.");
        return Mono.usingWhen(Mono.fromCallable(() -> Files.createTempFile("knowledge-replacement-", ".tmp"))
                .subscribeOn(Schedulers.boundedElastic()), temporary -> file.transferTo(temporary).then(
                        respond(() -> knowledge.submitReplacement(request, AccessContext.from(auth),
                                new KnowledgeService.Upload(temporary, file.filename(), "수정 파일")))),
                temporary -> Mono.fromRunnable(() -> {
                    try { Files.deleteIfExists(temporary); }
                    catch (IOException ex) { org.slf4j.LoggerFactory.getLogger(DocumentBrowserController.class).warn("Replacement staging cleanup failed"); }
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private static <T> Mono<ResponseEntity<T>> respond(Supplier<T> task) {
        return Mono.fromSupplier(() -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(task.get()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
