package io.saasforge.ai.mcp.model;

import java.util.UUID;

public record McpApprovalDecisionResponse(UUID approvalId, String status) {}
