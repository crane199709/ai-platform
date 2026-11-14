# 05: Sub-App 工作台

**What to build:** 按 [spec](../spec.md) 的“实现决策”，用 `sub-app-kit` 生成 AI Sub-App，实现会话列表、聊天、流式输出、模型选择与错误状态。

**Blocked by:** 04

**Status:** resolved

- [x] Sub-App 编码 `ai`，挂载到租户 Shell，路由与菜单由 Manifest 声明
- [x] 会话列表：新建、重命名、删除、搜索入口
- [x] 聊天区：消息列表、SSE 流式渲染、取消、重试、错误提示
- [x] 模型选择器：只显示当前租户可用模型；切换写审计
- [x] 工具调用展示：名称、状态、结果摘要
- [x] 空状态、加载状态、断流提示、权限不足提示
- [x] 组件测试覆盖会话列表、流式渲染、取消、错误；Playwright 覆盖主链路

## Comments


### 2026-11-14 页面简报与范围

需求方已确认：员工主任务为新建会话、发送与取消；重命名、删除、搜索和导出为低频操作。首屏显示当前模型、历史与流式回答，工具名称/状态/摘要随回合显示。沿用 Forge Shell、PageHeader 与工作台原型；管理员在 PageHeader 下通过并列视图进入 Prompt、Skill、MCP、模型和用量，列表沿用 PageToolbar/表格，编辑采用弹窗。

本工单追溯 spec 用户故事 16–21 与“Vue Sub-App 管理页”范围，补齐 07/08/09 后端先行后尚未交付的租户页面。租户模型页只选择平台目录；不维护平台密钥、地址。Prompt/Skill/MCP 提供草稿、发布、历史、回滚入口，权限分别消费 Shell 的 update/publish/rollback。MCP 和用量等待 09/10 的正式集成契约，不能以 mock 宣称联调完成。11 的检索/导出在正式后端合入后接线。

已确认测试接缝：Vitest + mocked TenantClient 的组件外部行为；Playwright 主链路。目标流：/apps/ai/home → 建会话 → 发送 → 逐步回答/取消/错误 → 刷新历史；各管理视图实测。Browser plugin not available，使用现有 Playwright。两档 1440/1024 基线按仓库规范入库并逐张查看。所有流与异步状态在租户/成员变化或退出时中止，延迟旧响应不能写回。


### 2026-11-14 敏感只读工具审批补充

按 spec 用户故事 11 与 09 的稳定契约，SSE 新增 tool.approval/tool.approval.resolved；卡片只显示可信 displayName、审核描述、20 秒倒计时与批准/拒绝。owner 通过 /ai/mcp/approvals/{approvalId}/decision 决定本次调用；过期、取消、断流后不再保留可操作的待批准状态，不展示内部 alias、参数或凭据。真实服务端授权与短 TTL 的最终组合验收由 09/12 负责。

## Answer

05 前端实现和约定接缝验收完成。复用既有 ai 路由/Manifest、Shell TenantClient 与权限上下文；提供新建/重命名/删除、可用模型选择、已发布 Prompt 变量、真实增量 SSE、工具摘要、取消、错误与幂等重试、历史恢复。切租户/成员或退出会中止请求和流，旧响应不可回填。原始 client.request 不自动添加 /api，SSE 已明确请求 /api/ai/...；普通 JSON 继续用 api('/ai/...')。

管理页补齐 spec 范围：Prompt 草稿、声明变量、预览、发布、历史与回滚；审核 Skill 启停与发布版本；审核 MCP 只读工具白名单、实际成员可用工具和配置历史；租户模型选择；用量精确值、估计占额、未知配额/套餐未同步提示。发布、回滚权限分开，平台预置只读，租户页面不接收密钥、地址或凭据引用。

验收：`bash scripts/check.sh sub-app` 通过（日志 `/private/tmp/ai-platform-05-check.log`）。Vitest 11/11，覆盖历史、分批 token、取消保留部分历史、SSE 错误、幂等重试、权限、迟到旧租户列表与流、审批批准/拒绝/过期和配额/套餐未同步；格式、隔离、style、类型和构建通过。Playwright 20/20，真实共享 SDK/开发宿主 + HTTP mock 覆盖登录、建会话、发布 Prompt 变量、模型选择、发送、刷新历史、重命名/删除及各管理操作；审批使用本地仅测试的鉴权 SSE HTTP 服务，验证等待批准后才继续回答。浏览器无未解释的框架/控制台错误（429 fixture 的预期网络错误除外）。Browser plugin not available，使用仓库现有 Chromium Playwright。

1440×900、1024×768 共 22 张基线已逐张 view_image 走查并随代码提交：home 两张由占位首页更新为工作台，其余新增 permission/busy/chat/approval/prompts/prompt-editor/skills/mcp/models/usage 两档。走查修复了繁忙时误报无模型、窄屏弹窗操作可达性和 SDK 原始 SSE 入口。补充展示证据位于 `/private/tmp/ai-platform-05-qa/`。

准确的剩余范围：11 启用当前禁用的检索与 Markdown/JSON 导出入口；09/10 正式合入后复核 MCP catalog/configuration/version/tools、审批 SSE/decision、可信 displayName/summary、quotaKnown=false 的 503 usage.planUnavailable 与配额错误；12 使用真实 Shell/IAM/Gateway/AI/Runner、MCP 与用量数据验收。这里的组件/浏览器 mock 验收不宣称上述跨组件集成已经通过。使用示例与接线清单见 [工作台交接](../../../docs/sub-app-workspace.md)。

### 2026-11-14 正式审查修复补充

Prompt/Skill/MCP三管理页共用小型操作反馈函数，领域请求与文案仍留在页面；MCP改用用户数据范围术语。Vue14/14、视觉26/26及两张变更MCP PNG逐张走查通过。 两轴原6项均关闭且新增0；准确提交、红绿日志、制品摘要与未验证边界见[当前验收记录](../acceptance.md)。
