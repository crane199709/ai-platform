INSERT INTO ai_model_catalog (
    id, name, provider, model_id, base_url, secret_ref, credential_kind, credential_cipher,
    capabilities, status, context_window
) VALUES (
    CAST('019a2525-0900-7000-8000-000000000092' AS uuid), 'Invalid GCM authentication', 'DEEPSEEK',
    'conversation-invalid-credential', 'https://models.example.test/v1', NULL, 'API_KEY',
    'v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==', 'CHAT', 'ENABLED', 65536
);

-- 使用独立的测试主密钥生成，验证服务当前主密钥无法解密时的公开错误契约。
INSERT INTO ai_model_catalog (
    id, name, provider, model_id, base_url, secret_ref, credential_kind, credential_cipher,
    capabilities, status, context_window
) VALUES (
    CAST('019a2525-0900-7000-8000-000000000093' AS uuid), 'Different master key', 'DEEPSEEK',
    'conversation-wrong-master-key', 'https://models.example.test/v1', NULL, 'API_KEY',
    'v1:AAAAAAAAAAAAAAAAy1DrqFhMcdD9Hr8oEFe1JMfr9NX8skefr1ZroRsp0+tS2WzC/1CRrrscDTA=', 'CHAT', 'ENABLED', 65536
);
