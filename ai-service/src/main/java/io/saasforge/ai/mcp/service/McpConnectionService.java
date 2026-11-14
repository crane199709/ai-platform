package io.saasforge.ai.mcp.service;

import io.saasforge.ai.mcp.config.McpProperties;
import io.saasforge.ai.mcp.model.McpCatalogRequest;
import io.saasforge.ai.mcp.model.McpToolResultResponse;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@EnableConfigurationProperties(McpProperties.class)
public class McpConnectionService {
    private final McpProperties properties;
    private final Environment environment;
    private final McpTransport transport;
    private final JsonMapper json = JsonMapper.builder().build();

    public McpConnectionService(McpProperties properties, Environment environment, McpTransport transport) {
        this.properties = properties;
        this.environment = environment;
        this.transport = transport;
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

    public McpToolResultResponse call(
            McpCatalogRequest config,
            String secretReference,
            McpToolSnapshot tool,
            Map<String, Object> arguments,
            Runnable authorize) {
        var uri = URI.create(config.url());
        var headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("Content-Type", "application/json");
        String credential = null;
        switch (config.credentialMode()) {
            case "TENANT_SECRET" -> {
                if (!properties.secretReferences().contains(secretReference)) {
                    throw new ForgeException(McpErrorCode.SECRET_UNAVAILABLE);
                }
                credential = environment.getProperty(secretReference);
                if (credential == null
                        || credential.isBlank()
                        || credential.length() > 8192
                        || credential.chars().anyMatch(c -> c < 33 || c > 126)) {
                    throw new ForgeException(McpErrorCode.SECRET_UNAVAILABLE);
                }
            }
            case "BUSINESS_IDENTITY" -> {
                if (!isTrustedBusiness(uri)
                        || !(SecurityContextHolder.getContext().getAuthentication()
                                instanceof JwtAuthenticationToken auth)) {
                    throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
                }
                credential = auth.getToken().getTokenValue();
            }
            case "PUBLIC" -> {}
            default -> throw new ForgeException(McpErrorCode.UNAVAILABLE);
        }
        if (credential != null) {
            headers.put("Authorization", "Bearer " + credential);
        }
        String session = null;
        try {
            var initialized = transport.exchange(
                    "POST",
                    uri,
                    headers,
                    json.writeValueAsString(Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            1,
                            "method",
                            "initialize",
                            "params",
                            Map.of(
                                    "protocolVersion",
                                    "2025-03-26",
                                    "capabilities",
                                    Map.of(),
                                    "clientInfo",
                                    Map.of("name", "ai-platform", "version", "1")))));
            var negotiation = result(initialized, 1);
            if (!"2025-03-26".equals(negotiation.path("protocolVersion").asText())
                    || !negotiation.path("capabilities").path("tools").isObject()) {
                throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
            }
            session = initialized.sessionId();
            if (session != null) {
                if (!session.matches("[\\x21-\\x7e]{1,256}")) {
                    session = null;
                    throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
                }
                headers.put("Mcp-Session-Id", session);
            }
            var notified = transport.exchange(
                    "POST",
                    uri,
                    headers,
                    json.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")));
            if (notified.status() != 202) {
                throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
            }
            // 握手和用户审批都可能跨越取消/到期，实际 tools/call 发送前再次验证活跃能力和实时授权。
            authorize.run();
            var reply = transport.exchange(
                    "POST",
                    uri,
                    headers,
                    json.writeValueAsString(Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            2,
                            "method",
                            "tools/call",
                            "params",
                            Map.of("name", tool.nativeName(), "arguments", arguments))));
            var value = result(reply, 2);
            if (value.path("isError").asBoolean()
                    || !value.path("content").isArray()
                    || value.path("content").size() > 32) {
                throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
            }
            var content = new ArrayList<McpToolResultResponse.TextContent>();
            int length = 0;
            for (var item : value.path("content")) {
                if (!"text".equals(item.path("type").asText())
                        || !item.path("text").isString()) {
                    throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
                }
                String text = item.path("text").asText();
                length += text.length();
                if (length > 128000 || (credential != null && text.contains(credential))) {
                    throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
                }
                content.add(new McpToolResultResponse.TextContent("text", text));
            }
            return new McpToolResultResponse(List.copyOf(content), false);
        } catch (ForgeException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
        } finally {
            if (session != null) {
                try {
                    transport.exchange("DELETE", uri, headers, null);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private JsonNode result(McpTransport.Response response, int id) {
        if (response.status() != 200 || response.body().length > 1048576) {
            throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
        }
        var candidates = new ArrayList<JsonNode>();
        if (response.contentType().startsWith("application/json")) {
            candidates.add(json.readTree(response.body()));
        } else if (response.contentType().startsWith("text/event-stream")) {
            for (String frame : new String(response.body(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .split("\n\n")) {
                var data = new StringBuilder();
                for (String line : frame.split("\n")) {
                    if (line.startsWith("data:")) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(line.substring(5).stripLeading());
                    }
                }
                if (!data.isEmpty()) {
                    candidates.add(json.readTree(data.toString()));
                }
            }
        } else {
            throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
        }
        for (var candidate : candidates) {
            var replies =
                    candidate.isArray() ? candidate : json.createArrayNode().add(candidate);
            for (var reply : replies) {
                if (reply.path("id").asInt(-1) == id
                        && "2.0".equals(reply.path("jsonrpc").asText())) {
                    if (reply.has("error") || !reply.path("result").isObject()) {
                        throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
                    }
                    return reply.path("result");
                }
            }
        }
        throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
    }

    private int port(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }
}
