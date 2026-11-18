package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeServiceTest {
    @TempDir Path temp;

    private Path source(String path, String content) throws Exception {
        Path destination = temp.resolve("source").resolve(path);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, content);
        return destination;
    }
    private KnowledgeService service() throws Exception {
        Path canonical = temp.toRealPath();
        return new KnowledgeService(canonical.resolve("source"), canonical.resolve("index"));
    }

    @Test void newProjectFolderAppearsWhileRunningAndDocumentsKeepTheirScope() throws Exception {
        Path root = temp.toRealPath().resolve("source");
        var catalog = new ProjectCatalog(root.toString());
        try (var knowledge = service()) {
            assertThat(catalog.projects()).isEmpty();
            Files.createDirectory(root.resolve("e2u"));
            Files.createSymbolicLink(root.resolve("linked"), root.resolve("e2u"));
            assertThat(catalog.projects()).containsExactly("e2u");
            source("e2u/guide.md", "# Guide\nNEW_PROJECT_LOOKUP paye2u syslink");
            assertThat(knowledge.search("NEW_PROJECT_LOOKUP", Set.of("e2u"), 4))
                    .extracting(SearchHit::project).containsExactly("e2u");
            Files.delete(root.resolve("e2u/guide.md"));
            Files.delete(root.resolve("e2u"));
            assertThat(catalog.projects()).isEmpty();
            assertThat(knowledge.search("NEW_PROJECT_LOOKUP", Set.of("e2u"), 4)).isEmpty();
            assertThat(knowledge.search("NEW_PROJECT_LOOKUP", Set.of("paye2u", "syslink"), 4)).isEmpty();
        }
    }

    @Test void firstScanCreatesConfiguredSourceAndIndexesFilesAddedLater() throws Exception {
        Path canonical = temp.toRealPath();
        Path root = canonical.resolve("custom/knowledge");
        Path index = canonical.resolve("state/index");
        try (var knowledge = new KnowledgeService(root, index)) {
            assertThat(root).isDirectory();
            assertThat(index).isDirectory();
            assertThat(knowledge.status().healthy()).isTrue();
            assertThat(knowledge.search("ONBOARDING", Set.of("common"), 4)).isEmpty();
            Path doc = root.resolve("common/guide.md");
            Files.createDirectories(doc.getParent());
            Files.writeString(doc, "# Guide\nONBOARDING");
            assertThat(knowledge.search("ONBOARDING", Set.of("common"), 4))
                    .extracting(SearchHit::path).containsExactly("common/guide.md");
        }
    }

    @Test void explicitCommonFolderKeepsSqlSearchableDespiteProjectNamesInContent() throws Exception {
        try (var knowledge = service()) {
            Path doc = source("common/20261101_bin.md", "# 카드 BIN 최신화 SQL\nCREATE DEFINER=`syslink`@`%` TRIGGER BIN_UPDATE;");
            assertThat(knowledge.syncNow().documents()).singleElement().satisfies(entry -> {
                assertThat(entry.project()).isEqualTo("common");
                assertThat(entry.state()).isEqualTo("ACTIVE");
            });
            assertThat(knowledge.search("BIN", Set.of("common"), 4)).isNotEmpty();
            var hits = knowledge.search("BIN 최신화 SQL 알려줘", Set.of("common"), 4);
            assertThat(hits).isNotEmpty().allMatch(hit -> hit.project().equals("common"));
            assertThat(knowledge.search("MISSING_BIN_IDENTIFIER 최신화", Set.of("common"), 4)).isEmpty();
            assertThat(knowledge.search("missing-bin.md 최신화", Set.of("common"), 4)).isEmpty();
            var hit = hits.get(0);
            assertThat(knowledge.download(hit.documentId(), hit.version(), Set.of("common"))).isPresent();
            assertThat(knowledge.search("BIN", Set.of("syslink"), 4)).isEmpty();
            assertThat(knowledge.download(hit.documentId(), hit.version(), Set.of("syslink"))).isEmpty();
            var excluded = new com.mychatbot.security.AccessContext("user", Set.of("common"), false, Set.of("common/20261101_bin.md"));
            assertThat(knowledge.search("BIN", excluded, 4)).isEmpty();
            assertThat(knowledge.download(hit.documentId(), hit.version(), excluded)).isEmpty();
            Files.writeString(doc, "# 카드 BIN 최신화 SQL\nsyslink paye2u UPDATED_BIN_PROCEDURE");
            assertThat(knowledge.search("UPDATED_BIN_PROCEDURE", Set.of("common"), 4)).isNotEmpty();
        }
    }

    @Test void exactIdentifiersKoreanAndServerAcl() throws Exception {
        source("paye2u/tables.md", "# 결제 데이터베이스\nMCHT 테이블 mchtId VARCHAR 가맹점 식별자\n승인 취소 이력은 PAY_CANCL에 저장한다.");
        source("syslink/guide.md", "# 별도 연동\nMCHT 은행 연동 목록");
        source("unknown/private.md", "# 비공개\nMCHT unknown workspace");
        try (var knowledge = service()) {
            assertThat(knowledge.status().healthy()).isTrue();
            var exact = knowledge.search("MCHT 테이블 컬럼 알려줘", Set.of("paye2u"), 5);
            assertThat(exact).isNotEmpty().allMatch(hit -> hit.project().equals("paye2u"));
            assertThat(exact.get(0).path()).isEqualTo("paye2u/tables.md");
            assertThat(exact.get(0).section()).contains("결제 데이터베이스");
            assertThat(knowledge.search("MCHT", KnowledgeService.SUPPORTED_PROJECTS, 5))
                    .extracting(SearchHit::project).containsExactlyInAnyOrder("paye2u", "syslink");
            assertThat(knowledge.search("가맹점 식별자는 무엇인가요", Set.of("paye2u"), 5)).isNotEmpty();
            assertThat(knowledge.search("MCHT", Set.of(), 5)).isEmpty();
            assertThat(knowledge.search("MCHT", Set.of("missing"), 5)).isEmpty();
            assertThat(knowledge.search("XYZZY_UNRELATED_Q99", Set.of("paye2u"), 5)).isEmpty();
            assertThat(knowledge.search("*:*", Set.of("paye2u"), 5)).isEmpty();
            assertThat(knowledge.search("알려 주세요", Set.of("paye2u"), 5)).isEmpty();
        }
    }

    @Test void detectsAdditionModificationDeletionAndSameTimestampChanges() throws Exception {
        Path doc = source("paye2u/table.md", "# Tables\nBEFORE_FIELD one");
        try (var knowledge = service()) {
            assertThat(knowledge.search("BEFORE_FIELD", Set.of("paye2u"), 3)).hasSize(1);
            FileTime mtime = Files.getLastModifiedTime(doc);
            Files.writeString(doc, "# Tables\nAFTER_COLUMN two");
            Files.setLastModifiedTime(doc, mtime);
            assertThat(knowledge.search("BEFORE_FIELD", Set.of("paye2u"), 3)).isEmpty();
            assertThat(knowledge.search("AFTER_COLUMN", Set.of("paye2u"), 3)).hasSize(1);
            source("paye2u/added.md", "# Added\nADDED_EVENT approved");
            assertThat(knowledge.search("ADDED_EVENT", Set.of("paye2u"), 3)).hasSize(1);
            Files.delete(doc);
            assertThat(knowledge.search("AFTER_COLUMN", Set.of("paye2u"), 3)).isEmpty();
            assertThat(knowledge.status().deleted()).isEqualTo(1);
            assertThat(Files.readString(temp.resolve("index/manifest.json"))).contains("DELETED");
        }
    }

    @Test void naturalKoreanQuestionRanksRelatedProcedureAboveGenericSettlementRows() throws Exception {
        for (int i = 0; i < 8; i++) {
            source("paye2u/generic-" + i + ".md", "# 정산\n차액정산 차액정산 정산 금액 알리는 절차");
        }
        source("paye2u/procedure.md", "# 업무 절차\n차액 정산 재매입 절차와 처리 순서를 확인한다.");
        source("syslink/private.md", "# 차액 정산 재매입\n차액 정산 재매입 전용 절차");
        try (var knowledge = service()) {
            var hits = knowledge.search("차액정산 재매입하는법 알려줘", Set.of("paye2u"), 4);
            assertThat(hits).isNotEmpty().allMatch(hit -> hit.project().equals("paye2u"));
            assertThat(hits.get(0).path()).isEqualTo("paye2u/procedure.md");
        }
    }

    @Test void completeSecretDocumentIsQuarantinedAndChangedDocumentRevokes() throws Exception {
        Path doc = source("paye2u/table.md", "# Public\nPUBLIC_TOPIC harmless");
        try (var knowledge = service()) {
            assertThat(knowledge.search("PUBLIC_TOPIC", Set.of("paye2u"), 5)).isNotEmpty();
            Files.writeString(doc, "# Public\nPUBLIC_TOPIC harmless\napi_key = synthetic_sensitive_value_123");
            assertThat(knowledge.search("PUBLIC_TOPIC", KnowledgeService.SUPPORTED_PROJECTS, 5)).isEmpty();
            assertThat(knowledge.status().quarantine()).isEqualTo(1);
            assertThat(Files.readString(temp.resolve("index/manifest.json"))).doesNotContain("synthetic_sensitive_value_123");
            Files.writeString(doc, "# Public\nPUBLIC_TOPIC harmless again");
            assertThat(knowledge.search("PUBLIC_TOPIC", Set.of("paye2u"), 5)).isNotEmpty();
        }
    }

    @Test void sourceDownloadRequiresCurrentVersionAndProjectAndNeverFollowsSymlinks() throws Exception {
        String original = "# 원본\nSOURCE_TEST_IDENTIFIER alpha";
        Path doc = source("paye2u/업무 자료.md", original);
        Path quarantined = source("paye2u/restricted.md", "api_key = synthetic_sensitive_value_123");
        try (var knowledge = service()) {
            var hit = knowledge.search("SOURCE_TEST_IDENTIFIER", Set.of("paye2u"), 5).get(0);
            var snapshot = knowledge.download(hit.documentId(), hit.version(), Set.of("paye2u")).orElseThrow();
            assertThat(snapshot.filename()).isEqualTo("업무 자료.md");
            assertThat(snapshot.bytes()).isEqualTo(Files.readAllBytes(doc));
            assertThat(knowledge.download(hit.documentId(), hit.version(), Set.of("syslink"))).isEmpty();
            assertThat(knowledge.download(hit.documentId(), null, Set.of("paye2u"))).isEmpty();
            assertThat(knowledge.download("../업무 자료.md", hit.version(), Set.of("paye2u"))).isEmpty();

            var restricted = knowledge.status().documents().stream()
                    .filter(entry -> entry.path().endsWith(quarantined.getFileName().toString())).findFirst().orElseThrow();
            assertThat(restricted.state()).isEqualTo("QUARANTINED");
            assertThat(knowledge.download(restricted.documentId(), restricted.version(), KnowledgeService.SUPPORTED_PROJECTS)).isEmpty();

            FileTime mtime = Files.getLastModifiedTime(doc);
            Files.writeString(doc, original.replace("alpha", "omega"));
            Files.setLastModifiedTime(doc, mtime);
            assertThat(knowledge.download(hit.documentId(), hit.version(), Set.of("paye2u"))).isEmpty();
            var updated = knowledge.search("SOURCE_TEST_IDENTIFIER", Set.of("paye2u"), 5).get(0);
            assertThat(updated.version()).isNotEqualTo(hit.version());
            assertThat(knowledge.download(updated.documentId(), updated.version(), Set.of("paye2u"))).isPresent();

            Path outside = temp.resolve("outside.md");
            Files.move(doc, outside);
            assertThat(knowledge.download(updated.documentId(), updated.version(), Set.of("paye2u"))).isEmpty();
            Files.createSymbolicLink(doc, outside);
            assertThat(knowledge.download(updated.documentId(), updated.version(), Set.of("paye2u"))).isEmpty();
        }
    }

    @Test void symlinksAndHiddenSourcesAreNotFollowedAndMissingRootFailsClosed() throws Exception {
        source("paye2u/ok.md", "# Safe\nVISIBLE_TOPIC");
        source("paye2u/.hidden.md", "HIDDEN_TOPIC");
        source("paye2u/~$lock.md", "LOCK_TOPIC");
        Path outside = temp.resolve("outside.md");
        Files.writeString(outside, "OUTSIDE_TOPIC");
        Files.createSymbolicLink(temp.resolve("source/paye2u/link.md"), outside);
        try (var knowledge = service()) {
            assertThat(knowledge.search("OUTSIDE_TOPIC", Set.of("paye2u"), 5)).isEmpty();
            assertThat(knowledge.search("HIDDEN_TOPIC", Set.of("paye2u"), 5)).isEmpty();
            assertThat(knowledge.search("LOCK_TOPIC", Set.of("paye2u"), 5)).isEmpty();
            Files.move(temp.resolve("source"), temp.resolve("removed-source"));
            assertThat(knowledge.search("VISIBLE_TOPIC", Set.of("paye2u"), 5)).isEmpty();
            assertThat(temp.resolve("source")).doesNotExist();
            assertThat(knowledge.status().healthy()).isFalse();
        }
    }

    @Test void multiSheetExcelRetainsHeadersRowsFormulaCachedValueAndMerge() throws Exception {
        Path workbookPath = source("paye2u/schema.xlsx", "");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Merchant schema");
            sheet.createRow(0).createCell(0).setCellValue("MCHT 가맹점 테이블");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            var header = sheet.createRow(1);
            header.createCell(0).setCellValue("테이블명");
            header.createCell(1).setCellValue("컬럼명");
            header.createCell(2).setCellValue("타입");
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("MCHT");
            row.createCell(1).setCellValue("mchtId");
            row.createCell(2).setCellValue("VARCHAR");
            var formula = row.createCell(3);
            formula.setCellFormula("21*2");
            formula.getCTCell().setV("42");
            workbook.createSheet("Settlement").createRow(0).createCell(0).setCellValue("SETTLEMENT_LEDGER 정산");
            try (var output = Files.newOutputStream(workbookPath)) { workbook.write(output); }
        }
        var extracted = new DocumentExtractor().extract(workbookPath);
        assertThat(extracted.chunks()).anySatisfy(chunk -> {
            assertThat(chunk.section()).isEqualTo("Merchant schema");
            assertThat(chunk.row()).isEqualTo(3);
            assertThat(chunk.text()).contains("Merged: A1:C1", "테이블명", "컬럼명", "MCHT", "mchtId", "formula[21*2] cached[42]");
        });
        assertThat(extracted.chunks()).anyMatch(chunk -> chunk.section().equals("Settlement"));
        try (var knowledge = service()) {
            assertThat(knowledge.search("mchtId", Set.of("paye2u"), 5)).anyMatch(hit -> hit.row() != null && hit.row() == 3);
        }
    }

    @Test void markdownDoesNotInterpretHeadingsInsideCodeOrDetachDescription() {
        String description = "문맥설명 ".repeat(430);
        var chunks = DocumentExtractor.markdown("# Main\n## SQL\n" + description + "\n\n```sql\n# code heading\nSELECT * FROM MCHT;\n```\n\n## Next\nOther text");
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.section()).isEqualTo("Main > SQL");
            assertThat(chunk.text()).contains(description, "# code heading", "SELECT * FROM MCHT;", "```sql");
        });
        assertThat(chunks).noneMatch(chunk -> chunk.section().contains("code heading"));
    }

    @Test void corruptAndUnsupportedFilesDoNotStopValidDocuments() throws Exception {
        source("paye2u/good.md", "# Good\nGOOD_IDENTIFIER");
        source("paye2u/broken.xlsx", "not a zip workbook");
        source("paye2u/broken.docx", "not a zip document");
        source("paye2u/image.png", "not parsed");
        try (var knowledge = service()) {
            assertThat(knowledge.status().healthy()).isTrue();
            assertThat(knowledge.status().errors()).isEqualTo(2);
            assertThat(knowledge.status().skipped()).isEqualTo(1);
            assertThat(knowledge.search("GOOD_IDENTIFIER", Set.of("paye2u"), 5)).hasSize(1);
        }
    }

    @Test void persistentIndexAndMetadataReopenWithoutReextractingUnchangedSources() throws Exception {
        source("paye2u/good.md", "# Good\nPERSISTED_IDENTIFIER");
        String indexedAt;
        try (var knowledge = service()) { indexedAt = knowledge.status().documents().get(0).indexedAt(); }
        try (var knowledge = service()) {
            assertThat(knowledge.search("PERSISTED_IDENTIFIER", Set.of("paye2u"), 5)).hasSize(1);
            assertThat(knowledge.status().documents().get(0).indexedAt()).isEqualTo(indexedAt);
        }
    }

    @Test void mixedProjectsAndHistoricalVersionsCannotBecomeCommon() throws Exception {
        source("mixed.md", "paye2u syslink MIXED_IDENTIFIER");
        source("notes/mixed.md", "paye2u syslink MIXED_IDENTIFIER");
        source("paye2u/before/table.md", "OLD_IDENTIFIER");
        source("paye2u/table_rollback.md", "ROLLBACK_IDENTIFIER");
        source("common/general.md", "COMMON_IDENTIFIER");
        try (var knowledge = service()) {
            assertThat(knowledge.search("MIXED_IDENTIFIER", Set.of("common", "notes", "paye2u", "syslink"), 5)).isEmpty();
            assertThat(knowledge.status().documents()).filteredOn(entry -> entry.path().endsWith("mixed.md"))
                    .hasSize(2).allMatch(entry -> entry.project().equals("unassigned") && entry.error().equals("unassigned-project"));
            assertThat(knowledge.search("OLD_IDENTIFIER", Set.of("paye2u"), 5)).isEmpty();
            assertThat(knowledge.search("ROLLBACK_IDENTIFIER", Set.of("paye2u"), 5)).isEmpty();
            assertThat(knowledge.search("COMMON_IDENTIFIER", Set.of("common"), 5)).hasSize(1);
        }
    }

    @Test void rootWbsSearchAndOriginalDownloadRequireBothProjects() throws Exception {
        Files.createDirectories(temp.resolve("source/paye2u"));
        Files.createDirectories(temp.resolve("source/syslink"));
        Path workbookPath = source("E2U_WBS.xlsx", "");
        Path sameNameElsewhere = source("notes/E2U_WBS.xlsx", "");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("업무사항");
            sheet.createRow(0).createCell(0).setCellValue("업무 내용");
            sheet.createRow(1).createCell(0).setCellValue("paye2u syslink WBS_SHARED_PROCEDURE");
            try (var output = Files.newOutputStream(workbookPath)) { workbook.write(output); }
        }
        Files.copy(workbookPath, sameNameElsewhere, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Set<String> both = Set.of("paye2u", "syslink");
        try (var knowledge = service()) {
            var hits = knowledge.search("WBS_SHARED_PROCEDURE", both, 5);
            assertThat(hits).isNotEmpty().allMatch(hit -> hit.path().equals("E2U_WBS.xlsx") && hit.project().equals("paye2u+syslink"));
            var hit = hits.get(0);
            var original = knowledge.download(hit.documentId(), hit.version(), both).orElseThrow();
            assertThat(original.filename()).isEqualTo("E2U_WBS.xlsx");
            assertThat(original.bytes()).isEqualTo(Files.readAllBytes(workbookPath));

            for (Set<String> grants : java.util.List.of(Set.of("paye2u", "notes"), Set.of("syslink", "common"),
                    Set.of("paye2u+syslink"), Set.of("paye2u", "paye2u+syslink"))) {
                assertThat(knowledge.search("WBS_SHARED_PROCEDURE", grants, 5)).isEmpty();
                assertThat(knowledge.download(hit.documentId(), hit.version(), grants)).isEmpty();
            }

            try (var workbook = new XSSFWorkbook()) {
                var sheet = workbook.createSheet("업무사항");
                sheet.createRow(0).createCell(0).setCellValue("업무 내용");
                sheet.createRow(1).createCell(0).setCellValue("paye2u syslink WBS_SHARED_PROCEDURE api_key=synthetic_secret");
                try (var output = Files.newOutputStream(workbookPath)) { workbook.write(output); }
            }
            assertThat(knowledge.search("WBS_SHARED_PROCEDURE", KnowledgeService.SUPPORTED_PROJECTS, 5)).isEmpty();
            assertThat(knowledge.download(hit.documentId(), hit.version(), both)).isEmpty();
            assertThat(knowledge.status().documents()).filteredOn(entry -> entry.path().equals("E2U_WBS.xlsx"))
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.state()).isEqualTo("QUARANTINED");
                        assertThat(knowledge.download(entry.documentId(), entry.version(), both)).isEmpty();
                    });
        }
    }

    @Test void excelCredentialColumnQuarantinesTheWholeWorkbook() throws Exception {
        Path workbookPath = source("paye2u/credentials.xlsx", "");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Data");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("ID");
            header.createCell(1).setCellValue("Password");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("synthetic-user");
            row.createCell(1).setCellValue("synthetic-only-123");
            try (var output = Files.newOutputStream(workbookPath)) { workbook.write(output); }
        }
        assertThatThrownBy(() -> new DocumentExtractor().extract(workbookPath)).isInstanceOf(DocumentExtractor.Quarantined.class);
        try (var knowledge = service()) { assertThat(knowledge.status().quarantine()).isEqualTo(1); }
    }

    @Test void docxCredentialTableQuarantinesTheWholeDocument() throws Exception {
        Path docx = source("paye2u/credentials.docx", "");
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("ID");
            table.getRow(0).getCell(1).setText("Password");
            table.getRow(1).getCell(0).setText("synthetic-user");
            table.getRow(1).getCell(1).setText("synthetic-only-123");
            try (var output = Files.newOutputStream(docx)) { document.write(output); }
        }
        assertThatThrownBy(() -> new DocumentExtractor().extract(docx)).isInstanceOf(DocumentExtractor.Quarantined.class);
    }

    @Test void policyRevisionRebuildsPersistedIndex() throws Exception {
        source("paye2u/good.md", "# Good\nPOLICY_IDENTIFIER");
        try (var knowledge = service()) { assertThat(knowledge.status().indexableDocuments()).isEqualTo(1); }
        Files.writeString(temp.resolve("index/policy.version"), "old-policy");
        Files.writeString(temp.resolve("index/manifest.json"), "[]");
        try (var knowledge = service()) {
            assertThat(knowledge.search("POLICY_IDENTIFIER", Set.of("paye2u"), 5)).hasSize(1);
            assertThat(Files.readString(temp.resolve("index/policy.version"))).isNotEqualTo("old-policy");
        }
    }

    @Test void longSqlCodeKeepsContextAndBoundedFragments() {
        String text = "# Procedure\n정산 프로시저 설명\n```sql\n" + "SELECT mchtId FROM MCHT;\n".repeat(2500) + "```\n";
        var chunks = DocumentExtractor.markdown(text);
        assertThat(chunks).hasSizeGreaterThan(2).allMatch(chunk -> chunk.text().length() <= DocumentExtractor.MAX_CHUNK);
        assertThat(chunks).filteredOn(chunk -> chunk.text().contains("SELECT")).allMatch(chunk -> chunk.section().equals("Procedure"));
        assertThat(chunks).anyMatch(chunk -> chunk.text().contains("정산 프로시저 설명"));
    }

    @Test void extractorRejectsOversizeInsteadOfSilentlyTruncating() throws Exception {
        Path doc = source("paye2u/huge.md", "A".repeat(DocumentExtractor.MAX_CHARS + 1));
        assertThatThrownBy(() -> new DocumentExtractor().extract(doc)).isInstanceOf(IllegalArgumentException.class);
    }
}
