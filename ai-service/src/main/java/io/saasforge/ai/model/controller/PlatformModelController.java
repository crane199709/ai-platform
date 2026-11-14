package io.saasforge.ai.model.controller;

import io.saasforge.ai.model.model.ModelRequest;
import io.saasforge.ai.model.model.ModelResponse;
import io.saasforge.ai.model.service.ModelCatalogService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/platform/models")
@PreAuthorize("@platformAccessService.hasPermission('platform:ops:manage')")
public class PlatformModelController {
    private final ModelCatalogService catalog;

    public PlatformModelController(ModelCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @Operation(summary = "列出平台模型目录")
    public ApiResponse<List<ModelResponse>> list() {
        return ApiResponse.ok(catalog.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取平台模型")
    public ApiResponse<ModelResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(catalog.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记平台模型与密钥引用")
    public ApiResponse<ModelResponse> create(@Valid @RequestBody ModelRequest request) {
        return ApiResponse.created(catalog.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新平台模型")
    public ApiResponse<ModelResponse> update(@PathVariable UUID id, @Valid @RequestBody ModelRequest request) {
        return ApiResponse.ok(catalog.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "逻辑删除平台模型")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        catalog.delete(id);
        return ApiResponse.ok(null);
    }
}
