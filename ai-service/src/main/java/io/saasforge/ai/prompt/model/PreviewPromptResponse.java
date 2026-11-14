package io.saasforge.ai.prompt.model;

public record PreviewPromptResponse(String content) {
    @Override
    public String toString() {
        return "PreviewPromptResponse[content omitted]";
    }
}
