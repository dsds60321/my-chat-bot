package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.mychatbot.security.AccessContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedDocumentBrowserTest {
    @TempDir Path temp;
    final AccessContext publisher = new AccessContext("admin", Set.of("user"), true);
    final AccessContext reader = new AccessContext("reader", Set.of("user"));
    Path root() throws Exception { return temp.toRealPath().resolve("source"); }
    Path store() throws Exception { return temp.toRealPath().resolve("store"); }
    KnowledgeService open() throws Exception { return new KnowledgeService(root(), temp.toRealPath().resolve("index"), store()); }
    KnowledgeProposal publish(KnowledgeService knowledge) {
        return knowledge.submit(new KnowledgeProposalController.SubmitRequest("user", null, null, "업무 안내",
                "MANAGED_BROWSER_CONTENT 확인 절차", BooleanNode.TRUE, "user/ghkang"), publisher);
    }

    @Test void unknownPublicationOwnershipDeniesPathChecksUntilLedgerLoads() throws Exception {
        var publication = new PublishedKnowledge(root(), store());
        String pendingId = "f1e8cdf2-3408-4f69-aa15-7c3096196689";
        assertThat(publication.pathsAllowed(pendingId, path -> true)).isFalse();
        publication.load();
        assertThat(publication.pathsAllowed(pendingId, path -> true)).isTrue();
    }

    @Test void approvedNewListsActualFolderOnceAndReturnedPhysicalIdentitySupportsPreviewAndDownloadAfterRestart() throws Exception {
        Files.createDirectories(root().resolve("user/ghkang"));
        KnowledgeService.DocumentInfo listed;
        try (var knowledge = open()) {
            publish(knowledge);
            var documents = knowledge.documents("user/ghkang", "", reader);
            assertThat(documents).hasSize(1);
            listed = documents.get(0);
            assertThat(listed.path()).startsWith("user/ghkang/업무 안내--").endsWith(".md");
            assertThat(listed.replaceable()).isTrue();
            assertThat(root().resolve(listed.path())).exists();
            assertThat(knowledge.documents("", "", reader)).containsExactly(listed);
            assertThat(knowledge.documents("user", "업무 안내", reader)).containsExactly(listed);
            assertThat(knowledge.documents("user", "", reader)).isEmpty();
            assertThat(knowledge.documents("user/ghkang", "not-present", reader)).isEmpty();
            var hit = knowledge.search("MANAGED_BROWSER_CONTENT", reader, 4);
            assertThat(hit).hasSize(1);
            assertThat(hit.get(0).documentId()).isEqualTo(listed.documentId());
            assertThat(hit.get(0).version()).isEqualTo(listed.version());
            var downloaded = knowledge.download(listed.documentId(), listed.version(), reader).orElseThrow();
            assertThat(downloaded.filename()).isEqualTo(Path.of(listed.path()).getFileName().toString());
            assertThat(new String(downloaded.bytes(), StandardCharsets.UTF_8)).contains("MANAGED_BROWSER_CONTENT");
            var preview = new DocumentPreviewService(knowledge, "unused", 1).preview(listed.documentId(), listed.version(), reader).orElseThrow();
            assertThat(preview.kind()).isEqualTo("text");
            assertThat(preview.text()).contains("MANAGED_BROWSER_CONTENT");
        }
        try (var knowledge = open()) {
            assertThat(knowledge.documents("user/ghkang", "", reader)).containsExactly(listed);
            assertThat(knowledge.search("MANAGED_BROWSER_CONTENT", reader, 4)).hasSize(1);
        }
    }

    @Test void managedPathsRespectProjectFolderAndExactFileExclusionsAndDisappearOnWithdrawalOrConflict() throws Exception {
        Files.createDirectories(root().resolve("user/ghkang"));
        try (var knowledge = open()) {
            var proposal = publish(knowledge);
            var listed = knowledge.documents("user/ghkang", "", reader).get(0);
            var wrongProject = new AccessContext("outside", Set.of("common"));
            var blockedFolder = new AccessContext("limited", Set.of("user"), false, Set.of("user/ghkang"));
            var blockedFile = new AccessContext("limited", Set.of("user"), false, Set.of(listed.path()));
            for (AccessContext denied : List.of(wrongProject, blockedFolder, blockedFile)) {
                assertThat(knowledge.documents("", "", denied)).isEmpty();
                assertThat(knowledge.download(listed.documentId(), listed.version(), denied)).isEmpty();
                assertThat(new DocumentPreviewService(knowledge, "unused", 1).preview(listed.documentId(), listed.version(), denied)).isEmpty();
                assertThat(knowledge.search("MANAGED_BROWSER_CONTENT", denied, 4)).isEmpty();
            }
            Path body = root().resolve(listed.path());
            byte[] original = Files.readAllBytes(body);
            Files.writeString(body, "external conflict");
            var edited = knowledge.documents("user/ghkang", "", reader);
            assertThat(edited).hasSize(1);
            assertThat(edited.get(0).version()).isNotEqualTo(listed.version());
            assertThat(knowledge.download(listed.documentId(), listed.version(), reader)).isEmpty();
            Files.write(body, original);
            assertThat(knowledge.documents("user/ghkang", "", reader)).hasSize(1);
            Path outside = temp.resolve("outside.md"); Files.writeString(outside, "KEEP_OUTSIDE");
            Files.delete(body); Files.createSymbolicLink(body, outside);
            assertThat(knowledge.documents("user/ghkang", "", reader)).isEmpty();
            assertThat(Files.readString(outside)).isEqualTo("KEEP_OUTSIDE");
            Files.delete(body); Files.write(body, original);
            knowledge.withdrawProposal(proposal.id(), publisher);
            assertThat(knowledge.documents("user/ghkang", "", reader)).isEmpty();
            assertThat(knowledge.download(listed.documentId(), listed.version(), reader)).isEmpty();
        }
    }

    @Test void excludingGeneratedAttachmentPathDeniesWholeAggregatedProposalOnEveryReadRoute() throws Exception {
        Files.createDirectories(root().resolve("user/ghkang"));
        Path upload = temp.resolve("attachment.md"); Files.writeString(upload, "MANAGED_ATTACHMENT_CONTENT");
        try (var knowledge = open()) {
            var proposal = knowledge.submit(new KnowledgeProposalController.SubmitRequest("user", null, null, "첨부 문서",
                    "MANAGED_PARENT_CONTENT", BooleanNode.TRUE, "user/ghkang"), publisher,
                    List.of(new KnowledgeService.Upload(upload, "attachment.md", "업무 첨부")));
            var document = knowledge.documents("user/ghkang", "", reader).get(0);
            var attachment = proposal.attachments().get(0);
            String excludedPath = null;
            var publications = new ObjectMapper().readTree(store().resolve("published-files.json").toFile());
            for (var publication : publications) if (publication.get("id").asText().equals(proposal.id())) {
                for (var file : publication.get("files")) if (file.get("path").asText().contains("/attachments/")) excludedPath = file.get("path").asText();
            }
            assertThat(excludedPath).isNotNull();
            var restricted = new AccessContext(publisher.userId(), Set.of("user"), false, Set.of(excludedPath));
            assertThat(knowledge.search("MANAGED_ATTACHMENT_CONTENT", reader, 4)).hasSize(1);
            assertThat(knowledge.documents("user/ghkang", "", restricted)).isEmpty();
            assertThat(knowledge.search("MANAGED_PARENT_CONTENT", restricted, 4)).isEmpty();
            assertThat(knowledge.search("MANAGED_ATTACHMENT_CONTENT", restricted, 4)).isEmpty();
            assertThat(knowledge.download(document.documentId(), document.version(), restricted)).isEmpty();
            assertThat(knowledge.download(attachment.documentId(), attachment.version(), restricted)).isEmpty();
            assertThat(new DocumentPreviewService(knowledge, "unused", 1).preview(document.documentId(), document.version(), restricted)).isEmpty();
            assertThat(knowledge.proposalAttachment(proposal.id(), attachment.documentId(), attachment.version(), restricted, false)).isEmpty();
            assertThat(knowledge.ownProposals(restricted)).isEmpty();
        }
    }

    @Test void activeAmendmentUsesPublishedPathButStillRequiresUnderlyingSourceAccess() throws Exception {
        Path source = root().resolve("user/ghkang/original.md"); Files.createDirectories(source.getParent());
        Files.writeString(source, "# Original\nORIGINAL_BROWSER_CONTENT");
        try (var knowledge = open()) {
            var original = knowledge.documents("user/ghkang", "", publisher).get(0);
            knowledge.submit(new KnowledgeProposalController.SubmitRequest("user", original.documentId(), original.version(),
                    "보완 안내", "AMENDMENT_BROWSER_CONTENT 확인", BooleanNode.TRUE, "user/ghkang"), publisher);
            var documents = knowledge.documents("user/ghkang", "", reader);
            assertThat(documents).hasSize(2);
            var amendment = documents.stream().filter(document -> !document.documentId().equals(original.documentId())).findFirst().orElseThrow();
            var restricted = new AccessContext("limited", Set.of("user"), false, Set.of(original.path()));
            assertThat(knowledge.documents("user/ghkang", "", restricted)).isEmpty();
            assertThat(knowledge.download(amendment.documentId(), amendment.version(), restricted)).isEmpty();
            assertThat(knowledge.search("AMENDMENT_BROWSER_CONTENT", reader, 4)).hasSize(1);
            Files.delete(source);
            assertThat(knowledge.documents("user/ghkang", "", reader)).isEmpty();
        }
    }
}
