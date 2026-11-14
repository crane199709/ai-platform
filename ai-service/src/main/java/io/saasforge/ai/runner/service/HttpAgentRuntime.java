package io.saasforge.ai.runner.service;

import io.saasforge.ai.runner.config.RunnerProperties;
import io.saasforge.sdk.core.api.ForgeException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class HttpAgentRuntime implements AgentRuntime {
    private final RunnerProperties properties;
    private final HttpClient client =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpAgentRuntime(RunnerProperties properties) {
        this.properties = properties;
    }

    public void admit(Session session) {
        create(session, true);
    }

    public void restore(Session session) {
        create(session, true);
    }

    public void run(Run request, Consumer<Event> emit) {
        var session = request.session();
        create(session, false);
        var body = Map.of(
                "text",
                request.text(),
                "contextToken",
                request.contextToken(),
                "model",
                session.model(),
                "tools",
                session.tools());
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean terminalEvent = false;
            emit.accept(new Event(
                    "attempt",
                    json.valueToTree(Map.of("attemptId", UUID.randomUUID().toString(), "retry", attempt > 0))));
            try {
                var response =
                        client.send(post(path(session) + "/messages", body), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 404 && attempt == 0) {
                    response.body().close();
                    create(session, true);
                    continue;
                }
                if (response.statusCode() != 200) {
                    response.body().close();
                    checkCapacity(response);
                    throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
                }
                try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String type = "message";
                    var data = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (!data.isEmpty()) {
                                var event = new Event(type, json.readTree(data.toString()));
                                if (type.equals("error") || type.equals("completed") || type.equals("cancelled")) {
                                    terminalEvent = true;
                                }
                                emit.accept(event);
                            }
                            type = "message";
                            data.setLength(0);
                        } else if (line.startsWith("event:")) {
                            type = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (!data.isEmpty()) {
                                data.append('\n');
                            }
                            data.append(line.substring(5).stripLeading());
                            if (data.length() > 1048576) {
                                throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
                            }
                        }
                    }
                }
                if (!terminalEvent) {
                    throw new ForgeException(RunnerErrorCode.DISCONNECTED);
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
            } catch (IOException e) {
                throw new ForgeException(RunnerErrorCode.DISCONNECTED);
            }
        }
    }

    private void checkCapacity(HttpResponse<?> response) {
        if (response.statusCode() == 429) {
            int seconds = 5;
            try {
                seconds = Math.max(
                        1,
                        Math.min(
                                300,
                                Integer.parseInt(response.headers()
                                        .firstValue("Retry-After")
                                        .orElse("5"))));
            } catch (NumberFormatException ignored) {
            }
            throw new ForgeException(RunnerErrorCode.POOL_FULL, seconds);
        }
    }

    public void deleteSession(UUID tenantId, UUID sessionId) {
        delete("/v1/tenants/" + tenantId + "/sessions/" + sessionId);
    }

    public void deleteTenant(UUID tenantId) {
        delete("/v1/tenants/" + tenantId);
    }

    private void delete(String path) {
        try {
            var response = client.send(
                    HttpRequest.newBuilder(
                                    URI.create(properties.baseUri().toString().replaceAll("/$", "") + path))
                            .timeout(properties.timeout())
                            .header("Authorization", "Bearer " + properties.serviceToken())
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 204 && response.statusCode() != 404) {
                throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
        } catch (IOException e) {
            throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
        }
    }

    private void create(Session session, boolean replace) {
        var body = new LinkedHashMap<String, Object>();
        body.put("sessionId", session.sessionId());
        if (replace) {
            body.put("replace", true);
        }
        body.put("model", session.model());
        body.put("transcript", session.transcript());
        body.put("instructions", session.instructions());
        body.put("tools", session.tools());
        if (!session.skills().isEmpty()) {
            body.put("skills", session.skills());
        }
        try {
            var response = client.send(
                    post("/v1/tenants/" + session.tenantId() + "/sessions", body),
                    HttpResponse.BodyHandlers.ofString());
            checkCapacity(response);
            if (response.statusCode() != 201
                    && !(!replace
                            && response.statusCode() == 409
                            && json.readTree(response.body())
                                    .path("code")
                                    .asText()
                                    .equals("runner.sessionExists"))) {
                throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
        } catch (IOException e) {
            throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
        }
    }

    public boolean cancel(UUID tenantId, UUID sessionId) {
        try {
            var response = client.send(
                    post("/v1/tenants/" + tenantId + "/sessions/" + sessionId + "/cancel", null),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200
                    && json.readTree(response.body()).path("cancelled").asBoolean();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private String path(Session session) {
        return "/v1/tenants/" + session.tenantId() + "/sessions/" + session.sessionId();
    }

    private HttpRequest post(String path, Object body) {
        String encoded = body == null ? "" : json.writeValueAsString(body);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 1048576) {
            throw new ForgeException(RunnerErrorCode.LIMIT);
        }
        return HttpRequest.newBuilder(URI.create(properties.baseUri().toString().replaceAll("/$", "") + path))
                .timeout(path.endsWith("/cancel") ? Duration.ofSeconds(5) : properties.timeout())
                .header("Authorization", "Bearer " + properties.serviceToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encoded))
                .build();
    }
}
