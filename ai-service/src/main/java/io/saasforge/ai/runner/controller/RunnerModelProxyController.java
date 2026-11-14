package io.saasforge.ai.runner.controller;

import io.saasforge.ai.runner.service.RunnerCapabilityService;
import io.saasforge.ai.runner.service.RunnerModelProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;

/** 模型原始 SSE 透明传输给 DSH；错误在打开流前沿用 Forge JSON 信封。 */
@Controller
public class RunnerModelProxyController {
    private final RunnerModelProxyService proxy;

    public RunnerModelProxyController(RunnerModelProxyService proxy) {
        this.proxy = proxy;
    }

    @PostMapping(value = "/internal/runner/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(
            @Valid @RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse servletResponse) {
        var granted = (RunnerCapabilityService.GrantedContext)
                request.getAttribute(RunnerCapabilityService.REQUEST_ATTRIBUTE);
        var response = proxy.open(granted, body);
        StreamingResponseBody stream = output -> {
            try (var input = response.body()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    // Spring 7 的回调流忽略 flush，需由 Servlet 显式提交每片 SSE。
                    servletResponse.flushBuffer();
                }
                servletResponse.flushBuffer();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }
}
