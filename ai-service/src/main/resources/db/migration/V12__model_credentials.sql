ALTER TABLE ai_model_catalog ADD COLUMN credential_kind varchar(16) NOT NULL DEFAULT 'REFERENCE';
ALTER TABLE ai_model_catalog ADD COLUMN credential_cipher text;
ALTER TABLE ai_model_catalog ALTER COLUMN secret_ref DROP NOT NULL;
ALTER TABLE ai_model_catalog ALTER COLUMN credential_kind DROP DEFAULT;
ALTER TABLE ai_model_catalog ADD CONSTRAINT ai_model_catalog_credential_check CHECK (
    (credential_kind = 'REFERENCE' AND secret_ref IS NOT NULL AND credential_cipher IS NULL)
    OR (credential_kind = 'API_KEY' AND secret_ref IS NULL AND credential_cipher IS NOT NULL)
);
COMMENT ON COLUMN ai_model_catalog.credential_kind IS '模型凭据来源：API_KEY 为加密密钥，REFERENCE 为部署引用';
COMMENT ON COLUMN ai_model_catalog.credential_cipher IS '绑定模型目录 ID 的 AES-256-GCM 版本化密文，仅 API_KEY 行填写';
COMMENT ON COLUMN ai_model_catalog.secret_ref IS '引用型凭据的环境变量名，API_KEY 行为空';
