package io.saasforge.ai.model.controller;

import io.saasforge.ai.model.model.ModelResponse;
import io.saasforge.ai.model.model.ModelSelectionRequest;
import io.saasforge.ai.model.model.ModelSelectionResponse;
import io.saasforge.ai.model.service.ModelSelectionService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/models")
public class TenantModelController {
    private final ModelSelectionService models;

    public TenantModelController(ModelSelectionService models) {
        this.models = models;
    }

    @GetMapping
    @RequiresPermission("ai:conversation:use")
    @Operation(summary = "列出当前租户可使用的模型")
    public ApiResponse<List<ModelResponse>> available() {
        return ApiResponse.ok(models.available());
    }

    @GetMapping("/{id}")
    @RequiresPermission("ai:conversation:use")
    @Operation(summary = "读取并验证当前租户可用模型")
    public ApiResponse<ModelResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(models.getAvailable(id));
    }

    @GetMapping("/catalog")
    @RequiresPermission("ai:configuration:manage")
    @Operation(summary = "列出租户可配置的模型目录")
    public ApiResponse<List<ModelResponse>> configurable() {
        return ApiResponse.ok(models.configurable());
    }

    @GetMapping("/selection")
    @RequiresPermission("ai:configuration:manage")
    @Operation(summary = "读取本租户模型选择")
    public ApiResponse<ModelSelectionResponse> selection() {
        return ApiResponse.ok(models.currentSelection());
    }

    @PutMapping("/selection")
    @RequiresPermission("ai:configuration:manage")
    @Operation(summary = "替换本租户可用模型选择")
    public ApiResponse<ModelSelectionResponse> select(@Valid @RequestBody ModelSelectionRequest request) {
        return ApiResponse.ok(models.select(request));
    }
}
