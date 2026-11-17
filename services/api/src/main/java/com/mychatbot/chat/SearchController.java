package com.mychatbot.chat;

import com.mychatbot.ai.AiException;
import com.mychatbot.knowledge.KnowledgeSearch;
import com.mychatbot.knowledge.SearchHit;
import com.mychatbot.knowledge.SensitiveData;
import com.mychatbot.security.AccessContext;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class SearchController {
    private final KnowledgeSearch search;
    private final RequestLimiter limiter;
    public SearchController(KnowledgeSearch search, RequestLimiter limiter) { this.search = search; this.limiter = limiter; }
    @GetMapping("/api/search")
    public Mono<List<SearchHit>> search(@RequestParam String q, Authentication authentication) {
        if (q.isBlank() || q.length() > 4000) throw new AiException("INVALID_INPUT", "검색어는 1~4000자로 입력해 주세요.");
        if (!SensitiveData.guard(q)) throw new AiException("SENSITIVE_INPUT", "검색어에서 비밀정보를 제거해 주세요.");
        AccessContext access = AccessContext.from(authentication);
        RequestLimiter.Lease lease = limiter.acquire(access.userId());
        return Mono.fromCallable(() -> search.search(q, access, 10))
                .subscribeOn(Schedulers.boundedElastic()).doFinally(signal -> lease.close());
    }
}
