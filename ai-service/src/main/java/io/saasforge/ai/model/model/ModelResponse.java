package io.saasforge.ai.model.model;

import java.util.Set;
import java.util.UUID;

public record ModelResponse(
        UUID id,
        String name,
        ModelProvider provider,
        String modelId,
        String baseUrl,
        ModelCredentialKind credentialKind,
        Set<ModelCapability> capabilities,
        ModelStatus status,
        int contextWindow) {}
