package io.saasforge.ai.usage.service;

import java.util.UUID;

/** 供同租户留存等能力只读使用的可信 Plan 副本；身份缺省时由使用方沿用自己的默认策略。 */
public record UsagePlanSnapshot(UUID planId, String planCode, long version, Long tokenQuota) {}
