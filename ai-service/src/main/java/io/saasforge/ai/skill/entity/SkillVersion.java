package io.saasforge.ai.skill.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_skill_version")
public class SkillVersion extends AuditableEntity {
    @TableId
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    private UUID catalogId;

    public UUID getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(UUID value) {
        catalogId = value;
    }

    private Integer version;

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer value) {
        version = value;
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

    @Override
    public String toString() {
        return "SkillVersion[redacted]";
    }
}
