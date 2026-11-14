package io.saasforge.ai.usage.controller;

import io.saasforge.ai.usage.model.UsageResponse;
import io.saasforge.ai.usage.service.UsageService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/usage")
public class UsageController {
    private final UsageService usage;

    public UsageController(UsageService usage) {
        this.usage = usage;
    }

    @GetMapping
    @RequiresPermission("ai:configuration:manage")
    public ApiResponse<UsageResponse> get(@RequestParam(required = false) String month) {
        return ApiResponse.ok(usage.read(month));
    }
}
