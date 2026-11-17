package com.mychatbot.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mychatbot.ai.AiException;
import com.mychatbot.security.AccessContext;
import com.mychatbot.security.SessionController;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeFolders {
    public enum State { PENDING_APPROVAL, APPROVED, REJECTED }
    public record FolderRequest(String id, String path, String author, State state, String createdAt, String updatedAt) {}
    public record Snapshot(List<String> folders, List<FolderRequest> requests, boolean canCreateDirectly, boolean canCreateProject) {}
    private final Path root;
    private final Path store;
    private final Path index;
    private final ObjectMapper json = new ObjectMapper();
    private Map<String, FolderRequest> requests;

    public KnowledgeFolders(@Value("${knowledge.root:../../data/knowledge}") String root,
            @Value("${knowledge.store-path:}") String store, @Value("${knowledge.index-path:../../.local/knowledge-index}") String index) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.store = (store.isBlank() ? this.root.resolveSibling("knowledge-submissions") : Path.of(store)).toAbsolutePath().normalize();
        this.index = Path.of(index).toAbsolutePath().normalize();
    }

    public static boolean validPath(String path) {
        return AccessContext.validExcludedPath(path) && path.split("/").length <= 16
                && ProjectCatalog.validId(path.split("/")[0])
                && Arrays.stream(path.split("/")).noneMatch(part -> part.split("\\.", 2)[0].toLowerCase(Locale.ROOT)
                        .matches("con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³]"));
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_KNOWLEDGE_ADMIN"));
    }

    private boolean allowed(String path, Authentication auth) {
        AccessContext access = AccessContext.from(auth);
        return validPath(path) && access.allowsPath(path)
                && (path.contains("/") ? access.projects().contains(path.substring(0, path.indexOf('/')))
                        : access.projects().contains(path) || (admin(auth) && SessionController.canCreateProject(auth, path)));
    }

    public synchronized Snapshot list(Authentication auth) {
        load();
        AccessContext access = AccessContext.from(auth);
        List<String> folders = new ArrayList<>();
        try {
            KnowledgeService.rejectSymlinkParents(root);
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 17, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (directory.equals(root)) return FileVisitResult.CONTINUE;
                    String path = root.relativize(directory).toString().replace('\\', '/');
                    if (!validPath(path) || !access.allowsPath(path)
                            || !access.projects().contains(path.split("/")[0])) return FileVisitResult.SKIP_SUBTREE;
                    KnowledgeService.rejectSymlinkParents(directory);
                    if (folders.size() >= 10000) throw new IOException("folder-limit");
                    folders.add(path);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) { throw unavailable(); }
        Collections.sort(folders);
        var visible = requests.values().stream().filter(request -> access.allowsPath(request.path())
                && ((request.author().equals(access.userId()) && (request.path().indexOf('/') < 0 || allowed(request.path(), auth)))
                    || (admin(auth) && request.state() == State.PENDING_APPROVAL && allowed(request.path(), auth))))
                .sorted(Comparator.comparing(FolderRequest::createdAt).reversed()).toList();
        return new Snapshot(List.copyOf(folders), visible, admin(auth), admin(auth) && SessionController.canCreateProject(auth, null));
    }

    public synchronized FolderRequest submit(String parent, String name, Authentication auth) {
        load();
        if (parent == null || name == null || name.contains("/") || name.contains("\\")) throw invalid();
        String path = parent.isEmpty() ? name : parent + "/" + name;
        if (!validPath(path) || name.length() > 100) throw invalid();
        AccessContext access = AccessContext.from(auth);
        if (!access.allowsPath(path) || (!parent.isEmpty() && !allowed(parent, auth))) throw forbidden();
        if (admin(auth) && parent.isEmpty() && !SessionController.canCreateProject(auth, path)) throw forbidden();
        validateDestination(path);
        if (requests.values().stream().anyMatch(request -> request.path().equals(path) && request.state() == State.PENDING_APPROVAL)) {
            throw conflict();
        }
        if (requests.size() >= 10000) throw unavailable();
        String now = Instant.now().toString();
        FolderRequest request = new FolderRequest(UUID.randomUUID().toString(), path, access.userId(),
                admin(auth) ? State.APPROVED : State.PENDING_APPROVAL, now, now);
        if (admin(auth)) createAndSave(request); else save(request);
        return request;
    }

    public synchronized FolderRequest review(String id, boolean approve, Authentication auth) {
        load();
        if (!admin(auth)) throw forbidden();
        FolderRequest previous = requests.get(id);
        if (previous == null || !allowed(previous.path(), auth)) throw forbidden();
        if (previous.state() != State.PENDING_APPROVAL) throw conflict();
        if (approve) validateDestination(previous.path());
        FolderRequest next = new FolderRequest(previous.id(), previous.path(), previous.author(),
                approve ? State.APPROVED : State.REJECTED, previous.createdAt(), Instant.now().toString());
        if (approve) createAndSave(next); else save(next);
        return next;
    }

    private void validateDestination(String relative) {
        if (!validPath(relative)) throw invalid();
        Path path = root.resolve(relative);
        try {
            KnowledgeService.rejectSymlinkParents(path);
            if (!Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS)) throw conflict();
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw conflict();
        } catch (IOException ex) { throw forbidden(); }
    }

    private void createAndSave(FolderRequest next) {
        Path path = root.resolve(next.path());
        try { Files.createDirectory(path); }
        catch (FileAlreadyExistsException ex) { throw conflict(); }
        catch (IOException ex) { throw unavailable(); }
        try { save(next); }
        catch (RuntimeException failure) {
            try { Files.delete(path); } catch (IOException ex) { /* A nonempty folder is preserved if another writer used it. */ }
            throw failure;
        }
    }

    private void load() {
        if (requests != null) return;
        try {
            if (store.startsWith(root) || root.startsWith(store) || store.startsWith(index) || index.startsWith(store)) throw new IOException("folder-store-overlap");
            KnowledgeService.rejectSymlinkParents(root);
            KnowledgeService.rejectSymlinkParents(store);
            Files.createDirectories(store);
            Path file = store.resolve("folder-requests.json");
            KnowledgeService.rejectSymlinkParents(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(store.resolve(".folders-initialized"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("folder-store-missing");
                write("folder-requests.json", "[]");
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16 * 1024 * 1024) throw new IOException("folder-store-invalid");
            List<FolderRequest> records = json.readValue(file.toFile(), new TypeReference<List<FolderRequest>>() {});
            Map<String, FolderRequest> loaded = new LinkedHashMap<>();
            if (records == null) throw new IOException("folder-store-invalid");
            for (FolderRequest request : records) {
                if (request == null || !validPath(request.path()) || request.state() == null || request.author() == null
                        || !UUID.fromString(request.id()).toString().equals(request.id())
                        || loaded.put(request.id(), request) != null) throw new IOException("folder-store-invalid");
                Instant.parse(request.createdAt()); Instant.parse(request.updatedAt());
            }
            write(".folders-initialized", "1");
            requests = loaded;
        } catch (Exception ex) { throw unavailable(); }
    }

    private void save(FolderRequest next) {
        Map<String, FolderRequest> changed = new LinkedHashMap<>(requests);
        changed.put(next.id(), next);
        try {
            String snapshot = json.writeValueAsString(changed.values());
            if (snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024 * 1024) throw new IOException("folder-store-limit");
            write("folder-requests.json", snapshot);
        } catch (IOException ex) { throw unavailable(); }
        requests = changed;
    }

    private void write(String name, String value) throws IOException {
        Path file = store.resolve(name);
        KnowledgeService.rejectSymlinkParents(file);
        Path temporary = Files.createTempFile(store, ".folders-", ".tmp");
        try {
            Files.writeString(temporary, value);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static AiException invalid() { return new AiException("PROPOSAL_INVALID", "폴더 이름과 생성 위치를 확인해 주세요."); }
    private static AiException forbidden() { return new AiException("PROPOSAL_FORBIDDEN", "이 위치에 폴더를 만들거나 요청을 검토할 권한이 없어요."); }
    private static AiException conflict() { return new AiException("PROPOSAL_CONFLICT", "폴더가 이미 있거나 생성 위치 또는 요청 상태가 변경됐어요."); }
    private static AiException unavailable() { return new AiException("PROPOSAL_UNAVAILABLE", "폴더 목록이나 생성 요청을 저장소에서 처리할 수 없어요."); }
}
