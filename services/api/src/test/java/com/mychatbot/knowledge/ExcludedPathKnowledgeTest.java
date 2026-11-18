package com.mychatbot.knowledge;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.mychatbot.ai.AiException;
import com.mychatbot.security.AccessContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExcludedPathKnowledgeTest {
    @TempDir Path temp;

    private Path source(String path, String content) throws Exception {
        Path file = temp.toRealPath().resolve("source").resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
    private KnowledgeService open() throws Exception {
        return new KnowledgeService(temp.toRealPath().resolve("source"), temp.toRealPath().resolve("index"));
    }
    private static KnowledgeProposalController.SubmitRequest amendment(SearchHit base, String content) {
        return new KnowledgeProposalController.SubmitRequest(base.project(), base.documentId(), base.version(),
                "업무 보완", content, BooleanNode.TRUE);
    }
    private static void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AiException.class,
                error -> assertThat(error.code()).isEqualTo("PROPOSAL_NOT_FOUND"));
    }

    @Test void subtreeExclusionsApplyBeforeLimitKeepSiblingsAndGuardDirectDownloads() throws Exception {
        for (int i = 0; i < 100; i++) source("user/ghkang/private-" + i + ".md", "# PRIVATE\nEXCLUSION_LOOKUP ".repeat(5));
        source("user/ghkang/nested/deep.md", "# PRIVATE\nEXCLUSION_LOOKUP");
        source("user/ghkang2/allowed.md", "# PUBLIC\nEXCLUSION_LOOKUP");
        source("user/jskim/allowed.md", "# PUBLIC\nEXCLUSION_LOOKUP");
        var all = new AccessContext("owner", Set.of("user"));
        var restricted = new AccessContext("employee", Set.of("user"), false, Set.of("user/ghkang"));
        try (var knowledge = open()) {
            var hidden = knowledge.search("EXCLUSION_LOOKUP", all, 20).stream()
                    .filter(hit -> hit.path().startsWith("user/ghkang/")).findFirst().orElseThrow();
            assertThat(knowledge.download(hidden.documentId(), hidden.version(), restricted)).isEmpty();
            assertThat(knowledge.download(hidden.documentId(), hidden.version(), all)).isPresent();
            assertThat(knowledge.search("EXCLUSION_LOOKUP", restricted, 1)).hasSize(1);
            assertThat(knowledge.search("EXCLUSION_LOOKUP", restricted, 20)).extracting(SearchHit::path)
                    .containsExactlyInAnyOrder("user/jskim/allowed.md", "user/ghkang2/allowed.md");
        }
        try (var knowledge = open()) {
            assertThat(knowledge.search("EXCLUSION_LOOKUP", restricted, 20)).hasSize(2);
        }
    }

    @Test void excludedAmendmentsAndAttachmentsCannotLeakThroughProposalOrApprovedRoutes() throws Exception {
        source("user/ghkang/private.md", "# 비공개\nPRIVATE_SOURCE_LOOKUP");
        source("user/jskim/public.md", "# 공개\nPUBLIC_SOURCE_LOOKUP");
        var author = new AccessContext("author", Set.of("user"));
        var admin = new AccessContext("reviewer", Set.of("user"));
        var restricted = new AccessContext("reviewer", Set.of("user"), false, Set.of("user/ghkang"));
        try (var knowledge = open()) {
            var base = knowledge.search("PRIVATE_SOURCE_LOOKUP", author, 4).get(0);
            var request = amendment(base, "PRIVATE_AMENDMENT_LOOKUP 확인 절차");
            denied(() -> knowledge.draft(new KnowledgeProposalController.DraftRequest(
                    "PRIVATE_SOURCE_LOOKUP 수정해 줘", base.project(), base.documentId(), base.version()), restricted));
            denied(() -> knowledge.submit(request, restricted));
            Path attachment = temp.resolve("notes.txt");
            Files.writeString(attachment, "PRIVATE_ATTACHMENT_LOOKUP 업무 설명");
            var pending = knowledge.submit(request, author, List.of(new KnowledgeService.Upload(attachment, "notes.txt", "업무 설명")));
            assertThat(knowledge.pendingProposals(restricted)).isEmpty();
            var restrictedAuthor = new AccessContext("author", Set.of("user"), false, Set.of("user/ghkang"));
            assertThat(knowledge.ownProposals(restrictedAuthor)).isEmpty();
            denied(() -> knowledge.reviewProposal(pending.id(), restricted, KnowledgeProposal.State.APPROVED));
            denied(() -> knowledge.reviewProposal(pending.id(), restricted, KnowledgeProposal.State.REJECTED));
            var file = pending.attachments().get(0);
            assertThat(knowledge.proposalAttachment(pending.id(), file.documentId(), file.version(), restricted, true)).isEmpty();
            assertThat(knowledge.proposalAttachment(pending.id(), file.documentId(), file.version(), restrictedAuthor, false)).isEmpty();
            assertThat(knowledge.proposalAttachment(pending.id(), file.documentId(), file.version(), admin, true)).isPresent();
            knowledge.reviewProposal(pending.id(), admin, KnowledgeProposal.State.APPROVED);
            var approved = knowledge.search("PRIVATE_AMENDMENT_LOOKUP", author, 4).get(0);
            assertThat(knowledge.search("PRIVATE_AMENDMENT_LOOKUP", restricted, 4)).isEmpty();
            assertThat(knowledge.search("PRIVATE_ATTACHMENT_LOOKUP", restricted, 4)).isEmpty();
            assertThat(knowledge.download(approved.documentId(), approved.version(), restricted)).isEmpty();
            assertThat(knowledge.download(file.documentId(), file.version(), restricted)).isEmpty();
            assertThat(knowledge.download(file.documentId(), file.version(), author)).isPresent();
            var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    "author", "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("PROJECT_user"),
                    new org.springframework.security.core.authority.SimpleGrantedAuthority("DENY_PATH_user/ghkang")));
            denied(() -> new KnowledgeProposalController(knowledge).withdraw(pending.id(), authentication).block());
            assertThat(knowledge.withdrawProposal(pending.id(), author).state()).isEqualTo(KnowledgeProposal.State.WITHDRAWN);
        }
    }

    @Test void wholeProjectExclusionsAlsoBlockNewProposalsWithoutOriginalPath() throws Exception {
        source("user/jskim/public.md", "# 공개\nPUBLIC_SOURCE_LOOKUP");
        var author = new AccessContext("author", Set.of("user"));
        var restricted = new AccessContext("employee", Set.of("user"), false, Set.of("user"));
        try (var knowledge = open()) {
            var request = new KnowledgeProposalController.SubmitRequest("user", null, null,
                    "새 업무", "NEW_PROJECT_LOOKUP 안내", BooleanNode.TRUE);
            denied(() -> knowledge.submit(request, restricted));
            var proposal = knowledge.submit(request, author);
            knowledge.reviewProposal(proposal.id(), new AccessContext("reviewer", Set.of("user")), KnowledgeProposal.State.APPROVED);
            assertThat(knowledge.search("NEW_PROJECT_LOOKUP", restricted, 4)).isEmpty();
            assertThat(knowledge.ownProposals(new AccessContext("author", Set.of("user"), false, Set.of("user")))).isEmpty();
        }
    }
    @Test void deletedSourceRemovesOwnProposalAndAttachmentsEvenWithoutExclusions() throws Exception {
        var original = source("user/ghkang/private.md", "# 문서\nDELETED_SOURCE_LOOKUP");
        var author = new AccessContext("author", Set.of("user"));
        try (var knowledge = open()) {
            var base = knowledge.search("DELETED_SOURCE_LOOKUP", author, 4).get(0);
            Path attachment = temp.resolve("notes.txt");
            Files.writeString(attachment, "업무 설명");
            var pending = knowledge.submit(amendment(base, "보완 절차"), author,
                    List.of(new KnowledgeService.Upload(attachment, "notes.txt", "업무 설명")));
            Files.delete(original);
            knowledge.syncNow();
            assertThat(knowledge.ownProposals(author)).extracting(KnowledgeProposal::id).doesNotContain(pending.id());
            var file = pending.attachments().get(0);
            assertThat(knowledge.proposalAttachment(pending.id(), file.documentId(), file.version(), author, false)).isEmpty();
        }
    }

}
