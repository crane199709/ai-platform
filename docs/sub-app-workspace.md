# AI Sub-App 工作台

入口是 Shell 的 `/apps/ai/home`，相对路由在 `ai-sub-app/src/sub-app.ts`。`HomePage` 只消费 Shell 提供的 TenantClient 和 AccessControl；不创建第二套应用、路由、Store 或会话存储。刷新凭据但租户/成员不变时保留工作台；租户/成员变化或退出时重建视图，中止普通请求和正在读取的 SSE，丢弃旧异步结果。

## 共享客户端与 HTTP

普通 JSON 使用 `client.api('/ai/conversations')`，SDK 自动添加 `/api`，读取 Forge 信封。原始 `request` 只附加鉴权与刷新，不添加业务入口，所以 SSE 和未来导出必须显式使用 `/api`：

```ts
const response = await client.request(`/api/ai/conversations/${id}/messages`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ text, idempotencyKey, modelId }),
  signal: controller.signal,
})
```

SSE 真实读取 token/tool/done/error 帧并逐批渲染；done/error 后重新读取服务端持久 transcript。仅网络断流或尚未确认接纳的请求复用原幂等键；已收到 error 终态后的用户显式重试建立新回合，不自动执行消息。取消复用服务端 cancel 接口，并保留服务端已保存部分回答。模型选择在发送下一回合时提交，审计由服务端执行。

工具名称优先采用服务端可信 `displayName`/`nativeName`，兼容旧记录原生 `name`；内部 opaque runtime alias 不作为用户名称。流中按 callId 更新工具状态，历史使用已保存摘要。

## 租户管理页

- Prompt：读取、创建/编辑草稿、声明变量、服务端预览、发布、历史与回滚；平台预置只读。新会话可以选择发布版本并填写该版本声明的变量。
- Skill：仅从平台审核目录配置启停，保存草稿、发布与回滚配置；不接收脚本、文件路径或下载引用。
- MCP：仅读审核目录、只读工具白名单与租户发布配置；`/mcp/tools` 返回本成员实际可用清单，目录候选不意味着已获数据范围。缺范围时提示通过角色管理配置 `ai_mcp_tool`；不填写 URL、凭据或密钥。
- 模型：只选择平台已启用目录，空选择明确关闭本租户新回合；不在租户页维护平台端点和密钥。
- 用量：读取服务端业务月份，区分精确 token、估计占额和未知配额；没有计量或没有套餐副本均不伪装为免费零成本。租户只有查看权限。

草稿、发布、回滚按钮分别使用 update、publish、rollback 权限。所有页面均以后端授权响应为准。

## 验证边界与后续接线

Vitest 通过 mocked TenantClient 和真实组件 DOM 验证外部行为；Playwright 使用真实共享 SDK 与开发宿主、HTTP mock 覆盖登录/建会话/Prompt变量/发送/刷新恢复和管理操作。两者均不代替真实 IAM/Gateway/Runner/MCP/用量集成验收。两档视觉基线 1440×900、1024×768 在 `ai-sub-app/e2e/visual/__screenshots__/`。

09/10 的稳定契约已接入页面，但正式合入后仍须复核 MCP catalog/configuration/version/tools 的形状、可信 displayName/summary、用量未知/额度状态和服务端错误。敏感只读工具消费 09 的 tool.approval/tool.approval.resolved 事件，显示可信名称、审核描述与倒计时；owner 通过 /mcp/approvals/{id}/decision 批准或拒绝本次调用。组件与本地鉴权 SSE 测试服务器覆盖等待批准后继续；服务端强制授权、TTL、取消与跨成员隔离仍由 09/12 验收。

11 负责历史检索和 Markdown/JSON 导出。05 已保留禁用入口；11 接通真实契约时启用，导出必须使用 `client.request('/api/ai/...')`。12 负责真实 Shell/IAM/Gateway/AI/Runner 全链路与跨组件验收。
