package io.saasforge.ai.mcp.model;

import jakarta.validation.constraints.*;
import java.util.*;

public record McpToolDefinition(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,16}") String name,
        @NotBlank @Size(max = 1000) String description,
        @NotNull Map<String, Object> inputSchema,

        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*:read")
        String permission,

        @NotNull Boolean readOnly) {}
