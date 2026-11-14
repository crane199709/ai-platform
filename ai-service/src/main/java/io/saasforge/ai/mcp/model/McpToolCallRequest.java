package io.saasforge.ai.mcp.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.saasforge.ai.mcp.service.McpErrorCode;
import io.saasforge.sdk.core.api.ForgeException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

public record McpToolCallRequest(
        @NotNull @Pattern(regexp = "m[0-9a-f]{32}") String name,
        @NotNull Map<String, Object> arguments) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new ForgeException(McpErrorCode.INVALID_CATALOG);
    }

    @Override
    public String toString() {
        return "McpToolCallRequest[redacted]";
    }
}
