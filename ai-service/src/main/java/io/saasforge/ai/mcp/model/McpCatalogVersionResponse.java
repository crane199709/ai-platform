package io.saasforge.ai.mcp.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record McpCatalogVersionResponse(
        UUID catalogId,
        int version,
        McpCatalogRequest configuration,
        UUID reviewedBy,
        OffsetDateTime reviewedAt,
        boolean current) {}
