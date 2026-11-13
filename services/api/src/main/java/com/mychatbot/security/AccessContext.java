package com.mychatbot.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;

public record AccessContext(String userId, Set<String> projects, boolean directPublish) {
    public AccessContext { projects = Set.copyOf(projects); }
    public AccessContext(String userId, Set<String> projects) { this(userId, projects, false); }

    public static AccessContext from(Authentication authentication) {
        return new AccessContext(authentication.getName(), authentication.getAuthorities().stream()
                .map(a -> a.getAuthority()).filter(a -> a.startsWith("PROJECT_"))
                .map(a -> a.substring("PROJECT_".length())).collect(Collectors.toUnmodifiableSet()),
                authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("KNOWLEDGE_DIRECT_PUBLISH")));
    }
}
