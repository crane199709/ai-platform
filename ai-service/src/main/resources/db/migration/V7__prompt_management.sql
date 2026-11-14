CREATE TABLE ai_prompt_template (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 name varchar(120) NOT NULL,
 content text NOT NULL,
 variable_names varchar(8192) NOT NULL,
 published_version integer,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by uuid,
 updated_by uuid,
 deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_prompt_template IS '租户 Prompt 模板与当前草稿';
COMMENT ON COLUMN ai_prompt_template.id IS '模板 ID';
COMMENT ON COLUMN ai_prompt_template.tenant_id IS '所属租户';
COMMENT ON COLUMN ai_prompt_template.name IS '模板名称';
COMMENT ON COLUMN ai_prompt_template.content IS '当前草稿内容';
COMMENT ON COLUMN ai_prompt_template.variable_names IS '逗号分隔的草稿变量名称';
COMMENT ON COLUMN ai_prompt_template.published_version IS '当前发布版本，未发布时为空';
COMMENT ON COLUMN ai_prompt_template.created_at IS '创建时间';
COMMENT ON COLUMN ai_prompt_template.updated_at IS '更新时间';
COMMENT ON COLUMN ai_prompt_template.created_by IS '创建人';
COMMENT ON COLUMN ai_prompt_template.updated_by IS '更新人';
COMMENT ON COLUMN ai_prompt_template.deleted IS '是否逻辑删除';
ALTER TABLE ai_prompt_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_prompt_template FORCE ROW LEVEL SECURITY;
CREATE POLICY prompt_template_tenant ON ai_prompt_template
 USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
 WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));

CREATE TABLE ai_prompt_version (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 template_id uuid NOT NULL REFERENCES ai_prompt_template(id),
 version integer NOT NULL CHECK (version > 0),
 content text NOT NULL,
 variable_names varchar(8192) NOT NULL,
 published_by uuid NOT NULL,
 published_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by uuid,
 updated_by uuid,
 deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_prompt_version IS '租户 Prompt 不可变发布版本';
COMMENT ON COLUMN ai_prompt_version.id IS '版本 ID';
COMMENT ON COLUMN ai_prompt_version.tenant_id IS '所属租户';
COMMENT ON COLUMN ai_prompt_version.template_id IS '模板 ID';
COMMENT ON COLUMN ai_prompt_version.version IS '不可变发布版本号';
COMMENT ON COLUMN ai_prompt_version.content IS '发布内容快照';
COMMENT ON COLUMN ai_prompt_version.variable_names IS '逗号分隔的版本变量名称';
COMMENT ON COLUMN ai_prompt_version.published_by IS '发布成员身份';
COMMENT ON COLUMN ai_prompt_version.published_at IS '发布时间';
COMMENT ON COLUMN ai_prompt_version.created_at IS '创建时间';
COMMENT ON COLUMN ai_prompt_version.updated_at IS '更新时间';
COMMENT ON COLUMN ai_prompt_version.created_by IS '创建人';
COMMENT ON COLUMN ai_prompt_version.updated_by IS '更新人';
COMMENT ON COLUMN ai_prompt_version.deleted IS '是否逻辑删除';
CREATE UNIQUE INDEX ai_prompt_version_number ON ai_prompt_version (tenant_id, template_id, version) WHERE NOT deleted;
ALTER TABLE ai_prompt_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_prompt_version FORCE ROW LEVEL SECURITY;
CREATE POLICY prompt_version_tenant ON ai_prompt_version
 USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
 WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));

-- 平台预置独立于租户业务表，只由迁移发布，租户 HTTP 接口不提供写入入口。
CREATE TABLE platform_prompt_preset (
 id uuid PRIMARY KEY,
 name varchar(120) NOT NULL,
 content text NOT NULL,
 variable_names varchar(8192) NOT NULL,
 version integer NOT NULL CHECK (version > 0),
 published_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by uuid,
 updated_by uuid,
 deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE platform_prompt_preset IS '只读的平台已发布 Prompt 预置';
COMMENT ON COLUMN platform_prompt_preset.id IS '预置模板 ID';
COMMENT ON COLUMN platform_prompt_preset.name IS '预置模板名称';
COMMENT ON COLUMN platform_prompt_preset.content IS '已发布内容';
COMMENT ON COLUMN platform_prompt_preset.variable_names IS '逗号分隔的变量名称';
COMMENT ON COLUMN platform_prompt_preset.version IS '固定发布版本号';
COMMENT ON COLUMN platform_prompt_preset.published_at IS '平台发布时间';
COMMENT ON COLUMN platform_prompt_preset.created_at IS '创建时间';
COMMENT ON COLUMN platform_prompt_preset.updated_at IS '更新时间';
COMMENT ON COLUMN platform_prompt_preset.created_by IS '创建人，系统预置时为空';
COMMENT ON COLUMN platform_prompt_preset.updated_by IS '更新人';
COMMENT ON COLUMN platform_prompt_preset.deleted IS '是否逻辑删除';
INSERT INTO platform_prompt_preset (id, name, content, variable_names, version, published_at)
VALUES ('019a2525-0700-7000-8000-000000000001', '平台默认助手',
 '你是一名面向租户内部成员的 AI 助手。请准确回答，无法确认的信息应说明。', '', 1, CURRENT_TIMESTAMP);
