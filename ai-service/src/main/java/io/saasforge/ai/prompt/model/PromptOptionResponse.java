package io.saasforge.ai.prompt.model;

import java.util.List;
import java.util.UUID;

/** 会话选择项不包含草稿与正文。 */
public record PromptOptionResponse(UUID id, String name, String source, int publishedVersion, List<String> variables) {}
