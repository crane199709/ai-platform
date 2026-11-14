# AI Platform

面向租户内部成员的 AI 工作台。平台通用术语沿用 SaaS Forge，本文件维护 AI 产品的规范用语。

## Language

**AI Business Service**：承载会话、Prompt、Skill、MCP、模型目录与配额的业务入口。
_Avoid_: AI 网关、模型转发器

**Agent Runtime**：执行 AI 会话、模型回合和工具调度的运行能力。
_Avoid_: AI 平台、业务服务

**Runner**：承载 Agent Runtime 的运行单元。
_Avoid_: 租户、Conversation

**Conversation**：用户在 AI 工作台看到的一段连续对话。
_Avoid_: Agent Session、聊天窗口

**Agent Session**：Agent Runtime 中承接连续对话的会话。
_Avoid_: Conversation、登录会话

**Tool Proxy**：AI Business Service 中校验授权并转发业务工具调用的入口。
_Avoid_: Runner 工具、透明转发

**MCP Catalog**：平台审核过、可供租户启用的 MCP 服务器目录。
_Avoid_: 任意 MCP 地址、租户服务器列表

**Model Catalog**：平台统一维护的模型目录，每个目录项描述一个模型及其能力和启用状态，供租户选择。
_Avoid_: 租户模型配置、当前可用模型列表

**Tenant Model Selection**：租户对允许使用哪些平台模型所作的选择，实际可用范围同时受平台模型启用状态限制。
_Avoid_: 模型目录、模型接入配置

**Prompt Template**：可版本化、可发布的提示词模板。
_Avoid_: 用户消息、系统配置字符串
