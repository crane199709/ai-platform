package io.saasforge.ai.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.ai.model.entity.TenantModelSelection;
import io.saasforge.ai.model.mapper.TenantModelSelectionMapper;
import io.saasforge.ai.model.model.ModelResponse;
import io.saasforge.ai.model.model.ModelSelectionRequest;
import io.saasforge.ai.model.model.ModelSelectionResponse;
import io.saasforge.ai.model.model.ModelStatus;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.security.MembershipGrantStore;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Conversation 只经此接缝获取当前租户可用模型；每回合重新验证禁用状态。 */
@Service
public class ModelSelectionService {
    private final ModelCatalogService catalog;
    private final TenantModelSelectionMapper selections;
    private final MembershipGrantStore grants;

    public ModelSelectionService(
            ModelCatalogService catalog, TenantModelSelectionMapper selections, MembershipGrantStore grants) {
        this.catalog = catalog;
        this.selections = selections;
        this.grants = grants;
    }

    public List<ModelResponse> available() {
        requireUse();
        var selected = selection();
        return enabled().stream()
                .filter(model -> !selected.configured() || selected.modelIds().contains(model.id()))
                .toList();
    }

    public ModelResponse getAvailable(UUID id) {
        requireUse();
        var model = catalog.get(id);
        var selected = selection();
        if (model.status() != ModelStatus.ENABLED
                || (selected.configured() && !selected.modelIds().contains(id))) {
            throw new ForgeException(ModelErrorCode.UNAVAILABLE);
        }
        return model;
    }

    public ModelRuntimeConfiguration resolveForUse(UUID id) {
        getAvailable(id);
        var row = catalog.require(id);
        // 再读配置也须检查状态，避免并发禁用夹在授权与配置读取之间。
        if (!ModelStatus.ENABLED.name().equals(row.getStatus())) {
            throw new ForgeException(ModelErrorCode.UNAVAILABLE);
        }
        return new ModelRuntimeConfiguration(
                row.getId(),
                row.getProvider(),
                row.getModelId(),
                URI.create(row.getBaseUrl()),
                row.getSecretRef(),
                row.getContextWindow());
    }

    public List<ModelResponse> configurable() {
        requireManagement();
        return enabled();
    }

    public ModelSelectionResponse currentSelection() {
        requireManagement();
        return selection();
    }

    @Transactional
    @AuditLog(action = "ai.model.selection.update", resourceType = "tenantModelSelection")
    public ModelSelectionResponse select(ModelSelectionRequest request) {
        requireManagement();
        var ids = request.modelIds().stream().distinct().sorted().toList();
        for (var id : ids) {
            if (catalog.get(id).status() != ModelStatus.ENABLED) {
                throw new ForgeException(ModelErrorCode.UNAVAILABLE);
            }
        }
        var row = selections.selectOne(new LambdaQueryWrapper<TenantModelSelection>());
        boolean create = row == null;
        if (create) {
            row = new TenantModelSelection();
        }
        row.setModelIds(ids.stream().map(UUID::toString).collect(Collectors.joining(",")));
        try {
            if (create) {
                selections.insert(row);
            } else {
                selections.updateById(row);
            }
        } catch (DuplicateKeyException ex) {
            throw new ForgeException(ModelErrorCode.SELECTION_CONFLICT);
        }
        return new ModelSelectionResponse(true, ids);
    }

    private ModelSelectionResponse selection() {
        var row = selections.selectOne(new LambdaQueryWrapper<TenantModelSelection>());
        if (row == null) {
            return new ModelSelectionResponse(false, List.of());
        }
        var ids = row.getModelIds().isEmpty()
                ? List.<UUID>of()
                : Arrays.stream(row.getModelIds().split(","))
                        .map(UUID::fromString)
                        .toList();
        return new ModelSelectionResponse(true, ids);
    }

    private List<ModelResponse> enabled() {
        return catalog.list().stream()
                .filter(model -> model.status() == ModelStatus.ENABLED)
                .toList();
    }

    private void requireUse() {
        if (!grants.current().permissions().contains("ai:conversation:use")) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
    }

    private void requireManagement() {
        requireUse();
        if (!grants.current().permissions().contains("ai:configuration:manage")) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
    }
}
