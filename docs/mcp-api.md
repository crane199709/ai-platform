# MCP 目录、租户配置与 Tool Proxy

平台目录仅由 Platform Realm 的真实 `platform:ops:manage` 授权维护。网关外部路径为 `/ai-platform/mcp`，服务内路径为 `/platform/mcp`；租户配置外部为 `/ai/mcp`，服务内为 `/mcp`。浏览器沿用 Shell 客户端。JSON 管理接口采用 Forge ApiResponse 信封；不得把 Runner 内部端点暴露至网关。

## 平台审核目录

| 方法 | 服务路径 | 行为 |
| --- | --- | --- |
| GET / POST | `/platform/mcp` | 列出平台草稿 / 创建草稿（201） |
| GET | `/platform/mcp/{id}` | 平台草稿与当前审核版本、总停用状态 |
| PUT | `/platform/mcp/{id}/draft` | 保存草稿，现有审核版本不变 |
| POST | `/platform/mcp/{id}/publish` | 由当前 Operator 明确审核，生成不可变版本并启用 |
| GET | `/platform/mcp/{id}/versions` | 审核历史，含审核 Operator 与时间 |
| POST | `/platform/mcp/{id}/versions/{version}/rollback` | 切换当前审核版本，草稿不变 |
| PUT | `/platform/mcp/{id}/status` | `{ "enabled": false }` 立即阻断目录全部工具 |

平台列表返回下列单项的数组，创建、读取、保存草稿与切换状态返回单项（以下均为 Forge 信封的 `data`）：

```json
{ "id": "catalog UUID", "draft": { "name": "业务查询", "url": "https://business.example.test/mcp", "protocol": "STREAMABLE_HTTP", "credentialMode": "PUBLIC", "isolation": "PUBLIC_DATA", "tools": [] },
  "publishedVersion": 2, "enabled": true }
```

`draft` 是待审核配置，`publishedVersion` 未审核时为 null。发布、回滚返回历史单项，历史接口返回同形数组：`{catalogId,version,configuration,reviewedBy,reviewedAt,current}`；`configuration` 是该版本不可变的完整审核配置，审核人是服务端当前 Operator，审核时间为 ISO UTC。回滚只切换当前审核版本，不重写历史或草稿。

草稿形状：

```json
{
  "name": "业务查询",
  "url": "https://business.example.test/mcp",
  "protocol": "STREAMABLE_HTTP",
  "credentialMode": "BUSINESS_IDENTITY",
  "isolation": "BUSINESS_RBAC_ABAC",
  "tools": [{
    "name": "lookup",
    "description": "读取业务状态",
    "inputSchema": { "type": "object", "properties": { "id": { "type": "string" } } },
    "permission": "orders:order:read",
    "readOnly": true,
    "requiresApproval": false
  }]
}
```

v1 最多 32 个显式审核工具，原始名称最长 128 字符。只接受 HTTPS 地址，无 userinfo、query、fragment；不接收 stdio、shell 命令、动态地址、原始 Key。`readOnly:true` 必须由 Operator 审核，权限码必须为 `:read`。运行期不根据上游 `readOnlyHint` 或 discovery 自动新增工具。配置声明不能证明远端实现只读，运营必须核对实际工具行为与隔离合同。

凭据与隔离模式必须成对匹配：

- `PUBLIC` + `PUBLIC_DATA`：无用户 JWT、无服务凭据，仅平台审核过的公共数据。
- `TENANT_SECRET` + `TENANT_CREDENTIAL`：服务端派生当前租户的环境变量引用，由运维把租户专属、只读凭据注入 AI 服务。上游必须以该凭据隔离租户；未知系统的成员行级权限不由 AI 服务推断。
- `BUSINESS_IDENTITY` + `BUSINESS_RBAC_ABAC`：仅可连接 `ai.mcp.trusted-business-origins` 显式配置的 HTTPS origin。Java 直接沿用能力绑定的已验证身份，可信 Business MCP/原业务 API 继续执行自己的 RBAC、Data Policy 与租户隔离。JWT 只在 Java 与受信 Business API 之间传递，绝不进入 Runner 或外部 MCP。

不满足隔离合同的目录不能发布/启用。平台草稿、URL、凭据模式、审核人信息不向租户返回。

## 租户发布配置

| 方法 | 服务路径 | 权限 |
| --- | --- | --- |
| GET | `/mcp/catalog` | `ai:conversation:use` + `ai:mcp:read`，仅已审核且启用目录摘要 |
| GET | `/mcp`、`/mcp/{catalogId}` | `ai:mcp:read`，本租户草稿与当前生效状态 |
| PUT | `/mcp/{catalogId}/draft` | `ai:mcp:update`，`{ "enabled": true, "toolNames": ["lookup"] }` |
| POST | `/mcp/{catalogId}/publish` | `ai:mcp:publish`，快照当前审核版本和允许工具 |
| GET | `/mcp/{catalogId}/versions` | `ai:mcp:read`，本租户发布历史 |
| POST | `/mcp/{catalogId}/versions/{version}/rollback` | `ai:mcp:rollback`，切换生效版本，草稿不变 |

所有配置接口同时要求 `ai:conversation:use`。配置从已验证身份取得租户，HTTP 不接收租户隔离依据。停用通过保存 `enabled:false` 草稿再发布生效；未发布草稿不影响正在使用的版本。平台总停用立即阻断所有租户。

租户不能填写凭据引用。`TENANT_SECRET` 的服务端引用格式为 `AI_MCP_<去横线且大写的tenant UUID>_<去横线且大写的catalog UUID>`，由运维按该引用注入环境变量。DB 仅保留引用；管理响应、Runner、审计不输出引用或明文。轮换只需替换受控 Secret 并重载环境/进程，不修改发布历史。

## 授权边界

工具访问范围由真实 Manifest DataResource `ai_mcp_tool` 声明。数据归属是发布者的 Membership；工具名称、目录 ID 与归属成员均取自服务端已发布记录，工具 arguments 中的 `ownerId`、`tenantId` 不构成授权证据。没有 IAM Data Policy 默认拒绝。当前 SDK 角色模板只支持 Permission Code，租户管理员须在 IAM 为 `ai-user` / `ai-admin` 角色配置 `ai_mcp_tool` 的 all/self/custom 范围；模板本身不隐式获得全部工具范围。

这一 Data Policy 控制哪些已启用工具可被使用。业务记录的行级范围由受信 Business MCP/原 API 再以同一身份检查；外部 MCP 只支持已审核公共数据或租户凭据隔离范围，不能把未知上游的成员行级权限宣称为已校验。

每次调用重新校验当前 IAM 授权、能力绑定和有效期、目录/租户启用状态、工具白名单和只读属性。调用审计只记录固定动作、资源 ID、成功/失败元数据，不记录参数、结果原文、JWT、能力 Token 或 MCP 凭据。

运行别名为 `m` + SHA-256(目录ID/审核版本/租户版本/完整原生名) 的前 32 个十六进制字符，持久化唯一索引碰撞时拒绝发布。原生名称完整保留；既不截断原名，也不通过短名猜测路由。DSH 的 `mcp__ai_service__` 前缀加入后总长 49，满足较严格的 64 字符兼容预算。命名依据：[OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)（64）、[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)（128）；实际 DSH 契约同时断言最终工具名格式、长度与不同别名无碰撞。

## 租户响应字段（供管理页接线）

以下为 Forge 信封的 `data`，所有名称列表均为完整原生工具名。

`GET /mcp/catalog` 返回仅含已审核、平台启用目录的数组：

```json
[{ "id": "catalog UUID", "name": "目录名称", "version": 2,
   "tools": [{ "name": "lookup", "description": "查询状态", "inputSchema": {"type":"object"}, "permission": "ai:mcp:read", "readOnly": true, "requiresApproval": false }] }]
```

这是可配置的目录候选，不表示当前成员实际获得使用工具的数据范围。URL、凭据模式、草稿和平台审核人员不返回。

`GET /mcp` 返回当前租户已经创建配置的数组；`GET /mcp/{catalogId}` 返回同形单项，未配置或其他租户的配置返回 404：

```json
{ "catalogId": "catalog UUID", "enabled": false, "toolNames": [],
  "publishedVersion": 1, "publishedEnabled": true, "catalogVersion": 2 }
```

`enabled`、`toolNames` 是当前草稿；`publishedVersion` 是当前生效的租户发布版本，未发布时为 null。`publishedEnabled` 是当前生效版本的启用状态，`catalogVersion` 是该生效版本引用的审核目录版本，两者未发布时均为 null。需要显示生效工具名单时读取下列历史中 `current:true` 项的 `toolNames`；不能把草稿名单误当成生效名单。

`GET /mcp/{catalogId}/versions` 返回版本号倒序数组；发布和回滚返回同形单项：

```json
[{ "catalogId": "catalog UUID", "version": 1, "enabled": true,
   "toolNames": ["lookup"], "catalogVersion": 2,
   "publishedBy": "Identity UUID", "publishedAt": "2026-11-14T08:00:00Z", "current": true }]
```

没有单独的 `scopeStatus` 或权限推测字段。实际可执行清单 `GET /mcp/tools` 经真实 IAM RBAC 和 `ai_mcp_tool` Data Policy 过滤，只返回本成员可用的快照：`{catalogId,catalogVersion,tenantVersion,runtimeName,nativeName,description,inputSchema,requiresApproval}`。`runtimeName` 是内部路由名，管理页使用 `nativeName`/目录名称展示；缺数据策略不会偷偷显示为已授权。配置页按钮使用已发布权限码 `ai:mcp:read/update/publish/rollback` 控制，同时以后端 403 为准。

## 敏感只读工具的用户审批

平台工具定义新增 `requiresApproval` 布尔字段，省略为 false；它仍必须是已审核只读工具。审批不能扩大 RBAC、Data Policy、目录白名单或 capability scopes。平台当前审核版撤下工具、收紧 read 权限或打开审批要求时，已有会话也必须遵守；历史快照保留版本与内容，不能冻结安全豁免。

需要审批时，实际 MCP 转发前发送当前会话 SSE：

```text
event: tool.approval
data: {"approvalId":"UUID","conversationId":"UUID","turnId":"UUID","toolName":"internal alias","displayName":"lookup","description":"已审核工具说明","expiresAt":"ISO UTC","status":"PENDING"}
```

所有者使用 Tenant Realm 身份 `POST /mcp/approvals/{approvalId}/decision`（网关外部 `/ai/mcp/...`），body 为 `{ "approved": true }` 或 false。成功为 Forge 信封 `data:{approvalId,status:"APPROVED"|"REJECTED"}`。其他租户、其他成员不能决定该请求（404）；回合已终止、重复决定或到期返回 409/404，不执行工具。

SSE `tool.approval.resolved` 的 data 为 `{approvalId,conversationId,turnId,status}`，status 为 `APPROVED`、`REJECTED`、`EXPIRED` 或 `CANCELLED`。界面显示完整 `displayName`、审核说明和倒计时；`toolName` 仅作为内部关联。审批事件、决策与审计均不含工具 arguments、原始结果、能力 Token 或密钥。

有效期为最多 20 秒，且不晚于 capability 到期。待批请求最多 64 个，超额直接拒绝，为决策 HTTP 留出 Servlet 线程。拒绝、超时、取消、断流、回合终态与服务重启都不会转发；批准后和 MCP 握手完成后的实际 `tools/call` 发送前，均重新检查 capability、当前 IAM RBAC/ABAC 与目录状态。审批状态与能力同属内存绑定，v1 必须让同一会话、内部代理和决策请求到达同一 Java 实例；不宣称任意多副本无状态可用。

## 内部代理与运行约束

`POST /internal/runner/tools/call` 仅由 Runner 调用，携带 opaque Bearer capability、`X-AI-Tenant-Id` 与 `X-AI-Session-Id`；请求为 `{name:<runtimeName>,arguments:{...}}`，最大 64 KiB。该精确路径使用独立认证链，恢复 Java 内保存的已验证身份再执行数据权限。成功使用 Runner 固定 MCP `{content:[{type:"text",text:"..."}],isError:false}` 结果信封，由专用 MVC Controller 输出；管理 JSON 继续使用 Forge 信封。

上游固定 MCP Streamable HTTP `2025-03-26` 初始化、initialized 通知和 `tools/call`，维护并关闭上游会话；支持 JSON 或 SSE 响应。v1 只接受 text 结果，总文本最多 128000 字符，上游响应最多 1 MiB，每次 HTTP 最多 10 秒，不跟随重定向，不自动重试工具操作。上游错误与非文本结果转换为固定 `mcp.upstreamFailure`，不透传上游错误正文。该传输依据 [MCP 2025-03-26 官方规范](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)。

部署设置 `AI_MCP_SECRET_REFERENCES` 为允许解引用的专属引用列表（逗号分隔），并注入对应 `AI_MCP_<TENANT32HEX>_<CATALOG32HEX>` 值。列表为空时 `TENANT_SECRET` 调用返回 503，不尝试读取其他环境密钥。`AI_MCP_TRUSTED_BUSINESS_ORIGINS` 为允许沿用已验证身份的 HTTPS origin 列表；为空时不能发布 BUSINESS_IDENTITY 目录。引用名与值均不进入 Runner/浏览器。

聊天公开 `tool` SSE 与持久 `toolCalls` 保留 canonical `name` 和 `callId`，从可信会话快照补 `displayName`（完整原生名）和固定 `summary`，不把 opaque alias 当作界面标签。官方本地 `skill` 工具用公开 `toolCallId` 关联启动与结果；`ai.tool.start` 记录已观测启动，`ai.tool.call` 记录完成/失败，未收到结果的终态记中断。内部生成的 invocation UUID 关联两条审计，重复结果不重复记账；不审计 args/content。MCP 的工具调用只在实际 Tool Proxy 记一次，审批决策另用 `ai.tool.approval.request/decision` 记录状态元数据。
