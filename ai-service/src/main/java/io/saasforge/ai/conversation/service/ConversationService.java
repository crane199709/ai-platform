package io.saasforge.ai.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.ai.conversation.entity.Conversation;
import io.saasforge.ai.conversation.entity.ConversationMessage;
import io.saasforge.ai.conversation.entity.ConversationTurn;
import io.saasforge.ai.conversation.mapper.ConversationMapper;
import io.saasforge.ai.conversation.mapper.ConversationMessageMapper;
import io.saasforge.ai.conversation.mapper.ConversationTurnMapper;
import io.saasforge.ai.conversation.model.ConversationResponse;
import io.saasforge.ai.conversation.model.CreateConversationRequest;
import io.saasforge.ai.mcp.service.McpSelectionService;
import io.saasforge.ai.model.service.ModelSelectionService;
import io.saasforge.ai.prompt.service.PromptService;
import io.saasforge.ai.runner.service.AgentRuntime;
import io.saasforge.ai.skill.service.SkillService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.TenantContext;
import io.saasforge.sdk.web.PageQuery;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ConversationService {
    private final ConversationMapper conversations;
    private final AgentRuntime runtime;
    private final ConversationMessageMapper messages;
    private final ModelSelectionService models;
    private final PromptService prompts;
    private final ConversationTurnMapper turns;
    private final SkillService skills;
    private final McpSelectionService mcp;
    private final JsonMapper json = JsonMapper.builder().build();

    public ConversationService(
            ConversationMapper conversations,
            ConversationMessageMapper messages,
            ModelSelectionService models,
            PromptService prompts,
            ConversationTurnMapper turns,
            SkillService skills,
            McpSelectionService mcp,
            AgentRuntime runtime) {
        this.runtime = runtime;
        this.mcp = mcp;
        this.skills = skills;
        this.turns = turns;
        this.prompts = prompts;
        this.conversations = conversations;
        this.messages = messages;
        this.models = models;
    }

    @Transactional
    public ConversationResponse create(CreateConversationRequest request) {
        models.resolveForUse(request.modelId());
        var row = new Conversation();
        row.setOwnerId(TenantContext.current().identityId());
        row.setTitle(request.title());
        row.setModelId(request.modelId());
        var prompt = prompts.resolvePublished(
                request.promptTemplateId(),
                request.promptVersion(),
                request.variables() == null ? Map.of() : request.variables());
        if (prompt.content().length() > 128000) {
            throw new ForgeException(ConversationErrorCode.LIMIT);
        }
        row.setPromptTemplateId(prompt.templateId());
        row.setPromptVersion(prompt.version());
        row.setInstructions(prompt.content());
        row.setSkillSnapshot(json.writeValueAsString(skills.resolvePublished()));
        var toolSnapshots = mcp.snapshotForConversation();
        row.setMcpSnapshot(json.writeValueAsString(toolSnapshots));
        if (json.writeValueAsBytes(Map.of(
                                "instructions",
                                row.getInstructions(),
                                "skills",
                                json.readTree(row.getSkillSnapshot()),
                                "tools",
                                McpSelectionService.runtimeTools(toolSnapshots)))
                        .length
                > 900000) {
            throw new ForgeException(ConversationErrorCode.LIMIT);
        }
        conversations.insert(row);
        return response(row, true);
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<ConversationResponse>> list(String query, PageQuery page) {
        String pattern = query == null || query.isBlank()
                ? null
                : "%"
                        + query.strip()
                                .toLowerCase(Locale.ROOT)
                                .replace("!", "!!")
                                .replace("%", "!%")
                                .replace("_", "!_")
                        + "%";
        UUID owner = TenantContext.current().identityId();
        var rows = conversations.search(owner, pattern, page.size(), page.offset()).stream()
                .map(row -> response(row, false))
                .toList();
        return ApiResponse.page(rows, page.toPageMeta(conversations.countSearch(owner, pattern)));
    }

    public ConversationResponse get(UUID id) {
        return response(require(id, false), true);
    }

    @Transactional
    public ConversationResponse rename(UUID id, String title) {
        var row = require(id, true);
        row.setTitle(title);
        conversations.updateById(row);
        return response(row, true);
    }

    @Transactional
    public void delete(UUID id) {
        var row = require(id, true);
        if (row.getActiveTurnId() != null) {
            throw new ForgeException(ConversationErrorCode.BUSY);
        }
        erase(id);
    }

    public void eraseTenantData() {
        erase(null);
    }

    @Transactional
    public int expire(OffsetDateTime cutoff) {
        var rows = conversations.expired(cutoff);
        for (var row : rows) {
            erase(row.getId());
        }
        return rows.size();
    }

    private void erase(UUID id) {
        if (id != null) {
            runtime.deleteSession(CurrentTenant.require(), id);
        }
        conversations.eraseMessages(id);
        conversations.eraseTurns(id);
        conversations.eraseConversations(id);
    }

    public Conversation require(UUID id, boolean lock) {
        var row = lock ? conversations.lock(id) : conversations.selectById(id);
        if (row == null || !row.getOwnerId().equals(TenantContext.current().identityId())) {
            throw new ForgeException(ConversationErrorCode.NOT_FOUND);
        }
        return row;
    }

    public List<ConversationMessage> transcript(UUID id) {
        return messages.selectList(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, id)
                .orderByAsc(ConversationMessage::getCreatedAt, ConversationMessage::getId));
    }

    private ConversationResponse response(Conversation row, boolean detail) {
        var list = detail
                ? transcript(row.getId()).stream()
                        .map(m -> new ConversationResponse.MessageResponse(
                                m.getId(),
                                m.getTurnId(),
                                m.getRole(),
                                m.getContent(),
                                m.getToolCalls(),
                                m.getToolName(),
                                m.getCreatedAt()))
                        .toList()
                : List.<ConversationResponse.MessageResponse>of();
        return new ConversationResponse(
                row.getId(),
                row.getOwnerId(),
                row.getTitle(),
                row.getModelId(),
                row.getActiveTurnId(),
                row.getPromptTemplateId(),
                row.getPromptVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                list,
                detail
                        ? turns
                                .selectList(new LambdaQueryWrapper<ConversationTurn>()
                                        .eq(ConversationTurn::getConversationId, row.getId())
                                        .orderByAsc(ConversationTurn::getCreatedAt, ConversationTurn::getId))
                                .stream()
                                .map(t -> new ConversationResponse.TurnResponse(
                                        t.getId(),
                                        t.getStatus(),
                                        t.getModelId(),
                                        t.getErrorCode(),
                                        t.getInputTokens(),
                                        t.getOutputTokens(),
                                        t.getCachedInputTokens(),
                                        t.getUsageReported()))
                                .toList()
                        : List.of());
    }
}
