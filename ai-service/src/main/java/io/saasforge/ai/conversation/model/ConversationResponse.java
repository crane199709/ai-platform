package io.saasforge.ai.conversation.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID ownerId,
        String title,
        UUID modelId,
        UUID activeTurnId,
        UUID promptTemplateId,
        Integer promptVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<MessageResponse> messages,
        List<TurnResponse> turns) {
    public record TurnResponse(
            UUID id,
            String status,
            UUID modelId,
            String errorCode,
            Long inputTokens,
            Long outputTokens,
            Long cachedInputTokens,
            boolean usageReported) {}

    public record MessageResponse(
            UUID id,
            UUID turnId,
            String role,
            String content,
            String toolCalls,
            String toolName,
            OffsetDateTime createdAt) {}
}
