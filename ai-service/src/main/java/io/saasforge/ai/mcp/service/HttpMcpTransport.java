package io.saasforge.ai.mcp.service;

import io.saasforge.sdk.core.api.ForgeException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class HttpMcpTransport implements McpTransport {
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public Response exchange(String method, URI uri, Map<String, String> headers, String body) {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        headers.forEach(request::header);
        request.method(
                method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var pending = client.sendAsync(request.build(), info -> new BoundedBody());
        try {
            var response = pending.get(10, TimeUnit.SECONDS);
            return new Response(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""),
                    response.headers().firstValue("Mcp-Session-Id").orElse(null),
                    response.body());
        } catch (InterruptedException error) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
        } catch (Exception error) {
            pending.cancel(true);
            throw new ForgeException(McpErrorCode.UPSTREAM_FAILURE);
        }
    }
    /** 限制远端流的总字节数；超限立即取消订阅，不让 ByteArraySubscriber 无界缓冲。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            subscription.request(1);
        }

        public void onNext(List<ByteBuffer> values) {
            for (var value : values) {
                if (bytes.size() + value.remaining() > 1048576) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("MCP response exceeds limit"));
                    return;
                }
                byte[] next = new byte[value.remaining()];
                value.get(next);
                bytes.writeBytes(next);
            }
            subscription.request(1);
        }

        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
