package io.saasforge.ai.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.ai.conversation.entity.Conversation;
import io.saasforge.ai.conversation.entity.ConversationMessage;
import io.saasforge.ai.conversation.entity.ConversationTurn;
import io.saasforge.ai.conversation.mapper.ConversationMapper;
import io.saasforge.ai.conversation.mapper.ConversationMessageMapper;
import io.saasforge.ai.conversation.mapper.ConversationTurnMapper;
import io.saasforge.ai.conversation.model.SendMessageRequest;
import io.saasforge.ai.model.service.ModelSelectionService;
import io.saasforge.ai.runner.service.AgentRuntime;
import io.saasforge.ai.usage.service.UsageService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ConversationTurnService {
    private final ConversationService conversations;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messages;
    private final ConversationTurnMapper turns;
    private final ModelSelectionService models;
    private final AuditRecorder audit;
    private final UsageService usage;
    private final JsonMapper json = JsonMapper.builder().build();

    public ConversationTurnService(
            ConversationService conversations,
            ConversationMapper conversationMapper,
            ConversationMessageMapper messages,
            ConversationTurnMapper turns,
            ModelSelectionService models,
            AuditRecorder audit,
            UsageService usage) {
        this.conversations = conversations;
        this.conversationMapper = conversationMapper;
        this.messages = messages;
        this.turns = turns;
        this.models = models;
        this.audit = audit;
        this.usage = usage;
    }

    @Transactional
    public Prepared prepare(UUID id, SendMessageRequest request) {
        var conversation = conversations.require(id, true);
        UUID model = request.modelId() == null ? conversation.getModelId() : request.modelId();
        var old = turns.selectOne(new LambdaQueryWrapper<ConversationTurn>()
                .eq(ConversationTurn::getConversationId, id)
                .eq(ConversationTurn::getIdempotencyKey, request.idempotencyKey()));
        var history = conversations.transcript(id);
        if (old != null) {
            UUID oldId = old.getId();
            var original = history.stream()
                    .filter(m -> m.getTurnId().equals(oldId) && m.getRole().equals("user"))
                    .findFirst()
                    .orElseThrow();
            if (!original.getContent().equals(request.text())
                    || (request.modelId() != null && !old.getModelId().equals(request.modelId()))) {
                throw new ForgeException(ConversationErrorCode.IDEMPOTENCY_CONFLICT);
            }
            if ("RUNNING".equals(old.getStatus())) {
                if (old.getCreatedAt().isBefore(OffsetDateTime.now().minusMinutes(3))) {
                    finish(id, old.getId(), "ERROR", "conversation.interrupted", "", "[]", null, false);
                    old = turns.selectById(old.getId());
                } else {
                    throw new ForgeException(ConversationErrorCode.BUSY);
                }
            }
            return new Prepared(conversation, old, List.of(), request.text(), true);
        }
        if (conversation.getActiveTurnId() != null) {
            var active = turns.selectById(conversation.getActiveTurnId());
            if (active != null
                    && active.getCreatedAt().isBefore(OffsetDateTime.now().minusMinutes(3))) {
                finish(id, active.getId(), "ERROR", "conversation.interrupted", "", "[]", null, false);
            } else {
                throw new ForgeException(ConversationErrorCode.BUSY);
            }
        }
        var modelConfig = models.resolveForUse(model);
        var transcript = history.stream()
                .map(m -> new AgentRuntime.TranscriptMessage(m.getRole(), m.getContent(), m.getToolName()))
                .toList();
        var check = Map.of(
                "transcript",
                transcript,
                "instructions",
                conversation.getInstructions(),
                "text",
                request.text(),
                "skills",
                json.readTree(conversation.getSkillSnapshot()));
        if (history.size() >= 998 || json.writeValueAsString(check).getBytes(StandardCharsets.UTF_8).length > 900000) {
            throw new ForgeException(ConversationErrorCode.LIMIT);
        }
        var turn = new ConversationTurn();
        turn.setId(UuidV7.generate());
        turn.setConversationId(id);
        turn.setIdempotencyKey(request.idempotencyKey());
        turn.setStatus("RUNNING");
        turn.setModelId(model);
        turn.setUsageReported(false);
        usage.admit(turn.getId(), model, modelConfig.contextWindow());
        turns.insert(turn);
        var user = new ConversationMessage();
        user.setConversationId(id);
        user.setTurnId(turn.getId());
        user.setRole("user");
        user.setContent(request.text());
        user.setToolCalls("[]");
        messages.insert(user);
        if (!Objects.equals(conversation.getModelId(), model)) {
            record("ai.model.switch", id);
        }
        conversation.setModelId(model);
        conversation.setActiveTurnId(turn.getId());
        conversationMapper.updateById(conversation);
        record("ai.conversation.start", id);
        return new Prepared(conversation, turn, transcript, request.text(), false);
    }
    /** 终态、消息与标准用量在同一事务提交；重复终态不追加消息或审计。 */
    @Transactional
    public boolean finish(
            UUID conversationId,
            UUID turnId,
            String status,
            String code,
            String content,
            String toolCalls,
            JsonNode usage,
            boolean usageReported) {
        return finish(conversationId, turnId, status, code, content, toolCalls, usage, usageReported, List.of());
    }

    @Transactional
    public boolean finish(
            UUID conversationId,
            UUID turnId,
            String status,
            String code,
            String content,
            String toolCalls,
            JsonNode usage,
            boolean usageReported,
            List<AgentRuntime.TranscriptMessage> committed) {
        var conversation = conversations.require(conversationId, true);
        var turn = turns.selectById(turnId);
        if (turn == null || !turn.getConversationId().equals(conversationId) || !"RUNNING".equals(turn.getStatus())) {
            return false;
        }
        turn.setStatus(status);
        turn.setErrorCode(code);
        usageReported = usageReported
                && usage != null
                && usage.path("inputTokens").isIntegralNumber()
                && usage.path("outputTokens").isIntegralNumber()
                && usage.path("inputTokens").asLong() >= 0
                && usage.path("outputTokens").asLong() >= 0
                && (!usage.has("cachedInputTokens")
                        || (usage.path("cachedInputTokens").isIntegralNumber()
                                && usage.path("cachedInputTokens").asLong() >= 0
                                && usage.path("cachedInputTokens").asLong()
                                        <= usage.path("inputTokens").asLong()));
        turn.setUsageReported(usageReported);
        if (usageReported) {
            turn.setInputTokens(usage.path("inputTokens").asLong());
            turn.setOutputTokens(usage.path("outputTokens").asLong());
            if (usage.has("cachedInputTokens")) {
                turn.setCachedInputTokens(usage.path("cachedInputTokens").asLong());
            }
        }
        long toolCount = 0;
        for (var item : json.readTree(toolCalls)) {
            if ("tool.call".equals(item.path("phase").asText())) {
                toolCount++;
            }
        }
        this.usage.finish(
                turnId,
                turn.getInputTokens(),
                turn.getOutputTokens(),
                turn.getCachedInputTokens(),
                usageReported,
                toolCount,
                status);
        turns.updateById(turn);
        if (!committed.isEmpty()) {
            for (var item : committed) {
                if (!"assistant".equals(item.role()) && !"tool".equals(item.role())) {
                    throw new ForgeException(ConversationErrorCode.RUNTIME);
                }
                var row = new ConversationMessage();
                row.setConversationId(conversationId);
                row.setTurnId(turnId);
                row.setRole(item.role());
                row.setContent(item.content());
                row.setToolName(item.name());
                row.setToolCalls("assistant".equals(item.role()) ? toolCalls : "[]");
                messages.insert(row);
            }
        } else if (!content.isEmpty() || !"[]".equals(toolCalls)) {
            var assistant = new ConversationMessage();
            assistant.setConversationId(conversationId);
            assistant.setTurnId(turnId);
            assistant.setRole("assistant");
            assistant.setContent(content);
            assistant.setToolCalls(toolCalls);
            messages.insert(assistant);
        }
        if (turnId.equals(conversation.getActiveTurnId())) {
            conversation.setActiveTurnId(null);
            conversationMapper.updateById(conversation);
        }
        record("ai.conversation.end", conversationId);
        return true;
    }

    public ConversationTurn get(UUID conversationId, UUID turnId) {
        conversations.require(conversationId, false);
        var turn = turns.selectById(turnId);
        if (turn == null || !turn.getConversationId().equals(conversationId)) {
            throw new ForgeException(ConversationErrorCode.NOT_FOUND);
        }
        return turn;
    }

    private void record(String action, UUID id) {
        var context = TenantContext.current();
        audit.record(AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, action)
                .tenant(context.tenantId())
                .actor(context.identityId())
                .resource("conversation", id)
                .build());
    }

    public record Prepared(
            Conversation conversation,
            ConversationTurn turn,
            List<AgentRuntime.TranscriptMessage> transcript,
            String text,
            boolean replayed) {}
}
