package com.mychatbot.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;

public record AccessContext(String userId, Set<String> projects, boolean directPublish, Set<String> excludedPaths) {
    public AccessContext {
        projects = Set.copyOf(projects);
        excludedPaths = Set.copyOf(excludedPaths);
        if (excludedPaths.stream().anyMatch(path -> !validExcludedPath(path))) {
            throw new IllegalArgumentException("excluded-paths: 지식 루트 기준의 상대 경로를 확인해 주세요.");
        }
        Set<String> denied = excludedPaths;
        projects = projects.stream().filter(project -> !denied.contains(project)).collect(Collectors.toUnmodifiableSet());
    }
    public AccessContext(String userId, Set<String> projects) { this(userId, projects, false); }
    public AccessContext(String userId, Set<String> projects, boolean directPublish) {
        this(userId, projects, directPublish, Set.of());
    }

    public static boolean validExcludedPath(String path) {
        return path != null && path.length() <= 1024
                && path.matches("[\\p{L}\\p{N}_-][\\p{L}\\p{M}\\p{N} ._-]*(/[\\p{L}\\p{N}_-][\\p{L}\\p{M}\\p{N} ._-]*)*")
                && java.util.Arrays.stream(path.split("/")).noneMatch(part -> part.endsWith(" ") || part.endsWith("."));
    }

    public boolean allowsPath(String path) {
        return path != null && excludedPaths.stream().noneMatch(excluded -> path.equals(excluded) || path.startsWith(excluded + "/"));
    }

    public static AccessContext from(Authentication authentication) {
        return new AccessContext(authentication.getName(), authentication.getAuthorities().stream()
                .map(a -> a.getAuthority()).filter(a -> a.startsWith("PROJECT_"))
                .map(a -> a.substring("PROJECT_".length())).collect(Collectors.toUnmodifiableSet()),
                authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("KNOWLEDGE_DIRECT_PUBLISH")),
                authentication.getAuthorities().stream().map(a -> a.getAuthority()).filter(a -> a.startsWith("DENY_PATH_"))
                        .map(a -> a.substring("DENY_PATH_".length())).collect(Collectors.toUnmodifiableSet()));
    }
}
