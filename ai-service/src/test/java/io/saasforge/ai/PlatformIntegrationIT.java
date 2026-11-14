package io.saasforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.ai.contract.entity.ContractRecord;
import io.saasforge.ai.contract.mapper.ContractMapper;
import io.saasforge.ai.runner.service.AgentRuntime;
import io.saasforge.ai.runner.service.ProviderTransport;
import io.saasforge.ai.runner.service.RunnerCapabilityService;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ErrorCode;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.RequiresPermission;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
        registry.add("CONVERSATION_TEST_KEY", () -> "test-provider-secret");
        registry.add("NON_MODEL_TEST_KEY", () -> "must-not-forward");
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
        runtime.serviceBase = "http://127.0.0.1:" + port;
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

        final List<Run> requests = new CopyOnWriteArrayList<>();
        final Set<UUID> cancelled = ConcurrentHashMap.newKeySet();
        String serviceBase;
        final List<Integer> proxyStatuses = new CopyOnWriteArrayList<>();
        final List<Integer> restrictedStatuses = new CopyOnWriteArrayList<>();

        public void run(Run request, Consumer<Event> emit) {
            requests.add(request);
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
        String first = "a".repeat(127) + "x";
        String second = "a".repeat(127) + "y";
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
        ProviderTransport fakeProviderTransport() {
            return (endpoint, secret, body) -> {
                assertThat(endpoint.toString()).isEqualTo("https://models.example.test/v1/chat/completions");
                assertThat(secret).isEqualTo("test-provider-secret");
                assertThat(JsonMapper.builder()
                                .build()
                                .readTree(body)
                                .path("model")
                                .asText())
                        .startsWith("conversation-");
                return new ProviderTransport.Response(
                        200,
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
