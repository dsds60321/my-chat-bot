package com.mychatbot.knowledge;

import com.mychatbot.ai.AiException;
import com.mychatbot.knowledge.KnowledgeProposal.State;
import com.mychatbot.security.AccessContext;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class KnowledgeProposalTest {
    @TempDir Path temp;
    private final AccessContext author = new AccessContext("employee", Set.of("paye2u"));
    private final AccessContext admin = new AccessContext("reviewer", Set.of("paye2u"));
    private final AccessContext other = new AccessContext("other", Set.of("syslink"));

    @Test void directPublishPreservesConsentScopeAndAmendmentConflictChecks() throws Exception {
        Path original = source("paye2u/procedure.md", "# 절차\nDIRECT_ORIGINAL");
        var publisher = new AccessContext(author.userId(), author.projects(), true);
        try (var knowledge = open()) {
            var base = knowledge.search("DIRECT_ORIGINAL", publisher.projects(), 4).getFirst();
            assertCode(() -> knowledge.submit(new KnowledgeProposalController.SubmitRequest(
                    base.project(), base.documentId(), base.version(), "정정", "변경", BooleanNode.FALSE), publisher),
                    "PROPOSAL_CONSENT_REQUIRED");
            assertCode(() -> knowledge.submit(request("syslink", null, "접근 범위 밖"), publisher), "PROPOSAL_NOT_FOUND");
            var published = knowledge.submit(request(base.project(), base, "DIRECT_PUBLISHED 검증 절차"), publisher);
            assertThat(published.state()).isEqualTo(State.APPROVED);
            assertThat(knowledge.pendingProposals(admin)).isEmpty();
            assertThat(knowledge.search("DIRECT_PUBLISHED", publisher.projects(), 4)).anyMatch(SearchHit::approvedKnowledge);
            assertThat(knowledge.search("DIRECT_PUBLISHED", other.projects(), 4)).isEmpty();
            assertThat(Files.readString(original)).contains("DIRECT_ORIGINAL").doesNotContain("DIRECT_PUBLISHED");
            assertCode(() -> knowledge.submit(request(base.project(), base, "중복 정정"), publisher), "PROPOSAL_CONFLICT");
        }
    }

    @Test void explicitConsentScopedReviewRestartAndWithdrawalControlRagAndDownload() throws Exception {
        Files.createDirectories(temp.resolve("source/paye2u"));
        try (var knowledge = open()) {
            var draft = knowledge.draft(new KnowledgeProposalController.DraftRequest(
                    "ONBOARDING_LOOKUP 절차를 기록한다. 추가해 주세요.", "paye2u", null, null), author);
            assertThat(draft.content()).isEqualTo("ONBOARDING_LOOKUP 절차를 기록한다");
            assertThat(knowledge.ownProposals(author.userId())).isEmpty();
            var invalid = new KnowledgeProposalController.SubmitRequest("paye2u", null, null, "업무 안내",
                    "ONBOARDING_LOOKUP 확인", TextNode.valueOf("true"));
            assertCode(() -> knowledge.submit(invalid, author), "PROPOSAL_CONSENT_REQUIRED");
            assertCode(() -> knowledge.submit(request("paye2u", null, "password=synthetic"), author), "SENSITIVE_INPUT");
            var pending = knowledge.submit(request("paye2u", null, "ONBOARDING_LOOKUP 확인"), author);
            assertThat(pending.state()).isEqualTo(State.PENDING_APPROVAL);
            assertThat(knowledge.search("ONBOARDING_LOOKUP", author.projects(), 4)).isEmpty();
            assertThat(knowledge.ownProposals(other.userId())).isEmpty();
            assertThat(knowledge.pendingProposals(other)).isEmpty();
            assertCode(() -> knowledge.reviewProposal(pending.id(), other, State.APPROVED), "PROPOSAL_NOT_FOUND");
            assertCode(() -> knowledge.reviewProposal(pending.id(), author, State.APPROVED), "PROPOSAL_FORBIDDEN");
            assertThat(knowledge.reviewProposal(pending.id(), admin, State.APPROVED).state()).isEqualTo(State.APPROVED);
            assertThat(knowledge.search("ONBOARDING_LOOKUP", author.projects(), 4)).isNotEmpty()
                    .allMatch(SearchHit::approvedKnowledge);
        }
        try (var reopened = open()) {
            var approved = reopened.ownProposals(author.userId()).getFirst();
            assertThat(approved.state()).isEqualTo(State.APPROVED);
            var hit = reopened.search("ONBOARDING_LOOKUP", author.projects(), 4).getFirst();
            assertThat(reopened.search("ONBOARDING_LOOKUP", other.projects(), 4)).isEmpty();
            assertThat(reopened.download(hit.documentId(), hit.version(), other.projects())).isEmpty();
            byte[] markdown = reopened.download(hit.documentId(), hit.version(), author.projects()).orElseThrow().bytes();
            assertThat(new String(markdown, StandardCharsets.UTF_8)).contains("ONBOARDING_LOOKUP");
            assertCode(() -> reopened.withdrawProposal(approved.id(), other.userId()), "PROPOSAL_NOT_FOUND");
            assertThat(reopened.withdrawProposal(approved.id(), author.userId()).state()).isEqualTo(State.WITHDRAWN);
            assertThat(reopened.download(hit.documentId(), hit.version(), author.projects())).isEmpty();
            assertThat(reopened.search("ONBOARDING_LOOKUP", author.projects(), 4)).isEmpty();
        }
    }

    @Test void amendmentPreviewUsesRelevantSourceAndApprovalPreservesOriginalAndRejectsStaleVersion() throws Exception {
        Path original = source("paye2u/procedure.md", "# 처음\n일반 소개\n\n# 정산 절차\nAMENDMENT_LOOKUP 이전 처리 단계");
        byte[] originalBytes = Files.readAllBytes(original);
        try (var knowledge = open()) {
            var base = knowledge.search("AMENDMENT_LOOKUP", author.projects(), 4).getFirst();
            var preview = knowledge.draft(new KnowledgeProposalController.DraftRequest(
                    "AMENDMENT_LOOKUP 처리 단계에는 검증 절차를 포함한다. 추가해 줘", base.project(), base.documentId(), base.version()), author);
            assertThat(preview.before()).contains("이전 처리 단계");
            assertThat(preview.sourceDocumentId()).isEqualTo(base.documentId());
            var first = knowledge.submit(request(base.project(), base, "AMENDMENT_LOOKUP 검증 단계 NEW_APPROVED_DETAIL"), author);
            var second = knowledge.submit(request(base.project(), base, "AMENDMENT_LOOKUP 별도 변경"), author);
            knowledge.reviewProposal(first.id(), admin, State.APPROVED);
            assertCode(() -> knowledge.reviewProposal(second.id(), admin, State.APPROVED), "PROPOSAL_CONFLICT");
            assertThat(Files.readAllBytes(original)).isEqualTo(originalBytes);
            var hit = knowledge.search("NEW_APPROVED_DETAIL", author.projects(), 4).getFirst();
            assertThat(hit.approvedKnowledge()).isTrue();
            Files.writeString(original, "# 정산 절차\nAMENDMENT_LOOKUP 원본의 새 버전");
            assertThat(knowledge.download(hit.documentId(), hit.version(), author.projects())).isEmpty();
            assertCode(() -> knowledge.reviewProposal(second.id(), admin, State.APPROVED), "PROPOSAL_CONFLICT");
            assertThat(knowledge.search("NEW_APPROVED_DETAIL", author.projects(), 4)).isEmpty();
            assertThat(knowledge.reviewProposal(second.id(), admin, State.REJECTED).state()).isEqualTo(State.REJECTED);
        }
    }

    @Test void mixedSourceAmendmentInheritsBothProjectGrantsForApprovalSearchAndDownload() throws Exception {
        Files.createDirectories(temp.resolve("source/paye2u"));
        Files.createDirectories(temp.resolve("source/syslink"));
        Path wbs = source("E2U_WBS.xlsx", "");
        try (var workbook = new XSSFWorkbook()) {
            workbook.createSheet("업무사항").createRow(0).createCell(0).setCellValue("paye2u syslink WBS_PROCEDURE");
            try (var stream = Files.newOutputStream(wbs)) { workbook.write(stream); }
        }
        var projects = Set.of("paye2u", "syslink");
        var mixedAuthor = new AccessContext(author.userId(), projects);
        var mixedAdmin = new AccessContext(admin.userId(), projects);
        try (var knowledge = open()) {
            var base = knowledge.search("WBS_PROCEDURE", projects, 4).getFirst();
            assertCode(() -> knowledge.submit(request("paye2u", base, "변경 내용"), author), "PROPOSAL_NOT_FOUND");
            assertCode(() -> knowledge.submit(request(base.project(), base, "변경 내용"), author), "PROPOSAL_NOT_FOUND");
            var pending = knowledge.submit(request(base.project(), base, "APPROVED_WBS_DETAIL 보완 내용"), mixedAuthor);
            assertThat(knowledge.pendingProposals(admin)).isEmpty();
            assertCode(() -> knowledge.reviewProposal(pending.id(), admin, State.APPROVED), "PROPOSAL_NOT_FOUND");
            knowledge.reviewProposal(pending.id(), mixedAdmin, State.APPROVED);
            var hit = knowledge.search("APPROVED_WBS_DETAIL", projects, 4).getFirst();
            assertThat(hit.approvedKnowledge()).isTrue();
            assertThat(knowledge.search("APPROVED_WBS_DETAIL", author.projects(), 4)).isEmpty();
            assertThat(knowledge.search("APPROVED_WBS_DETAIL", other.projects(), 4)).isEmpty();
            assertThat(knowledge.download(hit.documentId(), hit.version(), author.projects())).isEmpty();
            assertThat(knowledge.download(hit.documentId(), hit.version(), projects)).isPresent();
        }
    }

    private KnowledgeProposalController.SubmitRequest request(String project, SearchHit base, String content) {
        return new KnowledgeProposalController.SubmitRequest(project, base == null ? null : base.documentId(),
                base == null ? null : base.version(), "검토용 업무 지식", content, BooleanNode.TRUE);
    }

    private KnowledgeService open() throws Exception {
        Path real = temp.toRealPath();
        return new KnowledgeService(Files.createDirectories(real.resolve("source")), real.resolve("index"));
    }

    private Path source(String relative, String text) throws Exception {
        Path path = temp.resolve("source").resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text);
    }

    private static void assertCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(AiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
