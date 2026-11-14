package io.saasforge.ai.runner.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum RunnerErrorCode implements ErrorCode {
    UNAVAILABLE("runner.unavailable", 502),
    LIMIT("runner.requestTooLarge", 413),
    INVALID_CAPABILITY("runner.invalidCapability", 401),
    SCOPE_DENIED("runner.scopeDenied", 403),
    MODEL_DENIED("runner.modelDenied", 403),
    SECRET_UNAVAILABLE("runner.secretUnavailable", 503),
    INVALID_MODEL_REQUEST("runner.invalidModelRequest", 400);
    private final String code;
    private final int status;

    RunnerErrorCode(String code, int status) {
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
