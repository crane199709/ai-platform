package io.saasforge.ai.skill.model;

import jakarta.validation.constraints.NotNull;

public record SkillEnableRequest(@NotNull Boolean enabled) {}
