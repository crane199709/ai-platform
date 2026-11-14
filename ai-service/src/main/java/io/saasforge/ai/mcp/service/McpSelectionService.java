package io.saasforge.ai.mcp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.ai.mcp.entity.McpToolGrant;
import io.saasforge.ai.mcp.mapper.McpToolGrantMapper;
import io.saasforge.ai.mcp.model.McpToolDefinition;
import io.saasforge.sdk.abac.DataResourceDefinition;
import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.manifest.ResourceManifestCollector;
import io.saasforge.sdk.security.MembershipGrantStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/** Conversation 只保存该接缝产出的不可变已审核声明；每回合及每次调用重新授权。 */
@Service
public class McpSelectionService {
    private final McpCatalogService catalog;
    private final McpConfigurationService configurations;
    private final McpToolGrantMapper resources;
    private final StringRedisTemplate redis;
    private final List<DataResourceDefinition> definitions;

    public McpSelectionService(
            McpCatalogService catalog,
            McpConfigurationService configurations,
            McpToolGrantMapper resources,
            StringRedisTemplate redis) {
        this.catalog = catalog;
        this.configurations = configurations;
        this.resources = resources;
        this.redis = redis;
        this.definitions = ResourceManifestCollector.dataResources(getClass().getClassLoader()).stream()
                .map(DataResourceDefinition::of)
                .toList();
    }

    public List<McpToolSnapshot> snapshotForConversation() {
        var access = freshAccess();
        var snapshots = new ArrayList<McpToolSnapshot>();
        var enabled = catalog.available().stream().map(c -> c.id()).toList();
        for (var row : configurations.publishedRows()) {
            if (!enabled.contains(row.getCatalogId())) {
                continue;
            }
            var current = configurations.current(row.getCatalogId());
            if (!current.getEnabled()) {
                continue;
            }
            var config = catalog.published(row.getCatalogId(), current.getCatalogVersion());
            var liveConfig = catalog.published(row.getCatalogId(), catalog.currentVersion(row.getCatalogId()));
            for (var resource : resources.selectList(new LambdaQueryWrapper<McpToolGrant>()
                    .eq(McpToolGrant::getCatalogId, row.getCatalogId())
                    .eq(McpToolGrant::getTenantVersion, current.getVersion()))) {
                var tool = config.tools().stream()
                        .filter(t -> t.name().equals(resource.getToolName()))
                        .findFirst()
                        .orElseThrow(() -> new ForgeException(McpErrorCode.UNAVAILABLE));
                var liveTool = liveConfig.tools().stream()
                        .filter(t -> t.name().equals(tool.name()))
                        .findFirst()
                        .orElse(null);
                if (liveTool != null && authorized(resource, tool, access) && authorized(resource, liveTool, access)) {
                    snapshots.add(snapshot(
                            row.getCatalogId(),
                            current.getCatalogVersion(),
                            current.getVersion(),
                            tool,
                            Boolean.TRUE.equals(tool.requiresApproval())
                                    || Boolean.TRUE.equals(liveTool.requiresApproval())));
                }
            }
        }
        if (snapshots.size() > 32) {
            throw new ForgeException(McpErrorCode.UNAVAILABLE);
        }
        return List.copyOf(snapshots);
    }

    public List<McpToolSnapshot> authorizeSnapshots(List<McpToolSnapshot> snapshots) {
        var access = freshAccess();
        var allowed = new ArrayList<McpToolSnapshot>();
        for (var snapshot : snapshots) {
            try {
                allowed.add(resolve(snapshot.runtimeName(), access));
            } catch (ForgeException error) {
                if (error.errorCode().httpStatus() != 403
                        && error.errorCode().httpStatus() != 404
                        && error.errorCode().httpStatus() != 409) {
                    throw error;
                }
            }
        }
        return List.copyOf(allowed);
    }

    public McpToolSnapshot resolve(String runtimeName) {
        return resolve(runtimeName, freshAccess());
    }

    private McpToolSnapshot resolve(String runtimeName, Access access) {
        var resource = resources.selectOne(
                new LambdaQueryWrapper<McpToolGrant>().eq(McpToolGrant::getRuntimeName, runtimeName));
        if (resource == null) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
        UUID id = resource.getCatalogId();
        int catalogVersion = resource.getCatalogVersion();
        int tenantVersion = resource.getTenantVersion();
        var current = configurations.current(id);
        if (!current.getEnabled()
                || !McpConfigurationService.names(current.getToolNames()).contains(resource.getToolName())) {
            throw new ForgeException(McpErrorCode.UNAVAILABLE);
        }
        var pinned = configurations.version(id, tenantVersion);
        if (!pinned.getEnabled()
                || pinned.getCatalogVersion() != catalogVersion
                || !McpConfigurationService.names(pinned.getToolNames()).contains(resource.getToolName())) {
            throw new ForgeException(McpErrorCode.UNAVAILABLE);
        }
        var config = catalog.published(id, catalogVersion);
        var tool = config.tools().stream()
                .filter(t -> t.name().equals(resource.getToolName()))
                .findFirst()
                .orElseThrow(() -> new ForgeException(McpErrorCode.UNAVAILABLE));
        var liveTool = catalog.published(id, catalog.currentVersion(id)).tools().stream()
                .filter(t -> t.name().equals(tool.name()))
                .findFirst()
                .orElseThrow(() -> new ForgeException(McpErrorCode.UNAVAILABLE));
        if (!authorized(resource, tool, access) || !authorized(resource, liveTool, access)) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
        var snapshot = snapshot(
                id,
                catalogVersion,
                tenantVersion,
                tool,
                Boolean.TRUE.equals(tool.requiresApproval()) || Boolean.TRUE.equals(liveTool.requiresApproval()));
        if (!snapshot.runtimeName().equals(runtimeName)) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
        return snapshot;
    }

    private boolean authorized(McpToolGrant resource, McpToolDefinition tool, Access access) {
        return Boolean.TRUE.equals(tool.readOnly())
                && tool.permission().endsWith(":read")
                && access.grants().permissions().contains(tool.permission())
                && access.data()
                        .permits(
                                "ai_mcp_tool",
                                Map.of(
                                        "ownerId",
                                        resource.getOwnerId(),
                                        "catalogId",
                                        resource.getCatalogId(),
                                        "toolName",
                                        resource.getToolName()));
    }

    private McpToolSnapshot snapshot(
            UUID id, int catalogVersion, int tenantVersion, McpToolDefinition tool, boolean requiresApproval) {
        var name = runtimeName(id, catalogVersion, tenantVersion, tool.name());
        return new McpToolSnapshot(
                id,
                catalogVersion,
                tenantVersion,
                name,
                tool.name(),
                tool.description(),
                tool.inputSchema(),
                requiresApproval);
    }

    public static List<Map<String, Object>> runtimeTools(List<McpToolSnapshot> snapshots) {
        return snapshots.stream()
                .map(tool -> Map.<String, Object>of(
                        "name",
                        tool.runtimeName(),
                        "description",
                        tool.description(),
                        "inputSchema",
                        tool.inputSchema(),
                        "readOnly",
                        true))
                .toList();
    }

    static String runtimeName(UUID id, int catalogVersion, int tenantVersion, String nativeName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((id + "/" + catalogVersion + "/" + tenantVersion + "/" + nativeName)
                            .getBytes(StandardCharsets.UTF_8));
            return "m" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Access freshAccess() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken)) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
        // 工具边界不沿用 SDK 的跨请求一分钟缓存；同一请求的 RBAC 与 ABAC 共享一次真实 IAM 明细读取。
        var store = new MembershipGrantStore(redis);
        var grants = store.current();
        if (!grants.permissions().contains("ai:conversation:use")) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
        return new Access(grants, new DataPermission(definitions, store));
    }

    private record Access(MembershipGrants grants, DataPermission data) {
        @Override
        public String toString() {
            return "McpAccess[redacted]";
        }
    }
}
