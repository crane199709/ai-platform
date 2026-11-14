package io.saasforge.ai.mcp.service;

import java.util.Map;
import java.util.UUID;

public record McpToolSnapshot(
        UUID catalogId,
        int catalogVersion,
        int tenantVersion,
        String runtimeName,
        String nativeName,
        String description,
        Map<String, Object> inputSchema) {
    public McpToolSnapshot {
        inputSchema = Map.copyOf(inputSchema);
    }

    public String scope() {
        return "tool:" + runtimeName;
    }
}
