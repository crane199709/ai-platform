package io.saasforge.ai.mcp.model;

import jakarta.validation.constraints.*;
import java.util.*;

public record SaveMcpConfigurationRequest(
        @NotNull Boolean enabled, @NotNull @Size(max = 32) List<@NotBlank String> toolNames) {}
