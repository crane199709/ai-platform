package io.saasforge.ai.mcp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_tenant_mcp")
public class McpConfiguration extends AuditableEntity {
    @TableId
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID value) {
        id = value;
    }

    private UUID catalogId;

    public UUID getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(UUID value) {
        catalogId = value;
    }

    private Boolean enabled;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean value) {
        enabled = value;
    }

    private String toolNames;

    public String getToolNames() {
        return toolNames;
    }

    public void setToolNames(String value) {
        toolNames = value;
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
        return "McpConfiguration[redacted]";
    }
}
