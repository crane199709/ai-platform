# 01: 模型凭据双来源与加密存储

**What to build:** 按 [spec](../spec.md) 让模型目录同时支持直接密钥（加密存储）与部署引用，并让编辑不再强制重填凭据。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] `V10` 迁移：新增 `credential_kind`、`credential_cipher`，放开 `secret_ref` 非空约束，加 CHECK 与全部新字段的中文注释
- [ ] `ModelCredentialCipher`：AES-256-GCM，`v1:` + Base64(nonce12 ‖ 密文 ‖ tag)，AAD 为模型目录 ID；主密钥从 `AI_MODEL_CREDENTIAL_KEY` 读取并在启动时校验（Base64 32 字节，缺失即启动失败）
- [ ] `ModelRequest` 增加 `apiKey` 并与 `secretRef` 互斥；`POST` 必须给一个，`PUT` 可以都不给
- [ ] `ModelResponse` 增加 `credentialKind`；响应、审计、日志与 `toString` 都不出现 `apiKey`、密文和引用
- [ ] `ModelCatalogService` 写入语义：新增时加密，编辑时缺省保留、给了替换，引用型改成直接密钥时清空 `secret_ref`
- [ ] `ModelRuntimeConfiguration` 携带凭据类型与密文；`RunnerModelProxyService` 按类型解析，任何失败统一 503 `runner.secretUnavailable`
- [ ] `PlatformIntegrationIT` 覆盖 spec 的用例清单，包括迁移后存量引用型模型仍能完成回合

## Comments
