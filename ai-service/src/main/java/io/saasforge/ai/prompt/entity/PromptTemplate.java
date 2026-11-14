package io.saasforge.ai.prompt.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_prompt_template")
public class PromptTemplate extends AuditableEntity {
    @TableId
    private UUID id;

    private String name;
    private String content;
    private String variableNames;
    private Integer publishedVersion;

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

    public Integer getPublishedVersion() {
        return publishedVersion;
    }

    public void setPublishedVersion(Integer value) {
        this.publishedVersion = value;
    }

    @Override
    public String toString() {
        return "PromptTemplate[id=" + id + "]";
    }
}
