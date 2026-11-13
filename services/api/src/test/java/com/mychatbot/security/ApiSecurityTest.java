package com.mychatbot.security;

import com.mychatbot.chat.*;
import com.mychatbot.knowledge.KnowledgeSearch;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;

@WebFluxTest(controllers = {ChatController.class, SearchController.class, SessionController.class}, properties = {
        "spring.main.web-application-type=reactive", "spring.jackson.deserialization.fail-on-unknown-properties=true", "pet.api.password=temporary-test-password", "pet.api.projects=paye2u", "pet.api.requests-per-minute=100",
        "pet.api.admin.user=reviewer", "pet.api.admin.password=temporary-review-password", "pet.api.admin.projects=syslink"})
@Import({ApiSecurity.class, ApiErrors.class, RequestLimiter.class})
class ApiSecurityTest {
    @Autowired WebTestClient client;
    @MockitoBean ChatService chat;
    @MockitoBean KnowledgeSearch search;
    @BeforeEach void setup() {
        client = client.mutate().baseUrl("http://127.0.0.1:8080").build();
        when(chat.stream(any(), any(), anyString())).thenAnswer(call -> Flux.just(
                new StreamEvent("delta", new StreamEvent.Delta("test")),
                new StreamEvent("done", new StreamEvent.Done(call.getArgument(2), "mock"))));
        when(search.search(anyString(), anySet(), anyInt())).thenReturn(List.of());
    }
    @Test void directPublishRequiresAnExactConfiguredAccount() {
        var accounts = new ApiSecurity().users("developer", "employee-test", "common",
                "reviewer", "admin-test", "common", "developer, *");
        StepVerifier.create(accounts.findByUsername("developer"))
                .assertNext(user -> assertThat(user.getAuthorities()).extracting(a -> a.getAuthority())
                        .contains("KNOWLEDGE_DIRECT_PUBLISH").doesNotContain("ROLE_KNOWLEDGE_ADMIN"))
                .verifyComplete();
        StepVerifier.create(accounts.findByUsername("reviewer"))
                .assertNext(user -> assertThat(user.getAuthorities()).extracting(a -> a.getAuthority())
                        .doesNotContain("KNOWLEDGE_DIRECT_PUBLISH"))
                .verifyComplete();
    }

    @Test void requiresAuthenticationAndRejectsNonEmployee() {
        client.get().uri("/api/search?q=test").exchange().expectStatus().isUnauthorized();
        client.mutateWith(mockUser("outsider").roles("OTHER")).get().uri("/api/search?q=test")
                .exchange().expectStatus().isForbidden();
    }
    @Test void basicAuthAndOnlyServerProjectGrantsReachSearch() {
        client.get().uri("/api/search?q=MCHT&projects=operations")
                .headers(h -> { h.setBasicAuth("developer", "temporary-test-password"); h.set("X-Projects", "operations"); })
                .exchange().expectStatus().isOk().expectBody().json("[]");
        verify(search).search(eq("MCHT"), eq(Set.of("paye2u")), eq(10));
    }
    @Test void administratorUsesSeparateCredentialsAndExplicitProjectGrants() {
        client.get().uri("/api/knowledge/admin/proposals")
                .headers(h -> h.setBasicAuth("developer", "temporary-test-password"))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/session").headers(h -> h.setBasicAuth("reviewer", "temporary-test-password"))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/session").headers(h -> h.setBasicAuth("reviewer", "temporary-review-password"))
                .exchange().expectStatus().isOk().expectBody()
                .json("{\"username\":\"reviewer\",\"projects\":[\"syslink\"],\"admin\":true}");
        StepVerifier.create(new ApiSecurity().users("developer", "employee-test", "", "reviewer", "employee-test", "syslink", "")
                .findByUsername("reviewer")).verifyComplete();
        StepVerifier.create(new ApiSecurity().users("developer", "employee-test", "", "reviewer", "admin-test", "", "")
                .findByUsername("reviewer"))
                .assertNext(user -> assertThat(user.getAuthorities()).extracting(a -> a.getAuthority()).containsExactlyInAnyOrder(
                        "ROLE_EMPLOYEE", "ROLE_KNOWLEDGE_ADMIN", "PROJECT_paye2u", "PROJECT_syslink", "PROJECT_operations", "PROJECT_notes", "PROJECT_common"))
                .verifyComplete();
        StepVerifier.create(new ApiSecurity().users("developer", "employee-test", "", "reviewer", "admin-test", "unknown,*", "")
                .findByUsername("reviewer")).verifyComplete();
    }
    @Test void sessionValidatesCredentialsAndReturnsOnlyServerIdentityAndGrants() {
        client.get().uri("/api/session").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/session").headers(h -> h.setBasicAuth("developer", "incorrect-test-password"))
                .exchange().expectStatus().isUnauthorized();
        client.mutateWith(mockUser("outsider").roles("OTHER")).get().uri("/api/session")
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/session").headers(h -> h.setBasicAuth("developer", "temporary-test-password"))
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().json("{\"username\":\"developer\",\"projects\":[\"paye2u\"]}")
                .jsonPath("$.password").doesNotExist().jsonPath("$.apiKey").doesNotExist();
        verifyNoInteractions(chat, search);
    }
    @Test void streamsWithBasicAuthAndRejectsBodyClaimsAndInvalidMode() {
        var response = client.post().uri("/api/chat/stream").headers(h -> h.setBasicAuth("developer", "temporary-test-password"))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("{\"question\":\"안녕\"}").exchange().expectStatus().isOk()
                .expectHeader().exists("X-Request-Id").expectBody(String.class).returnResult();
        String requestId = response.getResponseHeaders().getFirst("X-Request-Id");
        assertThat(response.getResponseBody()).contains("event:delta", "event:done", "\"requestId\":\"" + requestId + "\"");
        verify(chat).stream(eq(new ChatRequest("안녕", null)), any(), eq(requestId));
        client.post().uri("/api/chat/stream").headers(h -> h.setBasicAuth("developer", "temporary-test-password"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"question\":\"Java\",\"mode\":\"GENERAL\"}")
                .exchange().expectStatus().isBadRequest();
        client.post().uri("/api/chat/stream").headers(h -> h.setBasicAuth("developer", "temporary-test-password"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"question\":\"Java\",\"mode\":\"general\",\"projects\":[\"operations\"]}")
                .exchange().expectStatus().isBadRequest();
    }
    @Test void corsAllowsOnlyExactDesktopOrigins() {
        client.options().uri("/api/chat/stream").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST").exchange().expectStatus().isForbidden();
        client.options().uri("/api/chat/stream").header("Origin", "http://127.0.0.1:1420")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin", "http://127.0.0.1:1420");
    }
    @Test void absentPasswordDisablesAccountAndProjectsAreServerAuthorities() {
        StepVerifier.create(new ApiSecurity().users("developer", "", "paye2u", "", "", "", "").findByUsername("developer")).verifyComplete();
        StepVerifier.create(new ApiSecurity().users("developer", "temporary-test", "paye2u,../bad", "", "", "", "").findByUsername("developer"))
                .assertNext(user -> assertThat(user.getAuthorities()).extracting(a -> a.getAuthority()).containsExactlyInAnyOrder("ROLE_EMPLOYEE", "PROJECT_paye2u"))
                .verifyComplete();
    }

    @Test void blankProjectsDefaultToAllAndInvalidExplicitProjectsGrantNothing() {
        ApiSecurity security = new ApiSecurity();
        StepVerifier.create(security.users("developer", "temporary-test", " \t ", "", "", "", "").findByUsername("developer"))
                .assertNext(user -> assertThat(user.getAuthorities()).extracting(a -> a.getAuthority()).containsExactlyInAnyOrder(
                        "ROLE_EMPLOYEE", "PROJECT_paye2u", "PROJECT_syslink", "PROJECT_operations", "PROJECT_notes", "PROJECT_common"))
                .verifyComplete();
        StepVerifier.create(security.users("developer", "temporary-test", ",unknown,unassigned,*,../bad", "", "", "", "").findByUsername("developer"))
                .assertNext(user -> assertThat(user.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_EMPLOYEE"))
                .verifyComplete();
    }
}
