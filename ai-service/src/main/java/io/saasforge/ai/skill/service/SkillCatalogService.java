package io.saasforge.ai.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.ai.skill.entity.SkillEntry;
import io.saasforge.ai.skill.entity.SkillVersion;
import io.saasforge.ai.skill.mapper.SkillMapper;
import io.saasforge.ai.skill.mapper.SkillVersionMapper;
import io.saasforge.ai.skill.model.*;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillCatalogService {
    private final SkillMapper catalog;
    private final SkillVersionMapper versions;

    public SkillCatalogService(SkillMapper catalog, SkillVersionMapper versions) {
        this.catalog = catalog;
        this.versions = versions;
    }

    public List<SkillResponse> list() {
        return catalog.selectList(new LambdaQueryWrapper<SkillEntry>().orderByAsc(SkillEntry::getCode)).stream()
                .map(this::response)
                .toList();
    }

    public SkillResponse get(UUID id) {
        return response(require(id));
    }

    @Transactional
    @AuditLog(action = "ai.skill.create", resourceType = "skill", resourceId = "#result.id()")
    public SkillResponse create(SkillRequest request) {
        var row = new SkillEntry();
        fill(row, request);
        row.setPublishedVersion(0);
        try {
            catalog.insert(row);
        } catch (DuplicateKeyException ex) {
            throw new ForgeException(SkillErrorCode.CONFLICT);
        }
        return response(row);
    }

    @Transactional
    @AuditLog(action = "ai.skill.update", resourceType = "skill", resourceId = "#id")
    public SkillResponse update(UUID id, SkillRequest request) {
        var row = lock(id);
        if (!row.getCode().equals(request.code())) {
            throw new ForgeException(SkillErrorCode.INVALID_CONTENT);
        }
        fill(row, request);
        catalog.updateById(row);
        return response(row);
    }

    @Transactional
    @AuditLog(action = "ai.skill.publish", resourceType = "skill", resourceId = "#id")
    public SkillResponse publish(UUID id) {
        var row = lock(id);
        if (!SkillStatus.REVIEWED.name().equals(row.getStatus())) {
            throw new ForgeException(SkillErrorCode.UNAVAILABLE);
        }
        publish(row);
        return response(row);
    }

    @Transactional
    @AuditLog(action = "ai.skill.rollback", resourceType = "skill", resourceId = "#id")
    public SkillResponse rollback(UUID id, int version) {
        var row = lock(id);
        var previous = version(id, version);
        if (!SkillStatus.REVIEWED.name().equals(row.getStatus())) {
            throw new ForgeException(SkillErrorCode.UNAVAILABLE);
        }
        row.setName(previous.getName());
        row.setDescription(previous.getDescription());
        row.setContent(previous.getContent());
        publish(row);
        return response(row);
    }

    public List<SkillVersionResponse> history(UUID id) {
        require(id);
        return versions
                .selectList(new LambdaQueryWrapper<SkillVersion>()
                        .eq(SkillVersion::getCatalogId, id)
                        .orderByDesc(SkillVersion::getVersion))
                .stream()
                .map(v -> new SkillVersionResponse(v.getVersion(), v.getName(), v.getDescription(), v.getContent()))
                .toList();
    }

    SkillEntry require(UUID id) {
        var row = catalog.selectById(id);
        if (row == null) {
            throw new ForgeException(SkillErrorCode.NOT_FOUND);
        }
        return row;
    }

    SkillVersion version(UUID id, int version) {
        var row = versions.selectOne(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getCatalogId, id)
                .eq(SkillVersion::getVersion, version));
        if (row == null) {
            throw new ForgeException(SkillErrorCode.NOT_FOUND);
        }
        return row;
    }

    SkillEntry lock(UUID id) {
        var row = catalog.lock(id);
        if (row == null) {
            throw new ForgeException(SkillErrorCode.NOT_FOUND);
        }
        return row;
    }

    private void fill(SkillEntry row, SkillRequest request) {
        if (request.content().indexOf(0) >= 0) {
            throw new ForgeException(SkillErrorCode.INVALID_CONTENT);
        }
        row.setCode(request.code());
        row.setName(request.name());
        row.setDescription(request.description());
        row.setContent(request.content());
        row.setStatus(request.status().name());
    }

    private void publish(SkillEntry row) {
        int version = row.getPublishedVersion() + 1;
        var snapshot = new SkillVersion();
        snapshot.setCatalogId(row.getId());
        snapshot.setVersion(version);
        snapshot.setName(row.getName());
        snapshot.setDescription(row.getDescription());
        snapshot.setContent(row.getContent());
        versions.insert(snapshot);
        row.setPublishedVersion(version);
        catalog.updateById(row);
    }

    private SkillResponse response(SkillEntry row) {
        return new SkillResponse(
                row.getId(),
                row.getCode(),
                row.getName(),
                row.getDescription(),
                row.getContent(),
                SkillStatus.valueOf(row.getStatus()),
                row.getPublishedVersion());
    }
}
