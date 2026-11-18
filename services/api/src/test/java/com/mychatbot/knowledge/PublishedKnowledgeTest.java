package com.mychatbot.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mychatbot.ai.AiException;
import com.mychatbot.security.AccessContext;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PublishedKnowledgeTest {
    @TempDir Path temp;
    private Path root, index, store;
    private final AccessContext author = new AccessContext("writer", Set.of("user"));
    private final AccessContext admin = new AccessContext("admin", Set.of("user"), true);
    @BeforeEach void setup() throws Exception {
        Path base = temp.toRealPath(); root = base.resolve("source"); index = base.resolve("index"); store = base.resolve("knowledge-submissions");
        Files.createDirectories(root.resolve("user/ghkang"));
    }
    private KnowledgeService open() { return new KnowledgeService(root, index); }
    private KnowledgeProposalController.SubmitRequest request(String content) {
        return new KnowledgeProposalController.SubmitRequest("user", null, null, "같은 제목", content, BooleanNode.TRUE, "user/ghkang");
    }
    private Path body(KnowledgeProposal proposal) { return root.resolve("user/ghkang/같은 제목--" + proposal.id() + ".md"); }
    private static void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AiException.class, error -> assertThat(error.code()).isEqualTo("PROPOSAL_UNAVAILABLE"));
    }

    @Test void sameAuthorAndTitleStillPublishSeparateUuidBodiesAttachmentsAndOnlyVirtualSearchHits() throws Exception {
        Path first = Files.writeString(temp.resolve("first.txt"), "PAIR_ATTACHMENT_LOOKUP first attachment");
        Path second = Files.writeString(temp.resolve("second.txt"), "PAIR_ATTACHMENT_LOOKUP second attachment");
        KnowledgeProposal a, b;
        try (var service = open()) {
            a = service.submit(request("PAIR_BODY_LOOKUP first"), admin,
                    List.of(new KnowledgeService.Upload(first, "manual.txt", "first"), new KnowledgeService.Upload(second, "manual.txt", "second")));
            b = service.submit(request("PAIR_BODY_LOOKUP second"), admin,
                    List.of(new KnowledgeService.Upload(second, "manual.txt", "second")));
            assertThat(a.id()).isNotEqualTo(b.id());
            assertThat(Files.readString(body(a))).contains(a.id(), "작성자: admin", "승인일:", "attachments/" + a.id() + "/1--manual.txt", "2--manual.txt").doesNotContain(b.id());
            Path aa = root.resolve("user/ghkang/attachments/" + a.id());
            Path ba = root.resolve("user/ghkang/attachments/" + b.id());
            assertThat(Files.readString(aa.resolve("1--manual.txt"))).contains("first attachment");
            assertThat(Files.readString(aa.resolve("2--manual.txt"))).contains("second attachment");
            assertThat(Files.readString(ba.resolve("1--manual.txt"))).contains("second attachment");
            var hits = service.search("PAIR_BODY_LOOKUP", admin, 20);
            assertThat(hits).hasSize(2).allMatch(SearchHit::approvedKnowledge);
            assertThat(service.search("PAIR_ATTACHMENT_LOOKUP", admin, 20)).allMatch(SearchHit::approvedKnowledge);
            assertThat(service.status().documents()).noneMatch(entry -> entry.path().contains(a.id()) || entry.path().contains(b.id()));
            var denied = new AccessContext("restricted", Set.of("user"), false, Set.of("user/ghkang"));
            assertThat(service.search("PAIR_BODY_LOOKUP", denied, 4)).isEmpty();
            assertThat(service.download(hits.get(0).documentId(), hits.get(0).version(), denied)).isEmpty();
        }
        byte[] before = Files.readAllBytes(body(a));
        try (var service = open()) {
            assertThat(service.status().healthy()).isTrue();
            assertThat(service.search("PAIR_BODY_LOOKUP", admin, 20)).hasSize(2).allMatch(SearchHit::approvedKnowledge);
        }
        assertThat(Files.readAllBytes(body(a))).isEqualTo(before);
    }

    @Test void pendingAndRejectedDoNotPublishAndApprovalCreatesBeforeReportingSuccess() throws Exception {
        try (var service = open()) {
            var pending = service.submit(request("PENDING_PUBLICATION_LOOKUP"), author);
            assertThat(Files.exists(body(pending))).isFalse();
            assertThat(service.reviewProposal(pending.id(), admin, KnowledgeProposal.State.APPROVED).state()).isEqualTo(KnowledgeProposal.State.APPROVED);
            assertThat(Files.readString(body(pending))).contains("PENDING_PUBLICATION_LOOKUP");
            var rejected = service.submit(request("REJECTED_PUBLICATION_LOOKUP"), author);
            service.reviewProposal(rejected.id(), admin, KnowledgeProposal.State.REJECTED);
            assertThat(Files.exists(body(rejected))).isFalse();
        }
    }

    @Test void withdrawalArchivesCurrentBytesForBothIntactAndEditedBodiesWithoutResurrectingEither() throws Exception {
        KnowledgeProposal intact, edited;
        String id, version;
        try (var service = open()) {
            intact = service.submit(request("WITHDRAW_COPY_LOOKUP"), admin);
            edited = service.submit(request("EDITED_COPY_LOOKUP"), admin);
            var hit = service.search("EDITED_COPY_LOOKUP", admin, 4).get(0); id=hit.documentId(); version=hit.version();
            Files.writeString(body(edited), "MANUAL_EDIT_LOOKUP user changed this copy");
            assertThat(service.download(id, version, admin)).isEmpty();
            assertThat(service.search("EDITED_COPY_LOOKUP", admin, 4)).isEmpty();
            assertThat(service.status().healthy()).isTrue();
            service.withdrawProposal(intact.id(), admin);
            service.withdrawProposal(edited.id(), admin);
            assertThat(Files.exists(body(intact))).isFalse();
            assertThat(body(edited)).doesNotExist();
            assertThat(service.syncNow().healthy()).isTrue();
            assertThat(service.search("MANUAL_EDIT_LOOKUP", admin, 4)).isEmpty();
            assertThat(service.search("WITHDRAW_COPY_LOOKUP", admin, 4)).isEmpty();
        }
        try (var paths = Files.walk(store.resolve("published-history").resolve(intact.id()))) {
            assertThat(paths.filter(Files::isRegularFile).toList()).hasSize(1);
        }
        try (var service = open()) {
            assertThat(service.status().healthy()).isTrue();
            assertThat(service.search("MANUAL_EDIT_LOOKUP", admin, 4)).isEmpty();
            assertThat(service.search("WITHDRAW_COPY_LOOKUP", admin, 4)).isEmpty();
        }
    }

    @Test void existingCollisionNeverGetsClaimedOrChangedAndApprovalCanRetryAfterCollisionRemoved() throws Exception {
        try (var service = open()) {
            var pending = service.submit(request("COLLISION_PROPOSAL_LOOKUP"), author);
            Files.writeString(body(pending), "UNRELATED_ORIGINAL_LOOKUP original");
            unavailable(() -> service.reviewProposal(pending.id(), admin, KnowledgeProposal.State.APPROVED));
            assertThat(Files.readString(body(pending))).isEqualTo("UNRELATED_ORIGINAL_LOOKUP original");
            assertThat(service.ownProposals(author).get(0).state()).isEqualTo(KnowledgeProposal.State.PENDING_APPROVAL);
            assertThat(service.search("UNRELATED_ORIGINAL_LOOKUP", admin, 4)).hasSize(1).noneMatch(SearchHit::approvedKnowledge);
            Files.move(body(pending), root.resolve("user/ghkang/original.md"));
            service.reviewProposal(pending.id(), admin, KnowledgeProposal.State.APPROVED);
            assertThat(Files.readString(body(pending))).contains("COLLISION_PROPOSAL_LOOKUP");
            assertThat(Files.readString(root.resolve("user/ghkang/original.md"))).contains("UNRELATED_ORIGINAL_LOOKUP");
        }
    }

    @Test void failedJsonSaveLeavesPendingAndRetryUsesNewApprovalTimeWithoutMixingArchivedCopies() throws Exception {
        try (var service = open()) {
            var pending = service.submit(request("RETRY_PUBLICATION_LOOKUP"), author);
            Path json = store.resolve("proposals.json"); Path backup = store.resolve("proposal-backup.json");
            Files.move(json, backup); Files.createDirectory(json);
            unavailable(() -> service.reviewProposal(pending.id(), admin, KnowledgeProposal.State.APPROVED));
            assertThat(Files.exists(body(pending))).isFalse();
            assertThat(service.ownProposals(author).get(0).state()).isEqualTo(KnowledgeProposal.State.PENDING_APPROVAL);
            Files.delete(json); Files.move(backup, json);
            service.reviewProposal(pending.id(), admin, KnowledgeProposal.State.APPROVED);
            assertThat(Files.readString(body(pending))).contains("RETRY_PUBLICATION_LOOKUP");
            service.withdrawProposal(pending.id(), author);
            assertThat(Files.exists(body(pending))).isFalse();
        }
        try (var service = open()) { assertThat(service.status().healthy()).isTrue(); }
    }

    @Test void failedDirectSubmitReservationSurvivesRestartAndCannotBecomeOfficialKnowledge() throws Exception {
        try (var service = open()) {
            Path json = store.resolve("proposals.json"); Path backup = store.resolve("proposal-backup.json");
            Files.move(json, backup); Files.createDirectory(json);
            unavailable(() -> service.submit(request("FAILED_DIRECT_LOOKUP"), admin));
            assertThat(service.ownProposals(admin)).isEmpty();
            Files.delete(json); Files.move(backup, json);
        }
        try (var service = open()) {
            assertThat(service.status().healthy()).isTrue();
            assertThat(service.search("FAILED_DIRECT_LOOKUP", admin, 4)).isEmpty();
        }
    }

    @Test void legacyApprovedProposalsMigrateAndAmendmentsNeverOverwriteOriginal() throws Exception {
        Path source = root.resolve("user/ghkang/original.md");
        Files.writeString(source, "# 원본\nAMEND_ORIGINAL_LOOKUP original");
        KnowledgeProposal amendment;
        try (var service = open()) {
            var hit = service.search("AMEND_ORIGINAL_LOOKUP", admin, 4).get(0);
            amendment = service.submit(new KnowledgeProposalController.SubmitRequest("user",hit.documentId(),hit.version(),
                    "같은 제목","AMEND_COPY_LOOKUP supplement",BooleanNode.TRUE),admin);
            assertThat(Files.readString(source)).contains("AMEND_ORIGINAL_LOOKUP").doesNotContain("AMEND_COPY_LOOKUP");
            assertThat(Files.readString(body(amendment))).contains(hit.documentId(),hit.version(),"AMEND_COPY_LOOKUP");
        }
        // Simulate pre-materialization APPROVED canonical JSON with no folderPath or ownership ledger.
        Files.delete(body(amendment)); Files.delete(store.resolve("published-files.json")); Files.delete(store.resolve(".published-initialized"));
        var json = new ObjectMapper(); var records = json.readTree(store.resolve("proposals.json").toFile());
        for(var record:records) ((ObjectNode)record).remove("folderPath");
        Files.writeString(store.resolve("proposals.json"),json.writeValueAsString(records));
        try(var service=open()) {
            assertThat(service.status().healthy()).isTrue();
            assertThat(Files.readString(body(amendment))).contains("AMEND_COPY_LOOKUP");
            assertThat(service.search("AMEND_COPY_LOOKUP",admin,4)).hasSize(1).allMatch(SearchHit::approvedKnowledge);
            Files.writeString(source,"# 변경\nAMEND_ORIGINAL_LOOKUP changed");
            assertThat(service.search("AMEND_COPY_LOOKUP",admin,4)).isEmpty();
            assertThat(Files.exists(body(amendment))).isFalse();
        }
    }

    @Test void missingOrMalformedOwnershipFailsBeforeUnrelatedSourcesAreScanned() throws Exception {
        KnowledgeProposal proposal;
        try(var service=open()){proposal=service.submit(request("LEDGER_LOSS_LOOKUP"),admin);service.withdrawProposal(proposal.id(),admin);}
        Files.writeString(body(proposal),"LEFTOVER_WITHDRAWN_LOOKUP");
        Files.delete(store.resolve("published-files.json"));
        try(var service=open()) {
            assertThat(service.status().healthy()).isFalse();
            assertThat(service.search("LEFTOVER_WITHDRAWN_LOOKUP",admin,4)).isEmpty();
        }
        String arbitrary="[{\"id\":\""+proposal.id()+"\",\"folder\":\"user/ghkang\",\"files\":[{\"path\":\"user/ghkang/original.md\",\"version\":\""+"0".repeat(64)+"\"}]}]";
        Files.writeString(store.resolve("published-files.json"),arbitrary);
        try(var service=open()){assertThat(service.status().healthy()).isFalse();}
        assertThat(Files.readString(body(proposal))).contains("LEFTOVER_WITHDRAWN_LOOKUP");
    }

    @Test void symlinkDestinationsStayUntouchedAndLongAttachmentNamesKeepTheirExtensions() throws Exception {
        Path original=Files.writeString(temp.resolve("manual.txt"),"EXTENSION_LOOKUP attachment");
        try(var service=open()) {
            String longName="가".repeat(150)+".txt";
            var proposal=service.submit(request("EXTENSION_BODY_LOOKUP"),admin,List.of(new KnowledgeService.Upload(original,longName,"설명")));
            try(var files=Files.list(root.resolve("user/ghkang/attachments/"+proposal.id()))) {
                var target=files.findFirst().orElseThrow();
                assertThat(target.getFileName().toString()).endsWith(".txt");
                assertThat(target.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(255);
            }
            var pending=service.submit(request("SYMLINK_PUBLISH_LOOKUP"),author);
            Path outside=Files.writeString(temp.resolve("outside.txt"),"outside unchanged");
            Files.createSymbolicLink(body(pending),outside);
            unavailable(()->service.reviewProposal(pending.id(),admin,KnowledgeProposal.State.APPROVED));
            assertThat(Files.readString(outside)).isEqualTo("outside unchanged");
        }
    }
    @Test void archiveKeepsBodyAndRelativeAttachmentTogetherInOnePublicationGeneration() throws Exception {
        Path upload = Files.writeString(temp.resolve("manual.txt"), "ARCHIVE_LINK_LOOKUP attachment");
        KnowledgeProposal proposal;
        try (var service = open()) {
            proposal = service.submit(request("ARCHIVE_BODY_LOOKUP"), admin,
                    List.of(new KnowledgeService.Upload(upload, "manual.txt", "설명")));
            service.withdrawProposal(proposal.id(), admin);
        }
        Path archive = store.resolve("published-history").resolve(proposal.id());
        List<Path> generations;
        try (var folders = Files.list(archive)) { generations = folders.toList(); }
        assertThat(generations).hasSize(1);
        Path archivedBody = generations.get(0).resolve(root.relativize(body(proposal)));
        assertThat(Files.readString(archivedBody)).contains("attachments/" + proposal.id() + "/1--manual.txt");
        assertThat(Files.readString(archivedBody.getParent().resolve("attachments/" + proposal.id() + "/1--manual.txt")))
                .contains("ARCHIVE_LINK_LOOKUP");
    }

    @Test void oversizedDerivedOutputPathIsRejectedBeforePublishingOrSavingApprovedState() throws Exception {
        // A short absolute base keeps the valid parent below macOS PATH_MAX before testing derived output validation.
        Path shortBase = Files.createTempDirectory(Path.of("/private/tmp"), "pub-");
        try {
            root = Files.createDirectories(shortBase.resolve("s")); index = shortBase.resolve("index"); store = shortBase.resolve("knowledge-submissions");
            String folder = "user/" + ("f".repeat(65) + "/").repeat(14) + "tail";
            Files.createDirectories(root.resolve(folder));
            Path upload = Files.writeString(temp.resolve("manual.txt"), "LONG_PATH_LOOKUP attachment");
            try (var service = open()) {
                unavailable(() -> service.submit(new KnowledgeProposalController.SubmitRequest("user", null, null,
                        "긴 경로", "LONG_PATH_BODY_LOOKUP", BooleanNode.TRUE, folder), admin,
                        List.of(new KnowledgeService.Upload(upload, "x".repeat(96) + ".txt", "설명"))));
                assertThat(service.ownProposals(admin)).isEmpty();
                try (var outputs = Files.list(root.resolve(folder))) { assertThat(outputs.toList()).isEmpty(); }
                assertThat(new ObjectMapper().readTree(store.resolve("published-files.json").toFile()).size()).isZero();
            }
            try (var service = open()) { assertThat(service.status().healthy()).isTrue(); }
        } finally {
            try (var paths = Files.walk(shortBase)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

}
