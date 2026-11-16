package io.saasforge.ai.model.config;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai.model-credential")
public record ModelCredentialProperties(String key) {
    public ModelCredentialProperties {
        try {
            if (key == null || Base64.getDecoder().decode(key).length != 32) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("AI_MODEL_CREDENTIAL_KEY must be a Base64 encoded 32-byte key");
        }
    }

    @Override
    public String toString() {
        return "ModelCredentialProperties[redacted]";
    }
}
