package io.saasforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.saasforge.ai.runner.config.RunnerProperties;
import io.saasforge.ai.runner.service.AgentRuntime;
import io.saasforge.ai.runner.service.HttpAgentRuntime;
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
            assertThat(events.stream().map(AgentRuntime.Event::type)).containsExactly("delta", "completed");
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
