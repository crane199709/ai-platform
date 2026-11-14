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
    "readOnly": true
  }]
}
```

v1 最多 32 个显式审核工具，原始名称最长 16 字符。只接受 HTTPS 地址，无 userinfo、query、fragment；不接收 stdio、shell 命令、动态地址、原始 Key。`readOnly:true` 必须由 Operator 审核，权限码必须为 `:read`。运行期不根据上游 `readOnlyHint` 或 discovery 自动新增工具。配置声明不能证明远端实现只读，运营必须核对实际工具行为与隔离合同。

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
