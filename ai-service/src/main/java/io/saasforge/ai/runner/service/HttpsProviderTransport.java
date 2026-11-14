package io.saasforge.ai.runner.service;

import io.saasforge.sdk.core.api.ForgeException;
import jakarta.annotation.PreDestroy;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class HttpsProviderTransport implements ProviderTransport {
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(action -> {
        var thread = new Thread(action, "model-stream-deadline");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void close() {
        deadlines.shutdownNow();
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public Response open(URI endpoint, String secret, String body) {
        if (!"https".equals(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            throw new ForgeException(RunnerErrorCode.INVALID_MODEL_REQUEST);
        }
        try {
            var response = client.send(
                    HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(120))
                            .header("Authorization", "Bearer " + secret)
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            var deadline = deadlines.schedule(
                    () -> {
                        try {
                            response.body().close();
                        } catch (IOException ignored) {
                        }
                    },
                    120,
                    TimeUnit.SECONDS);
            var guarded = new FilterInputStream(response.body()) {
                @Override
                public void close() throws IOException {
                    deadline.cancel(false);
                    super.close();
                }
            };
            return new Response(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""),
                    guarded);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
        } catch (IOException e) {
            throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
        }
    }
}
