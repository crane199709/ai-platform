package io.saasforge.ai.mcp.controller;

import io.saasforge.ai.mcp.model.McpApprovalDecisionResponse;
import io.saasforge.ai.mcp.service.McpToolApprovalService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mcp/approvals")
public class McpApprovalController {
    private final McpToolApprovalService approvals;

    public McpApprovalController(McpToolApprovalService approvals) {
        this.approvals = approvals;
    }

    @PostMapping("/{id}/decision")
    @RequiresPermission("ai:conversation:use")
    public ApiResponse<McpApprovalDecisionResponse> decide(
            @PathVariable UUID id, @Valid @RequestBody McpApprovalDecisionRequest request) {
        return ApiResponse.ok(approvals.decide(id, request.approved()));
    }

    public record McpApprovalDecisionRequest(@NotNull Boolean approved) {}
}
