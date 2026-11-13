package com.mychatbot.ai;

public final class AiException extends RuntimeException {
    private final String code;
    private final Integer retryAfterSeconds;
    public AiException(String code, String safeMessage) { this(code, safeMessage, null); }
    public AiException(String code, String safeMessage, Integer retryAfterSeconds) {
        super(safeMessage);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public String code() { return code; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
}
