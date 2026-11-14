package io.saasforge.ai.skill.model;

import jakarta.validation.constraints.*;

public record SkillVersionResponse(int version, String name, String description, String content) {
    @Override
    public String toString() {
        return "SkillVersionResponse[redacted]";
    }
}
