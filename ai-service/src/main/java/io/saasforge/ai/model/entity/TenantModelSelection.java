package io.saasforge.ai.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_tenant_model_selection")
public class TenantModelSelection extends AuditableEntity {
    @TableId
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    private String modelIds;

    public String getModelIds() {
        return modelIds;
    }

    public void setModelIds(String value) {
        this.modelIds = value;
    }

    @Override
    public String toString() {
        return "TenantModelSelection[redacted]";
    }
}
