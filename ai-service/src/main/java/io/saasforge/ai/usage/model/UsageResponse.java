package io.saasforge.ai.usage.model;

import java.util.List;

public record UsageResponse(
        String month,
        String businessZone,
        boolean quotaKnown,
        Long planVersion,
        Long tokenQuota,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long totalTokens,
        long estimatedTokens,
        long chargedTokens,
        Long remainingTokens,
        long turnCount,
        long toolCalls,
        long unknownUsageTurns,
        long activeTurns,
        List<DailyUsage> days) {}
