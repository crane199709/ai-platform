package io.saasforge.ai.skill.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_tenant_skill")
public class TenantSkill extends AuditableEntity {
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

    private Boolean draftEnabled;

    public Boolean getDraftEnabled() {
        return draftEnabled;
    }

    public void setDraftEnabled(Boolean value) {
        draftEnabled = value;
    }

    private Integer publishedVersion;

    public Integer getPublishedVersion() {
        return publishedVersion;
    }

    public void setPublishedVersion(Integer value) {
        publishedVersion = value;
    }
}
