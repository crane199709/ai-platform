package io.saasforge.ai.mcp.model;

import jakarta.validation.constraints.*;
import java.util.*;

public record McpStatusRequest(@NotNull Boolean enabled) {}
