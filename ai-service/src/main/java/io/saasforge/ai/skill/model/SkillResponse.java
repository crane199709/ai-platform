package io.saasforge.ai.skill.model;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record SkillResponse(
        UUID id,
        String code,
        String name,
        String description,
        String content,
        SkillStatus status,
        int publishedVersion) {
    @Override
    public String toString() {
        return "SkillResponse[redacted]";
    }
}
