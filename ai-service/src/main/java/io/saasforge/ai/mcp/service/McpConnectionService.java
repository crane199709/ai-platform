package io.saasforge.ai.mcp.service;

import io.saasforge.ai.mcp.config.McpProperties;
import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(McpProperties.class)
public class McpConnectionService {
    private final McpProperties properties;

    public McpConnectionService(McpProperties properties) {
        this.properties = properties;
    }

    public boolean isTrustedBusiness(URI uri) {
        return properties.trustedBusinessOrigins().stream()
                .anyMatch(origin -> "https".equals(origin.getScheme())
                        && origin.getHost() != null
                        && origin.getHost().equalsIgnoreCase(uri.getHost())
                        && port(origin) == port(uri)
                        && origin.getUserInfo() == null
                        && origin.getQuery() == null
                        && origin.getFragment() == null
                        && (origin.getPath().isEmpty() || origin.getPath().equals("/")));
    }

    private int port(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }
}
