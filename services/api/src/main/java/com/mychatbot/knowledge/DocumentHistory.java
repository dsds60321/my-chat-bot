package com.mychatbot.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Keeps old bytes and a recovery intent before an approved original-file replacement. */
final class DocumentHistory {
    private final Path root;
    private final Path store;
    private final ObjectMapper json = new ObjectMapper();
    record Version(String version, String filename, String createdAt) {}
    record Intent(String documentId, String path, String before, String after, String proposalId) {}
    interface Save { void run() throws IOException; }

    DocumentHistory(Path root, Path store) { this.root = root; this.store = store; }

    void replace(String id, String relative, String expected, byte[] next, String proposal, String project, Save save) throws IOException {
        if (Files.exists(store.resolve("document-transaction/intent.json"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("replacement-recovery-required");
        Path source = source(id, relative);
        byte[] before = read(source);
        if (!hash(before).equals(expected)) throw new IOException("replacement-version-conflict");
        Path history = historyDirectory(id);
        if (!scopeMatches(id, project)) throw new IOException("history-project-conflict");
        Files.createDirectories(history);
        Path scope = history.resolve(".project");
        if (!Files.exists(scope, LinkOption.NOFOLLOW_LINKS)) write(scope, project.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        backup(id, expected, before);
        Path transaction = store.resolve("document-transaction");
        KnowledgeService.rejectSymlinkParents(transaction);
        Files.createDirectories(transaction);
        write(transaction.resolve("next"), next);
        Intent intent = new Intent(id, relative, expected, hash(next), proposal);
        write(transaction.resolve("intent.json"), json.writeValueAsBytes(intent));
        if (!hash(read(source)).equals(expected)) {
            clear();
            throw new IOException("replacement-version-conflict");
        }
        write(source, next);
        try { save.run(); }
        catch (Exception failure) {
            if (!hash(read(source)).equals(intent.after())) throw new IOException("replacement-rollback-conflict", failure);
            write(source, before);
            clear();
            if (failure instanceof IOException io) throw io;
            throw failure;
        }
        // A failed cleanup is recovered against the committed proposal on the next sync.
        try { clear(); } catch (IOException ignored) { }
    }

    void recover(Map<String, KnowledgeProposal> proposals, java.util.Set<String> liveSources) throws IOException {
        Path transaction = store.resolve("document-transaction");
        KnowledgeService.rejectSymlinkParents(transaction);
        Path metadata = transaction.resolve("intent.json");
        KnowledgeService.rejectSymlinkParents(metadata);
        if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            clear();
            return;
        }
        if (Files.size(metadata) > 8192) throw new IOException("replacement-intent-limit");
        Intent intent = json.readValue(read(metadata), Intent.class);
        if (intent == null || !hex(intent.before()) || !hex(intent.after()) || !hex(intent.documentId())
                || intent.path() == null || !hash(intent.path().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(intent.documentId())) throw new IOException("replacement-intent-invalid");
        if (!liveSources.contains(intent.documentId())) {
            clear();
            return;
        }
        Path source = source(intent.documentId(), intent.path());
        String current = hash(read(source));
        KnowledgeProposal proposal = intent.proposalId() == null ? null : proposals.get(intent.proposalId());
        boolean committed = intent.proposalId() == null || (proposal != null
                && proposal.kind() == KnowledgeProposal.Kind.REPLACEMENT && proposal.state() == KnowledgeProposal.State.APPROVED
                && proposal.sourceDocumentId().equals(intent.documentId()) && proposal.sourceVersion().equals(intent.before())
                && proposal.attachments().size() == 1 && proposal.attachments().get(0).version().equals(intent.after()));
        String expected = committed ? intent.after() : intent.before();
        if (!current.equals(intent.before()) && !current.equals(intent.after())) throw new IOException("replacement-recovery-conflict");
        if (!current.equals(expected)) {
            byte[] chosen = committed ? read(transaction.resolve("next")) : read(history(intent.documentId(), intent.before()));
            if (!expected.equals(hash(chosen))) throw new IOException("replacement-recovery-corrupt");
            write(source, chosen);
        }
        clear();
    }

    java.util.Set<String> sourceIds() throws IOException {
        Path directory = store.resolve("document-history");
        KnowledgeService.rejectSymlinkParents(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return java.util.Set.of();
        try (var paths = Files.list(directory)) {
            var ids = new java.util.HashSet<String>();
            for (Path path : paths.toList()) {
                if (!hex(path.getFileName().toString())) throw new IOException("history-path-unknown");
                ids.add(path.getFileName().toString());
            }
            return java.util.Set.copyOf(ids);
        }
    }

    void purge(java.util.Set<String> ids) throws IOException {
        Path parent = store.resolve("document-history");
        KnowledgeService.rejectSymlinkParents(parent);
        for (String id : ids) {
            if (!hex(id)) throw new IOException("history-id");
            Path directory = parent.resolve(id);
            KnowledgeService.rejectSymlinkParents(directory);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("history-path-invalid");
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    KnowledgeService.rejectSymlinkParents(file);
                    if ((!hex(name) && !name.equals(".project") && !name.matches("\\.document-.*\\.tmp"))
                            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("history-path-unknown");
                    Files.delete(file);
                }
            }
            Files.delete(directory);
        }
    }

    boolean scopeMatches(String id, String project) throws IOException {
        Path directory = historyDirectory(id);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return true;
        Path scope = directory.resolve(".project");
        KnowledgeService.rejectSymlinkParents(scope);
        if (!Files.isRegularFile(scope, LinkOption.NOFOLLOW_LINKS) || Files.size(scope) > 128) throw new IOException("history-project-missing");
        return project.equals(new String(read(scope), java.nio.charset.StandardCharsets.UTF_8));
    }

    List<Version> list(String id, String filename) throws IOException {
        Path directory = historyDirectory(id);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(path -> hex(path.getFileName().toString())).map(path -> {
                try {
                    KnowledgeService.rejectSymlinkParents(path);
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("history-file-invalid");
                    return new Version(path.getFileName().toString(), filename, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().toString());
                } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
            }).sorted(Comparator.comparing(Version::createdAt).reversed()).toList();
        } catch (java.io.UncheckedIOException ex) { throw ex.getCause(); }
    }

    byte[] previous(String id, String version) throws IOException {
        byte[] bytes = read(history(id, version));
        if (!hash(bytes).equals(version)) throw new IOException("history-version-corrupt");
        return bytes;
    }

    private void backup(String id, String version, byte[] bytes) throws IOException {
        Path path = history(id, version);
        Files.createDirectories(path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!hash(read(path)).equals(version)) throw new IOException("history-version-corrupt");
        } else write(path, bytes);
    }

    private Path historyDirectory(String id) throws IOException {
        if (!hex(id)) throw new IOException("history-id");
        Path directory = store.resolve("document-history").resolve(id);
        KnowledgeService.rejectSymlinkParents(directory);
        return directory;
    }

    private Path history(String id, String version) throws IOException {
        if (!hex(version)) throw new IOException("history-version");
        Path path = historyDirectory(id).resolve(version);
        KnowledgeService.rejectSymlinkParents(path);
        return path;
    }

    private Path source(String id, String relative) throws IOException {
        if (relative == null || !hex(id) || !hash(relative.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(id)
                || relative.contains("\\") || relative.startsWith("/") || relative.contains("\u0000")) throw new IOException("history-source");
        Path source = root.resolve(relative).normalize();
        if (!source.startsWith(root) || !root.relativize(source).toString().replace('\\', '/').equals(relative)) throw new IOException("history-source");
        KnowledgeService.rejectSymlinkParents(source);
        return source;
    }

    private static byte[] read(Path path) throws IOException {
        KnowledgeService.rejectSymlinkParents(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > DocumentExtractor.MAX_BYTES) throw new IOException("history-file-invalid");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes((int) DocumentExtractor.MAX_BYTES + 1);
            if (bytes.length > DocumentExtractor.MAX_BYTES) throw new IOException("history-file-limit");
            return bytes;
        }
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        KnowledgeService.rejectSymlinkParents(path);
        Path temp = Files.createTempFile(path.getParent(), ".document-", ".tmp");
        try {
            Files.write(temp, bytes);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
            }
            KnowledgeService.rejectSymlinkParents(path);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private void clear() throws IOException {
        Path directory = store.resolve("document-transaction");
        KnowledgeService.rejectSymlinkParents(directory);
        KnowledgeService.rejectSymlinkParents(directory.resolve("next"));
        Files.deleteIfExists(directory.resolve("next"));
        Files.deleteIfExists(directory.resolve("intent.json"));
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) if (file.getFileName().toString().matches("\\.document-.*\\.tmp")) {
                    KnowledgeService.rejectSymlinkParents(file);
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("transaction-temp-invalid");
                    Files.delete(file);
                }
            }
        }
    }

    private static boolean hex(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
