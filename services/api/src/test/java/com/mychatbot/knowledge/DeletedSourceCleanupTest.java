package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.mychatbot.security.AccessContext;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeletedSourceCleanupTest {
    @TempDir Path temp;
    final AccessContext author = new AccessContext("author", Set.of("common"));
    final AccessContext admin = new AccessContext("admin", Set.of("common"), true);
    final ObjectMapper json = new ObjectMapper();
    Path root() throws Exception { return temp.toRealPath().resolve("source"); }
    Path store() throws Exception { return temp.toRealPath().resolve("store"); }
    KnowledgeService open() throws Exception { return new KnowledgeService(root(), temp.toRealPath().resolve("index"), store()); }
    Path original(String name) throws Exception {
        Path path = root().resolve("common/" + name); Files.createDirectories(path.getParent());
        Files.writeString(path, "# Original\nSOURCE_LOOKUP"); return path;
    }
    KnowledgeService.Upload upload(String text) throws Exception {
        Path file = Files.createTempFile(temp, "upload-", ".tmp"); Files.writeString(file, text);
        return new KnowledgeService.Upload(file, "updated.md", "설명");
    }
    KnowledgeProposalController.SubmitRequest request(KnowledgeService.DocumentInfo doc) {
        return new KnowledgeProposalController.SubmitRequest(doc.project(), doc.documentId(), doc.version(), "연결 문서", "관련 설명", BooleanNode.TRUE, "common");
    }
    KnowledgeProposal newDocument(KnowledgeService knowledge) throws Exception {
        return knowledge.submit(new KnowledgeProposalController.SubmitRequest("common", null, null, "독립 문서", "NEW_INDEPENDENT_LOOKUP", BooleanNode.TRUE, "common"), admin,
                List.of(upload("INDEPENDENT_UPLOAD")));
    }

    @Test void deletedOriginalPurgesAllRelatedStatesUploadsCopiesHistoryButPreservesNew() throws Exception {
        Path original = original("guide.md");
        try (var knowledge = open()) {
            var doc = knowledge.documents("common", "guide", admin).get(0);
            var approved = knowledge.submit(request(doc), admin, List.of(upload("APPROVED_UPLOAD")));
            var rejected = knowledge.submit(request(doc), author, List.of(upload("REJECTED_UPLOAD")));
            knowledge.reviewProposal(rejected.id(), admin, KnowledgeProposal.State.REJECTED);
            var withdrawn = knowledge.submit(request(doc), author, List.of(upload("WITHDRAWN_UPLOAD")));
            knowledge.withdrawProposal(withdrawn.id(), author);
            var replacement = knowledge.submitReplacement(request(doc), author, upload("# Update\nREPLACEMENT_UPLOAD"));
            knowledge.reviewProposal(replacement.id(), admin, KnowledgeProposal.State.APPROVED);
            knowledge.syncNow();
            var pending = knowledge.submitReplacement(request(knowledge.documents("common", "guide", admin).get(0)), author, upload("# Future\nPENDING_UPLOAD"));
            var active = knowledge.submit(request(knowledge.documents("common", "guide", admin).get(0)), admin, List.of(upload("ACTIVE_AMENDMENT_UPLOAD")));
            var ledger = json.readTree(store().resolve("published-files.json").toFile());
            for (var item : ledger) if (item.get("id").asText().equals(active.id())) for (var file : item.get("files")) {
                if (file.get("path").asText().endsWith(".md") && !file.get("path").asText().contains("/attachments/")) {
                    Files.writeString(root().resolve(file.get("path").asText()), "EXTERNALLY_EDITED_MANAGED_COPY");
                }
            }
            var independent = newDocument(knowledge);
            Path legacy = temp.resolve("index/proposals.json");
            Files.copy(store().resolve("proposals.json"), legacy);
            Files.createDirectories(temp.resolve("index/attachments"));
            for (var proposal : List.of(approved, rejected, withdrawn, replacement, pending, active, independent)) for (var attachment : proposal.attachments()) {
                Files.copy(store().resolve("attachments").resolve(attachment.documentId()), temp.resolve("index/attachments").resolve(attachment.documentId()));
            }
            Set<String> removed = Set.of(approved.id(), rejected.id(), withdrawn.id(), replacement.id(), pending.id(), active.id());
            assertThat(store().resolve("published-history").resolve(approved.id())).exists();
            Files.delete(original);
            assertThat(knowledge.syncNow().healthy()).isTrue();
            assertThat(knowledge.ownProposals(author)).isEmpty();
            assertThat(knowledge.ownProposals(admin)).extracting(KnowledgeProposal::id).containsExactly(independent.id());
            assertThat(store().resolve("document-history").resolve(doc.documentId())).doesNotExist();
            for (KnowledgeProposal proposal : List.of(approved, rejected, withdrawn, replacement, pending, active)) {
                for (var attachment : proposal.attachments()) {
                    assertThat(store().resolve("attachments").resolve(attachment.documentId())).doesNotExist();
                    assertThat(temp.resolve("index/attachments").resolve(attachment.documentId())).doesNotExist();
                }
                assertThat(store().resolve("published-history").resolve(proposal.id())).doesNotExist();
            }
            try (var paths = Files.walk(root())) {
                assertThat(paths.map(Path::toString)).noneMatch(path -> removed.stream().anyMatch(path::contains));
            }
            assertThat(knowledge.search("NEW_INDEPENDENT_LOOKUP", admin, 4)).isNotEmpty();
            assertThat(Files.readString(legacy)).doesNotContain(approved.id(), active.id(), replacement.id()).contains(independent.id());
            var file = independent.attachments().get(0);
            assertThat(temp.resolve("index/attachments").resolve(file.documentId())).exists();
            assertThat(knowledge.proposalAttachment(independent.id(), file.documentId(), file.version(), admin, false)).isPresent();
        }
    }

    @Test void legacyOnlyProposalStillPurgesItsPrimaryUploadAndOwnedGeneratedCopies() throws Exception {
        Path original = original("guide.md"); KnowledgeProposal linked;
        try (var knowledge = open()) {
            var doc = knowledge.documents("common", "guide", admin).get(0);
            linked = knowledge.submit(request(doc), admin, List.of(upload("LEGACY_ONLY_UPLOAD")));
            newDocument(knowledge);
        }
        Path primary = store().resolve("proposals.json");
        var snapshot = (com.fasterxml.jackson.databind.node.ArrayNode) json.readTree(primary.toFile());
        Files.copy(primary, temp.resolve("index/proposals.json"));
        var remaining = json.createArrayNode();
        snapshot.forEach(proposal -> { if (!proposal.get("id").asText().equals(linked.id())) remaining.add(proposal); });
        Files.writeString(primary, json.writeValueAsString(remaining));
        var attachment = linked.attachments().get(0);
        Files.createDirectories(temp.resolve("index/attachments"));
        Files.copy(store().resolve("attachments").resolve(attachment.documentId()), temp.resolve("index/attachments").resolve(attachment.documentId()));
        Files.delete(original);
        try (var knowledge = open()) {
            assertThat(knowledge.status().healthy()).isTrue();
            assertThat(knowledge.ownProposals(admin)).hasSize(1).allMatch(proposal -> proposal.kind() == KnowledgeProposal.Kind.NEW);
            assertThat(store().resolve("attachments").resolve(attachment.documentId())).doesNotExist();
            assertThat(temp.resolve("index/attachments").resolve(attachment.documentId())).doesNotExist();
            assertThat(Files.readString(store().resolve("published-files.json"))).doesNotContain(linked.id());
            try (var paths = Files.walk(root())) { assertThat(paths.map(Path::toString)).noneMatch(path -> path.contains(linked.id())); }
        }
    }

    @Test void restartAndPolicyResetStillFindDeletedSourceWithoutManifest() throws Exception {
        Path original = original("guide.md"); String id; String attachment;
        try (var knowledge = open()) {
            var doc = knowledge.documents("common", "guide", admin).get(0); id = doc.documentId();
            var proposal = knowledge.submitReplacement(request(doc), admin, upload("# Update\nNEW_VERSION"));
            attachment = proposal.attachments().get(0).documentId();
        }
        Files.delete(original);
        Files.writeString(temp.resolve("index/policy.version"), "old-policy");
        Files.writeString(temp.resolve("index/manifest.json"), "[]");
        try (var knowledge = open()) {
            assertThat(knowledge.status().healthy()).isTrue();
            assertThat(knowledge.ownProposals(admin)).isEmpty();
            assertThat(store().resolve("document-history").resolve(id)).doesNotExist();
            assertThat(store().resolve("attachments").resolve(attachment)).doesNotExist();
        }
        original("guide.md");
        try (var knowledge = open()) {
            assertThat(knowledge.documents("common", "guide", admin)).hasSize(1);
            assertThat(knowledge.versions(id, null, admin)).isEmpty();
        }
    }

    @Test void missingRootOrFailedWalkPreservesCopiesAndDoesNotRecreateRoot() throws Exception {
        Path original = original("guide.md"); String id;
        try (var knowledge = open()) {
            var doc = knowledge.documents("common", "guide", admin).get(0); id = doc.documentId();
            knowledge.submitReplacement(request(doc), admin, upload("# Update\nNEW_VERSION"));
        }
        Path moved = temp.resolve("moved-source"); Files.move(root(), moved);
        try (var knowledge = open()) {
            assertThat(knowledge.status().healthy()).isFalse();
            assertThat(root()).doesNotExist();
            assertThat(store().resolve("document-history").resolve(id)).exists();
        }
        Files.move(moved, root());
        try (var knowledge = open()) {
            Path unreadable = root().resolve("unreadable"); Files.createDirectory(unreadable);
            var permissions = Files.getPosixFilePermissions(unreadable);
            try {
                Files.setPosixFilePermissions(unreadable, Set.of());
                if (!Files.isReadable(unreadable)) {
                    Files.delete(original);
                    assertThat(knowledge.syncNow().healthy()).isFalse();
                    assertThat(store().resolve("document-history").resolve(id)).exists();
                }
            } finally { Files.setPosixFilePermissions(unreadable, permissions); }
        }
    }

    @Test void failedCleanupTombstoneRetriesAfterRestartEvenWhenSamePathReturns() throws Exception {
        Path original = original("guide.md"); String id; String attachment;
        try (var knowledge = open()) {
            var doc = knowledge.documents("common", "guide", admin).get(0); id = doc.documentId();
            var proposal = knowledge.submitReplacement(request(doc), admin, upload("# Update\nNEW_VERSION"));
            attachment = proposal.attachments().get(0).documentId();
            Path unexpected = store().resolve("document-history").resolve(id).resolve("unknown-user-file");
            Files.writeString(unexpected, "KEEP_ME_UNTIL_CLASSIFIED");
            Files.delete(original);
            assertThat(knowledge.syncNow().error()).isEqualTo("source-copy-cleanup-pending");
            assertThat(store().resolve("deleted-sources.json")).content().contains(id);
            assertThat(knowledge.ownProposals(admin)).isEmpty();
            Files.writeString(root().resolve("common/unrelated.md"), "# Other\nUNRELATED_SEARCH");
            assertThat(knowledge.search("UNRELATED_SEARCH", admin, 4)).isNotEmpty();
            Files.writeString(original, "# Readded\nNEW_ORIGINAL_CONTENT");
            Files.delete(unexpected);
        }
        try (var knowledge = open()) {
            assertThat(knowledge.status().healthy()).isTrue();
            assertThat(Files.readString(original)).contains("NEW_ORIGINAL_CONTENT");
            assertThat(knowledge.ownProposals(admin)).isEmpty();
            assertThat(store().resolve("document-history").resolve(id)).doesNotExist();
            assertThat(store().resolve("attachments").resolve(attachment)).doesNotExist();
        }
    }

    @Test void deletedSourceWithCommittedOrUncommittedTransactionNeverRecreatesIt() throws Exception {
        for (boolean committed : List.of(false, true)) {
            Path original = original("guide.md");
            KnowledgeProposal proposal;
            String id; String before;
            try (var knowledge = open()) {
                var doc = knowledge.documents("common", "guide", admin).get(0); id = doc.documentId(); before = doc.version();
                proposal = knowledge.submitReplacement(request(doc), committed ? admin : author, upload("# Update\nAFTER_TRANSACTION"));
                if (!committed) {
                    Path directory = store().resolve("document-history").resolve(id); Files.createDirectories(directory);
                    Files.writeString(directory.resolve(".project"), "common");
                    Files.copy(original, directory.resolve(before));
                }
            }
            Path tx = store().resolve("document-transaction"); Files.createDirectories(tx);
            Files.writeString(tx.resolve("next"), "# Update\nAFTER_TRANSACTION");
            Files.writeString(tx.resolve("intent.json"), json.writeValueAsString(new DocumentHistory.Intent(id, "common/guide.md", before,
                    hash("# Update\nAFTER_TRANSACTION"), proposal.id())));
            Files.delete(original);
            try (var knowledge = open()) {
                assertThat(knowledge.status().healthy()).isTrue();
                assertThat(original).doesNotExist();
                assertThat(store().resolve("document-history").resolve(id)).doesNotExist();
                assertThat(tx.resolve("next")).doesNotExist();
                assertThat(tx.resolve("intent.json")).doesNotExist();
            }
        }
    }

    @Test void tombstoneDominatesCommittedTransactionOnSamePathReaddAndOrphanStagingIsRemoved() throws Exception {
        Path original = original("guide.md");
        KnowledgeProposal proposal; String before; String id;
        try (var knowledge = open()) {
            var doc = knowledge.documents("common", "guide", admin).get(0); before = doc.version(); id = doc.documentId();
            proposal = knowledge.submitReplacement(request(doc), admin, upload("# Update\nOLD_TRANSACTION_BYTES"));
        }
        Files.writeString(store().resolve("deleted-sources.json"), json.writeValueAsString(Set.of(id)));
        Path tx = store().resolve("document-transaction"); Files.createDirectories(tx);
        Files.writeString(tx.resolve("next"), "# Update\nOLD_TRANSACTION_BYTES");
        Files.writeString(tx.resolve("intent.json"), json.writeValueAsString(new DocumentHistory.Intent(id, "common/guide.md", before,
                hash("# Update\nOLD_TRANSACTION_BYTES"), proposal.id())));
        Files.writeString(original, "# Readded\nNEW_RECREATED_ORIGINAL");
        try (var knowledge = open()) {
            assertThat(knowledge.status().healthy()).isTrue();
            assertThat(Files.readString(original)).contains("NEW_RECREATED_ORIGINAL");
            assertThat(knowledge.ownProposals(admin)).isEmpty();
            assertThat(store().resolve("document-history").resolve(id)).doesNotExist();
            assertThat(tx.resolve("next")).doesNotExist();
        }
        Files.writeString(tx.resolve("next"), "ORPHAN_UPLOAD_BYTES");
        Files.writeString(tx.resolve(".document-orphan.tmp"), "ORPHAN_TEMP_BYTES");
        Files.delete(original);
        try (var knowledge = open()) {
            assertThat(knowledge.status().healthy()).isTrue();
            assertThat(tx.resolve("next")).doesNotExist();
            assertThat(tx.resolve(".document-orphan.tmp")).doesNotExist();
        }
    }

    @Test void symlinkInsideHistoryCannotDeleteOutsideTarget() throws Exception {
        Path original = original("guide.md");
        try (var knowledge = open()) {
            var doc = knowledge.documents("common", "guide", admin).get(0);
            knowledge.submitReplacement(request(doc), admin, upload("# Update\nNEW_VERSION"));
            Path outside = temp.resolve("outside.txt"); Files.writeString(outside, "KEEP_OUTSIDE");
            Path link = store().resolve("document-history").resolve(doc.documentId()).resolve(hash("fake-backup"));
            Files.createSymbolicLink(link, outside);
            Files.delete(original);
            assertThat(knowledge.syncNow().error()).isEqualTo("source-copy-cleanup-pending");
            assertThat(Files.readString(outside)).isEqualTo("KEEP_OUTSIDE");
            Files.delete(link);
            assertThat(knowledge.syncNow().healthy()).isTrue();
        }
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
