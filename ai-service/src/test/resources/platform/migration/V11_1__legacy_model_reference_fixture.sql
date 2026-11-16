-- 在凭据迁移前写入旧版目录行，验证 V12 回填来源且不依赖环境变量回写。
INSERT INTO ai_model_catalog (
    id, name, provider, model_id, base_url, secret_ref, capabilities, status, context_window
) VALUES (
    CAST('019a2525-0900-7000-8000-000000000091' AS uuid), 'Migrated reference', 'DEEPSEEK',
    'conversation-migrated-reference', 'https://models.example.test/v1', 'CONVERSATION_TEST_KEY',
    'CHAT', 'ENABLED', 65536
);
