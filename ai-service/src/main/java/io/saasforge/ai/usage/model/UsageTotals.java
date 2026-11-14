package io.saasforge.ai.usage.model;

public record UsageTotals(
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long estimatedTokens,
        long turnCount,
        long toolCalls,
        long unknownUsageTurns,
        long activeTurns) {}
