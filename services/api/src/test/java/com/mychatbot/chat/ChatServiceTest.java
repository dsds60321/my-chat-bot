package com.mychatbot.chat;

import com.mychatbot.ai.*;
import com.mychatbot.knowledge.*;
import com.mychatbot.security.AccessContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ChatServiceTest {
    private static SearchHit hit() { return new SearchHit("doc", "paye2u/table.md", "MCHT 정의", "paye2u", "database", "md", "1", "chunk", "MCHT", null, "MCHT 테이블에는 merchantId가 있어요.", 1, false); }
    private static AiProvider provider(AtomicInteger calls) {
        return provider("test", request -> { calls.incrementAndGet(); return Flux.just("답변"); });
    }
    private static AiProvider provider(String id, Function<AiRequest, Flux<String>> stream) {
        return new AiProvider() {
            public String id() { return id; }
            public Flux<String> stream(AiRequest request) { return stream.apply(request); }
        };
    }
    private static AiProviderRouter router(AiProvider provider) {
        return router(provider.id(), provider);
    }
    private static AiProviderRouter router(String order, AiProvider... providers) {
        AiProperties properties = new AiProperties(); properties.setProvider(order);
        return new AiProviderRouter(properties, List.of(providers));
    }

    private static SearchHit selectionHit(String id, String text, boolean approved) {
        return new SearchHit(id, "e2u/" + id + ".md", text, "e2u", "operations", "md", "b".repeat(64),
                id + ":1", "절차", 21, text, 1, approved);
    }

    @Test void logsBothLegsAndFallbackWithOneRequestId(CapturedOutput output) {
        AiProvider first = provider("groq", req -> Flux.error(new AiException("RATE_LIMITED", "private upstream")));
        AiProvider second = provider("claude", req -> Flux.just("답변 Bear", "er private-credential"));
        var service = new ChatService(router("groq,claude", first, second), (q, p, l) -> List.of(hit()));
        var events = service.stream(new ChatRequest("MCHT 질문", null),
                new AccessContext("u", Set.of("paye2u")), "trace-1")
                .contextWrite(context -> context.put(com.mychatbot.logging.ContentLog.REQUEST,
                        new com.mychatbot.logging.ContentLog.Request("trace-1", "2026-11-18T01:00:00Z", "logged-in-user",
                                "POST", "/api/chat/stream", "127.0.0.2"))).collectList().block();
        assertThat(events).extracting(StreamEvent::event).containsExactly("sources", "delta", "delta", "done");
        assertThat(output.getOut().lines().filter(line -> line.contains("requestId=trace-1"))).allSatisfy(line ->
                assertThat(line).contains("[trace-1]", "requestedAt=2026-11-18T01:00:00Z", "userId=logged-in-user",
                        "method=POST", "url=/api/chat/stream", "clientIp=127.0.0.2"));
        assertThat(output.getOut()).contains("user_request requestId=trace-1", "MCHT 질문",
                "ai_request requestId=trace-1 provider=groq", "MCHT 테이블",
                "ai_response requestId=trace-1 provider=groq outcome=error code=RATE_LIMITED",
                "ai_response requestId=trace-1 provider=claude outcome=done", "user_response requestId=trace-1",
                "[REDACTED]").doesNotContain("private-credential", "private upstream");
    }

    @Test void logsLocalAnswersRejectedSecretsAndPartialCancellation(CapturedOutput output) {
        var local = new ChatService(router(new MockAiProvider()), (q, p, l) -> List.of());
        local.stream(new ChatRequest("근거 없는 질문", null), new AccessContext("u", Set.of()), "local").blockLast();
        local.stream(new ChatRequest("password=private-input", null), new AccessContext("u", Set.of()), "rejected").blockLast();
        var partial = new ChatService(router(provider("groq", req -> Flux.just("부분 답변").concatWith(Flux.never()))),
                (q, p, l) -> List.of(hit()));
        StepVerifier.create(partial.stream(new ChatRequest("MCHT", null),
                new AccessContext("u", Set.of("paye2u")), "cancelled"))
                .expectNextCount(2).thenCancel().verify();
        assertThat(output.getOut()).contains("user_response requestId=local", "관련 내용을 찾지 못했어요",
                "user_response requestId=rejected provider=- outcome=error code=SENSITIVE_INPUT",
                "user_response requestId=cancelled provider=- outcome=cancel", "부분 답변",
                "ai_response requestId=cancelled provider=groq outcome=cancel")
                .doesNotContain("private-input", "ai_request requestId=local", "ai_request requestId=rejected");
    }

    @Test void multipleDocumentsRequireSelectionWithoutCallingAnyProvider() {
        AtomicInteger calls = new AtomicInteger();
        var first = selectionHit("1".repeat(64), "가맹점 이관", false);
        var second = selectionHit("2".repeat(64), "혼합 매입사 서비스", true);
        var service = new ChatService(router(provider(calls)), (q, p, l) -> List.of(first, first, second));
        var events = service.stream(new ChatRequest("앱 가맹점 이관", null),
                new AccessContext("u", Set.of("e2u")), "id").collectList().block();
        assertThat(calls).hasValue(0);
        assertThat(events).extracting(StreamEvent::event).containsExactly("sources", "candidates", "delta", "done");
        var candidates = (java.util.Map<?, ?>) events.get(1).data();
        assertThat((List<?>) candidates.get("items")).hasSize(2);
    }

    @Test void selectedDocumentAloneReachesProviderAndSources() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String id = "1".repeat(64);
        var service = new ChatService(router(provider("test", request -> {
            calls.incrementAndGet();
            assertThat(request.context()).contains("MERCHANT_TRANSFER").doesNotContain("ACQUIRER_MIX");
            return Flux.just("이관 안내");
        })), (q, projects, limit) -> {
            assertThat(projects).containsExactly("e2u");
            return List.of(selectionHit(id, "MERCHANT_TRANSFER", false),
                    selectionHit("2".repeat(64), "ACQUIRER_MIX", true));
        });
        var events = service.stream(new ChatRequest("가맹점 이관", null, id, "b".repeat(64)),
                new AccessContext("u", Set.of("e2u")), "id").collectList().block();
        assertThat(calls).hasValue(1);
        assertThat(events).extracting(StreamEvent::event).doesNotContain("candidates");
        assertThat(new ObjectMapper().writeValueAsString(events.get(0).data()))
                .contains(id).doesNotContain("2".repeat(64));
    }

    @Test void changedOrInaccessibleSelectionCannotFallBackToAnotherDocument() {
        AtomicInteger calls = new AtomicInteger();
        var service = new ChatService(router(provider(calls)), (q, p, l) ->
                List.of(selectionHit("2".repeat(64), "OTHER_DOCUMENT", false)));
        for (ChatRequest request : List.of(
                new ChatRequest("이관", null, "1".repeat(64), "b".repeat(64)),
                new ChatRequest("이관", null, "2".repeat(64), "c".repeat(64)),
                new ChatRequest("이관", null, "2".repeat(64), null))) {
            var events = service.stream(request, new AccessContext("u", Set.of("e2u")), "id").collectList().block();
            assertThat(events).singleElement().satisfies(event ->
                    assertThat(event.data()).isInstanceOfSatisfying(StreamEvent.Failure.class,
                            failure -> assertThat(failure.code()).isEqualTo("SOURCE_SELECTION_CHANGED")));
        }
        assertThat(calls).hasValue(0);
    }

    @Test void chunksFromOneDocumentCanAnswerWithoutAnExtraSelection() {
        AtomicInteger calls = new AtomicInteger();
        var hit = selectionHit("1".repeat(64), "이관 절차", false);
        var service = new ChatService(router(provider(calls)), (q, p, l) -> List.of(hit, hit));
        var events = service.stream(new ChatRequest("이관", null),
                new AccessContext("u", Set.of("e2u")), "id").collectList().block();
        assertThat(calls).hasValue(1);
        assertThat(events).extracting(StreamEvent::event).doesNotContain("candidates");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"auto", "work"})
    void emptyKnowledgeNeverCallsProvider(String mode) {
        AtomicInteger calls = new AtomicInteger();
        var service = new ChatService(router(provider(calls)), (q, p, l) -> List.of());
        var events = service.stream(new ChatRequest("서브페이 빌드 방법", mode),
                new AccessContext("u", Set.of("paye2u")), "id").collectList().block();
        assertThat(calls).hasValue(0);
        assertThat(events).extracting(StreamEvent::event).containsExactly("sources", "delta", "done");
        assertThat(events.get(1).data()).isInstanceOfSatisfying(StreamEvent.Delta.class,
                delta -> assertThat(delta.text()).contains("문서에서 관련 내용을 찾지 못", "학습시켜 주세요", "학습 자료 관리", "새 문서 작성", "제안 제출"));
    }

    @Test void workSearchUsesTrustedProjectsAndSourcesDoNotContainText() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatService service = new ChatService(router(provider(calls)), (q, projects, limit) -> {
            assertThat(projects).containsExactly("paye2u"); return List.of(hit());
        });
        var events = service.stream(new ChatRequest("MCHT 컬럼", null), new AccessContext("u", Set.of("paye2u")), "id").collectList().block();
        assertThat(calls).hasValue(1);
        String json = new ObjectMapper().writeValueAsString(events.get(0).data());
        assertThat(json).contains("MCHT 정의", "documentId").doesNotContain("merchantId", "\"text\"");
    }

    @Test void generalModeAlsoRequiresDocumentsAndSensitiveQuestionNeverReachesProvider() {
        AtomicInteger calls = new AtomicInteger();
        ChatService service = new ChatService(router(provider(calls)), (q, projects, limit) -> List.of(hit()));
        StepVerifier.create(service.stream(new ChatRequest("Java final 변수", "general"), new AccessContext("u", Set.of("paye2u")), "id"))
                .expectNextMatches(event -> event.event().equals("sources"))
                .expectNextMatches(event -> event.event().equals("delta"))
                .expectNextMatches(event -> event.event().equals("done")).verifyComplete();
        assertThat(calls).hasValue(1);
        StepVerifier.create(service.stream(new ChatRequest("password=do-not-send-me", "general"), new AccessContext("u", Set.of()), "id"))
                .expectNextMatches(event -> event.data() instanceof StreamEvent.Failure error && error.code().equals("SENSITIVE_INPUT")).verifyComplete();
        assertThat(calls).hasValue(1);
    }

    @Test void autoWithoutGrantsNeverCallsProvider() {
        AtomicInteger calls = new AtomicInteger();
        ChatService service = new ChatService(router(provider(calls)), (q, projects, limit) -> {
            throw new AssertionError("No grants means no search");
        });
        var events = service.stream(new ChatRequest("안녕", null), new AccessContext("u", Set.of()), "id").collectList().block();
        assertThat(events).extracting(StreamEvent::event).containsExactly("sources", "delta", "done");
        assertThat(calls).hasValue(0);
    }

    @Test void explicitMockRoutingAndUnknownProvider() {
        var request = new AiRequest("안녕", "");
        StepVerifier.create(router("mock", new MockAiProvider()).route(request, text -> text, id -> id))
                .expectNextCount(2).expectNext("mock").verifyComplete();
        assertThatThrownBy(() -> router("unknown").route(request, text -> text, id -> id))
                .isInstanceOf(AiException.class).hasMessage("사용할 AI 공급자를 설정해 주세요.");
    }

    @Test void fallsBackInConfiguredOrderBeforeAnyTextAndReportsActualProvider() {
        List<String> attempts = new ArrayList<>();
        AiProvider first = provider("groq", request -> {
            attempts.add("groq");
            return Flux.error(new AiException("RATE_LIMITED", "한도 초과", 30));
        });
        AiProvider second = provider("claude", request -> {
            attempts.add("claude");
            return Flux.error(new AiException("PROVIDER_NOT_CONFIGURED", "키 없음"));
        });
        AiProvider third = provider("gemini", request -> {
            attempts.add("gemini");
            assertThat(request.context()).contains("MCHT 테이블");
            return Flux.just("답변");
        });
        AiProvider unused = provider("kimi", request -> { throw new AssertionError("unexpected fallback"); });
        AtomicInteger searches = new AtomicInteger();
        ChatService service = new ChatService(router("groq, claude,gemini,kimi", unused, third, first, second),
                (q, projects, limit) -> { searches.incrementAndGet(); return List.of(hit()); });
        StepVerifier.create(service.stream(new ChatRequest("MCHT", null), new AccessContext("u", Set.of("paye2u")), "id"))
                .expectNextMatches(event -> event.event().equals("sources"))
                .expectNext(new StreamEvent("delta", new StreamEvent.Delta("답변")))
                .expectNext(new StreamEvent("done", new StreamEvent.Done("id", "gemini")))
                .expectComplete().verify(Duration.ofSeconds(5));
        assertThat(attempts).containsExactly("groq", "claude", "gemini");
        assertThat(searches).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"groq,groq", "groq,unknown", "groq,mock", "groq,"})
    void invalidOrderFailsBeforeAnyCall(String order) {
        AiProvider first = provider("groq", request -> { throw new AssertionError("unexpected call"); });
        ChatService service = new ChatService(router(order, first, new MockAiProvider()), (q, p, l) -> List.of(hit()));
        StepVerifier.create(service.stream(new ChatRequest("안녕", null), new AccessContext("u", Set.of("paye2u")), "id"))
                .expectNextMatches(event -> event.data() instanceof StreamEvent.Failure failure
                        && failure.code().equals("PROVIDER_NOT_CONFIGURED"))
                .expectComplete().verify(Duration.ofSeconds(5));
    }

    @Test void doesNotFallBackAfterTextOrForNonQuotaErrors() {
        var request = new AiRequest("안녕", "");
        AiProvider unused = provider("claude", req -> { throw new AssertionError("unexpected fallback"); });
        var partialFailure = new AiException("RATE_LIMITED", "한도 초과");
        AiProvider partial = provider("groq", req -> Flux.just("부분 답변").concatWith(Flux.error(partialFailure)));
        StepVerifier.create(router("groq,claude", partial, unused).route(request, text -> text, id -> id))
                .expectNext("부분 답변").expectErrorMatches(error -> error == partialFailure).verify(Duration.ofSeconds(5));
        var unavailable = new AiException("UPSTREAM_UNAVAILABLE", "연결 중단");
        AiProvider failed = provider("groq", req -> Flux.error(unavailable));
        StepVerifier.create(router("groq,claude", failed, unused).route(request, text -> text, id -> id))
                .expectErrorMatches(error -> error == unavailable).verify(Duration.ofSeconds(5));
    }

    @Test void successStopsTheChainAndTrailingMissingKeyPreservesQuotaFailure() {
        var request = new AiRequest("안녕", "");
        AiProvider first = provider("groq", req -> Flux.just("답변"));
        AiProvider unused = provider("claude", req -> { throw new AssertionError("unexpected fallback"); });
        StepVerifier.create(router("groq,claude", first, unused).route(request, text -> text, id -> id))
                .expectNext("답변", "groq").expectComplete().verify(Duration.ofSeconds(5));
        var limit = new AiException("RATE_LIMITED", "한도 초과", 30);
        AiProvider limited = provider("groq", req -> Flux.error(limit));
        AiProvider missingKey = provider("claude", req -> Flux.error(new AiException("PROVIDER_NOT_CONFIGURED", "키 없음")));
        StepVerifier.create(router("groq,claude", limited, missingKey).route(request, text -> text, id -> id))
                .expectErrorMatches(error -> error == limit).verify(Duration.ofSeconds(5));
    }

    @Test void cancellationWhileFirstProviderIsPendingDoesNotStartFallback() {
        AiProvider pending = provider("groq", req -> Flux.never());
        AiProvider unused = provider("claude", req -> { throw new AssertionError("unexpected fallback"); });
        StepVerifier.withVirtualTime(() -> router("groq,claude", pending, unused)
                .route(new AiRequest("안녕", ""), text -> text, id -> id))
                .expectSubscription().expectNoEvent(Duration.ofSeconds(1)).thenCancel().verify(Duration.ofSeconds(5));
    }

    @Test void oneDeadlineCoversAllAttempts() {
        AiProperties settings = new AiProperties();
        settings.setProvider("groq,claude");
        settings.setRequestTimeout(Duration.ofSeconds(5));
        AiProvider slowQuotaFailure = provider("groq", req -> Flux.just("unused").delayElements(Duration.ofSeconds(3))
                .thenMany(Flux.error(new AiException("RATE_LIMITED", "한도 초과"))));
        AiProvider slowSuccess = provider("claude", req -> Flux.just("늦은 답변").delayElements(Duration.ofSeconds(3)));
        var router = new AiProviderRouter(settings, List.of(slowQuotaFailure, slowSuccess));
        StepVerifier.withVirtualTime(() -> router.route(new AiRequest("안녕", ""), text -> text, id -> id))
                .expectSubscription().expectNoEvent(Duration.ofSeconds(4)).thenAwait(Duration.ofSeconds(1))
                .expectErrorMatches(error -> error instanceof AiException ai && ai.code().equals("UPSTREAM_TIMEOUT"))
                .verify(Duration.ofSeconds(5));
    }

    @Test void rateAndConcurrentRequestsAreBoundedAndLeaseReleaseIsIdempotent() {
        AtomicLong now = new AtomicLong();
        RequestLimiter limiter = new RequestLimiter(2, now::get);
        RequestLimiter.Lease first = limiter.acquire("u");
        assertThatThrownBy(() -> limiter.acquire("u")).isInstanceOfSatisfying(AiException.class,
                error -> assertThat(error.retryAfterSeconds()).isNull());
        first.close(); first.close();
        limiter.acquire("u").close();
        now.set(12_200_000_000L);
        assertThatThrownBy(() -> limiter.acquire("u")).isInstanceOfSatisfying(AiException.class, error -> {
            assertThat(error.code()).isEqualTo("LOCAL_RATE_LIMITED");
            assertThat(error.retryAfterSeconds()).isEqualTo(48);
        });
        limiter.acquire("different-user").close();
        now.set(60_000_000_000L);
        limiter.acquire("u").close();
    }
}
