package com.mychatbot.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Tracks only generated copies; canonical proposals and uploads remain in the submission store. */
final class PublishedKnowledge {
    record OwnedFile(String path, String version) {}
    record Publication(String id, String folder, List<OwnedFile> files, boolean active) {
        Publication(String id, String folder, List<OwnedFile> files) { this(id, folder, files, false); }
    }
    private final Path root;
    private final Path store;
    private final ObjectMapper json = new ObjectMapper();
    private Map<String, Publication> publications;
    private String ledgerVersion;
    static final class StoreUnavailable extends IOException {
        StoreUnavailable(IOException cause) { super("publication-store-unavailable", cause); }
    }

    PublishedKnowledge(Path root, Path store) { this.root = root; this.store = store; }

    void load() throws IOException {
        if (publications != null) return;
        KnowledgeService.rejectSymlinkParents(store);
        Files.createDirectories(store);
        Path file = store.resolve("published-files.json");
        KnowledgeService.rejectSymlinkParents(file);
        Map<String, Publication> loaded = new LinkedHashMap<>();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16 * 1024 * 1024) throw new IOException("publication-store-invalid");
            List<Publication> saved = json.readValue(file.toFile(), new TypeReference<List<Publication>>() {});
            if (saved == null) throw new IOException("publication-store-invalid");
            Set<String> paths = new HashSet<>();
            for (Publication publication : saved) {
                if (publication == null || !UUID.fromString(publication.id()).toString().equals(publication.id())
                        || publication.folder() == null || (!publication.folder().isEmpty() && !KnowledgeFolders.validPath(publication.folder()))
                        || publication.files() == null || publication.files().isEmpty() || publication.files().size() > 4
                        || loaded.put(publication.id(), publication) != null) throw new IOException("publication-store-invalid");
                for (OwnedFile owned : publication.files()) {
                    if (owned == null || !validRelative(owned.path()) || !owned.version().matches("[0-9a-f]{64}") || !paths.add(owned.path())
                            || !ownedPath(publication, owned.path())) {
                        throw new IOException("publication-store-invalid");
                    }
                }
            }
        } else if (Files.exists(store.resolve(".published-initialized"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("publication-store-missing");
        }
        publications = loaded;
        try {
            save();
            write(store.resolve(".published-initialized"), "1".getBytes(StandardCharsets.UTF_8), true);
        } catch (IOException failure) { publications = null; throw failure; }
    }

    boolean owns(String path) {
        return publications.values().stream().anyMatch(publication -> {
            String group = (publication.folder().isEmpty() ? "" : publication.folder() + "/") + "attachments/" + publication.id();
            return path.equals(group) || path.startsWith(group + "/") || publication.files().stream()
                    .anyMatch(file -> file.path().equals(path) || path.startsWith(file.path() + "/"));
        });
    }

    Optional<String> bodyPath(String id) {
        Publication publication = publications == null ? null : publications.get(id);
        if (publication == null) return Optional.empty();
        String prefix = publication.folder().isEmpty() ? "" : publication.folder() + "/";
        return publication.files().stream().map(OwnedFile::path)
                .filter(path -> path.startsWith(prefix) && !path.substring(prefix.length()).contains("/")
                        && path.endsWith("--" + id + ".md")).findFirst();
    }

    Optional<String> owner(String path) {
        if (publications == null) return Optional.empty();
        return publications.values().stream().filter(publication -> publication.files().stream().anyMatch(file -> file.path().equals(path)))
                .map(Publication::id).findFirst();
    }

    Optional<String> attachmentPath(String id, int position) {
        Publication publication = publications == null ? null : publications.get(id);
        if (publication == null) return Optional.empty();
        String prefix = (publication.folder().isEmpty() ? "" : publication.folder() + "/") + "attachments/" + id + "/" + (position + 1) + "--";
        List<String> paths = publication.files().stream().map(OwnedFile::path).filter(path -> path.startsWith(prefix)).toList();
        return paths.size() == 1 ? Optional.of(paths.get(0)) : Optional.empty();
    }

    boolean hasArchivedBody(String id) throws IOException {
        String body = bodyPath(id).orElse(null);
        if (body == null) return false;
        Path directory = store.resolve("published-history").resolve(id);
        KnowledgeService.rejectSymlinkParents(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var generations = Files.list(directory)) {
            for (Path generation : generations.toList()) {
                if (!generation.getFileName().toString().matches("[0-9a-f]{64}")) continue;
                Path file = generation.resolve(body);
                KnowledgeService.rejectSymlinkParents(file);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return true;
            }
        }
        return false;
    }

    boolean active(String id) { return publications != null && publications.containsKey(id) && publications.get(id).active(); }

    void active(String id, boolean active) throws IOException {
        Publication previous = publications.get(id);
        if (previous == null || previous.active() == active) return;
        publications.put(id, new Publication(id, previous.folder(), previous.files(), active));
        try { save(); } catch (IOException failure) { publications.put(id, previous); throw failure; }
    }

    List<String> paths(String id) {
        Publication publication = publications == null ? null : publications.get(id);
        return publication == null ? List.of() : publication.files().stream().map(OwnedFile::path).toList();
    }

    boolean pathsAllowed(String id, java.util.function.Predicate<String> allowed) {
        if (publications == null) return false;
        Publication publication = publications.get(id);
        return publication == null || publication.files().stream().allMatch(file -> allowed.test(file.path()));
    }

    Set<String> ids() { return Set.copyOf(publications.keySet()); }

    boolean ready(KnowledgeProposal proposal) {
        Publication publication = publications == null ? null : publications.get(proposal.id());
        if (publication == null) return false;
        try {
            for (OwnedFile owned : publication.files()) {
                Path file = root.resolve(owned.path());
                KnowledgeService.rejectSymlinkParents(file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > DocumentExtractor.MAX_BYTES) return false;
            }
            Set<String> expected = new HashSet<>();
            for (int position = 0; position < proposal.attachments().size(); position++) {
                var attachment = proposal.attachments().get(position);
                String path = attachmentPath(proposal.id(), position).orElse(null);
                if (path == null || !expected.add(path) || Files.size(root.resolve(path)) > AttachmentImage.MAX_BYTES
                        || (attachment.image() && !matches(root.resolve(path), attachment.version()))) return false;
                if (!attachment.documentId().matches("[0-9a-f]{64}") || !attachment.version().matches("[0-9a-f]{64}")
                        || !matches(store.resolve("attachments").resolve(attachment.documentId()), attachment.version())) return false;
            }
            Path group = root.resolve((publication.folder().isEmpty() ? "" : publication.folder() + "/") + "attachments/" + proposal.id());
            KnowledgeService.rejectSymlinkParents(group);
            if (Files.exists(group, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)) return false;
                try (var files = Files.list(group)) {
                    for (Path file : files.toList()) if (!knownPath(file, root, expected)) return false;
                }
            }
            return true;
        } catch (IOException ex) { return false; }
    }

    private boolean knownPath(Path file, Path base, Set<String> expected) throws IOException {
        KnowledgeService.rejectSymlinkParents(file);
        String relative = base.relativize(file).toString().replace('\\', '/');
        if (expected.contains(relative)) return true;
        String normalized = java.text.Normalizer.normalize(relative, java.text.Normalizer.Form.NFC);
        for (String known : expected) {
            if (normalized.equals(java.text.Normalizer.normalize(known, java.text.Normalizer.Form.NFC))
                    && Files.isSameFile(file, base.resolve(known))) return true;
        }
        return false;
    }

    void publish(KnowledgeProposal proposal, String folder) throws IOException {
        load();
        Publication previous = publications.get(proposal.id());
        if (previous != null) folder = previous.folder();
        if (folder == null || (!folder.isEmpty() && !KnowledgeFolders.validPath(folder))) throw new IOException("publication-folder-invalid");
        Path directory = root.resolve(folder);
        KnowledgeService.rejectSymlinkParents(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("publication-folder-missing");
        String prefix = folder.isEmpty() ? "" : folder + "/";
        Map<String, byte[]> contents = new LinkedHashMap<>();
        StringBuilder links = new StringBuilder();
        int position = 0;
        for (var attachment : proposal.attachments()) {
            if (!attachment.documentId().matches("[0-9a-f]{64}") || !attachment.version().matches("[0-9a-f]{64}")) throw new IOException("publication-attachment-invalid");
            Path source = store.resolve("attachments").resolve(attachment.documentId());
            KnowledgeService.rejectSymlinkParents(source);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > AttachmentImage.MAX_BYTES) throw new IOException("publication-attachment-missing");
            byte[] bytes = Files.readAllBytes(source);
            if (!hash(bytes).equals(attachment.version())) throw new IOException("publication-attachment-changed");
            String name = (++position) + "--" + safeName(attachment.filename(), 100);
            String relative = "attachments/" + proposal.id() + "/" + name;
            contents.put(prefix + relative, bytes);
            links.append("\n- [").append(attachment.filename().replace("[", "\\[").replace("]", "\\]"))
                    .append("](").append("attachments/").append(proposal.id()).append('/')
                    .append(URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")).append(")")
                    .append(attachment.description().isBlank() ? "" : " — " + attachment.description());
        }
        String provenance = proposal.kind() == KnowledgeProposal.Kind.AMENDMENT
                ? "\n원본 문서 ID: " + proposal.sourceDocumentId() + "\n원본 버전: " + proposal.sourceVersion()
                    + "\n보완 대상: " + proposal.sourceTitle() + "\n" : "";
        String markdown = "# " + proposal.title() + "\n\n제안 ID: " + proposal.id() + "\n작성자: " + proposal.author()
                + "\n작성일: " + proposal.createdAt() + "\n승인일: " + proposal.updatedAt() + "\n상태: APPROVED\n"
                + provenance + "\n" + proposal.content() + (links.isEmpty() ? "\n" : "\n\n## 첨부파일\n" + links + "\n");
        contents.put(prefix + safeName(proposal.title(), 60) + "--" + proposal.id() + ".md", markdown.getBytes(StandardCharsets.UTF_8));
        List<OwnedFile> expected = new ArrayList<>();
        for (var file : contents.entrySet()) expected.add(new OwnedFile(file.getKey(), hash(file.getValue())));
        if (expected.stream().anyMatch(file -> !validRelative(file.path()))) throw new IOException("publication-path-too-long");
        if (previous != null && !previous.files().equals(expected)) {
            for (OwnedFile file : previous.files()) {
                KnowledgeService.rejectSymlinkParents(root.resolve(file.path()));
                if (Files.exists(root.resolve(file.path()), LinkOption.NOFOLLOW_LINKS)) throw new IOException("publication-definition-changed");
            }
        }
        for (OwnedFile file : expected) {
            Path path = root.resolve(file.path());
            KnowledgeService.rejectSymlinkParents(path);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && (previous == null || !matches(path, file.version()))) {
                throw new IOException("publication-path-conflict");
            }
        }
        if (previous == null) {
            Path group = root.resolve(prefix + "attachments/" + proposal.id());
            KnowledgeService.rejectSymlinkParents(group);
            if (Files.exists(group, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)) throw new IOException("publication-group-conflict");
                try (var children = Files.list(group)) {
                    if (children.findAny().isPresent()) throw new IOException("publication-group-conflict");
                }
            }
        }
        if (previous == null || !previous.files().equals(expected)) {
            publications.put(proposal.id(), new Publication(proposal.id(), folder, List.copyOf(expected)));
            try { save(); } catch (IOException failure) {
                if (previous == null) publications.remove(proposal.id()); else publications.put(proposal.id(), previous);
                throw failure;
            }
        }
        for (var file : contents.entrySet()) {
            Path path = root.resolve(file.getKey());
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            KnowledgeService.rejectSymlinkParents(path);
            Files.createDirectories(path.getParent());
            write(path, file.getValue(), false);
        }
        if (!ready(proposal)) throw new IOException("publication-incomplete");
        active(proposal.id(), true);
    }

    void archive(String id) throws IOException {
        load();
        Publication publication = publications.get(id);
        if (publication == null) return;
        List<OwnedFile> current = new ArrayList<>();
        for (OwnedFile owned : publication.files()) {
            Path path = root.resolve(owned.path());
            KnowledgeService.rejectSymlinkParents(path);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(path) > DocumentExtractor.MAX_BYTES) throw new IOException("publication-body-limit");
                current.add(new OwnedFile(owned.path(), hash(Files.readAllBytes(path))));
            } else current.add(owned);
        }
        if (publication.active() || !current.equals(publication.files())) {
            Publication previous = publication;
            publication = new Publication(id, publication.folder(), List.copyOf(current), false);
            publications.put(id, publication);
            try { save(); } catch (IOException failure) { publications.put(id, previous); throw failure; }
        }
        String generation = hash(json.writeValueAsBytes(publication.files()));
        for (OwnedFile owned : publication.files()) {
            Path source = root.resolve(owned.path());
            KnowledgeService.rejectSymlinkParents(source);
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS) || !matches(source, owned.version())) continue;
            Path destination = store.resolve("published-history").resolve(id).resolve(generation).resolve(owned.path());
            KnowledgeService.rejectSymlinkParents(destination);
            Files.createDirectories(destination.getParent());
            if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                write(destination, Files.readAllBytes(source), false);
            }
            if (!matches(destination, owned.version())) throw new IOException("publication-archive-conflict");
            if (matches(source, owned.version())) Files.delete(source);
        }
    }

    void purge(String id) throws IOException { purge(id, false); }

    void purge(String id, boolean preserveRecreatedBody) throws IOException {
        load();
        if (!UUID.fromString(id).toString().equals(id)) throw new IOException("publication-id-invalid");
        Publication publication = publications.get(id);
        if (publication != null) {
            String preserved = preserveRecreatedBody ? bodyPath(id).orElse(null) : null;
            for (OwnedFile owned : publication.files()) {
                if (owned.path().equals(preserved)) continue;
                Path path = root.resolve(owned.path());
                KnowledgeService.rejectSymlinkParents(path);
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("publication-delete-conflict");
            }
            for (OwnedFile owned : publication.files()) {
                if (!owned.path().equals(preserved)) Files.deleteIfExists(root.resolve(owned.path()));
            }
            Path group = root.resolve((publication.folder().isEmpty() ? "" : publication.folder() + "/") + "attachments/" + id);
            KnowledgeService.rejectSymlinkParents(group);
            if (Files.exists(group, LinkOption.NOFOLLOW_LINKS)) Files.delete(group);
        }
        Path archived = store.resolve("published-history").resolve(id);
        KnowledgeService.rejectSymlinkParents(archived);
        if (Files.exists(archived, LinkOption.NOFOLLOW_LINKS)) {
            if (publication == null) throw new IOException("publication-history-owner-missing");
            Publication owned = publication;
            Files.walkFileTree(archived, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    KnowledgeService.rejectSymlinkParents(directory);
                    if (directory.equals(archived)) return FileVisitResult.CONTINUE;
                    Path relative = archived.relativize(directory);
                    if (!relative.getName(0).toString().matches("[0-9a-f]{64}")) throw new IOException("publication-history-path-invalid");
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    KnowledgeService.rejectSymlinkParents(path);
                    Path relative = archived.relativize(path);
                    if (!attributes.isRegularFile() || relative.getNameCount() < 2
                            || !relative.getName(0).toString().matches("[0-9a-f]{64}")
                            || !knownPath(path, archived.resolve(relative.getName(0)), owned.files().stream().map(OwnedFile::path).collect(java.util.stream.Collectors.toSet()))) {
                        throw new IOException("publication-history-path-invalid");
                    }
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (publication != null) {
            publications.remove(id);
            try { save(); } catch (IOException failure) { publications.put(id, publication); throw failure; }
        }
    }

    void verifyStore() throws IOException {
        load();
        Path file = store.resolve("published-files.json");
        Path marker = store.resolve(".published-initialized");
        KnowledgeService.rejectSymlinkParents(file);
        KnowledgeService.rejectSymlinkParents(marker);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16 * 1024 * 1024
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !hash(Files.readAllBytes(file)).equals(ledgerVersion)) throw new IOException("publication-store-changed");
    }

    private void save() throws IOException {
        try {
            byte[] bytes = json.writeValueAsBytes(publications.values());
            if (bytes.length > 16 * 1024 * 1024) throw new IOException("publication-store-limit");
            write(store.resolve("published-files.json"), bytes, true);
            ledgerVersion = hash(bytes);
        } catch (IOException failure) { throw new StoreUnavailable(failure); }
    }

    private static void write(Path path, byte[] bytes, boolean replace) throws IOException {
        KnowledgeService.rejectSymlinkParents(path);
        Path temporary = Files.createTempFile(path.getParent(), ".publication-", ".tmp");
        try {
            Files.write(temporary, bytes);
            if (replace) Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, path);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static boolean matches(Path path, String expected) throws IOException {
        KnowledgeService.rejectSymlinkParents(path);
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= DocumentExtractor.MAX_BYTES
                && hash(Files.readAllBytes(path)).equals(expected);
    }

    private static String safeName(String value, int length) {
        String name = value.replaceAll("[^\\p{L}\\p{M}\\p{N} ._-]", "_").replaceAll("^[ .]+|[ .]+$", "");
        if (name.isBlank()) name = "문서";
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
        String stem = extension.isEmpty() ? name : name.substring(0, dot);
        stem = stem.substring(0, Math.min(stem.length(), Math.max(1, length - extension.length())));
        while ((stem + extension).getBytes(StandardCharsets.UTF_8).length > 180) {
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
        }
        return stem + extension;
    }

    private static boolean validRelative(String path) {
        return com.mychatbot.security.AccessContext.validExcludedPath(path);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static boolean ownedPath(Publication publication, String path) {
        String prefix = publication.folder().isEmpty() ? "" : publication.folder() + "/";
        if (!path.startsWith(prefix)) return false;
        String local = path.substring(prefix.length());
        String attachments = "attachments/" + publication.id() + "/";
        return (!local.contains("/") && local.endsWith("--" + publication.id() + ".md"))
                || (local.startsWith(attachments) && local.substring(attachments.length()).matches("[1-9][0-9]*--[^/]+"));
    }
}
