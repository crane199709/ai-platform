package io.saasforge.ai.prompt.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record PreviewPromptRequest(
        @NotBlank @Size(max = 32768) String content,
        @NotNull @Size(max = 64) List<String> variables,
        @NotNull @Size(max = 64) Map<String, String> values) {
    @Override
    public String toString() {
        return "PreviewPromptRequest[content and values omitted]";
    }
}
