package io.saasforge.ai.conversation.controller;

import io.saasforge.ai.conversation.model.ConversationResponse;
import io.saasforge.ai.conversation.model.CreateConversationRequest;
import io.saasforge.ai.conversation.service.ConversationService;
import io.saasforge.ai.conversation.service.ConversationStreamingService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/conversations")
public class ConversationController {
    private final ConversationService conversations;
    private final ConversationStreamingService streaming;

    public ConversationController(ConversationService conversations, ConversationStreamingService streaming) {
        this.streaming = streaming;
        this.conversations = conversations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("ai:conversation:use")
    public ApiResponse<ConversationResponse> create(@Valid @RequestBody CreateConversationRequest request) {
        return ApiResponse.created(conversations.create(request));
    }

    @GetMapping
    @RequiresPermission("ai:conversation:use")
    public ApiResponse<List<ConversationResponse>> list() {
        return ApiResponse.ok(conversations.list());
    }

    @GetMapping("/{id}")
    @RequiresPermission("ai:conversation:use")
    public ApiResponse<ConversationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(conversations.get(id));
    }

    @PatchMapping("/{id}")
    @RequiresPermission("ai:conversation:use")
    public ApiResponse<ConversationResponse> rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        return ApiResponse.ok(conversations.rename(id, request.title()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("ai:conversation:use")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        conversations.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission("ai:conversation:use")
    public ApiResponse<CancelResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(new CancelResponse(streaming.cancel(id)));
    }

    public record CancelResponse(boolean cancelled) {}

    public record RenameRequest(@NotBlank @Size(max = 200) String title) {}
}
