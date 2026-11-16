package io.saasforge.ai.runner.service;

import io.saasforge.ai.model.model.ModelCredentialKind;
import io.saasforge.ai.model.service.ModelCredentialCipher;
import io.saasforge.ai.model.service.ModelRuntimeConfiguration;
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
    private final ModelCredentialCipher cipher;
    private final Environment environment;
    private final ModelProxyProperties properties;
    private final ProviderTransport transport;
    private final UsageService usage;
    private final RunnerCapabilityService capabilities;
    private final JsonMapper json = JsonMapper.builder().build();

    public RunnerModelProxyService(
            ModelSelectionService models,
            Environment environment,
            ProviderTransport transport,
            ModelProxyProperties properties,
            UsageService usage,
            RunnerCapabilityService capabilities,
            ModelCredentialCipher cipher) {
        this.cipher = cipher;
        this.capabilities = capabilities;
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
        ObjectNode body = ((ObjectNode) request).deepCopy();
        body.put("model", config.modelId());
        body.put("max_tokens", output);
        String base = config.baseUrl().toString().replaceAll("/$", "");
        URI endpoint = URI.create(base + "/chat/completions");
        // 仅在尚未打开提供方响应流的明确瞬时 HTTP 状态下重试；不重放已输出内容或工具。
        for (int attempt = 0; attempt < 3; attempt++) {
            capabilities.requireActive(capability, "model:chat");
            var current = models.resolveForUse(capability.modelId());
            String secret = credential(current);
            usage.modelAttempt(capability.turnId());
            var response = transport.open(endpoint, secret, json.writeValueAsString(body));
            if (response.status() == 200 && response.contentType().startsWith("text/event-stream")) {
                return response;
            }
            try {
                response.body().close();
            } catch (IOException ignored) {
            }
            boolean transientStatus = response.status() == 429
                    || response.status() == 502
                    || response.status() == 503
                    || response.status() == 504;
            if (!transientStatus || attempt == 2) {
                throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
            }
            usage.preserveUnsettled(capability.turnId());
            try {
                Thread.sleep(100L * (attempt + 1));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
            }
        }
        throw new ForgeException(RunnerErrorCode.UNAVAILABLE);
    }

    private String credential(ModelRuntimeConfiguration config) {
        try {
            String secret;
            if (ModelCredentialKind.API_KEY.name().equals(config.credentialKind())) {
                secret = cipher.decrypt(config.credentialCipher(), config.catalogId());
            } else if (ModelCredentialKind.REFERENCE.name().equals(config.credentialKind())
                    && config.secretReference() != null
                    && properties.secretReferences().contains(config.secretReference())) {
                secret = environment.getProperty(config.secretReference());
            } else {
                throw new IllegalStateException();
            }
            if (secret == null
                    || secret.isBlank()
                    || secret.length() > 8192
                    || secret.chars().anyMatch(c -> c < 33 || c > 126)) {
                throw new IllegalStateException();
            }
            return secret;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 不把解密或环境变量失败原因带入异常链，防止运行时错误成为凭据探测信号。
            throw new ForgeException(RunnerErrorCode.SECRET_UNAVAILABLE);
        }
    }
}
