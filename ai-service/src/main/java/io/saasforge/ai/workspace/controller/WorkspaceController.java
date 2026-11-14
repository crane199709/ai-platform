package io.saasforge.ai.workspace.controller;

import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI 工作台")
@RestController
public class WorkspaceController {
    @Operation(summary = "读取当前 AI 工作台的租户与成员上下文")
    @GetMapping("/context")
    @RequiresPermission("ai:conversation:use")
    public ApiResponse<WorkspaceContextResponse> context() {
        var context = TenantContext.current();
        return ApiResponse.ok(
                new WorkspaceContextResponse(context.tenantId(), context.identityId(), context.membershipId()));
    }

    public record WorkspaceContextResponse(UUID tenantId, UUID identityId, UUID membershipId) {}
}
