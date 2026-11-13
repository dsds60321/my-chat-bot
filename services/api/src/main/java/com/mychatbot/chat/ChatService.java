package com.mychatbot.chat;

import com.mychatbot.ai.AiException;
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
    private final AiProviderRouter router;
    private final KnowledgeSearch search;
    public ChatService(AiProviderRouter router, KnowledgeSearch search) { this.router = router; this.search = search; }

    public Flux<StreamEvent> stream(ChatRequest request, AccessContext access, String requestId) {
        return Mono.fromCallable(() -> {
            if (!SensitiveData.guard(request.question())) throw new AiException("SENSITIVE_INPUT", "비밀정보로 보이는 내용은 전송할 수 없어요. 제거한 뒤 질문해 주세요.");
            return access.projects().isEmpty()
                    ? List.<SearchHit>of() : search.search(request.question(), access.projects(), 4);
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(hits -> {
            StringBuilder context = new StringBuilder();
            List<SourceMetadata> included = new ArrayList<>();
            for (SearchHit hit : hits) {
                int remaining = 12000 - context.length();
                String sourceType = hit.approvedKnowledge() ? " (승인 지식)" : " (공식 문서)";
                String heading = "[" + (included.size() + 1) + "]" + sourceType + " " + SensitiveData.mask(hit.title()) + "\n";
                if (remaining <= heading.length() + 1) break;
                String text = SensitiveData.mask(hit.text());
                context.append(heading).append(text, 0, Math.min(remaining - heading.length() - 1, text.length())).append('\n');
                included.add(SourceMetadata.from(hit));
            }
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
        });
    }
}
