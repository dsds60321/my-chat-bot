package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExcelTaskExtractionTest {
    @TempDir Path temp;
    void row(Sheet sheet, int number, String... values) {
        var row = sheet.createRow(number - 1);
        for (int column = 0; column < values.length; column++) if (!values[column].isEmpty()) row.createCell(column).setCellValue(values[column]);
    }
    DocumentExtractor.Extracted extract(XSSFWorkbook workbook) throws Exception {
        Path file = temp.resolve("tasks.xlsx");
        try (var output = Files.newOutputStream(file)) { workbook.write(output); }
        return new DocumentExtractor().extract(file);
    }

    @Test void taskRowsKeepOnlyTheirOwnInstructionsAndStructuralHeaders() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("업무사항");
            row(sheet, 1, "WBS"); sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
            row(sheet, 2, "구분", "작업명", "처리내용", "확인사항");
            row(sheet, 3, "정산", "대행사 수수료 계산식", "금액 × 실 대행사 수수료율", "부가세 반올림");
            row(sheet, 4, "운영", "카드 BIN 등록", "BIN_ONLY 절차", "");
            row(sheet, 5, "운영", "메일 관리", "MAIL_ONLY 절차", "");
            row(sheet, 6, "결제", "매입사 설정방법", "ACQUIRER_ONLY 선택 후 저장", "");
            row(sheet, 7, "결제", "재매입 방법", "REBUY_ONLY 거래 확인", "");
            row(sheet, 8, "", "", "REBUY_CONTINUED 원거래 보존 후 실행", "");
            row(sheet, 9, "결제", "차액 재매입 방법", "DIFFERENCE_ONLY 차액 계산", "");
            row(sheet, 10, "관계없는 병합 제목"); sheet.addMergedRegion(new CellRangeAddress(9, 9, 0, 3));
            var chunks = extract(workbook).chunks();
            for (String task : new String[] {"대행사 수수료 계산식", "매입사 설정방법", "재매입 방법", "차액 재매입 방법"}) {
                var chunk = chunks.stream().filter(c -> c.section().equals("업무사항 · " + task)).findFirst().orElseThrow();
                assertThat(chunk.text()).contains("작업명", "처리내용").doesNotContain("BIN_ONLY", "MAIL_ONLY", "관계없는 병합 제목");
                if (task.equals("재매입 방법")) assertThat(chunk.text()).contains("REBUY_CONTINUED").doesNotContain("DIFFERENCE_ONLY");
            }
            var rebuy = chunks.stream().filter(c -> c.section().equals("업무사항 · 재매입 방법")).findFirst().orElseThrow();
            assertThat(rebuy.row()).isEqualTo(7);
            assertThat(rebuy.text()).contains("Row 7:", "Row 8:");
        }
    }

    @Test void genericSchemaRetainsTitleHeadersFormulaAndCachedValue() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Schema");
            row(sheet, 1, "MCHT 가맹점 테이블"); sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            row(sheet, 2, "테이블명", "컬럼명", "타입");
            row(sheet, 3, "MCHT", "mchtId", "VARCHAR");
            var formula = ((org.apache.poi.xssf.usermodel.XSSFRow) sheet.getRow(2)).createCell(3);
            formula.setCellFormula("21*2"); formula.getCTCell().setV("42");
            var chunk = extract(workbook).chunks().stream().filter(c -> c.row() == 3).findFirst().orElseThrow();
            assertThat(chunk.section()).isEqualTo("Schema");
            assertThat(chunk.text()).contains("Merged: A1:C1", "테이블명", "컬럼명", "formula[21*2] cached[42]");
        }
    }

    @Test void unknownTablesDoNotPretendTheirFirstDataRowsAreHeaders() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Generic");
            row(sheet, 1, "첫 업무", "EARLY_ONLY"); row(sheet, 2, "두번째 업무", "LATER_ONLY");
            var chunk = extract(workbook).chunks().stream().filter(c -> c.row() == 2).findFirst().orElseThrow();
            assertThat(chunk.text()).contains("LATER_ONLY").doesNotContain("EARLY_ONLY");
        }
    }

    @Test void verticallyMergedTaskKeepsItsContinuationAndNotTheNextTask() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Tasks"); row(sheet, 1, "task", "description");
            row(sheet, 2, "Rebuy", "FIRST_STEP"); row(sheet, 3, "", "SECOND_STEP");
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));
            row(sheet, 4, "Difference rebuy", "OTHER_STEP");
            var chunks = extract(workbook).chunks();
            var task = chunks.stream().filter(c -> c.section().equals("Tasks · Rebuy")).findFirst().orElseThrow();
            assertThat(task.row()).isEqualTo(2);
            assertThat(task.text()).contains("Merged: A2:A3", "FIRST_STEP", "SECOND_STEP").doesNotContain("OTHER_STEP");
        }
    }

    @Test void laterPersonalDataDoesNotRejectTheWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Tasks"); row(sheet, 1, "작업명", "처리내용");
            row(sheet, 2, "안전 업무", "SAFE_STEP"); row(sheet, 30, "다른 업무", "연락처 01012345678");
            assertThat(extract(workbook).text()).contains("01012345678");
        }
    }

    @Test void credentialColumnsRemainAvailableAfterStructuralHeaderChanges() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Tasks"); row(sheet, 1, "작업명", "처리내용", "password");
            row(sheet, 2, "안전 업무", "SAFE_STEP", "ordinary-looking-value");
            assertThat(extract(workbook).text()).contains("password", "ordinary-looking-value");
        }
    }

    @Test void taskMetadataIsBoundedButFullRowRemainsAvailable() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Tasks"); row(sheet, 1, "작업 명", "처리 내용");
            String title = "긴제목".repeat(200);
            row(sheet, 2, title, "EXACT_STEP");
            var chunk = extract(workbook).chunks().stream().filter(c -> c.row() == 2).findFirst().orElseThrow();
            assertThat(chunk.section()).hasSize("Tasks · ".length() + 240);
            assertThat(chunk.text()).contains(title, "EXACT_STEP");
        }
    }

    @Test void longContinuationSplitsWithoutDroppingSteps() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Tasks"); row(sheet, 1, "task", "description");
            row(sheet, 2, "Long operation", "FIRST_STEP " + "x".repeat(16000));
            row(sheet, 3, "", "SECOND_STEP " + "y".repeat(16000));
            var chunks = extract(workbook).chunks().stream().filter(c -> c.section().equals("Tasks · Long operation")).toList();
            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(0).text()).contains("FIRST_STEP");
            assertThat(chunks.get(1).text()).contains("SECOND_STEP");
            assertThat(chunks.get(1).row()).isEqualTo(3);
        }
    }
}
