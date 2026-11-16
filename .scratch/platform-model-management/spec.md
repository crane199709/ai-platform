# 平台模型目录的凭据管理

状态：设计已确认，待实现。页面在 SaaS Forge 平台控制台，功能规格与验收见其 `.scratch/platform-model-management/spec.md`（本地路径 `/Users/liuhuan/workspace/coding/saas-forge/.scratch/platform-model-management/spec.md`）。

## 问题

平台模型目录的写入接口只接受环境变量引用（`secretRef`），且 `PUT` 是全量替换、引用必填。这带来两个后果：

- 每接入一个模型都要改部署 Secret、重启 AI 服务，模型目录页面只能改元数据；
- 页面编辑任意字段或切换启用状态，都必须重新提交密钥引用，否则 400。

平台运营需要直接在页面填写供应商 API Key，让 AI 服务加密保存并在调用时解密使用。

## 范围

- 模型目录的写入契约支持两种凭据来源：直接密钥（AI 服务加密存储）与部署引用（环境变量名）。
- 新增加密组件、主密钥注入与运行时解密分支。
- 存量引用型模型保持可用，不做自动迁移。
- 不做主密钥轮换、不做供应商连通性探测、不改 MCP 凭据（MCP 仍按租户派生引用、部署注入）。

## 决策

| 决策 | 结论 | 为什么 |
|---|---|---|
| 凭据持久化 | 直接密钥用 AES-256-GCM 加密存目录表，引用型保持不变 | 每模型改一次部署 Secret 无法接受；厂商 Key 必须原样回放，摘要不可行 |
| 主密钥 | `AI_MODEL_CREDENTIAL_KEY`（Base64 32 字节），无默认值，缺失启动失败 | 与 `FORGE_BUSINESS_ZONE` 同款约定：密钥路径漏配不能悄悄降级 |
| 接口字段 | 扁平 `apiKey` / `secretRef`，二选一 | 旧调用方与现有集成测试不改；嵌套对象会让所有 PUT 调用方重写 |
| 编辑语义 | 元数据全量替换；凭据缺省=保持、出现=替换；两者同传=400 | 表单整表提交；缺省必须能表达“不动密钥”，否则每次编辑都要重填 |
| 响应 | 只增 `credentialKind`（`API_KEY` / `REFERENCE`），仍不回引用名与密文 | 页面要显示凭据来源；回引用名会泄露部署配置，回密文更不可能 |
| 旧模型 | 迁移只打 `REFERENCE` 标记 | 从环境变量回写数据库会让密钥出现第二来源，且启动依赖 |
| 主密钥轮换 | v1 不做，丢失即重录 | 轮换需要多版本主密钥与重加密任务，超出本次范围 |
| 删除 | 接口保留，页面不暴露 | 历史会话引用模型，目录项消失会让统计与追溯断链 |

考虑过并否决的方案：明文入库（备份与导出直接泄漏）、只保留引用（页面价值只剩元数据）、嵌套 `credential` 对象（改动面大于收益）、`PATCH` 部分更新（与现有客户端和测试都不兼容，收益仅是语义更纯）。

## 接口变更

网关路径不变：`/ai-platform/models` → 服务内 `/platform/models`，`platform:ops:manage`。

### 写入载荷

```json
{
  "name": "DeepSeek Chat",
  "provider": "DEEPSEEK",
  "modelId": "deepseek-chat",
  "baseUrl": "https://api.deepseek.com",
  "apiKey": "sk-...",
  "capabilities": ["CHAT", "TOOLS"],
  "status": "ENABLED",
  "contextWindow": 65536
}
```

- `POST`：`apiKey` 与 `secretRef` 恰好一个，缺失或同时出现 → 400 `request.invalid`。
- `PUT`：元数据字段仍全量校验；`apiKey` / `secretRef` 都不传表示保持现有凭据，传一个表示替换，传两个 → 400 `request.invalid`。
- `apiKey` 约束与运行时一致：非空、长度 ≤ 8192、ASCII 可见字符（33–126），否则 400 `request.invalid`；不写进任何错误文案。
- `secretRef` 约束不变：`[A-Z][A-Z0-9_]{0,127}`。

### 响应

`ModelResponse` 增加 `credentialKind`：`API_KEY` 表示密钥由 AI 服务加密保存，`REFERENCE` 表示由部署环境按引用注入。响应仍不含 `secretRef`、密文与密钥；`credentialKind` 为空只可能是数据修复态，页面按“未配置”处理。

`ModelRequest`、`ModelEntry`、`ModelRuntimeConfiguration` 的 `toString` 必须继续脱敏；新增的密文字段与 `apiKey` 不得出现在日志、审计与错误响应中。

## 存储与加密

`V10` 迁移：

```sql
ALTER TABLE ai_model_catalog ADD COLUMN credential_kind varchar(16) NOT NULL DEFAULT 'REFERENCE';
ALTER TABLE ai_model_catalog ADD COLUMN credential_cipher text;
ALTER TABLE ai_model_catalog ALTER COLUMN secret_ref DROP NOT NULL;
ALTER TABLE ai_model_catalog ALTER COLUMN credential_kind DROP DEFAULT;
ALTER TABLE ai_model_catalog ADD CONSTRAINT ai_model_catalog_credential_check CHECK (
    (credential_kind = 'REFERENCE' AND secret_ref IS NOT NULL AND credential_cipher IS NULL)
    OR (credential_kind = 'API_KEY' AND secret_ref IS NULL AND credential_cipher IS NOT NULL)
);
```

两个新字段按规范写 `COMMENT ON COLUMN`；`secret_ref` 的注释改为“引用型凭据的环境变量名”，并在注释中说明 `API_KEY` 行为空。

- 密文格式：`v1:` + Base64(nonce12 ‖ ciphertext ‖ tag)，AES-256-GCM。
- AAD 用模型目录 ID 的字符串字节，防止把一行的密文挪到另一行仍可解密。
- 主密钥从 `AI_MODEL_CREDENTIAL_KEY` 读取，启动时解码并校验 32 字节，失败即启动失败；主密钥不进入代码、镜像与样例文件的真实值。
- 加密组件放在 `io.saasforge.ai.model.service`，只暴露 `encrypt(plaintext, modelId)` 与 `decrypt(ciphertext, modelId)`。

## 运行时解析

- `ModelSelectionService.resolveForUse` 返回的 `ModelRuntimeConfiguration` 增加凭据类型与密文（仍禁止序列化到 HTTP 或 Runner 上下文）。
- `RunnerModelProxyService` 按类型分支：`REFERENCE` 沿用现有允许列表 + `Environment` 读取；`API_KEY` 调用解密组件。
- 任一路径失败（未配置、不在允许列表、密钥为空、GCM 校验失败）统一抛 `runner.secretUnavailable`（503），不区分原因，避免把“主密钥不对”变成可探测信号。
- 解密只在发起供应商请求前发生，明文不缓存、不落字段。

## 兼容与迁移

- 存量行由 `DEFAULT 'REFERENCE'` 回填，`secret_ref` 保留，现有模型（如 `MODEL_DEEPSEEK_API_KEY`）继续可用。
- 平台运营人员把某个引用型模型改成直接密钥时，`secret_ref` 置空、密文写入；此操作不可逆，页面需要明确提示。
- 没有从环境变量回写数据库的迁移路径。

## 部署与配置

- `deploy/kubernetes/secrets.example.yaml` 增加占位 `AI_MODEL_CREDENTIAL_KEY: REPLACE_MODEL_CREDENTIAL_KEY`。
- `docs/deployment.md` 的配置表增加一行，说明必配、丢失后需重录、与模型/MCP 引用的关系。
- 本地 `ai-service/.env.example`、测试 `application.yml` 与 CI 提供确定性的测试主密钥（不是真实凭据）；`PlatformIntegrationIT` / `FullStackIT` 用动态属性注入。

## 测试与验收

后端集成测试（`PlatformIntegrationIT`，必要时加 `FullStackIT`）：

- 带 `apiKey` 创建返回 201，响应与列表不含密钥、密文与 `secretRef`，`credentialKind=API_KEY`；库中该列不是明文。
- 编辑不传凭据：原 Key 保留（轮换验证：改一个模型的 Key 后下一回合用新值），元数据更新生效。
- 编辑传 `apiKey` 替换：旧 Key 失效、新 Key 生效；引用型改成直接密钥后 `credential_kind` 变化。
- `apiKey` 与 `secretRef` 同传、或 `POST` 都不传：400 `request.invalid`。
- 存量 `REFERENCE` 模型在迁移后仍能完成回合；允许列表外引用仍返回 503。
- 主密钥错误/缺失导致解密失败时返回 503，不泄露原因。
- 租户 Token 直连 401、无 `platform:ops:manage` 的 Operator 403 保持不变。
- `ai.model.create/update` 审计仍为每条一次、`detail` 为空、不含密钥。

## 不在范围

- 主密钥轮换与重加密。
- 供应商连通性测试。
- MCP 凭据改造。
- 删除接口的页面入口与执行语义变更。

## 风险与后果

- 主密钥丢失后所有 `API_KEY` 模型不可用，只能重新录入；没有恢复手段。
- 数据库备份恢复会带出密文，安全性依赖主密钥不随备份流出；备份策略与密钥保管需要一并检查。
- 迁移不可逆地放开了 `secret_ref` 的空值约束，读取路径必须同时覆盖两种凭据，任何新代码都不能假设 `secret_ref` 非空。
