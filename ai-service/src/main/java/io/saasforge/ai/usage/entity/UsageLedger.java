package io.saasforge.ai.usage.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.time.LocalDate;
import java.util.UUID;

@TableName("ai_usage_ledger")
public class UsageLedger extends AuditableEntity {
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID value) {
        id = value;
    }

    private UUID modelId;

    public UUID getModelId() {
        return modelId;
    }

    public void setModelId(UUID value) {
        modelId = value;
    }

    private LocalDate usageDate;

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public void setUsageDate(LocalDate value) {
        usageDate = value;
    }

    private LocalDate billingMonth;

    public LocalDate getBillingMonth() {
        return billingMonth;
    }

    public void setBillingMonth(LocalDate value) {
        billingMonth = value;
    }

    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }

    private int contextWindow;

    public int getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(int value) {
        contextWindow = value;
    }

    private long modelCalls;

    public long getModelCalls() {
        return modelCalls;
    }

    public void setModelCalls(long value) {
        modelCalls = value;
    }

    private Long inputTokens;

    public Long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Long value) {
        inputTokens = value;
    }

    private Long outputTokens;

    public Long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Long value) {
        outputTokens = value;
    }

    private Long cachedInputTokens;

    public Long getCachedInputTokens() {
        return cachedInputTokens;
    }

    public void setCachedInputTokens(Long value) {
        cachedInputTokens = value;
    }

    private long priorEstimatedTokens;

    public long getPriorEstimatedTokens() {
        return priorEstimatedTokens;
    }

    public void setPriorEstimatedTokens(long value) {
        priorEstimatedTokens = value;
    }

    private long estimatedTokens;

    public long getEstimatedTokens() {
        return estimatedTokens;
    }

    public void setEstimatedTokens(long value) {
        estimatedTokens = value;
    }

    private String toolCallIds;

    public String getToolCallIds() {
        return toolCallIds;
    }

    public void setToolCallIds(String value) {
        toolCallIds = value;
    }

    private long toolCalls;

    public long getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(long value) {
        toolCalls = value;
    }

    private boolean unknownUsage;

    public boolean getUnknownUsage() {
        return unknownUsage;
    }

    public void setUnknownUsage(boolean value) {
        unknownUsage = value;
    }

    private String outcome;

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String value) {
        outcome = value;
    }
}
