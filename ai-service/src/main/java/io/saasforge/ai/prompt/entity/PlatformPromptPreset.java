package io.saasforge.ai.prompt.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("platform_prompt_preset")
public class PlatformPromptPreset extends AuditableEntity {
    @TableId
    private UUID id;

    private String name;
    private String content;
    private String variableNames;
    private Integer version;
    private OffsetDateTime publishedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID value) {
        this.id = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer value) {
        this.version = value;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(OffsetDateTime value) {
        this.publishedAt = value;
    }

    @Override
    public String toString() {
        return "PlatformPromptPreset[id=" + id + "]";
    }
}
