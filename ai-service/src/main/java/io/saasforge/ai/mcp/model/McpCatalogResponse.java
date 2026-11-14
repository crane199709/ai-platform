package io.saasforge.ai.mcp.model;

import java.util.UUID;

public record McpCatalogResponse(UUID id, McpCatalogRequest draft, Integer publishedVersion, boolean enabled) {}
