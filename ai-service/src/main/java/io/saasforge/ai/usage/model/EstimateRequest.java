package io.saasforge.ai.usage.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record EstimateRequest(
        @NotNull @Min(0) Long estimatedTokens,

        @NotNull @Pattern(regexp = "PROVIDER_RECONCILIATION|EXECUTION_CONFIRMED_ABSENT|OPERATIONS_ESTIMATE")
        String reasonCode) {}
