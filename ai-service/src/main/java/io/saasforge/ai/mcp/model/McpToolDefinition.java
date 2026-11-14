package io.saasforge.ai.mcp.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record McpToolDefinition(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String name,
        @NotBlank @Size(max = 1000) String description,
        @NotNull Map<String, Object> inputSchema,

        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*:read")
        String permission,

        @NotNull Boolean readOnly) {}
