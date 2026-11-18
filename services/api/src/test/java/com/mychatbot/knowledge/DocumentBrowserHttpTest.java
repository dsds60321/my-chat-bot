package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.BodyInserters.fromMultipartData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-test-fixture.properties", "ai.provider=mock",
        "pet.api.accounts[0].username=curator", "pet.api.accounts[0].password=curator-test",
        "pet.api.accounts[0].grade=admin", "pet.api.accounts[0].projects[0]=common",
        "pet.api.accounts[1].username=editor", "pet.api.accounts[1].password=editor-test",
        "pet.api.accounts[1].grade=user", "pet.api.accounts[1].projects[0]=common",
        "pet.api.accounts[2].username=other", "pet.api.accounts[2].password=other-test",
        "pet.api.accounts[2].grade=user", "pet.api.accounts[2].projects[0]=notes"
})
@AutoConfigureWebTestClient
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentBrowserHttpTest {
    @TempDir static Path folder;
    @Autowired WebTestClient client;
    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) throws Exception {
        Path real = folder.toRealPath();
        Files.createDirectories(real.resolve("source/common"));
        Files.writeString(real.resolve("source/common/guide.md"), "# Guide\nORIGINAL_DOCUMENT");
        registry.add("knowledge.root", () -> real.resolve("source").toString());
        registry.add("knowledge.index-path", () -> real.resolve("index").toString());
        registry.add("knowledge.store-path", () -> real.resolve("store").toString());
    }
    WebTestClient account(String user) {
        return client.mutate().defaultHeaders(headers -> headers.setBasicAuth(user, user + "-test")).build();
    }
    @Test void httpBrowserReplacementAndAdminOnlyRestoreRespectAuthentication() throws Exception {
        String documents = "/api/knowledge/documents";
        client.get().uri(documents).exchange().expectStatus().isUnauthorized();
        account("other").get().uri(documents + "?folderPath=common").exchange().expectStatus().isNotFound();
        var doc = account("editor").get().uri(documents + "?folderPath=common").exchange().expectStatus().isOk()
                .expectBody(KnowledgeService.DocumentInfo[].class).returnResult().getResponseBody()[0];
        var parts = new MultipartBodyBuilder();
        parts.part("metadata", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "project", doc.project(), "sourceDocumentId", doc.documentId(), "sourceVersion", doc.version(),
                "title", "수정 파일", "consent", true, "folderPath", "common")));
        parts.part("file", new ByteArrayResource("# Guide\nUPDATED_DOCUMENT".getBytes()) {
            @Override public String getFilename() { return "edited.md"; }
        });
        var pending = account("editor").post().uri("/api/knowledge/replacements").body(fromMultipartData(parts.build()))
                .exchange().expectStatus().isOk().expectBody(KnowledgeProposal.class).returnResult().getResponseBody();
        assertThat(Files.readString(folder.resolve("source/common/guide.md"))).contains("ORIGINAL_DOCUMENT");
        account("curator").post().uri("/api/knowledge/admin/proposals/" + pending.id() + "/approve")
                .bodyValue(Map.of()).exchange().expectStatus().isOk();
        var changed = account("editor").get().uri(documents + "?folderPath=common").exchange().expectStatus().isOk()
                .expectBody(KnowledgeService.DocumentInfo[].class).returnResult().getResponseBody()[0];
        String versions = documents + "/" + doc.documentId() + "/versions";
        account("editor").get().uri(versions).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].version").isEqualTo(doc.version());
        account("other").get().uri(versions + "/" + doc.version()).exchange().expectStatus().isNotFound();
        account("editor").get().uri(versions + "/" + doc.version()).exchange().expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("ORIGINAL_DOCUMENT"));
        String restore = documents + "/" + doc.documentId() + "/restore";
        var request = Map.of("version", changed.version(), "restoreVersion", doc.version(), "consent", true);
        account("editor").post().uri(restore).bodyValue(request).exchange().expectStatus().isForbidden();
        account("curator").post().uri(restore).bodyValue(request).exchange().expectStatus().isOk();
        assertThat(Files.readString(folder.resolve("source/common/guide.md"))).contains("ORIGINAL_DOCUMENT");
    }
}
