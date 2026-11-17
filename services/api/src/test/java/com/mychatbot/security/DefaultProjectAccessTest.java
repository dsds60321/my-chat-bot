package com.mychatbot.security;

import com.mychatbot.chat.RequestLimiter;
import com.mychatbot.chat.SearchController;
import com.mychatbot.knowledge.KnowledgeSearch;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = {SearchController.class, SessionController.class}, properties = {"spring.config.location=classpath:/application-test-fixture.properties", "pet.api.knowledge.direct-publish-users=developer", "pet.api.password=temporary-test-password", "knowledge.root=build/test-fixtures/no-corpus"})
@Import({ApiSecurity.class, com.mychatbot.knowledge.ProjectCatalog.class, RequestLimiter.class})
class DefaultProjectAccessTest {
    @Autowired WebTestClient client;
    @MockitoBean KnowledgeSearch search;
    @MockitoBean com.mychatbot.knowledge.ProjectCatalog catalog;

    @Test void authenticatedAccountWithoutConfiguredProjectsSearchesAllSupportedProjects() {
        var projects = Set.of("e2u", "paye2u");
        when(catalog.projects()).thenReturn(projects);
        client.get().uri("/api/search?q=MCHT").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(search);

        when(search.search("MCHT", new AccessContext("developer", projects, true), 10)).thenReturn(List.of());
        client.get().uri("/api/search?q=MCHT").headers(h -> h.setBasicAuth("developer", "temporary-test-password"))
                .exchange().expectStatus().isOk().expectBody().json("[]");
        verify(search).search("MCHT", new AccessContext("developer", projects, true), 10);
        client.get().uri("/api/session").headers(h -> h.setBasicAuth("developer", "temporary-test-password"))
                .exchange().expectStatus().isOk().expectBody().json("""
                        {"username":"developer","projects":["e2u","paye2u"]}
                        """);
    }
}
