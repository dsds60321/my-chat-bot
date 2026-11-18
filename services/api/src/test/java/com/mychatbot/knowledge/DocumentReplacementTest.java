package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mychatbot.security.AccessContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentReplacementTest {
    @TempDir Path temp;
    final AccessContext user = new AccessContext("alice", Set.of("common"));
    final AccessContext admin = new AccessContext("admin", Set.of("common"), true);
    final ObjectMapper json = new ObjectMapper();

    private Path source() throws Exception {
        Path path = temp.toRealPath().resolve("source/common/guide.md");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "# Guide\nORIGINAL_DOCUMENT");
        return path;
    }
    private KnowledgeService service() throws Exception {
        Path root = temp.toRealPath();
        return new KnowledgeService(root.resolve("source"), root.resolve("index"), root.resolve("store"));
    }
    private KnowledgeProposalController.SubmitRequest request(KnowledgeService.DocumentInfo document) {
        return new KnowledgeProposalController.SubmitRequest(document.project(), document.documentId(), document.version(),
                "수정 파일", "내용 갱신", json.getNodeFactory().booleanNode(true), "common");
    }
    private KnowledgeService.Upload upload(String filename, String content) throws Exception {
        Path path = Files.createTempFile(temp, "upload-", ".tmp"); Files.writeString(path, content);
        return new KnowledgeService.Upload(path, filename, "수정 파일");
    }

    @Test void replacementAcceptsMissingChangeSummaryButKeepsFileAndConsentValidation() throws Exception {
        Path original = source();
        try (var knowledge = service()) {
            var document = knowledge.documents("common", "", user).get(0);
            var request = new KnowledgeProposalController.SubmitRequest(document.project(), document.documentId(), document.version(),
                    "수정 파일", null, json.getNodeFactory().booleanNode(true), "common");
            var saved = knowledge.submitReplacement(request, user, upload("edited.md", "# Guide\nUPDATED_DOCUMENT"));
            assertThat(saved.content()).isEqualTo("수정 파일로 교체");
            assertThat(saved.state()).isEqualTo(KnowledgeProposal.State.PENDING_APPROVAL);
            assertThat(Files.readString(original)).contains("ORIGINAL_DOCUMENT");
            var noConsent = new KnowledgeProposalController.SubmitRequest(document.project(), document.documentId(), document.version(),
                    "수정 파일", null, json.getNodeFactory().booleanNode(false), "common");
            assertThatThrownBy(() -> knowledge.submitReplacement(noConsent, user, upload("edited.md", "safe")))
                    .hasMessageContaining("동의");
        }
    }

    @Test void approvalReplacesSameFilePreservesHistoryAndRestoresAcrossRestart() throws Exception {
        Path original = source();
        String id;
        String before;
        String after;
        try (var knowledge = service()) {
            var document = knowledge.documents("common", "guide", user).get(0);
            id = document.documentId(); before = document.version();
            var proposal = knowledge.submitReplacement(request(document), user, upload("edited.md", "# Guide\nUPDATED_DOCUMENT"));
            assertThat(proposal.kind()).isEqualTo(KnowledgeProposal.Kind.REPLACEMENT);
            assertThat(proposal.state()).isEqualTo(KnowledgeProposal.State.PENDING_APPROVAL);
            assertThat(Files.readString(original)).contains("ORIGINAL_DOCUMENT");
            knowledge.reviewProposal(proposal.id(), admin, KnowledgeProposal.State.APPROVED);
            assertThat(Files.readString(original)).contains("UPDATED_DOCUMENT");
            assertThat(knowledge.search("ORIGINAL_DOCUMENT", Set.of("common"), 4)).isEmpty();
            assertThat(knowledge.search("UPDATED_DOCUMENT", Set.of("common"), 4)).isNotEmpty();
            assertThat(knowledge.status().documents()).hasSize(1);
            assertThat(knowledge.versions(id, null, user)).extracting(DocumentHistory.Version::version).containsExactly(before);
            assertThat(new String(knowledge.previousVersion(id, before, user).orElseThrow().bytes(), StandardCharsets.UTF_8)).contains("ORIGINAL_DOCUMENT");
            assertThatThrownBy(() -> knowledge.withdrawProposal(proposal.id(), user)).hasMessageContaining("복원");
            after = knowledge.documents("common", "", user).get(0).version();
        }
        try (var knowledge = service()) {
            assertThat(knowledge.versions(id, after, user)).hasSize(1);
            var restored = knowledge.restore(id, after, before, true, admin);
            assertThat(restored.version()).isEqualTo(before);
            assertThat(Files.readString(original)).contains("ORIGINAL_DOCUMENT");
            assertThat(knowledge.versions(id, before, user)).extracting(DocumentHistory.Version::version).contains(before, after);
        }
    }

    @Test void authorizationConsentFormatAndStaleVersionsAreEnforced() throws Exception {
        Path original = source();
        var denied = new AccessContext("alice", Set.of("common"), false, Set.of("common/guide.md"));
        try (var knowledge = service()) {
            var doc = knowledge.documents("common", "", user).get(0);
            assertThat(knowledge.documents("common", "", denied)).isEmpty();
            assertThatThrownBy(() -> knowledge.versions(doc.documentId(), doc.version(), denied)).hasMessageContaining("찾을");
            assertThatThrownBy(() -> knowledge.previousVersion(doc.documentId(), doc.version(), new AccessContext("no", Set.of("other")))).hasMessageContaining("찾을");
            assertThatThrownBy(() -> knowledge.submitReplacement(request(doc), denied, upload("x.md", "safe"))).hasMessageContaining("찾을");
            assertThatThrownBy(() -> knowledge.submitReplacement(request(doc), user, upload("x.txt", "safe"))).hasMessageContaining("같은 형식");
            assertThatThrownBy(() -> knowledge.submitReplacement(request(doc), user, upload("x.md", "api_key=synthetic_secret"))).hasMessageContaining("민감정보");
            var proposal = knowledge.submitReplacement(request(doc), user, upload("x.md", "# Safe\nPROPOSED_CONTENT"));
            Files.writeString(original, "# External\nEXTERNAL_EDIT");
            assertThatThrownBy(() -> knowledge.reviewProposal(proposal.id(), admin, KnowledgeProposal.State.APPROVED)).hasMessageContaining("변경");
            assertThat(Files.readString(original)).contains("EXTERNAL_EDIT");
            assertThatThrownBy(() -> knowledge.submitReplacement(request(doc), user, upload("x.md", "# Safe\nNEXT_CONTENT"))).hasMessageContaining("변경");
            assertThatThrownBy(() -> knowledge.restore(doc.documentId(), doc.version(), doc.version(), false, admin)).hasMessageContaining("동의");
        }
    }

    @Test void directAdminReplacementAndSaveFailureRetainOriginalBytes() throws Exception {
        Path original = source();
        try (var knowledge = service()) {
            var doc = knowledge.documents("common", "", user).get(0);
            var proposal = knowledge.submitReplacement(request(doc), admin, upload("x.md", "# Safe\nADMIN_UPDATED"));
            assertThat(proposal.state()).isEqualTo(KnowledgeProposal.State.APPROVED);
            assertThat(Files.readString(original)).contains("ADMIN_UPDATED");
        }
        Path isolated = temp.toRealPath().resolve("helper"); Files.createDirectories(isolated);
        Path source = isolated.resolve("doc.md"); Files.writeString(source, "before");
        Path store = isolated.resolve("store"); Files.createDirectories(store);
        var history = new DocumentHistory(isolated, store);
        assertThatThrownBy(() -> history.replace(hash("doc.md"), "doc.md", hash("before"), "after".getBytes(), "proposal", "common", () -> { throw new java.io.IOException("disk-full"); }))
                .hasMessageContaining("disk-full");
        assertThat(Files.readString(source)).isEqualTo("before");
        assertThat(history.previous(hash("doc.md"), hash("before"))).isEqualTo("before".getBytes());
        assertThat(store.resolve("document-transaction/intent.json")).doesNotExist();
    }

    @Test void interruptedCommittedTransactionKeepsApprovedBytesAndUncommittedOneRollsBack() throws Exception {
        Path original = source();
        Path base = temp.toRealPath();
        String old = Files.readString(original);
        String replacement = "# Guide\nCOMMITTED_CONTENT";
        KnowledgeProposal approved;
        try (var knowledge = service()) {
            var doc = knowledge.documents("common", "", user).get(0);
            approved = knowledge.submitReplacement(request(doc), admin, upload("x.md", replacement));
        }
        String id = approved.sourceDocumentId();
        Path tx = base.resolve("store/document-transaction"); Files.createDirectories(tx);
        Files.write(tx.resolve("next"), replacement.getBytes());
        Files.writeString(tx.resolve("intent.json"), json.writeValueAsString(new DocumentHistory.Intent(id, "common/guide.md", hash(old), hash(replacement), approved.id())));
        try (var knowledge = service()) {
            assertThat(knowledge.status().healthy()).isTrue();
            assertThat(Files.readString(original)).isEqualTo(replacement);
            var doc = knowledge.documents("common", "", user).get(0);
            knowledge.submitReplacement(request(doc), admin, upload("next.md", "# Guide\nNEXT_COMMITTED"));
            assertThat(Files.readString(original)).contains("NEXT_COMMITTED");
        }
        Files.write(tx.resolve("next"), "# Guide\nUNCOMMITTED".getBytes());
        String current = Files.readString(original);
        Path history = base.resolve("store/document-history").resolve(id).resolve(hash(current));
        Files.writeString(history, current);
        Files.writeString(original, "# Guide\nUNCOMMITTED");
        Files.writeString(tx.resolve("intent.json"), json.writeValueAsString(new DocumentHistory.Intent(id, "common/guide.md", hash(current), hash("# Guide\nUNCOMMITTED"), "uncommitted-id")));
        try (var knowledge = service()) {
            assertThat(knowledge.status().healthy()).isTrue();
            assertThat(Files.readString(original)).isEqualTo(current);
        }
    }

    @Test void replacementCannotChangeProjectAndExternalReclassificationCannotExposeOldHistory() throws Exception {
        Path base = temp.toRealPath();
        Path path = base.resolve("source/notes/guide.md");
        Files.createDirectories(path.getParent());
        Files.createDirectories(base.resolve("source/syslink"));
        Files.createDirectories(base.resolve("source/paye2u"));
        Files.writeString(path, "# Internal\nsyslink ORIGINAL_SYS_CONTENT");
        var scoped = new AccessContext("editor", Set.of("syslink", "notes"), true);
        var later = new AccessContext("later", Set.of("paye2u", "notes"), true);
        try (var knowledge = service()) {
            var doc = knowledge.documents("notes", "", scoped).get(0);
            var request = new KnowledgeProposalController.SubmitRequest(doc.project(), doc.documentId(), doc.version(),
                    "Update", "내용 갱신", json.getNodeFactory().booleanNode(true), "notes");
            assertThatThrownBy(() -> knowledge.submitReplacement(request, scoped, upload("guide.md", "# Internal\npaye2u OTHER_CONTENT")))
                    .hasMessageContaining("저장");
            assertThat(Files.readString(path)).contains("ORIGINAL_SYS_CONTENT");
            assertThat(knowledge.documents("notes", "", later)).isEmpty();
            knowledge.submitReplacement(request, scoped, upload("guide.md", "# Internal\nsyslink UPDATED_SYS_CONTENT"));
            Files.writeString(path, "# External\npaye2u CURRENT_PAY_CONTENT");
            var changed = knowledge.documents("notes", "", later).get(0);
            assertThatThrownBy(() -> knowledge.versions(changed.documentId(), changed.version(), later)).hasMessageContaining("찾을");
            assertThatThrownBy(() -> knowledge.previousVersion(changed.documentId(), doc.version(), later)).hasMessageContaining("찾을");
        }
    }

    @Test void powerPointIsExtractedAndSearchableWithoutNewDependencies() throws Exception {
        Path file = temp.toRealPath().resolve("source/common/slides.pptx");
        Files.createDirectories(file.getParent());
        try (var slides = new org.apache.poi.xslf.usermodel.XMLSlideShow()) {
            slides.createSlide().createTextBox().setText("PRESENTATION_DOCUMENT");
            try (var output = Files.newOutputStream(file)) { slides.write(output); }
        }
        try (var knowledge = service()) {
            assertThat(knowledge.documents("common", "slides", user)).hasSize(1);
            assertThat(knowledge.search("PRESENTATION_DOCUMENT", user.projects(), 4)).isNotEmpty();
        }
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
