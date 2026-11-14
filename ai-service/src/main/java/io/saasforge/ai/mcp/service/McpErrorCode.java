package io.saasforge.ai.mcp.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum McpErrorCode implements ErrorCode {
    NOT_FOUND("mcp.notFound", 404),
    UNAVAILABLE("mcp.unavailable", 409),
    INVALID_CATALOG("mcp.invalidCatalog", 400),
    CONFLICT("mcp.conflict", 409),
    UPSTREAM_FAILURE("mcp.upstreamFailure", 502),
    SECRET_UNAVAILABLE("mcp.secretUnavailable", 503),
    APPROVAL_REJECTED("mcp.approvalRejected", 403),
    APPROVAL_EXPIRED("mcp.approvalExpired", 409),
    APPROVAL_UNAVAILABLE("mcp.approvalUnavailable", 409);
    private final String code;
    private final int status;

    McpErrorCode(String code, int status) {
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return status;
    }
}
