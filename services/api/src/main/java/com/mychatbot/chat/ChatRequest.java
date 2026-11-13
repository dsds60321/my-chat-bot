package com.mychatbot.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatRequest(@NotBlank @Size(max = 4000) String question,
                          @Pattern(regexp = "auto|work|general") String mode) {
    @Override public String toString() { return "ChatRequest[content omitted]"; }
}
