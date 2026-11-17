package io.saasforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.JsonNode;

/** 以独立进程运行真实 Forge 制品，测试代码只经 HTTP/MQ 契约交互，不依赖平台实现类。 */
final class PlatformEnvironment implements AutoCloseable {
    static final String OPS = "isolated-ai-ops-token";
    static final String PLATFORM_TOKEN = "isolated-platform-bootstrap-token";
    static final Path OUTPUT = Path.of("target/platform-it").toAbsolutePath();
    private static final String MQ_HOME = "/home/rocketmq/rocketmq-5.3.2";
    final SharedContainers.Postgres postgres = SharedContainers.postgres();
    final SharedContainers.Redis redis = SharedContainers.redis();
    final int iamPort = freePort();
    final int platformPort = freePort();
    final int gatewayPort = freePort();
    final String aiToken;
    private final List<Process> processes = new ArrayList<>();
    private ProcessBuilder iamLauncher;
    // POP 检查点编码器按工作线程保留约 4 MiB 堆外缓冲；128 MiB 会使组合审计消费耗尽内存。
    private final GenericContainer<?> mq = new GenericContainer<>("apache/rocketmq:5.3.2")
            .withExposedPorts(8081)
            .withEnv("JAVA_OPT_EXT", "-Xms128m -Xmx256m -Xmn64m -XX:MaxDirectMemorySize=512m")
            .withCopyToContainer(
                    Transferable.of("{\"rocketMQClusterName\":\"DefaultCluster\",\"useEndpointPortFromRequest\":true}"),
                    MQ_HOME + "/conf/rmq-proxy.json")
            .withCommand(
                    "sh",
                    "-c",
                    MQ_HOME + "/bin/mqnamesrv & " + MQ_HOME
                            + "/bin/mqbroker -n localhost:9876 & until " + MQ_HOME
                            + "/bin/mqadmin clusterList -n localhost:9876 | grep -q :10911; do sleep 2; done; exec "
                            + MQ_HOME + "/bin/mqproxy -n localhost:9876")
            .waitingFor(Wait.forListeningPorts(8081))
            .withStartupTimeout(Duration.ofMinutes(3));

    PlatformEnvironment() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "ai-platform-it-cleanup"));
        try {
            Files.createDirectories(OUTPUT);
            // 先核对制品，漏配时不白等 MQ 启动。
            for (String module : List.of("forge-iam", "forge-platform", "forge-gateway")) {
                assertThat(jar(module)).isRegularFile();
            }
            postgres.script("postgres/platform/prepare.sql");
            mq.start();
            for (String topic : List.of("forge-tenant", "forge-app", "forge-audit", "forge-notification")) {
                Awaitility.await()
                        .atMost(Duration.ofMinutes(2))
                        .pollInterval(Duration.ofSeconds(2))
                        .until(() -> {
                            mq.execInContainer(
                                    "sh",
                                    MQ_HOME + "/bin/mqadmin",
                                    "updateTopic",
                                    "-n",
                                    "localhost:9876",
                                    "-c",
                                    "DefaultCluster",
                                    "-t",
                                    topic,
                                    "-a",
                                    "+message.type=NORMAL");
                            return mq.execInContainer(
                                            "sh",
                                            MQ_HOME + "/bin/mqadmin",
                                            "topicRoute",
                                            "-n",
                                            "localhost:9876",
                                            "-t",
                                            topic)
                                    .getStdout()
                                    .contains("brokerName");
                        });
            }
            start(
                    "forge-iam",
                    iamPort,
                    Map.ofEntries(
                            Map.entry("spring.datasource.username", "iam_app"),
                            Map.entry("spring.datasource.password", "iam_app"),
                            Map.entry("spring.flyway.user", "iam_migrator"),
                            Map.entry("spring.flyway.password", "iam_migrator"),
                            Map.entry(
                                    "forge.iam.signing-key-file",
                                    OUTPUT.resolve("tenant.jwk").toString()),
                            Map.entry(
                                    "forge.iam.platform.signing-key-file",
                                    OUTPUT.resolve("operator.jwk").toString()),
                            Map.entry("forge.iam.manifest.tokens.forge-platform", PLATFORM_TOKEN),
                            Map.entry("forge.iam.sub-app.environment", "test")));
            start(
                    "forge-platform",
                    platformPort,
                    Map.of(
                            "spring.datasource.username",
                            "platform_app",
                            "spring.datasource.password",
                            "platform_app",
                            "spring.flyway.user",
                            "platform_migrator",
                            "spring.flyway.password",
                            "platform_migrator",
                            "forge.platform.iam.uri",
                            iam(),
                            "forge.manifest.iam-uri",
                            iam(),
                            "forge.platform.ops.tokens.ai-service",
                            OPS));
            aiToken = client(iam())
                    .post()
                    .uri("/internal/ops/service-credentials")
                    .header("Authorization", "Bearer " + OPS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("service", "ai-service", "actor", "019a2525-0000-7000-8000-000000000071"))
                    .retrieve()
                    .body(JsonNode.class)
                    .path("data")
                    .path("token")
                    .asText();
            assertThat(aiToken).isNotBlank();
        } catch (Exception | AssertionError error) {
            close();
            throw new IllegalStateException("平台夹具启动失败，查看 " + OUTPUT, error);
        }
    }

    String iam() {
        return "http://127.0.0.1:" + iamPort;
    }

    String platform() {
        return "http://127.0.0.1:" + platformPort;
    }

    String gateway() {
        return "http://127.0.0.1:" + gatewayPort;
    }

    String issuer() {
        return gateway() + "/iam";
    }

    String mqEndpoints() {
        return mq.getHost() + ":" + mq.getMappedPort(8081);
    }

    void publishSeededTenantState() throws Exception {
        // SQL 夹具在启动后写入；由真实 IAM 启动发布器发布准入和白名单，不伪造 Redis 授权缓存。
        Process previous = processes.get(0);
        previous.destroy();
        if (!previous.waitFor(15, TimeUnit.SECONDS)) {
            previous.destroyForcibly().waitFor();
        }
        Process current = iamLauncher.start();
        processes.set(0, current);
        awaitReady(current, "forge-iam", iamPort);
    }

    void startGateway(int aiPort) throws Exception {
        var properties = new HashMap<String, String>();
        String prefix = "spring.cloud.gateway.server.webflux.routes";
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("../deploy/forge-gateway.yaml"));
        yaml.getObject().forEach((key, value) -> properties.put(key.toString(), value.toString()));
        // 隔离环境只从本机调用；让网关生成可信的 X-Forwarded-Prefix，OpenAPI 才能还原 /ai。
        properties.put("spring.cloud.gateway.server.webflux.trusted-proxies", "127[.]0[.]0[.]1|0:0:0:0:0:0:0:1|::1");
        // 沿用实际交付的 /ai 路由，只把服务发现 URI 换成本次测试的随机端口。
        properties.put(prefix + "[0].uri", "http://127.0.0.1:" + aiPort);
        properties.put(prefix + "[1].uri", "http://127.0.0.1:" + aiPort);
        int index = 2;
        for (var route : Map.of("iam", iam(), "platform", platform()).entrySet()) {
            properties.put(prefix + "[" + index + "].id", route.getKey());
            properties.put(prefix + "[" + index + "].uri", route.getValue());
            properties.put(prefix + "[" + index + "].predicates[0]", "Path=/" + route.getKey() + "/**");
            properties.put(prefix + "[" + index + "].filters[0]", "StripPrefix=1");
            properties.put("forge.gateway.services[" + index + "].prefix", "/" + route.getKey());
            properties.put(
                    "forge.gateway.services[" + index + "].realm",
                    route.getKey().equals("platform") ? "platform" : "tenant");
            index++;
        }
        start("forge-gateway", gatewayPort, properties);
    }

    private void start(String module, int port, Map<String, String> extra) throws Exception {
        var args = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xms32m",
                "-Xmx256m",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                jar(module).toString(),
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--spring.config.import=",
                "--spring.cloud.nacos.config.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--spring.data.redis.database=" + redis.database(),
                "--forge.ops.token=" + OPS,
                "--forge.business-zone=Asia/Shanghai",
                "--forge.mq.endpoints=" + mqEndpoints(),
                "--forge.mq.ssl=false",
                "--forge.security.issuer=" + issuer(),
                "--forge.security.jwk-set-uri=" + iam() + "/oauth2/jwks",
                "--forge.security.platform.issuer=" + issuer() + "/platform",
                "--forge.security.platform.jwk-set-uri=" + iam() + "/platform/oauth2/jwks"));
        extra.forEach((key, value) -> args.add("--" + key + "=" + value));
        var builder = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .redirectOutput(OUTPUT.resolve(module + ".log").toFile());
        builder.environment().put("FORGE_MANIFEST_TOKEN", PLATFORM_TOKEN);
        if (module.equals("forge-iam")) {
            iamLauncher = builder;
        }
        Process process = builder.start();
        processes.add(process);
        Files.writeString(
                OUTPUT.resolve(module + ".sha256"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar(module))))
                        + "\n");
        awaitReady(process, module, port);
    }

    private void awaitReady(Process process, String module, int port) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    if (!process.isAlive()) {
                        throw new IllegalStateException(module + " exited; see " + OUTPUT);
                    }
                    try {
                        return client("http://127.0.0.1:" + port)
                                .get()
                                .uri("/actuator/health")
                                .exchange((req, res) -> res.getStatusCode().value() == 200);
                    } catch (Exception error) {
                        return false;
                    }
                });
    }

    static RestClient client(String base) {
        return RestClient.builder()
                .baseUrl(base)
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build()))
                .build();
    }

    private static Path jar(String module) {
        String repository = System.getenv("AI_FORGE_RUNTIME_REPOSITORY");
        if (repository == null || repository.isBlank()) {
            throw new IllegalStateException("设置 AI_FORGE_RUNTIME_REPOSITORY 指向包含 Forge 运行制品的 Maven 仓库");
        }
        return Path.of(repository, "io/saasforge", module, "0.2.0", module + "-0.2.0.jar");
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public synchronized void close() {
        for (Process process : processes) {
            process.destroy();
        }
        for (Process process : processes) {
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException error) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        processes.clear();
        mq.stop();
    }
}
