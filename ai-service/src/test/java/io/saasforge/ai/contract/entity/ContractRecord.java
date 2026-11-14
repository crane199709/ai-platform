package io.saasforge.ai.contract.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("contract_record")
public class ContractRecord extends AuditableEntity {
    @TableId
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }
}
