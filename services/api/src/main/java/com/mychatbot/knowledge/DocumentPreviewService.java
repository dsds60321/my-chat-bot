package com.mychatbot.knowledge;

import com.mychatbot.security.AccessContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentPreviewService {
    private static final Set<String> OFFICE = Set.of("doc", "docx", "ppt", "pptx");
    private static final Set<String> TEXT = Set.of("md", "txt", "text", "sql", "java", "xml", "json");
    private static final int MAX_PDF = 10 * 1024 * 1024;
    private static final int MAX_TEXT = 200_000;
    private final KnowledgeService knowledge;
    private final String command;
    private final int timeoutSeconds;
    private final Semaphore converters = new Semaphore(2);
    private final Map<String, byte[]> pdfCache = new LinkedHashMap<>();

    public record Sheet(String name, List<List<String>> rows) {}
    public record Preview(String kind, String text, List<Sheet> sheets, String message) {
        static Preview unavailable(String message) { return new Preview("unavailable", null, null, message); }
    }

    public DocumentPreviewService(KnowledgeService knowledge,
            @Value("${knowledge.preview-command:soffice}") String command,
            @Value("${knowledge.preview-timeout-seconds:20}") int timeoutSeconds) {
        this.knowledge = knowledge;
        this.command = command;
        this.timeoutSeconds = Math.max(1, Math.min(60, timeoutSeconds));
    }

    public Optional<Preview> preview(String id, String version, AccessContext access) {
        var source = knowledge.download(id, version, access);
        if (source.isEmpty()) return Optional.empty();
        var file = source.get();
        String type = DocumentExtractor.extension(file.filename());
        try {
            if (TEXT.contains(type)) {
                String text = new String(file.bytes(), StandardCharsets.UTF_8);
                return Optional.of(new Preview("text", text.substring(0, Math.min(MAX_TEXT, text.length())), null,
                        text.length() > MAX_TEXT ? "긴 문서의 앞부분만 표시합니다. 전체 내용은 원본을 다운로드해 주세요." : null));
            }
            if (type.equals("xls") || type.equals("xlsx")) return Optional.of(spreadsheet(file.bytes()));
            if (type.equals("pdf")) return Optional.of(new Preview("pdf", null, null, null));
            if (type.equals("doc") || type.equals("ppt")) return Optional.of(Preview.unavailable("이전 Office 형식은 원본을 다운로드하거나 DOCX·PPTX로 저장한 뒤 확인해 주세요."));
            if (OFFICE.contains(type)) {
                convertedPdf(version, type, file.bytes());
                if (knowledge.download(id, version, access).isEmpty()) return Optional.empty();
                return Optional.of(new Preview("pdf", null, null, "PDF 변환본입니다. 글꼴·서식과 애니메이션은 원본과 다를 수 있습니다."));
            }
            return Optional.of(Preview.unavailable("이 형식은 미리보기를 지원하지 않습니다. 원본을 다운로드해 주세요."));
        } catch (Exception ex) {
            if (knowledge.download(id, version, access).isEmpty()) return Optional.empty();
            return Optional.of(Preview.unavailable("미리보기를 만들 수 없습니다. Office 문서는 서버의 LibreOffice 설치·변환 설정을 확인하거나 원본을 다운로드해 주세요."));
        }
    }

    public Optional<byte[]> pdf(String id, String version, AccessContext access) {
        var source = knowledge.download(id, version, access);
        if (source.isEmpty()) return Optional.empty();
        var file = source.get();
        String type = DocumentExtractor.extension(file.filename());
        try {
            byte[] result;
            if (type.equals("pdf")) result = file.bytes();
            else if (type.equals("docx") || type.equals("pptx")) result = convertedPdf(version, type, file.bytes());
            else return Optional.empty();
            return knowledge.download(id, version, access).isPresent() ? Optional.of(result) : Optional.empty();
        } catch (Exception ex) { return Optional.empty(); }
    }

    private Preview spreadsheet(byte[] bytes) throws Exception {
        List<Sheet> sheets = new ArrayList<>();
        int remaining = MAX_TEXT;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(java.util.Locale.KOREA);
            formatter.setUseCachedValuesForFormulaCells(true);
            for (int s = 0; s < Math.min(5, workbook.getNumberOfSheets()) && remaining > 0; s++) {
                var sheet = workbook.getSheetAt(s);
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r <= Math.min(99, sheet.getLastRowNum()) && remaining > 0; r++) {
                    var row = sheet.getRow(r);
                    List<String> cells = new ArrayList<>();
                    if (row != null) for (int c = 0; c < Math.min(30, row.getLastCellNum()) && remaining > 0; c++) {
                        String value = formatter.formatCellValue(row.getCell(c));
                        value = value.substring(0, Math.min(Math.min(1000, remaining), value.length()));
                        remaining -= value.length();
                        cells.add(value);
                    }
                    rows.add(List.copyOf(cells));
                }
                sheets.add(new Sheet(sheet.getSheetName(), List.copyOf(rows)));
            }
        }
        return new Preview("sheets", null, List.copyOf(sheets),
                "최대 5개 시트·시트별 100행·30열을 표시합니다. 수식은 저장된 값이며 차트·병합·서식은 원본에서 확인해 주세요.");
    }

    private byte[] convertedPdf(String version, String type, byte[] source) throws Exception {
        String key = version + "." + type;
        synchronized (pdfCache) {
            byte[] cached = pdfCache.get(key);
            if (cached != null) return cached;
        }
        if (!converters.tryAcquire()) throw new IOException("preview-busy");
        Path directory = null;
        Process process = null;
        try {
            if (type.endsWith("x")) checkOfficeLinks(source);
            directory = Files.createTempDirectory("my-chat-bot-preview-");
            Path profile = Files.createDirectories(directory.resolve("profile/user"));
            Files.writeString(profile.resolve("registrymodifications.xcu"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <oor:items xmlns:oor="http://openoffice.org/2001/registry">
                  <item oor:path="/org.openoffice.Office.Common/Security/Scripting">
                    <prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop>
                    <prop oor:name="SecureURL" oor:op="fuse"><value/></prop>
                    <prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop>
                  </item>
                  <item oor:path="/org.openoffice.Office.Writer/Content/Update">
                    <prop oor:name="Link" oor:op="fuse"><value>0</value></prop>
                  </item>
                </oor:items>
                """);
            Path input = directory.resolve("source." + type);
            Files.write(input, source);
            // Use a fresh untrusted profile so document macros never inherit server-user trust settings.
            process = new ProcessBuilder(command, "-env:UserInstallation=" + directory.resolve("profile").toUri(),
                    "--headless", "--norestore", "--nodefault", "--nolockcheck", "--convert-to", "pdf", "--outdir",
                    directory.toString(), input.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("preview-conversion-failed");
            Path output = directory.resolve("source.pdf");
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) < 5 || Files.size(output) > MAX_PDF)
                throw new IOException("preview-output-limit");
            byte[] pdf;
            try (var stream = Files.newInputStream(output, LinkOption.NOFOLLOW_LINKS)) { pdf = stream.readNBytes(MAX_PDF + 1); }
            if (pdf.length > MAX_PDF || !new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
                throw new IOException("invalid-pdf");
            synchronized (pdfCache) {
                while (pdfCache.size() >= 4) pdfCache.remove(pdfCache.keySet().iterator().next());
                pdfCache.put(key, pdf);
            }
            return pdf;
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            if (directory != null) try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } finally { converters.release(); }
            else converters.release();
        }
    }

    private static void checkOfficeLinks(byte[] source) throws IOException {
        int entries = 0;
        long expanded = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(source))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
                if (++entries > 2000 || name.contains("vbaproject") || name.contains("/embeddings/") || name.contains("/activex/")
                        || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".mht") || name.endsWith(".mhtml") || name.endsWith(".vml"))
                    throw new IOException("unsafe-office-content");
                byte[] data = zip.readNBytes(20_000_001);
                expanded += data.length;
                if (data.length > 20_000_000 || expanded > 40_000_000) throw new IOException("office-archive-limit");
                if (name.endsWith(".rels")) checkRelationships(name, data);
                if (name.endsWith(".xml")) checkActiveContent(data);
            }
        }
    }
    private static org.w3c.dom.Document parseOfficeXml(byte[] data) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
    }

    private static void checkRelationships(String part, byte[] data) throws IOException {
        try {
            var document = parseOfficeXml(data);
            var relations = document.getElementsByTagNameNS("*", "Relationship");
            Path base = Path.of(part).getParent().getParent();
            if (base == null) base = Path.of("");
            for (int i = 0; i < relations.getLength(); i++) {
                var relation = (org.w3c.dom.Element) relations.item(i);
                String target = relation.getAttribute("Target");
                var uri = java.net.URI.create(target);
                String path = uri.getPath();
                if (relation.getAttribute("TargetMode").equalsIgnoreCase("External") || uri.getScheme() != null
                        || uri.getAuthority() != null || path == null || path.startsWith("/") || path.contains("\\")
                        || base.resolve(path).normalize().startsWith(".."))
                    throw new IOException("office-external-content");
            }
        } catch (IOException ex) { throw ex; }
        catch (Exception ex) { throw new IOException("invalid-office-relationships"); }
    }

    private static void checkActiveContent(byte[] data) throws IOException {
        try {
            var document = parseOfficeXml(data);
            // Field instructions and alternate embedded content can read files without an external relationship.
            for (String element : List.of("instrText", "fldSimple", "fldChar", "altChunk", "imagedata")) {
                if (document.getElementsByTagNameNS("*", element).getLength() > 0)
                    throw new IOException("office-active-content");
            }
        } catch (IOException ex) { throw ex; }
        catch (Exception ex) { throw new IOException("invalid-office-content"); }
    }
}
