package io.saasforge.ai.usage.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record DailyUsage(
        LocalDate usageDate,
        long version,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long turnCount,
        long toolCalls,
        long unknownUsageTurns,
        long estimatedTokens,
        OffsetDateTime reportedAt) {}
