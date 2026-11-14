package io.saasforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.ai.contract.entity.ContractRecord;
import io.saasforge.ai.contract.mapper.ContractMapper;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ErrorCode;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.RequiresPermission;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
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
    static final UUID TENANT_A = UUID.fromString("019a2525-0000-7000-8000-000000000001");
    static final UUID MEMBER_A = UUID.fromString("019a2525-0000-7000-8000-000000000031");
    static final UUID MEMBER_B = UUID.fromString("019a2525-0000-7000-8000-000000000032");
    static final String IDENTITY = "019a2525-0000-7000-8000-000000000011";
    static final PlatformEnvironment platform = new PlatformEnvironment();
    String tokenA;
    String tokenB;
    String operator;
    String tokenWithoutPermission;
    String readOnlyMember;
    String observer;
    String skillEditor;

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
        registry.add("forge.mq.ssl", () -> false);
        registry.add("forge.mq.endpoints", platform::mqEndpoints);
        registry.add("forge.tenant.write-guard.enabled", () -> true);
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
        tokenA = login(false, MEMBER_A);
        tokenB = login(false, MEMBER_B);
        tokenWithoutPermission = login(false, UUID.fromString("019a2525-0000-7000-8000-000000000033"));
        operator = login(true, null);
        observer = login(true, null, "ai-observer");
        readOnlyMember = login(false, UUID.fromString("019a2525-0000-7000-8000-000000000034"), "ai-reader");
        skillEditor = login(false, UUID.fromString("019a2525-0000-7000-8000-000000000035"), "ai-skill-editor");
    }

    @AfterAll
    void stopPlatform() {
        platform.close();
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
                assertThat(events.path("data")).hasSize(1);
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

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
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
