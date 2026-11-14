package io.saasforge.ai.skill.controller;

import io.saasforge.ai.skill.model.*;
import io.saasforge.ai.skill.service.SkillCatalogService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/platform/skills")
@PreAuthorize("@platformAccessService.hasPermission('platform:ops:manage')")
public class PlatformSkillController {
    private final SkillCatalogService skills;

    public PlatformSkillController(SkillCatalogService skills) {
        this.skills = skills;
    }

    @GetMapping
    @Operation(summary = "列出平台 Skill 草稿目录")
    public ApiResponse<List<SkillResponse>> list() {
        return ApiResponse.ok(skills.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取平台 Skill 草稿")
    public ApiResponse<SkillResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(skills.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记平台审核 Skill 草稿")
    public ApiResponse<SkillResponse> create(@Valid @RequestBody SkillRequest request) {
        return ApiResponse.created(skills.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新平台 Skill 草稿")
    public ApiResponse<SkillResponse> update(@PathVariable UUID id, @Valid @RequestBody SkillRequest request) {
        return ApiResponse.ok(skills.update(id, request));
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "发布平台审核 Skill")
    public ApiResponse<SkillResponse> publish(@PathVariable UUID id) {
        return ApiResponse.ok(skills.publish(id));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "读取平台 Skill 发布历史")
    public ApiResponse<List<SkillVersionResponse>> versions(@PathVariable UUID id) {
        return ApiResponse.ok(skills.history(id));
    }

    @PostMapping("/{id}/rollback")
    @Operation(summary = "回滚平台 Skill 为新版本")
    public ApiResponse<SkillResponse> rollback(
            @PathVariable UUID id, @Valid @RequestBody SkillRollbackRequest request) {
        return ApiResponse.ok(skills.rollback(id, request.version()));
    }
}
