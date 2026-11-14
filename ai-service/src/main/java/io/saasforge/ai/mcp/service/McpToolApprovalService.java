package io.saasforge.ai.mcp.service;

import io.saasforge.ai.mcp.model.McpApprovalDecisionResponse;
import io.saasforge.ai.runner.service.RunnerCapabilityService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** 中立的活动回合审批接缝；只保存短期能力及决策元数据，不持有 Conversation 实现或工具参数。 */
@Service
public class McpToolApprovalService {
    private final RunnerCapabilityService capabilities;
    private final AuditRecorder audit;
    private final ConcurrentHashMap<UUID, Binding> bindings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Semaphore pendingSlots = new Semaphore(64);

    public McpToolApprovalService(RunnerCapabilityService capabilities, AuditRecorder audit) {
        this.capabilities = capabilities;
        this.audit = audit;
    }

    public void register(String token, UUID tenantId, UUID sessionId, Consumer<Event> events) {
        var context = capabilities.authorize(token, tenantId, sessionId, "model:chat");
        var binding = new Binding(token, context, events);
        if (bindings.putIfAbsent(context.turnId(), binding) != null) {
            throw new ForgeException(McpErrorCode.CONFLICT);
        }
    }

    public void close(UUID turnId) {
        var binding = bindings.remove(turnId);
        if (binding == null) {
            return;
        }
        binding.closed.set(true);
        pending.values().stream().filter(p -> p.binding == binding).forEach(p -> resolve(p, Status.CANCELLED));
    }

    public void await(String token, RunnerCapabilityService.GrantedContext context, McpToolSnapshot tool) {
        if (!tool.requiresApproval()) {
            return;
        }
        var binding = bindings.get(context.turnId());
        if (binding == null
                || binding.closed.get()
                || !binding.token.equals(token)
                || !binding.context.sessionId().equals(context.sessionId())
                || !binding.context.tenant().equals(context.tenant())) {
            throw new ForgeException(McpErrorCode.APPROVAL_UNAVAILABLE);
        }
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(20);
        if (context.expiresAt().isBefore(expires)) {
            expires = context.expiresAt();
        }
        if (!expires.isAfter(now)) {
            throw new ForgeException(McpErrorCode.APPROVAL_EXPIRED);
        }
        // 审批等待不能占满 Servlet 工作线程，须给所有者的决策请求保留处理空间。
        if (!pendingSlots.tryAcquire()) {
            throw new ForgeException(McpErrorCode.APPROVAL_UNAVAILABLE);
        }
        var request = new Pending(UuidV7.generate(), binding, tool, expires);
        pending.put(request.id, request);
        try {
            if (bindings.get(context.turnId()) != binding || binding.closed.get()) {
                resolve(request, Status.CANCELLED);
            } else {
                record(request, "ai.tool.approval.request", "PENDING");
                emit(
                        binding,
                        "tool.approval",
                        Map.of(
                                "approvalId",
                                request.id,
                                "conversationId",
                                context.sessionId(),
                                "turnId",
                                context.turnId(),
                                "toolName",
                                tool.runtimeName(),
                                "displayName",
                                tool.nativeName(),
                                "description",
                                tool.description(),
                                "expiresAt",
                                expires,
                                "status",
                                "PENDING"));
            }
            Status decision;
            try {
                decision = request.result.get(
                        Math.max(1, Duration.between(Instant.now(), expires).toMillis()), TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                resolve(request, Status.EXPIRED);
                decision = request.status.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                resolve(request, Status.CANCELLED);
                throw new ForgeException(McpErrorCode.APPROVAL_UNAVAILABLE);
            } catch (Exception error) {
                resolve(request, Status.CANCELLED);
                throw new ForgeException(McpErrorCode.APPROVAL_UNAVAILABLE);
            }
            if (decision == Status.REJECTED) {
                throw new ForgeException(McpErrorCode.APPROVAL_REJECTED);
            }
            if (decision == Status.EXPIRED) {
                throw new ForgeException(McpErrorCode.APPROVAL_EXPIRED);
            }
            if (decision != Status.APPROVED) {
                throw new ForgeException(McpErrorCode.APPROVAL_UNAVAILABLE);
            }
        } finally {
            pending.remove(request.id, request);
            pendingSlots.release();
        }
    }

    public McpApprovalDecisionResponse decide(UUID id, boolean approved) {
        var request = pending.get(id);
        var actor = TenantContext.current();
        if (request == null
                || !request.binding.context.tenant().tenantId().equals(actor.tenantId())
                || !request.binding.context.tenant().identityId().equals(actor.identityId())
                || !request.binding.context.tenant().membershipId().equals(actor.membershipId())) {
            throw new ForgeException(McpErrorCode.NOT_FOUND);
        }
        var context = request.binding.context;
        if (bindings.get(context.turnId()) != request.binding || request.binding.closed.get()) {
            throw new ForgeException(McpErrorCode.APPROVAL_UNAVAILABLE);
        }
        if (!Instant.now().isBefore(request.expires)) {
            resolve(request, Status.EXPIRED);
            throw new ForgeException(McpErrorCode.APPROVAL_EXPIRED);
        }
        capabilities.authorize(request.binding.token, actor.tenantId(), context.sessionId(), request.tool.scope());
        Status status = approved ? Status.APPROVED : Status.REJECTED;
        if (!resolve(request, status)) {
            throw new ForgeException(McpErrorCode.APPROVAL_UNAVAILABLE);
        }
        return new McpApprovalDecisionResponse(id, status.name());
    }

    private boolean resolve(Pending request, Status status) {
        if (!request.status.compareAndSet(Status.PENDING, status)) {
            return false;
        }
        try {
            record(request, "ai.tool.approval.decision", status.name());
            var context = request.binding.context;
            emit(
                    request.binding,
                    "tool.approval.resolved",
                    Map.of(
                            "approvalId",
                            request.id,
                            "conversationId",
                            context.sessionId(),
                            "turnId",
                            context.turnId(),
                            "status",
                            status.name()));
        } finally {
            request.result.complete(status);
        }
        return true;
    }

    private void emit(Binding binding, String type, Map<String, Object> data) {
        try {
            binding.events.accept(new Event(type, data));
        } catch (RuntimeException error) {
            close(binding.context.turnId());
        }
    }

    private void record(Pending request, String action, String status) {
        var context = request.binding.context;
        audit.record(AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, action)
                .tenant(context.tenant().tenantId())
                .actor(context.tenant().identityId())
                .resource("mcpToolApproval", request.id)
                .detail("conversationId", context.sessionId())
                .detail("turnId", context.turnId())
                .detail("toolName", request.tool.runtimeName())
                .detail("status", status)
                .build());
    }

    public record Event(String type, Map<String, Object> data) {}

    private enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED,
        CANCELLED
    }

    private static final class Binding {
        final String token;
        final RunnerCapabilityService.GrantedContext context;
        final Consumer<Event> events;
        final AtomicBoolean closed = new AtomicBoolean();

        Binding(String token, RunnerCapabilityService.GrantedContext context, Consumer<Event> events) {
            this.token = token;
            this.context = context;
            this.events = events;
        }
    }

    private static final class Pending {
        final UUID id;
        final Binding binding;
        final McpToolSnapshot tool;
        final Instant expires;
        final CompletableFuture<Status> result = new CompletableFuture<>();
        final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);

        Pending(UUID id, Binding binding, McpToolSnapshot tool, Instant expires) {
            this.id = id;
            this.binding = binding;
            this.tool = tool;
            this.expires = expires;
        }
    }
}
