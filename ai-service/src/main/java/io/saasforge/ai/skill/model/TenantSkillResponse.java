package io.saasforge.ai.skill.model;

import java.util.UUID;

public record TenantSkillResponse(
        UUID catalogId,
        String code,
        String name,
        String description,
        SkillStatus status,
        int catalogVersion,
        boolean draftEnabled,
        int publishedVersion,
        boolean enabled,
        Integer skillVersion) {}
