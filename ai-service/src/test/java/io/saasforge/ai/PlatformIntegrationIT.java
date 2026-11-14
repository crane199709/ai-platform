package io.saasforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.ai.contract.entity.ContractRecord;
import io.saasforge.ai.contract.mapper.ContractMapper;
import io.saasforge.ai.lifecycle.service.ConversationRetentionService;
import io.saasforge.ai.mcp.service.McpTransport;
import io.saasforge.ai.runner.service.AgentRuntime;
import io.saasforge.ai.runner.service.ProviderTransport;
import io.saasforge.ai.runner.service.RunnerCapabilityService;
import io.saasforge.ai.runner.service.RunnerErrorCode;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ErrorCode;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.manifest.ResourceManifestCollector;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantDeleted;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import io.saasforge.sdk.provisioning.TenantStatusChanged;
import io.saasforge.sdk.security.RequiresPermission;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 真实 IAM 的登录、授权与审计；Probe 及其表只在本测试上下文存在。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PlatformIntegrationIT.ProbeConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformIntegrationIT {
    static final UUID USAGE_TENANT = UUID.fromString("019a2525-0010-7000-8000-000000000001");
    static final UUID USAGE_MEMBER = UUID.fromString("019a2525-0010-7000-8000-000000000031");

    @Autowired
    EventPublisher usageEvents;

    @Autowired
    PlatformTransactionManager usageTransactions;

    static final UUID TENANT_A = UUID.fromString("019a2525-0000-7000-8000-000000000001");
    static final UUID MEMBER_A = UUID.fromString("019a2525-0000-7000-8000-000000000031");
    static final UUID MEMBER_B = UUID.fromString("019a2525-0000-7000-8000-000000000032");
    static final String IDENTITY = "019a2525-0000-7000-8000-000000000011";
    static final PlatformEnvironment platform = new PlatformEnvironment();
    static final AtomicReference<String> MODEL_SECRET = new AtomicReference<>("test-provider-secret");
    static final AtomicReference<String> MCP_SECRET = new AtomicReference<>("test-tenant-mcp-key-never-log");
    String tokenA;
    String tokenB;
    String operator;
    String tokenWithoutPermission;
    String readOnlyMember;
    String observer;
    String skillEditor;
    long usageInitialVersion;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", platform.postgres::getJdbcUrl);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:platform/migration");
        registry.add("spring.data.redis.host", platform.redis::getHost);
        registry.add("spring.data.redis.port", () -> platform.redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", platform.redis::database);
        registry.add("forge.security.issuer", platform::issuer);
        registry.add("forge.security.platform.issuer", () -> platform.issuer() + "/platform");
        registry.add("forge.security.platform.jwk-set-uri", () -> platform.iam() + "/platform/oauth2/jwks");
        registry.add("forge.security.jwk-set-uri", () -> platform.iam() + "/oauth2/jwks");
        registry.add("forge.manifest.iam-uri", platform::iam);
        registry.add("forge.manifest.token", () -> platform.aiToken);
        registry.add("forge.manifest.enabled", () -> true);
        registry.add("forge.ops.token", () -> PlatformEnvironment.OPS);
        registry.add("forge.mq.enabled", () -> true);
        registry.add("CONVERSATION_TEST_KEY", MODEL_SECRET::get);
        registry.add("ai.mcp.trusted-business-origins", () -> "https://business.example.test");
        registry.add(
                "ai.mcp.secret-references",
                () -> "AI_MCP_019A2525000070008000000000000001_019A2525090070008000000000000081");
        registry.add("AI_MCP_019A2525000070008000000000000001_019A2525090070008000000000000081", MCP_SECRET::get);
        registry.add("NON_MODEL_TEST_KEY", () -> "must-not-forward");
        registry.add("forge.mq.ssl", () -> false);
        registry.add("forge.mq.endpoints", platform::mqEndpoints);
        registry.add("forge.tenant.write-guard.enabled", () -> true);
        registry.add("ai.retention.plan-days.long-plan", () -> 180);
        registry.add("ai.retention.tenant-days[019a2525-0011-7000-8000-000000000100]", () -> 60);
    }

    @LocalServerPort
    int port;

    @BeforeAll
    void connectPlatform() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var status = call(
                    "http://127.0.0.1:" + port,
                    HttpMethod.GET,
                    "/internal/onboarding-status",
                    PlatformEnvironment.OPS,
                    null,
                    200);
            assertThat(status.path("data").path("phase").asText()).isEqualTo("REPORTED");
        });
        platform.postgres.script("postgres/platform/seed.sql");
        platform.publishSeededTenantState();
        platform.startGateway(port);
        runtime.serviceBase = "http://127.0.0.1:" + port;
        tokenA = login(false, MEMBER_A);
        tokenB = login(false, MEMBER_B);
        tokenWithoutPermission = login(false, UUID.fromString("019a2525-0000-7000-8000-000000000033"));
        operator = login(true, null);
        observer = login(true, null, "ai-observer");
        readOnlyMember = login(false, UUID.fromString("019a2525-0000-7000-8000-000000000034"), "ai-reader");
        skillEditor = login(false, UUID.fromString("019a2525-0000-7000-8000-000000000035"), "ai-skill-editor");
        var featurePack = new ArrayList<>(
                ResourceManifestCollector.collect(
                                "ai-service",
                                "test",
                                "io.saasforge.ai",
                                getClass().getClassLoader())
                        .permissions()
                        .stream()
                        .map(p -> p.code())
                        .toList());
        long bootstrapVersionA = tokenVersion(tokenA);
        long bootstrapVersionB = tokenVersion(tokenB);
        for (UUID tenant : List.of(TENANT_A, UUID.fromString("019a2525-0000-7000-8000-000000000002"))) {
            sendUsagePlan(new TenantPlanChanged(
                    tenant, UUID.randomUUID(), "known-legacy", featurePack, List.of("ai"), null, null, 1));
        }
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(tokenVersion(login(false, MEMBER_A))).isGreaterThan(bootstrapVersionA);
            assertThat(tokenVersion(login(false, MEMBER_B))).isGreaterThan(bootstrapVersionB);
            assertThat(usage(login(false, MEMBER_A)).path("quotaKnown").asBoolean())
                    .isTrue();
            assertThat(usage(login(false, MEMBER_B)).path("quotaKnown").asBoolean())
                    .isTrue();
        });
        tokenA = login(false, MEMBER_A);
        tokenB = login(false, MEMBER_B);
        readOnlyMember = login(false, UUID.fromString("019a2525-0000-7000-8000-000000000034"), "ai-reader");
        skillEditor = login(false, UUID.fromString("019a2525-0000-7000-8000-000000000035"), "ai-skill-editor");
        CurrentTenant.run(
                USAGE_TENANT,
                () -> new TransactionTemplate(usageTransactions)
                        .executeWithoutResult(status -> usageEvents.publish(
                                "forge-tenant",
                                new TenantCreated(USAGE_TENANT, "AI 用量验收租户", "ai-member@example.test", "active"))));
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var roles = call(platform.gateway(), HttpMethod.GET, "/iam/roles", login(false, USAGE_MEMBER), null, 200)
                    .path("data");
            assertThat(roles.toString()).contains("ai-user", "ai-admin");
            var user = StreamSupport.stream(roles.spliterator(), false)
                    .filter(r -> r.path("code").asText().equals("ai-user"))
                    .findFirst()
                    .orElseThrow();
            assertThat(StreamSupport.stream(user.path("permissions").spliterator(), false)
                            .map(JsonNode::asText)
                            .toList())
                    .containsExactlyInAnyOrder("ai:conversation:use", "ai:mcp:read");
            var admin = StreamSupport.stream(roles.spliterator(), false)
                    .filter(r -> r.path("code").asText().equals("ai-admin"))
                    .findFirst()
                    .orElseThrow();
            var declared = ResourceManifestCollector.collect(
                            "ai-service", "test", "io.saasforge.ai", getClass().getClassLoader())
                    .permissions()
                    .stream()
                    .map(p -> p.code())
                    .toList();
            assertThat(StreamSupport.stream(admin.path("permissions").spliterator(), false)
                            .map(JsonNode::asText)
                            .toList())
                    .containsExactlyInAnyOrderElementsOf(declared);
        });
        usageInitialVersion = tokenVersion(login(false, USAGE_MEMBER));
    }

    @AfterAll
    void stopPlatform() {
        platform.close();
    }

    @Test
    void planQuotaUsageReplayUnknownRecoveryAndRealPlatformDelivery() throws Exception {
        String unreadyToken = login(false, USAGE_MEMBER);
        String unreadyModel = createConversationModel();
        String unreadyId = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        unreadyToken,
                        Map.of("title", "Plan pending", "modelId", unreadyModel),
                        201)
                .path("data")
                .path("id")
                .asText();
        assertThat(usage(unreadyToken).path("quotaKnown").asBoolean()).isFalse();
        var pending = call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/conversations/" + unreadyId + "/messages",
                unreadyToken,
                Map.of(
                        "text",
                        "not admitted",
                        "idempotencyKey",
                        UUID.randomUUID().toString()),
                503);
        assertThat(pending.path("code").asText()).isEqualTo("usage.planUnavailable");
        var createPlan = new HashMap<String, Object>(usagePlanBody(11L));
        createPlan.put("code", "usage-" + UUID.randomUUID());
        String planId = call(platform.gateway(), HttpMethod.POST, "/platform/plans", operator, createPlan, 201)
                .path("data")
                .path("id")
                .asText();
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/platform/tenants/" + USAGE_TENANT + "/plan",
                operator,
                Map.of("planId", planId),
                200);
        awaitUsagePlan(1, 11L);
        call(platform.gateway(), HttpMethod.PUT, "/platform/plans/" + planId, operator, usagePlanBody(11L), 200);
        awaitUsagePlan(2, 11L);
        sendUsagePlan(new TenantPlanChanged(
                USAGE_TENANT,
                UUID.randomUUID(),
                "old",
                List.of("ai:conversation:use", "ai:configuration:manage"),
                List.of("ai"),
                null,
                null,
                1));
        sendUsagePlan(new TenantPlanChanged(
                USAGE_TENANT,
                UUID.randomUUID(),
                "duplicate",
                List.of("ai:conversation:use", "ai:configuration:manage"),
                List.of("ai"),
                null,
                null,
                2));
        String token = login(false, USAGE_MEMBER);
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        token,
                        Map.of("title", "Usage", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        String key = UUID.randomUUID().toString();
        assertThat(sendMessage(id, "known", key, token)).contains("event:done");
        assertThat(sendMessage(id, "known", key, token)).contains("replayed");
        var known = usage(token);
        assertThat(known.path("planVersion").asLong()).isEqualTo(2);
        assertThat(known.path("planId").asText()).isEqualTo(planId);
        assertThat(known.path("planCode").asText()).isEqualTo(createPlan.get("code"));
        assertThat(known.path("inputTokens").asLong()).isEqualTo(9);
        assertThat(known.path("outputTokens").asLong()).isEqualTo(2);
        assertThat(known.path("cachedInputTokens").asLong()).isEqualTo(1);
        assertThat(known.path("totalTokens").asLong()).isEqualTo(11);
        assertThat(known.path("turnCount").asLong()).isEqualTo(1);
        assertThat(known.path("toolCalls").asLong()).isEqualTo(1);
        var denied = call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/conversations/" + id + "/messages",
                token,
                Map.of("text", "blocked", "idempotencyKey", UUID.randomUUID().toString()),
                429);
        assertThat(denied.path("code").asText()).isEqualTo("usage.quotaExceeded");
        assertThat(usage(token).path("turnCount").asLong()).isEqualTo(1);
        String month = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var received = call(
                            platform.gateway(),
                            HttpMethod.GET,
                            "/platform/tenants/" + USAGE_TENANT + "/ai-usage?month=" + month,
                            operator,
                            null,
                            200)
                    .path("data");
            assertThat(received.path("totalTokens").asLong()).isEqualTo(11);
            assertThat(received.path("turnCount").asLong()).isEqualTo(1);
        });
        call(platform.gateway(), HttpMethod.PUT, "/platform/plans/" + planId, operator, usagePlanBody(100L), 200);
        awaitUsagePlan(3, 100L);
        token = login(false, USAGE_MEMBER);
        String activeToken = token;
        String other = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        token,
                        Map.of("title", "Concurrent", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        var future = HttpClient.newHttpClient()
                .sendAsync(
                        HttpRequest.newBuilder(URI.create(platform.gateway() + "/ai/conversations/" + id + "/messages"))
                                .header("Authorization", "Bearer " + token)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"text\":\"slow\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var running = usage(activeToken);
            assertThat(running.path("activeTurns").asLong()).isEqualTo(1);
            assertThat(running.path("estimatedTokens").asLong()).isEqualTo(65536);
        });
        call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/conversations/" + other + "/messages",
                token,
                Map.of("text", "concurrent", "idempotencyKey", UUID.randomUUID().toString()),
                409);
        call(platform.gateway(), HttpMethod.POST, "/ai/conversations/" + id + "/cancel", token, null, 200);
        String cancelled = future.get(10, java.util.concurrent.TimeUnit.SECONDS).body();
        assertThat(cancelled).contains("CANCELLED");
        var unknown = usage(token);
        assertThat(unknown.path("unknownUsageTurns").asLong()).isEqualTo(1);
        assertThat(unknown.path("estimatedTokens").asLong()).isEqualTo(65536);
        call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/conversations/" + other + "/messages",
                token,
                Map.of(
                        "text",
                        "blocked unknown",
                        "idempotencyKey",
                        UUID.randomUUID().toString()),
                429);
        String turnId = capabilityTurnId(cancelled);
        String adjust = "/ai-platform/usage/tenants/" + USAGE_TENANT + "/turns/" + turnId + "/estimate";
        var adjustment = Map.of("estimatedTokens", 20, "reasonCode", "PROVIDER_RECONCILIATION");
        call(platform.gateway(), HttpMethod.PUT, adjust, token, adjustment, 401);
        call(platform.gateway(), HttpMethod.PUT, adjust, observer, adjustment, 403);
        var corrected = call(platform.gateway(), HttpMethod.PUT, adjust, operator, adjustment, 200)
                .path("data");
        assertThat(corrected.path("estimatedTokens").asLong()).isEqualTo(20);
        assertThat(corrected.path("unknownUsageTurns").asLong()).isEqualTo(1);
        assertThat(sendMessage(other, "recovered", UUID.randomUUID().toString(), token))
                .contains("event:done");
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var audits = call(
                            platform.gateway(),
                            HttpMethod.GET,
                            "/iam/platform/audit/events?action=ai.usage.adjustEstimate&resourceId=" + turnId,
                            operator,
                            null,
                            200)
                    .path("data");
            assertThat(audits).hasSize(1);
            assertThat(audits.get(0)
                            .path("detail")
                            .path("previousEstimatedTokens")
                            .asLong())
                    .isEqualTo(65536);
            assertThat(audits.toString()).doesNotContain("slow", "secret", "contextToken");
            var received = call(
                            platform.gateway(),
                            HttpMethod.GET,
                            "/platform/tenants/" + USAGE_TENANT + "/ai-usage?month=" + month,
                            operator,
                            null,
                            200)
                    .path("data");
            assertThat(received.path("totalTokens").asLong()).isEqualTo(22);
            assertThat(received.path("estimatedTokens").asLong()).isEqualTo(20);
            assertThat(received.path("unknownUsageTurns").asLong()).isEqualTo(1);
        });
        sendUsagePlan(new TenantPlanChanged(
                USAGE_TENANT,
                UUID.randomUUID(),
                "historic",
                List.of("ai:conversation:use", "ai:configuration:manage"),
                List.of("ai"),
                null,
                null,
                4));
        awaitUsagePlan(4, null);
    }

    private Map<String, Object> usagePlanBody(Long quota) {
        var body = new HashMap<String, Object>();
        body.put("name", "AI 用量验收套餐");
        body.put("permissions", List.of("ai:conversation:use", "ai:configuration:manage"));
        body.put("subApps", List.of("ai"));
        body.put("tokenQuota", quota);
        return body;
    }

    @Test
    void roleTemplatesProvisionRealIamRolesAndMatchDeclaredPermissions() {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            String token = login(false, USAGE_MEMBER);
            var roles = call(platform.gateway(), HttpMethod.GET, "/iam/roles", token, null, 200)
                    .path("data");
            var user = StreamSupport.stream(roles.spliterator(), false)
                    .filter(r -> r.path("code").asText().equals("ai-user"))
                    .findFirst()
                    .orElseThrow();
            var admin = StreamSupport.stream(roles.spliterator(), false)
                    .filter(r -> r.path("code").asText().equals("ai-admin"))
                    .findFirst()
                    .orElseThrow();
            assertThat(StreamSupport.stream(user.path("permissions").spliterator(), false)
                            .map(JsonNode::asText)
                            .toList())
                    .contains("ai:conversation:use")
                    .isSubsetOf("ai:conversation:use", "ai:mcp:read");
            var declared = ResourceManifestCollector.collect(
                            "ai-service", "test", "io.saasforge.ai", getClass().getClassLoader())
                    .permissions()
                    .stream()
                    .map(p -> p.code())
                    .toList();
            assertThat(StreamSupport.stream(admin.path("permissions").spliterator(), false)
                            .map(JsonNode::asText)
                            .toList())
                    .contains("ai:conversation:use", "ai:configuration:manage")
                    .isSubsetOf(declared);
        });
    }

    private long tokenVersion(String token) {
        return JsonMapper.builder()
                .build()
                .readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]))
                .path("ver")
                .asLong();
    }

    private String capabilityTurnId(String sse) {
        for (String line : sse.split("\n")) {
            if (line.startsWith("data:")) {
                var node = JsonMapper.builder().build().readTree(line.substring(5));
                if (node.has("turnId")) {
                    return node.path("turnId").asText();
                }
            }
        }
        throw new AssertionError("Missing turnId");
    }

    private JsonNode usage(String token) {
        return call(platform.gateway(), HttpMethod.GET, "/ai/usage", token, null, 200)
                .path("data");
    }

    private void sendUsagePlan(TenantPlanChanged event) {
        CurrentTenant.run(
                event.tenantId(),
                () -> new TransactionTemplate(usageTransactions)
                        .executeWithoutResult(status -> usageEvents.publish("forge-tenant", event)));
    }

    private void awaitUsagePlan(long version, Long quota) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            String token = login(false, USAGE_MEMBER);
            assertThat(tokenVersion(token)).isGreaterThanOrEqualTo(usageInitialVersion + version);
            var result = usage(token);
            assertThat(result.path("planVersion").asLong()).isEqualTo(version);
            if (quota == null) {
                assertThat(result.path("tokenQuota").isNull()).isTrue();
            } else {
                assertThat(result.path("tokenQuota").asLong()).isEqualTo(quota);
            }
        });
    }

    @Test
    @Sql("/platform/usage-expired.sql")
    void expiredReservationsRecoverWithoutInventingUsageAndStayInTheirBusinessMonth() {
        var recovered = call(platform.gateway(), HttpMethod.GET, "/ai/usage?month=2026-09", tokenA, null, 200)
                .path("data");
        assertThat(recovered.path("activeTurns").asLong()).isZero();
        assertThat(recovered.path("turnCount").asLong()).isEqualTo(2);
        assertThat(recovered.path("toolCalls").asLong()).isEqualTo(2);
        assertThat(recovered.path("unknownUsageTurns").asLong()).isEqualTo(1);
        assertThat(recovered.path("totalTokens").asLong()).isZero();
        assertThat(recovered.path("estimatedTokens").asLong()).isEqualTo(100);
        var repeated = call(platform.gateway(), HttpMethod.GET, "/ai/usage?month=2026-09", tokenA, null, 200)
                .path("data");
        assertThat(repeated.path("days").get(0).path("version").asLong()).isEqualTo(2);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/usage?month=2026-09", tokenB, null, 200)
                        .path("data")
                        .path("turnCount")
                        .asLong())
                .isZero();
    }

    @Test
    void toolCallsArePersistedBeforeFinishAndDeduplicatedAcrossCallAndResultEvents() throws Exception {
        var before = usage(tokenA);
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Metered tool", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        var future = HttpClient.newHttpClient()
                .sendAsync(
                        HttpRequest.newBuilder(URI.create(platform.gateway() + "/ai/conversations/" + id + "/messages"))
                                .header("Authorization", "Bearer " + tokenA)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"text\":\"metered tool pause\",\"idempotencyKey\":\"" + UUID.randomUUID()
                                                + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        try {
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var running = usage(tokenA);
                assertThat(running.path("toolCalls").asLong())
                        .isEqualTo(before.path("toolCalls").asLong() + 1);
                assertThat(running.path("activeTurns").asLong()).isEqualTo(1);
            });
        } finally {
            call(platform.gateway(), HttpMethod.POST, "/ai/conversations/" + id + "/cancel", tokenA, null, 200);
        }
        assertThat(future.get(10, java.util.concurrent.TimeUnit.SECONDS).body()).contains("CANCELLED");
        assertThat(usage(tokenA).path("toolCalls").asLong())
                .isEqualTo(before.path("toolCalls").asLong() + 1);
    }

    @Test
    void unstartedRuntimeRejectionHasNoModelCost() throws Exception {
        var before = usage(tokenA);
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Pool refusal", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        assertThat(sendMessage(
                        id, "pool full before execution", UUID.randomUUID().toString(), tokenA))
                .contains("event:error");
        var after = usage(tokenA);
        assertThat(after.path("totalTokens").asLong())
                .isEqualTo(before.path("totalTokens").asLong());
        assertThat(after.path("estimatedTokens").asLong())
                .isEqualTo(before.path("estimatedTokens").asLong());
        assertThat(after.path("unknownUsageTurns").asLong())
                .isEqualTo(before.path("unknownUsageTurns").asLong());
        assertThat(after.path("turnCount").asLong())
                .isEqualTo(before.path("turnCount").asLong() + 1);
    }

    @Test
    void usageIsVisibleToTenantAdministratorsAndDeniedToOrdinaryMembers() {
        var usage = call(platform.gateway(), HttpMethod.GET, "/ai/usage?month=2026-11", tokenA, null, 200)
                .path("data");
        assertThat(usage.path("businessZone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(usage.path("month").asText()).isEqualTo("2026-11");
        call(platform.gateway(), HttpMethod.GET, "/ai/usage?month=2026-11", readOnlyMember, null, 403);
        call(platform.gateway(), HttpMethod.GET, "/ai/usage?month=invalid", tokenA, null, 400);
    }

    @Test
    void conversationsAreOwnedPersistentTenantIsolatedAndLogicallyDeleted() {
        String model = createConversationModel();
        String path = "/ai/conversations";
        var created = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        path,
                        tokenA,
                        Map.of("title", "Private conversation", "modelId", model),
                        201)
                .path("data");
        String id = created.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(created.path("ownerId").asText()).isEqualTo(IDENTITY);
        var read = call(platform.gateway(), HttpMethod.GET, path + "/" + id, tokenA, null, 200)
                .path("data");
        assertThat(read.path("title").asText()).isEqualTo("Private conversation");
        assertThat(read.path("messages")).isEmpty();
        call(platform.gateway(), HttpMethod.GET, path + "/" + id, tokenB, null, 404);
        call(platform.gateway(), HttpMethod.GET, path + "/" + id, readOnlyMember, null, 404);
        call(
                platform.gateway(),
                HttpMethod.POST,
                path,
                tokenWithoutPermission,
                Map.of("title", "Forbidden", "modelId", model),
                403);
        call(platform.gateway(), HttpMethod.PATCH, path + "/" + id, tokenA, Map.of("title", "Renamed"), 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, path, tokenA, null, 200)
                        .path("data")
                        .toString())
                .contains("Renamed", id);
        call(platform.gateway(), HttpMethod.DELETE, path + "/" + id, tokenA, null, 200);
        call(platform.gateway(), HttpMethod.GET, path + "/" + id, tokenA, null, 404);
    }

    @Test
    void searchesLiteralTitleAndBodyAndExportsOnlyVisibleTranscript() throws Exception {
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "中文 100%_归档", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        sendMessage(id, "唯一正文检索", UUID.randomUUID().toString(), tokenA);
        for (String query : List.of("100%_", "唯一正文检索")) {
            String path = "/ai/conversations?query=" + query + "&size=1";
            var result = call(platform.gateway(), HttpMethod.GET, path, tokenA, null, 200)
                    .path("data");
            assertThat(result).hasSize(1);
            assertThat(result.get(0).path("id").asText()).isEqualTo(id);
            assertThat(call(platform.gateway(), HttpMethod.GET, path, tokenB, null, 200)
                            .path("data"))
                    .isEmpty();
        }
        for (String format : List.of("markdown", "json")) {
            var response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create(platform.gateway() + "/ai/conversations/" + id
                                            + "/export?format=" + format))
                                    .header("Authorization", "Bearer " + tokenA)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Disposition").orElseThrow())
                    .contains("attachment");
            assertThat(response.body())
                    .contains("中文 100%_归档", "唯一正文检索", "hello world")
                    .doesNotContain("instructions", "skillSnapshot", "mcpSnapshot", "secretReference", "contextToken");
            call(
                    platform.gateway(),
                    HttpMethod.GET,
                    "/ai/conversations/" + id + "/export?format=" + format,
                    tokenB,
                    null,
                    404);
        }
        call(platform.gateway(), HttpMethod.DELETE, "/ai/conversations/" + id, tokenA, null, 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/conversations?query=100%_", tokenA, null, 200)
                        .path("data"))
                .isEmpty();
    }

    @Autowired
    ConversationRetentionService retention;

    @Autowired
    ContractMapper hardeningData;

    @Test
    void retentionRemovesExpiredConversationContentAndPreservesFreshHistory() throws Exception {
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "保留期已过", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        sendMessage(id, "crash partial", UUID.randomUUID().toString(), tokenA);
        UUID recoveredTurn =
                UUID.fromString(call(platform.gateway(), HttpMethod.GET, "/ai/conversations/" + id, tokenA, null, 200)
                        .path("data")
                        .path("turns")
                        .get(0)
                        .path("id")
                        .asText());
        CurrentTenant.run(
                TENANT_A,
                () -> assertThat(hardeningData.recoverySnapshot(recoveredTurn)).contains("abandoned partial"));
        CurrentTenant.run(
                TENANT_A,
                () -> new TransactionTemplate(usageTransactions)
                        .executeWithoutResult(status -> hardeningData.ageConversation(UUID.fromString(id))));
        String fresh = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Fresh retention", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        retention.run();
        call(platform.gateway(), HttpMethod.GET, "/ai/conversations/" + fresh, tokenA, null, 200);
        call(platform.gateway(), HttpMethod.GET, "/ai/conversations/" + id, tokenA, null, 404);
        CurrentTenant.run(
                TENANT_A,
                () -> assertThat(hardeningData.recoverySnapshot(recoveredTurn)).isEqualTo("[]"));
        CurrentTenant.run(
                TENANT_A,
                () -> assertThat(hardeningData.remainingConversationContent(UUID.fromString(id)))
                        .isZero());
        retention.run();
    }

    @Test
    void poolAdmissionReturnsActual429WithRetryAfterAndCompletedKeyDoesNotReadmit() throws Exception {
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Pool admission", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        String key = UUID.randomUUID().toString();
        runtime.capacityDenied.add(UUID.fromString(id));
        var response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(platform.gateway() + "/ai/conversations/" + id + "/messages"))
                                .header("Authorization", "Bearer " + tokenA)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(JsonMapper.builder()
                                        .build()
                                        .writeValueAsString(Map.of("text", "admission", "idempotencyKey", key))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After").orElseThrow()).isEqualTo("7");
        assertThat(response.body()).contains("runner.poolFull");
        runtime.capacityDenied.remove(UUID.fromString(id));
        assertThat(sendMessage(id, "admission", key, tokenA)).contains("replayed", "event:error");
        assertThat(runtime.requests.stream()
                        .filter(run -> run.session().sessionId().toString().equals(id)))
                .isEmpty();
        var detail = call(platform.gateway(), HttpMethod.GET, "/ai/conversations/" + id, tokenA, null, 200)
                .path("data");
        assertThat(detail.path("messages")).hasSize(1);
        assertThat(detail.path("activeTurnId").isMissingNode()
                        || detail.path("activeTurnId").isNull())
                .isTrue();
    }

    static final Map<String, List<Integer>> providerScenarios = new ConcurrentHashMap<>();
    static final List<String> providerPrompts = new CopyOnWriteArrayList<>();
    static final List<String> providerSecrets = new CopyOnWriteArrayList<>();

    @Test
    void transientModelHttpStatusesRetryButAuthenticationDoesNotAndUnknownCostSurvivesSuccess() throws Exception {
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Model retries", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        var statuses = new CopyOnWriteArrayList<Integer>();
        for (int failure : List.of(503, 401)) {
            String marker = "retry-" + failure + "-" + UUID.randomUUID();
            providerScenarios.put(marker, new CopyOnWriteArrayList<>(List.of(failure, 200)));
            runtime.toolProbes.put(
                    UUID.fromString(id),
                    run -> statuses.add(runtime.proxy(
                            run,
                            run.contextToken(),
                            TENANT_A.toString(),
                            Map.of(
                                    "model",
                                    model,
                                    "stream",
                                    true,
                                    "max_tokens",
                                    10,
                                    "messages",
                                    List.of(Map.of("role", "user", "content", marker))))));
            sendMessage(id, "retry model " + failure, UUID.randomUUID().toString(), tokenA);
            assertThat(providerPrompts.stream().filter(marker::equals).count()).isEqualTo(failure == 503 ? 2 : 1);
        }
        runtime.toolProbes.remove(UUID.fromString(id));
        assertThat(statuses).containsExactly(200, 502);
        var usage = call(platform.gateway(), HttpMethod.GET, "/ai/usage", tokenA, null, 200)
                .path("data");
        assertThat(usage.path("estimatedTokens").asLong()).isGreaterThan(0);
        assertThat(usage.path("unknownUsageTurns").asLong()).isGreaterThan(0);
    }

    static final UUID CLEANUP_TENANT = UUID.fromString("019a2525-0011-7000-8000-000000000000");

    @Test
    @Sql("/platform/hardening-delete.sql")
    void tenantDeletedRetriesFailedRunnerCleanupThenErasesAllBusinessContentIdempotently() {
        runtime.cleanupFailures.put(CLEANUP_TENANT, new AtomicInteger(1));
        CurrentTenant.run(
                CLEANUP_TENANT,
                () -> new TransactionTemplate(usageTransactions)
                        .executeWithoutResult(
                                status -> usageEvents.publish("forge-tenant", new TenantDeleted(CLEANUP_TENANT))));
        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .untilAsserted(() -> CurrentTenant.run(CLEANUP_TENANT, () -> {
                    assertThat(runtime.cleanupAttempts.getOrDefault(CLEANUP_TENANT, 0))
                            .isGreaterThanOrEqualTo(2);
                    assertThat(hardeningData.remainingTenantContent()).isZero();
                    assertThat(hardeningData.retainedPlatformCatalogs()).isEqualTo(3);
                }));
        CurrentTenant.run(
                CLEANUP_TENANT,
                () -> new TransactionTemplate(usageTransactions)
                        .executeWithoutResult(
                                status -> usageEvents.publish("forge-tenant", new TenantDeleted(CLEANUP_TENANT))));
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(() ->
                        assertThat(runtime.cleanupAttempts.get(CLEANUP_TENANT)).isGreaterThanOrEqualTo(3));
        CurrentTenant.run(
                CLEANUP_TENANT,
                () -> assertThat(hardeningData.remainingTenantContent()).isZero());
    }

    @Test
    void modelSecretRotationUsesSameReferenceOnNextTurnWithoutSendingSecretsToRunnerOrExport() throws Exception {
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Rotation", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        int before = providerSecrets.size();
        runtime.toolProbes.put(
                UUID.fromString(id),
                run -> assertThat(runtime.proxy(
                                run,
                                run.contextToken(),
                                TENANT_A.toString(),
                                Map.of(
                                        "model",
                                        model,
                                        "stream",
                                        true,
                                        "max_tokens",
                                        10,
                                        "messages",
                                        List.of(Map.of("role", "user", "content", "rotation")))))
                        .isEqualTo(200));
        try {
            sendMessage(id, "before rotation", UUID.randomUUID().toString(), tokenA);
            MODEL_SECRET.set("rotated-provider-secret");
            sendMessage(id, "after rotation", UUID.randomUUID().toString(), tokenA);
            assertThat(providerSecrets.subList(before, providerSecrets.size()))
                    .containsExactly("test-provider-secret", "rotated-provider-secret");
            assertThat(JsonMapper.builder()
                            .build()
                            .writeValueAsString(runtime.requests.stream()
                                    .filter(run ->
                                            run.session().sessionId().toString().equals(id))
                                    .toList()))
                    .doesNotContain("test-provider-secret", "rotated-provider-secret", "CONVERSATION_TEST_KEY");
            var download = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create(
                                            platform.gateway() + "/ai/conversations/" + id + "/export?format=json"))
                                    .header("Authorization", "Bearer " + tokenA)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body())
                    .doesNotContain("test-provider-secret", "rotated-provider-secret", "CONVERSATION_TEST_KEY");
        } finally {
            MODEL_SECRET.set("test-provider-secret");
            runtime.toolProbes.remove(UUID.fromString(id));
        }
    }

    @Test
    @Sql("/platform/hardening-retention.sql")
    void retentionScansSuspendedAndExpiredIamTenantsWithPlanAndTenantPrecedence() {
        UUID suspended = UUID.fromString("019a2525-0011-7000-8000-000000000100");
        UUID expired = UUID.fromString("019a2525-0011-7000-8000-000000000200");
        for (var tenant : List.of(suspended, expired)) {
            CurrentTenant.run(
                    tenant,
                    () -> new TransactionTemplate(usageTransactions)
                            .executeWithoutResult(status -> usageEvents.publish(
                                    "forge-tenant",
                                    new TenantStatusChanged(
                                            tenant,
                                            "Retention verification",
                                            "active",
                                            tenant.equals(suspended) ? "suspended" : "expired",
                                            1))));
        }
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var directory = call(
                            platform.iam(),
                            HttpMethod.GET,
                            "/internal/tenants?status=suspended,expired&page=1&size=100",
                            platform.aiToken,
                            null,
                            200)
                    .path("data")
                    .toString();
            assertThat(directory).contains(suspended.toString(), expired.toString());
        });
        retention.run();
        CurrentTenant.run(
                suspended,
                () -> assertThat(hardeningData.remainingConversationContent(suspended))
                        .isZero());
        CurrentTenant.run(
                expired,
                () -> assertThat(hardeningData.remainingConversationContent(expired))
                        .isEqualTo(1));
    }

    @Test
    void distinctModelAttemptsCanReuseToolCallIdWhileSameAttemptRedeliveryIsDeduplicated() throws Exception {
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Tool attempts", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        long before = usage(tokenA).path("toolCalls").asLong();
        sendMessage(id, "reused tool id", UUID.randomUUID().toString(), tokenA);
        assertThat(usage(tokenA).path("toolCalls").asLong()).isEqualTo(before + 2);
    }

    @Test
    void crashedTurnReplaysOnceWithResetAndFreshCapabilityWhileKeepingOneUserAndAllAttemptCosts() throws Exception {
        for (String mode : List.of("partial", "tool", "twice")) {
            String model = createConversationModel();
            String id = call(
                            platform.gateway(),
                            HttpMethod.POST,
                            "/ai/conversations",
                            tokenA,
                            Map.of("title", "Recover " + mode, "modelId", model),
                            201)
                    .path("data")
                    .path("id")
                    .asText();
            String key = UUID.randomUUID().toString();
            String events = sendMessage(id, "crash " + mode, key, tokenA);
            assertThat(events).contains("event:retrying", "abandoned partial");
            assertThat(events).contains(mode.equals("twice") ? "event:error" : "event:done");
            assertThat(events.split("event:retrying", -1)).hasSize(2);
            var runs = runtime.requests.stream()
                    .filter(run -> run.session().sessionId().toString().equals(id))
                    .toList();
            assertThat(runs).hasSize(2);
            assertThat(runs.get(0).contextToken()).isNotEqualTo(runs.get(1).contextToken());
            assertThat(runs.get(1).session().transcript()).isEmpty();
            var detail = call(platform.gateway(), HttpMethod.GET, "/ai/conversations/" + id, tokenA, null, 200)
                    .path("data");
            assertThat(detail.path("messages")).hasSize(2);
            assertThat(detail.path("messages").get(0).path("content").asText()).isEqualTo("crash " + mode);
            assertThat(detail.path("messages").get(1).path("content").asText())
                    .isEqualTo(mode.equals("twice") ? "abandoned partial" : "recovered answer");
            UUID turn = UUID.fromString(detail.path("turns").get(0).path("id").asText());
            CurrentTenant.run(TENANT_A, () -> {
                String snapshot = hardeningData.recoverySnapshot(turn);
                assertThat(snapshot)
                        .contains("abandoned partial")
                        .doesNotContain(
                                "private arguments",
                                runs.get(0).contextToken(),
                                runs.get(1).contextToken());
                if (mode.equals("tool")) {
                    assertThat(snapshot).contains("retained tool result");
                }
                assertThat(JsonMapper.builder().build().readTree(snapshot)).hasSize(mode.equals("twice") ? 2 : 1);
                assertThat(hardeningData.modelCalls(turn)).isEqualTo(2);
            });
            sendMessage(id, "crash " + mode, key, tokenA);
            assertThat(runtime.requests.stream()
                            .filter(run -> run.session().sessionId().toString().equals(id))
                            .count())
                    .isEqualTo(2);
            call(platform.gateway(), HttpMethod.DELETE, "/ai/conversations/" + id, tokenA, null, 200);
            CurrentTenant.run(
                    TENANT_A,
                    () -> assertThat(hardeningData.recoverySnapshot(turn)).isEqualTo("[]"));
        }
    }

    @Test
    void recoveryBudgetOverflowSettlesTheTurnAndPreservesAcceptedResultsAcrossAttempts() throws Exception {
        for (String mode : List.of("single", "recovered", "unicode")) {
            String model = createConversationModel();
            String id = call(
                            platform.gateway(),
                            HttpMethod.POST,
                            "/ai/conversations",
                            tokenA,
                            Map.of("title", "Recovery budget " + mode, "modelId", model),
                            201)
                    .path("data")
                    .path("id")
                    .asText();
            String key = UUID.randomUUID().toString();
            long before = usage(tokenA).path("turnCount").asLong();
            String events = sendMessage(id, "recovery budget " + mode, key, tokenA);
            assertThat(events).contains("event:error", "conversation.limit").doesNotContain("persistenceFailure");
            var detail = call(platform.gateway(), HttpMethod.GET, "/ai/conversations/" + id, tokenA, null, 200)
                    .path("data");
            assertThat(detail.path("turns").get(0).path("status").asText()).isEqualTo("ERROR");
            assertThat(detail.path("messages")).hasSize(2);
            assertThat(detail.path("messages").get(1).path("content").asText()).isEqualTo("accepted partial");
            UUID turn = UUID.fromString(detail.path("turns").get(0).path("id").asText());
            CurrentTenant.run(TENANT_A, () -> {
                String saved = hardeningData.recoverySnapshot(turn);
                assertThat(saved.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2097152);
                var snapshots = JsonMapper.builder().build().readTree(saved);
                assertThat(snapshots).hasSize(mode.equals("recovered") ? 2 : 1);
                int resultCount = 0;
                for (var snapshot : snapshots) {
                    for (var result : snapshot.path("results")) {
                        assertThat(result.path("content").asText())
                                .isEqualTo(mode.equals("unicode") ? "中".repeat(42000) : "x".repeat(128000));
                        resultCount++;
                    }
                }
                assertThat(resultCount).isEqualTo(16);
            });
            assertThat(usage(tokenA).path("activeTurns").asLong()).isZero();
            assertThat(usage(tokenA).path("turnCount").asLong()).isEqualTo(before + 1);
            assertThat(sendMessage(id, "recovery budget " + mode, key, tokenA))
                    .contains("replayed", "conversation.limit");
            assertThat(usage(tokenA).path("turnCount").asLong()).isEqualTo(before + 1);
        }
    }

    @Test
    void replayedSensitiveReadOnlyCallRequiresANewOwnerApproval() throws Exception {
        String model = createConversationModel();
        String base = "http://127.0.0.1:" + port;
        String catalog = call(
                        base,
                        HttpMethod.POST,
                        "/platform/mcp",
                        operator,
                        Map.of(
                                "name",
                                "Recovery approval",
                                "url",
                                "https://mcp.example.test/recovery-approval",
                                "protocol",
                                "STREAMABLE_HTTP",
                                "credentialMode",
                                "PUBLIC",
                                "isolation",
                                "PUBLIC_DATA",
                                "tools",
                                List.of(Map.of(
                                        "name",
                                        "lookup",
                                        "description",
                                        "Readonly recovery",
                                        "inputSchema",
                                        Map.of("type", "object"),
                                        "permission",
                                        "ai:mcp:read",
                                        "readOnly",
                                        true,
                                        "requiresApproval",
                                        true))),
                        201)
                .path("data")
                .path("id")
                .asText();
        call(base, HttpMethod.POST, "/platform/mcp/" + catalog + "/publish", operator, Map.of(), 200);
        publishMcpSelection(catalog, tokenA, List.of("lookup"));
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Recovery approval", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        String alias = mcpAlias(catalog, tokenA);
        runtime.toolProbes.put(
                UUID.fromString(id),
                run -> toolCall(
                        run,
                        run.contextToken(),
                        TENANT_A.toString(),
                        id,
                        Map.of("name", alias, "arguments", Map.of()),
                        200));
        try {
            var response = openMessage(id, "crash approval", tokenA);
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String first = readApproval(reader).path("approvalId").asText();
                call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/mcp/approvals/" + first + "/decision",
                        tokenA,
                        Map.of("approved", true),
                        200);
                String second = readApproval(reader).path("approvalId").asText();
                assertThat(second).isNotEqualTo(first);
                call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/mcp/approvals/" + second + "/decision",
                        tokenA,
                        Map.of("approved", true),
                        200);
                assertThat(reader.lines().collect(Collectors.joining("\n"))).contains("event:done");
            }
            assertThat(mcpTransport.calls.stream()
                            .filter(request -> request.uri().getPath().equals("/recovery-approval")
                                    && request.body().path("method").asText().equals("tools/call"))
                            .count())
                    .isEqualTo(2);
        } finally {
            runtime.toolProbes.remove(UUID.fromString(id));
        }
    }

    @Autowired
    FakeAgentRuntime runtime;

    @Test
    void messagesStreamPersistAndReplayIdempotentlyWithoutReexecuting() throws Exception {
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Streaming", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        String key = UUID.randomUUID().toString();
        String first = sendMessage(id, "hello", key, tokenA);
        assertThat(first).contains("event:token", "hello ", "world", "event:tool", "event:done", "COMPLETED");
        var history = call(platform.gateway(), HttpMethod.GET, "/ai/conversations/" + id, tokenA, null, 200)
                .path("data")
                .path("messages");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("content").asText()).isEqualTo("hello");
        assertThat(history.get(1).path("content").asText()).isEqualTo("hello world");
        assertThat(history.get(1).path("toolCalls").asText()).contains("lookup");
        assertThat(sendMessage(id, "hello", key, tokenA)).contains("event:done", "replayed");
        assertThat(runtime.requests.stream()
                        .filter(r -> r.session().sessionId().toString().equals(id))
                        .count())
                .isEqualTo(1);
        var conflict = call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/conversations/" + id + "/messages",
                tokenA,
                Map.of("text", "different", "idempotencyKey", key),
                409);
        assertThat(conflict.path("code").asText()).isEqualTo("conversation.idempotencyConflict");
        for (String action : List.of("ai.conversation.start", "ai.conversation.end")) {
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                var events = call(
                                platform.gateway(),
                                HttpMethod.GET,
                                "/iam/audit/events?action=" + action + "&resourceId=" + id,
                                tokenA,
                                null,
                                200)
                        .path("data");
                assertThat(events).hasSize(1);
                assertThat(events.get(0).path("actorId").asText()).isEqualTo(IDENTITY);
                assertThat(events.toString()).doesNotContain("hello", "world", "contextToken", "secret");
            });
        }
    }

    @Test
    void modelProxyBindsCapabilityToActiveTenantSessionAndCatalogModel() throws Exception {
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Proxy", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        assertThat(sendMessage(id, "probe model", UUID.randomUUID().toString(), tokenA))
                .contains("event:done");
        assertThat(runtime.proxyStatuses).containsExactly(200, 401, 401, 403, 403);
        var sent = runtime.requests.stream()
                .filter(r -> r.session().sessionId().toString().equals(id))
                .findFirst()
                .orElseThrow();
        assertThat(runtime.proxy(
                        sent,
                        sent.contextToken(),
                        TENANT_A.toString(),
                        Map.of("model", model, "stream", true, "messages", List.of())))
                .isEqualTo(401);
        call(
                "http://127.0.0.1:" + port,
                HttpMethod.POST,
                "/internal/runner/chat/completions",
                PlatformEnvironment.OPS,
                Map.of(),
                401);
    }

    @Test
    void conversationsFreezePublishedPromptAndKeepRawUserTextSeparate() throws Exception {
        String model = createConversationModel();
        String template = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/prompts",
                        tokenA,
                        Map.of(
                                "name",
                                "Conversation prompt",
                                "content",
                                "Serve {{name}} privately.",
                                "variables",
                                List.of("name")),
                        201)
                .path("data")
                .path("id")
                .asText();
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + template + "/publish", tokenA, null, 200);
        var conversation = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of(
                                "title",
                                "Prompt snapshot",
                                "modelId",
                                model,
                                "promptTemplateId",
                                template,
                                "variables",
                                Map.of("name", "Alice")),
                        201)
                .path("data");
        String id = conversation.path("id").asText();
        assertThat(conversation.path("promptTemplateId").asText()).isEqualTo(template);
        assertThat(conversation.path("promptVersion").asInt()).isEqualTo(1);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/prompts/" + template + "/draft",
                tokenA,
                Map.of("name", "Changed", "content", "Changed {{other}}.", "variables", List.of("other")),
                200);
        var options = call(platform.gateway(), HttpMethod.GET, "/ai/prompts/published", readOnlyMember, null, 200)
                .path("data");
        var option = StreamSupport.stream(options.spliterator(), false)
                .filter(value -> value.path("id").asText().equals(template))
                .findFirst()
                .orElseThrow();
        assertThat(option.path("variables").toString()).isEqualTo("[\"name\"]");
        assertThat(option.toString()).doesNotContain("Serve", "Changed {{other}}");
        call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + template, readOnlyMember, null, 403);
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + template + "/publish", tokenA, null, 200);
        sendMessage(id, "Raw {{name}}", UUID.randomUUID().toString(), tokenA);
        var request = runtime.requests.stream()
                .filter(r -> r.session().sessionId().toString().equals(id))
                .findFirst()
                .orElseThrow();
        assertThat(request.session().instructions()).isEqualTo("Serve Alice privately.");
        assertThat(request.text()).isEqualTo("Raw {{name}}");
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/conversations/" + id, tokenA, null, 200)
                        .path("data")
                        .path("messages")
                        .get(0)
                        .path("content")
                        .asText())
                .isEqualTo("Raw {{name}}");
    }

    @Test
    void activeMessagesCanBeCancelledAndDisconnectedStreamsStopGeneration() throws Exception {
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Cancel", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        var body = Map.of("text", "slow", "idempotencyKey", UUID.randomUUID().toString());
        var response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + port + "/conversations/" + id + "/messages"))
                                .header("Authorization", "Bearer " + tokenA)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        JsonMapper.builder().build().writeValueAsString(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        call(platform.gateway(), HttpMethod.POST, "/ai/conversations/" + id + "/messages", tokenA, body, 409);
        call(platform.gateway(), HttpMethod.POST, "/ai/conversations/" + id + "/cancel", readOnlyMember, null, 404);
        assertThat(call(platform.gateway(), HttpMethod.POST, "/ai/conversations/" + id + "/cancel", tokenA, null, 200)
                        .path("data")
                        .path("cancelled")
                        .asBoolean())
                .isTrue();
        assertThat(new String(response.body().readAllBytes(), StandardCharsets.UTF_8))
                .contains("CANCELLED");
        response.body().close();
        String secondId = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Disconnect", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        var disconnected = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://127.0.0.1:" + port + "/conversations/" + secondId + "/messages"))
                                .header("Authorization", "Bearer " + tokenA)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(JsonMapper.builder()
                                        .build()
                                        .writeValueAsString(Map.of(
                                                "text",
                                                "slow",
                                                "idempotencyKey",
                                                UUID.randomUUID().toString()))))
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        disconnected.body().close();
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(runtime.cancelled).contains(UUID.fromString(secondId)));
        sendMessage(id, "after cancel", UUID.randomUUID().toString(), tokenA);
        var recovered = runtime.requests.stream()
                .filter(r -> r.session().sessionId().toString().equals(id))
                .reduce((first, last) -> last)
                .orElseThrow();
        assertThat(recovered.session().transcript().get(0).content()).isEqualTo("slow");
        assertThat(recovered.session().transcript().get(1).role()).isEqualTo("assistant");
    }

    @Test
    void committedToolTranscriptIsAuthoritativeAndUnknownUsageStaysUnknown() throws Exception {
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Canonical", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        assertThat(sendMessage(id, "canonical", UUID.randomUUID().toString(), tokenA))
                .contains("event:done");
        var detail = call(platform.gateway(), HttpMethod.GET, "/ai/conversations/" + id, tokenA, null, 200)
                .path("data");
        assertThat(detail.path("messages")).hasSize(3);
        assertThat(detail.path("messages").get(1).path("role").asText()).isEqualTo("tool");
        assertThat(detail.path("messages").get(1).path("content").asText()).isEqualTo("factual result");
        assertThat(detail.path("messages").get(2).path("content").asText()).isEqualTo("committed answer");
        assertThat(detail.path("turns").get(0).path("usageReported").asBoolean())
                .isFalse();
        assertThat(detail.path("turns").get(0).path("inputTokens").isMissingNode()
                        || detail.path("turns").get(0).path("inputTokens").isNull())
                .isTrue();
        sendMessage(id, "next", UUID.randomUUID().toString(), tokenA);
        var restored = runtime.requests.stream()
                .filter(r -> r.session().sessionId().toString().equals(id))
                .reduce((a, b) -> b)
                .orElseThrow()
                .session()
                .transcript();
        assertThat(restored).hasSize(3);
        assertThat(restored.get(1).name()).isEqualTo("lookup");
        assertThat(restored.get(2).content()).isEqualTo("committed answer");
    }

    @Test
    void failedTurnsKeepPartialAnswerAndReplayErrorWithoutAnotherExecution() throws Exception {
        String model = createConversationModel();
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Failure", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        String key = UUID.randomUUID().toString();
        assertThat(sendMessage(id, "fail", key, tokenA)).contains("event:error").doesNotContain("event:done");
        assertThat(sendMessage(id, "fail", key, tokenA))
                .contains("event:error", "replayed")
                .doesNotContain("event:done");
        assertThat(runtime.requests.stream()
                        .filter(r -> r.session().sessionId().toString().equals(id))
                        .count())
                .isEqualTo(1);
        var detail = call(platform.gateway(), HttpMethod.GET, "/ai/conversations/" + id, tokenA, null, 200)
                .path("data");
        assertThat(detail.path("messages").get(1).path("content").asText()).isEqualTo("incomplete");
        assertThat(detail.path("turns").get(0).path("status").asText()).isEqualTo("ERROR");
        sendMessage(id, "after failure", UUID.randomUUID().toString(), tokenA);
    }

    @Test
    void conversationsKeepPublishedSkillSnapshotAcrossConfigurationChanges() throws Exception {
        String model = createConversationModel();
        String base = "http://127.0.0.1:" + port;
        var draft = new HashMap<String, Object>(Map.of(
                "code",
                "conversation-skill-" + UUID.randomUUID().toString().substring(0, 8),
                "name",
                "Conversation skill",
                "description",
                "Reviewed helper",
                "content",
                "Original reviewed instruction",
                "status",
                "REVIEWED"));
        String skill = call(base, HttpMethod.POST, "/platform/skills", operator, draft, 201)
                .path("data")
                .path("id")
                .asText();
        call(base, HttpMethod.POST, "/platform/skills/" + skill + "/publish", operator, Map.of(), 200);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/skills/" + skill + "/draft",
                tokenA,
                Map.of("enabled", true),
                200);
        call(platform.gateway(), HttpMethod.POST, "/ai/skills/" + skill + "/publish", tokenA, Map.of(), 200);
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Skill frozen", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        draft.put("content", "New published instruction");
        call(base, HttpMethod.PUT, "/platform/skills/" + skill, operator, draft, 200);
        call(base, HttpMethod.POST, "/platform/skills/" + skill + "/publish", operator, Map.of(), 200);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/skills/" + skill + "/draft",
                tokenA,
                Map.of("enabled", false),
                200);
        call(platform.gateway(), HttpMethod.POST, "/ai/skills/" + skill + "/publish", tokenA, Map.of(), 200);
        sendMessage(id, "skill snapshot", UUID.randomUUID().toString(), tokenA);
        var snapshots = runtime.requests.stream()
                .filter(r -> r.session().sessionId().toString().equals(id))
                .findFirst()
                .orElseThrow()
                .session()
                .skills();
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).get("content")).isEqualTo("Original reviewed instruction");
        assertThat(snapshots.get(0).get("version")).isEqualTo(1);
        String next = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Skill disabled", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        sendMessage(next, "new without skill", UUID.randomUUID().toString(), tokenA);
        assertThat(runtime.requests.stream()
                        .filter(r -> r.session().sessionId().toString().equals(next))
                        .findFirst()
                        .orElseThrow()
                        .session()
                        .skills())
                .isEmpty();
    }

    @Test
    void modelCatalogCannotDereferenceUnapprovedEnvironmentCredentials() throws Exception {
        String model = createConversationModel();
        String base = "http://127.0.0.1:" + port;
        var original = call(base, HttpMethod.GET, "/platform/models/" + model, operator, null, 200)
                .path("data");
        call(
                base,
                HttpMethod.PUT,
                "/platform/models/" + model,
                operator,
                Map.of(
                        "name",
                        "Restricted reference",
                        "provider",
                        "DEEPSEEK",
                        "modelId",
                        original.path("modelId").asText(),
                        "baseUrl",
                        "https://models.example.test/v1",
                        "secretRef",
                        "NON_MODEL_TEST_KEY",
                        "capabilities",
                        List.of("CHAT"),
                        "status",
                        "ENABLED",
                        "contextWindow",
                        65536),
                200);
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "Restricted credential", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        sendMessage(id, "restricted credential", UUID.randomUUID().toString(), tokenA);
        assertThat(runtime.restrictedStatuses).containsExactly(503);
    }

    private String sendMessage(String id, String text, String key, String token) throws Exception {
        var response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(platform.gateway() + "/ai/conversations/" + id + "/messages"))
                                .header("Authorization", "Bearer " + token)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(JsonMapper.builder()
                                        .build()
                                        .writeValueAsString(Map.of("text", text, "idempotencyKey", key))))
                                .timeout(Duration.ofSeconds(15))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        return response.body();
    }

    static class FakeAgentRuntime implements AgentRuntime {
        private final RunnerCapabilityService capabilities;

        FakeAgentRuntime(RunnerCapabilityService capabilities) {
            this.capabilities = capabilities;
        }

        final Map<UUID, AtomicInteger> cleanupFailures = new ConcurrentHashMap<>();
        final Map<UUID, Integer> cleanupAttempts = new ConcurrentHashMap<>();

        public void deleteTenant(UUID tenantId) {
            cleanupAttempts.merge(tenantId, 1, Integer::sum);
            var failures = cleanupFailures.get(tenantId);
            if (failures != null && failures.getAndDecrement() > 0) {
                throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
            }
        }

        final Set<UUID> capacityDenied = ConcurrentHashMap.newKeySet();

        public void admit(Session session) {
            if (capacityDenied.contains(session.sessionId())) {
                throw new ForgeException(RunnerErrorCode.POOL_FULL, 7);
            }
        }

        final List<Run> requests = new CopyOnWriteArrayList<>();
        final Map<UUID, Consumer<Run>> toolProbes = new ConcurrentHashMap<>();
        final Set<UUID> cancelled = ConcurrentHashMap.newKeySet();
        String serviceBase;
        final List<Integer> proxyStatuses = new CopyOnWriteArrayList<>();
        final List<Integer> restrictedStatuses = new CopyOnWriteArrayList<>();

        public void run(Run request, Consumer<Event> emit) {
            requests.add(request);
            if (request.text().equals("reused tool id")) {
                var json = JsonMapper.builder().build();
                for (String attempt : List.of("model-attempt-1", "model-attempt-2")) {
                    var event = new Event(
                            "tool.call",
                            json.valueToTree(Map.of("attemptId", attempt, "name", "lookup", "callId", "call_1")));
                    emit.accept(event);
                    emit.accept(event);
                    emit.accept(new Event(
                            "tool.result",
                            json.valueToTree(Map.of(
                                    "attemptId", attempt, "name", "lookup", "callId", "call_1", "isError", false))));
                }
                emit.accept(new Event("completed", json.valueToTree(Map.of("usageReported", false))));
                return;
            }
            if (request.text().equals("metered tool pause")) {
                var json = JsonMapper.builder().build();
                var call = new Event(
                        "tool.call",
                        json.valueToTree(
                                Map.of("name", "lookup", "callId", "call_1", "arguments", "private tool arguments")));
                emit.accept(call);
                emit.accept(call);
                var result = new Event(
                        "tool.result",
                        json.valueToTree(Map.of(
                                "name",
                                "lookup",
                                "callId",
                                "call_1",
                                "content",
                                "private tool result",
                                "isError",
                                false)));
                emit.accept(result);
                emit.accept(result);
                while (!cancelled.contains(request.session().sessionId())) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                return;
            }
            if (request.text().equals("pool full before execution")) {
                throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
            }
            var probe = toolProbes.get(request.session().sessionId());
            if (probe != null) {
                probe.accept(request);
            }

            if (request.text().startsWith("recovery budget ")) {
                var json = JsonMapper.builder().build();
                long execution = requests.stream()
                        .filter(run -> run.session()
                                .sessionId()
                                .equals(request.session().sessionId()))
                        .count();
                for (char value : "accepted partial".toCharArray()) {
                    emit.accept(new Event("delta", json.valueToTree(Map.of("text", String.valueOf(value)))));
                }
                int count = request.text().endsWith("recovered") ? 10 : 20;
                for (int i = 0; i < count; i++) {
                    emit.accept(
                            new Event("tool.call", json.valueToTree(Map.of("name", "lookup", "callId", "call_" + i))));
                    emit.accept(new Event(
                            "tool.result",
                            json.valueToTree(Map.of(
                                    "name",
                                    "lookup",
                                    "callId",
                                    "call_" + i,
                                    "content",
                                    request.text().endsWith("unicode") ? "中".repeat(42000) : "x".repeat(128000),
                                    "isError",
                                    false))));
                }
                emit.accept(new Event(
                        "error",
                        json.valueToTree(Map.of(
                                "code",
                                request.text().endsWith("recovered") && execution == 1
                                        ? "runner.runtimeExited"
                                        : "runner.modelFailure"))));
                return;
            }
            if (request.text().startsWith("crash ")) {
                long execution = requests.stream()
                        .filter(run -> run.session()
                                .sessionId()
                                .equals(request.session().sessionId()))
                        .count();
                var json = JsonMapper.builder().build();
                if (execution == 1 || request.text().equals("crash twice")) {
                    emit.accept(new Event("delta", json.valueToTree(Map.of("text", "abandoned partial"))));
                    if (request.text().equals("crash tool")) {
                        emit.accept(new Event(
                                "tool.call",
                                json.valueToTree(Map.of(
                                        "attemptId",
                                        "first-model",
                                        "name",
                                        "lookup",
                                        "callId",
                                        "call_1",
                                        "arguments",
                                        "private arguments"))));
                        emit.accept(new Event(
                                "tool.result",
                                json.valueToTree(Map.of(
                                        "attemptId",
                                        "first-model",
                                        "name",
                                        "lookup",
                                        "callId",
                                        "call_1",
                                        "content",
                                        "retained tool result",
                                        "isError",
                                        false))));
                    }
                    emit.accept(new Event("error", json.valueToTree(Map.of("code", "runner.runtimeExited"))));
                } else {
                    var previous = requests.stream()
                            .filter(run -> run.session()
                                    .sessionId()
                                    .equals(request.session().sessionId()))
                            .findFirst()
                            .orElseThrow();
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> capabilities.authorize(
                                    previous.contextToken(),
                                    request.session().tenantId(),
                                    request.session().sessionId(),
                                    "model:chat"))
                            .isInstanceOf(ForgeException.class);
                    emit.accept(new Event("delta", json.valueToTree(Map.of("text", "recovered answer"))));
                    emit.accept(new Event(
                            "completed",
                            json.valueToTree(Map.of(
                                    "usageReported", true, "usage", Map.of("inputTokens", 3, "outputTokens", 2)))));
                }
                return;
            }
            if (request.text().startsWith("audit skill ")) {
                var json = JsonMapper.builder().build();
                String callId = "skill-call-" + UUID.randomUUID();
                String code = request.session().skills().get(0).get("code").toString();
                emit.accept(new Event(
                        "tool.call",
                        json.valueToTree(Map.of(
                                "name",
                                "skill",
                                "callId",
                                callId,
                                "arguments",
                                json.writeValueAsString(Map.of("name", code))))));
                if (request.text().endsWith("interrupted")) {
                    emit.accept(new Event(
                            "error", json.valueToTree(Map.of("code", "runner.modelFailure", "retryable", true))));
                    return;
                }
                var result = new Event(
                        "tool.result",
                        json.valueToTree(Map.of(
                                "name",
                                "skill",
                                "callId",
                                callId,
                                "content",
                                "private-skill-result",
                                "isError",
                                request.text().endsWith("failure"))));
                emit.accept(result);
                emit.accept(result);
                emit.accept(new Event("completed", json.valueToTree(Map.of("usageReported", false))));
                return;
            }
            if (request.text().equals("slow") || request.text().equals("idle")) {
                for (int i = 0;
                        i < 1000 && !cancelled.contains(request.session().sessionId());
                        i++) {
                    if (!request.text().equals("idle") || i == 0) {
                        emit.accept(new Event(
                                "delta", JsonMapper.builder().build().valueToTree(Map.of("text", "partial"))));
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                emit.accept(new Event("cancelled", JsonMapper.builder().build().createObjectNode()));
                return;
            }
            if (request.text().equals("fail")) {
                emit.accept(new Event("delta", JsonMapper.builder().build().valueToTree(Map.of("text", "incomplete"))));
                emit.accept(new Event(
                        "error",
                        JsonMapper.builder()
                                .build()
                                .valueToTree(Map.of("code", "runner.modelFailure", "retryable", true))));
                return;
            }
            if (request.text().equals("canonical")) {
                var transcript =
                        new ArrayList<TranscriptMessage>(request.session().transcript());
                transcript.add(new TranscriptMessage("user", request.text(), null));
                transcript.add(new TranscriptMessage("tool", "factual result", "lookup"));
                transcript.add(new TranscriptMessage("assistant", "committed answer", null));
                emit.accept(
                        new Event("delta", JsonMapper.builder().build().valueToTree(Map.of("text", "draft delta"))));
                emit.accept(new Event(
                        "completed",
                        JsonMapper.builder()
                                .build()
                                .valueToTree(Map.of("transcript", transcript, "usageReported", false))));
                return;
            }
            if (request.text().equals("restricted credential")) {
                restrictedStatuses.add(proxy(
                        request,
                        request.contextToken(),
                        request.session().tenantId().toString(),
                        Map.of(
                                "model",
                                request.session().model(),
                                "stream",
                                true,
                                "max_tokens",
                                10,
                                "messages",
                                List.of(Map.of("role", "user", "content", "probe")))));
            }
            if (request.text().equals("probe model")) {
                String proxyPath = "/internal/runner/chat/completions";
                Object payload = Map.of(
                        "model",
                        request.session().model(),
                        "messages",
                        List.of(Map.of("role", "user", "content", "probe")),
                        "stream",
                        true,
                        "max_tokens",
                        10);
                proxyStatuses.add(proxy(
                        request,
                        request.contextToken(),
                        request.session().tenantId().toString(),
                        payload));
                proxyStatuses.add(
                        proxy(request, "invalid", request.session().tenantId().toString(), payload));
                proxyStatuses.add(
                        proxy(request, request.contextToken(), UUID.randomUUID().toString(), payload));
                proxyStatuses.add(proxy(
                        request,
                        request.contextToken(),
                        request.session().tenantId().toString(),
                        Map.of("model", UUID.randomUUID().toString(), "messages", List.of(), "stream", true)));
                String limited = capabilities.issue(
                        request.session().sessionId(),
                        UUID.randomUUID(),
                        UUID.fromString(request.session().model()),
                        Set.of());
                proxyStatuses.add(
                        proxy(request, limited, request.session().tenantId().toString(), payload));
                capabilities.revoke(limited);
            }
            emit.accept(new Event("delta", JsonMapper.builder().build().valueToTree(Map.of("text", "hello "))));
            emit.accept(new Event(
                    "tool.call",
                    JsonMapper.builder()
                            .build()
                            .valueToTree(Map.of("name", "lookup", "callId", "c1", "arguments", "{}"))));
            emit.accept(new Event("delta", JsonMapper.builder().build().valueToTree(Map.of("text", "world"))));
            emit.accept(new Event(
                    "completed",
                    JsonMapper.builder()
                            .build()
                            .valueToTree(Map.of(
                                    "usage",
                                    Map.of("inputTokens", 9, "outputTokens", 2, "cachedInputTokens", 1),
                                    "usageReported",
                                    true))));
        }

        int proxy(Run request, String token, String tenant, Object payload) {
            return PlatformEnvironment.client(serviceBase)
                    .post()
                    .uri("/internal/runner/chat/completions")
                    .header("Authorization", "Bearer " + token)
                    .header("X-AI-Tenant-Id", tenant)
                    .header("X-AI-Session-Id", request.session().sessionId().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((req, res) -> res.getStatusCode().value());
        }

        public boolean cancel(UUID tenantId, UUID sessionId) {
            cancelled.add(sessionId);
            return true;
        }
    }

    private String createConversationModel() {
        String model = call(
                        "http://127.0.0.1:" + port,
                        HttpMethod.POST,
                        "/platform/models",
                        operator,
                        Map.of(
                                "name",
                                "Conversation",
                                "provider",
                                "DEEPSEEK",
                                "modelId",
                                "conversation-" + UUID.randomUUID(),
                                "baseUrl",
                                "https://models.example.test/v1",
                                "secretRef",
                                "CONVERSATION_TEST_KEY",
                                "capabilities",
                                List.of("CHAT"),
                                "status",
                                "ENABLED",
                                "contextWindow",
                                65536),
                        201)
                .path("data")
                .path("id")
                .asText();
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/models/selection",
                tokenA,
                Map.of("modelIds", List.of(model)),
                200);
        return model;
    }

    @Test
    void tenantCannotDiscoverOrConfigureUnpublishedSkillDraft() {
        String base = "http://127.0.0.1:" + port;
        var draft = Map.of(
                "code",
                "skill-unpublished-test",
                "name",
                "Unpublished draft name",
                "description",
                "Unpublished draft description",
                "content",
                "Unpublished body",
                "status",
                "REVIEWED");
        String id = call(base, HttpMethod.POST, "/platform/skills", operator, draft, 201)
                .path("data")
                .path("id")
                .asText();
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/skills", tokenA, null, 200)
                        .path("data")
                        .toString())
                .doesNotContain(id, "Unpublished draft name", "Unpublished draft description");
        call(platform.gateway(), HttpMethod.GET, "/ai/skills/" + id, tokenA, null, 409);
        call(platform.gateway(), HttpMethod.PUT, "/ai/skills/" + id + "/draft", tokenA, Map.of("enabled", false), 409);
    }

    @Test
    void tenantSkillPublicationIsAuthorizedIsolatedVersionedAndAudited() {
        String base = "http://127.0.0.1:" + port;
        var draft = new HashMap<String, Object>(Map.of(
                "code",
                "skill-tenant-test",
                "name",
                "Tenant Skill",
                "description",
                "Reviewed tenant assistance",
                "content",
                "Original skill private marker.",
                "status",
                "REVIEWED"));
        String id = call(base, HttpMethod.POST, "/platform/skills", operator, draft, 201)
                .path("data")
                .path("id")
                .asText();
        call(platform.gateway(), HttpMethod.PUT, "/ai/skills/" + id + "/draft", tokenA, Map.of("enabled", true), 409);
        call(base, HttpMethod.POST, "/platform/skills/" + id + "/publish", operator, Map.of(), 200);
        String path = "/ai/skills/" + id;
        call(platform.gateway(), HttpMethod.GET, "/ai/skills", readOnlyMember, null, 403);
        call(platform.gateway(), HttpMethod.GET, "/ai/skills/available", tokenWithoutPermission, null, 403);
        call(platform.gateway(), HttpMethod.PUT, path + "/draft", skillEditor, Map.of("enabled", true), 200);
        call(platform.gateway(), HttpMethod.POST, path + "/publish", skillEditor, Map.of(), 403);
        call(platform.gateway(), HttpMethod.POST, path + "/rollback", skillEditor, Map.of("version", 1), 403);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/skills/available", tokenA, null, 200)
                        .path("data"))
                .isEmpty();
        call(platform.gateway(), HttpMethod.POST, path + "/publish", tokenA, Map.of(), 200);
        var first = call(platform.gateway(), HttpMethod.GET, "/ai/skills/available", readOnlyMember, null, 200)
                .path("data");
        assertThat(first).hasSize(1);
        assertThat(first.get(0).path("version").asInt()).isEqualTo(1);
        assertThat(first.get(0).path("content").asText()).isEqualTo("Original skill private marker.");
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/skills/available", tokenB, null, 200)
                        .path("data"))
                .isEmpty();
        assertThat(call(platform.gateway(), HttpMethod.GET, path, tokenB, null, 200)
                        .path("data")
                        .path("enabled")
                        .asBoolean())
                .isFalse();
        draft.put("content", "New skill private marker.");
        call(base, HttpMethod.PUT, "/platform/skills/" + id, operator, draft, 200);
        call(base, HttpMethod.POST, "/platform/skills/" + id + "/publish", operator, Map.of(), 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/skills/available", tokenA, null, 200)
                        .path("data")
                        .get(0)
                        .path("version")
                        .asInt())
                .isEqualTo(1);
        call(platform.gateway(), HttpMethod.POST, path + "/publish", tokenA, Map.of(), 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/skills/available", tokenA, null, 200)
                        .path("data")
                        .get(0)
                        .path("content")
                        .asText())
                .isEqualTo("New skill private marker.");
        call(platform.gateway(), HttpMethod.PUT, path + "/draft", tokenA, Map.of("enabled", false), 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/skills/available", tokenA, null, 200)
                        .path("data"))
                .hasSize(1);
        call(platform.gateway(), HttpMethod.POST, path + "/publish", tokenA, Map.of(), 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/skills/available", tokenA, null, 200)
                        .path("data"))
                .isEmpty();
        call(platform.gateway(), HttpMethod.PUT, path + "/draft", tokenB, Map.of("enabled", true), 200);
        call(platform.gateway(), HttpMethod.POST, path + "/publish", tokenB, Map.of(), 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, path + "/versions", tokenA, null, 200)
                        .path("data"))
                .hasSize(3);
        assertThat(call(platform.gateway(), HttpMethod.GET, path + "/versions", tokenB, null, 200)
                        .path("data"))
                .hasSize(1);
        call(platform.gateway(), HttpMethod.POST, path + "/rollback", tokenA, Map.of("version", 1), 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/skills/available", tokenA, null, 200)
                        .path("data")
                        .get(0)
                        .path("content")
                        .asText())
                .isEqualTo("Original skill private marker.");
        call(platform.gateway(), HttpMethod.POST, path + "/rollback", tokenB, Map.of("version", 3), 404);
        draft.put("status", "DISABLED");
        call(base, HttpMethod.PUT, "/platform/skills/" + id, operator, draft, 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/skills/available", tokenA, null, 200)
                        .path("data"))
                .isEmpty();
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/skills/available", tokenB, null, 200)
                        .path("data"))
                .isEmpty();
        call(platform.gateway(), HttpMethod.POST, path + "/publish", tokenA, Map.of(), 409);
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            for (String action :
                    java.util.List.of("ai.skill.configuration.publish", "ai.skill.configuration.rollback")) {
                var events = call(
                                platform.gateway(),
                                HttpMethod.GET,
                                "/iam/audit/events?action=" + action + "&resourceId=" + id,
                                tokenA,
                                null,
                                200)
                        .path("data");
                assertThat(events)
                        .as("Tenant audit action=%s resource=%s", action, id)
                        .isNotEmpty();
                for (var event : events) {
                    assertThat(event.path("actorId").asText()).isEqualTo(IDENTITY);
                    assertThat(event.path("detail").toString()).isEqualTo("{}");
                }
                assertThat(events.toString())
                        .doesNotContain("Original skill private marker", "New skill private marker");
            }
            var events = call(
                            platform.iam(),
                            HttpMethod.GET,
                            "/platform/audit/events?action=ai.skill.publish&resourceId=" + id,
                            operator,
                            null,
                            200)
                    .path("data");
            assertThat(events).hasSize(2);
            assertThat(events.toString()).doesNotContain("Original skill private marker", "New skill private marker");
        });
    }

    @Test
    void skillCatalogPublishesImmutableReviewedVersionsAndRollsBack() {
        String base = "http://127.0.0.1:" + port;
        var draft = new HashMap<String, Object>(Map.of(
                "code",
                "skill-catalog-test",
                "name",
                "Skill Catalog",
                "description",
                "Reviewed assistance",
                "content",
                "Only answer with verified facts.",
                "status",
                "REVIEWED"));
        call(base, HttpMethod.POST, "/platform/skills", tokenA, draft, 401);
        call(base, HttpMethod.POST, "/platform/skills", observer, draft, 403);
        var created = call(base, HttpMethod.POST, "/platform/skills", operator, draft, 201)
                .path("data");
        String id = created.path("id").asText();
        assertThat(created.path("publishedVersion").asInt()).isZero();
        call(base, HttpMethod.POST, "/platform/skills/" + id + "/publish", operator, Map.of(), 200);
        draft.put("content", "Second reviewed instructions.");
        call(base, HttpMethod.PUT, "/platform/skills/" + id, operator, draft, 200);
        var history = call(base, HttpMethod.GET, "/platform/skills/" + id + "/versions", operator, null, 200)
                .path("data");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("content").asText()).isEqualTo("Only answer with verified facts.");
        call(base, HttpMethod.POST, "/platform/skills/" + id + "/publish", operator, Map.of(), 200);
        var rollback = call(
                        base,
                        HttpMethod.POST,
                        "/platform/skills/" + id + "/rollback",
                        operator,
                        Map.of("version", 1),
                        200)
                .path("data");
        assertThat(rollback.path("publishedVersion").asInt()).isEqualTo(3);
        assertThat(rollback.path("content").asText()).isEqualTo("Only answer with verified facts.");
        assertThat(call(base, HttpMethod.GET, "/platform/skills/" + id + "/versions", operator, null, 200)
                        .path("data"))
                .hasSize(3);
        call(base, HttpMethod.POST, "/platform/skills/" + id + "/rollback", operator, Map.of("version", 999), 404);
    }

    @Test
    void operatorMaintainsModelCatalogWithoutExposingSecretReferences() {
        String base = "http://127.0.0.1:" + port;
        var payload = Map.of(
                "name",
                "DeepSeek Chat",
                "provider",
                "DEEPSEEK",
                "modelId",
                "deepseek-chat",
                "baseUrl",
                "https://api.deepseek.com",
                "secretRef",
                "DEEPSEEK_API_KEY",
                "capabilities",
                java.util.List.of("CHAT", "TOOLS"),
                "status",
                "ENABLED",
                "contextWindow",
                65536);
        var created = call(base, HttpMethod.POST, "/platform/models", operator, payload, 201);
        String id = created.path("data").path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(created.toString()).doesNotContain("DEEPSEEK_API_KEY", "secretRef");
        var read = call(base, HttpMethod.GET, "/platform/models/" + id, operator, null, 200);
        assertThat(read.path("data").path("modelId").asText()).isEqualTo("deepseek-chat");
        var listed = call(base, HttpMethod.GET, "/platform/models", operator, null, 200);
        assertThat(listed.path("data").toString()).contains(id).doesNotContain("DEEPSEEK_API_KEY", "secretRef");
        var updated = new HashMap<String, Object>(payload);
        updated.put("name", "DeepSeek Updated");
        call(base, HttpMethod.PUT, "/platform/models/" + id, operator, updated, 200);
        assertThat(call(base, HttpMethod.GET, "/platform/models/" + id, operator, null, 200)
                        .path("data")
                        .path("name")
                        .asText())
                .isEqualTo("DeepSeek Updated");
        call(base, HttpMethod.DELETE, "/platform/models/" + id, operator, null, 200);
        call(base, HttpMethod.GET, "/platform/models/" + id, operator, null, 404);
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            for (String action : java.util.List.of("ai.model.create", "ai.model.update", "ai.model.delete")) {
                var events = call(
                        platform.iam(),
                        HttpMethod.GET,
                        "/platform/audit/events?action=" + action + "&resourceId=" + id,
                        operator,
                        null,
                        200);
                assertThat(events.path("data"))
                        .as("Platform audit action=%s resourceId=%s", action, id)
                        .hasSize(1);
                var event = events.path("data").get(0);
                assertThat(event.path("actorId").asText()).isEqualTo("019a2525-0000-7000-8000-000000000071");
                assertThat(event.path("outcome").asText()).isEqualTo("success");
                assertThat(event.path("detail").toString()).isEqualTo("{}");
                assertThat(events.toString()).doesNotContain("DEEPSEEK_API_KEY", "secretRef");
            }
        });
    }

    @Test
    void tenantSelectionIsIsolatedAndDisabledModelsCannotBeUsed() {
        String platformBase = "http://127.0.0.1:" + port;
        var payload = new HashMap<String, Object>(Map.of(
                "name",
                "Selection One",
                "provider",
                "OPENAI_COMPATIBLE",
                "modelId",
                "selection-one",
                "baseUrl",
                "https://models.example.test/v1",
                "secretRef",
                "OPENAI_TEST_SECRET_REF",
                "capabilities",
                java.util.List.of("CHAT"),
                "status",
                "ENABLED",
                "contextWindow",
                32768));
        String one = call(platformBase, HttpMethod.POST, "/platform/models", operator, payload, 201)
                .path("data")
                .path("id")
                .asText();
        payload.put("name", "Selection Two");
        payload.put("modelId", "selection-two");
        String two = call(platformBase, HttpMethod.POST, "/platform/models", operator, payload, 201)
                .path("data")
                .path("id")
                .asText();
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/models/selection",
                tokenA,
                Map.of("modelIds", java.util.List.of(one)),
                200);
        call(platform.gateway(), HttpMethod.GET, "/ai/models", readOnlyMember, null, 200);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/models/selection",
                readOnlyMember,
                Map.of("modelIds", java.util.List.of(two)),
                403);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/models/selection",
                tokenWithoutPermission,
                Map.of("modelIds", java.util.List.of(two)),
                403);
        var availableA = call(platform.gateway(), HttpMethod.GET, "/ai/models", tokenA, null, 200);
        assertThat(availableA.path("data").toString())
                .contains(one)
                .doesNotContain(two, "secretRef", "OPENAI_TEST_SECRET_REF");
        call(platform.gateway(), HttpMethod.GET, "/ai/models/" + two, tokenA, null, 409);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/contract/models/" + one, tokenA, null, 200)
                        .path("data")
                        .path("modelId")
                        .asText())
                .isEqualTo("selection-one");
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/models", tokenB, null, 200)
                        .path("data")
                        .toString())
                .contains(one, two);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/models/selection",
                tokenB,
                Map.of("modelIds", java.util.List.of(two)),
                200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/models/selection", tokenA, null, 200)
                        .path("data")
                        .path("modelIds")
                        .toString())
                .contains(one)
                .doesNotContain(two);
        payload.put("status", "DISABLED");
        call(platformBase, HttpMethod.PUT, "/platform/models/" + two, operator, payload, 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/models", tokenB, null, 200)
                        .path("data"))
                .isEmpty();
        call(platform.gateway(), HttpMethod.GET, "/ai/models/" + two, tokenB, null, 409);
        call(platform.gateway(), HttpMethod.GET, "/ai/contract/models/" + two, tokenB, null, 409);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/models/selection",
                tokenA,
                Map.of("modelIds", java.util.List.of(two)),
                409);
        call(platform.gateway(), HttpMethod.GET, "/ai/models", tokenWithoutPermission, null, 403);
        call(platformBase, HttpMethod.POST, "/platform/models", tokenA, payload, 401);
        call(platformBase, HttpMethod.GET, "/models", operator, null, 401);
        call(platform.gateway(), HttpMethod.DELETE, "/ai/platform/models/" + one, tokenA, null, 401);
    }

    @Test
    void platformCatalogRequiresOperatorGrantAndRejectsCredentialBearingUrls() {
        String base = "http://127.0.0.1:" + port;
        call(base, HttpMethod.GET, "/platform/models", null, null, 401);
        call(base, HttpMethod.GET, "/platform/models", observer, null, 403);
        call(platform.gateway(), HttpMethod.GET, "/ai-platform/models", tokenA, null, 401);
        call(platform.gateway(), HttpMethod.GET, "/ai-platform/models", observer, null, 403);
        call(platform.gateway(), HttpMethod.GET, "/ai-platform/models", operator, null, 200);
        var payload = new HashMap<String, Object>(Map.of(
                "name",
                "Invalid",
                "provider",
                "OPENAI_COMPATIBLE",
                "modelId",
                "invalid-input-model",
                "baseUrl",
                "https://user:password@models.example.test/v1",
                "secretRef",
                "TEST_REF",
                "capabilities",
                java.util.List.of("CHAT"),
                "status",
                "ENABLED",
                "contextWindow",
                1234));
        assertThat(call(base, HttpMethod.POST, "/platform/models", operator, payload, 400)
                        .path("code")
                        .asText())
                .isEqualTo("model.invalidEndpoint");
        payload.put("baseUrl", "https://models.example.test/v1?api_key=fake-key");
        call(base, HttpMethod.POST, "/platform/models", operator, payload, 400);
        payload.put("baseUrl", "https://models.example.test/v1");
        payload.put("secretRef", "sk-not-a-real-key");
        call(base, HttpMethod.POST, "/platform/models", operator, payload, 400);
    }

    @Test
    void gatewayAcceptsOnlyTenantTokenAndPreservesVerifiedContext() {
        call(platform.gateway(), HttpMethod.GET, "/ai/context", null, null, 401);
        call(platform.gateway(), HttpMethod.GET, "/ai/context", operator, null, 401);
        var denied = call(platform.gateway(), HttpMethod.GET, "/ai/context", tokenWithoutPermission, null, 403);
        assertThat(denied.path("code").asText()).isEqualTo("auth.forbidden");
        var response = call(platform.gateway(), HttpMethod.GET, "/ai/context?tenantId=spoofed", tokenA, null, 200);
        assertThat(response.path("data").path("tenantId").asText()).isEqualTo(TENANT_A.toString());
        assertThat(response.path("data").path("identityId").asText()).isEqualTo(IDENTITY);
        assertThat(response.path("data").path("membershipId").asText()).isEqualTo(MEMBER_A.toString());
        call(platform.gateway(), HttpMethod.GET, "/ai/internal/onboarding-status", tokenA, null, 403);
        call("http://127.0.0.1:" + port, HttpMethod.GET, "/internal/onboarding-status", platform.aiToken, null, 401);
        var docs = call(platform.gateway(), HttpMethod.GET, "/ai/v3/api-docs", null, null, 200);
        assertThat(docs.path("x-forge-service").asText()).isEqualTo("ai-service");
        assertThat(docs.path("servers").toString()).contains("/ai");
    }

    @Test
    void tenantRecordsRemainInvisibleAcrossTenantsAndAreAuditedInIam() {
        var created = call(platform.gateway(), HttpMethod.POST, "/ai/contract/records", tokenA, Map.of(), 201);
        String id = created.path("data").path("id").asText();
        assertThat(created.path("data").path("createdBy").asText()).isEqualTo(IDENTITY);
        assertThat(created.path("data").path("createdAt").asText()).isNotBlank();
        call(platform.gateway(), HttpMethod.GET, "/ai/contract/records/" + id, tokenA, null, 200);
        var hidden = call(platform.gateway(), HttpMethod.GET, "/ai/contract/records/" + id, tokenB, null, 404);
        assertThat(hidden.path("code").asText()).isEqualTo("aicontract.notFound");
        // 略过 MyBatis 租户拦截器，仍由运行账号的 RLS 保护。
        call(platform.gateway(), HttpMethod.GET, "/ai/contract/rls/" + id, tokenB, null, 404);
        call(platform.gateway(), HttpMethod.GET, "/ai/contract/rls/" + id, tokenA, null, 200);
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var audit = call(
                    platform.gateway(),
                    HttpMethod.GET,
                    "/iam/audit/events?action=ai.contract.verify&resourceId=" + id,
                    tokenA,
                    null,
                    200);
            assertThat(audit.path("data")).hasSize(1);
            var event = audit.path("data").get(0);
            assertThat(event.path("service").asText()).isEqualTo("ai-service");
            assertThat(event.path("actorId").asText()).isEqualTo(IDENTITY);
            assertThat(event.path("outcome").asText()).isEqualTo("success");
            assertThat(event.path("traceId").asText()).hasSize(32);
            assertThat(event.path("detail").toString()).isEqualTo("{}");
        });
        var otherTenant = call(
                platform.gateway(),
                HttpMethod.GET,
                "/iam/audit/events?action=ai.contract.verify&resourceId=" + id,
                tokenB,
                null,
                200);
        assertThat(otherTenant.path("data")).isEmpty();
    }

    @Test
    void appRegistryRegistersAiAndIamReceivesTheEntry() throws Exception {
        var payload = (ObjectNode)
                JsonMapper.builder().build().readTree(Files.readString(Path.of("../deploy/app-registry.local.json")));
        payload.putObject("remoteEntries").put("test", "http://127.0.0.1:5180/remoteEntry.js");
        var registered = call(platform.gateway(), HttpMethod.POST, "/platform/sub-apps", operator, payload, 201);
        assertThat(registered.path("data").path("code").asText()).isEqualTo("ai");
        var apps = call(platform.gateway(), HttpMethod.GET, "/platform/sub-apps", operator, null, 200);
        assertThat(apps.path("data").toString()).contains("remoteEntry.js", "\"ai\"");
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var access = call(platform.gateway(), HttpMethod.GET, "/iam/auth/access", tokenA, null, 200);
            assertThat(access.path("data").path("subApps").toString()).contains("remoteEntry.js", "\"ai\"");
        });
    }

    @Test
    void iamServiceCredentialsRejectInvalidToken() {
        call(platform.iam(), HttpMethod.GET, "/internal/services", "invalid-service-token", null, 401);
        var services = call(platform.iam(), HttpMethod.GET, "/internal/services", platform.aiToken, null, 200);
        assertThat(services.toString()).contains("ai-service");
    }

    private String login(boolean operatorRealm, UUID membership) throws Exception {
        return login(operatorRealm, membership, operatorRealm ? "ai-operator" : "ai-member");
    }

    private String login(boolean operatorRealm, UUID membership, String account) throws Exception {
        String prefix = operatorRealm ? "/platform" : "";
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = RestClient.builder()
                .baseUrl(platform.iam())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .cookieHandler(cookies)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
        JsonNode login = client.post()
                .uri(prefix + "/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("account", account, "password", "Demo-password-123!"))
                .retrieve()
                .body(JsonNode.class)
                .path("data");
        if (operatorRealm && login.path("nextStep").asText().equals("bind")) {
            String uri = client.post()
                    .uri("/platform/auth/login/bind/start")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve()
                    .body(JsonNode.class)
                    .path("data")
                    .path("otpauthUri")
                    .asText();
            String secret = UriComponentsBuilder.fromUriString(uri)
                    .build()
                    .getQueryParams()
                    .getFirst("secret");
            client.post()
                    .uri("/platform/auth/login/bind/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", totp(secret)))
                    .retrieve()
                    .toBodilessEntity();
        } else {
            assertThat(login.path("nextStep").asText()).isEqualTo("authorize");
        }
        String verifier = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String location = client.get()
                .uri(builder -> builder.path("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", operatorRealm ? "platform-console" : "tenant-app")
                        .queryParam("redirect_uri", operatorRealm ? "http://localhost:5173/" : "http://localhost:5174/")
                        .queryParam("scope", operatorRealm ? "platform" : "tenant")
                        .queryParam("state", "ai-it")
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("code_challenge", challenge)
                        .build())
                .exchange((req, res) -> {
                    assertThat(res.getStatusCode().value()).isEqualTo(302);
                    return res.getHeaders().getLocation().toString();
                });
        String code = UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams()
                .getFirst("code");
        assertThat(code).isNotBlank();
        var body = new HashMap<String, Object>(Map.of("code", code, "codeVerifier", verifier));
        if (membership != null) {
            body.put("membershipId", membership);
        }
        return client.post()
                .uri(prefix + "/auth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class)
                .path("data")
                .path("accessToken")
                .asText();
    }

    static JsonNode call(String base, HttpMethod method, String path, String token, Object body, int expectedStatus) {
        var request = PlatformEnvironment.client(base).method(method).uri(path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((req, res) -> {
            var result = res.bodyTo(JsonNode.class);
            assertThat(res.getStatusCode().value())
                    .as("%s %s: %s", method, path, result)
                    .isEqualTo(expectedStatus);
            return result;
        });
    }

    private static String totp(String secret) throws Exception {
        var decoded = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char character : secret.toCharArray()) {
            buffer = (buffer << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(character);
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                decoded.write((buffer >> bits) & 255);
            }
        }
        var mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(decoded.toByteArray(), "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8)
                .putLong(Instant.now().getEpochSecond() / 30)
                .array());
        int offset = hash[hash.length - 1] & 15;
        return "%06d".formatted((ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff) % 1_000_000);
    }

    @Test
    void tenantCanSaveAndRetrievePromptDraft() {
        var created = call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/prompts",
                tokenA,
                Map.of("name", "报告助手", "content", "请为 {{name}} 编写报告。", "variables", List.of("name")),
                201);
        String id = created.path("data").path("id").asText();
        var found = call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + id, tokenA, null, 200)
                .path("data");
        assertThat(found.path("content").asText()).isEqualTo("请为 {{name}} 编写报告。");
        assertThat(found.path("variables").get(0).asText()).isEqualTo("name");
        assertThat(found.path("publishedVersion").isNull()).isTrue();
    }

    @Test
    void publishedPromptSnapshotsSurviveDraftEditsAndRollback() {
        String id = createPrompt("第一版 {{name}}");
        call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/prompts/" + id + "/render",
                tokenA,
                Map.of("variables", Map.of("name", "小明")),
                409);
        var first = call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + id + "/publish", tokenA, Map.of(), 200)
                .path("data");
        assertThat(first.path("version").asInt()).isEqualTo(1);
        assertThat(first.path("publishedBy").asText()).isEqualTo(IDENTITY);
        assertThat(first.path("publishedAt").asText()).isNotBlank();
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/prompts/" + id + "/draft",
                tokenA,
                Map.of("name", "报告助手", "content", "第二版 {{name}}", "variables", List.of("name")),
                200);
        assertThat(renderPrompt(id, null)).isEqualTo("第一版 小明");
        var second = call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + id + "/publish", tokenA, Map.of(), 200)
                .path("data");
        assertThat(second.path("version").asInt()).isEqualTo(2);
        assertThat(renderPrompt(id, null)).isEqualTo("第二版 小明");
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + id + "/versions/1/rollback", tokenA, Map.of(), 200);
        assertThat(renderPrompt(id, null)).isEqualTo("第一版 小明");
        assertThat(renderPrompt(id, 2)).isEqualTo("第二版 小明");
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + id + "/versions", tokenA, null, 200)
                        .path("data"))
                .hasSize(2);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + id, tokenA, null, 200)
                        .path("data")
                        .path("content")
                        .asText())
                .isEqualTo("第二版 {{name}}");
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var events = call(
                            platform.gateway(),
                            HttpMethod.GET,
                            "/iam/audit/events?action=ai.prompt.publish&resourceId=" + id,
                            tokenA,
                            null,
                            200)
                    .path("data");
            assertThat(events).hasSize(2);
            assertThat(events.get(0).path("actorId").asText()).isEqualTo(IDENTITY);
            assertThat(events.get(0).path("outcome").asText()).isEqualTo("success");
            assertThat(events.get(0).path("detail").toString()).isEqualTo("{}");
            var rollback = call(
                            platform.gateway(),
                            HttpMethod.GET,
                            "/iam/audit/events?action=ai.prompt.rollback&resourceId=" + id,
                            tokenA,
                            null,
                            200)
                    .path("data");
            assertThat(rollback).hasSize(1);
            assertThat(rollback.get(0).path("detail").toString()).isEqualTo("{}");
        });
    }

    @Test
    void promptVariablesAreValidatedAndPreviewUsesLiteralSinglePassReplacement() {
        var invalid = call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/prompts",
                tokenA,
                Map.of("name", "非法变量", "content", "你好 {{missing}}", "variables", List.of("name")),
                400);
        assertThat(invalid.path("code").asText()).isEqualTo("prompt.invalidVariables");
        for (String content : List.of("你好 {{name", "你好 {{bad-name}}", "你好 {{name}} {{other}}")) {
            call(
                    platform.gateway(),
                    HttpMethod.POST,
                    "/ai/prompts",
                    tokenA,
                    Map.of("name", "非法变量", "content", content, "variables", List.of("name")),
                    400);
        }
        call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/prompts",
                tokenA,
                Map.of("name", "重复变量", "content", "{{name}}", "variables", List.of("name", "name")),
                400);
        var preview = call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/prompts/preview",
                tokenA,
                Map.of(
                        "content",
                        "你好 {{name}}，{{name}}",
                        "variables",
                        List.of("name"),
                        "values",
                        Map.of("name", "$1 {{name}}")),
                200);
        assertThat(preview.path("data").path("content").asText()).isEqualTo("你好 $1 {{name}}，$1 {{name}}");
        call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/prompts/preview",
                tokenA,
                Map.of("content", "{{name}}", "variables", List.of("name"), "values", Map.of()),
                400);
        var literal = call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/prompts/preview",
                tokenA,
                Map.of(
                        "content",
                        "{{name}} {{other}}",
                        "variables",
                        List.of("name", "other"),
                        "values",
                        Map.of("name", "{{other}}", "other", "已替换")),
                200);
        assertThat(literal.path("data").path("content").asText()).isEqualTo("{{other}} 已替换");
        String id = createPrompt("你好 {{name}}");
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/prompts/" + id + "/draft",
                tokenA,
                Map.of("name", "错误草稿", "content", "{{missing}}", "variables", List.of("name")),
                400);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + id, tokenA, null, 200)
                        .path("data")
                        .path("content")
                        .asText())
                .isEqualTo("你好 {{name}}");
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + id + "/publish", tokenA, Map.of(), 200);
        call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/prompts/" + id + "/render",
                tokenA,
                Map.of("variables", Map.of()),
                400);
        call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/prompts/" + id + "/render",
                tokenA,
                Map.of("variables", Map.of("name", "小明", "unknown", "不应注入")),
                400);
    }

    @Test
    void platformPresetIsPublishedReadOnlyAndAvailableBesideTenantPublishedPrompts() {
        var selected = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/prompts/default/render",
                        tokenA,
                        Map.of("variables", Map.of()),
                        200)
                .path("data");
        String id = selected.path("templateId").asText();
        assertThat(selected.path("version").asInt()).isEqualTo(1);
        assertThat(selected.path("content").asText()).isEqualTo("你是一名面向租户内部成员的 AI 助手。请准确回答，无法确认的信息应说明。");
        var preset = call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + id, tokenA, null, 200)
                .path("data");
        assertThat(preset.path("source").asText()).isEqualTo("PLATFORM");
        call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + id, tokenB, null, 200);
        var immutable = call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/prompts/" + id + "/draft",
                tokenA,
                Map.of("name", "不能改预置", "content", "任意内容", "variables", List.of()),
                403);
        assertThat(immutable.path("code").asText()).isEqualTo("prompt.readOnly");
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + id + "/publish", tokenA, Map.of(), 403);
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + id + "/versions/1/rollback", tokenA, Map.of(), 403);
        String draft = createPrompt("未发布草稿 {{name}}");
        call(platform.gateway(), HttpMethod.GET, "/ai/prompts/published", readOnlyMember, null, 200);
        call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + draft, readOnlyMember, null, 403);
        var options = call(platform.gateway(), HttpMethod.GET, "/ai/prompts/published", tokenA, null, 200)
                .path("data");
        assertThat(options.toString()).contains(id).doesNotContain(draft, "content", "未发布草稿 {{name}}");
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + draft + "/publish", tokenA, Map.of(), 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/prompts/published", tokenA, null, 200)
                        .path("data")
                        .toString())
                .contains(draft);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/prompts/published", tokenB, null, 200)
                        .path("data")
                        .toString())
                .doesNotContain(draft);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/prompts", tokenA, null, 200)
                        .path("data")
                        .toString())
                .contains(draft, id);
    }

    @Test
    void promptManagementPermissionsAreIndependentAndTenantDataStaysIsolated() throws Exception {
        String editor = login(false, UUID.fromString("019a2525-0700-7000-8000-000000000031"), "ai-prompt-editor");
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/prompts",
                        editor,
                        Map.of("name", "编辑者草稿", "content", "发布内容 {{name}}", "variables", List.of("name")),
                        201)
                .path("data")
                .path("id")
                .asText();
        call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + id, editor, null, 200);
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + id + "/publish", editor, Map.of(), 403);
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + id + "/publish", tokenA, Map.of(), 200);
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + id + "/versions/1/rollback", editor, Map.of(), 403);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/prompts/" + id + "/draft",
                editor,
                Map.of("name", "编辑者草稿", "content", "仍未发布 {{name}}", "variables", List.of("name")),
                200);
        assertThat(renderPrompt(id, null)).isEqualTo("发布内容 小明");
        var preview = Map.of("content", "{{name}}", "variables", List.of("name"), "values", Map.of("name", "预览"));
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/preview", editor, preview, 200);
        var draft = Map.of("name", "无草稿权限", "content", "{{name}}", "variables", List.of("name"));
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts", readOnlyMember, draft, 403);
        call(platform.gateway(), HttpMethod.PUT, "/ai/prompts/" + id + "/draft", readOnlyMember, draft, 403);
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/preview", readOnlyMember, preview, 403);
        call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + id, tokenWithoutPermission, null, 403);
        call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + id, tokenB, null, 404);
        call(platform.gateway(), HttpMethod.GET, "/ai/prompts/" + id + "/versions", tokenB, null, 404);
        call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + id + "/publish", tokenB, Map.of(), 404);
        call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai/prompts/" + id + "/render",
                tokenB,
                Map.of("variables", Map.of("name", "小明")),
                404);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/prompts/" + id + "/draft",
                tokenB,
                Map.of("name", "跨租户", "content", "跨租户 {{name}}", "variables", List.of("name")),
                404);
        assertThat(renderPrompt(id, null)).isEqualTo("发布内容 小明");
    }

    @Test
    void mcpCatalogRequiresReviewAndTenantPublishesIsolatedVersionedEnablement() {
        String base = "http://127.0.0.1:" + port;
        var draft = new HashMap<String, Object>(Map.of(
                "name",
                "业务只读目录",
                "url",
                "https://mcp.example.test/mcp",
                "protocol",
                "STREAMABLE_HTTP",
                "credentialMode",
                "TENANT_SECRET",
                "isolation",
                "TENANT_CREDENTIAL",
                "tools",
                List.of(Map.of(
                        "name",
                        "lookup",
                        "description",
                        "查询状态",
                        "inputSchema",
                        Map.of("type", "object"),
                        "permission",
                        "ai:mcp:read",
                        "readOnly",
                        true))));
        String id = call(base, HttpMethod.POST, "/platform/mcp", operator, draft, 201)
                .path("data")
                .path("id")
                .asText();
        call(base, HttpMethod.POST, "/platform/mcp", observer, draft, 403);
        call(base, HttpMethod.GET, "/platform/mcp/" + id, tokenA, null, 401);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/mcp/catalog", tokenA, null, 200)
                        .toString())
                .doesNotContain(id, "mcp.example.test");
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/mcp/" + id + "/draft",
                tokenA,
                Map.of("enabled", true, "toolNames", List.of("lookup")),
                409);
        var review = call(base, HttpMethod.POST, "/platform/mcp/" + id + "/publish", operator, Map.of(), 200)
                .path("data");
        assertThat(review.path("reviewedBy").asText()).isEqualTo("019a2525-0000-7000-8000-000000000071");
        assertThat(review.path("version").asInt()).isEqualTo(1);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/mcp/catalog", tokenA, null, 200)
                        .toString())
                .contains(id)
                .doesNotContain("url", "credentialMode", "reviewedBy");
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/mcp/" + id + "/draft",
                readOnlyMember,
                Map.of("enabled", true, "toolNames", List.of("lookup")),
                403);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/mcp/" + id + "/draft",
                tokenA,
                Map.of("enabled", true, "toolNames", List.of("lookup")),
                200);
        call(platform.gateway(), HttpMethod.POST, "/ai/mcp/" + id + "/publish", tokenA, Map.of(), 200);
        call(platform.gateway(), HttpMethod.GET, "/ai/mcp/" + id, tokenB, null, 404);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/mcp/" + id + "/draft",
                tokenA,
                Map.of("enabled", false, "toolNames", List.of()),
                200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/mcp/" + id, tokenA, null, 200)
                        .path("data")
                        .path("publishedEnabled")
                        .asBoolean())
                .isTrue();
        call(platform.gateway(), HttpMethod.POST, "/ai/mcp/" + id + "/publish", tokenA, Map.of(), 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/mcp/" + id, tokenA, null, 200)
                        .path("data")
                        .path("publishedEnabled")
                        .asBoolean())
                .isFalse();
        call(platform.gateway(), HttpMethod.POST, "/ai/mcp/" + id + "/versions/1/rollback", tokenA, Map.of(), 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/mcp/" + id, tokenA, null, 200)
                        .path("data")
                        .path("publishedEnabled")
                        .asBoolean())
                .isTrue();
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/mcp/" + id + "/versions", tokenA, null, 200)
                        .path("data"))
                .hasSize(2);
        draft.put("name", "平台未发布草稿");
        call(base, HttpMethod.PUT, "/platform/mcp/" + id + "/draft", operator, draft, 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/mcp/catalog", tokenA, null, 200)
                        .toString())
                .contains("业务只读目录")
                .doesNotContain("平台未发布草稿");
    }

    @Test
    void mcpToolsUseRealIamAllSelfCustomPoliciesAndRejectUnscopedMembers() throws Exception {
        String self = login(false, UUID.fromString("019a2525-0900-7000-8000-000000000031"), "ai-mcp-self");
        String custom = login(false, UUID.fromString("019a2525-0900-7000-8000-000000000032"), "ai-mcp-custom");
        String unscoped = login(false, UUID.fromString("019a2525-0900-7000-8000-000000000033"), "ai-mcp-unscoped");
        String id = createPublishedMcp("ABAC目录");
        publishMcpSelection(id, tokenA, List.of("lookup", "detail"));
        assertThat(mcpToolNames(id, tokenA)).containsExactlyInAnyOrder("lookup", "detail");
        assertThat(mcpToolNames(id, self)).isEmpty();
        assertThat(mcpToolNames(id, custom)).containsExactly("lookup");
        assertThat(mcpToolNames(id, unscoped)).isEmpty();
        String owned = createPublishedMcp("本人目录");
        publishMcpSelection(owned, self, List.of("lookup"));
        assertThat(mcpToolNames(owned, self)).containsExactly("lookup");
        assertThat(mcpToolNames(owned, tokenB)).isEmpty();
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/mcp/" + owned + "/draft",
                self,
                Map.of("enabled", true, "toolNames", List.of("lookup"), "secretRef", "OTHER_TENANT_SECRET"),
                400);
        var unsafe = Map.of(
                "name",
                "写工具",
                "url",
                "https://mcp.example.test/mcp",
                "protocol",
                "STREAMABLE_HTTP",
                "credentialMode",
                "PUBLIC",
                "isolation",
                "PUBLIC_DATA",
                "tools",
                List.of(Map.of(
                        "name",
                        "write",
                        "description",
                        "不应执行",
                        "inputSchema",
                        Map.of("type", "object"),
                        "permission",
                        "ai:mcp:read",
                        "readOnly",
                        false)));
        call("http://127.0.0.1:" + port, HttpMethod.POST, "/platform/mcp", operator, unsafe, 400);
    }

    private String createPublishedMcp(String name) {
        String base = "http://127.0.0.1:" + port;
        var tools = List.of("lookup", "detail").stream()
                .map(tool -> Map.of(
                        "name",
                        tool,
                        "description",
                        "只读查询",
                        "inputSchema",
                        Map.of("type", "object"),
                        "permission",
                        "ai:mcp:read",
                        "readOnly",
                        true))
                .toList();
        String id = call(
                        base,
                        HttpMethod.POST,
                        "/platform/mcp",
                        operator,
                        Map.of(
                                "name",
                                name,
                                "url",
                                "https://mcp.example.test/mcp",
                                "protocol",
                                "STREAMABLE_HTTP",
                                "credentialMode",
                                "PUBLIC",
                                "isolation",
                                "PUBLIC_DATA",
                                "tools",
                                tools),
                        201)
                .path("data")
                .path("id")
                .asText();
        call(base, HttpMethod.POST, "/platform/mcp/" + id + "/publish", operator, Map.of(), 200);
        return id;
    }

    private void publishMcpSelection(String id, String token, List<String> tools) {
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/mcp/" + id + "/draft",
                token,
                Map.of("enabled", true, "toolNames", tools),
                200);
        call(platform.gateway(), HttpMethod.POST, "/ai/mcp/" + id + "/publish", token, Map.of(), 200);
    }

    private List<String> mcpToolNames(String id, String token) {
        var names = new ArrayList<String>();
        for (var tool : call(platform.gateway(), HttpMethod.GET, "/ai/mcp/tools", token, null, 200)
                .path("data")) {
            if (tool.path("catalogId").asText().equals(id)) {
                names.add(tool.path("nativeName").asText());
            }
        }
        return names;
    }

    @Test
    void mcpLongNativeNamesProduceStableProviderSafeAliases() {
        String base = "http://127.0.0.1:" + port;
        String first = "orders." + "a".repeat(120) + "x";
        String second = "orders." + "a".repeat(120) + "y";
        var tools = List.of(first, second).stream()
                .map(name -> Map.of(
                        "name",
                        name,
                        "description",
                        "完整原生名称",
                        "inputSchema",
                        Map.of("type", "object"),
                        "permission",
                        "ai:mcp:read",
                        "readOnly",
                        true))
                .toList();
        String id = call(
                        base,
                        HttpMethod.POST,
                        "/platform/mcp",
                        operator,
                        Map.of(
                                "name",
                                "长名称工具目录",
                                "url",
                                "https://mcp.example.test/mcp",
                                "protocol",
                                "STREAMABLE_HTTP",
                                "credentialMode",
                                "PUBLIC",
                                "isolation",
                                "PUBLIC_DATA",
                                "tools",
                                tools),
                        201)
                .path("data")
                .path("id")
                .asText();
        call(base, HttpMethod.POST, "/platform/mcp/" + id + "/publish", operator, Map.of(), 200);
        publishMcpSelection(id, tokenA, List.of(first, second));
        assertThat(mcpToolNames(id, tokenA)).containsExactlyInAnyOrder(first, second);
        var aliases = new ArrayList<String>();
        for (var tool : call(platform.gateway(), HttpMethod.GET, "/ai/mcp/tools", tokenA, null, 200)
                .path("data")) {
            if (tool.path("catalogId").asText().equals(id)) {
                String alias = tool.path("runtimeName").asText();
                assertThat(alias).matches("m[0-9a-f]{32}");
                assertThat(("mcp__ai_service__" + alias).length()).isLessThanOrEqualTo(64);
                aliases.add(alias);
            }
        }
        assertThat(aliases).hasSize(2).doesNotHaveDuplicates();
        var repeated = call(platform.gateway(), HttpMethod.GET, "/ai/mcp/tools", tokenA, null, 200)
                .toString();
        assertThat(repeated).contains(aliases.get(0), aliases.get(1), first, second);
    }

    @Autowired
    FakeMcpTransport mcpTransport;

    @Test
    void conversationMcpSnapshotCallsRealAuthorizedProxyAndRevokesTerminalCapabilities() throws Exception {
        String model = createConversationModel();
        String catalogId = createPublishedMcp("代理目录");
        publishMcpSelection(catalogId, tokenA, List.of("lookup"));
        String pinnedAlias = mcpAlias(catalogId, tokenA);
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "MCP快照", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        var current = call(
                        "http://127.0.0.1:" + port, HttpMethod.GET, "/platform/mcp/" + catalogId, operator, null, 200)
                .path("data")
                .path("draft");
        var changed = (ObjectNode) current.deepCopy();
        changed.put("name", "新发布目录");
        call(
                "http://127.0.0.1:" + port,
                HttpMethod.PUT,
                "/platform/mcp/" + catalogId + "/draft",
                operator,
                changed,
                200);
        call(
                "http://127.0.0.1:" + port,
                HttpMethod.POST,
                "/platform/mcp/" + catalogId + "/publish",
                operator,
                Map.of(),
                200);
        publishMcpSelection(catalogId, tokenA, List.of("lookup", "detail"));
        var aliases = new ArrayList<String>();
        runtime.toolProbes.put(UUID.fromString(id), request -> {
            assertThat(request.session().tools().stream().map(t -> t.get("name")))
                    .contains(pinnedAlias);
            String alias = pinnedAlias;
            aliases.add(alias);
            var payload = Map.of("name", alias, "arguments", Map.of("query", "private-sensitive-query"));
            var result = toolCall(
                    request,
                    request.contextToken(),
                    request.session().tenantId().toString(),
                    id,
                    payload,
                    200);
            assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("status: healthy");
            assertThat(result.path("isError").asBoolean()).isFalse();
            toolCall(request, "invalid", request.session().tenantId().toString(), id, payload, 401);
            toolCall(request, tokenA, request.session().tenantId().toString(), id, payload, 401);
            toolCall(request, request.contextToken(), UUID.randomUUID().toString(), id, payload, 401);
            toolCall(
                    request,
                    request.contextToken(),
                    request.session().tenantId().toString(),
                    UUID.randomUUID().toString(),
                    payload,
                    401);
            toolCall(
                    request,
                    request.contextToken(),
                    request.session().tenantId().toString(),
                    id,
                    Map.of("name", "m00000000000000000000000000000000", "arguments", Map.of()),
                    403);
            var failed = toolCall(
                    request,
                    request.contextToken(),
                    request.session().tenantId().toString(),
                    id,
                    Map.of("name", alias, "arguments", Map.of("fail", true)),
                    502);
            assertThat(failed.toString()).doesNotContain("upstream-secret", "private-sensitive-query");
            call(
                    "http://127.0.0.1:" + port,
                    HttpMethod.PUT,
                    "/platform/mcp/" + catalogId + "/status",
                    operator,
                    Map.of("enabled", false),
                    200);
            toolCall(
                    request,
                    request.contextToken(),
                    request.session().tenantId().toString(),
                    id,
                    payload,
                    409);
        });
        try {
            assertThat(sendMessage(id, "MCP proxy", UUID.randomUUID().toString(), tokenA))
                    .contains("event:done");
        } finally {
            runtime.toolProbes.remove(UUID.fromString(id));
            call(
                    "http://127.0.0.1:" + port,
                    HttpMethod.PUT,
                    "/platform/mcp/" + catalogId + "/status",
                    operator,
                    Map.of("enabled", true),
                    200);
        }
        assertThat(aliases).hasSize(1);
        var request = runtime.requests.stream()
                .filter(r -> r.session().sessionId().toString().equals(id))
                .findFirst()
                .orElseThrow();
        toolCall(
                request,
                request.contextToken(),
                request.session().tenantId().toString(),
                id,
                Map.of("name", aliases.get(0), "arguments", Map.of()),
                401);
        var forwarded = mcpTransport.calls.stream()
                .filter(c -> c.uri().toString().equals("https://mcp.example.test/mcp")
                        && "tools/call".equals(c.body().path("method").asText()))
                .toList();
        assertThat(forwarded).hasSize(2);
        assertThat(forwarded.get(0).body().path("params").path("name").asText()).isEqualTo("lookup");
        assertThat(forwarded.get(0).headers().containsKey("Authorization")).isFalse();
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var events = call(
                            platform.gateway(),
                            HttpMethod.GET,
                            "/iam/audit/events?action=ai.tool.call&resourceId=" + aliases.get(0),
                            tokenA,
                            null,
                            200)
                    .path("data");
            assertThat(events).hasSize(3);
            assertThat(events.toString())
                    .contains("success", "failure")
                    .doesNotContain(
                            "private-sensitive-query",
                            "status: healthy",
                            "upstream-secret",
                            "arguments",
                            "contextToken");
            for (var event : events) {
                assertThat(event.path("actorId").asText()).isEqualTo(IDENTITY);
                assertThat(event.path("detail").toString()).isEqualTo("{}");
            }
        });
    }

    private JsonNode toolCall(
            AgentRuntime.Run run, String token, String tenant, String session, Object body, int status) {
        return PlatformEnvironment.client("http://127.0.0.1:" + port)
                .post()
                .uri("/internal/runner/tools/call")
                .header("Authorization", "Bearer " + token)
                .header("X-AI-Tenant-Id", tenant)
                .header("X-AI-Session-Id", session)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    var result = res.bodyTo(JsonNode.class);
                    assertThat(res.getStatusCode().value())
                            .as("tool proxy %s", result)
                            .isEqualTo(status);
                    return result;
                });
    }

    static class FakeMcpTransport implements McpTransport {
        record Call(URI uri, Map<String, String> headers, JsonNode body) {
            @Override
            public String toString() {
                return "McpCall[redacted]";
            }
        }

        final List<Call> calls = new CopyOnWriteArrayList<>();

        public Response exchange(String method, URI uri, Map<String, String> headers, String payload) {
            var json = JsonMapper.builder().build();
            var body = payload == null ? json.createObjectNode() : json.readTree(payload);
            calls.add(new Call(uri, Map.copyOf(headers), body));
            if (method.equals("DELETE")) {
                return new Response(204, "", null, new byte[0]);
            }
            if (body.path("method").asText().equals("notifications/initialized")) {
                return new Response(202, "", null, new byte[0]);
            }
            if (body.path("params").path("arguments").path("fail").asBoolean()) {
                return new Response(500, "text/plain", null, "upstream-secret".getBytes(StandardCharsets.UTF_8));
            }
            Object result = body.path("method").asText().equals("initialize")
                    ? Map.of(
                            "protocolVersion",
                            "2025-03-26",
                            "capabilities",
                            Map.of("tools", Map.of()),
                            "serverInfo",
                            Map.of("name", "test", "version", "1"))
                    : Map.of("content", List.of(Map.of("type", "text", "text", "status: healthy")), "isError", false);
            return new Response(
                    200,
                    "application/json",
                    "mcp-test-session",
                    json.writeValueAsBytes(
                            Map.of("jsonrpc", "2.0", "id", body.path("id").asInt(), "result", result)));
        }
    }

    @Test
    void mcpCredentialsStayInJavaAndBusinessIdentityOnlyReachesTrustedOrigin() throws Exception {
        String model = createConversationModel();
        String base = "http://127.0.0.1:" + port;
        String secretCatalog = "019a2525-0900-7000-8000-000000000081";
        String businessCatalog = "019a2525-0900-7000-8000-000000000082";
        call(base, HttpMethod.POST, "/platform/mcp/" + secretCatalog + "/publish", operator, Map.of(), 200);
        call(base, HttpMethod.POST, "/platform/mcp/" + businessCatalog + "/publish", operator, Map.of(), 200);
        publishMcpSelection(secretCatalog, tokenA, List.of("lookup"));
        publishMcpSelection(secretCatalog, tokenB, List.of("lookup"));
        publishMcpSelection(businessCatalog, tokenA, List.of("lookup"));
        var draft = (ObjectNode) call(base, HttpMethod.GET, "/platform/mcp/" + businessCatalog, operator, null, 200)
                .path("data")
                .path("draft")
                .deepCopy();
        draft.put("url", "https://untrusted.example.test/mcp");
        call(base, HttpMethod.POST, "/platform/mcp", operator, draft, 400);
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "凭据边界", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        String secretAlias = mcpAlias(secretCatalog, tokenA);
        String businessAlias = mcpAlias(businessCatalog, tokenA);
        runtime.toolProbes.put(UUID.fromString(id), request -> {
            toolCall(
                    request,
                    request.contextToken(),
                    TENANT_A.toString(),
                    id,
                    Map.of("name", secretAlias, "arguments", Map.of()),
                    200);
            toolCall(
                    request,
                    request.contextToken(),
                    TENANT_A.toString(),
                    id,
                    Map.of("name", businessAlias, "arguments", Map.of()),
                    200);
        });
        try {
            assertThat(sendMessage(id, "credentials", UUID.randomUUID().toString(), tokenA))
                    .contains("event:done");
            MCP_SECRET.set("rotated-tenant-mcp-key-never-log");
            assertThat(sendMessage(id, "rotated credentials", UUID.randomUUID().toString(), tokenA))
                    .contains("event:done");
        } finally {
            MCP_SECRET.set("test-tenant-mcp-key-never-log");
            runtime.toolProbes.remove(UUID.fromString(id));
        }
        String other = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenB,
                        Map.of("title", "未绑定凭据", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        String unboundAlias = mcpAlias(secretCatalog, tokenB);
        runtime.toolProbes.put(UUID.fromString(other), request -> {
            var denied = toolCall(
                    request,
                    request.contextToken(),
                    request.session().tenantId().toString(),
                    other,
                    Map.of("name", unboundAlias, "arguments", Map.of()),
                    503);
            assertThat(denied.toString()).doesNotContain("AI_MCP_", "test-tenant-mcp-key", tokenA);
        });
        try {
            assertThat(sendMessage(
                            other, "unbound credentials", UUID.randomUUID().toString(), tokenB))
                    .contains("event:done");
        } finally {
            runtime.toolProbes.remove(UUID.fromString(other));
        }
        var external = mcpTransport.calls.stream()
                .filter(c -> c.uri().toString().equals("https://mcp.example.test/tenant"))
                .toList();
        assertThat(external).isNotEmpty();
        assertThat(external.stream()
                        .map(request -> request.headers().get("Authorization"))
                        .toList())
                .contains("Bearer test-tenant-mcp-key-never-log", "Bearer rotated-tenant-mcp-key-never-log");
        for (var request : external) {
            assertThat(request.headers().get("Authorization"))
                    .isIn("Bearer test-tenant-mcp-key-never-log", "Bearer rotated-tenant-mcp-key-never-log");
            assertThat(request.body().toString()).doesNotContain(tokenA, "test-tenant-mcp-key");
        }
        var business = mcpTransport.calls.stream()
                .filter(c -> c.uri().getHost().equals("business.example.test"))
                .toList();
        assertThat(business).isNotEmpty();
        for (var request : business) {
            assertThat(("Bearer " + tokenA).equals(request.headers().get("Authorization")))
                    .isTrue();
        }
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/mcp", tokenA, null, 200)
                        .toString())
                .doesNotContain("AI_MCP_", "test-tenant-mcp-key", "secretRef");
    }

    private String mcpAlias(String catalogId, String token) {
        for (var tool : call(platform.gateway(), HttpMethod.GET, "/ai/mcp/tools", token, null, 200)
                .path("data")) {
            if (tool.path("catalogId").asText().equals(catalogId)
                    && tool.path("nativeName").asText().equals("lookup")) {
                return tool.path("runtimeName").asText();
            }
        }
        throw new AssertionError("Expected authorized MCP lookup declaration");
    }

    @Test
    void publishedLocalSkillCallsAuditCorrelatedSuccessFailureAndInterruptionWithoutContent() throws Exception {
        String model = createConversationModel();
        String base = "http://127.0.0.1:" + port;
        String code = "audited-skill-" + UUID.randomUUID().toString().substring(0, 8);
        String skill = call(
                        base,
                        HttpMethod.POST,
                        "/platform/skills",
                        operator,
                        Map.of(
                                "code",
                                code,
                                "name",
                                "审计技能",
                                "description",
                                "审核只读技能",
                                "content",
                                "private-skill-instructions",
                                "status",
                                "REVIEWED"),
                        201)
                .path("data")
                .path("id")
                .asText();
        call(base, HttpMethod.POST, "/platform/skills/" + skill + "/publish", operator, Map.of(), 200);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/skills/" + skill + "/draft",
                tokenA,
                Map.of("enabled", true),
                200);
        call(platform.gateway(), HttpMethod.POST, "/ai/skills/" + skill + "/publish", tokenA, Map.of(), 200);
        try {
            String id = call(
                            platform.gateway(),
                            HttpMethod.POST,
                            "/ai/conversations",
                            tokenA,
                            Map.of("title", "本地技能审计", "modelId", model),
                            201)
                    .path("data")
                    .path("id")
                    .asText();
            assertThat(sendMessage(id, "audit skill success", UUID.randomUUID().toString(), tokenA))
                    .contains("event:done", "displayName");
            assertThat(sendMessage(id, "audit skill failure", UUID.randomUUID().toString(), tokenA))
                    .contains("event:done");
            assertThat(sendMessage(
                            id, "audit skill interrupted", UUID.randomUUID().toString(), tokenA))
                    .contains("event:error");
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                var completed = new ArrayList<JsonNode>();
                var started = new ArrayList<JsonNode>();
                for (String action : List.of("ai.tool.call", "ai.tool.start")) {
                    for (var event : call(
                                    platform.gateway(),
                                    HttpMethod.GET,
                                    "/iam/audit/events?action=" + action + "&resourceType=skillToolCall",
                                    tokenA,
                                    null,
                                    200)
                            .path("data")) {
                        if (event.path("detail").path("conversationId").asText().equals(id)) {
                            (action.equals("ai.tool.call") ? completed : started).add(event);
                            assertThat(event.toString())
                                    .doesNotContain(
                                            "private-skill-instructions", "private-skill-result", "arguments", code);
                        }
                    }
                }
                assertThat(started).hasSize(3);
                assertThat(completed).hasSize(3);
                assertThat(completed.stream()
                                .filter(e -> e.path("outcome").asText().equals("success"))
                                .count())
                        .isEqualTo(1);
                assertThat(completed.stream()
                                .filter(e -> e.path("outcome").asText().equals("failure"))
                                .count())
                        .isEqualTo(2);
                assertThat(started.stream()
                                .map(e -> e.path("resourceId").asText())
                                .toList())
                        .containsExactlyInAnyOrderElementsOf(completed.stream()
                                .map(e -> e.path("resourceId").asText())
                                .toList());
            });
        } finally {
            call(
                    platform.gateway(),
                    HttpMethod.PUT,
                    "/ai/skills/" + skill + "/draft",
                    tokenA,
                    Map.of("enabled", false),
                    200);
            call(platform.gateway(), HttpMethod.POST, "/ai/skills/" + skill + "/publish", tokenA, Map.of(), 200);
        }
    }

    @Test
    void sensitiveReadOnlyToolsRequireActiveOwnerApprovalAndNeverForwardRejectedCancelledOrExpiredCalls()
            throws Exception {
        String model = createConversationModel();
        String base = "http://127.0.0.1:" + port;
        String catalog = call(
                        base,
                        HttpMethod.POST,
                        "/platform/mcp",
                        operator,
                        Map.of(
                                "name",
                                "敏感只读查询",
                                "url",
                                "https://mcp.example.test/approval",
                                "protocol",
                                "STREAMABLE_HTTP",
                                "credentialMode",
                                "PUBLIC",
                                "isolation",
                                "PUBLIC_DATA",
                                "tools",
                                List.of(Map.of(
                                        "name",
                                        "lookup",
                                        "description",
                                        "读取敏感只读状态",
                                        "inputSchema",
                                        Map.of("type", "object"),
                                        "permission",
                                        "ai:mcp:read",
                                        "readOnly",
                                        true,
                                        "requiresApproval",
                                        false))),
                        201)
                .path("data")
                .path("id")
                .asText();
        call(base, HttpMethod.POST, "/platform/mcp/" + catalog + "/publish", operator, Map.of(), 200);
        publishMcpSelection(catalog, tokenA, List.of("lookup"));
        String id = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/conversations",
                        tokenA,
                        Map.of("title", "敏感工具审批", "modelId", model),
                        201)
                .path("data")
                .path("id")
                .asText();
        var stricter = (ObjectNode) call(base, HttpMethod.GET, "/platform/mcp/" + catalog, operator, null, 200)
                .path("data")
                .path("draft")
                .deepCopy();
        ((ObjectNode) stricter.path("tools").get(0)).put("requiresApproval", true);
        call(base, HttpMethod.PUT, "/platform/mcp/" + catalog + "/draft", operator, stricter, 200);
        call(base, HttpMethod.POST, "/platform/mcp/" + catalog + "/publish", operator, Map.of(), 200);
        assertThat(call(platform.gateway(), HttpMethod.GET, "/ai/mcp/" + catalog, tokenA, null, 200)
                        .path("data")
                        .path("catalogVersion")
                        .asInt())
                .isEqualTo(1);
        String alias = mcpAlias(catalog, tokenA);
        runtime.toolProbes.put(UUID.fromString(id), request -> {
            int expected = request.text().endsWith("reject")
                    ? 403
                    : request.text().endsWith("cancel")
                                    || request.text().endsWith("expire")
                                    || request.text().endsWith("disabled")
                            ? 409
                            : 200;
            try {
                toolCall(
                        request,
                        request.contextToken(),
                        TENANT_A.toString(),
                        id,
                        Map.of("name", alias, "arguments", Map.of("query", "private-approval-query")),
                        expected);
            } catch (RuntimeException error) {
                if (!request.text().endsWith("cancel")) {
                    throw error;
                }
            }
        });
        try {
            for (String mode : List.of("approve", "reject", "disabled", "cancel", "expire")) {
                var response = openMessage(id, "approval " + mode, tokenA);
                try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    var pending = readApproval(reader);
                    assertThat(pending.path("displayName").asText()).isEqualTo("lookup");
                    assertThat(pending.toString())
                            .doesNotContain("private-approval-query", "arguments", "contextToken");
                    String approval = pending.path("approvalId").asText();
                    assertThat(Instant.parse(pending.path("expiresAt").asText()))
                            .isAfter(Instant.now())
                            .isBefore(Instant.now().plusSeconds(21));
                    long before = approvalForwards();
                    if (mode.equals("approve")) {
                        call(
                                platform.gateway(),
                                HttpMethod.POST,
                                "/ai/mcp/approvals/" + approval + "/decision",
                                tokenB,
                                Map.of("approved", true),
                                404);
                        call(
                                platform.gateway(),
                                HttpMethod.POST,
                                "/ai/mcp/approvals/" + approval + "/decision",
                                readOnlyMember,
                                Map.of("approved", true),
                                404);
                    }
                    if (mode.equals("disabled")) {
                        call(
                                base,
                                HttpMethod.PUT,
                                "/platform/mcp/" + catalog + "/status",
                                operator,
                                Map.of("enabled", false),
                                200);
                    }
                    if (mode.equals("cancel")) {
                        call(
                                platform.gateway(),
                                HttpMethod.POST,
                                "/ai/conversations/" + id + "/cancel",
                                tokenA,
                                Map.of(),
                                200);
                    } else if (!mode.equals("expire")) {
                        var decision = call(
                                        platform.gateway(),
                                        HttpMethod.POST,
                                        "/ai/mcp/approvals/" + approval + "/decision",
                                        tokenA,
                                        Map.of("approved", !mode.equals("reject")),
                                        200)
                                .path("data");
                        assertThat(decision.path("status").asText())
                                .isEqualTo(mode.equals("reject") ? "REJECTED" : "APPROVED");
                    }
                    String remaining = reader.lines().collect(Collectors.joining("\n"));
                    assertThat(remaining).contains("tool.approval.resolved", "event:done");
                    assertThat(approvalForwards()).isEqualTo(before + (mode.equals("approve") ? 1 : 0));
                    if (mode.equals("expire")) {
                        assertThat(remaining).contains("EXPIRED");
                    }
                    if (mode.equals("cancel")) {
                        assertThat(remaining).contains("CANCELLED");
                    }
                } finally {
                    call(
                            base,
                            HttpMethod.PUT,
                            "/platform/mcp/" + catalog + "/status",
                            operator,
                            Map.of("enabled", true),
                            200);
                }
            }
        } finally {
            runtime.toolProbes.remove(UUID.fromString(id));
        }
        assertThat(approvalForwards()).isEqualTo(1);
    }

    private long approvalForwards() {
        return mcpTransport.calls.stream()
                .filter(c -> c.uri().getPath().equals("/approval")
                        && c.body().path("method").asText().equals("tools/call"))
                .count();
    }

    private HttpResponse<InputStream> openMessage(String id, String text, String token) throws Exception {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(
                        HttpRequest.newBuilder(URI.create(platform.gateway() + "/ai/conversations/" + id + "/messages"))
                                .timeout(Duration.ofSeconds(40))
                                .header("Authorization", "Bearer " + token)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(JsonMapper.builder()
                                        .build()
                                        .writeValueAsString(Map.of(
                                                "text",
                                                text,
                                                "idempotencyKey",
                                                UUID.randomUUID().toString()))))
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
    }

    private JsonNode readApproval(BufferedReader reader) throws Exception {
        String type = "";
        String data = "";
        for (String line; (line = reader.readLine()) != null; ) {
            if (line.startsWith("event:")) {
                type = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                data = line.substring(5).stripLeading();
            } else if (line.isEmpty() && type.equals("tool.approval")) {
                return JsonMapper.builder().build().readTree(data);
            }
        }
        throw new AssertionError("Expected a pending tool approval before execution");
    }

    private String createPrompt(String content) {
        return call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai/prompts",
                        tokenA,
                        Map.of("name", "报告助手", "content", content, "variables", List.of("name")),
                        201)
                .path("data")
                .path("id")
                .asText();
    }

    private String renderPrompt(String id, Integer version) {
        var body = new HashMap<String, Object>();
        body.put("variables", Map.of("name", "小明"));
        if (version != null) {
            body.put("version", version);
        }
        return call(platform.gateway(), HttpMethod.POST, "/ai/prompts/" + id + "/render", tokenA, body, 200)
                .path("data")
                .path("content")
                .asText();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        @Primary
        FakeMcpTransport fakeMcpTransport() {
            return new FakeMcpTransport();
        }

        @Bean
        @Primary
        ProviderTransport fakeProviderTransport() {
            return (endpoint, secret, body) -> {
                assertThat(endpoint.toString()).isEqualTo("https://models.example.test/v1/chat/completions");
                assertThat(secret).isEqualTo(MODEL_SECRET.get());
                assertThat(JsonMapper.builder()
                                .build()
                                .readTree(body)
                                .path("model")
                                .asText())
                        .startsWith("conversation-");
                String marker = JsonMapper.builder()
                        .build()
                        .readTree(body)
                        .path("messages")
                        .get(0)
                        .path("content")
                        .asText();
                providerPrompts.add(marker);
                providerSecrets.add(secret);
                var scenario = providerScenarios.get(marker);
                int status = scenario == null || scenario.isEmpty() ? 200 : scenario.remove(0);
                return new ProviderTransport.Response(
                        status,
                        "text/event-stream",
                        new ByteArrayInputStream("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)));
            };
        }

        @Bean
        @Primary
        FakeAgentRuntime fakeAgentRuntime(RunnerCapabilityService capabilities) {
            return new FakeAgentRuntime(capabilities);
        }

        @Bean
        ProbeController probeController(
                ContractMapper mapper, io.saasforge.ai.model.service.ModelSelectionService models) {
            return new ProbeController(mapper, models);
        }
    }

    @RestController
    @TestComponent
    public static class ProbeController {
        private final ContractMapper mapper;
        private final io.saasforge.ai.model.service.ModelSelectionService models;

        ProbeController(ContractMapper mapper, io.saasforge.ai.model.service.ModelSelectionService models) {
            this.mapper = mapper;
            this.models = models;
        }

        @GetMapping("/contract/models/{id}")
        @RequiresPermission("ai:conversation:use")
        public ApiResponse<RuntimeProbe> runtimeConfig(@PathVariable UUID id) {
            var config = models.resolveForUse(id);
            return ApiResponse.ok(new RuntimeProbe(config.catalogId(), config.provider(), config.modelId()));
        }

        @PostMapping("/contract/records")
        @ResponseStatus(HttpStatus.CREATED)
        @RequiresPermission("ai:conversation:use")
        @AuditLog(action = "ai.contract.verify", resourceType = "contract", resourceId = "#result.data().id()")
        @Transactional
        public ApiResponse<ProbeResponse> create() {
            var row = new ContractRecord();
            mapper.insert(row);
            return ApiResponse.created(response(mapper.selectById(row.getId())));
        }

        @GetMapping("/contract/records/{id}")
        @RequiresPermission("ai:conversation:use")
        public ApiResponse<ProbeResponse> read(@PathVariable UUID id) {
            return ApiResponse.ok(response(mapper.selectById(id)));
        }

        @GetMapping("/contract/rls/{id}")
        @RequiresPermission("ai:conversation:use")
        public ApiResponse<ProbeResponse> readWithRlsOnly(@PathVariable UUID id) {
            return ApiResponse.ok(response(mapper.readWithRlsOnly(id)));
        }

        private ProbeResponse response(ContractRecord row) {
            if (row == null) {
                throw new ForgeException(ProbeError.NOT_FOUND);
            }
            return new ProbeResponse(row.getId(), row.getCreatedBy(), row.getCreatedAt());
        }
    }

    public record RuntimeProbe(UUID id, String provider, String modelId) {}

    public record ProbeResponse(UUID id, UUID createdBy, OffsetDateTime createdAt) {}

    enum ProbeError implements ErrorCode {
        NOT_FOUND;

        public String code() {
            return "aicontract.notFound";
        }

        public int httpStatus() {
            return 404;
        }
    }
}
