package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mychatbot.security.AccessContext;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentDataRegistrationTest {
    @TempDir Path temporary;
    final AccessContext editor = new AccessContext("editor", Set.of("common"));
    final AccessContext admin = new AccessContext("admin", Set.of("common"), true);
    final AccessContext denied = new AccessContext("excluded", Set.of("common"), false, Set.of("common/work"));
    final String content = "DOCUMENT_DATA_LOOKUP\n계좌번호=12345678901234\n주민번호=9001011234567\n연락처=01012345678\npassword=synthetic-only-value";
    Path root() throws Exception { return temporary.toRealPath().resolve("source"); }
    Path index() throws Exception { return temporary.toRealPath().resolve("index"); }
    Path store() throws Exception { return temporary.toRealPath().resolve("store"); }
    KnowledgeService open() throws Exception {
        Files.createDirectories(root().resolve("common/work"));
        return new KnowledgeService(root(), index(), store());
    }
    KnowledgeProposalController.SubmitRequest request(String id, String version, String text) {
        return new KnowledgeProposalController.SubmitRequest("common", id, version, "연락처 01012345678", text, BooleanNode.TRUE, "common/work");
    }
    Path workbook(String filename, String marker) throws Exception {
        Path path = temporary.resolve(filename);
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Data");
            String[][] values = {{"작업명", "계좌번호", "주민번호", "휴대번호", "Password"},
                    {marker, "12345678901234", "9001011234567", "01012345678", "synthetic-only-value"}};
            for (int i = 0; i < values.length; i++) {
                var row = sheet.createRow(i);
                for (int j = 0; j < values[i].length; j++) row.createCell(j).setCellValue(values[i][j]);
            }
            try (var out = Files.newOutputStream(path)) { book.write(out); }
        }
        return path;
    }

    @Test void personalDataUploadStillRequiresApprovalAndPathAccess() throws Exception {
        Path upload = workbook("upload.xlsx", "REGISTERED_ROWS_LOOKUP");
        byte[] bytes = Files.readAllBytes(upload);
        try (var knowledge = open()) {
            var submitted = knowledge.submit(request(null, null, content), editor,
                    List.of(new KnowledgeService.Upload(upload, "01012345678.xlsx", "password=synthetic-only-description")));
            assertThat(submitted.state()).isEqualTo(KnowledgeProposal.State.PENDING_APPROVAL);
            assertThat(knowledge.search("REGISTERED_ROWS_LOOKUP", editor, 20)).isEmpty();
            var attachment = submitted.attachments().get(0);
            assertThat(knowledge.proposalAttachment(submitted.id(), attachment.documentId(), attachment.version(), editor, false)).isPresent();
            assertThat(knowledge.proposalAttachment(submitted.id(), attachment.documentId(), attachment.version(), denied, false)).isEmpty();
            knowledge.reviewProposal(submitted.id(), admin, KnowledgeProposal.State.APPROVED);
            var hits = knowledge.search("REGISTERED_ROWS_LOOKUP", editor, 20);
            assertThat(hits).isNotEmpty().allSatisfy(hit -> assertThat(hit.text()).contains("12345678901234", "9001011234567", "synthetic-only-value"));
            assertThat(knowledge.search("REGISTERED_ROWS_LOOKUP", denied, 20)).isEmpty();
            assertThat(knowledge.download(attachment.documentId(), attachment.version(), editor).orElseThrow().bytes()).isEqualTo(bytes);
            assertThat(knowledge.download(attachment.documentId(), attachment.version(), denied)).isEmpty();
            var preview = new DocumentPreviewService(knowledge, "unused", 1).preview(attachment.documentId(), attachment.version(), editor).orElseThrow();
            assertThat(preview.kind()).isEqualTo("sheets");
            assertThat(preview.sheets().get(0).rows().get(1)).contains("12345678901234", "9001011234567", "synthetic-only-value");
            assertThat(knowledge.status().quarantine()).isZero();
        }
    }

    @Test void directWorkbookDataSupportsReplacementAndRestoreWithCurrentVersionChecks() throws Exception {
        Files.createDirectories(root().resolve("common/work"));
        Path original = root().resolve("common/work/original.xlsx");
        Files.copy(workbook("before.xlsx", "BEFORE_ROWS_LOOKUP"), original);
        byte[] bytes = Files.readAllBytes(original);
        try (var knowledge = open()) {
            var source = knowledge.documents("common/work", "original.xlsx", editor).get(0);
            var replacement = knowledge.submitReplacement(request(source.documentId(), source.version(), ""), editor,
                    new KnowledgeService.Upload(workbook("after.xlsx", "AFTER_ROWS_LOOKUP"), "after.xlsx", ""));
            assertThat(replacement.state()).isEqualTo(KnowledgeProposal.State.PENDING_APPROVAL);
            assertThat(Files.readAllBytes(original)).isEqualTo(bytes);
            knowledge.reviewProposal(replacement.id(), admin, KnowledgeProposal.State.APPROVED);
            var updated = knowledge.documents("common/work", "original.xlsx", editor).get(0);
            assertThat(knowledge.search("AFTER_ROWS_LOOKUP", editor, 20)).isNotEmpty();
            assertThat(knowledge.download(source.documentId(), source.version(), editor)).isEmpty();
            assertThat(knowledge.download(updated.documentId(), updated.version(), denied)).isEmpty();
            knowledge.restore(updated.documentId(), updated.version(), source.version(), true, admin);
            assertThat(Files.readAllBytes(original)).isEqualTo(bytes);
            assertThat(knowledge.search("BEFORE_ROWS_LOOKUP", editor, 20)).isNotEmpty();
        }
    }

    @Test void oldQuarantinedManifestIsReindexedWithoutChangingSourceBytes() throws Exception {
        Files.createDirectories(root().resolve("common/work"));
        Path original = root().resolve("common/work/personal.md"); Files.writeString(original, content);
        byte[] bytes = Files.readAllBytes(original);
        try (var knowledge = open()) { assertThat(knowledge.status().healthy()).isTrue(); }
        var json = new ObjectMapper();
        var manifest = json.readTree(index().resolve("manifest.json").toFile());
        ((ObjectNode) manifest.get(0)).put("state", "QUARANTINED").put("chunks", 0);
        Files.writeString(index().resolve("manifest.json"), json.writeValueAsString(manifest));
        Files.writeString(index().resolve("policy.version"), "knowledge-policy-v18-task-evidence");
        try (var knowledge = open()) {
            assertThat(knowledge.search("DOCUMENT_DATA_LOOKUP", editor, 20)).isNotEmpty();
            assertThat(knowledge.status().quarantine()).isZero();
            assertThat(knowledge.documents("common/work", "personal.md", editor)).hasSize(1);
            assertThat(Files.readString(index().resolve("policy.version"))).contains("v20");
            assertThat(Files.readAllBytes(original)).isEqualTo(bytes);
        }
    }
}
