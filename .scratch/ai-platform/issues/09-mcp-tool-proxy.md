# 09: MCP 目录与工具代理

**What to build:** 按 [spec](../spec.md) 的“实现决策”，实现平台审核的 MCP 目录、租户启用、凭据引用与只读工具代理。

**Blocked by:** 02, 03

**Status:** resolved

- [x] 平台级 MCP 目录：名称、URL、协议、允许工具、状态、审核信息
- [x] 租户启用/停用；凭据由 AI 服务保管，runner 只拿到代理地址
- [x] 工具代理：短期上下文（tenant、user、session、scopes），校验 RBAC/ABAC 后转发业务 API
- [x] v1 只开只读工具；写工具默认拒绝
- [x] 工具调用写审计；不记录参数中的敏感原文
- [x] 测试覆盖：目录审核、租户启用、代理授权、只读限制、跨租户拒绝、审计

## Comments


## Answer

2026-11-14 在隔离分支 `codex/ai-platform-09` 完成，产品最终源码 `d571fd2`，基线已合正式集成 `ea891eb`（04 Conversation/Capability 与 05 UI）。未写源工作区、未推送，由集成 merger 合入。

- 平台 `/platform/mcp` 复用独立 Platform Realm 与真实 `platform:ops:manage`，提供草稿、审核发布、不可变历史、回滚和立即停用。租户 `/mcp` 提供审核目录候选、独立启停草稿、发布历史和回滚，不能填写 URL、命令、Key 或 secretRef；平台未发布内容及运营元数据不向租户泄漏。
- V9 增加目录/审核版本、租户配置/版本、工具授权记录与 Conversation 的 `mcp_snapshot`；租户表有标准列、逻辑删除、RLS 和中文注释。Manifest 版本 8 新增四项 MCP 权限及真实 `ai_mcp_tool` DataResource。无 DataPolicy 默认拒绝，IAM all/self/custom 实际范围参与每次鉴权。
- `McpSelectionService.snapshotForConversation()` 在创建时保存授权工具版本；每回合 `authorizeSnapshots()` 重新检查当前 IAM grants、数据范围及当前目录/租户状态，再提供 Runner 声明和精确 `tool:<runtimeName>` scope。短别名保留完整原生名映射，不截断路由；DSH 前缀加入后 Provider 名称为49字符，真实 DSH 验证合法性与无碰撞。
- `/internal/runner/tools/call` 独立精确认证链验证真实 capability 的 tenant/user/membership/session/activeTurn/TTL/scope，恢复 Java 内存中的已验证身份执行 RBAC/ABAC。工具必须属于审核只读白名单，未审核、写工具、停用、跨租户和越权均失败关闭。参数中的 ownerId/tenantId 不是授权依据。
- 凭据只在 Java 解引用，租户引用由服务端派生并受 `AI_MCP_SECRET_REFERENCES` 部署允许列表约束；受信业务身份模式另要求 `AI_MCP_TRUSTED_BUSINESS_ORIGINS`。MCP 固定 HTTPS origin、禁止重定向，不自动重试工具。用户 JWT 绝不进入 Runner 或外部 MCP；受信 Business API 可沿用原已验证身份继续执行其自己的行级权限。
- 敏感只读工具可选 `requiresApproval`。SSE `tool.approval`/`tool.approval.resolved` 配合 `POST /mcp/approvals/{approvalId}/decision`，仅当前所有者与活跃回合可批准/拒绝，最长20秒；未批、拒绝、过期、断流、取消均不转发，批准后和实际发送前再次鉴权。05 已按稳定契约接入审批卡片。
- 工具调用与审批只审计动作、资源及决策元数据。官方本地 Skill 使用公开 `toolCallId` 关联启动/完成/失败/中断，MCP 只在实际代理审计一次；参数、结果原文、能力和凭据不入审计。公开 SSE/持久摘要带可信 displayName/summary 与 canonical name/callId。
- Runner `/messages` 新增可选严格 `tools`；Java 每回合发送当前授权清单，显式 `[]` 撤权，沿用原配置 fingerprint 重启/恢复。同一 session 的真实 DSH 测试证明第二回合撤权后 Provider 不再看到旧工具。此接缝只刷新声明，失败后的 DB-authoritative 单会话 transcript 恢复仍由工单11补齐。

验证结果（最终产品源码 `d571fd2`）：

- `AI_MAVEN_OFFLINE=true bash scripts/check.sh service`：**10/10**，架构、Java HTTP 适配、Manifest 权限/角色、V9/SDK 中文注释、格式与打包通过。日志 `/private/tmp/ai09-final-service.log`。
- `bash scripts/check.sh runner`：**22/22**，严格 TypeScript 与构建通过；真实固定版 DSH + 本地 Provider/Tool Proxy 覆盖最终工具名、MCP/Skill callId 关联、17秒审批等待、同 session 工具撤权、只读/schema/大小拒绝及原有隔离恢复。日志 `/private/tmp/ai09-final-runner2.log`。
- `AI_FORGE_RUNTIME_REPOSITORY=/Users/liuhuan/.m2/repository ./mvnw -o -s .mvn/settings.xml test -Dtest=PlatformIntegrationIT`：**31/31**，真实 IAM/Gateway/PostgreSQL/Redis/MQ 全部通过；包含目录审核、租户版本/跨租户、真实 all/self/custom/缺策略、capability/终态撤销、凭据隔离、审批批准/拒绝/超时/取消/批准后禁用、工具审计与原会话回归。日志 `/private/tmp/ai09-full-platform-final.log`（17:45:27 完成）。上一轮30/31中的 Skill 测试配置污染已用 finally 停用发布修复，本轮完整通过。
- 工单10在包含全部09产品改动的 `cb23455` 上独立执行完整组合 **37/37**（上述31项+用量6项）通过，日志 `/private/tmp/usage-ai-platform-final37.log`。未削减权限、审计或超时断言。
- `git diff --check` 通过。无付费模型调用或真实外部密钥。Java 最高接缝使用 FakeAgentRuntime，外部 MCP 网络用 McpTransport 接缝；IAM授权、RLS与审计均真实，另有真实 Node DSH HTTP 契约验证，未声称生产外部 MCP 或未知业务行级数据系统已端到端验收。

HTTP 请求/响应、稳定服务 DTO、工具与下游行级权限边界、部署环境和审批限制见 [MCP 契约](../../../docs/mcp-api.md)；Runner 消息刷新、工具关联和升级顺序见 [Runner 契约](../../../docs/runner-contract.md)。v1 能力与待批回合绑定签发 Java 实例，部署须单实例或精确会话路由；IAM 管理员须实际配置 `ai_mcp_tool` DataPolicy。管理页面由05完成，生产网络/cgroup与外部系统验收由12负责。
