package io.saasforge.ai.mcp.service;

import io.saasforge.ai.mcp.model.McpToolCallRequest;
import io.saasforge.ai.mcp.model.McpToolResultResponse;
import io.saasforge.ai.runner.service.RunnerCapabilityService;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ForgeException;
import org.springframework.stereotype.Service;

@Service
public class McpToolProxyService {
    private final McpSelectionService selection;
    private final McpCatalogService catalog;
    private final McpConfigurationService configurations;
    private final McpConnectionService connection;
    private final McpToolApprovalService approvals;
    private final RunnerCapabilityService capabilities;

    public McpToolProxyService(
            McpSelectionService selection,
            McpCatalogService catalog,
            McpConfigurationService configurations,
            McpConnectionService connection,
            McpToolApprovalService approvals,
            RunnerCapabilityService capabilities) {
        this.approvals = approvals;
        this.capabilities = capabilities;
        this.selection = selection;
        this.catalog = catalog;
        this.configurations = configurations;
        this.connection = connection;
    }

    @AuditLog(action = "ai.tool.call", resourceType = "mcpTool", resourceId = "#request.name()")
    public McpToolResultResponse call(
            McpToolCallRequest request, RunnerCapabilityService.GrantedContext context, String token) {
        var tool = selection.resolve(request.name());
        approvals.await(token, context, tool);
        Runnable authorize = () -> {
            capabilities.authorize(token, context.tenant().tenantId(), context.sessionId(), tool.scope());
            var live = selection.resolve(request.name());
            if (live.requiresApproval() && !tool.requiresApproval()) {
                throw new ForgeException(McpErrorCode.APPROVAL_UNAVAILABLE);
            }
        };
        authorize.run();
        var config = catalog.published(tool.catalogId(), tool.catalogVersion());
        var pinned = configurations.version(tool.catalogId(), tool.tenantVersion());
        return connection.call(config, pinned.getSecretRef(), tool, request.arguments(), authorize);
    }
}
