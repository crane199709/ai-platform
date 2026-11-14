package io.saasforge.ai.mcp.controller;

import io.saasforge.ai.mcp.model.McpToolCallRequest;
import io.saasforge.ai.mcp.model.McpToolResultResponse;
import io.saasforge.ai.mcp.service.McpToolProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/** 内网 Runner 使用固定 MCP 工具结果信封；独立于对浏览器的 Forge JSON 管理接口。 */
@Controller
public class RunnerToolProxyController {
    private final McpToolProxyService proxy;

    public RunnerToolProxyController(McpToolProxyService proxy) {
        this.proxy = proxy;
    }

    @PostMapping("/internal/runner/tools/call")
    @ResponseBody
    public McpToolResultResponse call(@Valid @RequestBody McpToolCallRequest request, HttpServletRequest servlet) {
        return proxy.call(request, servlet);
    }
}
