package io.saasforge.ai.mcp.model;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;

public record McpConfigurationVersionResponse(
        UUID catalogId,
        int version,
        boolean enabled,
        List<String> toolNames,
        int catalogVersion,
        UUID publishedBy,
        OffsetDateTime publishedAt,
        boolean current) {}
