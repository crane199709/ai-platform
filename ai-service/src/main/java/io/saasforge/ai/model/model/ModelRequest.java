package io.saasforge.ai.model.model;

import jakarta.validation.constraints.*;
import java.util.Set;

public record ModelRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull ModelProvider provider,

        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}")
        String modelId,

        @NotBlank @Size(max = 500) String baseUrl,
        @Pattern(regexp = "[!-~]+") @Size(max = 8192) String apiKey,
        @Pattern(regexp = "[A-Z][A-Z0-9_]{0,127}") String secretRef,
        @NotEmpty Set<@NotNull ModelCapability> capabilities,
        @NotNull ModelStatus status,
        @NotNull @Min(1) Integer contextWindow) {
    @Override
    public String toString() {
        return "ModelRequest[redacted]";
    }
}
