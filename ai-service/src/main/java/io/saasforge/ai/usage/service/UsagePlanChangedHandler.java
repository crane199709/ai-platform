package io.saasforge.ai.usage.service;

import io.saasforge.ai.usage.mapper.UsageMapper;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsagePlanChangedHandler implements EventHandler<TenantPlanChanged> {
    private final UsageMapper mapper;

    public UsagePlanChangedHandler(UsageMapper mapper) {
        this.mapper = mapper;
    }

    public String topic() {
        return "forge-tenant";
    }

    public Class<TenantPlanChanged> eventType() {
        return TenantPlanChanged.class;
    }

    @Transactional
    public void handle(TenantPlanChanged event) {
        if (!CurrentTenant.require().equals(event.tenantId())
                || event.version() <= 0
                || (event.tokenQuota() != null && event.tokenQuota() < 0)) {
            throw new ForgeException(UsageErrorCode.INVALID);
        }
        mapper.applyPlan(UuidV7.generate(), event);
    }
}
