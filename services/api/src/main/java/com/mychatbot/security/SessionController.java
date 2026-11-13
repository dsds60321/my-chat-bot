package com.mychatbot.security;

import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    public record Session(String username, Set<String> projects, boolean admin) {}

    @GetMapping("/api/session")
    public ResponseEntity<Session> current(Authentication authentication) {
        AccessContext access = AccessContext.from(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Session(access.userId(), access.projects(), authentication.getAuthorities().stream()
                        .anyMatch(authority -> authority.getAuthority().equals("ROLE_KNOWLEDGE_ADMIN"))));
    }
}
