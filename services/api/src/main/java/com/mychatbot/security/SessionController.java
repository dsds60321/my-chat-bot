package com.mychatbot.security;

import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    public record Session(String username, Set<String> projects, boolean admin, boolean canCreateProject) {}

    public static boolean canCreateProject(Authentication auth, String project) {
        return (project == null || AccessContext.from(auth).allowsPath(project)) && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("CREATE_PROJECT_ALL")
                || (project == null ? a.getAuthority().startsWith("CREATE_PROJECT_")
                                    : a.getAuthority().equals("CREATE_PROJECT_" + project)));
    }

    @GetMapping("/api/session")
    public ResponseEntity<Session> current(Authentication authentication) {
        AccessContext access = AccessContext.from(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Session(access.userId(), access.projects(), authentication.getAuthorities().stream()
                        .anyMatch(authority -> authority.getAuthority().equals("ROLE_KNOWLEDGE_ADMIN")), canCreateProject(authentication, null)));
    }
}
