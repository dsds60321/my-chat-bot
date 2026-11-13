package com.mychatbot.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("ai")
public class AiProperties {
    private String provider = "groq";
    private final Endpoint groq = new Endpoint("https://api.groq.com/openai/v1", "openai/gpt-oss-20b");
    private final Endpoint codex = new Endpoint("https://api.openai.com/v1", "gpt-6-sol");
    private final Endpoint claude = new Endpoint("https://api.anthropic.com/v1", "claude-sonnet-4-6");
    private final Endpoint gemini = new Endpoint("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.5-flash");
    private final Endpoint kimi = new Endpoint("https://api.moonshot.ai/v1", "kimi-k3.1-pro");
    private Duration requestTimeout = Duration.ofSeconds(45);
    private Duration idleTimeout = Duration.ofSeconds(20);
    private int maxTokens = 4096;
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public Endpoint getGroq() { return groq; }
    public Endpoint getCodex() { return codex; }
    public Endpoint getClaude() { return claude; }
    public Endpoint getGemini() { return gemini; }
    public Endpoint getKimi() { return kimi; }
    int orderOf(String id) {
        return switch (id) {
            case "groq" -> groq.getOrder();
            case "codex" -> codex.getOrder();
            case "claude" -> claude.getOrder();
            case "gemini" -> gemini.getOrder();
            case "kimi" -> kimi.getOrder();
            default -> 100;
        };
    }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { requestTimeout = value; }
    public Duration getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(Duration value) { idleTimeout = value; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int value) { maxTokens = value; }

    // Deliberately no toString: configuration may contain an API key.
    public static class Endpoint {
        private String apiKey = "";
        private String baseUrl;
        private String model;
        private int order = 100;
        Endpoint(String baseUrl, String model) {
            this.baseUrl = baseUrl;
            this.model = model;
        }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { baseUrl = value; }
        public String getModel() { return model; }
        public void setModel(String value) { model = value; }
        public int getOrder() { return order; }
        public void setOrder(int value) { order = value; }
    }
}
