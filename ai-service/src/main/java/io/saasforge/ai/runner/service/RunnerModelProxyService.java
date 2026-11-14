package io.saasforge.ai.runner.service;

import io.saasforge.ai.model.service.ModelSelectionService;
import io.saasforge.ai.runner.config.ModelProxyProperties;
import io.saasforge.ai.usage.service.UsageService;
import io.saasforge.sdk.core.api.ForgeException;
import java.io.IOException;
import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class RunnerModelProxyService {
    private final ModelSelectionService models;
    private final Environment environment;
    private final ModelProxyProperties properties;
    private final ProviderTransport transport;
    private final UsageService usage;
    private final JsonMapper json = JsonMapper.builder().build();

    public RunnerModelProxyService(
            ModelSelectionService models,
            Environment environment,
            ProviderTransport transport,
            ModelProxyProperties properties,
            UsageService usage) {
        this.properties = properties;
        this.usage = usage;
        this.models = models;
        this.environment = environment;
        this.transport = transport;
    }

    public ProviderTransport.Response open(RunnerCapabilityService.GrantedContext capability, JsonNode request) {
        if (!capability.modelId().toString().equals(request.path("model").asText())) {
            throw new ForgeException(RunnerErrorCode.MODEL_DENIED);
        }
        var config = models.resolveForUse(capability.modelId());
        if (!(request instanceof ObjectNode)
                || !request.path("stream").asBoolean()
                || !request.path("messages").isArray()
                || request.path("messages").isEmpty()) {
            throw new ForgeException(RunnerErrorCode.INVALID_MODEL_REQUEST);
        }
        long output = request.path("max_tokens").asLong(4096);
        long bytes = json.writeValueAsBytes(request.path("messages")).length;
        // UTF-8 字节上界是保守预算，避免未知 tokenizer 低估实际输入；目录窗口仍为硬边界。
        if (output <= 0
                || output > config.contextWindow()
                || bytes + output > config.contextWindow()
                || json.writeValueAsBytes(request).length > 1048576) {
            throw new ForgeException(RunnerErrorCode.INVALID_MODEL_REQUEST);
        }
        if (!properties.secretReferences().contains(config.secretReference())) {
            throw new ForgeException(RunnerErrorCode.SECRET_UNAVAILABLE);
        }
        String secret = environment.getProperty(config.secretReference());
        if (secret == null
                || secret.isBlank()
                || secret.length() > 8192
                || secret.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new ForgeException(RunnerErrorCode.SECRET_UNAVAILABLE);
        }
        ObjectNode body = ((ObjectNode) request).deepCopy();
        body.put("model", config.modelId());
        body.put("max_tokens", output);
        String base = config.baseUrl().toString().replaceAll("/$", "");
        URI endpoint = URI.create(base + "/chat/completions");
        usage.modelAttempt(capability.turnId());
        var response = transport.open(endpoint, secret, json.writeValueAsString(body));
        if (response.status() != 200 || !response.contentType().startsWith("text/event-stream")) {
            try {
                response.body().close();
            } catch (IOException ignored) {
            }
            throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
        }
        return response;
    }
}
