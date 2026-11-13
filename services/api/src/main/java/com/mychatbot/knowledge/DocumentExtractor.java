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
        if (type.equals("xlsx") || type.equals("docx")) checkZip(path);
        List<Chunk> chunks;
        String text;
        if (type.equals("xlsx") || type.equals("xls")) {
            chunks = excel(path);
            text = String.join("\n", chunks.stream().map(Chunk::text).toList());
        } else {
            text = type.equals("docx") || type.equals("pdf") ? tika(path, type) : readText(path);
            if (text.length() > MAX_CHARS) throw new IllegalArgumentException("text-size-limit");
            chunks = markdown(text);
        }
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
            var parser = type.equals("pdf") ? new PDFParser() : new OOXMLParser();
            parser.parse(input, handler, new Metadata(), context);
        }
        return handler.toString();
    }

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
                StringBuilder context = new StringBuilder("Sheet: ").append(sheet.getSheetName()).append('\n');
                for (var merged : sheet.getMergedRegions()) {
                    context.append("Merged: ").append(merged.formatAsString()).append(" = ");
                    Row row = sheet.getRow(merged.getFirstRow());
                    Cell cell = row == null ? null : row.getCell(merged.getFirstColumn());
                    if (cell != null) context.append(formatter.formatCellValue(cell));
                    context.append('\n');
                    if (context.length() > 6000) throw new IllegalArgumentException("merged-context-limit");
                }
                List<String> headers = new ArrayList<>();
                java.util.Set<Integer> sensitiveColumns = new java.util.HashSet<>();
                for (Row row : sheet) {
                    if (row.getLastCellNum() > 1000) throw new IllegalArgumentException("column-limit");
                    StringBuilder line = new StringBuilder("Row ").append(row.getRowNum() + 1).append(": ");
                    boolean hasValue = false;
                    for (Cell cell : row) {
                        if (++cells > 100000) throw new IllegalArgumentException("cell-limit");
                        String value = formatter.formatCellValue(cell);
                        if (sensitiveColumns.contains(cell.getColumnIndex()) && !value.isBlank() && !SensitiveData.sensitiveHeader(value))
                            throw new Quarantined();
                        if (SensitiveData.sensitiveHeader(value)) sensitiveColumns.add(cell.getColumnIndex());
                        if (value.isBlank() && cell.getCellType() != CellType.FORMULA) continue;
                        hasValue = true;
                        line.append(CellReference.convertNumToColString(cell.getColumnIndex())).append('=');
                        if (cell.getCellType() == CellType.FORMULA)
                            line.append("formula[").append(cell.getCellFormula()).append("] cached[").append(value).append(']');
                        else line.append(value);
                        line.append(" | ");
                    }
                    if (!hasValue) continue;
                    String rowText = line.toString();
                    // shortcut: retain the first eight populated rows as table context; richer per-table detection can replace this for heterogeneous sheets.
                    if (headers.size() < 8 && String.join("\n", headers).length() + rowText.length() < 6000) headers.add(rowText);
                    String content = context + "Header/table context:\n" + String.join("\n", headers) + "\n" + rowText;
                    if (content.length() > MAX_CHUNK) throw new IllegalArgumentException("row-text-limit");
                    chars += content.length();
                    if (chars > MAX_CHARS) throw new IllegalArgumentException("text-size-limit");
                    result.add(new Chunk(sheet.getSheetName(), row.getRowNum() + 1, content));
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
