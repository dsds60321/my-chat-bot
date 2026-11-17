package com.mychatbot.security;

import com.mychatbot.knowledge.ProjectCatalog;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountGradeTest {
    @Test void accountArrayAdminAndUserOwnRolesAndOverrideLegacyDirectPublishSettings() {
        var catalog = mock(ProjectCatalog.class);
        when(catalog.projects()).thenReturn(Set.of("user"));
        var environment = new MockEnvironment().withProperty("pet.api.accounts[0].username", "developer")
                .withProperty("pet.api.accounts[0].password", "synthetic-password")
                .withProperty("pet.api.accounts[0].grade", "user")
                .withProperty("pet.api.accounts[1].username", "admin")
                .withProperty("pet.api.accounts[1].password", "different-synthetic-password")
                .withProperty("pet.api.accounts[1].grade", "admin");
        var security = new ApiSecurity(catalog);
        var accounts = security.configuredUsers(environment, "developer", "legacy-password", "", "legacy-admin", "legacy-admin-password", "", "developer");
        var user = accounts.findByUsername("developer").block();
        var admin = accounts.findByUsername("admin").block();
        assertThat(user.getAuthorities()).extracting(a -> a.getAuthority()).contains("PROJECT_user")
                .doesNotContain("ROLE_KNOWLEDGE_ADMIN", "KNOWLEDGE_DIRECT_PUBLISH", "CREATE_PROJECT_ALL");
        assertThat(admin.getAuthorities()).extracting(a -> a.getAuthority())
                .contains("ROLE_KNOWLEDGE_ADMIN", "KNOWLEDGE_DIRECT_PUBLISH", "CREATE_PROJECT_ALL");
        assertThat(accounts.findByUsername("legacy-admin").block()).isNull();
        var alias = security.users(List.of(new ApiSecurity.Account("old-member", "synthetic-password", "member", List.of())));
        var legacyMember = alias.findByUsername("old-member").block();
        assertThat(legacyMember.getAuthorities()).extracting(a -> a.getAuthority()).doesNotContain("ROLE_KNOWLEDGE_ADMIN", "KNOWLEDGE_DIRECT_PUBLISH");
    }
}
