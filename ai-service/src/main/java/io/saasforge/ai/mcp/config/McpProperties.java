package io.saasforge.ai.mcp.config;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai.mcp")
public record McpProperties(List<URI> trustedBusinessOrigins, List<String> secretReferences) {
    public McpProperties {
        trustedBusinessOrigins = trustedBusinessOrigins == null ? List.of() : List.copyOf(trustedBusinessOrigins);
        secretReferences = secretReferences == null ? List.of() : List.copyOf(secretReferences);
        if (secretReferences.stream().anyMatch(ref -> !ref.matches("AI_MCP_[A-F0-9]{32}_[A-F0-9]{32}"))) {
            throw new IllegalArgumentException("Invalid MCP secret reference allowlist");
        }
    }

    @Override
    public String toString() {
        return "McpProperties[redacted]";
    }
}
