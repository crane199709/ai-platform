package io.saasforge.ai.conversation.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public record CreateConversationRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull UUID modelId,
        UUID promptTemplateId,
        @Positive Integer promptVersion,
        Map<String, String> variables) {}
