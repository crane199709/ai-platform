package io.saasforge.ai.conversation.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_conversation_message")
public class ConversationMessage extends AuditableEntity {
    @TableId
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID value) {
        this.id = value;
    }

    private UUID conversationId;

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID value) {
        this.conversationId = value;
    }

    private UUID turnId;

    public UUID getTurnId() {
        return turnId;
    }

    public void setTurnId(UUID value) {
        this.turnId = value;
    }

    private String role;

    public String getRole() {
        return role;
    }

    public void setRole(String value) {
        this.role = value;
    }

    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String value) {
        this.content = value;
    }

    private String toolCalls;

    public String getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(String value) {
        this.toolCalls = value;
    }

    private String toolName;

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String value) {
        this.toolName = value;
    }

    @Override
    public String toString() {
        return "ConversationMessage[redacted]";
    }
}
