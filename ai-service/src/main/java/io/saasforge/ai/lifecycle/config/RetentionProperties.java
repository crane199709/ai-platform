package io.saasforge.ai.lifecycle.config;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ai.retention")
public record RetentionProperties(
        @DefaultValue("90") int defaultDays, Map<String, Integer> planDays, Map<UUID, Integer> tenantDays) {
    public RetentionProperties {
        planDays = planDays == null ? Map.of() : Map.copyOf(planDays);
        tenantDays = tenantDays == null ? Map.of() : Map.copyOf(tenantDays);
        if (defaultDays <= 0
                || planDays.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue() <= 0)
                || tenantDays.values().stream().anyMatch(days -> days <= 0)) {
            throw new IllegalArgumentException("AI retention days must be positive");
        }
    }

    public int days(UUID tenantId, String planCode) {
        Integer tenant = tenantDays.get(tenantId);
        if (tenant != null) {
            return tenant;
        }
        return planCode == null ? defaultDays : planDays.getOrDefault(planCode, defaultDays);
    }
}
