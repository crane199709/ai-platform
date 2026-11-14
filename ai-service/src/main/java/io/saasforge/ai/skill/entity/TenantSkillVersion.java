package io.saasforge.ai.skill.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_tenant_skill_version")
public class TenantSkillVersion extends AuditableEntity {
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

    private Boolean enabled;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean value) {
        enabled = value;
    }

    private Integer skillVersion;

    public Integer getSkillVersion() {
        return skillVersion;
    }

    public void setSkillVersion(Integer value) {
        skillVersion = value;
    }
}
