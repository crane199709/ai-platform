package io.saasforge.ai.skill.controller;

import io.saasforge.ai.skill.model.*;
import io.saasforge.ai.skill.service.SkillService;
import io.saasforge.ai.skill.service.SkillSnapshot;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/skills")
public class TenantSkillController {
    private final SkillService skills;

    public TenantSkillController(SkillService skills) {
        this.skills = skills;
    }

    @GetMapping
    @RequiresPermission("ai:skill:read")
    @Operation(summary = "列出审核 Skill 与本租户启用配置")
    public ApiResponse<List<TenantSkillResponse>> list() {
        return ApiResponse.ok(skills.list());
    }

    @GetMapping("/available")
    @RequiresPermission("ai:conversation:use")
    @Operation(summary = "读取新会话可用的已发布 Skill 快照")
    public ApiResponse<List<SkillSnapshot>> available() {
        return ApiResponse.ok(skills.resolvePublished());
    }

    @GetMapping("/{id}")
    @RequiresPermission("ai:skill:read")
    @Operation(summary = "读取本租户 Skill 配置")
    public ApiResponse<TenantSkillResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(skills.get(id));
    }

    @PutMapping("/{id}/draft")
    @RequiresPermission("ai:skill:update")
    @Operation(summary = "保存本租户 Skill 启停草稿")
    public ApiResponse<TenantSkillResponse> draft(
            @PathVariable UUID id, @Valid @RequestBody SkillEnableRequest request) {
        return ApiResponse.ok(skills.draft(id, request.enabled()));
    }

    @PostMapping("/{id}/publish")
    @RequiresPermission("ai:skill:publish")
    @Operation(summary = "发布本租户 Skill 配置")
    public ApiResponse<TenantSkillResponse> publish(@PathVariable UUID id) {
        return ApiResponse.ok(skills.publish(id));
    }

    @GetMapping("/{id}/versions")
    @RequiresPermission("ai:skill:read")
    @Operation(summary = "读取本租户 Skill 配置历史")
    public ApiResponse<List<TenantSkillVersionResponse>> versions(@PathVariable UUID id) {
        return ApiResponse.ok(skills.history(id));
    }

    @PostMapping("/{id}/rollback")
    @RequiresPermission("ai:skill:rollback")
    @Operation(summary = "回滚本租户 Skill 配置")
    public ApiResponse<TenantSkillResponse> rollback(
            @PathVariable UUID id, @Valid @RequestBody SkillRollbackRequest request) {
        return ApiResponse.ok(skills.rollback(id, request.version()));
    }

    public record SkillEnableRequest(@NotNull Boolean enabled) {}
}
