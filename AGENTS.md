# AGENTS.md

本仓库是 SaaS Forge 之外的 AI 产品，包含 ai-service、runner、ai-sub-app。范围与待办以 `.scratch/ai-platform/spec.md` 和 `issues/` 为准，工单 01 只建立工程与 CI。

- 修改 Java 前读 `ai-service/AGENTS.md` 与随脚手架交付的业务规范。使用 Forge 公开 SDK，不引用平台实现类；新增 SDK 表按规范复制迁移。
- 修改页面前读 `ai-sub-app/AGENTS.md` 与页面规范，走查截图；Sub-App 复用 Shell 会话和客户端。
- Runner 使用 Node 24 与严格 TypeScript。公开行为经 HTTP 契约测试；DSH 适配与进程隔离属于工单 03。
- 术语正文在 `docs/GLOSSARY.md`，架构决策在 `docs/adr/`。工单记录约定见 `docs/agents/issue-tracker.md` 与 `triage-labels.md`。
- 使用 `bash scripts/check.sh` 统一验证三套工程，子工程迭代时指定 service、runner 或 sub-app。已发布 SDK 验收使用 `scripts/verify-published-sdk.sh`，排除本机 Forge 缓存；本地离线验证用 `AI_MAVEN_OFFLINE=true`。
