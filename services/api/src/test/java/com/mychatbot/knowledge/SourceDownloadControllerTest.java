package com.mychatbot.knowledge;

import com.mychatbot.chat.RequestLimiter;
import com.mychatbot.security.ApiSecurity;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = SourceDownloadController.class, properties = {"knowledge.root=build/test-fixtures/no-corpus",
        "pet.api.password=temporary-test-password", "pet.api.projects=paye2u"})
@Import({ApiSecurity.class, com.mychatbot.knowledge.ProjectCatalog.class, RequestLimiter.class})
class SourceDownloadControllerTest {
    @Autowired WebTestClient client;
    @MockitoBean KnowledgeService knowledge;

    @Test void returnsAuthenticatedAttachmentWithOnlyServerProjects() {
        String id = "1".repeat(64);
        String version = "2".repeat(64);
        String path = "/api/sources/" + id;
        byte[] bytes = "원본 자료".getBytes(StandardCharsets.UTF_8);
        when(knowledge.download(id, version, Set.of("paye2u")))
                .thenReturn(Optional.of(new KnowledgeService.SourceFile("업무 자료.md", bytes)));
        client.get().uri(path + "?version=" + version).exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(knowledge);

        client.mutate().baseUrl("http://127.0.0.1:9001").build().get()
                .uri(path + "?version=" + version + "&project=syslink")
                .headers(h -> {
                    h.setBasicAuth("developer", "temporary-test-password");
                    h.setOrigin("http://127.0.0.1:1420");
                })
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, value -> assertThat(value).contains("Content-Disposition"))
                .expectHeader().value(HttpHeaders.CONTENT_DISPOSITION, value -> {
                    var attachment = ContentDisposition.parse(value);
                    assertThat(attachment.isAttachment()).isTrue();
                    assertThat(attachment.getFilename()).isEqualTo("업무 자료.md");
                }).expectBody(byte[].class).value(body -> assertThat(body).isEqualTo(bytes));
        verify(knowledge).download(id, version, Set.of("paye2u"));

        client.get().uri(path).headers(h -> h.setBasicAuth("developer", "temporary-test-password"))
                .exchange().expectStatus().isNotFound().expectBody().isEmpty();
        client.get().uri(path + "?version=" + "3".repeat(64))
                .headers(h -> h.setBasicAuth("developer", "temporary-test-password"))
                .exchange().expectStatus().isNotFound().expectBody().isEmpty();
    }
}
