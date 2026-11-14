package io.saasforge.ai.skill.model;

import jakarta.validation.constraints.*;

public record SkillRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*")
        String code,

        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 1000) String description,
        @NotBlank @Size(max = 64000) String content,
        @NotNull SkillStatus status) {
    @Override
    public String toString() {
        return "SkillRequest[redacted]";
    }
}
