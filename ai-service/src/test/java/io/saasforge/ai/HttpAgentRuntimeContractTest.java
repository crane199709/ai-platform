package io.saasforge.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.saasforge.ai.runner.config.RunnerProperties;
import io.saasforge.ai.runner.service.AgentRuntime;
import io.saasforge.ai.runner.service.HttpAgentRuntime;
import io.saasforge.sdk.core.api.ForgeException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HttpAgentRuntimeContractTest {
    @Test
    void reportsCrashAndPartialEventsWithoutReplayingAtTransportLayer() throws Exception {
        for (String preceding : List.of("", "delta", "tool.call", "modelFailure")) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var prompts = new AtomicInteger();
            var replacements = new AtomicInteger();
            server.createContext("/v1/tenants/", exchange -> {
                if (exchange.getRequestURI().getPath().endsWith("/messages")) {
                    int attempt = prompts.incrementAndGet();
                    String frames;
                    if (attempt == 1) {
                        frames = preceding.equals("delta")
                                ? "event: delta\ndata: {\"text\":\"partial\"}\n\n"
                                : preceding.equals("tool.call")
                                        ? "event: tool.call\ndata: {\"callId\":\"call_1\"}\n\n"
                                        : "";
                        frames += "event: error\ndata: {\"code\":\"runner."
                                + (preceding.equals("modelFailure") ? "modelFailure" : "runtimeExited") + "\"}\n\n";
                    } else {
                        frames = "event: completed\ndata: {\"usageReported\":false}\n\n";
                    }
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    byte[] bytes = frames.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } else {
                    var body = JsonMapper.builder().build().readTree(exchange.getRequestBody());
                    if (body.path("replace").asBoolean()) {
                        replacements.incrementAndGet();
                        assertThat(body.path("transcript")).hasSize(1);
                        assertThat(body.path("transcript")
                                        .get(0)
                                        .path("content")
                                        .asText())
                                .isEqualTo("DB history");
                    }
                    exchange.sendResponseHeaders(201, -1);
                }
                exchange.close();
            });
            server.start();
            try {
                var runtime = new HttpAgentRuntime(new RunnerProperties(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "service",
                        Duration.ofSeconds(5)));
                var events = new CopyOnWriteArrayList<AgentRuntime.Event>();
                runtime.run(
                        new AgentRuntime.Run(
                                new AgentRuntime.Session(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "model",
                                        List.of(new AgentRuntime.TranscriptMessage("user", "DB history", null)),
                                        "",
                                        List.of(),
                                        List.of()),
                                "current",
                                "capability"),
                        events::add);
                assertThat(prompts.get()).isEqualTo(1);
                assertThat(replacements.get()).isZero();
                assertThat(events.get(events.size() - 1).type()).isEqualTo("error");
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void admissionRetainsRealPoolCapacityStatusBeforeStreaming() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tenants/", exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "7");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();
        try {
            var runtime = new HttpAgentRuntime(new RunnerProperties(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test", Duration.ofSeconds(5)));
            var session = new AgentRuntime.Session(
                    UUID.randomUUID(), UUID.randomUUID(), "model", List.of(), "", List.of(), List.of());
            assertThatThrownBy(() -> runtime.admit(session))
                    .isInstanceOf(ForgeException.class)
                    .satisfies(error ->
                            assertThat(((ForgeException) error).args()[0]).isEqualTo(7));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void realHttpAdapterRecreatesLostSessionFromDbSnapshotAndForwardsLiveEvents() throws Exception {
        var json = JsonMapper.builder().build();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var messageRequests = new CopyOnWriteArrayList<JsonNode>();
        var creates = new AtomicInteger();
        var prompts = new AtomicInteger();
        var firstDelta = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        server.createContext("/v1/tenants/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-service-token");
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/cancel")) {
                byte[] response = "{\"cancelled\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else if (path.endsWith("/messages")) {
                var request = json.readTree(exchange.getRequestBody());
                messageRequests.add(request);
                assertThat(request.path("contextToken").asText()).isEqualTo("short-capability");
                assertThat(request.toString()).doesNotContain("test-service-token");
                if (prompts.incrementAndGet() == 1) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody()
                            .write("event: delta\ndata: {\"text\":\"live\"}\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    try {
                        finish.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.getResponseBody()
                            .write("event: completed\ndata: {\"usageReported\":false}\n\n"
                                    .getBytes(StandardCharsets.UTF_8));
                }
            } else {
                creates.incrementAndGet();
                requests.add(json.readTree(exchange.getRequestBody()));
                exchange.sendResponseHeaders(201, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            var runtime = new HttpAgentRuntime(new RunnerProperties(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "test-service-token",
                    Duration.ofSeconds(5)));
            UUID tenant = UUID.randomUUID();
            UUID session = UUID.randomUUID();
            String model = UUID.randomUUID().toString();
            var snapshot = List.of(
                    new AgentRuntime.TranscriptMessage("user", "old question", null),
                    new AgentRuntime.TranscriptMessage("assistant", "old answer", null));
            var run = new AgentRuntime.Run(
                    new AgentRuntime.Session(
                            tenant,
                            session,
                            model,
                            snapshot,
                            "Published instructions",
                            List.of(),
                            List.of(Map.of(
                                    "name",
                                    "lookup",
                                    "description",
                                    "Approved read-only lookup",
                                    "inputSchema",
                                    Map.of("type", "object"),
                                    "readOnly",
                                    true))),
                    "next question",
                    "short-capability");
            var events = new CopyOnWriteArrayList<AgentRuntime.Event>();
            var future = CompletableFuture.runAsync(() -> runtime.run(run, event -> {
                events.add(event);
                if (event.type().equals("delta")) {
                    firstDelta.countDown();
                }
            }));
            assertThat(firstDelta.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(future.isDone()).isFalse();
            finish.countDown();
            future.get(5, TimeUnit.SECONDS);
            assertThat(events.stream().map(AgentRuntime.Event::type).filter(type -> !type.equals("attempt")))
                    .containsExactly("delta", "completed");
            assertThat(creates.get()).isEqualTo(2);
            assertThat(prompts.get()).isEqualTo(2);
            assertThat(requests).hasSize(2);
            for (var request : requests) {
                assertThat(request.path("instructions").asText()).isEqualTo("Published instructions");
                assertThat(request.path("transcript").get(0).path("content").asText())
                        .isEqualTo("old question");
                assertThat(request.path("transcript").get(0).has("name")).isFalse();
                assertThat(request.path("model").asText()).isEqualTo(model);
                assertThat(request.path("tools")).hasSize(1);
                assertThat(request.path("tools").get(0).path("name").asText()).isEqualTo("lookup");
            }
            assertThat(messageRequests).hasSize(2);
            for (var message : messageRequests) {
                assertThat(message.path("tools").isArray()).isTrue();
                assertThat(message.path("tools").get(0).path("name").asText()).isEqualTo("lookup");
            }
            assertThat(runtime.cancel(tenant, session)).isTrue();
        } finally {
            finish.countDown();
            server.stop(0);
        }
    }
}
