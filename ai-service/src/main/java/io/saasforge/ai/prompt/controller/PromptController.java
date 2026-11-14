package io.saasforge.ai.prompt.controller;

import io.saasforge.ai.prompt.model.PreviewPromptRequest;
import io.saasforge.ai.prompt.model.PreviewPromptResponse;
import io.saasforge.ai.prompt.model.PromptOptionResponse;
import io.saasforge.ai.prompt.model.PromptResponse;
import io.saasforge.ai.prompt.model.PromptVersionResponse;
import io.saasforge.ai.prompt.model.RenderPromptRequest;
import io.saasforge.ai.prompt.model.ResolvedPromptResponse;
import io.saasforge.ai.prompt.model.SavePromptRequest;
import io.saasforge.ai.prompt.service.PromptService;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Prompt 管理")
@RestController
@RequestMapping("/prompts")
public class PromptController {
    private final PromptService prompts;

    public PromptController(PromptService prompts) {
        this.prompts = prompts;
    }

    @Operation(summary = "创建当前租户的 Prompt 草稿")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("ai:prompt:update")
    public ApiResponse<PromptResponse> create(@Valid @RequestBody SavePromptRequest request) {
        return ApiResponse.created(prompts.create(request));
    }

    @Operation(summary = "读取当前租户的 Prompt 草稿")
    @GetMapping("/{id}")
    @RequiresPermission("ai:prompt:read")
    public ApiResponse<PromptResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(prompts.get(id));
    }

    @Operation(summary = "保存 Prompt 草稿，不修改发布版本")
    @PutMapping("/{id}/draft")
    @RequiresPermission("ai:prompt:update")
    public ApiResponse<PromptResponse> saveDraft(@PathVariable UUID id, @Valid @RequestBody SavePromptRequest request) {
        return ApiResponse.ok(prompts.saveDraft(id, request));
    }

    @Operation(summary = "发布不可变 Prompt 版本")
    @PostMapping("/{id}/publish")
    @RequiresPermission("ai:prompt:publish")
    @AuditLog(action = "ai.prompt.publish", resourceType = "prompt", resourceId = "#id")
    public ApiResponse<PromptVersionResponse> publish(@PathVariable UUID id) {
        return ApiResponse.ok(prompts.publish(id));
    }

    @Operation(summary = "读取 Prompt 发布历史")
    @GetMapping("/{id}/versions")
    @RequiresPermission("ai:prompt:read")
    public ApiResponse<List<PromptVersionResponse>> history(@PathVariable UUID id) {
        return ApiResponse.ok(prompts.history(id));
    }

    @Operation(summary = "将当前发布指针回滚到历史版本")
    @PostMapping("/{id}/versions/{version}/rollback")
    @RequiresPermission("ai:prompt:rollback")
    @AuditLog(action = "ai.prompt.rollback", resourceType = "prompt", resourceId = "#id")
    public ApiResponse<PromptVersionResponse> rollback(@PathVariable UUID id, @PathVariable int version) {
        return ApiResponse.ok(prompts.rollback(id, version));
    }

    @Operation(summary = "渲染指定的已发布 Prompt 版本")
    @PostMapping("/{id}/render")
    @RequiresPermission("ai:conversation:use")
    public ApiResponse<ResolvedPromptResponse> render(
            @PathVariable UUID id, @Valid @RequestBody RenderPromptRequest request) {
        return ApiResponse.ok(prompts.resolvePublished(id, request.version(), request.variables()));
    }

    @Operation(summary = "校验并预览 Prompt，不保存或发布")
    @PostMapping("/preview")
    @RequiresPermission("ai:prompt:update")
    public ApiResponse<PreviewPromptResponse> preview(@Valid @RequestBody PreviewPromptRequest request) {
        return ApiResponse.ok(prompts.preview(request));
    }

    @Operation(summary = "读取平台预置和本租户的 Prompt 草稿目录")
    @GetMapping
    @RequiresPermission("ai:prompt:read")
    public ApiResponse<List<PromptResponse>> list() {
        return ApiResponse.ok(prompts.list());
    }

    @Operation(summary = "读取会话可选择的已发布 Prompt，不返回正文")
    @GetMapping("/published")
    @RequiresPermission("ai:conversation:use")
    public ApiResponse<List<PromptOptionResponse>> availablePublished() {
        return ApiResponse.ok(prompts.availablePublished());
    }

    @Operation(summary = "渲染默认的已发布平台 Prompt")
    @PostMapping("/default/render")
    @RequiresPermission("ai:conversation:use")
    public ApiResponse<ResolvedPromptResponse> renderDefault(@Valid @RequestBody RenderPromptRequest request) {
        return ApiResponse.ok(prompts.resolvePublished(null, request.version(), request.variables()));
    }
}
