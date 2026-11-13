package com.mychatbot.ai;

import reactor.core.publisher.Flux;

public interface AiProvider {
    String id();
    Flux<String> stream(AiRequest request);
}
