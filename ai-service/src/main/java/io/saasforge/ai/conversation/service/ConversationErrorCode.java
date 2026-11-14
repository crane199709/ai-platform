package io.saasforge.ai.conversation.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum ConversationErrorCode implements ErrorCode {
    NOT_FOUND("conversation.notFound", 404),
    BUSY("conversation.busy", 409),
    IDEMPOTENCY_CONFLICT("conversation.idempotencyConflict", 409),
    LIMIT("conversation.limit", 413),
    RUNTIME("conversation.runtimeUnavailable", 502);

    private final String code;
    private final int status;

    ConversationErrorCode(String code, int status) {
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
