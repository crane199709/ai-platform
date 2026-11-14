package io.saasforge.ai.prompt.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum PromptErrorCode implements ErrorCode {
    NOT_FOUND("prompt.notFound", 404),
    NOT_PUBLISHED("prompt.notPublished", 409),
    INVALID_VARIABLES("prompt.invalidVariables", 400),
    READ_ONLY("prompt.readOnly", 403);
    private final String code;
    private final int status;

    PromptErrorCode(String code, int status) {
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
