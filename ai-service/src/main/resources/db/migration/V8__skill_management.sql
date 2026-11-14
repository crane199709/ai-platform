CREATE TABLE ai_skill_catalog (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    description varchar(1000) NOT NULL,
    content text NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('REVIEWED', 'DISABLED')),
    published_version integer NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_skill_catalog IS '平台审核 Skill 草稿目录';
COMMENT ON COLUMN ai_skill_catalog.id IS 'Skill ID，UUIDv7';
COMMENT ON COLUMN ai_skill_catalog.code IS '稳定 Skill 编码';
COMMENT ON COLUMN ai_skill_catalog.name IS '草稿名称';
COMMENT ON COLUMN ai_skill_catalog.description IS '草稿说明';
COMMENT ON COLUMN ai_skill_catalog.content IS '草稿 Markdown 正文';
COMMENT ON COLUMN ai_skill_catalog.status IS '审核可用状态';
COMMENT ON COLUMN ai_skill_catalog.published_version IS '最新发布版本，零表示尚未发布';
COMMENT ON COLUMN ai_skill_catalog.created_at IS '创建时间';
COMMENT ON COLUMN ai_skill_catalog.updated_at IS '更新时间';
COMMENT ON COLUMN ai_skill_catalog.created_by IS '创建人 ID';
COMMENT ON COLUMN ai_skill_catalog.updated_by IS '更新人 ID';
COMMENT ON COLUMN ai_skill_catalog.deleted IS '是否逻辑删除';

CREATE TABLE ai_skill_version (
    id uuid PRIMARY KEY,
    catalog_id uuid NOT NULL,
    version integer NOT NULL,
    name varchar(120) NOT NULL,
    description varchar(1000) NOT NULL,
    content text NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_skill_version IS '平台 Skill 不可变发布历史';
COMMENT ON COLUMN ai_skill_version.id IS '历史记录 ID，UUIDv7';
COMMENT ON COLUMN ai_skill_version.catalog_id IS '平台 Skill ID';
COMMENT ON COLUMN ai_skill_version.version IS '单调递增发布版本';
COMMENT ON COLUMN ai_skill_version.name IS '发布名称快照';
COMMENT ON COLUMN ai_skill_version.description IS '发布说明快照';
COMMENT ON COLUMN ai_skill_version.content IS '发布 Markdown 正文快照';
COMMENT ON COLUMN ai_skill_version.created_at IS '创建时间';
COMMENT ON COLUMN ai_skill_version.updated_at IS '更新时间';
COMMENT ON COLUMN ai_skill_version.created_by IS '创建人 ID';
COMMENT ON COLUMN ai_skill_version.updated_by IS '更新人 ID';
COMMENT ON COLUMN ai_skill_version.deleted IS '是否逻辑删除';

CREATE UNIQUE INDEX ai_skill_catalog_code_key ON ai_skill_catalog (code) WHERE NOT deleted;
CREATE UNIQUE INDEX ai_skill_version_number_key ON ai_skill_version (catalog_id, version) WHERE NOT deleted;

CREATE TABLE ai_tenant_skill (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    catalog_id uuid NOT NULL,
    draft_enabled boolean NOT NULL,
    published_version integer NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_tenant_skill IS '租户 Skill 启停草稿与当前发布版本';
COMMENT ON COLUMN ai_tenant_skill.id IS '配置 ID，UUIDv7';
COMMENT ON COLUMN ai_tenant_skill.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_tenant_skill.catalog_id IS '平台 Skill ID';
COMMENT ON COLUMN ai_tenant_skill.draft_enabled IS '草稿是否启用';
COMMENT ON COLUMN ai_tenant_skill.published_version IS '当前配置发布版本，零表示未发布';
COMMENT ON COLUMN ai_tenant_skill.created_at IS '创建时间';
COMMENT ON COLUMN ai_tenant_skill.updated_at IS '更新时间';
COMMENT ON COLUMN ai_tenant_skill.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN ai_tenant_skill.updated_by IS '更新人 Identity ID';
COMMENT ON COLUMN ai_tenant_skill.deleted IS '是否逻辑删除';
ALTER TABLE ai_tenant_skill ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_tenant_skill FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_tenant_skill_tenant ON ai_tenant_skill
 USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
 WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));
CREATE TABLE ai_tenant_skill_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    catalog_id uuid NOT NULL,
    version integer NOT NULL,
    enabled boolean NOT NULL,
    skill_version integer,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_tenant_skill_version IS '租户 Skill 配置不可变发布历史';
COMMENT ON COLUMN ai_tenant_skill_version.id IS '历史 ID，UUIDv7';
COMMENT ON COLUMN ai_tenant_skill_version.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_tenant_skill_version.catalog_id IS '平台 Skill ID';
COMMENT ON COLUMN ai_tenant_skill_version.version IS '配置发布版本';
COMMENT ON COLUMN ai_tenant_skill_version.enabled IS '此发布版本是否启用';
COMMENT ON COLUMN ai_tenant_skill_version.skill_version IS '锁定的平台审核正文版本，停用时为空';
COMMENT ON COLUMN ai_tenant_skill_version.created_at IS '创建时间';
COMMENT ON COLUMN ai_tenant_skill_version.updated_at IS '更新时间';
COMMENT ON COLUMN ai_tenant_skill_version.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN ai_tenant_skill_version.updated_by IS '更新人 Identity ID';
COMMENT ON COLUMN ai_tenant_skill_version.deleted IS '是否逻辑删除';
ALTER TABLE ai_tenant_skill_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_tenant_skill_version FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_tenant_skill_version_tenant ON ai_tenant_skill_version
 USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
 WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));
CREATE UNIQUE INDEX ai_tenant_skill_catalog_key ON ai_tenant_skill (tenant_id, catalog_id) WHERE NOT deleted;
CREATE UNIQUE INDEX ai_tenant_skill_version_key ON ai_tenant_skill_version (tenant_id, catalog_id, version) WHERE NOT deleted;
