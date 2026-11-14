package io.saasforge.ai.prompt.model;

import java.util.List;
import java.util.UUID;

public record PromptResponse(
        UUID id, String name, String source, String content, List<String> variables, Integer publishedVersion) {
    @Override
    public String toString() {
        return "PromptResponse[id=" + id + "]";
    }
}
