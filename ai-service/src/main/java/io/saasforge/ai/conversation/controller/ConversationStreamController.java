package io.saasforge.ai.conversation.controller;

import io.saasforge.ai.conversation.model.SendMessageRequest;
import io.saasforge.ai.conversation.service.ConversationStreamingService;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.RequiresPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE 自身的 token/tool/done/error 事件为信封，专用 MVC handler 返回真实流。 */
@Controller
public class ConversationStreamController {
    private final ConversationStreamingService streaming;

    public ConversationStreamController(ConversationStreamingService streaming) {
        this.streaming = streaming;
    }

    @PostMapping(value = "/conversations/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission("ai:conversation:use")
    public SseEmitter send(
            @PathVariable UUID id, @Valid @RequestBody SendMessageRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        try {
            return streaming.send(id, request);
        } catch (ForgeException error) {
            if (error.errorCode().httpStatus() == 429) {
                response.setHeader("Retry-After", error.args().length > 0 ? error.args()[0].toString() : "5");
            }
            throw error;
        }
    }
    // 断开的 SSE 没有可写响应，避免通用 JSON 异常处理器再次写入已关闭连接。
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void disconnected(HttpServletResponse response) {}
}
