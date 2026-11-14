package io.saasforge.ai.mcp.model;

import jakarta.validation.constraints.*;
import java.util.*;

public record McpCatalogOptionResponse(UUID id, String name, int version, List<McpToolDefinition> tools) {}
