# Runner 内网契约 v1

工单 03；协议版本 1。Runner 是 AI Business Service 的内网依赖。所有 `/v1/**` 带 `Authorization: Bearer <AI_RUNNER_SERVICE_TOKEN>`，`/health` 是公开存活探针。模型与工具另用短期 `contextToken`，它是 AI 服务签发的租户、用户、会话和 scope 能力，不能传入用户 Token、原始模型 Key 或 MCP 凭据。AI 服务负责验证上下文与授权、解引用模型密钥、计量和审计。

## 会话与回合

| 方法   | 路径                                                   | 请求 / 响应                                                                                                                                                                                                                                                       |
| ------ | ------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| POST   | `/v1/tenants/{tenantId}/sessions`                      | `{ "sessionId": "conversation UUID", "model": "catalog model id", "transcript": [], "tools": [], "instructions": "可选已解析Prompt", "skills": [], "instructions": "可选已发布Prompt正文" }` → 201 `{ "sessionId", "model", "recovery": "none\|context-replay" }` |
| POST   | `/v1/tenants/{tenantId}/sessions/{sessionId}/messages` | `{ "text": "用户输入", "contextToken": "短期能力", "model": "可选模型ID" }` → SSE                                                                                                                                                                                 |
| POST   | `/v1/tenants/{tenantId}/sessions/{sessionId}/cancel`   | 无 body → 200 `{ "cancelled": true/false }`                                                                                                                                                                                                                       |
| GET    | `/v1/tenants/{tenantId}/sessions/{sessionId}/export`   | 200 `{ "sessionId", "model", "transcript": [...] }`                                                                                                                                                                                                               |
| DELETE | `/v1/tenants/{tenantId}`                               | 204，终止进程并清理 home/workspace；不存在时同样返回 204                                                                                                                                                                                                          |
| GET    | `/v1/runtime`                                          | DSH 版本、profile、限额、租户数量及 `{tenantId,processId?,state}` 诊断；不含内容和凭据                                                                                                                                                                            |

`tenantId` / `sessionId` 为 `[A-Za-z0-9_-]{1,128}`。`model` 为 `[A-Za-z0-9][A-Za-z0-9_./:-]{0,127}`，允许平台目录动态编码；AI 服务能力代理校验模型是否属于本回合授权范围，Runner 不维护第二份业务目录。调用方不能提供 endpoint、apiKey、shell、workspace 或 profile 补丁。JSON 请求最大 1 MiB，未识别字段拒绝；text 和每条 transcript content 最大 128000 字符；恢复快照最多 1000 条消息。AI 服务须在会话长度限制中考虑此边界，不能假设任意长的 90 天历史都能一次恢复。

`instructions` 是 AI 服务渲染好的已发布 Prompt 快照正文，最多 128000 字符。Runner 通过官方 `system-prompt.config.personaPrefix` 将它放进真实模型系统上下文；Prompt 不混入逻辑 transcript，Java 另外保存发布版本和渲染快照。不同会话 Prompt 不同时会重建该租户进程，其他会话仍可从 transcript 恢复。

transcript 是 Java DB 保存的产品历史快照：`[{ "role": "user|assistant|tool", "content": "文本或工具摘要", "name": "可选工具名" }]`。Runner 导出同一结构；助手文本来自已提交的 DSH 事件，工具结果以 tool 角色保存。恢复时将角色、文本和工具摘要编码为有明确边界的历史上下文，通过 SDK `session/prompt` 与新输入一起发送，不重放历史工具动作。DSH SDK 没有历史导入方法，因此 `context-replay` 保留可见历史事实，不保证逐条原始角色消息、推理缓存或模型请求字节相同。取消、崩溃和模型切换后采用相同语义。中断时已发送但尚未被 DSH 提交的 delta 不会作为完整助手消息写入 Runner 导出；AI 服务可保留用户可见的中断状态。

Runner 禁用 JSONL 持久化及其投影缓存，只保存运行期内存状态，因此恢复不依赖上游内部日志格式或原 home。AI 服务必须持久化每个已接纳的用户消息以及回合结果，再将完整快照用于新 Runner 的建会话请求；导出不能替代 DB 持久化。空闲回收或 Runner 重启后会话返回 404，AI 服务重新建会话并提供 DB transcript。

## SSE

每个帧采用 `event: <type>\ndata: <JSON>\n\n`；200 响应为 `text/event-stream`，关闭缓存和代理缓冲。

| 事件        | data                                                                                                                                             |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| started     | `{ "sessionId": "..." }`                                                                                                                         |
| delta       | `{ "text": "分片" }`                                                                                                                             |
| tool.call   | `{ "name": "mcp__ai_service__lookup", "callId": "...", "arguments": "JSON字符串" }`                                                              |
| tool.result | `{ "content": "工具结果文本", "isError": false }`                                                                                                |
| completed   | `{ "sessionId", "model", "transcript": [...], "usage": { "inputTokens": 7, "outputTokens": 2, "cachedInputTokens": 0 }, "usageReported": true }` |
| cancelled   | `{ "sessionId": "..." }`                                                                                                                         |
| error       | `{ "code": "runner.*", "retryable": true/false }`                                                                                                |

`completed/cancelled/error` 为互斥终态。usage 为本回合各 DSH 模型步骤之和；inputTokens/outputTokens 为数字；inputTokens 是含缓存读取/写入的总输入数（Runner 将上游 DSH 的分离计数汇总，避免配额漏计），cachedInputTokens 是其中缓存读取的子集。cachedInputTokens 仅在提供方报告缓存读取时出现。上游未报告用量时返回零值并置 `usageReported:false`，不能把它当作免费调用；计量侧应按缺失用量处理。没有模型原始密钥进入该链路。

SDK 没有实时 token 通知；delta 来自透明模型代理桥观测的原始 OpenAI-compatible SSE `choices[].delta.content`，到达即发，不在完成后拆字。相同原始流同时交给真实 DSH provider adapter。最终 transcript、工具调用与用量取 DSH 已提交事件。连接断开会取消当前回合。

## AI 服务代理（工单 04 / 09）

部署时固定 `AI_SERVICE_PROXY_URL`，例如 `http://ai-service:8080/internal/runner`。Runner 不接受调用请求中的 URL，也不跟随代理重定向。所有代理请求均携带 `Authorization: Bearer <contextToken>`、`X-AI-Tenant-Id`、`X-AI-Session-Id`。AI 服务须验证三者与能力声明一致，且在工具转发前重新做 RBAC/ABAC 和只读范围校验。

- `POST {AI_SERVICE_PROXY_URL}/chat/completions`：OpenAI Chat Completions SSE。body 由真实 DSH adapter 生成，包含选定 model 和 messages；AI 服务解引用密钥并路由 DeepSeek/OpenAI-compatible 上游。适配器默认输出上限 4096 token，目录路由的 contextWindow 暂用 128000；服务端应按实际模型能力拒绝过大请求。
- `POST {AI_SERVICE_PROXY_URL}/tools/call`：body `{ "name": "lookup", "arguments": {"query":"status"} }`；成功响应是标准 MCP 工具结果 `{ "content": [{ "type": "text", "text": "status: healthy" }], "isError": false }`。工具失败交回真实 DSH agent loop；错误状态不透传上游正文和凭据。

会话 tools 是 AI 服务根据审核目录和租户启用状态生成的只读声明，最多 32 项，示例：

```json
[
  {
    "name": "lookup",
    "description": "Read service status",
    "inputSchema": { "type": "object", "properties": { "query": { "type": "string" } } },
    "readOnly": true
  }
]
```

name 为 `[A-Za-z0-9_-]{1,64}`，同一会话不能重复。Runner 在每租户 loopback 端口提供仅含该清单的最小 MCP Streamable HTTP 服务，使用官方 `dsh-mcp-client` 挂载为 `mcp__ai_service__<name>`；实际工具执行始终送回 AI 服务。`readOnly:true` 是声明约束，实际授权责任仍由 Tool Proxy 承担。HTTP API 不接收外部 MCP URL 或 stdio command。

## DSH 固定版本和生命周期

官方包 [`@deepseek-ai/dsh@0.2.2-rc.1`](https://www.npmjs.com/package/@deepseek-ai/dsh/v/0.2.2-rc.1)，上游 [`deepseek-ai/deepseek-harness`](https://github.com/deepseek-ai/deepseek-harness)。npm integrity：`sha512-ijR9UEVYkG15Q/Q79JzvzLBzQOQfc63VLr9n6flpwHPhr5IKn2UxthnSoto/P9l7qGd7pPvI1Ra6TW7LH3oKpg==`。整个传递依赖由根 pnpm-lock.yaml 锁定。仅使用公开 SDK JSON-RPC、官方 profile patch 与 MCP，不修改 DSH 内部文件或日志格式。

实际启动链为固定 `/bin/sh` 包装，先执行 `ulimit -n 128` 与 `ulimit -t <CPU秒数>`，再 exec 当前 Node 24：

```text
node --max-old-space-size=256 --max-semi-space-size=8 <官方包>/lib/bin.js --profile sdk --patch <tenant-home>/runner.patch.json
```

每租户独立进程、随机私有目录、DSH_HOME、HOME 和临时 workspace；目录 mode 0700、profile 文件 mode 0600。环境使用允许列表，不继承模型凭据、用户 home、NODE_OPTIONS 或代理变量。进程仅连接自己的 loopback 模型/MCP 桥，桥只访问配置好的 AI 服务。

官方 profile 的 shell/FS、Web、plugin manager、子代理、调度、Office 和其他原生工具通过受支持 patch 禁用；真实契约断言 Provider 收到的工具清单为空或严格等于声明的只读 MCP 工具。DSH 客户端审批请求一律拒绝。每租户同模型/同工具/同 Prompt 配置复用进程；模型、工具或 Prompt 配置变化会重启该租户进程，下次请求从各会话 transcript 恢复。

同租户最多一个活跃/正在准入回合；第二回合返回 409 `runner.tenantBusy`。取消通过终止该租户进程实现，因为 SDK 没有 prompt-cancel；先 SIGTERM，1 秒后仍未退出则 SIGKILL，处理整个进程组。该租户其他会话的运行期句柄失效，下次调用恢复上下文；其他租户不受影响。池满返回 429 `runner.poolFull`、`Retry-After: 5`，没有 Runner 内部持久排队；AI 服务负责有限重试。默认空闲 15 分钟回收进程、home、workspace 和内存缓存。

## 配置与资源边界

示例见 [runner/.env.example](../runner/.env.example)。必填 host、port、服务令牌、固定代理 URL 和可写 root；可调 maxTenants（默认 8）、idleTimeoutMs（900000）、Node old-space MiB（256）、CPU 秒数（120）、回合 wall timeout ms（120000）、RPC timeout ms（15000）。CPU 是进程累计 CPU 时间限额，回合 timeout 是单回合墙上时限。

本地 Node 24.14.1 实测：CPU soft limit 120 秒、open-files 128，`--max-old-space-size=256 --max-semi-space-size=8` 的 V8 heap_size_limit 为 293601280 字节（280 MiB）。Node 堆限制不等于进程 RSS 硬上限；FD/CPU rlimit 和每回合超时已经生效，生产容器的 RSS/CPU 总额及网络隔离由工单 12 部署配置提供。本工单未执行 Linux/Kubernetes 的 cgroup 验证，也没有把私有 home 当作不同 OS 用户或容器沙箱。

pnpm-workspace.yaml 显式拒绝未使用原生能力的依赖构建脚本；受限 SDK profile 的真实测试不需要 terminal、Koffi、protobuf 或 Google provider 的安装脚本。升级时须保留这一约束并重跑全部契约。

## 审核 Skill 快照（工单 08）

建会话的可选 `skills` 只接受 AI 服务已解析的已发布审核快照，默认空清单。每项字段为 `catalogId`（UUID）、`code`（1–64 字符小写字母/数字 kebab-case）、`name`（120 字符）、`description`（1000 字符）、`version`（正整数）、`content`（非空 Markdown 正文，最多 64000 字符）。清单最多 32 项且序列化后最多 512 KiB，编码与目录 ID 均不能重复；拒绝额外字段、文件路径、脚本附件、空字符或任意下载引用。整个建会话请求仍受 1 MiB 上限约束，AI 服务须把 Skill 与 Prompt 计入恢复预算。

Runner 在随机私有 tenant home 的 `skills/<code>/SKILL.md` 中写入受控 frontmatter 与快照正文（目录 0700、文件 0600）。非空清单只额外启用官方 `skill-filesystem` 与 `tool-skill`：`includeDefaultRoots:false`、`customSkillDirs:[受控skills目录]`、`watch:false`；Shell、文件操作、Web 与其他原生执行工具继续禁用。官方工具名为 `skill`，通过公开 Skill 文件协议读取正文，不使用虚构的 DSH 环境变量或内部接口。

每个会话保存独立 Skill 快照。清单参与进程配置比较，切换会话 Skill 版本会重建当前租户进程并从该会话 transcript 恢复；停用后传空清单的新会话不加载旧目录，已有会话保持已捕获版本。空闲回收、取消或租户删除清理整个受控目录。Java Conversation 必须保存创建时的清单，Runner 重建时仍提供原清单。

真实 DSH HTTP 契约验证 Provider 只有允许的 `skill` 工具、调用后收到审核正文、停用后的新会话没有该 Skill、旧会话版本仍保留，以及跨租户不可见；同时验证路径/脚本附件/重复编码与无效版本拒绝。

## 错误与排障

JSON 错误体是 `{ "code": "runner.*", "retryable": false/true }`。SSE 开始后使用 error 事件，不改变 HTTP 状态；错误不携带上游正文、消息内容、stderr 或能力 Token。DSH 原始 stderr 被 drain，不进入产品日志。排障先查询 `/v1/runtime` 的固定版本/profile、限额、进程 PID 与状态，再结合 AI 服务 trace 和下面的分类判断恢复动作。

| HTTP | code                                                                                               | 处理                                                  |
| ---- | -------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| 400  | runner.invalidRequest                                                                              | 修正请求字段/模型/只读工具声明                        |
| 401  | runner.unauthorized                                                                                | 修正 AI 服务到 Runner 的服务身份                      |
| 404  | runner.notFound / runner.sessionNotFound                                                           | 核对路径；会话已回收时从 DB 重建                      |
| 409  | runner.sessionExists / runner.tenantBusy                                                           | 复用已建会话或等待同租户回合结束                      |
| 413  | runner.requestTooLarge                                                                             | 由产品明确处理会话长度限制                            |
| 429  | runner.poolFull                                                                                    | 遵循 Retry-After，有限重试                            |
| 502  | runner.runtimeUnavailable / runner.runtimeExited / runner.runtimeFailure / runner.protocolMismatch | 查看版本与部署，保留 DB 历史后重建                    |
| 502  | runner.modelFailure                                                                                | 模型代理/提供方失败；不自动重试工具操作               |
| 503  | runner.notConfigured / runner.tenantClosed                                                         | 配置缺失或租户正在关闭                                |
| 504  | runner.runtimeTimeout                                                                              | 回合或 SDK 握手超时；进程已清理，可从 transcript 恢复 |

真实进程契约入口为 `bash scripts/check.sh runner`，使用本地 OpenAI-compatible Provider 和 Tool Proxy，不读取真实密钥，不产生计费请求。覆盖初始化、流式分批、只读工具、取消、全新 Runner 恢复、动态模型切换、已发布 Prompt 到达系统上下文、导出、SIGKILL、模型/工具失败、超时、并发准入、跨租户隔离、池满与空闲回收。
