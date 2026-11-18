package com.mychatbot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import com.mychatbot.ai.*;
import com.mychatbot.knowledge.*;
import com.mychatbot.security.AccessContext;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class TemplateDownloadTest {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> query = new AtomicReference<>();
    ChatService service(List<SearchHit> hits) {
        var properties = new AiProperties(); properties.setProvider("mock");
        AiProvider provider = new AiProvider() {
            public String id() { return "mock"; }
            public Flux<String> stream(AiRequest request) { calls.incrementAndGet(); return Flux.just("설명 답변"); }
        };
        KnowledgeSearch search = (question, projects, limit) -> { query.set(question); return projects.contains("common") ? hits : List.of(); };
        return new ChatService(new AiProviderRouter(properties, List.of(provider)), search);
    }
    SearchHit hit(String id) {
        var attachment = new KnowledgeProposal.Attachment("c".repeat(64), "d".repeat(64), "등록양식.xlsx", "입력 양식", false);
        return new SearchHit(id.repeat(64), "common/guide.md", "등록 양식", "common", "uncategorized", "md", "b".repeat(64), "chunk-"+id, "등록 양식", 1,
                "등록 양식과 안내 내용", 1, true, List.of(attachment));
    }
    @Test void explicitDownloadReturnsRegisteredAttachmentsWithoutAiOrDocumentSelection() {
        var events = service(List.of(hit("a"), hit("e"))).stream(new ChatRequest("앱 ID 대량등록 양식 다운로드해줘", "work"),
                new AccessContext("u", Set.of("common")), "download").collectList().block();
        assertThat(calls).hasValue(0);
        assertThat(query.get()).isEqualTo("앱 ID 대량등록 양식");
        assertThat(events).noneMatch(event -> event.event().equals("candidates"));
        @SuppressWarnings("unchecked") var sources = (List<SourceMetadata>) ((Map<?,?>) events.get(0).data()).get("items");
        assertThat(sources).hasSize(2).allSatisfy(source -> assertThat(source.attachments()).extracting(KnowledgeProposal.Attachment::filename).containsExactly("등록양식.xlsx"));
        assertThat(events.get(events.size()-1).data()).isEqualTo(new StreamEvent.Done("download", "documents"));
    }
    @Test void normalQuestionsStillUseAiAndLiteralFilenamesRemainIntact() {
        service(List.of(hit("a"))).stream(new ChatRequest("등록 절차 알려줘", "work"),new AccessContext("u",Set.of("common")),"normal").collectList().block();
        assertThat(calls).hasValue(1);
        service(List.of(hit("a"))).stream(new ChatRequest("다운로드양식.xlsx 다운로드해줘", "work"),new AccessContext("u",Set.of("common")),"file").collectList().block();
        assertThat(query.get()).isEqualTo("다운로드양식.xlsx"); assertThat(calls).hasValue(1);
        service(List.of(hit("a"))).stream(new ChatRequest("다운로드양식.xlsx 내용 알려줘", "work"),new AccessContext("u",Set.of("common")),"filename-content").collectList().block();
        assertThat(calls).hasValue(2);
    }
    @Test void missingOrUnauthorizedFileNeverProducesInventedDownloadOrAiCall() {
        var events=service(List.of(hit("a"))).stream(new ChatRequest("등록 양식 다운로드", "work"),new AccessContext("u",Set.of("other")),"missing").collectList().block();
        assertThat(calls).hasValue(0);
        @SuppressWarnings("unchecked") var sources=(List<SourceMetadata>)((Map<?,?>)events.get(0).data()).get("items");
        assertThat(sources).isEmpty();
    }
}
