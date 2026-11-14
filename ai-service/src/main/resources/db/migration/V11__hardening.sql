-- pg_trgm 由管理员预装到 extensions；受限迁移角色只创建本服务索引。
CREATE INDEX ai_conversation_title_trgm ON ai_conversation USING gist (LOWER(title) extensions.gist_trgm_ops) WHERE NOT deleted;
CREATE INDEX ai_conversation_message_content_trgm ON ai_conversation_message USING gist (LOWER(content) extensions.gist_trgm_ops) WHERE NOT deleted;

ALTER TABLE ai_usage_ledger ADD COLUMN prior_estimated_tokens bigint NOT NULL DEFAULT 0;
COMMENT ON COLUMN ai_usage_ledger.prior_estimated_tokens IS '重试前未结算尝试的保守占额，最终成功用量不得清零';

ALTER TABLE ai_conversation_turn ADD COLUMN recovery_snapshot text NOT NULL DEFAULT '[]';
COMMENT ON COLUMN ai_conversation_turn.recovery_snapshot IS '有界崩溃重放前的正文与工具结果快照，不含参数、密钥或引用';
