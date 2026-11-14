package io.saasforge.ai.mcp.model;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;

public record McpCatalogVersionResponse(
        UUID catalogId,
        int version,
        McpCatalogRequest configuration,
        UUID reviewedBy,
        OffsetDateTime reviewedAt,
        boolean current) {}
