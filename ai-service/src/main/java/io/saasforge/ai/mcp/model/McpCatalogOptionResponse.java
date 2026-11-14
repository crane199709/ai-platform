package io.saasforge.ai.mcp.model;

import java.util.List;
import java.util.UUID;

public record McpCatalogOptionResponse(UUID id, String name, int version, List<McpToolDefinition> tools) {}
