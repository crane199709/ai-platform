package io.saasforge.ai.mcp.model;

import jakarta.validation.constraints.*;
import java.util.*;

public record McpCatalogResponse(UUID id, McpCatalogRequest draft, Integer publishedVersion, boolean enabled) {}
