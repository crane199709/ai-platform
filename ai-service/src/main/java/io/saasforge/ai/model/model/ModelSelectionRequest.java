package io.saasforge.ai.model.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ModelSelectionRequest(
        @NotNull @Size(max = 1000) List<@NotNull UUID> modelIds) {}
