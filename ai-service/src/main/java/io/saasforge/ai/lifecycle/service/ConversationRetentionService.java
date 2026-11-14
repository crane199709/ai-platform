package io.saasforge.ai.lifecycle.service;

import io.saasforge.ai.conversation.service.ConversationService;
import io.saasforge.ai.lifecycle.config.RetentionProperties;
import io.saasforge.ai.usage.service.UsagePlanSnapshot;
import io.saasforge.ai.usage.service.UsageService;
import io.saasforge.sdk.core.BusinessZoneProperties;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.scheduling.GlobalTask;
import io.saasforge.sdk.scheduling.SchedulingProperties;
import io.saasforge.sdk.tenant.TenantWriteGuard;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** 普通 TenantTask 不扫描停用租户；通过 IAM 公开服务目录逐租户进入保留期维护。 */
@Service
@EnableConfigurationProperties(RetentionProperties.class)
public class ConversationRetentionService {
    private static final ParameterizedTypeReference<ApiResponse<List<UUID>>> RESPONSE =
            new ParameterizedTypeReference<>() {};
    private final RestClient directory;
    private final RetentionProperties properties;
    private final UsageService usage;
    private final ConversationService conversations;
    private final TransactionTemplate transactions;
    private final ZoneId zone;

    public ConversationRetentionService(
            SchedulingProperties scheduling,
            RetentionProperties properties,
            UsageService usage,
            ConversationService conversations,
            PlatformTransactionManager manager,
            BusinessZoneProperties businessZone) {
        if (scheduling.iamUri() == null
                || scheduling.token() == null
                || scheduling.token().isBlank()) {
            throw new IllegalStateException("IAM directory configuration required for AI retention");
        }
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        directory = RestClient.builder()
                .baseUrl(scheduling.iamUri())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + scheduling.token())
                .build();
        this.properties = properties;
        this.usage = usage;
        this.conversations = conversations;
        this.transactions = new TransactionTemplate(manager);
        this.zone = businessZone.require("AI retention");
    }

    @GlobalTask(name = "ai-conversation-retention", fixedDelay = "1h")
    public void run() {
        for (int page = 1; ; page++) {
            var response = directory
                    .get()
                    .uri(
                            "/internal/tenants?status=pending,trial,active,suspended,expired,archived,deleted&page={page}&size=100",
                            page)
                    .retrieve()
                    .body(RESPONSE);
            if (response == null
                    || !ApiResponse.OK.equals(response.code())
                    || response.data() == null
                    || response.meta() == null
                    || response.meta().page() == null
                    || response.meta().page().number() != page
                    || response.meta().page().size() != 100) {
                throw new IllegalStateException("Invalid IAM retention directory page");
            }
            for (UUID tenant : response.data()) {
                CurrentTenant.run(
                        tenant,
                        () -> TenantWriteGuard.exempt(() -> {
                            // 只豁免到期正文清空，事务在绑定租户后开始，RLS 始终保留。
                            int count;
                            do {
                                count = transactions.execute(status -> {
                                    int days = properties.days(
                                            tenant,
                                            usage.currentPlan()
                                                    .map(UsagePlanSnapshot::planCode)
                                                    .orElse(null));
                                    return conversations.expire(ZonedDateTime.now(zone)
                                            .minusDays(days)
                                            .toOffsetDateTime());
                                });
                            } while (count == 100);
                        }));
            }
            if (page >= response.meta().page().totalPages()) {
                return;
            }
        }
    }
}
