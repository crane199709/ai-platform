package io.saasforge.ai.prompt.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("ai_prompt_version")
public class PromptVersion extends AuditableEntity {
    @TableId
    private UUID id;

    private UUID templateId;
    private Integer version;
    private String content;
    private String variableNames;
    private UUID publishedBy;
    private OffsetDateTime publishedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID value) {
        this.id = value;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID value) {
        this.templateId = value;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer value) {
        this.version = value;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String value) {
        this.content = value;
    }

    public String getVariableNames() {
        return variableNames;
    }

    public void setVariableNames(String value) {
        this.variableNames = value;
    }

    public UUID getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(UUID value) {
        this.publishedBy = value;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(OffsetDateTime value) {
        this.publishedAt = value;
    }

    @Override
    public String toString() {
        return "PromptVersion[id=" + id + "]";
    }
}
