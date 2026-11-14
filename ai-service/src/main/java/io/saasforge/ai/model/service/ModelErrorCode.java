package io.saasforge.ai.model.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum ModelErrorCode implements ErrorCode {
    NOT_FOUND("model.notFound", 404),
    UNAVAILABLE("model.unavailable", 409),
    INVALID_ENDPOINT("model.invalidEndpoint", 400),
    DUPLICATE("model.duplicate", 409),
    SELECTION_CONFLICT("model.selectionConflict", 409);
    private final String code;
    private final int status;

    ModelErrorCode(String code, int status) {
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
