CREATE TABLE ai_model_catalog (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    provider varchar(32) NOT NULL CHECK (provider IN ('DEEPSEEK', 'OPENAI_COMPATIBLE')),
    model_id varchar(200) NOT NULL,
    base_url varchar(500) NOT NULL,
    secret_ref varchar(128) NOT NULL,
    capabilities varchar(100) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ENABLED', 'DISABLED')),
    context_window integer NOT NULL CHECK (context_window > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_model_catalog IS '平台级模型目录，由平台运营统一维护';
COMMENT ON COLUMN ai_model_catalog.id IS '模型目录 ID，UUIDv7';
COMMENT ON COLUMN ai_model_catalog.name IS '模型显示名称';
COMMENT ON COLUMN ai_model_catalog.provider IS '模型提供方';
COMMENT ON COLUMN ai_model_catalog.model_id IS '提供方的模型标识';
COMMENT ON COLUMN ai_model_catalog.base_url IS '不含凭据的 HTTPS 服务地址';
COMMENT ON COLUMN ai_model_catalog.secret_ref IS '平台注入的密钥环境变量引用，不存密钥明文';
COMMENT ON COLUMN ai_model_catalog.capabilities IS '模型能力标记，逗号分隔';
COMMENT ON COLUMN ai_model_catalog.status IS '模型启用状态';
COMMENT ON COLUMN ai_model_catalog.context_window IS '模型上下文窗口 Token 数';
COMMENT ON COLUMN ai_model_catalog.created_at IS '创建时间';
COMMENT ON COLUMN ai_model_catalog.updated_at IS '更新时间';
COMMENT ON COLUMN ai_model_catalog.created_by IS '创建人 Operator ID';
COMMENT ON COLUMN ai_model_catalog.updated_by IS '更新人 Operator ID';
COMMENT ON COLUMN ai_model_catalog.deleted IS '是否逻辑删除';
CREATE UNIQUE INDEX ai_model_catalog_provider_model_key ON ai_model_catalog (provider, model_id) WHERE NOT deleted;

CREATE TABLE ai_tenant_model_selection (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    model_ids text NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_tenant_model_selection IS '租户可用模型选择，未配置时使用平台全部启用模型';
COMMENT ON COLUMN ai_tenant_model_selection.id IS '租户模型选择 ID，UUIDv7';
COMMENT ON COLUMN ai_tenant_model_selection.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_tenant_model_selection.model_ids IS '租户选择的模型目录 ID，空串表示关闭全部模型';
COMMENT ON COLUMN ai_tenant_model_selection.created_at IS '创建时间';
COMMENT ON COLUMN ai_tenant_model_selection.updated_at IS '更新时间';
COMMENT ON COLUMN ai_tenant_model_selection.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN ai_tenant_model_selection.updated_by IS '更新人 Identity ID';
COMMENT ON COLUMN ai_tenant_model_selection.deleted IS '是否逻辑删除';
CREATE UNIQUE INDEX ai_tenant_model_selection_tenant_key ON ai_tenant_model_selection (tenant_id) WHERE NOT deleted;
ALTER TABLE ai_tenant_model_selection ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_tenant_model_selection FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_tenant_model_selection_tenant ON ai_tenant_model_selection
    USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));
