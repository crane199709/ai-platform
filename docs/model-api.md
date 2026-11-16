# 模型目录 HTTP 契约

平台运营维护模型目录；租户管理员限制本租户可用的目录项。浏览器通过 Gateway，服务直连路径去掉 `/ai`；平台路径由 `/ai-platform` 重写为 `/platform`。响应统一为 Forge `ApiResponse` 信封。

> `apiKey` 与 `credentialKind` 是本次变更的目标契约，实现见 [平台模型目录的凭据管理](../.scratch/platform-model-management/spec.md)；该实现合入前，代码仍只接受 `secretRef`。

| Gateway HTTP | 授权 | 行为 |
|---|---|---|
| `GET /ai-platform/models` | Platform `platform:ops:manage` | 列出未删除的全部目录项，含禁用项 |
| `POST /ai-platform/models` | 同上 | 登记目录项，返回 201 |
| `GET /ai-platform/models/{id}` | 同上 | 读回目录项 |
| `PUT /ai-platform/models/{id}` | 同上 | 替换可维护字段，包括凭据与启用状态 |
| `DELETE /ai-platform/models/{id}` | 同上 | 逻辑删除，返回 200，data 为空 |
| `GET /ai/models` | Tenant `ai:conversation:use` | 当前租户启用且平台未禁用的模型 |
| `GET /ai/models/{id}` | 同上 | 验证并读取可用模型，禁用或未选项返回 409 |
| `GET /ai/models/catalog` | Tenant `ai:configuration:manage` + AI 使用权限 | 租户可配置的全部平台启用模型 |
| `GET /ai/models/selection` | 同上 | 当前租户选择，返回 `configured` 与 `modelIds` |
| `PUT /ai/models/selection` | 同上 | 以 `{"modelIds":["UUID"]}` 原子替换选择；空列表关闭全部 |

目录写入载荷：

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

`POST` 需给出 `apiKey` 与 `secretRef` 之一，`PUT` 可都不给表示保留现有凭据；两者同时出现返回 400 `request.invalid`。`apiKey` 是平台运营直接填写的供应商密钥，由 AI 服务用部署主密钥加密保存，要求非空、长度 ≤ 8192、ASCII 可见字符（33–126）。`secretRef` 仍是 K8s Secret/Vault 注入的环境变量名，格式 `[A-Z][A-Z0-9_]{0,127}`，用于既有引用型模型；它和 `apiKey` 不会同时存在。

`provider` 支持 `DEEPSEEK`、`OPENAI_COMPATIBLE`，能力为 `CHAT`、`TOOLS`、`VISION`、`REASONING`，状态为 `ENABLED`、`DISABLED`。`baseUrl` 必须为 HTTPS，禁止 userinfo、query、fragment，以免把凭据夹入 URL。任何 HTTP 响应均不包含 `secretRef`、密文或密钥；请求/实体/服务端配置的 `toString` 省略全部字段，`apiKey` 也不会进入审计或日志。

目录响应包含 `id/name/provider/modelId/baseUrl/capabilities/status/contextWindow/credentialKind`；`credentialKind` 为 `API_KEY`（AI 服务加密保存）或 `REFERENCE`（部署环境按引用注入），为空按未配置处理。同 provider + modelId 的未删除项唯一；重复返回 409 `model.duplicate`，不存在返回 404 `model.notFound`，不可用返回 409 `model.unavailable`，并发首次保存选择冲突返回 409 `model.selectionConflict`；不合规端点返回 400 `model.invalidEndpoint`，Bean Validation 失败沿用 400 `request.invalid`。

租户未保存选择时默认可使用全部平台启用模型；保存空列表后全部不可用。选择按 tenant_id、SDK 拦截器及强制 RLS 隔离。禁用或逻辑删除目录项后立即从可用列表剔除，每次取得服务端配置时重新检查。Plan 的 Feature Pack 权限裁剪由真实 IAM 的有效 Membership grants 执行：AI 权限不在 Plan 中时，即使角色曾获授权也不能调用。沿用平台对未登记 Plan 的历史租户语义，不在 AI 服务另造套餐副本。

平台目录复用现有 `platform:ops:manage`（平台服务运维）权限。SDK 目前仅支持服务级 Manifest Realm，不能在 ai-service 的租户清单中声明一条平台权限。本次使用独立 `/platform/**` Spring Security 链固定 Platform issuer/JWKS，并显式读取 IAM Operator grants；租户 Token 在服务直连时也返回 401。该权限同时允许其他平台运维，尚非模型专属细粒度授权；后续若需要拆分，须由平台提供正式权限目录契约。本次不改变既有用户授权。

Conversation 经 `model.service.ModelSelectionService.available()`、`getAvailable(UUID)` 和 `resolveForUse(UUID)` 访问模型；`resolveForUse` 返回 `ModelRuntimeConfiguration`，包含 endpoint、provider、modelId、contextWindow 以及凭据（类型 + 引用或密文），仅供受信任服务端调用，禁止作为 HTTP/Runner 上下文直接序列化。调用要求保留已认证的 Tenant 请求上下文，后台异步任务需在请求期完成授权与配置解析。密钥解析与模型转发由 `RunnerModelProxyService` 完成：引用型按允许列表读环境变量，直接密钥解密；任何失败统一 503 `runner.secretUnavailable`，不区分原因。

审计动作是 `ai.model.create/update/delete` 与 `ai.model.selection.update`，经 SDK Outbox/MQ 提交后写入真实 IAM。目录审计为 Platform Realm，选择审计为 Tenant Realm；仅记录 actor、对象 ID、动作、结果、trace，不记录载荷、URL、引用或密钥。
