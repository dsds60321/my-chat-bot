package com.mychatbot.ai;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class MockAiProvider implements AiProvider {
    public String id() { return "mock"; }
    public Flux<String> stream(AiRequest request) {
        return Flux.just("[서버 Mock] ", request.context().isBlank()
                ? "안녕하세요! 질문을 받았어요. 서버 Mock 모드에서 연결을 확인했습니다."
                : "접근 가능한 업무 문서를 찾았어요. 아래 출처의 원문을 확인해 주세요. 실제 AI 분석은 실행하지 않았어요.");
    }
}
