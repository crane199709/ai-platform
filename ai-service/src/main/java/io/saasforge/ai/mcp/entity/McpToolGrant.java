package io.saasforge.ai.mcp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_mcp_tool_grant")
public class McpToolGrant extends AuditableEntity {
    @TableId
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID value) {
        id = value;
    }

    private Integer catalogVersion;
    private String runtimeName;

    public Integer getCatalogVersion() {
        return catalogVersion;
    }

    public void setCatalogVersion(Integer value) {
        catalogVersion = value;
    }

    public String getRuntimeName() {
        return runtimeName;
    }

    public void setRuntimeName(String value) {
        runtimeName = value;
    }

    private UUID catalogId;

    public UUID getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(UUID value) {
        catalogId = value;
    }

    private Integer tenantVersion;

    public Integer getTenantVersion() {
        return tenantVersion;
    }

    public void setTenantVersion(Integer value) {
        tenantVersion = value;
    }

    private String toolName;

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String value) {
        toolName = value;
    }

    private UUID ownerId;

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID value) {
        ownerId = value;
    }
}
