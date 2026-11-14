package io.saasforge.ai.mcp.model;

import jakarta.validation.constraints.NotNull;

public record McpApprovalDecisionRequest(@NotNull Boolean approved) {}
