package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in, read-only source validation; artifacts go exclusively to .local/knowledge-index. */
@EnabledIfEnvironmentVariable(named = "KNOWLEDGE_CORPUS_TEST", matches = "true")
class ActualKnowledgeCorpusTest {
    @Test void parsesTheExistingCorpusAndReportsOnlyCountsAndSourceMetadata() throws Exception {
        Path project = Path.of("../..").toRealPath();
        try (var input = java.nio.file.Files.newInputStream(project.resolve("data/knowledge/E2U_WBS.xlsx"));
             var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(input)) {
            int rows = 0;
            for (var sheet : workbook) rows += sheet.getPhysicalNumberOfRows();
            System.out.printf("WBS bytes=%d sheets=%d populatedRows=%d%n", java.nio.file.Files.size(project.resolve("data/knowledge/E2U_WBS.xlsx")), workbook.getNumberOfSheets(), rows);
        }
        try (var service = new KnowledgeService(project.resolve("data/knowledge"), project.resolve(".local/knowledge-index"))) {
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
