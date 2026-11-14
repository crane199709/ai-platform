package io.saasforge.ai.skill.model;

import jakarta.validation.constraints.*;

public record SkillRollbackRequest(@Min(1) int version) {}
