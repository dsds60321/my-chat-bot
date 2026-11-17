package com.mychatbot.security;

import com.mychatbot.knowledge.ProjectCatalog;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExcludedPathAccountTest {
    @Test void yamlBindingPreservesExclusionsAndDenyWinsOverAllProjectsAndCreation() {
        var properties = new java.util.HashMap<String, Object>();
        properties.put("pet.api.accounts[0].username", "jskim");
        properties.put("pet.api.accounts[0].password", "synthetic-password");
        properties.put("pet.api.accounts[0].grade", "admin");
        properties.put("pet.api.accounts[0].excluded-paths[0]", "user/ghkang");
        properties.put("pet.api.accounts[0].excluded-paths[1]", "paye2u");
        properties.put("pet.api.accounts[1].username", "legacy");
        properties.put("pet.api.accounts[1].password", "synthetic-password");
        properties.put("pet.api.accounts[1].grade", "member");
        var entries = new Binder(new MapConfigurationPropertySource(properties))
                .bind("pet.api.accounts", Bindable.listOf(ApiSecurity.Account.class)).orElseThrow(IllegalStateException::new);
        var catalog = mock(ProjectCatalog.class);
        when(catalog.projects()).thenReturn(Set.of("user", "paye2u", "syslink"));
        var accounts = new ApiSecurity(catalog).users(entries);
        assertThat(accounts.findByUsername("legacy").block().getAuthorities()).extracting(a -> a.getAuthority())
                .contains("PROJECT_user", "PROJECT_paye2u", "PROJECT_syslink").noneMatch(a -> a.startsWith("DENY_PATH_"));
        var user = accounts.findByUsername("jskim").block();
        var authentication = new UsernamePasswordAuthenticationToken(user, "", user.getAuthorities());
        var access = AccessContext.from(authentication);
        assertThat(access.excludedPaths()).containsExactlyInAnyOrder("user/ghkang", "paye2u");
        assertThat(access.projects()).containsExactlyInAnyOrder("user", "syslink");
        assertThat(access.allowsPath("user/ghkang/file.md")).isFalse();
        assertThat(access.allowsPath("user/ghkang2/file.md")).isTrue();
        assertThat(SessionController.canCreateProject(authentication, "paye2u")).isFalse();
        assertThat(SessionController.canCreateProject(authentication, "wayup")).isTrue();
    }

    @Test void malformedExclusionsFailConfigurationRatherThanSilentlyGrantingAccess() {
        var security = new ApiSecurity(mock(ProjectCatalog.class));
        for (String path : List.of("", "/user/ghkang", "../user", "user/../ghkang", "user//ghkang",
                "user/ghkang/", "user\\ghkang", "C:/user", "user/ghkang.", "user/ghkang ", "user/*")) {
            assertThatThrownBy(() -> security.users(List.of(new ApiSecurity.Account(
                    "employee", "synthetic-password", "member", List.of(), List.of(path)))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("excluded-paths");
        }
        assertThat(AccessContext.validExcludedPath("user/홍길동/업무 자료.md")).isTrue();
    }
}
