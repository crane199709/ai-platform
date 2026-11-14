package io.saasforge.ai.skill.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_skill_catalog")
public class SkillEntry extends AuditableEntity {
    @TableId
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String value) {
        code = value;
    }

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String value) {
        name = value;
    }

    private String description;

    public String getDescription() {
        return description;
    }

    public void setDescription(String value) {
        description = value;
    }

    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String value) {
        content = value;
    }

    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }

    private Integer publishedVersion;

    public Integer getPublishedVersion() {
        return publishedVersion;
    }

    public void setPublishedVersion(Integer value) {
        publishedVersion = value;
    }

    @Override
    public String toString() {
        return "SkillEntry[redacted]";
    }
}
