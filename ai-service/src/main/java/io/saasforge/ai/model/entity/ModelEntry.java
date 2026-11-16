package io.saasforge.ai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

@TableName("ai_model_catalog")
public class ModelEntry extends AuditableEntity {
    @TableId
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    private String name;
    private String provider;
    private String modelId;
    private String baseUrl;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String secretRef;

    private String credentialKind;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String credentialCipher;

    private String capabilities;
    private String status;
    private Integer contextWindow;

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String value) {
        this.provider = value;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String value) {
        this.modelId = value;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String value) {
        this.baseUrl = value;
    }

    public String getSecretRef() {
        return secretRef;
    }

    public void setSecretRef(String value) {
        this.secretRef = value;
    }

    public String getCredentialKind() {
        return credentialKind;
    }

    public void setCredentialKind(String value) {
        this.credentialKind = value;
    }

    public String getCredentialCipher() {
        return credentialCipher;
    }

    public void setCredentialCipher(String value) {
        this.credentialCipher = value;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(String value) {
        this.capabilities = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        this.status = value;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(Integer value) {
        this.contextWindow = value;
    }

    @Override
    public String toString() {
        return "ModelEntry[redacted]";
    }
}
