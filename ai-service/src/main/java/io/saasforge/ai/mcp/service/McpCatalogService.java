package io.saasforge.ai.mcp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.ai.mcp.entity.McpCatalog;
import io.saasforge.ai.mcp.entity.McpCatalogVersion;
import io.saasforge.ai.mcp.mapper.McpCatalogMapper;
import io.saasforge.ai.mcp.mapper.McpCatalogVersionMapper;
import io.saasforge.ai.mcp.model.McpCatalogOptionResponse;
import io.saasforge.ai.mcp.model.McpCatalogRequest;
import io.saasforge.ai.mcp.model.McpCatalogResponse;
import io.saasforge.ai.mcp.model.McpCatalogVersionResponse;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.OperatorContext;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class McpCatalogService {
    private final McpCatalogMapper catalogs;
    private final McpCatalogVersionMapper versions;
    private final McpConnectionService connections;
    private final JsonMapper json;

    public McpCatalogService(
            McpCatalogMapper catalogs,
            McpCatalogVersionMapper versions,
            McpConnectionService connections,
            JsonMapper json) {
        this.catalogs = catalogs;
        this.versions = versions;
        this.connections = connections;
        this.json = json;
    }

    public List<McpCatalogResponse> list() {
        return catalogs.selectList(new LambdaQueryWrapper<McpCatalog>().orderByAsc(McpCatalog::getId)).stream()
                .map(this::response)
                .toList();
    }

    public McpCatalogResponse get(UUID id) {
        return response(require(id, false));
    }

    @Transactional
    @AuditLog(action = "ai.mcp.catalog.create", resourceType = "mcpCatalog", resourceId = "#result.id()")
    public McpCatalogResponse create(McpCatalogRequest request) {
        validate(request);
        var row = new McpCatalog();
        row.setName(request.name());
        row.setConfiguration(json.writeValueAsString(request));
        row.setEnabled(false);
        catalogs.insert(row);
        return response(row);
    }

    @Transactional
    @AuditLog(action = "ai.mcp.catalog.update", resourceType = "mcpCatalog", resourceId = "#id")
    public McpCatalogResponse save(UUID id, McpCatalogRequest request) {
        validate(request);
        var row = require(id, true);
        row.setName(request.name());
        row.setConfiguration(json.writeValueAsString(request));
        catalogs.updateById(row);
        return response(row);
    }

    @Transactional
    @AuditLog(action = "ai.mcp.catalog.publish", resourceType = "mcpCatalog", resourceId = "#id")
    public McpCatalogVersionResponse publish(UUID id) {
        var row = require(id, true);
        var config = decode(row.getConfiguration());
        validate(config);
        var version = new McpCatalogVersion();
        version.setCatalogId(id);
        var history = versionRows(id);
        version.setVersion(
                history.stream().mapToInt(McpCatalogVersion::getVersion).max().orElse(0) + 1);
        version.setConfiguration(row.getConfiguration());
        version.setReviewedBy(OperatorContext.current().operatorId());
        version.setReviewedAt(OffsetDateTime.now());
        versions.insert(version);
        row.setPublishedVersion(version.getVersion());
        row.setEnabled(true);
        catalogs.updateById(row);
        return response(version, true);
    }

    public List<McpCatalogVersionResponse> history(UUID id) {
        var row = require(id, false);
        return versionRows(id).stream()
                .map(v -> response(v, v.getVersion().equals(row.getPublishedVersion())))
                .toList();
    }

    @Transactional
    @AuditLog(action = "ai.mcp.catalog.rollback", resourceType = "mcpCatalog", resourceId = "#id")
    public McpCatalogVersionResponse rollback(UUID id, int number) {
        var row = require(id, true);
        var version = requireVersion(id, number);
        validate(decode(version.getConfiguration()));
        row.setPublishedVersion(number);
        catalogs.updateById(row);
        return response(version, true);
    }

    @Transactional
    @AuditLog(action = "ai.mcp.catalog.status", resourceType = "mcpCatalog", resourceId = "#id")
    public McpCatalogResponse status(UUID id, boolean enabled) {
        var row = require(id, true);
        if (enabled && row.getPublishedVersion() == null) {
            throw new ForgeException(McpErrorCode.UNAVAILABLE);
        }
        row.setEnabled(enabled);
        catalogs.updateById(row);
        return response(row);
    }

    public List<McpCatalogOptionResponse> available() {
        return catalogs.selectList(new LambdaQueryWrapper<McpCatalog>().eq(McpCatalog::getEnabled, true)).stream()
                .filter(r -> r.getPublishedVersion() != null)
                .map(r -> {
                    var config = published(r.getId(), r.getPublishedVersion());
                    return new McpCatalogOptionResponse(
                            r.getId(), config.name(), r.getPublishedVersion(), config.tools());
                })
                .toList();
    }

    McpCatalogRequest published(UUID id, int number) {
        var row = require(id, false);
        if (!row.getEnabled() || row.getPublishedVersion() == null) {
            throw new ForgeException(McpErrorCode.UNAVAILABLE);
        }
        return decode(requireVersion(id, number).getConfiguration());
    }

    int currentVersion(UUID id) {
        var row = require(id, false);
        if (!row.getEnabled() || row.getPublishedVersion() == null) {
            throw new ForgeException(McpErrorCode.UNAVAILABLE);
        }
        return row.getPublishedVersion();
    }

    McpCatalogRequest decode(String value) {
        return json.readValue(value, McpCatalogRequest.class);
    }

    private void validate(McpCatalogRequest request) {
        URI uri;
        try {
            uri = URI.create(request.url());
        } catch (IllegalArgumentException error) {
            throw new ForgeException(McpErrorCode.INVALID_CATALOG);
        }
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getFragment() != null) {
            throw new ForgeException(McpErrorCode.INVALID_CATALOG);
        }
        boolean validMode = switch (request.credentialMode()) {
            case "PUBLIC" -> request.isolation().equals("PUBLIC_DATA");
            case "TENANT_SECRET" -> request.isolation().equals("TENANT_CREDENTIAL");
            case "BUSINESS_IDENTITY" ->
                request.isolation().equals("BUSINESS_RBAC_ABAC") && connections.isTrustedBusiness(uri);
            default -> false;
        };
        var names = new HashSet<String>();
        if (!validMode || request.tools().size() > 32 || request.tools().isEmpty()) {
            throw new ForgeException(McpErrorCode.INVALID_CATALOG);
        }
        for (var tool : request.tools()) {
            // 只采用运营明确审核的实际白名单；不从上游 discovery/hint 自动扩展权限。
            if (!Boolean.TRUE.equals(tool.readOnly())
                    || !tool.permission().endsWith(":read")
                    || !names.add(tool.name())
                    || !"object".equals(tool.inputSchema().get("type"))
                    || json.writeValueAsString(tool.inputSchema()).length() > 16384) {
                throw new ForgeException(McpErrorCode.INVALID_CATALOG);
            }
        }
    }

    private McpCatalog require(UUID id, boolean lock) {
        var row = lock ? catalogs.lockById(id) : catalogs.selectById(id);
        if (row == null) {
            throw new ForgeException(McpErrorCode.NOT_FOUND);
        }
        return row;
    }

    private McpCatalogVersion requireVersion(UUID id, int number) {
        var row = versions.selectOne(new LambdaQueryWrapper<McpCatalogVersion>()
                .eq(McpCatalogVersion::getCatalogId, id)
                .eq(McpCatalogVersion::getVersion, number));
        if (row == null) {
            throw new ForgeException(McpErrorCode.NOT_FOUND);
        }
        return row;
    }

    private List<McpCatalogVersion> versionRows(UUID id) {
        return versions.selectList(new LambdaQueryWrapper<McpCatalogVersion>()
                .eq(McpCatalogVersion::getCatalogId, id)
                .orderByDesc(McpCatalogVersion::getVersion));
    }

    private McpCatalogResponse response(McpCatalog row) {
        return new McpCatalogResponse(
                row.getId(), decode(row.getConfiguration()), row.getPublishedVersion(), row.getEnabled());
    }

    private McpCatalogVersionResponse response(McpCatalogVersion row, boolean current) {
        return new McpCatalogVersionResponse(
                row.getCatalogId(),
                row.getVersion(),
                decode(row.getConfiguration()),
                row.getReviewedBy(),
                row.getReviewedAt(),
                current);
    }
}
