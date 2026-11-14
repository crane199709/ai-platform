package io.saasforge.ai.mcp.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record McpCatalogRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 500) String url,
        @NotBlank @Pattern(regexp = "STREAMABLE_HTTP") String protocol,

        @NotBlank @Pattern(regexp = "PUBLIC|TENANT_SECRET|BUSINESS_IDENTITY")
        String credentialMode,

        @NotBlank @Pattern(regexp = "PUBLIC_DATA|TENANT_CREDENTIAL|BUSINESS_RBAC_ABAC")
        String isolation,

        @NotEmpty @Size(max = 32) List<@Valid McpToolDefinition> tools) {}
