package io.saasforge.ai.usage.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum UsageErrorCode implements ErrorCode {
    PLAN_UNAVAILABLE("usage.planUnavailable", 503),
    QUOTA("usage.quotaExceeded", 429),
    BUSY("usage.busy", 409),
    NOT_FOUND("usage.notFound", 404),
    INVALID("usage.invalidRequest", 400),
    NOT_UNKNOWN("usage.notUnknown", 409);
    private final String code;
    private final int status;

    UsageErrorCode(String code, int status) {
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
