CREATE TABLE ai_usage_plan (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    plan_id uuid,
    plan_code varchar(64),
    plan_version bigint NOT NULL,
    token_quota bigint,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_usage_plan IS 'AI 配额与用量持久记录';
COMMENT ON COLUMN ai_usage_plan.id IS '记录 ID，UUIDv7';
COMMENT ON COLUMN ai_usage_plan.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_usage_plan.plan_id IS '当前可信 Plan ID，历史快照未提供时为空';
COMMENT ON COLUMN ai_usage_plan.plan_code IS '当前可信 Plan 编码，供按 Plan 配置留存策略，历史缺省为空';
COMMENT ON COLUMN ai_usage_plan.plan_version IS '已应用 Plan 版本';
COMMENT ON COLUMN ai_usage_plan.token_quota IS '每租户每月 Token 上限，空为不限';
COMMENT ON COLUMN ai_usage_plan.created_at IS '创建时间';
COMMENT ON COLUMN ai_usage_plan.updated_at IS '更新时间';
COMMENT ON COLUMN ai_usage_plan.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN ai_usage_plan.updated_by IS '更新人 Identity ID';
COMMENT ON COLUMN ai_usage_plan.deleted IS '是否逻辑删除';
ALTER TABLE ai_usage_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_usage_plan FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_usage_plan_tenant ON ai_usage_plan USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid)) WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));

CREATE TABLE ai_usage_month (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    billing_month date NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_usage_month IS 'AI 配额与用量持久记录';
COMMENT ON COLUMN ai_usage_month.id IS '记录 ID，UUIDv7';
COMMENT ON COLUMN ai_usage_month.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_usage_month.billing_month IS '业务月份第一日，用作并发准入锁';
COMMENT ON COLUMN ai_usage_month.created_at IS '创建时间';
COMMENT ON COLUMN ai_usage_month.updated_at IS '更新时间';
COMMENT ON COLUMN ai_usage_month.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN ai_usage_month.updated_by IS '更新人 Identity ID';
COMMENT ON COLUMN ai_usage_month.deleted IS '是否逻辑删除';
ALTER TABLE ai_usage_month ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_usage_month FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_usage_month_tenant ON ai_usage_month USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid)) WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));

CREATE TABLE ai_usage_ledger (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    model_id uuid NOT NULL,
    usage_date date NOT NULL,
    billing_month date NOT NULL,
    status varchar(16) NOT NULL,
    context_window integer NOT NULL,
    model_calls bigint NOT NULL DEFAULT 0,
    input_tokens bigint,
    output_tokens bigint,
    cached_input_tokens bigint,
    estimated_tokens bigint NOT NULL DEFAULT 0,
    tool_calls bigint NOT NULL DEFAULT 0,
    tool_call_ids text NOT NULL DEFAULT '[]',
    unknown_usage boolean NOT NULL DEFAULT false,
    outcome varchar(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_usage_ledger IS 'AI 配额与用量持久记录';
COMMENT ON COLUMN ai_usage_ledger.id IS '回合 ID，与会话回合一一对应';
COMMENT ON COLUMN ai_usage_ledger.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_usage_ledger.model_id IS '模型目录 ID';
COMMENT ON COLUMN ai_usage_ledger.usage_date IS '准入时业务日期';
COMMENT ON COLUMN ai_usage_ledger.billing_month IS '准入时业务月份第一日';
COMMENT ON COLUMN ai_usage_ledger.status IS 'RUNNING 或 FINISHED';
COMMENT ON COLUMN ai_usage_ledger.context_window IS '每次模型执行未知用量的初始估计窗口';
COMMENT ON COLUMN ai_usage_ledger.model_calls IS '已尝试的模型请求次数';
COMMENT ON COLUMN ai_usage_ledger.input_tokens IS '提供方总输入量，包含缓存';
COMMENT ON COLUMN ai_usage_ledger.output_tokens IS '提供方输出量';
COMMENT ON COLUMN ai_usage_ledger.cached_input_tokens IS '输入中的缓存子集';
COMMENT ON COLUMN ai_usage_ledger.estimated_tokens IS '未知用量的独立估计占额';
COMMENT ON COLUMN ai_usage_ledger.tool_call_ids IS '已经计数的运行尝试作用域工具标识 JSON，不包含参数或结果';
COMMENT ON COLUMN ai_usage_ledger.tool_calls IS '工具调用次数';
COMMENT ON COLUMN ai_usage_ledger.unknown_usage IS '已执行但未收到完整提供方用量';
COMMENT ON COLUMN ai_usage_ledger.outcome IS '完成状态或恢复状态';
COMMENT ON COLUMN ai_usage_ledger.created_at IS '创建时间';
COMMENT ON COLUMN ai_usage_ledger.updated_at IS '更新时间';
COMMENT ON COLUMN ai_usage_ledger.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN ai_usage_ledger.updated_by IS '更新人 Identity ID';
COMMENT ON COLUMN ai_usage_ledger.deleted IS '是否逻辑删除';
ALTER TABLE ai_usage_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_usage_ledger FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_usage_ledger_tenant ON ai_usage_ledger USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid)) WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));

CREATE TABLE ai_usage_daily (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    usage_date date NOT NULL,
    snapshot_version bigint NOT NULL,
    input_tokens bigint NOT NULL,
    output_tokens bigint NOT NULL,
    cached_input_tokens bigint NOT NULL,
    estimated_tokens bigint NOT NULL,
    turn_count bigint NOT NULL,
    tool_calls bigint NOT NULL,
    unknown_usage_turns bigint NOT NULL,
    reported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_usage_daily IS 'AI 配额与用量持久记录';
COMMENT ON COLUMN ai_usage_daily.id IS '记录 ID，UUIDv7';
COMMENT ON COLUMN ai_usage_daily.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_usage_daily.usage_date IS '业务日期';
COMMENT ON COLUMN ai_usage_daily.snapshot_version IS '持久单调快照版本';
COMMENT ON COLUMN ai_usage_daily.input_tokens IS '已知输入量，包含缓存';
COMMENT ON COLUMN ai_usage_daily.output_tokens IS '已知输出量';
COMMENT ON COLUMN ai_usage_daily.cached_input_tokens IS '输入中的缓存子集';
COMMENT ON COLUMN ai_usage_daily.estimated_tokens IS '未知用量的估计占额';
COMMENT ON COLUMN ai_usage_daily.turn_count IS '已准入回合数，包含执行中的回合';
COMMENT ON COLUMN ai_usage_daily.tool_calls IS '工具调用次数';
COMMENT ON COLUMN ai_usage_daily.unknown_usage_turns IS '用量不完整回合数';
COMMENT ON COLUMN ai_usage_daily.reported_at IS '快照形成时间';
COMMENT ON COLUMN ai_usage_daily.created_at IS '创建时间';
COMMENT ON COLUMN ai_usage_daily.updated_at IS '更新时间';
COMMENT ON COLUMN ai_usage_daily.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN ai_usage_daily.updated_by IS '更新人 Identity ID';
COMMENT ON COLUMN ai_usage_daily.deleted IS '是否逻辑删除';
ALTER TABLE ai_usage_daily ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_usage_daily FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_usage_daily_tenant ON ai_usage_daily USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid)) WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));

CREATE UNIQUE INDEX ai_usage_plan_tenant ON ai_usage_plan (tenant_id) WHERE NOT deleted;
CREATE UNIQUE INDEX ai_usage_month_tenant ON ai_usage_month (tenant_id, billing_month) WHERE NOT deleted;
CREATE UNIQUE INDEX ai_usage_daily_tenant ON ai_usage_daily (tenant_id, usage_date) WHERE NOT deleted;
CREATE INDEX ai_usage_ledger_month ON ai_usage_ledger (tenant_id, billing_month, status) WHERE NOT deleted;
