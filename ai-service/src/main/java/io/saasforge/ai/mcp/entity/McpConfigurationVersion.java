package io.saasforge.ai.mcp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("ai_tenant_mcp_version")
public class McpConfigurationVersion extends AuditableEntity {
    @TableId
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID value) {
        id = value;
    }

    private UUID configurationId;

    public UUID getConfigurationId() {
        return configurationId;
    }

    public void setConfigurationId(UUID value) {
        configurationId = value;
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

    private String toolNames;

    public String getToolNames() {
        return toolNames;
    }

    public void setToolNames(String value) {
        toolNames = value;
    }

    private Integer catalogVersion;

    public Integer getCatalogVersion() {
        return catalogVersion;
    }

    public void setCatalogVersion(Integer value) {
        catalogVersion = value;
    }

    private String secretRef;

    public String getSecretRef() {
        return secretRef;
    }

    public void setSecretRef(String value) {
        secretRef = value;
    }

    private UUID publishedBy;

    public UUID getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(UUID value) {
        publishedBy = value;
    }

    private OffsetDateTime publishedAt;

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(OffsetDateTime value) {
        publishedAt = value;
    }

    @Override
    public String toString() {
        return "McpConfigurationVersion[redacted]";
    }
}
