package io.saasforge.ai.conversation.service;

import io.saasforge.sdk.core.api.ForgeException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ConversationExportService {
    private final ConversationService conversations;
    private final JsonMapper json = JsonMapper.builder().build();

    public ConversationExportService(ConversationService conversations) {
        this.conversations = conversations;
    }

    @Transactional(readOnly = true)
    public Download export(UUID id, String format) {
        if (!List.of("markdown", "json").contains(format)) {
            throw new ForgeException(ConversationErrorCode.FORMAT);
        }
        var conversation = conversations.get(id);
        // 专门公开投影，绝不把数据库实体、工具参数或配置快照序列化到下载中。
        var messages = conversation.messages().stream()
                .map(m -> new ExportMessage(m.role(), m.content(), m.createdAt()))
                .toList();
        String body;
        if (format.equals("json")) {
            body = json.writeValueAsString(
                    new ExportConversation(id, conversation.title(), conversation.createdAt(), messages));
        } else {
            var markdown = new StringBuilder("# ")
                    .append(conversation.title().replace("\r", " ").replace("\n", " "))
                    .append("\n");
            for (var message : messages) {
                markdown.append("\n## ")
                        .append(
                                switch (message.role()) {
                                    case "user" -> "你";
                                    case "assistant" -> "助手";
                                    default -> "工具";
                                })
                        .append("\n\n")
                        .append(message.content())
                        .append("\n");
            }
            body = markdown.toString();
        }
        return new Download(
                "conversation-" + id + (format.equals("json") ? ".json" : ".md"),
                format.equals("json") ? "application/json" : "text/markdown",
                body.getBytes(StandardCharsets.UTF_8));
    }

    public record Download(String filename, String contentType, byte[] bytes) {}

    public record ExportConversation(UUID id, String title, OffsetDateTime createdAt, List<ExportMessage> messages) {}

    public record ExportMessage(String role, String content, OffsetDateTime createdAt) {}
}
