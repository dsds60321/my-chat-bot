package com.mychatbot.chat;

public record StreamEvent(String event, Object data) {
    public record Delta(String text) {}
    public record Failure(String code, String message, Integer retryAfterSeconds) {}
    public record Done(String requestId, String provider) {}
}
