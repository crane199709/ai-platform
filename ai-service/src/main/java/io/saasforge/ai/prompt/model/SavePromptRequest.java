package io.saasforge.ai.prompt.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SavePromptRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 32768) String content,
        @NotNull @Size(max = 64) List<String> variables) {
    @Override
    public String toString() {
        return "SavePromptRequest[content omitted]";
    }
}
