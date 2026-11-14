package io.saasforge.ai.conversation.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_conversation")
public class Conversation extends AuditableEntity {
    @TableId
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID value) {
        this.id = value;
    }

    private UUID ownerId;

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID value) {
        this.ownerId = value;
    }

    private String title;

    public String getTitle() {
        return title;
    }

    public void setTitle(String value) {
        this.title = value;
    }

    private UUID modelId;

    public UUID getModelId() {
        return modelId;
    }

    public void setModelId(UUID value) {
        this.modelId = value;
    }

    @com.baomidou.mybatisplus.annotation.TableField(
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private UUID activeTurnId;

    public UUID getActiveTurnId() {
        return activeTurnId;
    }

    public void setActiveTurnId(UUID value) {
        this.activeTurnId = value;
    }

    private UUID promptTemplateId;

    public UUID getPromptTemplateId() {
        return promptTemplateId;
    }

    public void setPromptTemplateId(UUID value) {
        this.promptTemplateId = value;
    }

    private Integer promptVersion;

    public Integer getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(Integer value) {
        this.promptVersion = value;
    }

    private String instructions;

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String value) {
        this.instructions = value;
    }

    private String skillSnapshot;

    public String getSkillSnapshot() {
        return skillSnapshot;
    }

    public void setSkillSnapshot(String value) {
        this.skillSnapshot = value;
    }

    @Override
    public String toString() {
        return "Conversation[redacted]";
    }

    private String mcpSnapshot;

    public String getMcpSnapshot() {
        return mcpSnapshot;
    }

    public void setMcpSnapshot(String value) {
        mcpSnapshot = value;
    }
}
