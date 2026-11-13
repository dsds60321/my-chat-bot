package com.mychatbot.ai;

public record AiRequest(String question, String context) {
    @Override public String toString() { return "AiRequest[content omitted]"; }
}
