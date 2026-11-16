package io.saasforge.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.ai.model.config.ModelCredentialProperties;
import io.saasforge.ai.model.entity.ModelEntry;
import io.saasforge.ai.model.model.*;
import io.saasforge.ai.model.service.ModelCredentialCipher;
import io.saasforge.ai.model.service.ModelRuntimeConfiguration;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ModelCredentialCipherTest {
    static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void encryptedCredentialCanOnlyBeRecoveredForItsCatalogEntry() {
        var cipher = new ModelCredentialCipher(new ModelCredentialProperties(TEST_KEY));
        var id = UUID.fromString("019a2525-0000-7000-8000-000000000081");
        var encrypted = cipher.encrypt("test-provider-secret", id);
        assertThat(encrypted).startsWith("v1:").doesNotContain("test-provider-secret");
        assertThat(cipher.decrypt(encrypted, id)).isEqualTo("test-provider-secret");
    }

    @Test
    void ciphertextIsRandomizedAndRejectsAnotherCatalogEntryOrMasterKey() {
        var cipher = new ModelCredentialCipher(new ModelCredentialProperties(TEST_KEY));
        var id = UUID.randomUUID();
        var encrypted = cipher.encrypt("test-provider-secret", id);
        assertThat(cipher.encrypt("test-provider-secret", id)).isNotEqualTo(encrypted);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Model credential unavailable");
        var otherKey = new ModelCredentialCipher(
                new ModelCredentialProperties("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="));
        assertThatThrownBy(() -> otherKey.decrypt(encrypted, id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Model credential unavailable");
    }

    @Test
    void malformedOrTamperedCiphertextNeverDisclosesItsContents() {
        var cipher = new ModelCredentialCipher(new ModelCredentialProperties(TEST_KEY));
        var id = UUID.randomUUID();
        var encrypted = cipher.encrypt("test-provider-secret", id);
        var bytes = Base64.getDecoder().decode(encrypted.substring(3));
        bytes[12] ^= 1;
        var tampered = "v1:" + Base64.getEncoder().encodeToString(bytes);
        for (String value : List.of("v2:secret", "v1:not-base64", "v1:AAAA", tampered)) {
            assertThatThrownBy(() -> cipher.decrypt(value, id))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Model credential unavailable");
        }
    }

    @Test
    void credentialHoldersNeverRenderPlaintextCiphertextOrReferences() {
        var entry = new ModelEntry();
        entry.setSecretRef("PRIVATE_MODEL_REFERENCE");
        entry.setCredentialCipher("test-encrypted-value");
        var request = new ModelRequest(
                "Test",
                ModelProvider.DEEPSEEK,
                "test-model",
                "https://models.example.test",
                "test-private-key",
                "PRIVATE_MODEL_REFERENCE",
                Set.of(ModelCapability.CHAT),
                ModelStatus.ENABLED,
                100);
        var runtime = new ModelRuntimeConfiguration(
                UUID.randomUUID(),
                "DEEPSEEK",
                "test-model",
                URI.create("https://models.example.test"),
                "API_KEY",
                "PRIVATE_MODEL_REFERENCE",
                "test-encrypted-value",
                100);
        assertThat(List.of(entry.toString(), request.toString(), runtime.toString()))
                .allSatisfy(value -> assertThat(value)
                        .doesNotContain("test-private-key", "PRIVATE_MODEL_REFERENCE", "test-encrypted-value"));
    }

    @Test
    void springContextCannotStartWithoutAValidMasterKey() {
        var context = new ApplicationContextRunner().withUserConfiguration(ModelCredentialCipher.class);
        context.run(application -> assertThat(application.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessage("AI_MODEL_CREDENTIAL_KEY must be a Base64 encoded 32-byte key"));
        context.withPropertyValues("ai.model-credential.key=not-base64")
                .run(application -> assertThat(application.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .rootCause()
                        .hasMessage("AI_MODEL_CREDENTIAL_KEY must be a Base64 encoded 32-byte key"));
        context.withPropertyValues("ai.model-credential.key=" + TEST_KEY)
                .run(application -> assertThat(application).hasSingleBean(ModelCredentialCipher.class));
    }

    @Test
    void missingMalformedAndWrongLengthMasterKeysFailBeforeUse() {
        for (String value : new String[] {null, "", "not-base64", "dGVzdA=="}) {
            assertThatThrownBy(() -> new ModelCredentialProperties(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("AI_MODEL_CREDENTIAL_KEY must be a Base64 encoded 32-byte key");
        }
        assertThat(new ModelCredentialProperties(TEST_KEY).toString()).doesNotContain(TEST_KEY);
    }
}
