CREATE TABLE ai_mcp_catalog (
 id uuid PRIMARY KEY,
 name varchar(120) NOT NULL,
 configuration text NOT NULL,
 published_version integer,
 enabled boolean NOT NULL DEFAULT false,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by uuid,
 updated_by uuid,
 deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_mcp_catalog IS '平台 MCP 审核目录与草稿';
COMMENT ON COLUMN ai_mcp_catalog.id IS '主键 UUIDv7';
COMMENT ON COLUMN ai_mcp_catalog.name IS '目录名称';
COMMENT ON COLUMN ai_mcp_catalog.configuration IS '待审核配置 JSON';
COMMENT ON COLUMN ai_mcp_catalog.published_version IS '当前审核发布版本';
COMMENT ON COLUMN ai_mcp_catalog.enabled IS '平台是否启用，停用立即阻断全部调用';
COMMENT ON COLUMN ai_mcp_catalog.created_at IS '创建时间';
COMMENT ON COLUMN ai_mcp_catalog.updated_at IS '更新时间';
COMMENT ON COLUMN ai_mcp_catalog.created_by IS '创建身份';
COMMENT ON COLUMN ai_mcp_catalog.updated_by IS '更新身份';
COMMENT ON COLUMN ai_mcp_catalog.deleted IS '是否逻辑删除';
CREATE TABLE ai_mcp_catalog_version (
 id uuid PRIMARY KEY,
 catalog_id uuid NOT NULL REFERENCES ai_mcp_catalog(id),
 version integer NOT NULL,
 configuration text NOT NULL,
 reviewed_by uuid NOT NULL,
 reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by uuid,
 updated_by uuid,
 deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_mcp_catalog_version IS '平台 MCP 不可变审核版本';
COMMENT ON COLUMN ai_mcp_catalog_version.id IS '主键 UUIDv7';
COMMENT ON COLUMN ai_mcp_catalog_version.catalog_id IS '目录 ID';
COMMENT ON COLUMN ai_mcp_catalog_version.version IS '审核版本号';
COMMENT ON COLUMN ai_mcp_catalog_version.configuration IS '已审核配置 JSON';
COMMENT ON COLUMN ai_mcp_catalog_version.reviewed_by IS '审核 Operator ID';
COMMENT ON COLUMN ai_mcp_catalog_version.reviewed_at IS '审核时间';
COMMENT ON COLUMN ai_mcp_catalog_version.created_at IS '创建时间';
COMMENT ON COLUMN ai_mcp_catalog_version.updated_at IS '更新时间';
COMMENT ON COLUMN ai_mcp_catalog_version.created_by IS '创建身份';
COMMENT ON COLUMN ai_mcp_catalog_version.updated_by IS '更新身份';
COMMENT ON COLUMN ai_mcp_catalog_version.deleted IS '是否逻辑删除';
CREATE TABLE ai_tenant_mcp (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 catalog_id uuid NOT NULL REFERENCES ai_mcp_catalog(id),
 enabled boolean NOT NULL,
 tool_names text NOT NULL,
 published_version integer,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by uuid,
 updated_by uuid,
 deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_tenant_mcp IS '租户 MCP 配置与草稿';
COMMENT ON COLUMN ai_tenant_mcp.id IS '主键 UUIDv7';
COMMENT ON COLUMN ai_tenant_mcp.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_tenant_mcp.catalog_id IS '平台目录 ID';
COMMENT ON COLUMN ai_tenant_mcp.enabled IS '草稿启用状态';
COMMENT ON COLUMN ai_tenant_mcp.tool_names IS '草稿允许工具名称，逗号分隔';
COMMENT ON COLUMN ai_tenant_mcp.published_version IS '当前生效发布版本';
COMMENT ON COLUMN ai_tenant_mcp.created_at IS '创建时间';
COMMENT ON COLUMN ai_tenant_mcp.updated_at IS '更新时间';
COMMENT ON COLUMN ai_tenant_mcp.created_by IS '创建身份';
COMMENT ON COLUMN ai_tenant_mcp.updated_by IS '更新身份';
COMMENT ON COLUMN ai_tenant_mcp.deleted IS '是否逻辑删除';
ALTER TABLE ai_tenant_mcp ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_tenant_mcp FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_tenant_mcp_tenant ON ai_tenant_mcp
 USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
 WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));
CREATE TABLE ai_tenant_mcp_version (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 configuration_id uuid NOT NULL REFERENCES ai_tenant_mcp(id),
 catalog_id uuid NOT NULL REFERENCES ai_mcp_catalog(id),
 version integer NOT NULL,
 enabled boolean NOT NULL,
 tool_names text NOT NULL,
 catalog_version integer NOT NULL,
 secret_ref varchar(128),
 published_by uuid NOT NULL,
 published_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by uuid,
 updated_by uuid,
 deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_tenant_mcp_version IS '租户 MCP 不可变发布版本';
COMMENT ON COLUMN ai_tenant_mcp_version.id IS '主键 UUIDv7';
COMMENT ON COLUMN ai_tenant_mcp_version.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_tenant_mcp_version.configuration_id IS '租户配置 ID';
COMMENT ON COLUMN ai_tenant_mcp_version.catalog_id IS '平台目录 ID';
COMMENT ON COLUMN ai_tenant_mcp_version.version IS '租户发布版本号';
COMMENT ON COLUMN ai_tenant_mcp_version.enabled IS '发布启用状态';
COMMENT ON COLUMN ai_tenant_mcp_version.tool_names IS '发布允许工具名称，逗号分隔';
COMMENT ON COLUMN ai_tenant_mcp_version.catalog_version IS '审核目录版本快照';
COMMENT ON COLUMN ai_tenant_mcp_version.secret_ref IS '由服务端派生的租户专属凭据引用';
COMMENT ON COLUMN ai_tenant_mcp_version.published_by IS '发布成员 Identity ID';
COMMENT ON COLUMN ai_tenant_mcp_version.published_at IS '发布时间';
COMMENT ON COLUMN ai_tenant_mcp_version.created_at IS '创建时间';
COMMENT ON COLUMN ai_tenant_mcp_version.updated_at IS '更新时间';
COMMENT ON COLUMN ai_tenant_mcp_version.created_by IS '创建身份';
COMMENT ON COLUMN ai_tenant_mcp_version.updated_by IS '更新身份';
COMMENT ON COLUMN ai_tenant_mcp_version.deleted IS '是否逻辑删除';
ALTER TABLE ai_tenant_mcp_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_tenant_mcp_version FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_tenant_mcp_version_tenant ON ai_tenant_mcp_version
 USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
 WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));
CREATE UNIQUE INDEX ai_mcp_catalog_version_number ON ai_mcp_catalog_version (catalog_id, version) WHERE NOT deleted;
CREATE UNIQUE INDEX ai_tenant_mcp_catalog_key ON ai_tenant_mcp (tenant_id,catalog_id) WHERE NOT deleted;
CREATE UNIQUE INDEX ai_tenant_mcp_version_number ON ai_tenant_mcp_version (tenant_id,configuration_id,version) WHERE NOT deleted;
CREATE TABLE ai_mcp_tool_grant (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 catalog_id uuid NOT NULL REFERENCES ai_mcp_catalog(id),
 tenant_version integer NOT NULL,
 catalog_version integer NOT NULL,
 runtime_name varchar(33) NOT NULL,
 tool_name varchar(128) NOT NULL,
 owner_id uuid NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by uuid,
 updated_by uuid,
 deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_mcp_tool_grant IS '已发布 MCP 工具资源，IAM Data Policy 保护工具访问范围';
COMMENT ON COLUMN ai_mcp_tool_grant.id IS '工具授权主键 UUIDv7';
COMMENT ON COLUMN ai_mcp_tool_grant.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_mcp_tool_grant.catalog_id IS '平台 MCP 目录 ID';
COMMENT ON COLUMN ai_mcp_tool_grant.tenant_version IS '所属租户配置发布版本';
COMMENT ON COLUMN ai_mcp_tool_grant.tool_name IS '审核工具原始名称';
COMMENT ON COLUMN ai_mcp_tool_grant.owner_id IS '发布者 Membership，数据权限归属';
COMMENT ON COLUMN ai_mcp_tool_grant.created_at IS '创建时间';
COMMENT ON COLUMN ai_mcp_tool_grant.updated_at IS '更新时间';
COMMENT ON COLUMN ai_mcp_tool_grant.created_by IS '创建身份';
COMMENT ON COLUMN ai_mcp_tool_grant.updated_by IS '更新身份';
COMMENT ON COLUMN ai_mcp_tool_grant.deleted IS '是否逻辑删除';
CREATE UNIQUE INDEX ai_mcp_tool_grant_version ON ai_mcp_tool_grant(tenant_id,catalog_id,tenant_version,tool_name) WHERE NOT deleted;
ALTER TABLE ai_mcp_tool_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_mcp_tool_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_mcp_tool_grant_tenant ON ai_mcp_tool_grant
 USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
 WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));
COMMENT ON COLUMN ai_mcp_tool_grant.catalog_version IS '审核目录版本快照';
COMMENT ON COLUMN ai_mcp_tool_grant.runtime_name IS '固定短别名，保留 DSH 前缀后的提供方命名预算';
CREATE UNIQUE INDEX ai_mcp_tool_grant_runtime_name ON ai_mcp_tool_grant(tenant_id,runtime_name) WHERE NOT deleted;
