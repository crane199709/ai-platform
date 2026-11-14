# DSH 作为可替换的 Agent Runtime

Status: accepted

AI 业务依赖 Agent Runtime 端口，DSH 只在 Runner 适配层实现该端口。固定具体 DSH 版本，通过初始化、建会话、流式、工具调用、取消、恢复与导出的契约测试再升级；具体版本和适配器由工单 03 落地。

直接 fork DSH 会把产品发布与上游内部实现绑定；只使用 SDK JSON-RPC、MCP 和 Skill 文件，将升级影响限制在一个边界。
