package io.saasforge.ai.model.model;

import java.util.List;
import java.util.UUID;

public record ModelSelectionResponse(boolean configured, List<UUID> modelIds) {}
