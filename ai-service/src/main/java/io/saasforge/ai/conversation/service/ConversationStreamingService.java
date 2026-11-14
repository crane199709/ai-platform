package io.saasforge.ai.conversation.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.saasforge.ai.conversation.model.SendMessageRequest;
import io.saasforge.ai.mcp.service.McpSelectionService;
import io.saasforge.ai.mcp.service.McpToolApprovalService;
import io.saasforge.ai.mcp.service.McpToolSnapshot;
import io.saasforge.ai.runner.service.AgentRuntime;
import io.saasforge.ai.runner.service.RunnerCapabilityService;
import io.saasforge.ai.runner.service.RunnerErrorCode;
import io.saasforge.ai.skill.service.SkillSnapshot;
import io.saasforge.ai.usage.service.UsageService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.TenantContext;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ConversationStreamingService {
    private final MeterRegistry metrics;
    private final Semaphore slots = new Semaphore(8);
    private final ConversationTurnService turns;
    private final ConversationService conversations;
    private final AgentRuntime runtime;
    private final UsageService usage;
    private final RunnerCapabilityService capabilities;
    private final McpSelectionService mcp;
    private final AuditRecorder toolAudit;
    private final McpToolApprovalService approvals;
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                var t = new Thread(r, "conversation-stream");
                t.setDaemon(true);
                return t;
            });
    private final Tracer tracer;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "conversation-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final JsonMapper json = JsonMapper.builder().build();

    public ConversationStreamingService(
            ConversationTurnService turns,
            ConversationService conversations,
            AgentRuntime runtime,
            RunnerCapabilityService capabilities,
            ObjectProvider<Tracer> tracing,
            UsageService usage,
            McpSelectionService mcp,
            AuditRecorder toolAudit,
            McpToolApprovalService approvals,
            MeterRegistry metrics) {
        this.metrics = metrics;
        this.usage = usage;
        this.approvals = approvals;
        this.toolAudit = toolAudit;
        this.mcp = mcp;
        this.tracer = tracing.getIfAvailable();
        this.turns = turns;
        this.conversations = conversations;
        this.runtime = runtime;
        this.capabilities = capabilities;
        heartbeat.scheduleAtFixedRate(
                () -> jobs.values().forEach(job -> job.context(job::heartbeat)), 1, 1, TimeUnit.SECONDS);
    }

    public SseEmitter send(UUID id, SendMessageRequest request) {
        usage.recoverExpired();
        var prepared = turns.prepare(id, request);
        var emitter = new SseEmitter(130000L);
        if (prepared.replayed()) {
            try {
                boolean failed = "ERROR".equals(prepared.turn().getStatus());
                var replay = new LinkedHashMap<String, Object>();
                replay.put("turnId", prepared.turn().getId());
                replay.put("status", prepared.turn().getStatus());
                replay.put("replayed", true);
                replay.put("usageReported", prepared.turn().getUsageReported());
                if (failed) {
                    replay.put("code", prepared.turn().getErrorCode());
                    replay.put("retryable", false);
                }
                emitter.send(SseEmitter.event().name(failed ? "error" : "done").data(replay));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        if (!slots.tryAcquire()) {
            turns.finish(id, prepared.turn().getId(), "ERROR", "runner.poolFull", "", "[]", null, false);
            throw new ForgeException(RunnerErrorCode.POOL_FULL, 5);
        }
        var job = new Job(
                prepared,
                emitter,
                TenantContext.current(),
                SecurityContextHolder.getContext().getAuthentication(),
                MDC.getCopyOfContextMap());
        try {
            job.admit(false);
        } catch (RuntimeException error) {
            job.finish(
                    "ERROR",
                    error instanceof ForgeException f ? f.errorCode().code() : "conversation.runtimeUnavailable",
                    null,
                    false);
            throw error;
        }
        jobs.put(prepared.turn().getId(), job);
        emitter.onCompletion(() -> {
            job.disconnected.set(true);
            job.context(job::cancel);
        });
        emitter.onTimeout(() -> {
            job.disconnected.set(true);
            job.context(job::cancel);
        });
        emitter.onError(e -> {
            job.disconnected.set(true);
            job.context(job::cancel);
        });
        try {
            executor.execute(() -> job.context(job::run));
        } catch (RejectedExecutionException e) {
            job.context(() -> job.finish("ERROR", "runner.poolFull", null, false));
            throw new ForgeException(RunnerErrorCode.POOL_FULL, 5);
        }
        return emitter;
    }

    public boolean cancel(UUID id) {
        var conversation = conversations.require(id, false);
        var active = conversation.getActiveTurnId();
        if (active == null) {
            return false;
        }
        var job = jobs.get(active);
        if (job != null) {
            job.context(job::cancel);
        } else {
            runtime.cancel(TenantContext.current().tenantId(), id);
            turns.finish(id, active, "CANCELLED", null, "", "[]", null, false);
        }
        return true;
    }

    public void stopTenant(UUID tenantId) {
        jobs.values().stream()
                .filter(job -> job.tenant.tenantId().equals(tenantId))
                .forEach(job -> job.context(job::cancel));
        capabilities.revokeTenant(tenantId);
        runtime.deleteTenant(tenantId);
    }

    @PreDestroy
    public void close() {
        jobs.values().forEach(j -> j.context(j::cancel));
        heartbeat.shutdownNow();
        executor.shutdownNow();
    }

    private final class Job {
        private final ConversationTurnService.Prepared prepared;
        private final SseEmitter emitter;
        private final TenantContext tenant;
        private final Authentication authentication;
        private final Map<String, String> mdc;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile Thread worker;
        private final StringBuilder content = new StringBuilder();
        private final List<Map<String, Object>> tools = new ArrayList<>();
        private String token;
        private AgentRuntime.Session session;
        private String attemptId = UUID.randomUUID().toString();
        private final long startedNanos = System.nanoTime();
        private boolean firstTokenRecorded;
        private int recoveryCount;
        private long recoveryBytes;
        private boolean recoverRequested;
        private long callsBeforeAttempt;
        private final List<AgentRuntime.TranscriptMessage> attemptResults = new ArrayList<>();
        private List<McpToolSnapshot> authorizedToolSnapshots = List.of();
        private final Map<String, UUID> pendingSkillCalls = new LinkedHashMap<>();
        private List<AgentRuntime.TranscriptMessage> committed = List.of();
        private final TraceContext traceContext =
                tracer == null ? null : tracer.currentTraceContext().context();

        Job(
                ConversationTurnService.Prepared prepared,
                SseEmitter emitter,
                TenantContext tenant,
                Authentication authentication,
                Map<String, String> mdc) {
            this.prepared = prepared;
            this.emitter = emitter;
            this.tenant = tenant;
            this.authentication = authentication;
            this.mdc = mdc;
            recoveryBytes = 2 + recoveryAttemptBytes(attemptId);
        }

        void context(Runnable action) {
            var previous = SecurityContextHolder.getContext();
            var previousMdc = MDC.getCopyOfContextMap();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try (var ignored = CurrentTenant.bind(tenant.tenantId());
                    var traceScope =
                            tracer == null ? null : tracer.currentTraceContext().newScope(traceContext)) {
                action.run();
            } finally {
                SecurityContextHolder.setContext(previous);
                if (previousMdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousMdc);
                }
            }
        }

        void admit(boolean restoring) {
            authorizedToolSnapshots = mcp.authorizeSnapshots(
                    List.of(json.readValue(prepared.conversation().getMcpSnapshot(), McpToolSnapshot[].class)));
            var scopes = new HashSet<String>();
            scopes.add("model:chat");
            authorizedToolSnapshots.forEach(tool -> scopes.add(tool.scope()));
            token = capabilities.issue(
                    prepared.conversation().getId(),
                    prepared.turn().getId(),
                    prepared.turn().getModelId(),
                    scopes);
            approvals.register(
                    token,
                    tenant.tenantId(),
                    prepared.conversation().getId(),
                    event -> context(() -> send(event.type(), event.data())));
            var snapshots =
                    List.of(json.readValue(prepared.conversation().getSkillSnapshot(), SkillSnapshot[].class)).stream()
                            .map(snapshot -> Map.<String, Object>of(
                                    "catalogId",
                                    snapshot.catalogId().toString(),
                                    "code",
                                    snapshot.code(),
                                    "name",
                                    snapshot.name(),
                                    "description",
                                    snapshot.description(),
                                    "version",
                                    snapshot.version(),
                                    "content",
                                    snapshot.content()))
                            .toList();
            session = new AgentRuntime.Session(
                    tenant.tenantId(),
                    prepared.conversation().getId(),
                    prepared.turn().getModelId().toString(),
                    prepared.transcript(),
                    prepared.conversation().getInstructions(),
                    snapshots,
                    McpSelectionService.runtimeTools(authorizedToolSnapshots));
            if (restoring) {
                runtime.restore(session);
            } else {
                runtime.admit(session);
            }
        }

        void run() {
            worker = Thread.currentThread();
            if (terminal.get()) {
                return;
            }
            try {
                if (cancelRequested.get()) {
                    finish("CANCELLED", null, null, false);
                    return;
                }
                for (int execution = 0; execution < 2 && !terminal.get(); execution++) {
                    recoverRequested = false;
                    callsBeforeAttempt = usage.modelCallsSoFar(prepared.turn().getId());
                    try {
                        runtime.run(new AgentRuntime.Run(session, prepared.text(), token), this::event);
                    } catch (ForgeException error) {
                        if (error.errorCode() == RunnerErrorCode.DISCONNECTED && canRecover()) {
                            recoverRequested = true;
                        } else {
                            throw error;
                        }
                    }
                    if (terminal.get()) {
                        break;
                    }
                    if (recoverRequested && canRecover()) {
                        recover();
                        continue;
                    }
                    finish(
                            cancelRequested.get() ? "CANCELLED" : "ERROR",
                            cancelRequested.get() ? null : "conversation.interrupted",
                            null,
                            false);
                }
            } catch (RuntimeException e) {
                finish(
                        cancelRequested.get() ? "CANCELLED" : "ERROR",
                        cancelRequested.get() ? null : "conversation.runtimeUnavailable",
                        null,
                        false);
            } finally {
                capabilities.revoke(token);
                approvals.close(prepared.turn().getId());
                if (cancelRequested.get()) {
                    Thread.interrupted();
                }
            }
        }

        boolean canRecover() {
            return recoveryCount == 0
                    && !cancelRequested.get()
                    && !Thread.currentThread().isInterrupted()
                    && session.tools().stream().allMatch(tool -> Boolean.TRUE.equals(tool.get("readOnly")));
        }

        synchronized void recover() {
            capabilities.revoke(token);
            approvals.close(prepared.turn().getId());
            for (UUID invocation : pendingSkillCalls.values()) {
                auditSkill("ai.tool.call", invocation, "interrupted", "conversation.interrupted");
            }
            pendingSkillCalls.clear();
            String saved = turns.recordRecovery(
                    prepared.conversation().getId(),
                    prepared.turn().getId(),
                    attemptId,
                    content.toString(),
                    List.copyOf(tools),
                    List.copyOf(attemptResults));
            // 仅在真实持久化后校准；流式增量不反复解析或序列化已有结果。
            recoveryBytes = saved.getBytes(StandardCharsets.UTF_8).length;
            String nextAttempt = UUID.randomUUID().toString();
            if (!acceptRecovery(recoveryAttemptBytes(nextAttempt))) {
                return;
            }
            // 新授权或重建失败时保留当前 partial，不向浏览器宣称已经开始重新生成。
            admit(true);
            recoveryCount++;
            attemptId = nextAttempt;
            content.setLength(0);
            tools.clear();
            attemptResults.clear();
            committed = List.of();
            send(
                    "retrying",
                    Map.of(
                            "turnId",
                            prepared.turn().getId(),
                            "attempt",
                            recoveryCount + 1,
                            "reason",
                            "runner.recovered"));
        }

        synchronized void event(AgentRuntime.Event event) {
            if (terminal.get()) {
                return;
            }
            JsonNode data = event.data();
            if ("delta".equals(event.type())
                    || "tool.call".equals(event.type())
                    || "tool.result".equals(event.type())) {
                usage.observedExecution(prepared.turn().getId(), callsBeforeAttempt);
            }
            switch (event.type()) {
                case "attempt" -> {
                    if (data.path("retry").asBoolean()) {
                        usage.preserveUnsettled(prepared.turn().getId());
                    }
                    String nextAttempt = data.path("attemptId").asText();
                    if (!acceptRecovery(Math.max(0, jsonBytes(nextAttempt) - jsonBytes(attemptId)))) {
                        return;
                    }
                    attemptId = nextAttempt;
                }
                case "delta" -> {
                    String text = data.path("text").asText();
                    if (!text.isEmpty() && !firstTokenRecorded) {
                        firstTokenRecorded = true;
                        metrics.timer("ai.conversation.first.token")
                                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
                    }
                    if (content.length() + text.length() > 128000) {
                        cancel();
                        return;
                    }
                    long additional = jsonBytes(text) - 2;
                    // 分片可能在代理项中间断开；逐项预留转义上界，不能低估拼接后的 UTF-8。
                    for (int i = 0; i < text.length(); i++) {
                        if (Character.isSurrogate(text.charAt(i))) {
                            additional += 6;
                        }
                    }
                    if (!acceptRecovery(additional)) {
                        return;
                    }
                    content.append(text);
                    send("token", Map.of("turnId", prepared.turn().getId(), "text", text));
                }
                case "tool.call", "tool.result" -> {
                    String modelAttempt = data.path("attemptId").asText(attemptId);
                    if (event.type().equals("tool.call")) {
                        usage.recordToolCall(
                                prepared.turn().getId(),
                                modelAttempt + ":" + data.path("callId").asText());
                    }
                    var summary = new LinkedHashMap<String, Object>();
                    summary.put("phase", event.type());
                    summary.put("attemptId", modelAttempt);
                    if (data.has("name")) {
                        summary.put("name", data.path("name").asText());
                    }
                    if (data.has("callId")) {
                        summary.put("callId", data.path("callId").asText());
                    }
                    if (data.has("isError")) {
                        summary.put("isError", data.path("isError").asBoolean());
                    }
                    String name = data.path("name").asText();
                    String callId = data.path("callId").asText();
                    String scopedCallId = modelAttempt + ":" + callId;
                    if (!callId.isBlank() && callId.length() <= 256) {
                        if (event.type().equals("tool.call")
                                && name.equals("skill")
                                && !json.readTree(prepared.conversation().getSkillSnapshot())
                                        .isEmpty()
                                && !pendingSkillCalls.containsKey(scopedCallId)) {
                            UUID invocation = UuidV7.generate();
                            pendingSkillCalls.put(scopedCallId, invocation);
                            auditSkill("ai.tool.start", invocation, "started", null);
                        } else if (event.type().equals("tool.result") && (name.equals("skill") || name.isBlank())) {
                            UUID invocation = pendingSkillCalls.remove(scopedCallId);
                            if (invocation != null) {
                                auditSkill(
                                        "ai.tool.call",
                                        invocation,
                                        "completed",
                                        data.path("isError").asBoolean() ? "runner.toolFailure" : null);
                            }
                        }
                    }

                    for (var tool : authorizedToolSnapshots) {
                        if (name.equals(tool.runtimeName()) || name.equals("mcp__ai_service__" + tool.runtimeName())) {
                            summary.put("displayName", tool.nativeName());
                            summary.put(
                                    "summary",
                                    event.type().equals("tool.call")
                                            ? "正在执行只读查询"
                                            : data.path("isError").asBoolean() ? "工具执行失败" : "只读查询已完成");
                            break;
                        }
                    }
                    if (name.equals("skill")) {
                        summary.put("displayName", "技能");
                        summary.put(
                                "summary",
                                event.type().equals("tool.call")
                                        ? "正在调用已发布技能"
                                        : data.path("isError").asBoolean() ? "技能调用失败" : "技能调用已完成");
                    }
                    AgentRuntime.TranscriptMessage result = null;
                    long additional = jsonBytes(summary) + 1;
                    if (event.type().equals("tool.result")) {
                        result = new AgentRuntime.TranscriptMessage(
                                "tool",
                                data.path("content").asText(),
                                data.path("name").asText());
                        additional += jsonBytes(result) + 1;
                    }
                    if (!acceptRecovery(additional)) {
                        return;
                    }
                    if (result != null) {
                        attemptResults.add(result);
                    }
                    tools.add(summary);
                    send("tool", summary);
                }
                case "completed" -> {
                    if (data.path("transcript").isArray()) {
                        var messages = new ArrayList<AgentRuntime.TranscriptMessage>();
                        for (int i = prepared.transcript().size() + 1;
                                i < data.path("transcript").size();
                                i++) {
                            var item = data.path("transcript").get(i);
                            messages.add(new AgentRuntime.TranscriptMessage(
                                    item.path("role").asText(),
                                    item.path("content").asText(),
                                    item.hasNonNull("name") ? item.path("name").asText() : null));
                        }
                        committed = List.copyOf(messages);
                    }
                    finish(
                            "COMPLETED",
                            null,
                            data.path("usage"),
                            data.path("usageReported").asBoolean());
                }
                case "cancelled" -> finish("CANCELLED", null, null, false);
                case "error" -> {
                    if (data.path("code").asText().equals("runner.runtimeExited") && canRecover()) {
                        recoverRequested = true;
                    } else {
                        finish("ERROR", "conversation.runtimeUnavailable", null, false);
                    }
                }
                default -> {}
            }
        }

        private long jsonBytes(Object value) {
            return json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
        }

        private long recoveryAttemptBytes(String id) {
            // 每个数组元素多预留一个逗号；字段包络与生产快照保持一致。
            return jsonBytes(Map.of("attemptId", id, "content", "", "tools", List.of(), "results", List.of())) + 1;
        }

        private boolean acceptRecovery(long additional) {
            if (recoveryBytes + additional <= 2097152) {
                recoveryBytes += additional;
                return true;
            }
            // 超限事件尚未对外接受；先停执行，再用已接受数据正常终结与结算。
            try {
                runtime.cancel(tenant.tenantId(), prepared.conversation().getId());
            } finally {
                finish("ERROR", ConversationErrorCode.LIMIT.code(), null, false);
            }
            return false;
        }

        synchronized void cancel() {
            if (terminal.get()) {
                return;
            }
            cancelRequested.set(true);
            capabilities.revoke(token);
            if (worker != null && worker != Thread.currentThread()) {
                worker.interrupt();
            }
            runtime.cancel(tenant.tenantId(), prepared.conversation().getId());
            finish("CANCELLED", null, null, false);
        }

        private void auditSkill(String action, UUID invocation, String phase, String errorCode) {
            var event = AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, action)
                    .tenant(tenant.tenantId())
                    .actor(tenant.identityId())
                    .resource("skillToolCall", invocation)
                    .detail("conversationId", prepared.conversation().getId())
                    .detail("turnId", prepared.turn().getId())
                    .detail("toolType", "skill")
                    .detail("phase", phase);
            if (errorCode != null) {
                event.failure(errorCode);
            }
            toolAudit.record(event.build());
        }

        synchronized void finish(String status, String code, JsonNode usage, boolean reported) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            slots.release();
            metrics.counter("ai.conversation.turns", "status", status).increment();
            if (reported && usage != null) {
                metrics.counter("ai.conversation.tokens", "direction", "input")
                        .increment(Math.max(0, usage.path("inputTokens").asLong()));
                metrics.counter("ai.conversation.tokens", "direction", "output")
                        .increment(Math.max(0, usage.path("outputTokens").asLong()));
            }
            capabilities.revoke(token);
            approvals.close(prepared.turn().getId());
            for (UUID invocation : pendingSkillCalls.values()) {
                auditSkill("ai.tool.call", invocation, "interrupted", "conversation.interrupted");
            }
            pendingSkillCalls.clear();
            try {
                if (!status.equals("COMPLETED")
                        && (!attemptResults.isEmpty() || (recoveryCount > 0 && !content.isEmpty()))) {
                    turns.recordRecovery(
                            prepared.conversation().getId(),
                            prepared.turn().getId(),
                            attemptId,
                            content.toString(),
                            List.copyOf(tools),
                            List.copyOf(attemptResults));
                }
                turns.finish(
                        prepared.conversation().getId(),
                        prepared.turn().getId(),
                        status,
                        code,
                        content.toString(),
                        json.writeValueAsString(tools),
                        usage,
                        reported,
                        committed);
                if (code != null) {
                    send("error", Map.of("turnId", prepared.turn().getId(), "code", code, "retryable", false));
                } else {
                    var result = new LinkedHashMap<String, Object>();
                    result.put("turnId", prepared.turn().getId());
                    result.put("status", status);
                    result.put("usageReported", reported);
                    if (reported && usage != null) {
                        result.put("usage", usage);
                    }
                    send("done", result);
                }
            } catch (RuntimeException error) {
                send(
                        "error",
                        Map.of(
                                "turnId",
                                prepared.turn().getId(),
                                "code",
                                "conversation.persistenceFailure",
                                "retryable",
                                false));
            } finally {
                jobs.remove(prepared.turn().getId(), this);
                if (!disconnected.get()) {
                    emitter.complete();
                }
            }
        }

        synchronized void heartbeat() {
            if (terminal.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException error) {
                disconnected.set(true);
                cancel();
            }
        }

        void send(String type, Object body) {
            try {
                emitter.send(SseEmitter.event().name(type).data(body));
            } catch (IOException | IllegalStateException e) {
                disconnected.set(true);
                if (!terminal.get()) {
                    cancel();
                }
            }
        }
    }
}
