package com.mychatbot.knowledge;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;

final class DocumentExtractor {
    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final int MAX_CHARS = 2_000_000;
    static final int MAX_CHUNK = 30_000;
    record Chunk(String section, Integer row, String text) {}
    record Extracted(String title, String type, String text, List<Chunk> chunks) {}

    Extracted extract(Path path) throws Exception {
        String name = path.getFileName().toString();
        return extract(path, name);
    }

    Extracted extract(Path path, String name) throws Exception {
        String type = extension(name);
        if (Files.size(path) > MAX_BYTES) throw new IllegalArgumentException("file-size-limit");
        if (type.equals("xlsx") || type.equals("docx") || type.equals("pptx")) checkZip(path);
        List<Chunk> chunks;
        String text;
        if (type.equals("xlsx") || type.equals("xls")) {
            chunks = excel(path);
            text = String.join("\n", chunks.stream().map(Chunk::text).toList());
        } else {
            text = Set.of("docx", "doc", "pptx", "ppt", "pdf").contains(type) ? tika(path, type) : readText(path);
            if (text.length() > MAX_CHARS) throw new IllegalArgumentException("text-size-limit");
            chunks = markdown(text);
        }
        return extracted(name, type, text, chunks);
    }

    Extracted extractManagedMarkdown(Path path, java.util.function.UnaryOperator<String> indexText) throws Exception {
        if (Files.size(path) > MAX_BYTES) throw new IllegalArgumentException("file-size-limit");
        String text = readText(path);
        if (text.length() > MAX_CHARS) throw new IllegalArgumentException("text-size-limit");
        text = indexText.apply(text);
        return extracted(path.getFileName().toString(), "md", text, markdown(text));
    }

    private Extracted extracted(String name, String type, String text, List<Chunk> chunks) throws Quarantined {
        if (text.length() > MAX_CHARS) throw new IllegalArgumentException("text-size-limit");
        if (!SensitiveData.guard(text)) throw new Quarantined();
        String title = text.lines().filter(line -> line.startsWith("# ")).findFirst()
            .map(line -> line.substring(2).strip()).orElse(name);
        if (title.length() > 240) title = title.substring(0, 240);
        return new Extracted(title, type, text, chunks);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "text" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void checkZip(Path path) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            long expanded = 0;
            int count = 0;
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                if (++count > 2000 || entry.getSize() < 0 || entry.getSize() > 20_000_000)
                    throw new IllegalArgumentException("archive-entry-limit");
                expanded += entry.getSize();
                if (expanded > 50_000_000 || (entry.getSize() > 1_000_000 &&
                    entry.getSize() / Math.max(1, entry.getCompressedSize()) > 100))
                    throw new IllegalArgumentException("archive-expansion-limit");
            }
        }
    }

    private static String readText(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("file-size-limit");
        for (byte value : bytes) if (value == 0) throw new IllegalArgumentException("binary-text");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            return Charset.forName("MS949").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private static String tika(Path path, String type) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            public boolean shouldParseEmbedded(Metadata metadata) { return false; }
            public void parseEmbedded(InputStream input, org.xml.sax.ContentHandler handler,
                                      Metadata metadata, boolean outputHtml) { }
        });
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setExtractInlineImages(false);
        context.set(PDFParserConfig.class, pdfConfig);
        try (InputStream input = Files.newInputStream(path)) {
            org.apache.tika.parser.Parser parser = type.equals("pdf") ? new PDFParser()
                    : Set.of("doc", "ppt").contains(type) ? new OfficeParser() : new OOXMLParser();
            parser.parse(input, handler, new Metadata(), context);
        }
        return handler.toString();
    }

    private static final Set<String> TABLE_HEADERS = Set.of(
            "구분", "작업명", "업무명", "제목", "항목", "처리내용", "내용", "확인사항", "작성자", "요청지", "비고",
            "설명", "방법", "절차", "기준", "테이블명", "컬럼명", "타입", "필드명", "필수", "기본값", "예시",
            "name", "title", "task", "description", "type", "column", "field", "value", "notes", "procedure");
    // shortcut: unknown layouts remain independent rows until an explicit task/title header is recognized.
    private static final Set<String> TASK_HEADERS = Set.of("작업명", "업무명", "제목", "항목", "task", "title", "procedure");

    private static List<Chunk> excel(Path path) throws Exception {
        List<Chunk> result = new ArrayList<>();
        int cells = 0;
        int chars = 0;
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        formatter.setUseCachedValuesForFormulaCells(true);
        try (InputStream input = Files.newInputStream(path); var workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() > 100) throw new IllegalArgumentException("sheet-limit");
            for (var sheet : workbook) {
                if (sheet.getLastRowNum() > 20000) throw new IllegalArgumentException("row-limit");
                var mergedRegions = sheet.getMergedRegions();
                mergedRegions.sort(java.util.Comparator.comparingInt(org.apache.poi.ss.util.CellRangeAddress::getFirstRow));
                var activeMerges = new ArrayList<org.apache.poi.ss.util.CellRangeAddress>();
                int nextMerge = 0;
                String headers = "";
                String previousHeading = "";
                String task = "";
                int taskColumn = -1;
                int group = -1;
                int previousRow = -2;
                java.util.Set<Integer> sensitiveColumns = new java.util.HashSet<>();
                for (Row row : sheet) {
                    if (row.getLastCellNum() > 1000) throw new IllegalArgumentException("column-limit");
                    StringBuilder line = new StringBuilder("Row ").append(row.getRowNum() + 1).append(": ");
                    var values = new java.util.LinkedHashMap<Integer, String>();
                    boolean shortLabels = true;
                    for (Cell cell : row) {
                        if (++cells > 100000) throw new IllegalArgumentException("cell-limit");
                        String value = formatter.formatCellValue(cell);
                        if (sensitiveColumns.contains(cell.getColumnIndex()) && !value.isBlank() && !SensitiveData.sensitiveHeader(value))
                            throw new Quarantined();
                        if (SensitiveData.sensitiveHeader(value)) sensitiveColumns.add(cell.getColumnIndex());
                        if (value.isBlank() && cell.getCellType() != CellType.FORMULA) continue;
                        values.put(cell.getColumnIndex(), value);
                        shortLabels &= value.length() <= 60 && !value.contains("\n") && cell.getCellType() != CellType.FORMULA;
                        line.append(CellReference.convertNumToColString(cell.getColumnIndex())).append('=');
                        if (cell.getCellType() == CellType.FORMULA)
                            line.append("formula[").append(cell.getCellFormula()).append("] cached[").append(value).append(']');
                        else line.append(value);
                        line.append(" | ");
                    }
                    if (values.isEmpty()) { group = -1; previousHeading = ""; continue; }
                    if (row.getRowNum() != previousRow + 1) { group = -1; previousHeading = ""; }
                    String rowText = line.toString();
                    String mergedTask = "";
                    StringBuilder merged = new StringBuilder();
                    boolean horizontalHeading = false;
                    activeMerges.removeIf(region -> region.getLastRow() < row.getRowNum());
                    while (nextMerge < mergedRegions.size() && mergedRegions.get(nextMerge).getFirstRow() <= row.getRowNum()) {
                        var region = mergedRegions.get(nextMerge++);
                        if (region.getLastRow() >= row.getRowNum()) activeMerges.add(region);
                    }
                    for (var region : activeMerges) {
                        Row anchor = sheet.getRow(region.getFirstRow());
                        Cell cell = anchor == null ? null : anchor.getCell(region.getFirstColumn());
                        String value = cell == null ? "" : formatter.formatCellValue(cell);
                        if (taskColumn >= region.getFirstColumn() && taskColumn <= region.getLastColumn()) mergedTask = value.strip();
                        merged.append("Merged: ").append(region.formatAsString()).append(" = ").append(value).append('\n');
                        horizontalHeading |= region.getFirstRow() == region.getLastRow() && region.getFirstColumn() < region.getLastColumn();
                        if (merged.length() > 6000) throw new IllegalArgumentException("merged-context-limit");
                    }
                    boolean header = shortLabels && values.values().stream()
                            .filter(value -> TABLE_HEADERS.contains(value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT))).count() >= 2;
                    if (header) {
                        headers = previousHeading + rowText + "\n";
                        taskColumn = values.entrySet().stream()
                                .filter(entry -> TASK_HEADERS.contains(entry.getValue().replaceAll("\\s+", "").toLowerCase(Locale.ROOT)))
                                .mapToInt(java.util.Map.Entry::getKey).findFirst().orElse(-1);
                        task = ""; group = -1;
                    }
                    String explicitTask = values.getOrDefault(taskColumn, "").strip();
                    String rowTask = explicitTask.isEmpty() ? mergedTask : explicitTask;
                    boolean continuation = !header && taskColumn >= 0 && explicitTask.isEmpty() && group >= 0
                            && (rowTask.isEmpty() || rowTask.equals(task));
                    if (!continuation) {
                        task = header || taskColumn < 0 ? "" : rowTask;
                        String section = sheet.getSheetName() + (task.isEmpty() ? "" : " · " + task.substring(0, Math.min(task.length(), 240)));
                        String content = "Sheet: " + sheet.getSheetName() + "\n" + merged
                                + (header || headers.isEmpty() ? "" : "Header/table context:\n" + headers) + rowText;
                        if (content.length() > MAX_CHUNK) throw new IllegalArgumentException("row-text-limit");
                        result.add(new Chunk(section, row.getRowNum() + 1, content));
                        group = task.isEmpty() ? -1 : result.size() - 1;
                        chars += content.length();
                    } else {
                        Chunk prior = result.get(group);
                        String addition = (prior.text().contains(merged.toString()) ? "" : merged.toString()) + rowText;
                        String content = prior.text() + "\n" + addition;
                        if (content.length() <= MAX_CHUNK) {
                            result.set(group, new Chunk(prior.section(), prior.row(), content));
                            chars += addition.length() + 1;
                        } else {
                            content = "Sheet: " + sheet.getSheetName() + "\nTask: " + task + "\n" + merged
                                    + "Header/table context:\n" + headers + rowText;
                            if (content.length() > MAX_CHUNK) throw new IllegalArgumentException("row-text-limit");
                            result.add(new Chunk(prior.section(), row.getRowNum() + 1, content));
                            group = result.size() - 1;
                            chars += content.length();
                        }
                    }
                    if (chars > MAX_CHARS) throw new IllegalArgumentException("text-size-limit");
                    previousHeading = horizontalHeading && values.size() == 1 && rowText.length() <= 300 ? merged.toString() : "";
                    previousRow = row.getRowNum();
                }
            }
        }
        return result;
    }

    static List<Chunk> markdown(String text) {
        List<Chunk> result = new ArrayList<>();
        String[] headings = new String[6];
        StringBuilder block = new StringBuilder();
        String section = "Document";
        String fence = null;
        int start = 1;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.stripLeading();
            if (fence == null && trimmed.matches("#{1,6}\\s+.*")) {
                if (!block.isEmpty()) result.add(new Chunk(section, start, block.toString()));
                block.setLength(0);
                int level = trimmed.indexOf(' ');
                String heading = trimmed.substring(level).strip();
                headings[level - 1] = heading.substring(0, Math.min(240, heading.length()));
                for (int j = level; j < headings.length; j++) headings[j] = null;
                section = String.join(" > ", java.util.Arrays.stream(headings).filter(java.util.Objects::nonNull).toList());
                start = i + 1;
            }
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                String marker = trimmed.substring(0, 3);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
            }
            block.append(line).append('\n');
            if (block.length() > MAX_CHUNK) {
                String content = block.toString();
                String context = content.substring(0, Math.min(1000, content.length()));
                for (int offset = 0; offset < content.length(); offset += 12000) {
                    String fragment = content.substring(offset, Math.min(offset + 12000, content.length()));
                    String prefix = offset == 0 ? "" : "Section: " + section + "\nContext:\n" + context + "\n[continuation]\n";
                    result.add(new Chunk(section, start, prefix + fragment + (fence == null ? "" : "\n" + fence)));
                }
                block.setLength(0);
                if (fence != null) block.append("Context:\n").append(context).append("\n[continuation]\n").append(fence).append('\n');
                start = i + 1;
            }
            if (fence == null && line.isBlank() && block.length() > 2400 &&
                (i + 1 == lines.length || !(lines[i + 1].stripLeading().startsWith("```") || lines[i + 1].stripLeading().startsWith("~~~")))) {
                result.add(new Chunk(section, start, block.toString()));
                block.setLength(0);
                start = i + 2;
            }
        }
        if (!block.isEmpty()) result.add(new Chunk(section, start, block.toString()));
        return result;
    }

    static final class Quarantined extends Exception { }
}
