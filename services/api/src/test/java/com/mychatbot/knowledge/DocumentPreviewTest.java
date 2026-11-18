package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.mychatbot.security.AccessContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentPreviewTest {
    @TempDir Path temp;
    private final AccessContext access = new AccessContext("reader", Set.of("common"));
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final String version = "a".repeat(64);
    private void source(String name, byte[] bytes) {
        when(knowledge.download(eq("id"), eq(version), any(AccessContext.class)))
                .thenReturn(Optional.of(new KnowledgeService.SourceFile(name, bytes)));
    }
    private byte[] word() throws Exception {
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Document preview");
            doc.write(bytes);
            return bytes.toByteArray();
        }
    }
    private Path converter(String body) throws Exception {
        Path script = temp.resolve("converter-" + System.nanoTime());
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }
    @Test void spreadsheetIsBoundedAndUsesCachedFormulaWithoutEvaluating() throws Exception {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("매출");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("<script>literal text</script>");
            row.createCell(1).setCellFormula("2+3");
            for (int r = 1; r < 105; r++) sheet.createRow(r).createCell(0).setCellValue(r);
            workbook.write(bytes);
            source("report.xlsx", bytes.toByteArray());
        }
        var preview = new DocumentPreviewService(knowledge, "missing", 1).preview("id", version, access).orElseThrow();
        assertThat(preview.kind()).isEqualTo("sheets");
        assertThat(preview.sheets().get(0).rows()).hasSize(100);
        assertThat(preview.sheets().get(0).rows().get(0)).containsExactly("<script>literal text</script>", "0");
    }
    @Test void textRemainsPlainAndIsBounded() {
        source("guide.md", ("<script>example</script>" + "x".repeat(250_000)).getBytes(StandardCharsets.UTF_8));
        var preview = new DocumentPreviewService(knowledge, "missing", 1).preview("id", version, access).orElseThrow();
        assertThat(preview.text()).startsWith("<script>example</script>").hasSize(200_000);
        assertThat(preview.message()).isNotBlank();
    }
    @Test void missingConverterReturnsSafeUnavailableResponse() throws Exception {
        source("guide.docx", word());
        var preview = new DocumentPreviewService(knowledge, temp.resolve("not-installed").toString(), 1)
                .preview("id", version, access).orElseThrow();
        assertThat(preview.kind()).isEqualTo("unavailable");
        assertThat(preview.message()).doesNotContain(temp.toString()).contains("LibreOffice");
    }
    @Test void conversionUsesIsolatedMacroDisabledProfileAndCachedPdfStillRequiresAccess() throws Exception {
        source("guide.docx", word());
        Path script = converter("while [ \"$#\" -gt 2 ]; do shift; done\n"
                + "test -f \"$1/profile/user/registrymodifications.xcu\" || exit 1\n"
                + "grep -q DisableMacrosExecution \"$1/profile/user/registrymodifications.xcu\" || exit 1\n"
                + "printf '%s' '%PDF-1.4 fixture' > \"$1/source.pdf\"");
        var service = new DocumentPreviewService(knowledge, script.toString(), 1);
        assertThat(service.preview("id", version, access).orElseThrow().kind()).isEqualTo("pdf");
        assertThat(new String(service.pdf("id", version, access).orElseThrow(), StandardCharsets.UTF_8)).startsWith("%PDF-");
        when(knowledge.download("id", version, access)).thenReturn(Optional.empty());
        assertThat(service.pdf("id", version, access)).isEmpty();
        assertThat(service.preview("id", version, access)).isEmpty();
    }
    @Test void stuckConverterIsKilled() throws Exception {
        source("guide.docx", word());
        var service = new DocumentPreviewService(knowledge, converter("sleep 10").toString(), 1);
        Instant start = Instant.now();
        assertThat(service.preview("id", version, access).orElseThrow().kind()).isEqualTo("unavailable");
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }
    @Test void externalRelationshipsAreRejectedAcrossXmlEncodingAndEntities() throws Exception {
        for (String xml : new String[]{
                "<?xml version='1.0' encoding='UTF-16'?><Relationships><Relationship TargetMode='External'/></Relationships>",
                "<Relationships><Relationship TargetMode='Ext&#101;rnal'/></Relationships>",
                "<Relationships><Relationship Target='file:///etc/passwd'/></Relationships>",
                "<Relationships><Relationship Target='../../etc/passwd'/></Relationships>",
                "<Relationships><Relationship Target='%2e%2e/%2e%2e/etc/passwd'/></Relationships>"}) {
            try (var bytes = new ByteArrayOutputStream()) {
                try (var zip = new ZipOutputStream(bytes)) {
                    zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
                    zip.write(xml.getBytes(xml.contains("UTF-16") ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                source("linked.docx", bytes.toByteArray());
            }
            Path marker = temp.resolve("started");
            var service = new DocumentPreviewService(knowledge, converter("touch '" + marker + "'").toString(), 1);
            assertThat(service.preview("id", version, access).orElseThrow().kind()).isEqualTo("unavailable");
            assertThat(marker).doesNotExist();
        }
    }
    @Test void wordFieldsCannotReadLocalFilesWithoutExternalRelationships() throws Exception {
        try (var bytes = new ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("word/document.xml"));
                zip.write("<w:document xmlns:w='urn:word'><w:instrText>INCLUDETEXT file:///etc/passwd</w:instrText></w:document>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            source("field.docx", bytes.toByteArray());
        }
        Path marker = temp.resolve("active-started");
        var service = new DocumentPreviewService(knowledge, converter("touch '" + marker + "'").toString(), 1);
        assertThat(service.preview("id", version, access).orElseThrow().kind()).isEqualTo("unavailable");
        assertThat(marker).doesNotExist();
    }
    @Test void legacyOfficeDoesNotInvokeConverter() {
        source("old.doc", new byte[]{1, 2, 3});
        var service = new DocumentPreviewService(knowledge, "missing", 1);
        assertThat(service.preview("id", version, access).orElseThrow().kind()).isEqualTo("unavailable");
        assertThat(service.pdf("id", version, access)).isEmpty();
    }
    @Test void excludedPathAndStaleVersionCannotReadPreviewOrPdf() throws Exception {
        Path canonical = temp.toRealPath();
        Path root = canonical.resolve("source");
        Files.createDirectories(root.resolve("common"));
        Files.writeString(root.resolve("common/guide.md"), "# Guide\nPUBLIC_PREVIEW");
        try (var actual = new KnowledgeService(root, canonical.resolve("index"))) {
            var entry = actual.status().documents().stream().filter(item -> item.path().endsWith("guide.md")).findFirst().orElseThrow();
            var service = new DocumentPreviewService(actual, "missing", 1);
            var denied = new AccessContext("reader", Set.of("common"), false, Set.of("common/guide.md"));
            assertThat(service.preview(entry.documentId(), entry.version(), denied)).isEmpty();
            assertThat(service.pdf(entry.documentId(), entry.version(), denied)).isEmpty();
            assertThat(service.preview(entry.documentId(), "0".repeat(64), access)).isEmpty();
            assertThat(service.preview(entry.documentId(), entry.version(), access)).isPresent();
            Files.writeString(root.resolve("common/guide.md"), "# Changed\nCHANGED_PREVIEW");
            assertThat(service.preview(entry.documentId(), entry.version(), access)).isEmpty();
        }
    }
}
