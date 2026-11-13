package com.mychatbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
class AiProviderConfiguration {
    @Bean
    WebClient aiWebClient(AiProperties properties) {
        return HttpAiProvider.client(properties);
    }

    @Bean
    AiProvider groq(AiProperties settings, ObjectMapper mapper, WebClient aiWebClient) {
        return new GroqAiProvider(settings, mapper, aiWebClient);
    }

    @Bean
    AiProvider codex(AiProperties settings, ObjectMapper mapper, WebClient aiWebClient) {
        return new HttpAiProvider("codex", settings.getCodex(), settings, mapper, aiWebClient);
    }

    @Bean
    AiProvider claude(AiProperties settings, ObjectMapper mapper, WebClient aiWebClient) {
        return new HttpAiProvider("claude", settings.getClaude(), settings, mapper, aiWebClient);
    }

    @Bean
    AiProvider gemini(AiProperties settings, ObjectMapper mapper, WebClient aiWebClient) {
        return new HttpAiProvider("gemini", settings.getGemini(), settings, mapper, aiWebClient);
    }

    @Bean
    AiProvider kimi(AiProperties settings, ObjectMapper mapper, WebClient aiWebClient) {
        return new HttpAiProvider("kimi", settings.getKimi(), settings, mapper, aiWebClient);
    }
}
