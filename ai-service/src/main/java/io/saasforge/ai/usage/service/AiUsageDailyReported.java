package io.saasforge.ai.usage.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** forge-tenant 的按日累计快照；字段契约见 docs/usage-api.md。 */
public record AiUsageDailyReported(
        UUID tenantId,
        LocalDate usageDate,
        String businessZone,
        long version,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long turnCount,
        long toolCalls,
        long unknownUsageTurns,
        long estimatedTokens,
        OffsetDateTime reportedAt) {}
