package io.saasforge.ai.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.ai.skill.entity.*;
import io.saasforge.ai.skill.mapper.TenantSkillMapper;
import io.saasforge.ai.skill.mapper.TenantSkillVersionMapper;
import io.saasforge.ai.skill.model.*;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.security.MembershipGrantStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SkillService {
    private static final JsonMapper SNAPSHOT_JSON = JsonMapper.builder().build();
    private final SkillCatalogService catalog;
    private final TenantSkillMapper settings;
    private final TenantSkillVersionMapper versions;
    private final MembershipGrantStore grants;

    public SkillService(
            SkillCatalogService catalog,
            TenantSkillMapper settings,
            TenantSkillVersionMapper versions,
            MembershipGrantStore grants) {
        this.catalog = catalog;
        this.settings = settings;
        this.versions = versions;
        this.grants = grants;
    }

    public List<TenantSkillResponse> list() {
        require("ai:skill:read");
        return catalog.list().stream()
                .filter(s -> s.publishedVersion() > 0)
                .map(s -> response(catalog.require(s.id()), find(s.id())))
                .toList();
    }

    public TenantSkillResponse get(UUID id) {
        require("ai:skill:read");
        var row = publishedCatalog(id);
        return response(row, find(id));
    }

    @Transactional
    @AuditLog(action = "ai.skill.draft.update", resourceType = "tenantSkill", resourceId = "#id")
    public TenantSkillResponse draft(UUID id, boolean enabled) {
        require("ai:skill:update");
        var skill = catalog.lock(id);
        if (skill.getPublishedVersion() == 0) {
            throw new ForgeException(SkillErrorCode.UNAVAILABLE);
        }
        if (enabled) {
            requireUsable(skill);
        }
        var row = settings.lock(id);
        boolean create = row == null;
        if (create) {
            row = new TenantSkill();
            row.setCatalogId(id);
            row.setPublishedVersion(0);
        }
        row.setDraftEnabled(enabled);
        try {
            if (create) {
                settings.insert(row);
            } else {
                settings.updateById(row);
            }
        } catch (DuplicateKeyException ex) {
            throw new ForgeException(SkillErrorCode.CONFLICT);
        }
        return response(skill, row);
    }

    @Transactional
    @AuditLog(action = "ai.skill.configuration.publish", resourceType = "tenantSkill", resourceId = "#id")
    public TenantSkillResponse publish(UUID id) {
        require("ai:skill:publish");
        var skill = catalog.lock(id);
        var row = settings.lock(id);
        if (row == null) {
            throw new ForgeException(SkillErrorCode.NOT_FOUND);
        }
        Integer contentVersion = null;
        if (row.getDraftEnabled()) {
            requireUsable(skill);
            contentVersion = skill.getPublishedVersion();
        }
        append(row, row.getDraftEnabled(), contentVersion);
        return response(skill, row);
    }

    public List<TenantSkillVersionResponse> history(UUID id) {
        require("ai:skill:read");
        catalog.require(id);
        return versions
                .selectList(new LambdaQueryWrapper<TenantSkillVersion>()
                        .eq(TenantSkillVersion::getCatalogId, id)
                        .orderByDesc(TenantSkillVersion::getVersion))
                .stream()
                .map(v -> new TenantSkillVersionResponse(v.getVersion(), v.getEnabled(), v.getSkillVersion()))
                .toList();
    }

    @Transactional
    @AuditLog(action = "ai.skill.configuration.rollback", resourceType = "tenantSkill", resourceId = "#id")
    public TenantSkillResponse rollback(UUID id, int version) {
        require("ai:skill:rollback");
        var skill = catalog.lock(id);
        var row = settings.lock(id);
        var previous = version(id, version);
        if (row == null) {
            throw new ForgeException(SkillErrorCode.NOT_FOUND);
        }
        if (previous.getEnabled()) {
            requireUsable(skill);
            catalog.version(id, previous.getSkillVersion());
        }
        row.setDraftEnabled(previous.getEnabled());
        append(row, previous.getEnabled(), previous.getSkillVersion());
        return response(skill, row);
    }

    @Transactional(readOnly = true)
    public List<SkillSnapshot> resolvePublished() {
        require("ai:conversation:use");
        var result = new ArrayList<SkillSnapshot>();
        for (var row :
                settings.selectList(new LambdaQueryWrapper<TenantSkill>().orderByAsc(TenantSkill::getCatalogId))) {
            if (row.getPublishedVersion() == 0) {
                continue;
            }
            var selection = version(row.getCatalogId(), row.getPublishedVersion());
            if (!selection.getEnabled()) {
                continue;
            }
            var skill = catalog.require(row.getCatalogId());
            if (!SkillStatus.REVIEWED.name().equals(skill.getStatus()) || skill.getPublishedVersion() == 0) {
                continue;
            }
            var snapshot = catalog.version(skill.getId(), selection.getSkillVersion());
            result.add(new SkillSnapshot(
                    skill.getId(),
                    skill.getCode(),
                    snapshot.getName(),
                    snapshot.getDescription(),
                    snapshot.getVersion(),
                    snapshot.getContent()));
        }
        // 为 Runner 的 1 MiB 请求上限保留 transcript 与协议字段空间，拒绝无法恢复的会话快照。
        if (result.size() > 32 || SNAPSHOT_JSON.writeValueAsBytes(result).length > 512 * 1024) {
            throw new ForgeException(SkillErrorCode.CAPACITY_EXCEEDED);
        }
        return List.copyOf(result);
    }

    private TenantSkill find(UUID id) {
        return settings.selectOne(new LambdaQueryWrapper<TenantSkill>().eq(TenantSkill::getCatalogId, id));
    }

    private TenantSkillVersion version(UUID id, int version) {
        var row = versions.selectOne(new LambdaQueryWrapper<TenantSkillVersion>()
                .eq(TenantSkillVersion::getCatalogId, id)
                .eq(TenantSkillVersion::getVersion, version));
        if (row == null) {
            throw new ForgeException(SkillErrorCode.NOT_FOUND);
        }
        return row;
    }

    private void append(TenantSkill row, boolean enabled, Integer skillVersion) {
        var version = new TenantSkillVersion();
        version.setCatalogId(row.getCatalogId());
        version.setVersion(row.getPublishedVersion() + 1);
        version.setEnabled(enabled);
        version.setSkillVersion(skillVersion);
        versions.insert(version);
        row.setPublishedVersion(version.getVersion());
        settings.updateById(row);
    }

    private SkillEntry publishedCatalog(UUID id) {
        var row = catalog.require(id);
        if (row.getPublishedVersion() == 0) {
            throw new ForgeException(SkillErrorCode.UNAVAILABLE);
        }
        return row;
    }

    private void requireUsable(SkillEntry row) {
        if (!SkillStatus.REVIEWED.name().equals(row.getStatus()) || row.getPublishedVersion() == 0) {
            throw new ForgeException(SkillErrorCode.UNAVAILABLE);
        }
    }

    private TenantSkillResponse response(SkillEntry skill, TenantSkill row) {
        var published =
                skill.getPublishedVersion() > 0 ? catalog.version(skill.getId(), skill.getPublishedVersion()) : null;
        var selected =
                row != null && row.getPublishedVersion() > 0 ? version(skill.getId(), row.getPublishedVersion()) : null;
        return new TenantSkillResponse(
                skill.getId(),
                skill.getCode(),
                published != null ? published.getName() : skill.getName(),
                published != null ? published.getDescription() : skill.getDescription(),
                SkillStatus.valueOf(skill.getStatus()),
                skill.getPublishedVersion(),
                row != null && row.getDraftEnabled(),
                row != null ? row.getPublishedVersion() : 0,
                selected != null && selected.getEnabled(),
                selected != null ? selected.getSkillVersion() : null);
    }

    private void require(String permission) {
        var permissions = grants.current().permissions();
        if (!permissions.contains("ai:conversation:use") || !permissions.contains(permission)) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
    }
}
