package io.saasforge.ai.lifecycle.service;

import io.saasforge.ai.conversation.service.ConversationService;
import io.saasforge.ai.conversation.service.ConversationStreamingService;
import io.saasforge.ai.mcp.service.McpConfigurationService;
import io.saasforge.ai.model.service.ModelSelectionService;
import io.saasforge.ai.prompt.service.PromptService;
import io.saasforge.ai.skill.service.SkillService;
import io.saasforge.ai.usage.service.UsageService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import org.springframework.stereotype.Service;

/** SDK 唯一 TenantDeleted handler 的参与者；失败抛出并由同事务消费端回滚、重投。 */
@Service
public class AiTenantDataCleaner implements TenantDataCleaner {
    private final ConversationStreamingService streaming;
    private final ConversationService conversations;
    private final ModelSelectionService models;
    private final PromptService prompts;
    private final SkillService skills;
    private final McpConfigurationService mcp;
    private final UsageService usage;

    public AiTenantDataCleaner(
            ConversationStreamingService streaming,
            ConversationService conversations,
            ModelSelectionService models,
            PromptService prompts,
            SkillService skills,
            McpConfigurationService mcp,
            UsageService usage) {
        this.streaming = streaming;
        this.conversations = conversations;
        this.models = models;
        this.prompts = prompts;
        this.skills = skills;
        this.mcp = mcp;
        this.usage = usage;
    }

    public void clean(TenantDeleted event) {
        if (!CurrentTenant.require().equals(event.tenantId())) {
            throw new IllegalStateException("Tenant cleanup context mismatch");
        }
        // 必须先撤销运行能力并确认文件释放；失败不能提交数据库清理。
        streaming.stopTenant(event.tenantId());
        conversations.eraseTenantData();
        prompts.eraseTenantData();
        skills.eraseTenantData();
        mcp.eraseTenantData();
        models.eraseTenantData();
        usage.eraseTenantData();
    }
}
