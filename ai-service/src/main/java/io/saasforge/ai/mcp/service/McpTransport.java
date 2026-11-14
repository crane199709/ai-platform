package io.saasforge.ai.mcp.service;

import java.net.URI;
import java.util.Map;

/** 外部 MCP 的 HTTP 边界；测试只替代远端网络，不替代目录或授权。 */
public interface McpTransport {
    Response exchange(String method, URI uri, Map<String, String> headers, String body);

    record Response(int status, String contentType, String sessionId, byte[] body) {
        @Override
        public String toString() {
            return "McpResponse[redacted]";
        }
    }
}
