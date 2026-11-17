package com.mychatbot.knowledge;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.mychatbot.security.AccessContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class ProposalStorageTest {
    @TempDir Path temporary;

    @Test void legacyProposalsAndAttachmentsSurviveIndexLossWithoutRevivingWithdrawnKnowledge() throws Exception {
        Path base = temporary.toRealPath();
        Path root = base.resolve("knowledge");
        Path index = base.resolve("index");
        Path store = base.resolve("knowledge-submissions");
        Files.createDirectories(root.resolve("e2u"));
        Path upload = base.resolve("manual.txt");
        Files.writeString(upload, "ATTACHED_LOOKUP 절차");
        var author = new AccessContext("writer", Set.of("e2u"), true);
        KnowledgeProposal active;
        KnowledgeProposal withdrawn;
        try (var service = new KnowledgeService(root, index)) {
            active = service.submit(request("ACTIVE_LOOKUP"), author,
                    List.of(new KnowledgeService.Upload(upload, "manual.txt", "절차")));
            withdrawn = service.submit(request("WITHDRAWN_LOOKUP"), author);
            service.withdrawProposal(withdrawn.id(), author.userId());
        }
        // Simulate the previous release: it had no generated publication copies or ownership metadata.
        var exported = new com.fasterxml.jackson.databind.ObjectMapper().readTree(store.resolve("published-files.json").toFile());
        for (var publication : exported) for (var file : publication.get("files")) Files.deleteIfExists(root.resolve(file.get("path").asText()));
        Files.delete(store.resolve("published-files.json"));
        Files.delete(store.resolve(".published-initialized"));
        if (Files.exists(store.resolve("published-history"))) {
            try (var paths = Files.walk(store.resolve("published-history"))) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        // The old release kept the canonical proposals and uploads inside its index directory.
        Files.move(store.resolve("proposals.json"), index.resolve("proposals.json"));
        Files.move(store.resolve("attachments"), index.resolve("attachments"));
        Files.delete(store.resolve(".initialized"));
        Files.delete(store);
        try (var migrated = new KnowledgeService(root, index)) {
            assertThat(migrated.status().healthy()).isTrue();
            assertThat(migrated.ownProposals(author.userId())).hasSize(2);
            var file = active.attachments().get(0);
            assertThat(migrated.proposalAttachment(active.id(), file.documentId(), file.version(), author, false)).isPresent();
            assertThat(Files.exists(store.resolve("proposals.json"))).isTrue();
            assertThat(Files.exists(index.resolve("proposals.json"))).isTrue();
        }
        try (var paths = Files.walk(index)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        try (var rebuilt = new KnowledgeService(root, index)) {
            assertThat(rebuilt.status().healthy()).isTrue();
            assertThat(rebuilt.search("ACTIVE_LOOKUP", author.projects(), 4)).isNotEmpty();
            assertThat(rebuilt.search("WITHDRAWN_LOOKUP", author.projects(), 4)).isEmpty();
            var file = active.attachments().get(0);
            assertThat(rebuilt.download(file.documentId(), file.version(), author.projects())).isPresent();
            assertThat(rebuilt.download(file.documentId(), file.version(), Set.of("other"))).isEmpty();
            assertThat(rebuilt.ownProposals(author.userId())).anyMatch(p -> p.id().equals(withdrawn.id())
                    && p.state() == KnowledgeProposal.State.WITHDRAWN);
        }
    }

    private KnowledgeProposalController.SubmitRequest request(String content) {
        return new KnowledgeProposalController.SubmitRequest("e2u", null, null, "절차", content, BooleanNode.TRUE);
    }
}
