package io.saasforge.ai.prompt.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PromptVersionResponse(
        UUID templateId,
        int version,
        String status,
        String content,
        List<String> variables,
        UUID publishedBy,
        OffsetDateTime publishedAt,
        boolean current) {
    @Override
    public String toString() {
        return "PromptVersionResponse[templateId=" + templateId + ", version=" + version + "]";
    }
}
