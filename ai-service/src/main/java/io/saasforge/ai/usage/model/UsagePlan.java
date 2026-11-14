package io.saasforge.ai.usage.model;

import java.util.UUID;

public record UsagePlan(UUID planId, String planCode, long planVersion, Long tokenQuota) {}
