package com.mychatbot.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mychatbot.ai.AiException;
import com.mychatbot.security.AccessContext;
import java.nio.file.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class FolderProposalTest {
    @TempDir Path temp;
    private Path root() throws Exception { return temp.toRealPath().resolve("source"); }
    private KnowledgeService open() throws Exception { return new KnowledgeService(root(), temp.toRealPath().resolve("index")); }
    @Test void newKnowledgeRetainsFolderAndAllItsRoutesRespectSubtreeExclusionsAfterRestart() throws Exception {
        Files.createDirectories(root().resolve("user/ghkang/업무 자료"));
        var publisher = new AccessContext("publisher", Set.of("user"), true);
        var limited = new AccessContext("employee", Set.of("user"), false, Set.of("user/ghkang"));
        var request = new KnowledgeProposalController.SubmitRequest("user", null, null, "업무 절차",
                "FOLDER_KNOWLEDGE_LOOKUP 확인 절차", BooleanNode.TRUE, "user/ghkang/업무 자료");
        String document; String version;
        try (var service = open()) {
            var draft = service.draft(new KnowledgeProposalController.DraftRequest("업무 절차 등록해 줘", "user", null, null,
                    "user/ghkang/업무 자료"), publisher);
            assertThat(draft.folderPath()).isEqualTo("user/ghkang/업무 자료");
            var proposal = service.submit(request, publisher);
            assertThat(proposal.folderPath()).isEqualTo(draft.folderPath());
            assertThat(service.ownProposals(limited)).isEmpty();
            var hit = service.search("FOLDER_KNOWLEDGE_LOOKUP", publisher, 4).get(0);
            document = hit.documentId(); version = hit.version();
            assertThat(service.search("FOLDER_KNOWLEDGE_LOOKUP", limited, 4)).isEmpty();
            assertThat(service.download(document, version, limited)).isEmpty();
            assertThatThrownBy(() -> service.submit(request, limited)).isInstanceOf(AiException.class);
            assertThatThrownBy(() -> service.submit(new KnowledgeProposalController.SubmitRequest("user", null, null,
                    "잘못된 위치", "FOLDER_KNOWLEDGE_LOOKUP 확인", BooleanNode.TRUE, "paye2u"), publisher)).isInstanceOf(AiException.class);
            assertThatThrownBy(() -> service.submit(new KnowledgeProposalController.SubmitRequest("user", null, null,
                    "잘못된 위치", "FOLDER_KNOWLEDGE_LOOKUP 확인", BooleanNode.TRUE, "user/../outside"), publisher)).isInstanceOf(AiException.class);
        }
        try (var service = open()) {
            assertThat(service.search("FOLDER_KNOWLEDGE_LOOKUP", publisher, 4)).hasSize(1);
            assertThat(service.search("FOLDER_KNOWLEDGE_LOOKUP", limited, 4)).isEmpty();
            assertThat(service.download(document, version, limited)).isEmpty();
        }
    }
    @Test void nestedLegacyAmendmentWithoutFolderFieldStillApprovesAndNewConflictingFolderIsRejected() throws Exception {
        Path source = root().resolve("user/ghkang/legacy.md");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "# 원본\nLEGACY_FOLDER_LOOKUP 확인 절차");
        var author = new AccessContext("author", Set.of("user"));
        var admin = new AccessContext("admin", Set.of("user"));
        String id;
        try (var service = open()) {
            var hit = service.search("LEGACY_FOLDER_LOOKUP", author, 4).get(0);
            var request = new KnowledgeProposalController.SubmitRequest("user", hit.documentId(), hit.version(),
                    "보완", "LEGACY_AMENDMENT_LOOKUP 확인 절차", BooleanNode.TRUE);
            id = service.submit(request, author).id();
            assertThatThrownBy(() -> service.submit(new KnowledgeProposalController.SubmitRequest("user", hit.documentId(), hit.version(),
                    "보완", "LEGACY_AMENDMENT_LOOKUP 확인 절차", BooleanNode.TRUE, "user"), author)).isInstanceOf(AiException.class);
        }
        Path stored = root().resolveSibling("knowledge-submissions").resolve("proposals.json");
        ObjectMapper json = new ObjectMapper();
        var records = json.readTree(stored.toFile());
        for (var record : records) ((ObjectNode) record).remove("folderPath");
        Files.writeString(stored, json.writeValueAsString(records));
        try (var service = open()) {
            assertThat(service.reviewProposal(id, admin, KnowledgeProposal.State.APPROVED).state()).isEqualTo(KnowledgeProposal.State.APPROVED);
            assertThat(service.search("LEGACY_AMENDMENT_LOOKUP", author, 4)).isNotEmpty();
        }
    }
}
