package io.saasforge.ai.usage.service;

import io.saasforge.ai.usage.entity.UsageLedger;
import io.saasforge.ai.usage.mapper.UsageMapper;
import io.saasforge.ai.usage.model.*;
import io.saasforge.sdk.audit.*;
import io.saasforge.sdk.core.BusinessZoneProperties;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.security.OperatorContext;
import java.time.*;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class UsageService {
    private final JsonMapper json = JsonMapper.builder().build();
    private final UsageMapper mapper;
    private final EventPublisher events;
    private final ZoneId zone;
    private final AuditRecorder audit;
    private final TransactionTemplate recovery;

    public UsageService(
            UsageMapper mapper,
            EventPublisher events,
            BusinessZoneProperties properties,
            AuditRecorder audit,
            PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.events = events;
        this.zone = properties.require("AI usage");
        this.audit = audit;
        this.recovery = new TransactionTemplate(manager);
        this.recovery.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 必须在会话事务开始之前恢复，避免旧回合结算已持月份锁时再开启新事务等待自身。 */
    public void recoverExpired() {
        recover(LocalDate.now(zone).withDayOfMonth(1));
    }

    @Transactional
    public void admit(UUID turnId, UUID modelId, int contextWindow) {
        LocalDate date = LocalDate.now(zone);
        LocalDate month = date.withDayOfMonth(1);
        lock(month);
        var plan = mapper.plan();
        if (plan == null) {
            throw new ForgeException(UsageErrorCode.PLAN_UNAVAILABLE);
        }
        var totals = mapper.totals(month);
        if (plan.tokenQuota() != null) {
            if (totals.activeTurns() > 0) {
                throw new ForgeException(UsageErrorCode.BUSY);
            }
            if (totals.inputTokens() + totals.outputTokens() + totals.estimatedTokens() >= plan.tokenQuota()) {
                throw new ForgeException(UsageErrorCode.QUOTA);
            }
        }
        var row = new UsageLedger();
        row.setId(turnId);
        row.setModelId(modelId);
        row.setUsageDate(date);
        row.setBillingMonth(month);
        row.setContextWindow(contextWindow);
        row.setStatus("RUNNING");
        mapper.insert(row);
        publish(date);
    }
    /** 在鉴权、请求校验之后、首次向提供方发送之前调用；失败是否已产生费用不能确定时仍保守估计。 */
    @Transactional
    public void modelAttempt(UUID turnId) {
        var row = mapper.selectById(turnId);
        if (row == null || !"RUNNING".equals(row.getStatus())) {
            throw new ForgeException(UsageErrorCode.NOT_FOUND);
        }
        lock(row.getBillingMonth());
        row = mapper.selectById(turnId);
        if (!"RUNNING".equals(row.getStatus())) {
            throw new ForgeException(UsageErrorCode.NOT_FOUND);
        }
        row.setModelCalls(row.getModelCalls() + 1);
        row.setUnknownUsage(true);
        row.setEstimatedTokens(Math.addExact(row.getEstimatedTokens(), row.getContextWindow()));
        mapper.updateById(row);
        publish(row.getUsageDate());
    }
    /** 已经收到模型输出/工具事件也是执行证据；适用于可替换的 AgentRuntime。 */
    @Transactional
    public void observedExecution(UUID turnId) {
        var row = mapper.selectById(turnId);
        if (row != null && "RUNNING".equals(row.getStatus()) && row.getModelCalls() == 0) {
            modelAttempt(turnId);
        }
    }

    /** scopedCallId 只含运行尝试命名空间与工具标识；相同调用重送不重复计数。 */
    @Transactional
    public void recordToolCall(UUID turnId, String scopedCallId) {
        if (scopedCallId == null || scopedCallId.isBlank() || scopedCallId.length() > 512) {
            throw new ForgeException(UsageErrorCode.INVALID);
        }
        var row = mapper.selectById(turnId);
        if (row == null) {
            throw new ForgeException(UsageErrorCode.NOT_FOUND);
        }
        lock(row.getBillingMonth());
        row = mapper.selectById(turnId);
        if (!"RUNNING".equals(row.getStatus())) {
            return;
        }
        var ids = new LinkedHashSet<>(Arrays.asList(json.readValue(row.getToolCallIds(), String[].class)));
        if (!ids.add(scopedCallId)) {
            return;
        }
        row.setToolCallIds(json.writeValueAsString(ids));
        row.setToolCalls(row.getToolCalls() + 1);
        mapper.updateById(row);
        publish(row.getUsageDate());
    }

    @Transactional
    public void finish(
            UUID turnId, Long input, Long output, Long cached, boolean reported, long toolCalls, String outcome) {
        var row = mapper.selectById(turnId);
        if (row == null) {
            return;
        }
        lock(row.getBillingMonth());
        row = mapper.selectById(turnId);
        if ("FINISHED".equals(row.getStatus())) {
            return;
        }
        finishLocked(row, input, output, cached, reported, toolCalls, outcome);
    }

    private void finishLocked(
            UsageLedger row, Long input, Long output, Long cached, boolean reported, long tools, String outcome) {
        boolean complete = reported
                && input != null
                && output != null
                && input >= 0
                && output >= 0
                && (cached == null || (cached >= 0 && cached <= input));
        row.setStatus("FINISHED");
        row.setOutcome(outcome);
        row.setToolCalls(Math.max(row.getToolCalls(), tools));
        row.setUnknownUsage(!complete && row.getModelCalls() > 0);
        if (complete) {
            row.setInputTokens(input);
            row.setOutputTokens(output);
            row.setCachedInputTokens(cached);
            row.setEstimatedTokens(0);
        }
        mapper.updateById(row);
        publish(row.getUsageDate());
    }

    /** 没有收到可信事件时返回 empty，不把未知套餐变成已知不限。 */
    @Transactional(readOnly = true)
    public Optional<UsagePlanSnapshot> currentPlan() {
        var plan = mapper.plan();
        return plan == null
                ? Optional.empty()
                : Optional.of(
                        new UsagePlanSnapshot(plan.planId(), plan.planCode(), plan.planVersion(), plan.tokenQuota()));
    }

    @Transactional(readOnly = true)
    public UsageResponse read(String value) {
        YearMonth month = month(value);
        recover(month.atDay(1));
        return response(month);
    }

    private UsageResponse response(YearMonth month) {
        var totals = mapper.totals(month.atDay(1));
        var plan = mapper.plan();
        long total = totals.inputTokens() + totals.outputTokens();
        long charged = total + totals.estimatedTokens();
        Long quota = plan == null ? null : plan.tokenQuota();
        return new UsageResponse(
                month.toString(),
                zone.getId(),
                plan != null,
                plan == null ? null : plan.planVersion(),
                plan == null ? null : plan.planId(),
                plan == null ? null : plan.planCode(),
                quota,
                totals.inputTokens(),
                totals.outputTokens(),
                totals.cachedInputTokens(),
                total,
                totals.estimatedTokens(),
                charged,
                quota == null ? null : Math.max(0, quota - charged),
                totals.turnCount(),
                totals.toolCalls(),
                totals.unknownUsageTurns(),
                totals.activeTurns(),
                mapper.days(month.atDay(1), month.plusMonths(1).atDay(1)));
    }

    @Transactional
    public UsageResponse adjust(UUID turnId, EstimateRequest request) {
        var row = mapper.selectById(turnId);
        if (row == null) {
            throw new ForgeException(UsageErrorCode.NOT_FOUND);
        }
        lock(row.getBillingMonth());
        row = mapper.selectById(turnId);
        if (!"FINISHED".equals(row.getStatus()) || !row.getUnknownUsage()) {
            throw new ForgeException(UsageErrorCode.NOT_UNKNOWN);
        }
        long old = row.getEstimatedTokens();
        row.setEstimatedTokens(request.estimatedTokens());
        mapper.updateById(row);
        publish(row.getUsageDate());
        audit.record(AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.OPERATION, "ai.usage.adjustEstimate")
                .tenant(CurrentTenant.require())
                .actor(OperatorContext.current().operatorId())
                .resource("aiUsageTurn", turnId)
                .detail("tenantId", CurrentTenant.require())
                .detail("modelId", row.getModelId())
                .detail("modelCalls", row.getModelCalls())
                .detail("previousEstimatedTokens", old)
                .detail("estimatedTokens", request.estimatedTokens())
                .detail("reasonCode", request.reasonCode())
                .build());
        return response(YearMonth.from(row.getBillingMonth()));
    }

    private void recover(LocalDate month) {
        recovery.executeWithoutResult(status -> {
            lock(month);
            for (var stale : mapper.expired(month)) {
                finishLocked(stale, null, null, null, false, 0, "INTERRUPTED");
            }
        });
    }

    private void lock(LocalDate month) {
        mapper.ensureMonth(UuidV7.generate(), month);
        mapper.lockMonth(month);
    }

    private YearMonth month(String value) {
        try {
            return value == null ? YearMonth.now(zone) : YearMonth.parse(value);
        } catch (DateTimeException e) {
            throw new ForgeException(UsageErrorCode.INVALID);
        }
    }

    private void publish(LocalDate date) {
        mapper.rebuildDay(UuidV7.generate(), date);
        var d = mapper.day(date);
        events.publish(
                "forge-tenant",
                new AiUsageDailyReported(
                        CurrentTenant.require(),
                        date,
                        zone.getId(),
                        d.version(),
                        d.inputTokens(),
                        d.outputTokens(),
                        d.cachedInputTokens(),
                        d.turnCount(),
                        d.toolCalls(),
                        d.unknownUsageTurns(),
                        d.estimatedTokens(),
                        d.reportedAt()));
    }
}
