package io.saasforge.ai.mcp.config;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai.mcp")
public record McpProperties(List<URI> trustedBusinessOrigins) {
    public McpProperties {
        trustedBusinessOrigins = trustedBusinessOrigins == null ? List.of() : List.copyOf(trustedBusinessOrigins);
    }
}
