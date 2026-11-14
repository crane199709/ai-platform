package io.saasforge.ai.mcp.controller;

import io.saasforge.ai.mcp.model.McpCatalogRequest;
import io.saasforge.ai.mcp.model.McpCatalogResponse;
import io.saasforge.ai.mcp.model.McpCatalogVersionResponse;
import io.saasforge.ai.mcp.service.McpCatalogService;
import io.saasforge.sdk.core.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/platform/mcp")
@PreAuthorize("@platformAccessService.hasPermission('platform:ops:manage')")
public class PlatformMcpController {
    private final McpCatalogService catalog;

    public PlatformMcpController(McpCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ApiResponse<List<McpCatalogResponse>> list() {
        return ApiResponse.ok(catalog.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<McpCatalogResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(catalog.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<McpCatalogResponse> create(@Valid @RequestBody McpCatalogRequest request) {
        return ApiResponse.created(catalog.create(request));
    }

    @PutMapping("/{id}/draft")
    public ApiResponse<McpCatalogResponse> save(@PathVariable UUID id, @Valid @RequestBody McpCatalogRequest request) {
        return ApiResponse.ok(catalog.save(id, request));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<McpCatalogVersionResponse> publish(@PathVariable UUID id) {
        return ApiResponse.ok(catalog.publish(id));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<McpCatalogVersionResponse>> history(@PathVariable UUID id) {
        return ApiResponse.ok(catalog.history(id));
    }

    @PostMapping("/{id}/versions/{version}/rollback")
    public ApiResponse<McpCatalogVersionResponse> rollback(@PathVariable UUID id, @PathVariable int version) {
        return ApiResponse.ok(catalog.rollback(id, version));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<McpCatalogResponse> status(@PathVariable UUID id, @Valid @RequestBody McpStatusRequest request) {
        return ApiResponse.ok(catalog.status(id, request.enabled()));
    }

    public record McpStatusRequest(@NotNull Boolean enabled) {}
}
