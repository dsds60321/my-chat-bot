package com.mychatbot.chat;

import com.mychatbot.ai.AiException;
import com.mychatbot.logging.ContentLog;
import com.mychatbot.ai.AiProviderRouter;
import com.mychatbot.ai.AiRequest;
import com.mychatbot.knowledge.KnowledgeSearch;
import com.mychatbot.knowledge.SearchHit;
import com.mychatbot.knowledge.SensitiveData;
import com.mychatbot.security.AccessContext;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class ChatService {
    private static final int MAX_CONTEXT = 12000;
    private static final String OMITTED = "\n[일부 검색 근거는 길이 한도로 생략됐습니다. 제공된 발췌에서 확인되지 않는 단계는 추측하지 마세요.]\n";
    private final AiProviderRouter router;
    private final KnowledgeSearch search;
    public ChatService(AiProviderRouter router, KnowledgeSearch search) { this.router = router; this.search = search; }

    public Flux<StreamEvent> stream(ChatRequest request, AccessContext access, String requestId) {
        return Flux.deferContextual(contextView -> {
            ContentLog.Request logRequest = contextView.getOrDefault(ContentLog.REQUEST,
                    new ContentLog.Request(requestId, java.time.Instant.now().toString(), access.userId(),
                            "POST", "/api/chat/stream", "-"));
            ContentLog.request("user", logRequest, "-", request.question());
            var response = new ContentLog.Response("user", logRequest, "-");
            return Mono.fromCallable(() -> {
                if (!SensitiveData.guard(request.question())) throw new AiException("SENSITIVE_INPUT", "비밀정보로 보이는 내용은 전송할 수 없어요. 제거한 뒤 질문해 주세요.");
                return access.projects().isEmpty()
                        ? List.<SearchHit>of() : search.search(request.question(), access, 20, request.documentId(), request.version());
            }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(hits -> {
                if ((request.documentId() == null) != (request.version() == null)) {
                    throw new AiException("SOURCE_SELECTION_CHANGED", "답변에 사용할 문서를 다시 선택해 주세요.");
                }
                if (request.documentId() != null) {
                    hits = hits.stream().filter(hit -> request.documentId().equals(hit.documentId())
                            && request.version().equals(hit.version())).toList();
                    if (hits.isEmpty()) {
                        throw new AiException("SOURCE_SELECTION_CHANGED", "선택한 문서가 변경되었거나 접근할 수 없어요. 원래 질문으로 다시 검색해 주세요.");
                    }
                } else {
                    var documents = new java.util.LinkedHashMap<String, SourceMetadata>();
                    for (SearchHit hit : hits) documents.putIfAbsent(hit.documentId(), SourceMetadata.from(hit));
                    if (documents.size() > 1) {
                        var candidates = documents.values().stream().limit(10).toList();
                        return Flux.just(new StreamEvent("sources", Map.of("items", List.of())),
                                new StreamEvent("candidates", Map.of("items", candidates)),
                                new StreamEvent("delta", new StreamEvent.Delta("관련 문서가 여러 개 있어요. 답변에 사용할 문서를 선택해 주세요. 아래 버튼을 누르거나 번호를 입력하면 선택한 문서만 기준으로 안내합니다.")),
                                new StreamEvent("done", new StreamEvent.Done(requestId, "selection")));
                    }
                }
                StringBuilder context = new StringBuilder();
                List<SourceMetadata> included = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                int omitted = 0;
                for (SearchHit hit : hits) {
                    String text = SensitiveData.mask(hit.text());
                    if (text.isBlank() || !seen.add(text.strip())) continue;
                    int remaining = MAX_CONTEXT - OMITTED.length() - context.length();
                    String sourceType = hit.approvedKnowledge() ? " (승인 지식)" : " (공식 문서)";
                    String heading = "[" + (included.size() + 1) + "]" + sourceType + " " + SensitiveData.mask(hit.title())
                            + " · " + SensitiveData.mask(hit.section()) + (hit.row() == null ? "" : " · 행 " + hit.row()) + "\n";
                    int budget = remaining - heading.length() - 1;
                    if (text.length() > budget) {
                        if (!included.isEmpty() || budget <= 0) { omitted++; continue; }
                        text = excerpt(text, budget);
                        omitted++;
                    }
                    context.append(heading).append(text).append('\n');
                    included.add(SourceMetadata.from(hit));
                }
                if (omitted > 0 && !included.isEmpty()) context.append(OMITTED);
                ContentLog.request("rag_context", logRequest, "-", "matchedChunks=" + hits.size()
                        + " includedChunks=" + included.size() + " omittedChunks=" + omitted + " contextChars=" + context.length());
                StreamEvent sources = new StreamEvent("sources", Map.of("items", included));
                if (included.isEmpty()) {
                    return Flux.just(sources, new StreamEvent("delta", new StreamEvent.Delta(
                            "현재 접근 가능한 업무 문서에서 관련 내용을 찾지 못했어요.\n\n관련 내용을 알고 계시다면 챗봇이 참고할 수 있도록 학습시켜 주세요. 상단의 ‘학습 자료 관리’ → ‘새 문서 작성’에서 내용을 작성하고 공유에 동의한 뒤 ‘제안 제출’을 눌러 주세요. 관리자 승인 후 검색에 반영되며, 직접 반영 권한이 있으면 바로 반영됩니다.")),
                            new StreamEvent("done", new StreamEvent.Done(requestId, "documents")));
                }
                return Flux.concat(Flux.just(sources), router.route(new AiRequest(request.question(), context.toString()),
                        text -> new StreamEvent("delta", new StreamEvent.Delta(text)),
                        provider -> new StreamEvent("done", new StreamEvent.Done(requestId, provider))));
            }).onErrorResume(error -> {
                AiException safe = error instanceof AiException ai ? ai : new AiException("INTERNAL_ERROR", "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.");
                return Flux.just(new StreamEvent("error", new StreamEvent.Failure(safe.code(), safe.getMessage(), safe.retryAfterSeconds())));
            }).doOnNext(event -> {
                if (event.data() instanceof StreamEvent.Delta delta) response.append(delta.text());
                else if (event.data() instanceof StreamEvent.Failure failure) {
                    response.append(failure.message());
                    response.finish("error", failure.code());
                } else if (event.data() instanceof StreamEvent.Done) response.finish("done", "-");
            }).doFinally(signal -> response.finish(signal.toString(), "-"))
                    .contextWrite(context -> context.put(ContentLog.REQUEST, logRequest));
        });
    }
    private static String excerpt(String text, int budget) {
        int boundary = text.lastIndexOf("\n\n", budget);
        if (boundary < budget / 2) boundary = text.lastIndexOf('\n', budget);
        if (boundary < budget / 2) boundary = budget;
        if (boundary > 0 && boundary < text.length() && Character.isHighSurrogate(text.charAt(boundary - 1))) boundary--;
        return text.substring(0, boundary);
    }

}
