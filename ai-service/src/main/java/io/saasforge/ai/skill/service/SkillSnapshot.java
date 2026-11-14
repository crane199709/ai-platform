package io.saasforge.ai.skill.service;

import java.util.UUID;

/** 会话保存的不可变审核内容；仅物化 Markdown 正文，不含路径或执行配置。 */
public record SkillSnapshot(UUID catalogId, String code, String name, String description, int version, String content) {
    @Override
    public String toString() {
        return "SkillSnapshot[redacted]";
    }
}
