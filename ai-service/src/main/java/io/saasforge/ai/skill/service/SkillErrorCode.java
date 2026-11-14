package io.saasforge.ai.skill.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum SkillErrorCode implements ErrorCode {
    NOT_FOUND("skill.notFound", 404),
    UNAVAILABLE("skill.unavailable", 409),
    INVALID_CONTENT("skill.invalidContent", 400),
    CONFLICT("skill.conflict", 409),
    CAPACITY_EXCEEDED("skill.capacityExceeded", 409);
    private final String code;
    private final int status;

    SkillErrorCode(String code, int status) {
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
