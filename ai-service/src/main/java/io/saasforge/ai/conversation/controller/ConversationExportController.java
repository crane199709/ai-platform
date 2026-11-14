package io.saasforge.ai.conversation.controller;

import io.saasforge.ai.conversation.service.ConversationExportService;
import io.saasforge.sdk.security.RequiresPermission;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** 规格要求直接下载 Markdown/JSON，专用 MVC handler；错误仍由 Forge 标准异常处理器映射。 */
@Controller
public class ConversationExportController {
    private final ConversationExportService exports;

    public ConversationExportController(ConversationExportService exports) {
        this.exports = exports;
    }

    @GetMapping("/conversations/{id}/export")
    @RequiresPermission("ai:conversation:use")
    public void export(@PathVariable UUID id, @RequestParam String format, HttpServletResponse response)
            throws IOException {
        var download = exports.export(id, format);
        response.setContentType(download.contentType() + ";charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + download.filename() + "\"");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLengthLong(download.bytes().length);
        response.getOutputStream().write(download.bytes());
    }
}
