package io.saasforge.ai.conversation.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_conversation_turn")
public class ConversationTurn extends AuditableEntity {
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

    private String idempotencyKey;

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String value) {
        this.idempotencyKey = value;
    }

    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        this.status = value;
    }

    private UUID modelId;

    public UUID getModelId() {
        return modelId;
    }

    public void setModelId(UUID value) {
        this.modelId = value;
    }

    private String errorCode;

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String value) {
        this.errorCode = value;
    }

    private Long inputTokens;

    public Long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Long value) {
        this.inputTokens = value;
    }

    private Long outputTokens;

    public Long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Long value) {
        this.outputTokens = value;
    }

    private Long cachedInputTokens;

    public Long getCachedInputTokens() {
        return cachedInputTokens;
    }

    public void setCachedInputTokens(Long value) {
        this.cachedInputTokens = value;
    }

    private Boolean usageReported;

    public Boolean getUsageReported() {
        return usageReported;
    }

    public void setUsageReported(Boolean value) {
        this.usageReported = value;
    }

    @Override
    public String toString() {
        return "ConversationTurn[redacted]";
    }
}
