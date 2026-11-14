package io.saasforge.ai.mcp.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("ai_mcp_catalog_version")
public class McpCatalogVersion extends AuditableEntity {
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

    private Integer version;

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer value) {
        version = value;
    }

    private String configuration;

    public String getConfiguration() {
        return configuration;
    }

    public void setConfiguration(String value) {
        configuration = value;
    }

    private UUID reviewedBy;

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(UUID value) {
        reviewedBy = value;
    }

    private OffsetDateTime reviewedAt;

    public OffsetDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(OffsetDateTime value) {
        reviewedAt = value;
    }

    @Override
    public String toString() {
        return "McpCatalogVersion[redacted]";
    }
}
