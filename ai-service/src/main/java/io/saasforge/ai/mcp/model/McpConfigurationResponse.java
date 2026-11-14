package io.saasforge.ai.mcp.model;

import java.util.List;
import java.util.UUID;

public record McpConfigurationResponse(
        UUID catalogId,
        boolean enabled,
        List<String> toolNames,
        Integer publishedVersion,
        Boolean publishedEnabled,
        Integer catalogVersion) {}
