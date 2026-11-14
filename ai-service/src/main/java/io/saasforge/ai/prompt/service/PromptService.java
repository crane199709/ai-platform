package io.saasforge.ai.prompt.service;

import io.saasforge.ai.prompt.entity.PlatformPromptPreset;
import io.saasforge.ai.prompt.entity.PromptTemplate;
import io.saasforge.ai.prompt.entity.PromptVersion;
import io.saasforge.ai.prompt.mapper.PromptPresetMapper;
import io.saasforge.ai.prompt.mapper.PromptTemplateMapper;
import io.saasforge.ai.prompt.mapper.PromptVersionMapper;
import io.saasforge.ai.prompt.model.PreviewPromptRequest;
import io.saasforge.ai.prompt.model.PreviewPromptResponse;
import io.saasforge.ai.prompt.model.PromptOptionResponse;
import io.saasforge.ai.prompt.model.PromptResponse;
import io.saasforge.ai.prompt.model.PromptVersionResponse;
import io.saasforge.ai.prompt.model.ResolvedPromptResponse;
import io.saasforge.ai.prompt.model.SavePromptRequest;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.security.TenantContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromptService {
    private static final UUID DEFAULT_TEMPLATE_ID = UUID.fromString("019a2525-0700-7000-8000-000000000001");
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z_][A-Za-z0-9_]{0,63})}}");
    private final PromptTemplateMapper templates;
    private final PromptVersionMapper versions;
    private final PromptPresetMapper presets;

    /** SDK 租户清理事务内调用，包含历史逻辑删除行。 */
    public void eraseTenantData() {
        templates.eraseTenantData();
        versions.eraseTenantData();
    }

    public PromptService(PromptTemplateMapper templates, PromptVersionMapper versions, PromptPresetMapper presets) {
        this.templates = templates;
        this.versions = versions;
        this.presets = presets;
    }

    @Transactional
    public PromptResponse create(SavePromptRequest request) {
        var row = new PromptTemplate();
        copyDraft(row, request);
        templates.insert(row);
        return response(row);
    }

    public PromptResponse get(UUID id) {
        var preset = presets.findById(id);
        return preset == null ? response(requireTemplate(id, false)) : response(preset);
    }

    @Transactional(readOnly = true)
    public List<PromptResponse> list() {
        var result = new ArrayList<PromptResponse>();
        presets.findAll().forEach(preset -> result.add(response(preset)));
        templates.findAll(false).forEach(template -> result.add(response(template)));
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public List<PromptOptionResponse> availablePublished() {
        var result = new ArrayList<PromptOptionResponse>();
        presets.findAll()
                .forEach(preset -> result.add(new PromptOptionResponse(
                        preset.getId(),
                        preset.getName(),
                        "PLATFORM",
                        preset.getVersion(),
                        variables(preset.getVariableNames()))));
        templates
                .findAll(true)
                .forEach(template -> result.add(new PromptOptionResponse(
                        template.getId(),
                        template.getName(),
                        "TENANT",
                        template.getPublishedVersion(),
                        variables(requireVersion(template.getId(), template.getPublishedVersion())
                                .getVariableNames()))));
        return List.copyOf(result);
    }

    @Transactional
    public PromptResponse saveDraft(UUID id, SavePromptRequest request) {
        var row = requireTemplate(id, true);
        copyDraft(row, request);
        templates.updateById(row);
        return response(row);
    }

    @Transactional
    public PromptVersionResponse publish(UUID id) {
        var row = requireTemplate(id, true);
        var snapshot = new PromptVersion();
        snapshot.setTemplateId(id);
        snapshot.setVersion(versions.nextVersion(id));
        snapshot.setContent(row.getContent());
        snapshot.setVariableNames(row.getVariableNames());
        snapshot.setPublishedBy(TenantContext.current().identityId());
        snapshot.setPublishedAt(OffsetDateTime.now());
        versions.insert(snapshot);
        row.setPublishedVersion(snapshot.getVersion());
        templates.updateById(row);
        return versionResponse(snapshot, true);
    }

    @Transactional(readOnly = true)
    public List<PromptVersionResponse> history(UUID id) {
        var preset = presets.findById(id);
        if (preset != null) {
            return List.of(versionResponse(preset));
        }
        var row = requireTemplate(id, false);
        return versions.history(id).stream()
                .map(v -> versionResponse(v, v.getVersion().equals(row.getPublishedVersion())))
                .toList();
    }

    @Transactional
    public PromptVersionResponse rollback(UUID id, int version) {
        var row = requireTemplate(id, true);
        var snapshot = requireVersion(id, version);
        row.setPublishedVersion(version);
        templates.updateById(row);
        return versionResponse(snapshot, true);
    }
    /** 会话启动接缝：只消费已发布的不可变快照，调用方持久化返回的版本与内容。 */
    @Transactional(readOnly = true)
    @RequiresPermission("ai:conversation:use")
    public ResolvedPromptResponse resolvePublished(UUID templateId, Integer version, Map<String, String> variables) {
        UUID selectedId = templateId == null ? DEFAULT_TEMPLATE_ID : templateId;
        var preset = presets.findById(selectedId);
        if (preset != null) {
            if (version != null && !version.equals(preset.getVersion())) {
                throw new ForgeException(PromptErrorCode.NOT_FOUND);
            }
            return new ResolvedPromptResponse(
                    selectedId,
                    preset.getVersion(),
                    renderContent(preset.getContent(), variables(preset.getVariableNames()), variables));
        }
        var template = requireTemplate(selectedId, false);
        Integer selected = version == null ? template.getPublishedVersion() : version;
        if (selected == null) {
            throw new ForgeException(PromptErrorCode.NOT_PUBLISHED);
        }
        var snapshot = requireVersion(selectedId, selected);
        return new ResolvedPromptResponse(
                selectedId,
                selected,
                renderContent(snapshot.getContent(), variables(snapshot.getVariableNames()), variables));
    }

    public PreviewPromptResponse preview(PreviewPromptRequest request) {
        return new PreviewPromptResponse(renderContent(request.content(), request.variables(), request.values()));
    }

    private String renderContent(String content, List<String> declared, Map<String, String> values) {
        validateTemplate(content, declared);
        if (values == null
                || !values.keySet().equals(new HashSet<>(declared))
                || values.values().stream().anyMatch(value -> value == null || value.length() > 8192)) {
            throw new ForgeException(PromptErrorCode.INVALID_VARIABLES);
        }
        // 不对替换结果再次扫描，变量值中的占位符只作为普通文本。
        Matcher matcher = PLACEHOLDER.matcher(content);
        var rendered = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(values.get(matcher.group(1))));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private void validateTemplate(String content, List<String> declared) {
        if (content == null || declared == null || declared.size() > 64) {
            throw new ForgeException(PromptErrorCode.INVALID_VARIABLES);
        }
        Set<String> names = new HashSet<>();
        for (String name : declared) {
            if (name == null || !VARIABLE_NAME.matcher(name).matches() || !names.add(name)) {
                throw new ForgeException(PromptErrorCode.INVALID_VARIABLES);
            }
        }
        Set<String> referenced = new HashSet<>();
        int cursor = 0;
        while (true) {
            int open = content.indexOf("{{", cursor);
            int close = content.indexOf("}}", cursor);
            if (open < 0) {
                if (close >= 0) {
                    throw new ForgeException(PromptErrorCode.INVALID_VARIABLES);
                }
                break;
            }
            if (close < open) {
                throw new ForgeException(PromptErrorCode.INVALID_VARIABLES);
            }
            String name = content.substring(open + 2, close);
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw new ForgeException(PromptErrorCode.INVALID_VARIABLES);
            }
            referenced.add(name);
            cursor = close + 2;
        }
        if (!names.equals(referenced)) {
            throw new ForgeException(PromptErrorCode.INVALID_VARIABLES);
        }
    }

    private PromptTemplate requireTemplate(UUID id, boolean lock) {
        var row = lock ? templates.lockById(id) : templates.selectById(id);
        if (row == null) {
            if (presets.findById(id) != null) {
                throw new ForgeException(PromptErrorCode.READ_ONLY);
            }
            throw new ForgeException(PromptErrorCode.NOT_FOUND);
        }
        return row;
    }

    private PromptVersion requireVersion(UUID id, int version) {
        var row = versions.findVersion(id, version);
        if (row == null) {
            throw new ForgeException(PromptErrorCode.NOT_FOUND);
        }
        return row;
    }

    private void copyDraft(PromptTemplate row, SavePromptRequest request) {
        validateTemplate(request.content(), request.variables());
        row.setName(request.name());
        row.setContent(request.content());
        row.setVariableNames(String.join(",", request.variables()));
    }

    private List<String> variables(String names) {
        return names.isEmpty() ? List.of() : Arrays.asList(names.split(","));
    }

    private PromptResponse response(PromptTemplate row) {
        return new PromptResponse(
                row.getId(),
                row.getName(),
                "TENANT",
                row.getContent(),
                variables(row.getVariableNames()),
                row.getPublishedVersion());
    }

    private PromptResponse response(PlatformPromptPreset row) {
        return new PromptResponse(
                row.getId(),
                row.getName(),
                "PLATFORM",
                row.getContent(),
                variables(row.getVariableNames()),
                row.getVersion());
    }

    private PromptVersionResponse versionResponse(PlatformPromptPreset row) {
        return new PromptVersionResponse(
                row.getId(),
                row.getVersion(),
                "PUBLISHED",
                row.getContent(),
                variables(row.getVariableNames()),
                null,
                row.getPublishedAt(),
                true);
    }

    private PromptVersionResponse versionResponse(PromptVersion row, boolean current) {
        return new PromptVersionResponse(
                row.getTemplateId(),
                row.getVersion(),
                "PUBLISHED",
                row.getContent(),
                variables(row.getVariableNames()),
                row.getPublishedBy(),
                row.getPublishedAt(),
                current);
    }
}
