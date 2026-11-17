package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in, read-only source validation; all index and submission artifacts use isolated temporary paths. */
@EnabledIfEnvironmentVariable(named = "KNOWLEDGE_CORPUS_TEST", matches = "true")
class ActualKnowledgeCorpusTest {
    @Test void subpayQuestionFindsWbsSetupRow(@org.junit.jupiter.api.io.TempDir Path index) throws Exception {
        Path project = Path.of("../..").toRealPath();
        try (var service = new KnowledgeService(project.resolve("data/knowledge"), index.toRealPath().resolve("index"), index.toRealPath().resolve("submissions"))) {
            assertThat(service.search("서브페이 빌드 방법", KnowledgeService.SUPPORTED_PROJECTS, 4))
                    .anyMatch(hit -> hit.text().contains("서브페이"));
            var hits = service.search("서브페이 빌드 방법", Set.of("paye2u", "syslink"), 4);
            assertThat(hits).anySatisfy(hit -> {
                assertThat(hit.path()).isEqualTo("E2U_WBS.xlsx");
                assertThat(hit.section()).contains("업무사항");
                assertThat(hit.text()).contains("서브페이");
            });
        }
    }

    @Test void parsesTheExistingCorpusAndReportsOnlyCountsAndSourceMetadata(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        Path project = Path.of("../..").toRealPath();
        try (var input = java.nio.file.Files.newInputStream(project.resolve("data/knowledge/E2U_WBS.xlsx"));
             var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(input)) {
            int rows = 0;
            for (var sheet : workbook) rows += sheet.getPhysicalNumberOfRows();
            System.out.printf("WBS bytes=%d sheets=%d populatedRows=%d%n", java.nio.file.Files.size(project.resolve("data/knowledge/E2U_WBS.xlsx")), workbook.getNumberOfSheets(), rows);
        }
        try (var service = new KnowledgeService(project.resolve("data/knowledge"), temporary.toRealPath().resolve("index"), temporary.toRealPath().resolve("submissions"))) {
            var status = service.status();
            assertThat(status.healthy()).isTrue();
            assertThat(status.files()).isGreaterThan(100);
            assertThat(status.indexableDocuments()).isGreaterThan(0);
            System.out.printf("CORPUS files=%d searchable=%d chunks=%d quarantine=%d errors=%d skipped=%d%n",
                status.files(), status.indexableDocuments(), status.chunks(), status.quarantine(), status.errors(), status.skipped());
            System.out.println("CORPUS projects=" + status.projects() + " categories=" + status.categories());
            for (String query : new String[] { "MCHT", "mchtId", "정산 수수료", "취소 API", "월별 업무" }) {
                var hits = service.search(query, Set.of("paye2u", "syslink", "operations", "notes", "common"), 3);
                System.out.println("SEARCH query=" + query + " count=" + hits.size());
                for (var hit : hits) System.out.println("SOURCE " + hit.path() + " | " + hit.title() + " | " + hit.section() + " | row=" + hit.row());
            }
        }
    }
}
