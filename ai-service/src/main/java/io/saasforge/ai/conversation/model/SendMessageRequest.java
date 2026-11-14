package io.saasforge.ai.conversation.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SendMessageRequest(
        @NotBlank @Size(max = 128000) String text,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String idempotencyKey,
        UUID modelId) {
    @Override
    public String toString() {
        return "SendMessageRequest[redacted]";
    }
}
