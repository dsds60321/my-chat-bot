package com.mychatbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClient;

public class GroqAiProvider extends HttpAiProvider {
    public GroqAiProvider(AiProperties properties, ObjectMapper mapper) {
        this(properties, mapper, client(properties));
    }

    GroqAiProvider(AiProperties properties, ObjectMapper mapper, WebClient client) {
        super("groq", properties.getGroq(), properties, mapper, client);
    }
}
