package io.saasforge.ai.mcp.model;

import java.util.List;

public record McpToolResultResponse(List<TextContent> content, boolean isError) {
    public record TextContent(String type, String text) {
        @Override
        public String toString() {
            return "McpTextContent[redacted]";
        }
    }

    @Override
    public String toString() {
        return "McpToolResultResponse[redacted]";
    }
}
