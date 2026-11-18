package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mychatbot.security.AccessContext;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedDocumentEditingTest {
    @TempDir Path temp;
    final AccessContext admin = new AccessContext("admin", Set.of("user"), true);
    final AccessContext user = new AccessContext("editor", Set.of("user"));
    final ObjectMapper json = new ObjectMapper();
    Path root() throws Exception { return temp.toRealPath().resolve("source"); }
    Path store() throws Exception { return temp.toRealPath().resolve("store"); }
    KnowledgeService open() throws Exception {
        Files.createDirectories(root().resolve("user/ghkang"));
        return new KnowledgeService(root(), temp.toRealPath().resolve("index"), store());
    }
    KnowledgeProposal create(KnowledgeService knowledge, String title, String content) {
        return knowledge.submit(new KnowledgeProposalController.SubmitRequest("user", null, null, title, content, BooleanNode.TRUE, "user/ghkang"), admin);
    }
    KnowledgeService.DocumentInfo document(KnowledgeService knowledge, String title) {
        return knowledge.documents("user/ghkang", title, user).get(0);
    }
    KnowledgeProposalController.SubmitRequest request(KnowledgeService.DocumentInfo source, String title, String content) {
        return new KnowledgeProposalController.SubmitRequest(source.project(), source.documentId(), source.version(), title, content, BooleanNode.TRUE, "user/ghkang");
    }
    KnowledgeService.Upload upload(String content) throws Exception {
        Path path = Files.createTempFile(temp, "managed-edit-", ".md"); Files.writeString(path, content);
        return new KnowledgeService.Upload(path, "edited.md", "변경 파일");
    }

    @Test void managedMetadataIsTrimmedWithoutRejectingUserDocumentData() throws Exception {
        String digest = "a".repeat(20) + "01012345678" + "b".repeat(33);
        String ownerId = "01012345-6789-4000-8000-000000000000";
        var owner = new KnowledgeProposal(ownerId, KnowledgeProposal.Kind.AMENDMENT, "user", digest, digest,
                "대상", "보완", "SAFE_PROVENANCE_LOOKUP", "admin", KnowledgeProposal.State.APPROVED, "time", "time", List.of());
        String text = "# 보완\n제안 ID: " + ownerId + "\n원본 문서 ID: " + digest + "\n원본 버전: " + digest
                + "\nSAFE_PROVENANCE_LOOKUP\n[첨부](attachments/" + ownerId + "/guide.md)\n";
        assertThat(SensitiveData.guard(text)).isFalse();
        Path file = temp.resolve("generated.md"); Files.writeString(file, text);
        var extractor = new DocumentExtractor();
        assertThat(extractor.extractManagedMarkdown(file, raw -> KnowledgeService.managedIndexText(owner, raw)).text())
                .contains("SAFE_PROVENANCE_LOOKUP").doesNotContain(digest).doesNotContain(ownerId);
        Files.writeString(file, text + "\n연락처 01012345678\n");
        assertThat(extractor.extractManagedMarkdown(file, raw -> KnowledgeService.managedIndexText(owner, raw)).text())
                .contains("01012345678");
        // User-written metadata remains content rather than being confused with audit metadata.
        Files.writeString(file, "# Edited\n원본 버전: 01012345678\n");
        assertThat(extractor.extractManagedMarkdown(file, raw -> KnowledgeService.managedIndexText(owner, raw)).text())
                .contains("01012345678");
    }

    @Test void downloadedManagedFileWithSensitiveLookingAuditIdCanBeReplacedAndRestored() throws Exception {
        try (var knowledge = open()) { assertThat(knowledge.status().healthy()).isTrue(); }
        String ownerId = "00000000-0000-4000-8000-01012345678a";
        var owner = new KnowledgeProposal(ownerId, KnowledgeProposal.Kind.NEW, "user", null, null,
                null, "고정 감사 원본", "OPAQUE_OWNER_LOOKUP", "admin", KnowledgeProposal.State.APPROVED,
                "2026-11-18T00:00:00Z", "2026-11-18T00:00:00Z", List.of(), "user/ghkang");
        var publisher = new PublishedKnowledge(root(), store()); publisher.load(); publisher.publish(owner, "user/ghkang");
        Files.writeString(store().resolve("proposals.json"), json.writeValueAsString(List.of(owner)));
        try (var knowledge = open()) {
            var source = document(knowledge, owner.title());
            var downloaded = knowledge.download(source.documentId(), source.version(), user).orElseThrow();
            byte[] original = downloaded.bytes();
            assertThat(downloaded.filename()).isEqualTo("source.md");
            assertThat(SensitiveData.guard(new String(original, StandardCharsets.UTF_8))).isFalse();
            Path edited = temp.resolve("downloaded-edit.md");
            Files.writeString(edited, new String(original, StandardCharsets.UTF_8) + "\nOPAQUE_UPDATED_LOOKUP\n");
            var replacement = knowledge.submitReplacement(request(source, "교체", ""), user,
                    new KnowledgeService.Upload(edited, downloaded.filename(), ""));
            knowledge.reviewProposal(replacement.id(), admin, KnowledgeProposal.State.APPROVED);
            var updated = document(knowledge, owner.title());
            assertThat(knowledge.search("OPAQUE_UPDATED_LOOKUP", user, 4)).hasSize(1);
            knowledge.restore(updated.documentId(), updated.version(), source.version(), true, admin);
            assertThat(Files.readAllBytes(root().resolve(source.path()))).isEqualTo(original);
            var restored = document(knowledge, owner.title());
            var personalReplacement = knowledge.submitReplacement(request(restored, "민감 교체", ""), admin,
                    upload(new String(original, StandardCharsets.UTF_8) + "\n연락처 01012345678\n"));
            assertThat(personalReplacement.state()).isEqualTo(KnowledgeProposal.State.APPROVED);
        }
    }

    @Test void approvedNewAcceptsUserAmendmentThenApprovedReplacementAndRestoreWithoutStaleRepublish() throws Exception {
        String id; String before; String after; Path body; byte[] original;
        try (var knowledge = open()) {
            create(knowledge, "업무 원본", "MANAGED_ORIGINAL_LOOKUP");
            var source = document(knowledge, "업무 원본"); id = source.documentId(); before = source.version(); body = root().resolve(source.path());
            original = Files.readAllBytes(body);
            assertThat(source.documentId()).isEqualTo(hash(source.path().getBytes(StandardCharsets.UTF_8)));
            assertThat(source.version()).isEqualTo(hash(original));
            assertThat(source.replaceable()).isTrue();
            var draft = knowledge.draft(new KnowledgeProposalController.DraftRequest("보완 내용을 추가해 줘", "user", id, before, "user/ghkang"), user);
            assertThat(draft.before()).contains("MANAGED_ORIGINAL_LOOKUP");
            var amendment = knowledge.submit(request(source, "업무 보완", "MANAGED_AMENDMENT_LOOKUP"), user);
            assertThat(amendment.state()).isEqualTo(KnowledgeProposal.State.PENDING_APPROVAL);
            assertThat(Files.readAllBytes(body)).isEqualTo(original);
            knowledge.reviewProposal(amendment.id(), admin, KnowledgeProposal.State.APPROVED);
            assertThat(knowledge.search("MANAGED_AMENDMENT_LOOKUP", user, 4)).hasSize(1);
            var replacement = knowledge.submitReplacement(request(source, "파일 교체", "수정 내역"), user, upload("# Edited\nMANAGED_UPDATED_LOOKUP"));
            assertThat(Files.readAllBytes(body)).isEqualTo(original);
            knowledge.reviewProposal(replacement.id(), admin, KnowledgeProposal.State.APPROVED);
            assertThat(Files.readString(body)).contains("MANAGED_UPDATED_LOOKUP").doesNotContain("MANAGED_ORIGINAL_LOOKUP");
            var updated = document(knowledge, "업무 원본"); after = updated.version();
            assertThat(updated.documentId()).isEqualTo(id);
            assertThat(after).isEqualTo(hash(Files.readAllBytes(body))).isNotEqualTo(before);
            assertThat(knowledge.search("MANAGED_UPDATED_LOOKUP", user, 4)).hasSize(1);
            assertThat(knowledge.search("MANAGED_ORIGINAL_LOOKUP", user, 4)).isEmpty();
            // Existing amendment lineage remains version-bound after its source is changed.
            assertThat(knowledge.search("MANAGED_AMENDMENT_LOOKUP", user, 4)).isEmpty();
            assertThat(new String(knowledge.download(id, after, user).orElseThrow().bytes(), StandardCharsets.UTF_8)).contains("MANAGED_UPDATED_LOOKUP");
            assertThat(new DocumentPreviewService(knowledge, "unused", 1).preview(id, after, user).orElseThrow().text()).contains("MANAGED_UPDATED_LOOKUP");
        }
        try (var knowledge = open()) {
            assertThat(document(knowledge, "업무 원본").version()).isEqualTo(after);
            assertThat(Files.readString(body)).contains("MANAGED_UPDATED_LOOKUP");
            assertThat(knowledge.versions(id, after, user)).extracting(DocumentHistory.Version::version).contains(before);
            var restored = knowledge.restore(id, after, before, true, admin);
            assertThat(restored.replaceable()).isTrue();
            assertThat(restored.version()).isEqualTo(before);
            assertThat(Files.readAllBytes(body)).isEqualTo(original);
        }
        try (var knowledge = open()) {
            assertThat(document(knowledge, "업무 원본").version()).isEqualTo(before);
            assertThat(knowledge.search("MANAGED_ORIGINAL_LOOKUP", user, 4)).hasSize(1);
        }
    }

    @Test void managedAmendmentChainsRetainAncestorExclusionsAndVersionInvalidation() throws Exception {
        Path original;
        try (var knowledge = open()) {
            original = root().resolve("user/ghkang/original.md"); Files.writeString(original, "# Original\nANCESTOR_ORIGINAL_LOOKUP");
            var source = document(knowledge, "original.md");
            knowledge.submit(request(source, "1차 보완", "ANCESTOR_CHILD_LOOKUP"), admin);
            var child = document(knowledge, "1차 보완");
            knowledge.submit(request(child, "2차 보완", "ANCESTOR_GRANDCHILD_LOOKUP"), admin);
            var grandchild = document(knowledge, "2차 보완");
            var restricted = new AccessContext("limited", Set.of("user"), false, Set.of(source.path()));
            assertThat(knowledge.search("ANCESTOR_CHILD_LOOKUP", restricted, 4)).isEmpty();
            assertThat(knowledge.search("ANCESTOR_GRANDCHILD_LOOKUP", restricted, 4)).isEmpty();
            assertThat(knowledge.download(grandchild.documentId(), grandchild.version(), restricted)).isEmpty();
            assertThatThrownBy(() -> knowledge.versions(grandchild.documentId(), grandchild.version(), restricted)).hasMessageContaining("찾을");
            assertThatThrownBy(() -> knowledge.submitReplacement(request(grandchild, "교체", "수정"), restricted, upload("# Edit\nSAFE_EDIT"))).hasMessageContaining("찾을");
            Files.writeString(original, "# Changed\nANCESTOR_CHANGED_LOOKUP");
            assertThat(knowledge.documents("user/ghkang", "보완", user)).isEmpty();
            assertThat(knowledge.search("ANCESTOR_GRANDCHILD_LOOKUP", user, 4)).isEmpty();
            assertThat(root().resolve(child.path())).doesNotExist();
            assertThat(root().resolve(grandchild.path())).doesNotExist();
        }
        try (var knowledge = open()) {
            assertThat(knowledge.ownProposals(admin)).hasSize(2);
            assertThat(knowledge.search("ANCESTOR_CHANGED_LOOKUP", user, 4)).hasSize(1);
        }
    }

    @Test void deletingManagedNewBodyPurgesOwnerDescendantsUploadsAndHistoryWithoutRecreation() throws Exception {
        Path body; String id; Set<String> linked;
        try (var knowledge = open()) {
            var owner = create(knowledge, "삭제 원본", "OWNER_DELETE_LOOKUP");
            var source = document(knowledge, "삭제 원본"); id = source.documentId(); body = root().resolve(source.path());
            var child = knowledge.submit(request(source, "삭제 보완", "CHILD_DELETE_LOOKUP"), user, List.of(upload("CHILD_UPLOAD_LOOKUP")));
            knowledge.reviewProposal(child.id(), admin, KnowledgeProposal.State.APPROVED);
            var replacement = knowledge.submitReplacement(request(source, "교체", "수정"), admin, upload("# Edited\nOWNER_REPLACEMENT_LOOKUP"));
            create(knowledge, "독립 문서", "INDEPENDENT_MANAGED_LOOKUP");
            linked = Set.of(owner.id(), child.id(), replacement.id());
            Files.delete(body);
            assertThat(knowledge.syncNow().healthy()).isTrue();
            assertThat(body).doesNotExist();
            assertThat(knowledge.ownProposals(admin)).hasSize(1);
            assertThat(knowledge.ownProposals(user)).isEmpty();
            assertThat(store().resolve("document-history").resolve(id)).doesNotExist();
            assertThat(Files.readString(store().resolve("proposals.json"))).doesNotContain(linked.toArray(String[]::new));
            assertThat(Files.readString(store().resolve("published-files.json"))).doesNotContain(linked.toArray(String[]::new));
            for (var attachment : replacement.attachments()) assertThat(store().resolve("attachments").resolve(attachment.documentId())).doesNotExist();
        }
        try (var knowledge = open()) {
            assertThat(body).doesNotExist();
            assertThat(knowledge.search("INDEPENDENT_MANAGED_LOOKUP", user, 4)).hasSize(1);
        }
    }

    @Test void durableOwnerTombstoneSurvivesLedgerPurgeCrashAndPreservesReaddedOrdinaryFile() throws Exception {
        Path body; String id; String owner;
        try (var knowledge = open()) {
            owner = create(knowledge, "삭제 재시도", "OLD_OWNER_LOOKUP").id();
            var source = document(knowledge, "삭제 재시도"); id = source.documentId(); body = root().resolve(source.path());
            knowledge.submitReplacement(request(source, "교체", "수정"), admin, upload("# Old\nOLD_REPLACEMENT_LOOKUP"));
        }
        Files.writeString(store().resolve("deleted-sources.json"), json.writeValueAsString(Set.of(id)));
        Files.writeString(store().resolve("deleted-proposals.json"), json.writeValueAsString(Set.of(owner)));
        var publication = new PublishedKnowledge(root(), store()); publication.load(); publication.purge(owner);
        Files.writeString(body, "# Recreated\nRECREATED_ORDINARY_LOOKUP");
        try (var knowledge = open()) {
            assertThat(knowledge.status().healthy()).isTrue();
            assertThat(knowledge.ownProposals(admin)).isEmpty();
            assertThat(Files.readString(body)).contains("RECREATED_ORDINARY_LOOKUP");
            assertThat(store().resolve("document-history").resolve(id)).doesNotExist();
            assertThat(knowledge.search("RECREATED_ORDINARY_LOOKUP", user, 4)).hasSize(1);
            assertThat(knowledge.search("OLD_OWNER_LOOKUP", user, 4)).isEmpty();
        }
    }

    @Test void blockedCleanupWithLedgerRetainedPreservesFreshPrimaryForNewAndAmendment() throws Exception {
        for (boolean amendment : List.of(false, true)) {
            String title = amendment ? "재생성 보완" : "재생성 신규";
            String owner; String id; Path body; Path conflict;
            try (var knowledge = open()) {
                KnowledgeProposal created;
                if (amendment) {
                    Path original = root().resolve("user/ghkang/parent.md"); Files.writeString(original, "# Parent\nPARENT_UNCHANGED_LOOKUP");
                    created = knowledge.submit(request(document(knowledge, "parent.md"), title, "OLD_PENDING_OWNER_LOOKUP"), admin, List.of(upload("OLD_PENDING_UPLOAD_LOOKUP")));
                } else {
                    created = knowledge.submit(new KnowledgeProposalController.SubmitRequest("user", null, null, title,
                            "OLD_PENDING_OWNER_LOOKUP", BooleanNode.TRUE, "user/ghkang"), admin, List.of(upload("OLD_PENDING_UPLOAD_LOOKUP")));
                }
                owner = created.id();
                var source = document(knowledge, title); id = source.documentId(); body = root().resolve(source.path());
                knowledge.submitReplacement(request(source, "교체", "수정"), admin, upload("# Edited\nOLD_PENDING_EDIT_LOOKUP"));
                conflict = root().resolve("user/ghkang/attachments").resolve(owner).resolve("unknown.txt");
                Files.writeString(conflict, "DO_NOT_DELETE");
            }
            Files.delete(body);
            try (var knowledge = open()) {
                assertThat(knowledge.status().error()).isEqualTo("source-copy-cleanup-pending");
                assertThat(Files.readString(store().resolve("published-files.json"))).contains(owner);
                assertThat(Files.readString(store().resolve("absent-deleted-bodies.json"))).contains(id);
            }
            Files.writeString(body, "# Fresh primary\nFRESH_PRIMARY_LOOKUP");
            // Retry across a restart while the conflicting group and old owner still exist.
            try (var knowledge = open()) {
                assertThat(knowledge.status().error()).isEqualTo("source-copy-cleanup-pending");
                assertThat(Files.readString(body)).contains("FRESH_PRIMARY_LOOKUP");
            }
            Files.delete(conflict);
            try (var knowledge = open()) {
                assertThat(knowledge.status().healthy()).isTrue();
                assertThat(Files.readString(body)).contains("FRESH_PRIMARY_LOOKUP");
                assertThat(knowledge.ownProposals(admin)).noneMatch(proposal -> proposal.id().equals(owner));
                assertThat(store().resolve("document-history").resolve(id)).doesNotExist();
                assertThat(root().resolve("user/ghkang/attachments").resolve(owner)).doesNotExist();
                assertThat(Files.readString(store().resolve("published-files.json"))).doesNotContain(owner);
                assertThat(knowledge.search("FRESH_PRIMARY_LOOKUP", user, 4)).isNotEmpty();
                assertThat(knowledge.search("OLD_PENDING_OWNER_LOOKUP", user, 4)).isEmpty();
            }
            Files.delete(body);
        }
    }

    @Test void legacyLedgerAdoptsActualBodyWithoutOverwritingAndPreservesPriorInactiveArchives() throws Exception {
        Path body; String owner;
        try (var knowledge = open()) {
            owner = create(knowledge, "기존 승인", "LEGACY_CANONICAL_LOOKUP").id();
            body = root().resolve(document(knowledge, "기존 승인").path());
            var inactive = create(knowledge, "이전 철회", "LEGACY_WITHDRAWN_LOOKUP");
            knowledge.withdrawProposal(inactive.id(), admin);
        }
        Files.writeString(body, "# Externally edited\nLEGACY_ACTUAL_EDIT_LOOKUP");
        Path ledger = store().resolve("published-files.json");
        var records = json.readTree(ledger.toFile()); for (var record : records) ((ObjectNode) record).remove("active");
        Files.writeString(ledger, json.writeValueAsString(records));
        Files.writeString(temp.resolve("index/policy.version"), "legacy-policy");
        Files.writeString(temp.resolve("index/manifest.json"), "[]");
        try (var knowledge = open()) {
            assertThat(knowledge.status().healthy()).isTrue();
            var source = document(knowledge, "기존 승인");
            assertThat(source.replaceable()).isTrue();
            assertThat(source.version()).isEqualTo(hash(Files.readAllBytes(body)));
            assertThat(knowledge.search("LEGACY_ACTUAL_EDIT_LOOKUP", user, 4)).hasSize(1);
            assertThat(knowledge.search("LEGACY_CANONICAL_LOOKUP", user, 4)).isEmpty();
            assertThat(knowledge.ownProposals(admin)).hasSize(2);
            assertThat(Files.readString(ledger)).contains("\"active\":true");
            assertThat(Files.readString(body)).contains("LEGACY_ACTUAL_EDIT_LOOKUP");
            assertThat(knowledge.ownProposals(admin)).anyMatch(proposal -> proposal.id().equals(owner));
        }
    }

    @Test void managedProjectScopeIsBoundToApprovedOwnerDuringReplacementAndRestore() throws Exception {
        try (var knowledge = open()) {
            Files.createDirectories(root().resolve("paye2u"));
            var scopedAdmin = new AccessContext("admin", Set.of("paye2u"), true);
            knowledge.submit(new KnowledgeProposalController.SubmitRequest("paye2u", null, null, "SQL 안내",
                    "syslink ORIGINAL_SQL_OWNER_LOOKUP", BooleanNode.TRUE, "paye2u"), scopedAdmin);
            var source = knowledge.documents("paye2u", "", scopedAdmin).get(0);
            var replacement = new KnowledgeProposalController.SubmitRequest("paye2u", source.documentId(), source.version(),
                    "파일 수정", "수정 내역", BooleanNode.TRUE, "paye2u");
            knowledge.submitReplacement(replacement, scopedAdmin, upload("# Edited\nsyslink UPDATED_SQL_OWNER_LOOKUP"));
            var updated = knowledge.documents("paye2u", "", scopedAdmin).get(0);
            assertThat(updated.project()).isEqualTo("paye2u");
            assertThat(knowledge.search("UPDATED_SQL_OWNER_LOOKUP", scopedAdmin, 4)).hasSize(1);
            assertThat(knowledge.restore(updated.documentId(), updated.version(), source.version(), true, scopedAdmin).project()).isEqualTo("paye2u");
        }
    }

    @Test void missingLegacyApprovedBodiesWithoutArchiveAreDeletedButInvalidatedSourcesExplainPriorAbsence() throws Exception {
        KnowledgeProposal missingNew; KnowledgeProposal missingAmendment; KnowledgeProposal invalidated;
        KnowledgeService.DocumentInfo newBody; KnowledgeService.DocumentInfo amendmentBody; KnowledgeService.DocumentInfo invalidatedBody;
        Path ancestor;
        try (var knowledge = open()) {
            ancestor = root().resolve("user/ghkang/legacy-original.md"); Files.writeString(ancestor, "# Source\nLEGACY_SOURCE_LOOKUP");
            var source = document(knowledge, "legacy-original.md");
            missingNew = knowledge.submit(new KnowledgeProposalController.SubmitRequest("user", null, null, "누락 승인", "MISSING_LEGACY_NEW_LOOKUP",
                    BooleanNode.TRUE, "user/ghkang"), admin, List.of(upload("MISSING_LEGACY_UPLOAD")));
            newBody = document(knowledge, "누락 승인");
            missingAmendment = knowledge.submit(request(source, "누락 보완", "MISSING_LEGACY_AMENDMENT_LOOKUP"), admin);
            amendmentBody = document(knowledge, "누락 보완");
            Path other = root().resolve("user/ghkang/changed-source.md"); Files.writeString(other, "# Other\nUNCHANGED_LEGACY_LOOKUP");
            invalidated = knowledge.submit(request(document(knowledge, "changed-source.md"), "비활성 보완", "INVALIDATED_LEGACY_LOOKUP"), admin);
            invalidatedBody = document(knowledge, "비활성 보완");
            Files.writeString(other, "# Other changed\nCHANGED_LEGACY_LOOKUP");
        }
        Files.delete(root().resolve(newBody.path()));
        Files.delete(root().resolve(amendmentBody.path()));
        Files.delete(root().resolve(invalidatedBody.path()));
        Path ledger = store().resolve("published-files.json");
        var records = json.readTree(ledger.toFile()); for (var record : records) ((ObjectNode) record).remove("active");
        Files.writeString(ledger, json.writeValueAsString(records));
        Files.writeString(temp.resolve("index/policy.version"), "pre-managed-physical-policy");
        Files.writeString(temp.resolve("index/manifest.json"), "[]");
        try (var knowledge = open()) {
            assertThat(knowledge.status().healthy()).isTrue();
            assertThat(knowledge.ownProposals(admin)).extracting(KnowledgeProposal::id)
                    .doesNotContain(missingNew.id(), missingAmendment.id()).contains(invalidated.id());
            for (var attachment : missingNew.attachments()) assertThat(store().resolve("attachments").resolve(attachment.documentId())).doesNotExist();
            assertThat(root().resolve(newBody.path())).doesNotExist();
            assertThat(root().resolve(amendmentBody.path())).doesNotExist();
            assertThat(root().resolve(invalidatedBody.path())).doesNotExist();
            assertThat(Files.readString(ancestor)).contains("LEGACY_SOURCE_LOOKUP");
        }
    }

    @Test void withdrawalArchivesCurrentEditedBytesAndMissingRootNeverDeletesManagedData() throws Exception {
        Path body; String owner; String id;
        try (var knowledge = open()) {
            owner = create(knowledge, "철회 원본", "WITHDRAW_ORIGINAL_LOOKUP").id();
            var source = document(knowledge, "철회 원본"); id = source.documentId(); body = root().resolve(source.path());
            knowledge.submitReplacement(request(source, "교체", "수정"), admin, upload("# Current\nWITHDRAW_EDITED_LOOKUP"));
        }
        Path moved = temp.resolve("unavailable-source"); Files.move(root(), moved);
        try (var knowledge = new KnowledgeService(root(), temp.toRealPath().resolve("index"), store())) {
            assertThat(knowledge.status().healthy()).isFalse();
            assertThat(root()).doesNotExist();
            assertThat(store().resolve("document-history").resolve(id)).exists();
            assertThat(Files.readString(store().resolve("proposals.json"))).contains(owner);
        }
        Files.move(moved, root());
        try (var knowledge = open()) {
            assertThat(Files.readString(body)).contains("WITHDRAW_EDITED_LOOKUP");
            knowledge.withdrawProposal(owner, admin);
            assertThat(knowledge.documents("user/ghkang", "철회 원본", user)).isEmpty();
            assertThat(body).doesNotExist();
            Path archive = store().resolve("published-history").resolve(owner);
            try (var files = Files.walk(archive)) {
                var bodies = files.filter(Files::isRegularFile).filter(path -> path.getFileName().equals(body.getFileName())).toList();
                assertThat(bodies).anySatisfy(path -> assertThat(Files.readString(path)).contains("WITHDRAW_EDITED_LOOKUP"));
            }
        }
    }

    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
