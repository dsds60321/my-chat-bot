package com.mychatbot.knowledge;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.BodyInserters.fromMultipartData;

@SpringBootTest(properties = {
        "ai.provider=mock",
        "pet.api.accounts[0].username=curator", "pet.api.accounts[0].password=curator-test",
        "pet.api.accounts[0].grade=admin", "pet.api.accounts[0].projects[0]=payments-guide",
        "pet.api.accounts[1].username=editor", "pet.api.accounts[1].password=editor-test",
        "pet.api.accounts[1].grade=member", "pet.api.accounts[1].projects[0]=payments-guide",
        "pet.api.accounts[2].username=other", "pet.api.accounts[2].password=other-test",
        "pet.api.accounts[2].grade=member", "pet.api.accounts[2].projects[0]=notes",
        "pet.api.password=legacy-test"
})
@AutoConfigureWebTestClient
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class KnowledgeUploadTest {
    @TempDir static Path folder;
    @Autowired WebTestClient client;

    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) throws java.io.IOException {
        Path real = folder.toRealPath();
        registry.add("knowledge.root", () -> real.resolve("source").toString());
        registry.add("knowledge.index-path", () -> real.resolve("index").toString());
    }

    @Test void projectGrantsAndAttachmentApprovalApplyToUploadsSearchAndDownloads() throws Exception {
        String projects = "/api/knowledge/admin/projects";
        String proposals = "/api/knowledge/proposals";
        account("developer", "legacy-test").get().uri("/api/session").exchange().expectStatus().isUnauthorized();
        account("editor").post().uri(projects).bodyValue(Map.of("id", "payments-guide"))
                .exchange().expectStatus().isForbidden();
        account("curator").post().uri(projects).bodyValue(Map.of("id", "outside"))
                .exchange().expectStatus().isForbidden();
        account("curator").post().uri(projects).bodyValue(Map.of("id", "payments-guide"))
                .exchange().expectStatus().isOk();
        account("editor").get().uri("/api/session").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.projects[0]").isEqualTo("payments-guide");
        account("other").post().uri(proposals).body(fromMultipartData(upload()))
                .exchange().expectStatus().isNotFound();

        var submitted = account("editor").post().uri(proposals).body(fromMultipartData(upload()))
                .exchange().expectStatus().isOk().expectBody(KnowledgeProposal.class).returnResult().getResponseBody();
        assertThat(submitted.state()).isEqualTo(KnowledgeProposal.State.PENDING_APPROVAL);
        assertThat(submitted.attachments()).hasSize(2);
        var image = submitted.attachments().get(0);
        String query = "/api/search?q=취소입금확인";
        String source = "/api/sources/" + image.documentId() + "?version=" + image.version();
        String review = proposals + "/" + submitted.id() + "/attachments/" + image.documentId() + "?version=" + image.version();

        account("editor").get().uri(query).exchange().expectStatus().isOk().expectBody().json("[]");
        account("editor").get().uri(source).exchange().expectStatus().isNotFound();
        account("other").get().uri(review).exchange().expectStatus().isNotFound();
        account("curator").get().uri(review).exchange().expectStatus().isOk().expectBody(byte[].class)
                .value(bytes -> assertThat(bytes).isNotEmpty());
        account("curator").post().uri("/api/knowledge/admin/proposals/" + submitted.id() + "/approve")
                .bodyValue(Map.of()).exchange().expectStatus().isOk();
        account("editor").get().uri(query).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].attachments[0].filename").isEqualTo("취소 화면.png");
        account("editor").get().uri("/api/search?q=승인번호확인절차").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].section").value(section -> assertThat(section.toString()).contains("매뉴얼.txt"));
        account("other").get().uri(source).exchange().expectStatus().isNotFound();
        account("editor").post().uri("/api/chat/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("question", "취소입금확인")).exchange().expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains(image.documentId()));
        account("editor").get().uri(source).exchange().expectStatus().isOk();
        account("editor").post().uri(proposals + "/" + submitted.id() + "/withdraw")
                .bodyValue(Map.of()).exchange().expectStatus().isOk();
        account("editor").get().uri(source).exchange().expectStatus().isNotFound();

        account("curator").post().uri(proposals).body(fromMultipartData(upload())).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.state").isEqualTo("APPROVED");
    }

    private WebTestClient account(String username) { return account(username, username + "-test"); }

    private WebTestClient account(String username, String password) {
        return client.mutate().defaultHeaders(headers -> headers.setBasicAuth(username, password)).build();
    }

    private static org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> upload() throws Exception {
        var builder = new MultipartBodyBuilder();
        builder.part("metadata", """
                {"proposal":{"project":"payments-guide","title":"강제취소 방법","content":"거래 상세에서 강제취소 탭으로 이동합니다.","consent":true},
                 "descriptions":["취소입금확인 화면",""]}
                """);
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png);
        builder.part("files", new ByteArrayResource(png.toByteArray()), MediaType.IMAGE_PNG).filename("취소 화면.png");
        builder.part("files", new ByteArrayResource("승인번호확인절차 안내".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                MediaType.TEXT_PLAIN).filename("매뉴얼.txt");
        return builder.build();
    }
}
