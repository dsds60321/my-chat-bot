package com.mychatbot.knowledge;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import com.mychatbot.ai.AiException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProjectCatalog {
    private static final Pattern PROJECT_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private final Path root;

    public ProjectCatalog(@Value("${knowledge.root:../../data/knowledge}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public static boolean validId(String id) {
        return id != null && !id.equals("unassigned") && PROJECT_ID.matcher(id).matches()
                && !id.matches("con|prn|aux|nul|com[0-9]|lpt[0-9]");
    }

    public void create(String id) {
        if (!validId(id)) throw new AiException("PROPOSAL_INVALID", "프로젝트 ID는 영문 소문자, 숫자, -, _로 입력해 주세요.");
        Path path = root.resolve(id);
        try {
            KnowledgeService.rejectSymlinkParents(root);
            Files.createDirectory(path);
        } catch (FileAlreadyExistsException ex) {
            throw new AiException("PROPOSAL_CONFLICT", "이미 존재하는 프로젝트예요.");
        } catch (IOException ex) {
            throw new AiException("PROPOSAL_UNAVAILABLE", "프로젝트 폴더를 만들 수 없어요.");
        }
    }

    public Set<String> projects() {
        Set<String> projects = new HashSet<>();
        try {
            KnowledgeService.rejectSymlinkParents(root);
            try (var children = Files.list(root)) {
                children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .map(path -> path.getFileName().toString())
                        .filter(ProjectCatalog::validId)
                        .forEach(projects::add);
            }
        } catch (IOException | java.io.UncheckedIOException ignored) {
            return Set.of();
        }
        return Set.copyOf(projects);
    }
}
