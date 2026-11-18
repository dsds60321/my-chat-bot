package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.mychatbot.security.AccessContext;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedAttachmentEditingTest {
    @TempDir Path temp;
    final AccessContext admin = new AccessContext("admin", Set.of("common"), true);
    final AccessContext editor = new AccessContext("editor", Set.of("common"));
    Path root() throws Exception { return temp.toRealPath().resolve("source"); }
    Path store() throws Exception { return temp.toRealPath().resolve("store"); }
    KnowledgeService open() throws Exception {
        Files.createDirectories(root().resolve("common/work"));
        return new KnowledgeService(root(), temp.toRealPath().resolve("index"), store());
    }
    KnowledgeProposalController.SubmitRequest request(KnowledgeService.DocumentInfo target) {
        return new KnowledgeProposalController.SubmitRequest("common", target == null ? null : target.documentId(), target == null ? null : target.version(),
                target == null ? "양식 안내" : "파일 교체", target == null ? "FORM_ATTACHMENT_LOOKUP 양식 다운로드 안내" : "", BooleanNode.TRUE,
                target == null ? "common/work" : target.path().substring(0, target.path().lastIndexOf('/')));
    }
    Path xlsx(String marker) throws Exception {
        Path file = Files.createTempFile(temp, "form-", ".xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("양식"); sheet.createRow(0).createCell(0).setCellValue(marker);
            try (var out = Files.newOutputStream(file)) { workbook.write(out); }
        }
        return file;
    }
    KnowledgeProposal submit(KnowledgeService knowledge) throws Exception {
        Path sibling = temp.resolve("sibling.txt"); Files.writeString(sibling, "SIBLING_ATTACHMENT_LOOKUP");
        return knowledge.submit(request(null), admin, List.of(new KnowledgeService.Upload(xlsx("BEFORE_ATTACHMENT_LOOKUP"), "등록 양식.xlsx", "양식 설명"),
                new KnowledgeService.Upload(sibling, "지침.txt", "지침")));
    }
    KnowledgeService.DocumentInfo parent(KnowledgeService knowledge) { return knowledge.documents("common/work", "양식", editor).get(0); }
    KnowledgeService.DocumentInfo child(KnowledgeService knowledge) { return parent(knowledge).attachments().stream().filter(file -> file.type().equals("xlsx")).findFirst().orElseThrow(); }

    @Test void attachmentDownloadPreviewPendingApprovalRestartAndRestoreUseCurrentBytes() throws Exception {
        String owner; String id; String beforeVersion; byte[] before; byte[] after;
        try (var knowledge = open()) {
            var proposal = submit(knowledge); owner = proposal.id(); var file = child(knowledge); id = file.documentId(); beforeVersion = file.version();
            before = knowledge.download(id, beforeVersion, editor).orElseThrow().bytes();
            assertThat(file.title()).isEqualTo("등록 양식.xlsx"); assertThat(file.replaceable()).isTrue();
            assertThat(knowledge.download(id, beforeVersion, editor).orElseThrow().filename()).isEqualTo("등록 양식.xlsx");
            assertThat(knowledge.download(proposal.attachments().get(0).documentId(), beforeVersion, editor)).isPresent();
            var other = parent(knowledge).attachments().stream().filter(value -> value.type().equals("txt")).findFirst().orElseThrow();
            byte[] sibling = Files.readAllBytes(root().resolve(other.path()));
            var preview = new DocumentPreviewService(knowledge, "unused", 1).preview(id, beforeVersion, editor).orElseThrow();
            assertThat(preview.kind()).isEqualTo("sheets"); assertThat(preview.sheets().get(0).rows().get(0)).contains("BEFORE_ATTACHMENT_LOOKUP");
            Path updated = xlsx("AFTER_ATTACHMENT_LOOKUP"); after = Files.readAllBytes(updated);
            var pending = knowledge.submitReplacement(request(file), editor, new KnowledgeService.Upload(updated, "수정본.xlsx", ""));
            assertThat(pending.state()).isEqualTo(KnowledgeProposal.State.PENDING_APPROVAL);
            assertThat(knowledge.download(id, beforeVersion, editor).orElseThrow().bytes()).isEqualTo(before);
            knowledge.reviewProposal(pending.id(), admin, KnowledgeProposal.State.APPROVED); knowledge.syncNow(); knowledge.syncNow();
            var changed = child(knowledge); assertThat(changed.documentId()).isEqualTo(id); assertThat(changed.version()).isNotEqualTo(beforeVersion);
            assertThat(knowledge.download(id, beforeVersion, editor)).isEmpty();
            assertThat(knowledge.download(proposal.attachments().get(0).documentId(), beforeVersion, editor)).isEmpty();
            assertThat(knowledge.download(id, changed.version(), editor).orElseThrow().bytes()).isEqualTo(after);
            assertThat(Files.readAllBytes(store().resolve("attachments").resolve(proposal.attachments().get(0).documentId()))).isEqualTo(before);
            assertThat(Files.readAllBytes(root().resolve(other.path()))).isEqualTo(sibling);
            var hits = knowledge.search("AFTER_ATTACHMENT_LOOKUP", editor, 20);
            assertThat(hits).isNotEmpty().allMatch(hit -> hit.documentId().equals(parent(knowledge).documentId()));
            assertThat(hits.get(0).attachments()).anySatisfy(attachment -> {
                assertThat(attachment.documentId()).isEqualTo(id); assertThat(attachment.version()).isEqualTo(changed.version());
                assertThat(attachment.filename()).isEqualTo("등록 양식.xlsx"); assertThat(attachment.bytes()).isEqualTo(after.length);
            });
            assertThat(knowledge.search("BEFORE_ATTACHMENT_LOOKUP", editor, 20)).isEmpty();
        }
        try (var knowledge = open()) {
            var current = child(knowledge); assertThat(current.documentId()).isEqualTo(id);
            assertThat(knowledge.download(id, current.version(), editor).orElseThrow().bytes()).isEqualTo(after);
            assertThat(knowledge.versions(id, current.version(), admin)).isNotEmpty();
            assertThat(knowledge.previousVersion(id, beforeVersion, admin).orElseThrow().filename()).isEqualTo("등록 양식.xlsx");
            knowledge.restore(id, current.version(), beforeVersion, true, admin);
            assertThat(knowledge.download(id, child(knowledge).version(), editor).orElseThrow().bytes()).isEqualTo(before);
            var body = parent(knowledge); Files.delete(root().resolve(body.path())); knowledge.syncNow();
            assertThat(root().resolve(current.path())).doesNotExist(); assertThat(store().resolve("document-history").resolve(id)).doesNotExist();
            assertThat(knowledge.ownProposals(admin)).noneMatch(proposal -> proposal.id().equals(owner)); assertThat(knowledge.ownProposals(editor)).isEmpty();
        }
    }

    @Test void ownerAndChildExclusionsAndStaleVersionStillProtectAttachmentOperations() throws Exception {
        try (var knowledge = open()) {
            submit(knowledge); var body = parent(knowledge); var file = child(knowledge);
            for (String excluded : List.of(body.path(), file.path(), "common/work")) {
                var denied = new AccessContext("excluded", Set.of("common"), false, Set.of(excluded));
                assertThat(knowledge.documents("", "", denied)).isEmpty(); assertThat(knowledge.download(file.documentId(), file.version(), denied)).isEmpty();
                assertThatThrownBy(() -> knowledge.versions(file.documentId(), file.version(), denied)).isInstanceOf(com.mychatbot.ai.AiException.class);
                assertThatThrownBy(() -> knowledge.submitReplacement(request(file), denied, new KnowledgeService.Upload(xlsx("DENIED_ATTACHMENT_LOOKUP"), "edited.xlsx", "")))
                        .isInstanceOf(com.mychatbot.ai.AiException.class);
            }
            var stale = new KnowledgeService.DocumentInfo(file.documentId(), "0".repeat(64), file.path(), file.title(), file.project(), file.type(), file.bytes(), file.modified(), true);
            assertThatThrownBy(() -> knowledge.submitReplacement(request(stale), editor, new KnowledgeService.Upload(xlsx("STALE_ATTACHMENT_LOOKUP"), "edited.xlsx", "")))
                    .isInstanceOf(com.mychatbot.ai.AiException.class);
        }
    }

    @Test void imageDownloadsRemainAvailableButCannotCreateUnapprovableReplacement() throws Exception {
        Path image = temp.resolve("original.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        try (var knowledge = open()) {
            knowledge.submit(request(null), admin, List.of(new KnowledgeService.Upload(image, "사진.png", "이미지 설명")));
            var file = parent(knowledge).attachments().get(0);
            assertThat(file.replaceable()).isFalse();
            assertThat(knowledge.download(file.documentId(), file.version(), editor)).isPresent();
            assertThatThrownBy(() -> knowledge.submitReplacement(request(file), editor, new KnowledgeService.Upload(image, "edited.png", "")))
                    .isInstanceOf(com.mychatbot.ai.AiException.class);
            assertThat(knowledge.ownProposals(editor)).isEmpty();
        }
    }

    @Test void amendmentAttachmentRequiresCurrentAncestorAndAncestorPathPermission() throws Exception {
        Files.createDirectories(root().resolve("common/work"));
        Path base = root().resolve("common/work/base.md"); Files.writeString(base, "# Base\nANCESTOR_ATTACHMENT_LOOKUP");
        try (var knowledge = open()) {
            var source = knowledge.documents("common/work", "base.md", editor).get(0);
            var proposal = new KnowledgeProposalController.SubmitRequest("common", source.documentId(), source.version(), "양식 보완 안내",
                    "ANCESTOR_CHILD_LOOKUP", BooleanNode.TRUE, "common/work");
            knowledge.submit(proposal, admin, List.of(new KnowledgeService.Upload(xlsx("ANCESTOR_FILE_LOOKUP"), "양식.xlsx", "설명")));
            var file = child(knowledge);
            var denied = new AccessContext("excluded", Set.of("common"), false, Set.of(source.path()));
            assertThat(knowledge.download(file.documentId(), file.version(), denied)).isEmpty();
            Files.writeString(base, "# Base\nCHANGED_ANCESTOR_LOOKUP"); knowledge.syncNow();
            assertThat(knowledge.download(file.documentId(), file.version(), editor)).isEmpty();
            assertThat(root().resolve(file.path())).doesNotExist();
        }
    }

    @Test void deletionNeverClaimsUnknownArchivedAttachmentNamesAsOwned() throws Exception {
        try (var knowledge = open()) {
            var proposal = submit(knowledge); var body = parent(knowledge); var file = child(knowledge);
            Path unknown = store().resolve("published-history").resolve(proposal.id()).resolve("a".repeat(64))
                    .resolve(file.path()).resolveSibling("99--unknown.txt");
            Files.createDirectories(unknown.getParent()); Files.writeString(unknown, "UNOWNED_ARCHIVE_LOOKUP");
            Files.delete(root().resolve(body.path())); knowledge.syncNow();
            assertThat(Files.readString(unknown)).isEqualTo("UNOWNED_ARCHIVE_LOOKUP");
            assertThat(knowledge.status().error()).isEqualTo("source-copy-cleanup-pending");
            assertThat(knowledge.download(file.documentId(), file.version(), editor)).isEmpty();
        }
    }

    @Test void archivedKoreanAttachmentsArePurgedWhenTheirAncestorIsDeleted() throws Exception {
        Files.createDirectories(root().resolve("common/work"));
        Path base = root().resolve("common/work/base.md"); Files.writeString(base, "# Base\nARCHIVE_BASE_LOOKUP");
        String name = java.text.Normalizer.normalize("설정_양식.xlsx", java.text.Normalizer.Form.NFD);
        try (var knowledge = open()) {
            var source = knowledge.documents("common/work", "base.md", editor).get(0);
            var request = new KnowledgeProposalController.SubmitRequest("common", source.documentId(), source.version(), "양식 보완 안내",
                    "ARCHIVE_CHILD_LOOKUP", BooleanNode.TRUE, "common/work");
            var owner = knowledge.submit(request, admin, List.of(new KnowledgeService.Upload(xlsx("ARCHIVE_FILE_LOOKUP"), name, "설명")));
            knowledge.withdrawProposal(owner.id(), admin.userId());
            assertThat(store().resolve("published-history").resolve(owner.id())).isDirectory();
            Files.delete(base); knowledge.syncNow();
            assertThat(knowledge.status().error()).isEmpty();
            assertThat(store().resolve("published-history").resolve(owner.id())).doesNotExist();
            assertThat(knowledge.ownProposals(admin)).isEmpty();
        }
    }

    @Test void managedAttachmentDirectoriesCannotReceiveNewOrAmendmentPublications() throws Exception {
        try (var knowledge = open()) {
            submit(knowledge); var file = child(knowledge);
            String folder = file.path().substring(0, file.path().lastIndexOf('/'));
            var nested = new KnowledgeProposalController.SubmitRequest("common", null, null, "중첩 문서", "NESTED_BAD_LOOKUP", BooleanNode.TRUE, folder);
            assertThatThrownBy(() -> knowledge.submit(nested, admin)).isInstanceOf(com.mychatbot.ai.AiException.class);
            var amendment = new KnowledgeProposalController.SubmitRequest("common", file.documentId(), file.version(), "첨부 보완", "NESTED_BAD_LOOKUP", BooleanNode.TRUE, folder);
            assertThatThrownBy(() -> knowledge.submit(amendment, admin)).isInstanceOf(com.mychatbot.ai.AiException.class);
            assertThat(child(knowledge).version()).isEqualTo(file.version());
            assertThat(knowledge.search("NESTED_BAD_LOOKUP", editor, 20)).isEmpty();
        }
    }

    @Test void manualAttachmentEditKeepsBodyVersionButRevokesStaleFileCitations() throws Exception {
        String id; byte[] changed;
        try (var knowledge = open()) {
            var owner = submit(knowledge); var body = parent(knowledge); var file = child(knowledge); id = file.documentId();
            byte[] initial = Files.readAllBytes(root().resolve(file.path())); changed = Files.readAllBytes(xlsx("EXTERNAL_ATTACHMENT_LOOKUP"));
            Files.write(root().resolve(file.path()), changed);
            assertThat(knowledge.download(body.documentId(), body.version(), editor)).isPresent();
            assertThat(knowledge.download(file.documentId(), file.version(), editor)).isEmpty();
            assertThat(knowledge.download(owner.attachments().get(0).documentId(), owner.attachments().get(0).version(), editor)).isEmpty();
            assertThat(knowledge.search("EXTERNAL_ATTACHMENT_LOOKUP", editor, 20)).isNotEmpty();
            assertThat(parent(knowledge).version()).isEqualTo(body.version());
            assertThat(Files.readAllBytes(store().resolve("attachments").resolve(owner.attachments().get(0).documentId()))).isEqualTo(initial);
        }
        try (var knowledge = open()) {
            var file = child(knowledge); assertThat(file.documentId()).isEqualTo(id);
            assertThat(knowledge.download(id, file.version(), editor).orElseThrow().bytes()).isEqualTo(changed);
        }
    }

    @Test void normalizedKoreanAttachmentFilenameSearchPreservesOriginalDownloadName() throws Exception {
        String name = java.text.Normalizer.normalize("이투유_앱ID_대량등록.xlsx", java.text.Normalizer.Form.NFD);
        try (var knowledge = open()) {
            knowledge.submit(request(null), admin, List.of(new KnowledgeService.Upload(xlsx("KOREAN_ATTACHMENT_LOOKUP"), name, "양식")));
            var hits = knowledge.search("이투유_앱ID_대량등록.xlsx", editor, 20);
            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).attachments()).singleElement().satisfies(file -> {
                assertThat(file.filename()).isEqualTo(name);
                assertThat(knowledge.download(file.documentId(), file.version(), editor).orElseThrow().filename()).isEqualTo(name);
            });
        }
    }

    @Test void missingSymlinkAndUnknownAttachmentFilesFailClosedWithoutRegeneration() throws Exception {
        try (var knowledge = open()) {
            submit(knowledge); var file = child(knowledge); Path physical = root().resolve(file.path()); byte[] original = Files.readAllBytes(physical);
            Files.delete(physical); knowledge.syncNow(); knowledge.syncNow(); assertThat(physical).doesNotExist(); assertThat(knowledge.documents("", "", editor)).isEmpty();
            Files.write(physical, original); assertThat(child(knowledge).version()).isEqualTo(file.version());
            Path unknown = physical.resolveSibling("unknown.txt"); Files.writeString(unknown, "UNKNOWN_ATTACHMENT_LOOKUP"); knowledge.syncNow();
            assertThat(knowledge.download(file.documentId(), file.version(), editor)).isEmpty(); assertThat(Files.readString(unknown)).isEqualTo("UNKNOWN_ATTACHMENT_LOOKUP"); Files.delete(unknown);
            Files.delete(physical); Path outside = temp.resolve("outside.xlsx"); Files.write(outside, original); Files.createSymbolicLink(physical, outside);
            knowledge.syncNow(); assertThat(knowledge.download(file.documentId(), file.version(), editor)).isEmpty();
            assertThat(Files.readAllBytes(outside)).isEqualTo(original); assertThat(Files.isSymbolicLink(physical)).isTrue();
        }
    }
}
