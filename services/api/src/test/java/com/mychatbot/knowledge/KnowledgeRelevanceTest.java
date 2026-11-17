package com.mychatbot.knowledge;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.mychatbot.security.AccessContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRelevanceTest {
    @TempDir Path temporary;

    private void source(String path, String text) throws Exception {
        Path file = temporary.resolve("source").resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private KnowledgeService open() throws Exception {
        Path base = temporary.toRealPath();
        return new KnowledgeService(base.resolve("source"), base.resolve("index"));
    }

    @Test void merchantAppTransferExcludesGuidesThatOnlyMentionMerchantOrApp() throws Exception {
        source("e2u/transfer.md", "# 가맹점 앱 이관\n앱의 가맹점 ID 변경 절차를 확인합니다.");
        source("e2u/mixed.md", "# 혼합방식 매입사 서비스\n가맹점 앱에 v2 매입사 서비스를 등록합니다.");
        source("e2u/web.md", "# 가맹점 웹 이관\n가맹점 웹 이관 절차");
        try (var service = open()) {
            var author = new AccessContext("admin", Set.of("e2u"), true);
            service.submit(new KnowledgeProposalController.SubmitRequest("e2u", null, null,
                    "혼합방식 매입사 서비스", "가맹점 앱의 v2 매입사 서비스 등록 안내", BooleanNode.TRUE), author);
            for (String query : new String[] {"가맹점 앱 이관", "가맹점 앱 이관 방법 알려줘", "앱 이관", "앱 앱 이관 이관"}) {
                assertThat(service.search(query, author.projects(), 20)).as(query)
                        .extracting(SearchHit::path).containsExactly("e2u/transfer.md");
            }
            assertThat(service.search("웹 이관", author.projects(), 20))
                    .extracting(SearchHit::path).containsExactly("e2u/web.md");
        }
    }

    @Test void repeatedTermsAcrossFieldsCannotReplaceAMissingQueryTerm() throws Exception {
        source("e2u/common.md", "# 가맹점 가맹점\n가맹점 가맹점 가맹점");
        try (var service = open()) {
            assertThat(service.search("가맹점 이관", Set.of("e2u"), 20)).isEmpty();
        }
    }

    @Test void pathsDoNotMakeUnrelatedContentRelevantButExplicitFilenamesStillWork() throws Exception {
        source("e2u/가맹점/앱/이관/guide.md", "# 매입사 서비스\n혼합방식 매입사 등록 절차");
        try (var service = open()) {
            assertThat(service.search("가맹점 앱 이관", Set.of("e2u"), 20)).isEmpty();
            assertThat(service.search("guide.md", Set.of("e2u"), 20))
                    .extracting(SearchHit::path).containsExactly("e2u/가맹점/앱/이관/guide.md");
        }
    }

    @Test void matchingTitleRanksAheadAndSingleCharacterNounIsSearchable() throws Exception {
        source("e2u/title.md", "# 앱 이관\n가맹점 변경 절차");
        source("e2u/body.md", "# 운영 자료\n앱 이관 절차");
        source("e2u/web.md", "# 웹 이관\n웹 운영 절차");
        try (var service = open()) {
            assertThat(service.search("앱", Set.of("e2u"), 20)).extracting(SearchHit::path)
                    .containsExactlyInAnyOrder("e2u/title.md", "e2u/body.md");
            assertThat(service.search("앱 이관", Set.of("e2u"), 20).get(0).path()).isEqualTo("e2u/title.md");
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "KNOWLEDGE_RELEVANCE_TEST", matches = "true")
    void registeredCorpusExcludesUnrelatedMixedAcquirerGuide() throws Exception {
        Path project = Path.of("../..").toRealPath();
        Path copy = temporary.toRealPath().resolve("store");
        Path original = project.resolve("data/knowledge-submissions");
        if (Files.isDirectory(original)) {
            try (var paths = Files.walk(original)) {
                for (Path path : paths.toList()) {
                    Path destination = copy.resolve(original.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(destination);
                    else {
                        assertThat(Files.isSymbolicLink(path)).isFalse();
                        Files.copy(path, destination);
                    }
                }
            }
        }
        Path corpus = temporary.toRealPath().resolve("corpus");
        Path sourceRoot = project.resolve("data/knowledge");
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.toList()) {
                assertThat(Files.isSymbolicLink(path)).isFalse();
                Path destination = corpus.resolve(sourceRoot.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        try (var service = new KnowledgeService(corpus,
                temporary.toRealPath().resolve("index"), copy)) {
            assertThat(service.status().healthy()).isTrue();
            var projects = new ProjectCatalog(corpus.toString()).projects();
            for (String question : new String[] {"가맹점 앱 이관", "가맹점 앱 이관 방법 알려줘"}) {
                var hits = service.search(question, projects, 20);
                assertThat(hits).anyMatch(hit -> hit.path().endsWith("E2U_WBS.xlsx"))
                        .noneMatch(hit -> hit.title().contains("혼합방식"));
                System.out.println("Relevance verified: " + question + " -> "
                        + hits.stream().map(SearchHit::path).distinct().toList());
            }
        }
    }

    @Test void longerQuestionsAllowOnlyALimitedNumberOfMissingTerms() throws Exception {
        source("e2u/guide.md", "# 가맹점 앱 이관\n가맹점 앱 이관 승인 절차");
        source("e2u/partial.md", "# 앱 등록\n가맹점 앱 등록");
        try (var service = open()) {
            assertThat(service.search("가맹점 앱 이관 승인 점검", Set.of("e2u"), 20))
                    .extracting(SearchHit::path).containsExactly("e2u/guide.md");
        }
    }
}
