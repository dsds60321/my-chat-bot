package com.mychatbot.knowledge;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.mychatbot.security.AccessContext;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PublicationIsolationTest {
    @TempDir Path temporary;
    private Path root, index, store;
    private final AccessContext admin = new AccessContext("admin", Set.of("user"), true);
    @BeforeEach void setup() throws Exception {
        Path base = temporary.toRealPath(); root = base.resolve("source"); index = base.resolve("index"); store = base.resolve("knowledge-submissions");
        Files.createDirectories(root.resolve("user/folder"));
        Files.writeString(root.resolve("user/original.md"), "# 원본\nORIGINAL_ONLY_LOOKUP original");
    }
    private KnowledgeService open() { return new KnowledgeService(root, index); }
    private KnowledgeProposalController.SubmitRequest request(String title, String content) {
        return new KnowledgeProposalController.SubmitRequest("user", null, null, title, content, BooleanNode.TRUE, "user/folder");
    }
    private Path body(KnowledgeProposal proposal) { return root.resolve("user/folder/" + proposal.title() + "--" + proposal.id() + ".md"); }
    private Path attachment(KnowledgeProposal proposal) { return root.resolve("user/folder/attachments/" + proposal.id() + "/1--manual.txt"); }
    private List<KnowledgeService.Upload> upload() throws Exception {
        Path path = Files.writeString(temporary.resolve("manual.txt"), "ISOLATION_ATTACHMENT_LOOKUP attachment");
        return List.of(new KnowledgeService.Upload(path, "manual.txt", "설명"));
    }
    private static SearchHit hit(KnowledgeService service, String query, KnowledgeProposal proposal, AccessContext access) {
        return service.search(query, access, 20).stream().filter(item -> item.path().contains(proposal.id())).findFirst().orElseThrow();
    }
    private void good(KnowledgeService service, SearchHit original, SearchHit healthy, KnowledgeProposal proposal) {
        assertThat(service.status().healthy()).isTrue();
        assertThat(service.search("ORIGINAL_ONLY_LOOKUP", admin, 1)).extracting(SearchHit::documentId).containsExactly(original.documentId());
        assertThat(service.search("ISOLATION_LOOKUP", admin, 1)).extracting(SearchHit::documentId).containsExactly(healthy.documentId());
        assertThat(service.download(original.documentId(), original.version(), admin)).isPresent();
        assertThat(service.download(healthy.documentId(), healthy.version(), admin)).isPresent();
        var attachment = proposal.attachments().get(0);
        assertThat(service.download(attachment.documentId(), attachment.version(), admin)).isPresent();
    }

    @ParameterizedTest @ValueSource(strings = {"body", "attachment", "canonical", "symlink", "directory"})
    void onlyDamagedProposalIsRemovedBeforeTopLimitAndRecoversAfterExactRestore(String mode) throws Exception {
        KnowledgeProposal damaged, healthy;
        SearchHit a, b, original;
        Path target;
        byte[] expected;
        try (var service = open()) {
            damaged = service.submit(request("문제 제안", ("# 부분\nISOLATION_LOOKUP detail\n").repeat(60)), admin, upload());
            healthy = service.submit(request("정상 제안", "ISOLATION_LOOKUP HEALTHY_ONLY_LOOKUP healthy"), admin, upload());
            a = hit(service, "ISOLATION_LOOKUP", damaged, admin);
            b = hit(service, "HEALTHY_ONLY_LOOKUP", healthy, admin);
            original = service.search("ORIGINAL_ONLY_LOOKUP", admin, 1).get(0);
            target = switch (mode) {
                case "attachment" -> attachment(damaged);
                case "canonical" -> store.resolve("attachments").resolve(damaged.attachments().get(0).documentId());
                default -> body(damaged);
            };
            expected = Files.readAllBytes(target);
            if (mode.equals("symlink")) {
                Files.delete(target);
                Files.createSymbolicLink(target, root.resolve("user/original.md"));
            } else if (mode.equals("directory")) {
                Files.delete(target); Files.createDirectory(target);
                Files.writeString(target.resolve("child.txt"), "DIRECTORY_BYPASS_LOOKUP hidden");
            } else if (mode.equals("canonical")) Files.delete(target);
            else Files.writeString(target, "MANUAL_EDIT_LOOKUP changed");
            // Direct source requests must reject the entire damaged proposal even before the next scan.
            assertThat(service.download(a.documentId(), a.version(), admin)).isEmpty();
            var blocked = damaged.attachments().get(0);
            assertThat(service.download(blocked.documentId(), blocked.version(), admin)).isEmpty();
            assertThat(service.syncNow().healthy()).isTrue();
            good(service, original, b, healthy);
            assertThat(service.search("DIRECTORY_BYPASS_LOOKUP", admin, 4)).isEmpty();
            if (mode.equals("body")) assertThat(service.search("MANUAL_EDIT_LOOKUP", admin, 4)).hasSize(1);
            else assertThat(service.search("MANUAL_EDIT_LOOKUP", admin, 4)).isEmpty();
        }
        try (var service = open()) {
            good(service, original, b, healthy);
            assertThat(service.download(a.documentId(), a.version(), admin)).isEmpty();
            if (mode.equals("directory")) assertThat(Files.readString(target.resolve("child.txt"))).contains("DIRECTORY_BYPASS_LOOKUP");
            else if (mode.equals("symlink")) assertThat(Files.isSymbolicLink(target)).isTrue();
            else if (!mode.equals("canonical")) assertThat(Files.readString(target)).contains("MANUAL_EDIT_LOOKUP");
            if (mode.equals("directory")) { Files.delete(target.resolve("child.txt")); Files.delete(target); }
            else if (mode.equals("symlink")) Files.delete(target);
            Files.write(target, expected);
            assertThat(service.syncNow().healthy()).isTrue();
            assertThat(service.search("ISOLATION_LOOKUP", admin, 20)).anyMatch(item -> item.documentId().equals(a.documentId()));
            assertThat(service.search("HEALTHY_ONLY_LOOKUP", admin, 4)).anyMatch(item -> item.documentId().equals(b.documentId()));
            assertThat(service.download(a.documentId(), a.version(), admin)).isPresent();
            var restored = damaged.attachments().get(0);
            assertThat(service.download(restored.documentId(), restored.version(), admin)).isPresent();
            assertThat(service.download(original.documentId(), original.version(), admin)).isPresent();
        }
    }

    @Test void invalidatedAmendmentArchiveFailureDoesNotDisableOtherDocuments() throws Exception {
        KnowledgeProposal amendment, healthy;
        try (var service = open()) {
            var original = service.search("ORIGINAL_ONLY_LOOKUP", admin, 1).get(0);
            amendment = service.submit(new KnowledgeProposalController.SubmitRequest("user", original.documentId(), original.version(),
                    "보완", "STALE_AMENDMENT_LOOKUP supplement", BooleanNode.TRUE), admin);
            healthy = service.submit(request("정상 제안", "ISOLATION_LOOKUP HEALTHY_ONLY_LOOKUP healthy"), admin, upload());
            var b = hit(service, "HEALTHY_ONLY_LOOKUP", healthy, admin);
            Path archive = store.resolve("published-history"); Files.createDirectories(archive);
            Files.writeString(archive.resolve(amendment.id()), "archive destination conflict");
            Files.writeString(root.resolve("user/original.md"), "# 원본 변경\nORIGINAL_ONLY_LOOKUP updated");
            assertThat(service.syncNow().healthy()).isTrue();
            var updatedOriginal = service.search("ORIGINAL_ONLY_LOOKUP", admin, 1).get(0);
            good(service, updatedOriginal, b, healthy);
            assertThat(service.search("STALE_AMENDMENT_LOOKUP", admin, 4)).isEmpty();
            assertThat(Files.readString(archive.resolve(amendment.id()))).isEqualTo("archive destination conflict");
        }
        try (var service = open()) {
            assertThat(service.status().healthy()).isTrue();
            assertThat(service.search("STALE_AMENDMENT_LOOKUP", admin, 4)).isEmpty();
            assertThat(hit(service, "ISOLATION_LOOKUP", healthy, admin)).isNotNull();
        }
    }

    @Test void deletedMaterializedTargetFolderPurgesOwnerAndDoesNotRecreateBodyWhenFolderReturns() throws Exception {
        KnowledgeProposal damaged, healthy;
        Path privateFolder = Files.createDirectories(root.resolve("user/missing"));
        try (var service = open()) {
            damaged = service.submit(new KnowledgeProposalController.SubmitRequest("user", null, null, "위치 제안",
                    "MISSING_TARGET_LOOKUP", BooleanNode.TRUE, "user/missing"), admin);
            healthy = service.submit(request("정상 제안", "ISOLATION_LOOKUP HEALTHY_ONLY_LOOKUP healthy"), admin, upload());
        }
        Files.delete(privateFolder.resolve("위치 제안--" + damaged.id() + ".md")); Files.delete(privateFolder);
        try (var service = open()) {
            assertThat(service.status().healthy()).isTrue();
            assertThat(service.search("MISSING_TARGET_LOOKUP", admin, 4)).isEmpty();
            assertThat(hit(service, "ISOLATION_LOOKUP", healthy, admin)).isNotNull();
            Files.createDirectory(privateFolder);
            assertThat(service.syncNow().healthy()).isTrue();
            assertThat(service.search("MISSING_TARGET_LOOKUP", admin, 4)).isEmpty();
            assertThat(service.ownProposals(admin)).noneMatch(proposal -> proposal.id().equals(damaged.id()));
        }
    }

    @Test void missingOwnershipLedgerStillFailsClosedGloballyInTheRunningService() throws Exception {
        try (var service = open()) {
            service.submit(request("정상 제안", "ISOLATION_LOOKUP HEALTHY_ONLY_LOOKUP healthy"), admin, upload());
            Files.delete(store.resolve("published-files.json"));
            assertThat(service.syncNow().healthy()).isFalse();
            assertThat(service.search("ORIGINAL_ONLY_LOOKUP", admin, 4)).isEmpty();
            assertThat(service.search("ISOLATION_LOOKUP", admin, 4)).isEmpty();
        }
    }
    @Test void knownAttachmentNamespaceExtrasNeverBecomeOfficialEvenAfterWithdrawal() throws Exception {
        KnowledgeProposal proposal;
        Path extra;
        try (var service = open()) {
            proposal = service.submit(request("정상 제안", "ISOLATION_LOOKUP healthy"), admin, upload());
            extra = attachment(proposal).getParent().resolve("untracked.txt");
            Files.writeString(extra, "UNTRACKED_GROUP_LOOKUP preserve these bytes");
            assertThat(service.syncNow().healthy()).isTrue();
            assertThat(service.search("UNTRACKED_GROUP_LOOKUP", admin, 4)).isEmpty();
            service.withdrawProposal(proposal.id(), admin);
            assertThat(Files.readString(extra)).contains("preserve these bytes");
            assertThat(service.search("UNTRACKED_GROUP_LOOKUP", admin, 4)).isEmpty();
        }
        try (var service = open()) {
            assertThat(service.status().healthy()).isTrue();
            assertThat(service.search("UNTRACKED_GROUP_LOOKUP", admin, 4)).isEmpty();
            assertThat(Files.readString(extra)).contains("preserve these bytes");
        }
    }

    @Test void preexistingUnownedAttachmentGroupIsNeverClaimedOrHidden() throws Exception {
        var author = new AccessContext("writer", Set.of("user"));
        try (var service = open()) {
            var pending = service.submit(request("대기 제안", "PENDING_GROUP_LOOKUP"), author, upload());
            Path group = Files.createDirectories(attachment(pending).getParent());
            Path original = Files.writeString(group.resolve("original.txt"), "UNOWNED_ORIGINAL_LOOKUP original");
            assertThatThrownBy(() -> service.reviewProposal(pending.id(), admin, KnowledgeProposal.State.APPROVED))
                    .isInstanceOf(com.mychatbot.ai.AiException.class);
            assertThat(Files.readString(original)).isEqualTo("UNOWNED_ORIGINAL_LOOKUP original");
            assertThat(service.search("UNOWNED_ORIGINAL_LOOKUP", admin, 4)).hasSize(1).noneMatch(SearchHit::approvedKnowledge);
            assertThat(service.ownProposals(author).get(0).state()).isEqualTo(KnowledgeProposal.State.PENDING_APPROVAL);
        }
    }

    @Test @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC, org.junit.jupiter.api.condition.OS.LINUX})
    void unreadableKnownAttachmentGroupDoesNotPreventNormalSourceScanning() throws Exception {
        KnowledgeProposal damaged, healthy;
        Path group;
        Set<java.nio.file.attribute.PosixFilePermission> permissions;
        try (var service = open()) {
            damaged = service.submit(request("문제 제안", "DAMAGED_PERMISSION_LOOKUP"), admin, upload());
            healthy = service.submit(request("정상 제안", "ISOLATION_LOOKUP healthy"), admin, upload());
            group = attachment(damaged).getParent();
            permissions = Files.getPosixFilePermissions(group);
            try {
                Files.setPosixFilePermissions(group, Set.of());
                assertThat(service.syncNow().healthy()).isTrue();
                assertThat(service.search("ORIGINAL_ONLY_LOOKUP", admin, 4)).hasSize(1);
                assertThat(hit(service, "ISOLATION_LOOKUP", healthy, admin)).isNotNull();
            } finally { Files.setPosixFilePermissions(group, permissions); }
            assertThat(service.syncNow().healthy()).isTrue();
            assertThat(hit(service, "DAMAGED_PERMISSION_LOOKUP", damaged, admin)).isNotNull();
        }
    }

}
