package io.saasforge.ai.mcp.model;

import jakarta.validation.constraints.*;
import java.util.*;

public record McpConfigurationResponse(
        UUID catalogId,
        boolean enabled,
        List<String> toolNames,
        Integer publishedVersion,
        Boolean publishedEnabled,
        Integer catalogVersion) {}
