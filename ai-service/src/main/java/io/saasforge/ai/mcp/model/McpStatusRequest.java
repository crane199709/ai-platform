package io.saasforge.ai.mcp.model;

import jakarta.validation.constraints.NotNull;

public record McpStatusRequest(@NotNull Boolean enabled) {}
