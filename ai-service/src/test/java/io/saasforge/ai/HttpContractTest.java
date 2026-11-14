package io.saasforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpContractTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Test
    void managementHealthIsAvailableWithoutAuthentication() {
        assertThat(RestClient.create("http://127.0.0.1:" + managementPort)
                        .get()
                        .uri("/actuator/health")
                        .retrieve()
                        .body(JsonNode.class)
                        .path("status")
                        .asText())
                .isEqualTo("UP");
    }

    @Test
    void metricsAreAvailableOnlyOnManagementPort() {
        assertThat(RestClient.create("http://127.0.0.1:" + managementPort)
                        .get()
                        .uri("/actuator/prometheus")
                        .retrieve()
                        .body(String.class))
                .contains("jvm_memory_used_bytes");
        client().get().uri("/actuator/prometheus").exchange((request, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(404);
            return null;
        });
    }

    @Test
    void unauthenticatedBusinessRequestReturns401Envelope() {
        client().get().uri("/context").exchange((request, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            var body = response.bodyTo(JsonNode.class);
            assertThat(body.path("code").asText()).isEqualTo("auth.unauthenticated");
            assertThat(body.path("httpStatus").asInt()).isEqualTo(401);
            assertThat(body.path("traceId").asText()).hasSize(32);
            return null;
        });
    }

    @Test
    void openApiDescribesContextEnvelopeAndServiceIdentity() {
        var document = client().get().uri("/v3/api-docs").retrieve().body(JsonNode.class);
        assertThat(document.path("x-forge-service").asText()).isEqualTo("ai-service");
        assertThat(document.path("paths").has("/context")).isTrue();
        assertThat(document.toString()).contains("tenantId", "identityId", "membershipId", "httpStatus");
    }

    private RestClient client() {
        return RestClient.create("http://127.0.0.1:" + port);
    }
}
