package io.saasforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.manifest.ResourceManifestCollector;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 真 Shell → Gateway → IAM/AI → 真实 DSH → HTTPS 模型/MCP；无 AgentRuntime 替身。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullStackIT {
    static final UUID TENANT_A = UUID.fromString("019a2525-0000-7000-8000-000000000001");
    static final UUID MEMBER_A = UUID.fromString("019a2525-0000-7000-8000-000000000031");
    static final UUID MEMBER_B = UUID.fromString("019a2525-0000-7000-8000-000000000032");
    static final PlatformEnvironment platform = new PlatformEnvironment();
    static final int runnerPort = freePort();
    static final int managementPort = freePort();
    static final int consolePort = freePort();
    static final Path output = Path.of(System.getenv("AI_E2E_OUTPUT"));
    static final String provider = JsonMapper.builder()
            .build()
            .readTree(read(output.resolve("provider-ready.json")))
            .path("url")
            .asText();
    final List<Process> processes = new ArrayList<>();
    String operator;
    JsonNode operatorSession;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", platform.postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", platform.redis::getHost);
        registry.add("spring.data.redis.port", () -> platform.redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", platform.redis::database);
        registry.add("forge.security.issuer", platform::issuer);
        registry.add("forge.security.jwk-set-uri", () -> platform.iam() + "/oauth2/jwks");
        registry.add("forge.security.platform.issuer", () -> platform.issuer() + "/platform");
        registry.add("forge.security.platform.jwk-set-uri", () -> platform.iam() + "/platform/oauth2/jwks");
        registry.add("forge.manifest.iam-uri", platform::iam);
        registry.add("forge.manifest.token", () -> platform.aiToken);
        registry.add("forge.manifest.enabled", () -> true);
        registry.add("forge.ops.token", () -> PlatformEnvironment.OPS);
        registry.add("forge.mq.enabled", () -> true);
        registry.add("forge.mq.ssl", () -> false);
        registry.add("forge.mq.endpoints", platform::mqEndpoints);
        registry.add("forge.tenant.write-guard.enabled", () -> true);
        registry.add("ai.runner.base-uri", () -> "http://127.0.0.1:" + runnerPort);
        registry.add("ai.runner.service-token", () -> "fullstack-runner-service-identity");
        registry.add("ai.model-proxy.secret-references", () -> "FULLSTACK_MODEL_KEY");
        registry.add("FULLSTACK_MODEL_KEY", () -> "deterministic-local-model-key");
        registry.add("ai.model-credential.key", () -> ModelCredentialCipherTest.TEST_KEY);
        registry.add("management.server.port", () -> managementPort);
    }

    @BeforeAll
    void startStack() throws Exception {
        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .untilAsserted(() -> assertThat(call(
                                        "http://127.0.0.1:" + port,
                                        HttpMethod.GET,
                                        "/internal/onboarding-status",
                                        PlatformEnvironment.OPS,
                                        null,
                                        200)
                                .path("data")
                                .path("phase")
                                .asText())
                        .isEqualTo("REPORTED"));
        platform.postgres.script("postgres/platform/seed.sql");
        platform.publishSeededTenantState();
        platform.startGateway(port);
        operator = login(true, null);
        var permissions =
                ResourceManifestCollector.collect(
                                "ai-service",
                                "test",
                                "io.saasforge.ai",
                                getClass().getClassLoader())
                        .permissions()
                        .stream()
                        .map(p -> p.code())
                        .toList();
        String plan = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/platform/plans",
                        operator,
                        Map.of(
                                "code",
                                "fullstack",
                                "name",
                                "AI 全链路",
                                "permissions",
                                permissions,
                                "subApps",
                                List.of("ai"),
                                "tokenQuota",
                                1000),
                        201)
                .path("data")
                .path("id")
                .asText();
        for (String tenant : List.of(TENANT_A.toString(), "019a2525-0000-7000-8000-000000000002")) {
            call(
                    platform.gateway(),
                    HttpMethod.PUT,
                    "/platform/tenants/" + tenant + "/plan",
                    operator,
                    Map.of("planId", plan),
                    200);
        }
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(
                                call(platform.gateway(), HttpMethod.GET, "/ai/usage", login(false, MEMBER_A), null, 200)
                                        .path("data")
                                        .path("quotaKnown")
                                        .asBoolean())
                        .isTrue());
        String token = login(false, MEMBER_A);
        call(
                platform.gateway(),
                HttpMethod.POST,
                "/ai-platform/models",
                operator,
                Map.of(
                        "name",
                        "本地确定性模型",
                        "provider",
                        "OPENAI_COMPATIBLE",
                        "modelId",
                        "local-fullstack",
                        "baseUrl",
                        provider + "/v1",
                        "secretRef",
                        "FULLSTACK_MODEL_KEY",
                        "capabilities",
                        List.of("CHAT", "TOOLS"),
                        "status",
                        "ENABLED",
                        "contextWindow",
                        65536),
                201);
        String catalog = call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai-platform/mcp",
                        operator,
                        Map.of(
                                "name",
                                "敏感只读状态",
                                "url",
                                provider + "/mcp",
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
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of("query", Map.of("type", "string"))),
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
        call(platform.gateway(), HttpMethod.POST, "/ai-platform/mcp/" + catalog + "/publish", operator, Map.of(), 200);
        call(
                platform.gateway(),
                HttpMethod.PUT,
                "/ai/mcp/" + catalog + "/draft",
                token,
                Map.of("enabled", true, "toolNames", List.of("lookup")),
                200);
        call(platform.gateway(), HttpMethod.POST, "/ai/mcp/" + catalog + "/publish", token, Map.of(), 200);
        var registration = (ObjectNode)
                JsonMapper.builder().build().readTree(Files.readString(Path.of("../deploy/app-registry.local.json")));
        registration.putObject("remoteEntries").put("test", "http://127.0.0.1:5180/remoteEntry.js");
        call(platform.gateway(), HttpMethod.POST, "/platform/sub-apps", operator, registration, 201);
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(
                        () -> assertThat(call(platform.gateway(), HttpMethod.GET, "/iam/auth/access", token, null, 200)
                                        .path("data")
                                        .path("subApps")
                                        .toString())
                                .contains("remoteEntry.js"));
        Path root = Path.of("..").toAbsolutePath().normalize();
        start(
                "runner",
                root,
                Map.of(
                        "AI_RUNNER_HOST",
                        "127.0.0.1",
                        "AI_RUNNER_PORT",
                        "" + runnerPort,
                        "AI_RUNNER_SERVICE_TOKEN",
                        "fullstack-runner-service-identity",
                        "AI_SERVICE_PROXY_URL",
                        "http://127.0.0.1:" + port + "/internal/runner",
                        "AI_RUNNER_ROOT",
                        output.resolve("runner-home").toString()),
                "node",
                "runner/dist/main.js");
        start(
                "remote",
                root.resolve("ai-sub-app"),
                Map.of(),
                "pnpm",
                "exec",
                "vite",
                "preview",
                "--host",
                "127.0.0.1",
                "--port",
                "5180",
                "--strictPort");
        start(
                "shell",
                Path.of(System.getenv("AI_FORGE_SOURCE"), "frontend"),
                Map.of("FORGE_DEV_GATEWAY", platform.gateway()),
                "pnpm",
                "--filter",
                "@forge/tenant-app",
                "exec",
                "vite",
                "preview",
                "--host",
                "127.0.0.1",
                "--port",
                "5174",
                "--strictPort");
        start(
                "console",
                Path.of(System.getenv("AI_FORGE_SOURCE"), "frontend"),
                Map.of("FORGE_DEV_GATEWAY", platform.gateway()),
                "pnpm",
                "--filter",
                "@forge/platform-console",
                "exec",
                "vite",
                "preview",
                "--host",
                "127.0.0.1",
                "--port",
                "" + consolePort,
                "--strictPort");
        for (String url : List.of(
                "http://127.0.0.1:" + runnerPort + "/ready",
                "http://127.0.0.1:5180/remoteEntry.js",
                "http://localhost:5174/",
                "http://127.0.0.1:" + consolePort + "/")) {
            Awaitility.await()
                    .alias("HTTP readiness " + url)
                    .atMost(Duration.ofSeconds(45))
                    .ignoreExceptions()
                    .until(() -> HttpClient.newBuilder()
                                    // Vite preview 不处理 JDK 默认的 h2c 升级；与浏览器的本地 HTTP/1.1 一致。
                                    .version(HttpClient.Version.HTTP_1_1)
                                    .connectTimeout(Duration.ofSeconds(2))
                                    .build()
                                    .send(
                                            HttpRequest.newBuilder(URI.create(url))
                                                    .timeout(Duration.ofSeconds(3))
                                                    .GET()
                                                    .build(),
                                            HttpResponse.BodyHandlers.discarding())
                                    .statusCode()
                            == 200);
        }
        Files.writeString(
                output.resolve("browser-env.json"),
                JsonMapper.builder()
                        .build()
                        .writeValueAsString(Map.of(
                                "gateway", platform.gateway(),
                                "runner", "http://127.0.0.1:" + runnerPort,
                                "platformConsole",
                                        Map.of(
                                                "url", "http://127.0.0.1:" + consolePort,
                                                "session", operatorSession,
                                                "modelBaseUrl", provider + "/v1"))));
    }

    @Test
    @Order(1)
    void realShellConversationApprovalIsolationAndUsage() throws Exception {
        var command = new ProcessBuilder("pnpm", "exec", "playwright", "test", "-c", "playwright.fullstack.config.ts")
                .directory(Path.of("../ai-sub-app").toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.resolve("playwright.log").toFile());
        command.environment().put("AI_E2E_OUTPUT", output.toString());
        Process browser = command.start();
        try {
            assertThat(browser.waitFor(5, java.util.concurrent.TimeUnit.MINUTES))
                    .isTrue();
            assertThat(browser.exitValue())
                    .as("Playwright log: %s", read(output.resolve("playwright.log")))
                    .isZero();
        } finally {
            browser.descendants().forEach(ProcessHandle::destroy);
            browser.destroyForcibly();
        }
        var evidence = JsonMapper.builder().build().readTree(read(output.resolve("provider-evidence.json")));
        assertThat(evidence.path("mcpCalls").asInt()).isEqualTo(2);
        assertThat(evidence.path("modelRequests").asInt()).isEqualTo(6);
        assertThat(evidence.path("streamedChunks").asInt()).isEqualTo(8);
        assertThat(evidence.path("modelAuthorizationValid").asBoolean()).isTrue();
        String metrics = PlatformEnvironment.client("http://127.0.0.1:" + managementPort)
                .get()
                .uri("/actuator/prometheus")
                .retrieve()
                .body(String.class);
        assertThat(metrics)
                .contains(
                        "http_server_requests_seconds_count",
                        "ai_conversation_first_token_seconds_count",
                        "ai_conversation_tokens_total");
        assertThat(metrics)
                .doesNotContain(
                        "FULLSTACK_MODEL_KEY", "deterministic-local-model-key", "APPROVAL_TOOL", "contextToken");
        Files.writeString(output.resolve("metrics.prom"), metrics);
        var usage = call(platform.gateway(), HttpMethod.GET, "/ai/usage", login(false, MEMBER_A), null, 200)
                .path("data");
        assertThat(usage.path("totalTokens").asInt()).isEqualTo(54);
        assertThat(usage.path("toolCalls").asInt()).isEqualTo(2);
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(call(
                                        platform.gateway(),
                                        HttpMethod.GET,
                                        "/platform/tenants/" + TENANT_A + "/ai-usage",
                                        operator,
                                        null,
                                        200)
                                .path("data")
                                .path("totalTokens")
                                .asInt())
                        .isEqualTo(54));
        Files.writeString(output.resolve("usage.json"), usage.toString());
    }

    /** 同一真实 DSH 与模型代理链验证零值、完全缺失和工具回合的部分缺失。 */
    @Test
    @Order(2)
    void missingProviderUsageRetainsQuota() throws Exception {
        String token = login(false, MEMBER_B);
        for (String scenario : List.of("USAGE_ZERO", "USAGE_MISSING", "USAGE_MIXED")) {
            String model = call(
                            platform.gateway(),
                            HttpMethod.POST,
                            "/ai-platform/models",
                            operator,
                            Map.of(
                                    "name",
                                    scenario,
                                    "provider",
                                    "OPENAI_COMPATIBLE",
                                    "modelId",
                                    "local-" + scenario.toLowerCase().replace('_', '-'),
                                    "baseUrl",
                                    provider + "/v1",
                                    "secretRef",
                                    "FULLSTACK_MODEL_KEY",
                                    "capabilities",
                                    List.of("CHAT", "TOOLS"),
                                    "status",
                                    "ENABLED",
                                    "contextWindow",
                                    65536),
                            201)
                    .path("data")
                    .path("id")
                    .asText();
            if (scenario.equals("USAGE_MIXED")) {
                String catalog = call(
                                platform.gateway(),
                                HttpMethod.POST,
                                "/ai-platform/mcp",
                                operator,
                                Map.of(
                                        "name",
                                        "计量验收只读状态",
                                        "url",
                                        provider + "/mcp",
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
                                                "读取状态",
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
                call(
                        platform.gateway(),
                        HttpMethod.POST,
                        "/ai-platform/mcp/" + catalog + "/publish",
                        operator,
                        Map.of(),
                        200);
                call(
                        platform.gateway(),
                        HttpMethod.PUT,
                        "/ai/mcp/" + catalog + "/draft",
                        token,
                        Map.of("enabled", true, "toolNames", List.of("lookup")),
                        200);
                call(platform.gateway(), HttpMethod.POST, "/ai/mcp/" + catalog + "/publish", token, Map.of(), 200);
            }
            String id = call(
                            platform.gateway(),
                            HttpMethod.POST,
                            "/ai/conversations",
                            token,
                            Map.of("title", scenario, "modelId", model),
                            201)
                    .path("data")
                    .path("id")
                    .asText();
            var stream = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(
                                            URI.create(platform.gateway() + "/ai/conversations/" + id + "/messages"))
                                    .header("Authorization", "Bearer " + token)
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(JsonMapper.builder()
                                            .build()
                                            .writeValueAsString(Map.of(
                                                    "text",
                                                    scenario,
                                                    "idempotencyKey",
                                                    UUID.randomUUID().toString()))))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(stream.statusCode()).isEqualTo(200);
            assertThat(stream.body()).contains("event:done");
            var detail = call(platform.gateway(), HttpMethod.GET, "/ai/conversations/" + id, token, null, 200)
                    .path("data");
            var turn = detail.path("turns").get(0);
            var measured = call(platform.gateway(), HttpMethod.GET, "/ai/usage", token, null, 200)
                    .path("data");
            assertThat(measured.path("activeTurns").asInt()).isZero();
            if (scenario.equals("USAGE_ZERO")) {
                assertThat(turn.path("usageReported").asBoolean()).isTrue();
                assertThat(measured.path("unknownUsageTurns").asInt()).isZero();
                assertThat(measured.path("chargedTokens").asLong()).isZero();
            } else {
                assertThat(turn.path("usageReported").asBoolean()).isFalse();
                long estimate = scenario.equals("USAGE_MIXED") ? 131072 : 65536;
                assertThat(measured.path("estimatedTokens").asLong()).isEqualTo(estimate);
                assertThat(measured.path("chargedTokens").asLong()).isEqualTo(estimate);
                assertThat(measured.path("unknownUsageTurns").asInt())
                        .isEqualTo(scenario.equals("USAGE_MIXED") ? 2 : 1);
                assertThat(measured.path("remainingTokens").asLong()).isZero();
                var denied = HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(
                                                platform.gateway() + "/ai/conversations/" + id + "/messages"))
                                        .header("Authorization", "Bearer " + token)
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(JsonMapper.builder()
                                                .build()
                                                .writeValueAsString(Map.of(
                                                        "text",
                                                        "must reject before model",
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString()))))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertThat(denied.statusCode()).isEqualTo(429);
                assertThat(denied.body()).contains("usage.quotaExceeded");
                Files.writeString(output.resolve(scenario.toLowerCase() + ".json"), measured.toString());
                Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                    var projection = call(
                                    platform.gateway(),
                                    HttpMethod.GET,
                                    "/platform/tenants/019a2525-0000-7000-8000-000000000002/ai-usage",
                                    operator,
                                    null,
                                    200)
                            .path("data");
                    assertThat(projection.path("estimatedTokens").asLong()).isEqualTo(estimate);
                    assertThat(projection.path("unknownUsageTurns").asInt())
                            .isEqualTo(scenario.equals("USAGE_MIXED") ? 2 : 1);
                    Files.writeString(
                            output.resolve("platform-" + scenario.toLowerCase() + ".json"), projection.toString());
                });
                if (scenario.equals("USAGE_MISSING")) {
                    // 通过现有运营校正接口开启下一个独立场景，不改变套餐额度。
                    call(
                            platform.gateway(),
                            HttpMethod.PUT,
                            "/ai-platform/usage/tenants/019a2525-0000-7000-8000-000000000002/turns/"
                                    + turn.path("id").asText() + "/estimate",
                            operator,
                            Map.of("estimatedTokens", 0, "reasonCode", "PROVIDER_RECONCILIATION"),
                            200);
                }
            }
            call(platform.gateway(), HttpMethod.DELETE, "/ai/conversations/" + id, token, null, 200);
        }
        var evidence = JsonMapper.builder().build().readTree(read(output.resolve("provider-evidence.json")));
        var modelCalls = new ArrayList<String>();
        evidence.path("usageScenarios").forEach(item -> modelCalls.add(item.asText()));
        assertThat(modelCalls).containsExactly("USAGE_ZERO", "USAGE_MISSING", "USAGE_MIXED", "USAGE_MIXED");
    }

    void start(String name, Path directory, Map<String, String> environment, String... command) throws Exception {
        var builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.resolve(name + ".log").toFile());
        if (name.equals("runner")) {
            builder.environment()
                    .keySet()
                    .removeIf(
                            key -> !List.of("PATH", "TMPDIR", "LANG", "LC_ALL").contains(key));
        }
        builder.environment().putAll(environment);
        processes.add(builder.start());
    }

    @AfterAll
    void stopStack() {
        for (var process : processes) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
        }
        for (var process : processes) {
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        platform.close();
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static int freePort() {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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
        JsonNode session = client.post()
                .uri(prefix + "/auth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class)
                .path("data");
        if (operatorRealm) {
            operatorSession = session;
        }
        return session.path("accessToken").asText();
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
}
