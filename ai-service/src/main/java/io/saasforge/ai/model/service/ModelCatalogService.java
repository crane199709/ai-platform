package io.saasforge.ai.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.ai.model.entity.ModelEntry;
import io.saasforge.ai.model.mapper.ModelMapper;
import io.saasforge.ai.model.model.*;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ForgeException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelCatalogService {
    private final ModelMapper mapper;

    public ModelCatalogService(ModelMapper mapper) {
        this.mapper = mapper;
    }

    public List<ModelResponse> list() {
        return mapper
                .selectList(new LambdaQueryWrapper<ModelEntry>().orderByAsc(ModelEntry::getName, ModelEntry::getId))
                .stream()
                .map(this::response)
                .toList();
    }

    public ModelResponse get(UUID id) {
        return response(require(id));
    }

    @Transactional
    @AuditLog(action = "ai.model.create", resourceType = "model", resourceId = "#result.id()")
    public ModelResponse create(ModelRequest request) {
        var row = new ModelEntry();
        fill(row, request);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException ex) {
            throw new ForgeException(ModelErrorCode.DUPLICATE);
        }
        return response(row);
    }

    @Transactional
    @AuditLog(action = "ai.model.update", resourceType = "model", resourceId = "#id")
    public ModelResponse update(UUID id, ModelRequest request) {
        var row = require(id);
        fill(row, request);
        try {
            mapper.updateById(row);
        } catch (DuplicateKeyException ex) {
            throw new ForgeException(ModelErrorCode.DUPLICATE);
        }
        return response(row);
    }

    @Transactional
    @AuditLog(action = "ai.model.delete", resourceType = "model", resourceId = "#id")
    public UUID delete(UUID id) {
        require(id);
        mapper.deleteById(id);
        return id;
    }

    ModelEntry require(UUID id) {
        var row = mapper.selectById(id);
        if (row == null) {
            throw new ForgeException(ModelErrorCode.NOT_FOUND);
        }
        return row;
    }

    ModelResponse response(ModelEntry row) {
        Set<ModelCapability> capabilities = Arrays.stream(row.getCapabilities().split(","))
                .map(ModelCapability::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        return new ModelResponse(
                row.getId(),
                row.getName(),
                ModelProvider.valueOf(row.getProvider()),
                row.getModelId(),
                row.getBaseUrl(),
                capabilities,
                ModelStatus.valueOf(row.getStatus()),
                row.getContextWindow());
    }

    private void fill(ModelEntry row, ModelRequest request) {
        URI uri;
        try {
            uri = URI.create(request.baseUrl());
        } catch (IllegalArgumentException ex) {
            throw new ForgeException(ModelErrorCode.INVALID_ENDPOINT);
        }
        // 查询参数、userinfo 和 fragment 可能夹带凭据，不能进入目录或响应。
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new ForgeException(ModelErrorCode.INVALID_ENDPOINT);
        }
        row.setName(request.name());
        row.setProvider(request.provider().name());
        row.setModelId(request.modelId());
        row.setBaseUrl(uri.toString());
        row.setSecretRef(request.secretRef());
        row.setCapabilities(
                request.capabilities().stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        row.setStatus(request.status().name());
        row.setContextWindow(request.contextWindow());
    }
}
