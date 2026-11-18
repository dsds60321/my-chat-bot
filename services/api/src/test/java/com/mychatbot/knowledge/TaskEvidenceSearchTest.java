package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.mychatbot.security.AccessContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskEvidenceSearchTest {
    @TempDir Path temp;
    @org.junit.jupiter.api.BeforeEach void canonicalPaths() throws Exception { temp = temp.toRealPath(); }

    private Path workbook() throws Exception {
        Path path = temp.resolve("source/common/tasks.xlsx");
        Files.createDirectories(path.getParent());
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("업무사항");
            String[][] rows = {
                {"구분", "작업명", "처리내용", "확인사항"},
                {"정산", "재매입", "REBUY_MAIN 원거래 조회 후 거래 재생성. 차액 정산 재매입은 별도 업무를 참조한다.", "일반 재매입 처리 상태 확인"},
                {"정산", "차액 정산 재매입", "DIFFERENCE_REBUY 차액 정산 대상의 차이를 대조하고 재매입 처리한다.", "대상 기간과 차액 대조"},
                {"매입", "매입 추가 (신규 매입사, 자동 매입)", "ACQUIRER_SETUP 매입사 설정은 서비스 코드 등록 후 자동 매입 연결 설정.", "매입사별 연결 상태 확인"},
                {"가맹점", "가맹점 설정", "MERCHANT_SETUP 가맹점 등록과 서비스 설정. 매입사 설정은 별도 작업을 따른다.", "가맹점 상태 확인"},
                {"수수료", "대행사 수수료 기준 계산식", "DIST_FEE 금액에 실 대행사 수수료율을 곱하는 계산식. 기준은 버림과 부가세 반올림.", "에이전시 수수료 기준과 대행사 수수료 기준을 혼동하지 않는다."},
                {"수수료", "에이전시 수수료 변경", "AGENCY_FEE 에이전시 수수료율 변경. 대행사 수수료 기준은 별도 계산식을 참조한다.", "변경 적용일과 기준 확인"},
                {"시스템", "카드 BIN 최신화", "BIN_UPDATE 카드 BIN 최신화는 신규 테이블 검증 후 교체.", "기존 테이블 유지"},
                {"그룹웨어", "메일함 용량 변경", "MAIL_QUOTA 메일함 관리 페이지에서 용량을 변경한다.", "메일 계정 확인"}
            };
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]);
            }
            try (var out = Files.newOutputStream(path)) { book.write(out); }
        }
        return path;
    }

    @Test void taskTitlesSeparateSimilarSubjectsWithoutHardcodedQueryNames() throws Exception {
        workbook();
        try (var knowledge = new KnowledgeService(temp.resolve("source"), temp.resolve("index"))) {
            var access = new AccessContext("u", Set.of("common"));
            for (String query : List.of("차액 재매입 방법", "차액 정산 재매입 방법 알려줘", "차액 재매입에 대해 알려줘", "차액 재매입하는법")) {
                assertThat(knowledge.search(query, access, 20)).isNotEmpty().allSatisfy(hit ->
                    assertThat(hit.text()).contains("DIFFERENCE_REBUY").doesNotContain("REBUY_MAIN", "MAIL_QUOTA", "BIN_UPDATE"));
            }
            assertThat(knowledge.search("재매입 방법", access, 20)).extracting(SearchHit::text)
                    .anyMatch(text -> text.contains("REBUY_MAIN")).anyMatch(text -> text.contains("DIFFERENCE_REBUY"));
            for (String query : List.of("매입사 설정방법", "매입사 설정 방법 알려줘", "매입사설정방법")) {
                assertThat(knowledge.search(query, access, 20)).isNotEmpty().first().satisfies(hit ->
                    assertThat(hit.text()).contains("ACQUIRER_SETUP"));
            }
            assertThat(knowledge.search("대행사 수수료 기준", access, 20)).isNotEmpty().allSatisfy(hit ->
                    assertThat(hit.text()).contains("DIST_FEE").doesNotContain("AGENCY_FEE", "MAIL_QUOTA"));
            assertThat(knowledge.search("에이전시 수수료 변경 방법", access, 20)).isNotEmpty().allSatisfy(hit ->
                    assertThat(hit.text()).contains("AGENCY_FEE").doesNotContain("DIST_FEE"));
            assertThat(knowledge.search("카드 BIN 최신화 알려줘", access, 20)).isNotEmpty().allSatisfy(hit ->
                    assertThat(hit.text()).contains("BIN_UPDATE").doesNotContain("DIST_FEE", "MAIL_QUOTA"));
            assertThat(knowledge.search("UNRECORDED_UNIQUE_WORKFLOW", access, 20)).isEmpty();
        }
    }

    @Test void partialTaskTitlesDoNotDiscardBodyOnlyEvidenceAndActionsAreOptional() throws Exception {
        Path path = temp.resolve("source/common/setup.xlsx"); Files.createDirectories(path.getParent());
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("운영");
            String[][] rows = {
                {"작업명", "처리내용"},
                {"서버 계정 설정", "서버 계정 만료의 세부 처리와 정책은 권한 지침을 참조. INCIDENTAL_EXPIRY"},
                {"접근 권한", "서버 계정 만료 처리: 승인 확인 후 만료일 갱신, 접근 검증. BODY_ONLY_EXPIRY"},
                {"신규 매입사 연결", "매입사 서비스 코드를 입력한다. 연결 상태를 검증한다. NEW_ACQUIRER"}
            };
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]);
            }
            try (var out = Files.newOutputStream(path)) { book.write(out); }
        }
        try (var knowledge = new KnowledgeService(temp.resolve("source"), temp.resolve("index"))) {
            assertThat(knowledge.search("서버 계정 만료 설정", Set.of("common"), 20)).extracting(SearchHit::text)
                    .anyMatch(text -> text.contains("BODY_ONLY_EXPIRY"));
            assertThat(knowledge.search("신규 매입사 등록 절차", Set.of("common"), 20)).isNotEmpty()
                    .allSatisfy(hit -> assertThat(hit.text()).contains("NEW_ACQUIRER"));
        }
    }

    @Test void selectedDocumentIsFilteredBeforeOtherDocumentsConsumeResultLimit() throws Exception {
        Path root = temp.resolve("source/common"); Files.createDirectories(root);
        for (int i = 0; i < 30; i++) Files.writeString(root.resolve("other-" + i + ".md"),
                "# 재매입 절차 재매입 절차\n재매입 절차 상세 기준\n".repeat(3));
        Files.writeString(root.resolve("selected.md"), "# 대상\n재매입 절차 TARGET_DOCUMENT\n");
        try (var knowledge = new KnowledgeService(temp.resolve("source"), temp.resolve("index"))) {
            var access = new AccessContext("u", Set.of("common"));
            var selected = knowledge.documents("common", "selected.md", access).get(0);
            assertThat(knowledge.search("재매입 절차", access, 1)).noneMatch(hit -> hit.documentId().equals(selected.documentId()));
            assertThat(knowledge.search("재매입 절차", access, 1, selected.documentId(), selected.version()))
                    .singleElement().satisfies(hit -> assertThat(hit.text()).contains("TARGET_DOCUMENT"));
            assertThat(knowledge.search("재매입 절차", access, 20, selected.documentId(), "0".repeat(64))).isEmpty();
            assertThat(knowledge.search("재매입 절차", new AccessContext("u", Set.of("common"), false, Set.of("common/selected.md")),
                    20, selected.documentId(), selected.version())).isEmpty();
        }
    }
}
