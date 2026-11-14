# 03: Runner 与 AgentRuntime

**What to build:** 按 [spec](../spec.md) 的“实现决策”，实现 Node/TS runner，把 DSH 包装成 `AgentRuntime`：每租户进程、内网 HTTP/SSE、取消、会话导出与契约测试。

**Blocked by:** 01

**Status:** resolved

- [x] runner 使用固定 DSH 版本与 `sdk` profile，启动/复用每租户 DSH 进程
- [x] `AgentRuntime` 端口覆盖：初始化、建会话、发消息/流式、取消、导出、关闭
- [x] 每租户独立 `DSH_HOME` 与临时 workspace，进程资源限额
- [x] 内网 HTTP/SSE 接口，只有 AI 服务可调用；不暴露 shell/FS 工具
- [x] 空闲回收与池满 429 的接口语义
- [x] 契约测试：初始化 → 建会话 → 流式 → 取消 → 恢复 → 导出
- [x] 记录 DSH 版本、启动参数与错误分类，供 AI 服务排障

## Comments

### 2026-11-14 工单认领

基于集成分支 `codex/ai-platform-v1` 的 `92edf73`，在隔离 worktree 的 `codex/ai-platform-03` 实现。验收接缝采用已授权的 Runner HTTP/SSE 与真实 DSH 进程契约，模型端使用本地假 Provider。

## Answer

2026-11-14，基于最新集成分支 `2fbac4b` 完成 Runner / AgentRuntime。正式接入契约见 [docs/runner-contract.md](../../../docs/runner-contract.md)，公开端口见 `runner/src/runtime.ts`，适配层为 `dsh-runtime.ts` / `dsh-process.ts`。

- 固定官方 `@deepseek-ai/dsh@0.2.2-rc.1` 和完整根 lockfile；用官方 `sdk` profile + 受控 patch + JSON-RPC 创建真实 Session，保留可替换端口，不修改上游内部日志格式。
- 每租户独立 Node/DSH 进程、0700 home/workspace、最小环境允许列表；同模型/工具/Prompt 配置复用进程，进程组终止与目录清理幂等。模型/MCP 只向固定 AI 服务代理转发短期能力，原始密钥、用户 Token 不进 Runner。
- 仅受服务令牌保护的内网 HTTP/SSE；真实 Provider 分批 delta 抵达即转发；官方 native shell/FS/Web/plugin manager/子代理/Office 等工具被关闭；工具清单严格等于 AI 服务声明的只读 MCP 工具。
- 支持取消、导出、崩溃与全新 Runner 恢复、动态模型选择、已发布 Prompt instructions；同租户回合准入独占，默认 15 分钟空闲清理，池满 429 + Retry-After: 5。修复并验证同时首回合和慢请求体的准入竞争。
- 标准化 token 用量：inputTokens 包含缓存读取/写入，cachedInputTokens 是缓存读取子集，避免配额漏计。Prompt 通过官方 system-prompt.personaPrefix 进入真实 system 上下文，逻辑 transcript 保留原用户消息。

### 验证证据

- Node `v24.14.1`，pnpm `12.4.2`。
- `pnpm install --frozen-lockfile`：通过。根 pnpm-workspace.yaml 明确拒绝 5 项未使用原生能力的构建脚本，避免 CI 安装时待审批；本工单因此同时修改根 workspace 配置和 lockfile。
- `bash scripts/check.sh runner`：TypeScript strict typecheck、构建和 **17 条契约全部通过，0 skip**，最终运行约 24.7 秒。
- 契约使用真实官方 DSH 子进程和本地假 OpenAI-compatible Provider / Tool Proxy；覆盖初始化、真正建 Session（不发模型请求）、分批流式、只读工具、取消、上下文恢复、导出、SIGKILL、模型和工具失败、超时、跨租户隔离、池满、空闲、并发、缓存计量及 Prompt system 上下文；未读取真实密钥、未发送计费请求。
- `git diff --check`：通过。
- 与正式启动相同的 POSIX 包装实测：CPU rlimit 120 秒、open files 128；`node --max-old-space-size=256 --max-semi-space-size=8` 的 V8 heap_size_limit 为 293601280 bytes（280 MiB）。

### 上游限制与交接

- SDK 没有 prompt-cancel；取消终止租户进程，本租户其他会话句柄失效，下一回合从产品 transcript 恢复。
- SDK 没有 history import / 实时 token 通知。恢复采用角色/工具摘要的 context-replay，保留事实而不保证原消息角色边界和推理缓存相同；流式来自固定模型代理的透明 SSE 观测。禁用 DSH JSONL 和投影缓存，不依赖内部持久化结构。
- 请求上限 1 MiB，恢复最多 1000 条、每条 content 128000 字符；AI 服务需要明确会话长度策略。中断未提交的 delta 不伪装为完整助手消息。
- Node 堆限制不等于 RSS/cgroup 硬限制；本地 FD/CPU rlimit 和回合超时已实测，Linux/Kubernetes cgroup/网络隔离由工单 12 补充，未冒充已验证。
- 工单 04/09 按契约实现模型代理 `/chat/completions` 和 Tool Proxy `/tools/call`；工单 07 将已渲染发布 Prompt 通过会话 instructions 传入；工单 08 使用文档列明的官方受控 Skill 物化接缝，避免误用不存在的 DSH_SKILLS_PATH。

### 2026-11-14 正式审查修复补充

逐次从原始提供方SSE确定完整计量；DSH合成零对象不作上报证据，显式合法零值保留。真实DSH 30/30、真实组合ZERO/MISSING/MIXED及新Linux镜像均已通过。 两轴原6项均关闭且新增0；准确提交、红绿日志、制品摘要与未验证边界见[当前验收记录](../acceptance.md)。
