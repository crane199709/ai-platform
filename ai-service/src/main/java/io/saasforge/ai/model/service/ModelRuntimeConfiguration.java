package io.saasforge.ai.model.service;

import java.net.URI;
import java.util.UUID;

/** 仅在服务端功能包间传递；引用由受控的密钥解析器解析，不交给浏览器。 */
public record ModelRuntimeConfiguration(
        UUID catalogId, String provider, String modelId, URI baseUrl, String secretReference, int contextWindow) {
    @Override
    public String toString() {
        return "ModelRuntimeConfiguration[redacted]";
    }
}
