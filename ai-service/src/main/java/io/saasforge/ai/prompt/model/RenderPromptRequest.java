package io.saasforge.ai.prompt.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record RenderPromptRequest(
        @Positive Integer version, @NotNull @Size(max = 64) Map<String, String> variables) {
    @Override
    public String toString() {
        return "RenderPromptRequest[values omitted]";
    }
}
