CREATE TABLE ai_conversation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    title varchar(200) NOT NULL,
    model_id uuid NOT NULL,
    active_turn_id uuid,
    prompt_template_id uuid,
    prompt_version integer,
    instructions text NOT NULL,
    skill_snapshot text NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_conversation IS 'AI 会话权威历史及回合状态';
COMMENT ON COLUMN ai_conversation.id IS '记录 ID，UUIDv7';
COMMENT ON COLUMN ai_conversation.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_conversation.owner_id IS '会话所有者 Identity ID';
COMMENT ON COLUMN ai_conversation.title IS '会话标题';
COMMENT ON COLUMN ai_conversation.model_id IS '当前模型目录 ID';
COMMENT ON COLUMN ai_conversation.active_turn_id IS '正在执行的回合 ID';
COMMENT ON COLUMN ai_conversation.prompt_template_id IS '创建时的已发布模板 ID';
COMMENT ON COLUMN ai_conversation.prompt_version IS '创建时的发布版本';
COMMENT ON COLUMN ai_conversation.instructions IS '创建时渲染的系统提示词快照，与用户消息分离';
COMMENT ON COLUMN ai_conversation.skill_snapshot IS '创建时已发布技能快照';
COMMENT ON COLUMN ai_conversation.created_at IS '创建时间';
COMMENT ON COLUMN ai_conversation.updated_at IS '更新时间';
COMMENT ON COLUMN ai_conversation.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN ai_conversation.updated_by IS '更新人 Identity ID';
COMMENT ON COLUMN ai_conversation.deleted IS '是否逻辑删除';
ALTER TABLE ai_conversation ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_conversation FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_conversation_tenant ON ai_conversation USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid)) WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));

CREATE TABLE ai_conversation_turn (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    status varchar(20) NOT NULL,
    model_id uuid NOT NULL,
    error_code varchar(100),
    input_tokens bigint,
    output_tokens bigint,
    cached_input_tokens bigint,
    usage_reported boolean NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_conversation_turn IS 'AI 会话权威历史及回合状态';
COMMENT ON COLUMN ai_conversation_turn.id IS '记录 ID，UUIDv7';
COMMENT ON COLUMN ai_conversation_turn.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_conversation_turn.conversation_id IS '所属会话 ID';
COMMENT ON COLUMN ai_conversation_turn.idempotency_key IS '用户消息幂等键';
COMMENT ON COLUMN ai_conversation_turn.status IS '回合状态 RUNNING COMPLETED CANCELLED ERROR';
COMMENT ON COLUMN ai_conversation_turn.model_id IS '本回合模型目录 ID';
COMMENT ON COLUMN ai_conversation_turn.error_code IS '终止错误码';
COMMENT ON COLUMN ai_conversation_turn.input_tokens IS '总输入用量，包含缓存输入';
COMMENT ON COLUMN ai_conversation_turn.output_tokens IS '输出 Token 用量';
COMMENT ON COLUMN ai_conversation_turn.cached_input_tokens IS '总输入中的缓存读取 Token 数';
COMMENT ON COLUMN ai_conversation_turn.usage_reported IS '提供方是否报告用量，false 不等于免费';
COMMENT ON COLUMN ai_conversation_turn.created_at IS '创建时间';
COMMENT ON COLUMN ai_conversation_turn.updated_at IS '更新时间';
COMMENT ON COLUMN ai_conversation_turn.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN ai_conversation_turn.updated_by IS '更新人 Identity ID';
COMMENT ON COLUMN ai_conversation_turn.deleted IS '是否逻辑删除';
ALTER TABLE ai_conversation_turn ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_conversation_turn FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_conversation_turn_tenant ON ai_conversation_turn USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid)) WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));

CREATE TABLE ai_conversation_message (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    turn_id uuid NOT NULL,
    role varchar(16) NOT NULL,
    content text NOT NULL,
    tool_calls text NOT NULL,
    tool_name varchar(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ai_conversation_message IS 'AI 会话权威历史及回合状态';
COMMENT ON COLUMN ai_conversation_message.id IS '记录 ID，UUIDv7';
COMMENT ON COLUMN ai_conversation_message.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ai_conversation_message.conversation_id IS '所属会话 ID';
COMMENT ON COLUMN ai_conversation_message.turn_id IS '所属回合 ID';
COMMENT ON COLUMN ai_conversation_message.role IS '消息角色 user assistant tool';
COMMENT ON COLUMN ai_conversation_message.content IS '原始用户文本或助手输出内容';
COMMENT ON COLUMN ai_conversation_message.tool_name IS '工具角色消息对应的工具名称';
COMMENT ON COLUMN ai_conversation_message.tool_calls IS '工具调用摘要 JSON，不保存凭据';
COMMENT ON COLUMN ai_conversation_message.created_at IS '创建时间';
COMMENT ON COLUMN ai_conversation_message.updated_at IS '更新时间';
COMMENT ON COLUMN ai_conversation_message.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN ai_conversation_message.updated_by IS '更新人 Identity ID';
COMMENT ON COLUMN ai_conversation_message.deleted IS '是否逻辑删除';
ALTER TABLE ai_conversation_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_conversation_message FORCE ROW LEVEL SECURITY;
CREATE POLICY ai_conversation_message_tenant ON ai_conversation_message USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid)) WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));

CREATE UNIQUE INDEX ai_conversation_turn_idempotency ON ai_conversation_turn (tenant_id, conversation_id, idempotency_key) WHERE NOT deleted;
CREATE INDEX ai_conversation_owner ON ai_conversation (tenant_id, owner_id, updated_at) WHERE NOT deleted;
CREATE INDEX ai_conversation_message_history ON ai_conversation_message (tenant_id, conversation_id, created_at, id) WHERE NOT deleted;
