-- 只用于工单 02 接入验收；不打入生产迁移，不提前实现 Conversation 或模型目录。
CREATE TABLE contract_record (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE contract_record IS '隔离验收专用租户记录';
COMMENT ON COLUMN contract_record.id IS '记录 ID';
COMMENT ON COLUMN contract_record.tenant_id IS '所属租户';
COMMENT ON COLUMN contract_record.created_at IS '创建时间';
COMMENT ON COLUMN contract_record.updated_at IS '更新时间';
COMMENT ON COLUMN contract_record.created_by IS '创建人';
COMMENT ON COLUMN contract_record.updated_by IS '更新人';
COMMENT ON COLUMN contract_record.deleted IS '是否逻辑删除';
ALTER TABLE contract_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE contract_record FORCE ROW LEVEL SECURITY;
CREATE POLICY contract_tenant ON contract_record
    USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));

-- 固定 ID 的未审核草稿用于部署凭据绑定；测试必须经真实 Operator HTTP 审核发布。
INSERT INTO ai_mcp_catalog(id,name,configuration,published_version,enabled) VALUES ('019a2525-0900-7000-8000-000000000081','凭据边界验收目录','{"name": "凭据边界验收目录", "url": "https://mcp.example.test/tenant", "protocol": "STREAMABLE_HTTP", "credentialMode": "TENANT_SECRET", "isolation": "TENANT_CREDENTIAL", "tools": [{"name": "lookup", "description": "只读查询", "inputSchema": {"type": "object"}, "permission": "ai:mcp:read", "readOnly": true}]}',NULL,false);

-- 固定 ID 的未审核草稿用于部署凭据绑定；测试必须经真实 Operator HTTP 审核发布。
INSERT INTO ai_mcp_catalog(id,name,configuration,published_version,enabled) VALUES ('019a2525-0900-7000-8000-000000000082','凭据边界验收目录','{"name": "凭据边界验收目录", "url": "https://business.example.test/mcp", "protocol": "STREAMABLE_HTTP", "credentialMode": "BUSINESS_IDENTITY", "isolation": "BUSINESS_RBAC_ABAC", "tools": [{"name": "lookup", "description": "只读查询", "inputSchema": {"type": "object"}, "permission": "ai:mcp:read", "readOnly": true}]}',NULL,false);
