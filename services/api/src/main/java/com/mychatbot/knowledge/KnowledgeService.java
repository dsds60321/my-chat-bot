package com.mychatbot.knowledge;

import com.mychatbot.ai.AiException;
import com.mychatbot.knowledge.KnowledgeProposal.Kind;
import com.mychatbot.knowledge.KnowledgeProposal.State;
import com.mychatbot.security.AccessContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class KnowledgeService implements KnowledgeSearch, AutoCloseable {
    private static final String POLICY = "knowledge-policy-v8";
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeService.class);
    private static final Set<String> TYPES = Set.of("md", "txt", "text", "java", "xml", "json", "sql", "xlsx", "xls", "docx", "pdf");
    public static final Set<String> SUPPORTED_PROJECTS = Set.of("paye2u", "syslink", "operations", "notes", "common");
    private static final String SHARED_WORKBOOK = "E2U_WBS.xlsx";
    private static final String SHARED_WORKBOOK_PROJECT = "paye2u+syslink";
    private static final Set<String> SHARED_WORKBOOK_GRANTS = Set.of("paye2u", "syslink");
    private static final Set<String> STOP = Set.of("알려", "알려줘", "알려주세요", "주세요", "문서", "관련", "대한", "대해", "어떤", "무엇", "어떻게", "어디", "있어", "있나요", "있", "하", "해", "줘", "수", "것", "좀", "내용", "설명", "확인", "찾아", "방법", "요", "나", "때", "가요", "테이블", "컬럼");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_./:$-]+");
    private static final Pattern CONTENT_ID = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_PROPOSALS = 1000;
    private static final String PROPOSALS_FILE = "proposals.json";
    private static final String PROPOSAL_PATH_PREFIX = ".approved/";
    private final Path root;
    private final Path indexPath;
    private final ObjectMapper json = new ObjectMapper();
    private final DocumentExtractor extractor = new DocumentExtractor();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, KnowledgeProposal> proposals = new LinkedHashMap<>();
    private final Map<String, Entry> approvedEntries = new LinkedHashMap<>();
    private Analyzer analyzer;
    private FSDirectory directory;
    private IndexWriter writer;
    private boolean healthy;
    private String scanError = "not-scanned";
    private String scannedAt = "";
    private boolean proposalsLoaded;

    public record Entry(String documentId, String path, String title, String project, String category,
                        String type, String version, long bytes, long modified, String state,
                        int chunks, String summary, String error, String indexedAt) {}
    public record Status(boolean healthy, String scannedAt, String error, int files, int indexableDocuments,
                         int chunks, int quarantine, int errors, int skipped, int deleted, int historical,
                         Map<String, Long> projects, Map<String, Long> categories, List<Entry> documents) {}
    public record SourceFile(String filename, byte[] bytes) {}

    @Autowired
    public KnowledgeService(@Value("${knowledge.root:../../data/knowledge}") String root,
                            @Value("${knowledge.index-path:../../.local/knowledge-index}") String indexPath) {
        this(Path.of(root), Path.of(indexPath));
    }

    public KnowledgeService(Path root, Path indexPath) {
        this.root = root.toAbsolutePath().normalize();
        this.indexPath = indexPath.toAbsolutePath().normalize();
        syncNow();
    }

    private void initialize() throws Exception {
        if (writer != null) return;
        if (indexPath.startsWith(root) || root.startsWith(indexPath)) throw new IOException("index-source-overlap");
        rejectSymlinkParents(root);
        rejectSymlinkParents(indexPath);
        Files.createDirectories(root);
        Files.createDirectories(indexPath);
        Analyzer exact = CustomAnalyzer.builder().withTokenizer("pattern", "pattern", "[^\\p{L}\\p{N}_./:$-]+")
            .addTokenFilter("lowercase").build();
        analyzer = new PerFieldAnalyzerWrapper(new KoreanAnalyzer(), Map.of("exact", exact));
        rejectSymlinkParents(indexPath.resolve("lucene"));
        directory = FSDirectory.open(indexPath.resolve("lucene"));
        boolean existingIndex = DirectoryReader.indexExists(directory);
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
        writer.deleteDocuments(new Term("approved-proposal", "true"));
        Path manifest = indexPath.resolve("manifest.json");
        Path policy = indexPath.resolve("policy.version");
        boolean currentPolicy = existingIndex && Files.isRegularFile(policy, LinkOption.NOFOLLOW_LINKS) && Files.readString(policy).equals(POLICY);
        if (currentPolicy && Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            try {
                List<Entry> saved = json.readValue(manifest.toFile(), new TypeReference<List<Entry>>() {});
                for (Entry entry : saved) entries.put(entry.path(), entry);
            } catch (Exception ex) {
                // An unreadable manifest cannot be used as an ACL source; rebuild from the originals.
                entries.clear();
                writer.deleteAll();
            }
        } else writer.deleteAll();
    }

    @Scheduled(fixedDelayString = "${knowledge.poll-ms:5000}", initialDelayString = "${knowledge.poll-ms:5000}")
    public synchronized Status syncNow() {
        try {
            initialize();
            rejectSymlinkParents(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("source-unavailable");
            List<Path> paths;
            try (var walk = Files.walk(root)) {
                paths = walk.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted().limit(10001).toList();
            }
            if (paths.size() > 10000) throw new IOException("source-file-limit");
            Set<String> seen = new HashSet<>();
            for (Path path : paths) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                seen.add(relative);
                process(path, relative);
            }
            for (Entry entry : List.copyOf(entries.values())) {
                if (!seen.contains(entry.path()) && !entry.state().equals("DELETED")) {
                    writer.deleteDocuments(new Term("documentId", entry.documentId()));
                    entries.put(entry.path(), inactive(entry, "DELETED", "source-missing"));
                }
            }
            loadProposals();
            syncApprovedProposals();
            writer.commit();
            healthy = true;
            scanError = "";
            scannedAt = Instant.now().toString();
            writeMetadata();
            atomicWrite("policy.version", POLICY);
        } catch (Exception ex) {
            healthy = false;
            scanError = ex.getClass().getSimpleName();
            scannedAt = Instant.now().toString();
            LOG.warn("Knowledge scan unavailable ({})", scanError);
        }
        return status();
    }

    private void process(Path path, String relative) throws IOException {
        String type = DocumentExtractor.extension(path.getFileName().toString());
        String id = hash(relative.getBytes(StandardCharsets.UTF_8));
        Entry old = entries.get(relative);
        String version = "";
        long bytes = 0;
        long modified = 0;
        try {
            rejectSymlinkParents(path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || hidden(relative) || !TYPES.contains(type)) {
                writer.deleteDocuments(new Term("documentId", id));
                entries.put(relative, new Entry(id, relative, path.getFileName().toString(), "unassigned", "uncategorized", type,
                    "", 0, 0, "SKIPPED", 0, "", "unsupported-or-hidden", Instant.now().toString()));
                return;
            }
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            bytes = before.size();
            modified = before.lastModifiedTime().toMillis();
            if (bytes > DocumentExtractor.MAX_BYTES) throw new IllegalArgumentException("file-size-limit");
            version = fileHash(path);
            if (old != null && old.version().equals(version) && old.bytes() == bytes && old.modified() == modified &&
                (old.state().equals("ACTIVE") || old.state().equals("HISTORICAL") || old.state().equals("QUARANTINED") || old.state().equals("ERROR"))) return;
            var extracted = extractor.extract(path);
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) ||
                !version.equals(fileHash(path))) throw new IOException("source-changed-during-read");
            String project = project(relative, extracted.text());
            if ("unassigned".equals(project)) {
                writer.deleteDocuments(new Term("documentId", id));
                entries.put(relative, new Entry(id, relative, extracted.title(), project, category(relative, extracted.text()),
                    type, version, bytes, modified, "ERROR", 0, "", "unassigned-project", Instant.now().toString()));
                LOG.info("Knowledge document {}: ERROR (unassigned-project)", relative);
                return;
            }
            String category = category(relative, extracted.text());
            List<Document> documents = indexDocuments(id, relative, extracted.title(), project, category, type,
                    version, modified, extracted.chunks(), false);
            writer.updateDocuments(new Term("documentId", id), documents);
            String summary = extracted.text().lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                .findFirst().orElse("");
            summary = SensitiveData.mask(summary.substring(0, Math.min(180, summary.length())));
            entries.put(relative, new Entry(id, relative, extracted.title(), project, category, type, version, bytes,
                modified, historical(relative) ? "HISTORICAL" : "ACTIVE", documents.size(), summary, "", Instant.now().toString()));
        } catch (Exception ex) {
            writer.deleteDocuments(new Term("documentId", id));
            String state = ex instanceof DocumentExtractor.Quarantined ? "QUARANTINED" : "ERROR";
            entries.put(relative, new Entry(id, relative, path.getFileName().toString(), project(relative, ""),
                category(relative, ""), type, version, bytes, modified, state, 0, "", ex.getClass().getSimpleName(), Instant.now().toString()));
            LOG.info("Knowledge document {}: {} ({})", relative, state, ex.getClass().getSimpleName());
        }
    }

    private static List<Document> indexDocuments(String id, String relative, String title, String project, String category,
            String type, String version, long modified, List<DocumentExtractor.Chunk> chunks, boolean approvedProposal) {
        List<Document> documents = new ArrayList<>();
        int number = 0;
        for (var chunk : chunks) {
            if (chunk.text().isBlank()) continue;
            Document doc = new Document();
            add(doc, "documentId", id); add(doc, "path", relative); add(doc, "title", title);
            add(doc, "project", project); add(doc, "category", category); add(doc, "type", type);
            add(doc, "version", version); add(doc, "chunkId", id + ":" + number++);
            add(doc, "section", chunk.section());
            add(doc, "acl", (approvedProposal ? "APPROVED:" : "OFFICIAL:") + project);
            add(doc, "indexedAt", Instant.now().toString());
            add(doc, "modifiedAt", Long.toString(modified));
            if (approvedProposal) add(doc, "approved-proposal", "true");
            if (chunk.row() != null) doc.add(new StoredField("row", chunk.row()));
            doc.add(new StoredField("text", chunk.text()));
            String searchable = relative + "\n" + title + "\n" + chunk.section() + "\n" + chunk.text();
            doc.add(new TextField("korean", searchable, Field.Store.NO));
            doc.add(new TextField("exact", searchable, Field.Store.NO));
            documents.add(doc);
        }
        return documents;
    }

    @Override
    public synchronized List<SearchHit> search(String question, Set<String> projects, int limit) {
        if (question == null || question.isBlank() || question.length() > 4000 || projects == null || projects.isEmpty()) return List.of();
        // A request revalidates source availability and versions, so missed filesystem events cannot expose stale data.
        syncNow();
        if (!healthy) return List.of();
        try {
            Set<String> allowed = new HashSet<>(projects);
            allowed.retainAll(SUPPORTED_PROJECTS);
            if (allowed.isEmpty()) return List.of();
            BooleanQuery.Builder acl = new BooleanQuery.Builder();
            for (String project : allowed) acl.add(new TermQuery(new Term("project", project)), BooleanClause.Occur.SHOULD);
            if (allowed.containsAll(SHARED_WORKBOOK_GRANTS)) {
                acl.add(new TermQuery(new Term("project", SHARED_WORKBOOK_PROJECT)), BooleanClause.Occur.SHOULD);
            }
            acl.setMinimumNumberShouldMatch(1);
            BooleanQuery.Builder technical = new BooleanQuery.Builder();
            int technicalCount = 0;
            var identifiers = TOKEN.matcher(question);
            while (identifiers.find() && technicalCount < 16) {
                String value = identifiers.group();
                if (value.contains("_") || value.contains("/") || value.matches("[A-Z][A-Z0-9_]{2,}") || value.matches("[a-z]+[A-Z][A-Za-z0-9]*")) {
                    technical.add(new TermQuery(new Term("exact", value.toLowerCase(Locale.ROOT))), BooleanClause.Occur.SHOULD);
                    technicalCount++;
                }
            }
            BooleanQuery.Builder terms = new BooleanQuery.Builder();
            int count = 0;
            var matcher = TOKEN.matcher(question.toLowerCase(Locale.ROOT));
            while (matcher.find() && count < 32) {
                String token = matcher.group();
                if (token.length() < 2 || STOP.contains(token)) continue;
                terms.add(new BoostQuery(new TermQuery(new Term("exact", token)), 4), BooleanClause.Occur.SHOULD);
                count++;
            }
            String analyzedQuestion = TOKEN.matcher(question).results().map(MatchResult::group)
                    .filter(token -> !STOP.contains(token.toLowerCase(Locale.ROOT)))
                    .collect(Collectors.joining(" "));
            Set<String> koreanTerms = new LinkedHashSet<>();
            try (TokenStream stream = analyzer.tokenStream("korean", analyzedQuestion)) {
                CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
                stream.reset();
                while (stream.incrementToken() && count < 64) {
                    String token = term.toString();
                    if (token.length() < 2 || STOP.contains(token)) continue;
                    terms.add(new TermQuery(new Term("korean", token)), BooleanClause.Occur.SHOULD);
                    if (token.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL)) {
                        koreanTerms.add(token);
                    }
                    count++;
                }
                stream.end();
            }
            if (count == 0) return List.of();
            terms.setMinimumNumberShouldMatch(1);
            var queryBuilder = new BooleanQuery.Builder().add(acl.build(), BooleanClause.Occur.FILTER)
                .add(terms.build(), BooleanClause.Occur.MUST);
            if (technicalCount > 0) queryBuilder.add(technical.setMinimumNumberShouldMatch(1).build(), BooleanClause.Occur.MUST);
            addKoreanRelevance(queryBuilder, koreanTerms);
            queryBuilder.add(new BoostQuery(new ConstantScoreQuery(new TermQuery(new Term("approved-proposal", "true"))), 2),
                    BooleanClause.Occur.SHOULD);
            var query = queryBuilder.build();
            List<SearchHit> hits = new ArrayList<>();
            try (DirectoryReader reader = DirectoryReader.open(writer)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                for (var match : searcher.search(query, Math.clamp(limit, 1, 20) * 4).scoreDocs) {
                    Document doc = searcher.storedFields().document(match.doc);
                    boolean proposal = "true".equals(doc.get("approved-proposal"));
                    Entry current = (proposal ? approvedEntries : entries).get(doc.get("path"));
                    if (current == null || !current.state().equals("ACTIVE") || !current.version().equals(doc.get("version"))
                            || (proposal ? !mayReadApproved(current, allowed)
                                         : !hasProjectAccess(current, allowed) || !currentlyReadable(current))) continue;
                    hits.add(new SearchHit(doc.get("documentId"), doc.get("path"), doc.get("title"), current.project(),
                        doc.get("category"), doc.get("type"), doc.get("version"), doc.get("chunkId"), doc.get("section"),
                        doc.getField("row") == null ? null : doc.getField("row").numericValue().intValue(), doc.get("text"), match.score, proposal));
                    if (hits.size() >= Math.clamp(limit, 1, 20)) break;
                }
            }
            return List.copyOf(hits);
        } catch (Exception ex) {
            healthy = false;
            scanError = ex.getClass().getSimpleName();
            LOG.warn("Knowledge search unavailable ({})", scanError);
            return List.of();
        }
    }

    private static void addKoreanRelevance(BooleanQuery.Builder query, Set<String> tokens) {
        if (tokens.size() < 2) return;
        for (int minimum = 2; minimum <= Math.min(3, tokens.size()); minimum++) {
            BooleanQuery.Builder cooccurrence = new BooleanQuery.Builder().setMinimumNumberShouldMatch(minimum);
            for (String token : tokens) {
                cooccurrence.add(new TermQuery(new Term("korean", token)), BooleanClause.Occur.SHOULD);
            }
            query.add(new BoostQuery(new ConstantScoreQuery(cooccurrence.build()), 12), BooleanClause.Occur.SHOULD);
        }
        if (tokens.size() <= 4) {
            var phrase = new PhraseQuery(2, "korean", tokens.toArray(String[]::new));
            query.add(new BoostQuery(new ConstantScoreQuery(phrase), 12), BooleanClause.Occur.SHOULD);
        }
    }

    public synchronized KnowledgeProposal.Draft draft(KnowledgeProposalController.DraftRequest request, AccessContext author) {
        String instruction = proposalText(request.instruction(), 4000);
        Entry source = proposalTarget(request.project(), request.sourceDocumentId(), request.sourceVersion(), author);
        String content = instruction.replaceFirst("(?:[.。]?[ \\t]*)(?:추가|반영|수정|등록|보완)\\s*해\\s*(?:줘|주세요)[.!。]?$", "").strip();
        if (content.isEmpty()) content = instruction;
        String title = source == null ? content.lines().findFirst().orElse("새 지식").strip()
                : "보완: " + source.title();
        title = title.substring(0, Math.min(title.length(), 120));
        String before = source == null ? null : sourceExcerpt(source, content);
        return new KnowledgeProposal.Draft(source == null ? Kind.NEW : Kind.AMENDMENT, request.project(),
                source == null ? null : source.documentId(), source == null ? null : source.version(),
                source == null ? null : source.title(), before, title, content);
    }

    public synchronized KnowledgeProposal submit(KnowledgeProposalController.SubmitRequest request, AccessContext author) {
        if (request.consent() == null || !request.consent().isBoolean() || !request.consent().booleanValue()) {
            throw new AiException("PROPOSAL_CONSENT_REQUIRED", "공유 동의를 확인해 주세요.");
        }
        requireProposals();
        String title = proposalText(request.title(), 120);
        String content = proposalText(request.content(), 4000);
        validateProposalContent(title, content);
        Entry source = proposalTarget(request.project(), request.sourceDocumentId(), request.sourceVersion(), author);
        if (proposals.size() >= MAX_PROPOSALS) throw new AiException("PROPOSAL_UNAVAILABLE", "제안 저장 공간의 한도에 도달했어요.");
        String now = Instant.now().toString();
        KnowledgeProposal proposal = new KnowledgeProposal(UUID.randomUUID().toString(), source == null ? Kind.NEW : Kind.AMENDMENT,
                request.project(), source == null ? null : source.documentId(), source == null ? null : source.version(),
                source == null ? null : source.title(), title, content, author.userId(), author.directPublish() ? State.APPROVED : State.PENDING_APPROVAL, now, now);
        if (author.directPublish()) validateApproval(proposal, source);
        saveProposal(proposal);
        return proposal;
    }

    public synchronized List<KnowledgeProposal> ownProposals(String author) {
        requireProposals();
        return proposals.values().stream().filter(p -> p.author().equals(author))
                .sorted(Comparator.comparing(KnowledgeProposal::createdAt).reversed()).toList();
    }

    public synchronized List<KnowledgeProposal> pendingProposals(AccessContext admin) {
        requireProposals();
        return proposals.values().stream().filter(p -> p.state() == State.PENDING_APPROVAL
                        && hasProposalScope(p.project(), admin.projects()))
                .sorted(Comparator.comparing(KnowledgeProposal::createdAt)).toList();
    }

    public synchronized KnowledgeProposal withdrawProposal(String id, String author) {
        requireProposals();
        KnowledgeProposal proposal = proposals.get(id);
        if (proposal == null || !proposal.author().equals(author)) throw proposalNotFound();
        if (proposal.state() != State.PENDING_APPROVAL && proposal.state() != State.APPROVED) throw proposalConflict();
        KnowledgeProposal withdrawn = proposal.withState(State.WITHDRAWN, Instant.now().toString());
        saveProposal(withdrawn);
        return withdrawn;
    }

    public synchronized KnowledgeProposal reviewProposal(String id, AccessContext admin, State next) {
        requireProposals();
        KnowledgeProposal proposal = proposals.get(id);
        if (proposal == null || !hasProposalScope(proposal.project(), admin.projects())) throw proposalNotFound();
        if (proposal.state() != State.PENDING_APPROVAL) throw proposalConflict();
        if (proposal.author().equals(admin.userId())) {
            throw new AiException("PROPOSAL_FORBIDDEN", "본인이 제출한 제안은 검토할 수 없어요.");
        }
        if (next == State.APPROVED) {
            validateProposalContent(proposal.title(), proposal.content());
            Entry source = proposalTarget(proposal.project(), proposal.sourceDocumentId(), proposal.sourceVersion(), admin);
            validateApproval(proposal, source);
        }
        KnowledgeProposal reviewed = proposal.withState(next, Instant.now().toString());
        saveProposal(reviewed);
        return reviewed;
    }

    private void validateApproval(KnowledgeProposal proposal, Entry source) {
        if (!SensitiveData.guard(new String(proposalMarkdown(proposal), StandardCharsets.UTF_8))) {
            throw new AiException("SENSITIVE_INPUT", "승인 문서에 민감정보가 포함되어 있어요.");
        }
        if (source != null && proposals.values().stream().anyMatch(p -> p.state() == State.APPROVED
                && source.documentId().equals(p.sourceDocumentId()) && source.version().equals(p.sourceVersion()))) {
            throw new AiException("PROPOSAL_CONFLICT", "이 원본 버전에 이미 승인된 보완 지식이 있어요.");
        }
    }

    private static String proposalText(String text, int max) {
        if (text == null || text.isBlank() || text.length() > max) {
            throw new AiException("PROPOSAL_INVALID", "제목과 내용을 허용된 길이 안에서 입력해 주세요.");
        }
        String normalized = text.strip();
        if (!SensitiveData.guard(normalized)) {
            throw new AiException("SENSITIVE_INPUT", "비밀번호, 키 또는 개인정보를 제거해 주세요.");
        }
        return normalized;
    }

    private static void validateProposalContent(String title, String content) {
        if (title.lines().count() != 1 || !SensitiveData.guard(title + "\n" + content)) {
            throw new AiException("PROPOSAL_INVALID", "제목을 한 줄로 입력하고 민감정보를 제거해 주세요.");
        }
    }

    private Entry proposalTarget(String project, String sourceId, String version, AccessContext access) {
        if (project == null || project.isBlank()) throw new AiException("PROPOSAL_INVALID", "프로젝트를 선택해 주세요.");
        if (sourceId == null && version == null) {
            if (!SUPPORTED_PROJECTS.contains(project) || !access.projects().contains(project)) throw proposalNotFound();
            return null;
        }
        if (sourceId == null || version == null || !CONTENT_ID.matcher(sourceId).matches()
                || !CONTENT_ID.matcher(version).matches()) throw new AiException("PROPOSAL_INVALID", "원본 문서와 버전을 선택해 주세요.");
        if (!healthy) throw new AiException("PROPOSAL_UNAVAILABLE", "원본 색인을 읽을 수 없어요.");
        Entry source = officialSource(sourceId);
        if (source == null || !project.equals(source.project()) || !hasProjectAccess(source, access.projects())) {
            throw proposalNotFound();
        }
        if (!"ACTIVE".equals(source.state()) || !version.equals(source.version()) || !currentlyReadable(source)) throw proposalConflict();
        return source;
    }

    private Entry officialSource(String documentId) {
        if (documentId == null) return null;
        return entries.values().stream().filter(entry -> documentId.equals(entry.documentId())).findFirst().orElse(null);
    }

    private String sourceExcerpt(Entry source, String instruction) {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            var original = new TermQuery(new Term("documentId", source.documentId()));
            var relevant = new BooleanQuery.Builder().add(original, BooleanClause.Occur.FILTER);
            try (TokenStream stream = analyzer.tokenStream("korean", instruction)) {
                var term = stream.addAttribute(CharTermAttribute.class);
                stream.reset();
                for (int count = 0; count < 64 && stream.incrementToken(); count++) {
                    String token = term.toString();
                    if (token.length() >= 2 && !STOP.contains(token)) {
                        relevant.add(new TermQuery(new Term("korean", token)), BooleanClause.Occur.SHOULD);
                    }
                }
                stream.end();
            }
            var top = searcher.search(relevant.setMinimumNumberShouldMatch(1).build(), 1);
            if (top.scoreDocs.length == 0) top = searcher.search(original, 1);
            if (top.scoreDocs.length == 0) throw proposalConflict();
            var doc = searcher.storedFields().document(top.scoreDocs[0].doc);
            if (!source.version().equals(doc.get("version"))) throw proposalConflict();
            String text = SensitiveData.mask(doc.get("text"));
            return text.substring(0, Math.min(text.length(), 1200));
        } catch (IOException ex) {
            throw new AiException("PROPOSAL_UNAVAILABLE", "원본 색인을 읽을 수 없어요.");
        }
    }

    private void requireProposals() {
        try {
            initialize();
            loadProposals();
        } catch (Exception ex) {
            throw new AiException("PROPOSAL_UNAVAILABLE", "제안 저장소를 읽을 수 없어요.");
        }
    }

    private void loadProposals() throws IOException {
        if (proposalsLoaded) return;
        Path file = indexPath.resolve(PROPOSALS_FILE);
        rejectSymlinkParents(file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 32 * 1024 * 1024) {
                throw new IOException("proposal-store-invalid");
            }
            List<KnowledgeProposal> saved = json.readValue(file.toFile(), new TypeReference<List<KnowledgeProposal>>() {});
            Map<String, KnowledgeProposal> loaded = new LinkedHashMap<>();
            for (KnowledgeProposal proposal : saved) {
                if (!UUID.fromString(proposal.id()).toString().equals(proposal.id()) || loaded.put(proposal.id(), proposal) != null) {
                    throw new IOException("proposal-store-invalid");
                }
            }
            proposals.putAll(loaded);
        }
        proposalsLoaded = true;
    }

    private void saveProposal(KnowledgeProposal next) {
        var changed = new LinkedHashMap<>(proposals);
        changed.put(next.id(), next);
        try {
            rejectSymlinkParents(indexPath.resolve(PROPOSALS_FILE));
            String snapshot = json.writeValueAsString(changed.values());
            if (snapshot.getBytes(StandardCharsets.UTF_8).length > 32 * 1024 * 1024) {
                throw new IOException("proposal-store-limit");
            }
            atomicWrite(PROPOSALS_FILE, snapshot);
        } catch (Exception ex) {
            throw new AiException("PROPOSAL_UNAVAILABLE", "제안 상태를 저장할 수 없어요. 다시 시도해 주세요.");
        }
        proposals.put(next.id(), next);
    }

    private void syncApprovedProposals() throws IOException {
        for (Entry entry : List.copyOf(approvedEntries.values())) {
            if (!indexableProposal(proposals.get(proposalId(entry.path())))) {
                writer.deleteDocuments(new Term("documentId", entry.documentId()));
                approvedEntries.remove(entry.path());
            }
        }
        for (KnowledgeProposal proposal : proposals.values()) {
            if (!indexableProposal(proposal)) continue;
            String path = PROPOSAL_PATH_PREFIX + proposal.id() + ".md";
            if (approvedEntries.containsKey(path)) continue;
            byte[] markdown = proposalMarkdown(proposal);
            String id = hash(("approved:" + path).getBytes(StandardCharsets.UTF_8));
            String version = hash(markdown);
            long modified = Instant.parse(proposal.updatedAt()).toEpochMilli();
            String title = "승인 지식: " + proposal.title();
            var chunks = DocumentExtractor.markdown(new String(markdown, StandardCharsets.UTF_8));
            var documents = indexDocuments(id, path, title, proposal.project(), "uncategorized", "md",
                    version, modified, chunks, true);
            writer.updateDocuments(new Term("documentId", id), documents);
            approvedEntries.put(path, new Entry(id, path, title, proposal.project(), "uncategorized", "md", version,
                    markdown.length, modified, "ACTIVE", documents.size(), "", "", proposal.updatedAt()));
        }
    }

    private boolean indexableProposal(KnowledgeProposal proposal) {
        if (proposal == null || proposal.state() != State.APPROVED) return false;
        if (!SensitiveData.guard(new String(proposalMarkdown(proposal), StandardCharsets.UTF_8))) return false;
        if (proposal.kind() == Kind.NEW) return SUPPORTED_PROJECTS.contains(proposal.project());
        return currentApprovedBase(proposal);
    }

    private boolean currentApprovedBase(KnowledgeProposal proposal) {
        Entry source = officialSource(proposal.sourceDocumentId());
        return source != null && "ACTIVE".equals(source.state()) && source.version().equals(proposal.sourceVersion())
                && source.project().equals(proposal.project()) && currentlyReadable(source);
    }

    private boolean mayReadApproved(Entry entry, Set<String> projects) {
        KnowledgeProposal proposal = proposals.get(proposalId(entry.path()));
        return proposal != null && proposal.state() == State.APPROVED && hasProposalScope(entry.project(), projects)
                && (proposal.kind() == Kind.NEW || currentApprovedBase(proposal));
    }

    private static boolean hasProposalScope(String project, Set<String> projects) {
        return SHARED_WORKBOOK_PROJECT.equals(project) ? projects.containsAll(SHARED_WORKBOOK_GRANTS)
                : SUPPORTED_PROJECTS.contains(project) && projects.contains(project);
    }

    private static String proposalId(String path) {
        return path.substring(PROPOSAL_PATH_PREFIX.length(), path.length() - ".md".length());
    }

    private static byte[] proposalMarkdown(KnowledgeProposal proposal) {
        String provenance = proposal.kind() == Kind.AMENDMENT
                ? "\n보완 대상: " + proposal.sourceTitle().replace('\n', ' ').replace('\r', ' ') + "\n"
                : "";
        return ("# 승인 지식: " + proposal.title() + "\n" + provenance + "\n" + proposal.content() + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static AiException proposalNotFound() {
        return new AiException("PROPOSAL_NOT_FOUND", "문서 또는 제안을 찾을 수 없어요.");
    }

    private static AiException proposalConflict() {
        return new AiException("PROPOSAL_CONFLICT", "문서 버전이나 제안 상태가 변경됐어요. 최신 내용을 확인해 주세요.");
    }

    private boolean currentlyReadable(Entry entry) {
        try {
            Path path = sourcePath(entry);
            return matchesSnapshot(path, entry) && fileHash(path).equals(entry.version());
        } catch (Exception ex) { return false; }
    }

    public synchronized Optional<SourceFile> download(String documentId, String version, Set<String> projects) {
        if (!healthy || !CONTENT_ID.matcher(documentId).matches() || version == null
                || !CONTENT_ID.matcher(version).matches() || projects.isEmpty()) return Optional.empty();
        for (Entry approved : approvedEntries.values()) {
            if (documentId.equals(approved.documentId()) && version.equals(approved.version()) && mayReadApproved(approved, projects)) {
                KnowledgeProposal proposal = proposals.get(proposalId(approved.path()));
                return Optional.of(new SourceFile("approved-" + proposal.id() + ".md", proposalMarkdown(proposal)));
            }
        }
        Entry entry = entries.values().stream()
                .filter(candidate -> documentId.equals(candidate.documentId()) && version.equals(candidate.version())
                        && "ACTIVE".equals(candidate.state()) && candidate.chunks() > 0
                        && hasProjectAccess(candidate, projects))
                .findFirst().orElse(null);
        if (entry == null || entry.bytes() < 0 || entry.bytes() > DocumentExtractor.MAX_BYTES) return Optional.empty();
        try {
            Path path = sourcePath(entry);
            if (!matchesSnapshot(path, entry)) return Optional.empty();
            byte[] bytes;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(Math.toIntExact(entry.bytes() + 1));
            }
            // Verify the exact bytes returned so a file swap cannot bypass the cited version or document ACL.
            if (bytes.length != entry.bytes() || !version.equals(hash(bytes))
                    || !matchesSnapshot(sourcePath(entry), entry)) return Optional.empty();
            String name = path.getFileName().toString();
            String filename = SensitiveData.guard(name) ? name.replaceAll("[^\\p{L}\\p{M}\\p{N} ._()\\-]", "_") : "source." + entry.type();
            return Optional.of(new SourceFile(filename, bytes));
        } catch (IOException | SecurityException ex) {
            return Optional.empty();
        }
    }

    private Path sourcePath(Entry entry) throws IOException {
        Path path = root.resolve(entry.path()).normalize();
        if (!path.startsWith(root)) throw new IOException("outside-source-root");
        rejectSymlinkParents(path);
        return path;
    }

    private static boolean matchesSnapshot(Path path, Entry entry) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isRegularFile() && attributes.size() == entry.bytes()
                && attributes.lastModifiedTime().toMillis() == entry.modified();
    }

    public synchronized Status status() {
        List<Entry> documents = entries.values().stream().sorted(Comparator.comparing(Entry::path)).toList();
        Map<String, Long> projects = new java.util.TreeMap<>();
        Map<String, Long> categories = new java.util.TreeMap<>();
        for (Entry entry : documents) if (!entry.state().equals("DELETED")) {
            projects.merge(entry.project(), 1L, Long::sum);
            categories.merge(entry.category(), 1L, Long::sum);
        }
        return new Status(healthy, scannedAt, scanError, (int) documents.stream().filter(e -> !e.state().equals("DELETED")).count(),
            count("ACTIVE"), documents.stream().filter(e -> e.state().equals("ACTIVE")).mapToInt(Entry::chunks).sum(),
            count("QUARANTINED"), count("ERROR"), count("SKIPPED"), count("DELETED"), count("HISTORICAL"), Map.copyOf(projects), Map.copyOf(categories), documents);
    }

    private int count(String state) { return (int) entries.values().stream().filter(e -> e.state().equals(state)).count(); }
    private static void add(Document doc, String name, String value) { doc.add(new StringField(name, value, Field.Store.YES)); }
    private static Entry inactive(Entry e, String state, String error) {
        return new Entry(e.documentId(), e.path(), e.title(), e.project(), e.category(), e.type(), e.version(),
            e.bytes(), e.modified(), state, 0, "", error, e.indexedAt());
    }
    private static boolean historical(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/before/") || lower.contains("rollback") || lower.contains("revision.diff");
    }
    private static boolean hidden(String relative) {
        for (String part : relative.split("/")) if (part.startsWith(".") || part.startsWith("~$")) return true;
        return false;
    }
    private static void rejectSymlinkParents(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("symlink-rejected");
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String fileHash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int length;
            long total = 0;
            while ((length = input.read(buffer)) != -1) {
                total += length;
                if (total > DocumentExtractor.MAX_BYTES) throw new IOException("file-size-limit");
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String project(String path, String text) {
        if (SHARED_WORKBOOK.equals(path)) return SHARED_WORKBOOK_PROJECT;
        String content = text.toLowerCase(Locale.ROOT);
        String name = path.toLowerCase(Locale.ROOT);
        boolean pay = name.contains("paye2u") || content.contains("paye2u");
        boolean sys = name.contains("syslink") || content.contains("syslink");
        if (pay && sys) return "unassigned";
        if (pay) return "paye2u";
        if (sys) return "syslink";
        String first = name.contains("/") ? name.substring(0, name.indexOf('/')) : "unassigned";
        return SUPPORTED_PROJECTS.contains(first) ? first : "unassigned";
    }

    private static boolean hasProjectAccess(Entry entry, Set<String> grants) {
        if (SHARED_WORKBOOK.equals(entry.path())) {
            return SHARED_WORKBOOK_PROJECT.equals(entry.project()) && grants.containsAll(SHARED_WORKBOOK_GRANTS);
        }
        return SUPPORTED_PROJECTS.contains(entry.project()) && grants.contains(entry.project());
    }
    private static String category(String path, String text) {
        String source = (path + "\n" + text.substring(0, Math.min(text.length(), 100000))).toLowerCase(Locale.ROOT);
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("database", List.of("create table", "varchar", "ddl", "테이블", "prc_", "trg_"));
        categories.put("payment", List.of("결제", "승인", "취소", "payment", "cancel"));
        categories.put("settlement", List.of("정산", "수수료", "채권", "settlement", "cmsn"));
        categories.put("merchant", List.of("가맹점", "merchant", "mcht"));
        categories.put("api", List.of("api", "endpoint", "request", "response"));
        categories.put("security", List.of("인증", "보안", "security", "방화벽"));
        categories.put("operations", List.of("운영", "장애", "복구", "업무", "operations"));
        String best = "uncategorized";
        int bestScore = 0;
        for (var category : categories.entrySet()) {
            int score = 0;
            for (String keyword : category.getValue()) {
                for (int from = 0, found; (found = source.indexOf(keyword, from)) >= 0; from = found + keyword.length()) score++;
            }
            if (score > bestScore) { best = category.getKey(); bestScore = score; }
        }
        return best;
    }
    private void writeMetadata() throws Exception {
        var snapshot = status();
        atomicWrite("manifest.json", json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot.documents()));
        atomicWrite("status.json", json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
        StringBuilder csv = new StringBuilder("documentId,path,title,project,category,type,version,bytes,state,chunks,summary,error\n");
        for (Entry e : snapshot.documents()) {
            for (String value : List.of(e.documentId(), e.path(), e.title(), e.project(), e.category(), e.type(), e.version(),
                Long.toString(e.bytes()), e.state(), Integer.toString(e.chunks()), e.summary(), e.error())) {
                if (value.matches("^[=+@-].*")) value = "'" + value;
                csv.append('"').append(value.replace("\"", "\"\"")).append("\",");
            }
            csv.setLength(csv.length() - 1);
            csv.append('\n');
        }
        atomicWrite("inventory.csv", csv.toString());
    }
    private void atomicWrite(String name, String value) throws Exception {
        Path destination = indexPath.resolve(name);
        Path temporary = Files.createTempFile(indexPath, "metadata-", ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    @Override @PreDestroy
    public synchronized void close() throws IOException {
        healthy = false;
        if (writer != null) writer.close();
        if (directory != null) directory.close();
        if (analyzer != null) analyzer.close();
        writer = null;
    }
}
