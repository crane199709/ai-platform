package io.saasforge.ai.mcp.controller;

import io.saasforge.ai.mcp.model.*;
import io.saasforge.ai.mcp.service.McpConfigurationService;
import io.saasforge.sdk.core.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mcp")
public class TenantMcpController {
    private final McpConfigurationService configurations;

    public TenantMcpController(McpConfigurationService configurations) {
        this.configurations = configurations;
    }

    @GetMapping("/catalog")
    public ApiResponse<List<McpCatalogOptionResponse>> catalog() {
        return ApiResponse.ok(configurations.catalog());
    }

    @GetMapping
    public ApiResponse<List<McpConfigurationResponse>> list() {
        return ApiResponse.ok(configurations.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<McpConfigurationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(configurations.get(id));
    }

    @PutMapping("/{id}/draft")
    public ApiResponse<McpConfigurationResponse> save(
            @PathVariable UUID id, @Valid @RequestBody SaveMcpConfigurationRequest request) {
        return ApiResponse.ok(configurations.save(id, request));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<McpConfigurationVersionResponse> publish(@PathVariable UUID id) {
        return ApiResponse.ok(configurations.publish(id));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<McpConfigurationVersionResponse>> history(@PathVariable UUID id) {
        return ApiResponse.ok(configurations.history(id));
    }

    @PostMapping("/{id}/versions/{version}/rollback")
    public ApiResponse<McpConfigurationVersionResponse> rollback(@PathVariable UUID id, @PathVariable int version) {
        return ApiResponse.ok(configurations.rollback(id, version));
    }
}
