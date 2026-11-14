package io.saasforge.ai.mcp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.ai.mcp.entity.McpConfiguration;
import io.saasforge.ai.mcp.entity.McpConfigurationVersion;
import io.saasforge.ai.mcp.entity.McpToolGrant;
import io.saasforge.ai.mcp.mapper.McpConfigurationMapper;
import io.saasforge.ai.mcp.mapper.McpConfigurationVersionMapper;
import io.saasforge.ai.mcp.mapper.McpToolGrantMapper;
import io.saasforge.ai.mcp.model.McpCatalogOptionResponse;
import io.saasforge.ai.mcp.model.McpConfigurationResponse;
import io.saasforge.ai.mcp.model.McpConfigurationVersionResponse;
import io.saasforge.ai.mcp.model.McpToolDefinition;
import io.saasforge.ai.mcp.model.SaveMcpConfigurationRequest;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.security.MembershipGrantStore;
import io.saasforge.sdk.security.TenantContext;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class McpConfigurationService {
    private final McpCatalogService catalog;
    private final McpConfigurationMapper configurations;
    private final McpConfigurationVersionMapper versions;
    private final MembershipGrantStore grants;
    private final McpToolGrantMapper toolGrants;

    public McpConfigurationService(
            McpCatalogService catalog,
            McpConfigurationMapper configurations,
            McpConfigurationVersionMapper versions,
            MembershipGrantStore grants,
            McpToolGrantMapper toolGrants) {
        this.catalog = catalog;
        this.configurations = configurations;
        this.versions = versions;
        this.grants = grants;
        this.toolGrants = toolGrants;
    }

    public List<McpCatalogOptionResponse> catalog() {
        require("ai:mcp:read");
        return catalog.available();
    }

    public List<McpConfigurationResponse> list() {
        require("ai:mcp:read");
        return configurations.selectList(new LambdaQueryWrapper<McpConfiguration>()).stream()
                .map(this::response)
                .toList();
    }

    public McpConfigurationResponse get(UUID catalogId) {
        require("ai:mcp:read");
        return response(requireConfiguration(catalogId, false));
    }

    @Transactional
    @AuditLog(action = "ai.mcp.update", resourceType = "mcp", resourceId = "#catalogId")
    public McpConfigurationResponse save(UUID catalogId, SaveMcpConfigurationRequest request) {
        require("ai:mcp:update");
        int current = catalog.currentVersion(catalogId);
        validate(catalogId, current, request.toolNames());
        var row = configurations.lockByCatalog(catalogId);
        boolean create = row == null;
        if (create) {
            row = new McpConfiguration();
            row.setCatalogId(catalogId);
        }
        row.setEnabled(request.enabled());
        row.setToolNames(String.join(
                ",", request.toolNames().stream().distinct().sorted().toList()));
        try {
            if (create) {
                configurations.insert(row);
            } else {
                configurations.updateById(row);
            }
        } catch (DuplicateKeyException error) {
            throw new ForgeException(McpErrorCode.CONFLICT);
        }
        return response(row);
    }

    @Transactional
    @AuditLog(action = "ai.mcp.publish", resourceType = "mcp", resourceId = "#catalogId")
    public McpConfigurationVersionResponse publish(UUID catalogId) {
        require("ai:mcp:publish");
        var row = requireConfiguration(catalogId, true);
        int current = catalog.currentVersion(catalogId);
        validate(catalogId, current, names(row.getToolNames()));
        var version = new McpConfigurationVersion();
        version.setConfigurationId(row.getId());
        version.setCatalogId(catalogId);
        version.setVersion(historyRows(catalogId).stream()
                        .mapToInt(McpConfigurationVersion::getVersion)
                        .max()
                        .orElse(0)
                + 1);
        version.setEnabled(row.getEnabled());
        version.setToolNames(row.getToolNames());
        version.setCatalogVersion(current);
        // 凭据路径只由已验证租户和目录派生，租户请求不能绑定其他租户的密钥。
        version.setSecretRef("AI_MCP_"
                + TenantContext.current().tenantId().toString().replace("-", "").toUpperCase(Locale.ROOT) + "_"
                + catalogId.toString().replace("-", "").toUpperCase(Locale.ROOT));
        version.setPublishedBy(TenantContext.current().identityId());
        version.setPublishedAt(OffsetDateTime.now());
        versions.insert(version);
        for (String name : names(version.getToolNames())) {
            var grant = new McpToolGrant();
            grant.setCatalogId(catalogId);
            grant.setCatalogVersion(current);
            grant.setRuntimeName(McpSelectionService.runtimeName(catalogId, current, version.getVersion(), name));
            grant.setTenantVersion(version.getVersion());
            grant.setToolName(name);
            grant.setOwnerId(TenantContext.current().membershipId());
            try {
                toolGrants.insert(grant);
            } catch (DuplicateKeyException error) {
                // 短摘要碰撞必须回滚发布，不能把两个原生工具映射到同一运行名。
                throw new ForgeException(McpErrorCode.CONFLICT);
            }
        }
        row.setPublishedVersion(version.getVersion());
        configurations.updateById(row);
        return versionResponse(version, true);
    }

    public List<McpConfigurationVersionResponse> history(UUID catalogId) {
        require("ai:mcp:read");
        var row = requireConfiguration(catalogId, false);
        return historyRows(catalogId).stream()
                .map(v -> versionResponse(v, v.getVersion().equals(row.getPublishedVersion())))
                .toList();
    }

    @Transactional
    @AuditLog(action = "ai.mcp.rollback", resourceType = "mcp", resourceId = "#catalogId")
    public McpConfigurationVersionResponse rollback(UUID catalogId, int version) {
        require("ai:mcp:rollback");
        var row = requireConfiguration(catalogId, true);
        var snapshot = version(catalogId, version);
        catalog.published(catalogId, snapshot.getCatalogVersion());
        row.setPublishedVersion(version);
        configurations.updateById(row);
        return versionResponse(snapshot, true);
    }

    McpConfigurationVersion current(UUID id) {
        var row = requireConfiguration(id, false);
        if (row.getPublishedVersion() == null) {
            throw new ForgeException(McpErrorCode.UNAVAILABLE);
        }
        return version(id, row.getPublishedVersion());
    }

    McpConfigurationVersion version(UUID id, int number) {
        var row = versions.selectOne(new LambdaQueryWrapper<McpConfigurationVersion>()
                .eq(McpConfigurationVersion::getCatalogId, id)
                .eq(McpConfigurationVersion::getVersion, number));
        if (row == null) {
            throw new ForgeException(McpErrorCode.NOT_FOUND);
        }
        return row;
    }

    List<McpConfiguration> publishedRows() {
        return configurations.selectList(
                new LambdaQueryWrapper<McpConfiguration>().isNotNull(McpConfiguration::getPublishedVersion));
    }

    static List<String> names(String value) {
        return value.isEmpty() ? List.of() : Arrays.asList(value.split(","));
    }

    private List<McpConfigurationVersion> historyRows(UUID id) {
        return versions.selectList(new LambdaQueryWrapper<McpConfigurationVersion>()
                .eq(McpConfigurationVersion::getCatalogId, id)
                .orderByDesc(McpConfigurationVersion::getVersion));
    }

    private McpConfiguration requireConfiguration(UUID id, boolean lock) {
        var row = lock
                ? configurations.lockByCatalog(id)
                : configurations.selectOne(
                        new LambdaQueryWrapper<McpConfiguration>().eq(McpConfiguration::getCatalogId, id));
        if (row == null) {
            throw new ForgeException(McpErrorCode.NOT_FOUND);
        }
        return row;
    }

    private void validate(UUID id, int version, List<String> names) {
        var config = catalog.published(id, version);
        var allowed = config.tools().stream().map(McpToolDefinition::name).toList();
        if (names.size() != new HashSet<>(names).size() || !allowed.containsAll(names)) {
            throw new ForgeException(McpErrorCode.INVALID_CATALOG);
        }
    }

    private void require(String code) {
        if (!grants.current().permissions().containsAll(Set.of("ai:conversation:use", code))) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
    }

    private McpConfigurationResponse response(McpConfiguration row) {
        var published =
                row.getPublishedVersion() == null ? null : version(row.getCatalogId(), row.getPublishedVersion());
        return new McpConfigurationResponse(
                row.getCatalogId(),
                row.getEnabled(),
                names(row.getToolNames()),
                row.getPublishedVersion(),
                published == null ? null : published.getEnabled(),
                published == null ? null : published.getCatalogVersion());
    }

    private McpConfigurationVersionResponse versionResponse(McpConfigurationVersion row, boolean current) {
        return new McpConfigurationVersionResponse(
                row.getCatalogId(),
                row.getVersion(),
                row.getEnabled(),
                names(row.getToolNames()),
                row.getCatalogVersion(),
                row.getPublishedBy(),
                row.getPublishedAt(),
                current);
    }
}
