package io.saasforge.ai.prompt.model;

import java.util.UUID;

public record ResolvedPromptResponse(UUID templateId, int version, String content) {
    @Override
    public String toString() {
        return "ResolvedPromptResponse[templateId=" + templateId + ", version=" + version + "]";
    }
}
