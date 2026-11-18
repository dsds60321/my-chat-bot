package com.mychatbot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import com.mychatbot.ai.*;
import com.mychatbot.knowledge.*;
import com.mychatbot.security.AccessContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class EvidenceContextTest {
    private SearchHit hit(String chunk, String text) {
        return new SearchHit("a".repeat(64), "common/guide.md", "업무 안내", "common", "operations", "md",
                "b".repeat(64), chunk, "처리 절차", 4, text, 1, false);
    }
    private ChatService service(List<SearchHit> hits, AtomicReference<AiRequest> sent) {
        AiProvider provider = new AiProvider() {
            public String id() { return "test"; }
            public Flux<String> stream(AiRequest request) { sent.set(request); return Flux.just("검증 답변"); }
        };
        AiProperties properties = new AiProperties(); properties.setProvider("test");
        return new ChatService(new AiProviderRouter(properties, List.of(provider)), (question, projects, limit) -> hits);
    }
    private void ask(ChatService service) {
        service.stream(new ChatRequest("처리 절차", null), new AccessContext("u", Set.of("common")), "context-test").blockLast();
    }
    @Test void duplicateEvidenceIsSentOnceAndCompleteChunksFitWithoutMidStepCutoff() {
        AtomicReference<AiRequest> sent = new AtomicReference<>();
        String first = "1. FIRST_STEP\n2. VERIFY_STEP\n" + "설명".repeat(2600);
        String second = "SECOND_PROCEDURE " + "검증".repeat(4000);
        ask(service(List.of(hit("one", first), hit("duplicate", first), hit("two", second), hit("three", "SMALL_EVIDENCE_COMPLETE")), sent));
        assertThat(sent.get().context()).hasSizeLessThanOrEqualTo(12000).contains(first, "SMALL_EVIDENCE_COMPLETE", "일부 검색 근거")
                .doesNotContain("SECOND_PROCEDURE");
        assertThat(sent.get().context().indexOf("FIRST_STEP")).isEqualTo(sent.get().context().lastIndexOf("FIRST_STEP"));
    }
    @Test void dedupDoesNotCollapseMeaningfulWhitespaceInsideCode() {
        AtomicReference<AiRequest> sent = new AtomicReference<>();
        ask(service(List.of(hit("one", "SELECT 'a b';"), hit("two", "SELECT 'a  b';")), sent));
        assertThat(sent.get().context()).contains("SELECT 'a b';", "SELECT 'a  b';");
    }
    @Test void oversizedFirstEvidenceIsExplicitlyPartialInsteadOfPretendingNoEvidenceExists() {
        AtomicReference<AiRequest> sent = new AtomicReference<>();
        String text = "1. START_EVIDENCE\n\n" + "설명\n".repeat(5000) + "2. FINAL_STEP";
        var events = service(List.of(hit("large", text)), sent).stream(new ChatRequest("처리 절차", null),
                new AccessContext("u", Set.of("common")), "context-test").collectList().block();
        assertThat(sent.get().context()).hasSizeLessThanOrEqualTo(12000).contains("START_EVIDENCE", "일부 검색 근거는 길이 한도로 생략")
                .doesNotContain("FINAL_STEP");
        assertThat(events).extracting(StreamEvent::event).containsExactly("sources", "delta", "done");
    }
}
