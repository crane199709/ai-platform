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

    public void run(Run request, Consumer<Event> emit) {
        var session = request.session();
        create(session);
        var body = Map.of("text", request.text(), "contextToken", request.contextToken(), "model", session.model());
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var response =
                        client.send(post(path(session) + "/messages", body), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 404 && attempt == 0) {
                    response.body().close();
                    create(session);
                    continue;
                }
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
                }
                try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String type = "message";
                    var data = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (!data.isEmpty()) {
                                emit.accept(new Event(type, json.readTree(data.toString())));
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
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
            } catch (IOException e) {
                throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
            }
        }
    }

    private void create(Session session) {
        var body = new LinkedHashMap<String, Object>();
        body.put("sessionId", session.sessionId());
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
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 201 && response.statusCode() != 409) {
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
