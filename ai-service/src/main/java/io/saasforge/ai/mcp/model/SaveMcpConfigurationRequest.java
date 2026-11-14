package io.saasforge.ai.mcp.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.saasforge.ai.mcp.service.McpErrorCode;
import io.saasforge.sdk.core.api.ForgeException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SaveMcpConfigurationRequest(
        @NotNull Boolean enabled, @NotNull @Size(max = 32) List<@NotBlank String> toolNames) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new ForgeException(McpErrorCode.INVALID_CATALOG);
    }
}
