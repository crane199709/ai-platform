package io.saasforge.ai.mcp.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record McpConfigurationVersionResponse(
        UUID catalogId,
        int version,
        boolean enabled,
        List<String> toolNames,
        int catalogVersion,
        UUID publishedBy,
        OffsetDateTime publishedAt,
        boolean current) {}
