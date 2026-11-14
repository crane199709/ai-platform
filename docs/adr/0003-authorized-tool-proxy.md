# 工具与 MCP 通过 AI 服务授权代理

Status: accepted

Runner 的工具和 MCP 调用经 AI Business Service 的 Tool Proxy。AI 服务签发包含租户、用户、会话和 scopes 的短期上下文，先做 RBAC/ABAC 校验再访问业务 API；用户 Token 不进入 Runner。v1 仅开放只读工具。

直接把业务凭据交给执行模型指令的进程会绕开产品授权边界。平台审核 MCP Catalog，租户只启用目录项，凭据由 AI 服务保管。
