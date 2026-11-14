package io.saasforge.ai.usage.controller;

import io.saasforge.ai.usage.model.*;
import io.saasforge.ai.usage.service.UsageService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/platform/usage")
public class PlatformUsageController {
    private final UsageService usage;

    public PlatformUsageController(UsageService usage) {
        this.usage = usage;
    }

    @PutMapping("/tenants/{tenantId}/turns/{turnId}/estimate")
    @PreAuthorize("@platformAccessService.hasPermission('platform:ops:manage')")
    public ApiResponse<UsageResponse> adjust(
            @PathVariable UUID tenantId, @PathVariable UUID turnId, @Valid @RequestBody EstimateRequest request) {
        try (var ignored = CurrentTenant.bind(tenantId)) {
            return ApiResponse.ok(usage.adjust(turnId, request));
        }
    }
}
