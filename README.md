# AI Platform

[English](README-en.md) | [SaaS Forge](https://github.com/crane199709/saas-forge)

面向租户内部成员的 AI 工作台，作为独立产品接入 SaaS Forge。仓库包含 Java Business Service、Node Runner 和 Vue Sub-App，复用平台的租户、权限、套餐、审计和应用注册契约。

当前交付工程骨架与 AI 服务平台接入：Java 接入 Forge SDK、Manifest 角色模板和租户上下文接口，并提供真实 IAM、网关、审计与 App Registry 的隔离验收。Runner 提供存活检查，Sub-App 显示工作台空状态；会话、聊天流、DSH、模型、Prompt、Skill、MCP 与完整部署由后续工单实现。

## 工程

| 目录                  | 内容                                                                  |
| --------------------- | --------------------------------------------------------------------- |
| ai-service/           | Java 17 / Spring Boot，Forge archetype 生成；SDK 契约、迁移和架构测试 |
| runner/               | Node 24 / TypeScript，内网 Runner 的最小启动与存活入口                |
| ai-sub-app/           | Vue / sub-app-kit，编码 ai，联邦入口 dist/remoteEntry.js              |
| deploy/               | 部署前提与配置边界                                                    |
| docs/                 | AI 术语、6 个 ADR 与 CI 说明                                          |
| .scratch/ai-platform/ | 产品规格、交接设计与执行工单                                          |

## 本地构建

需要 Java 17、Node 24、pnpm 12.4.2、Docker，以及可读的 Forge Maven 快照。前端配套包已经固定在 .forge-packages 中。

```bash
pnpm install --frozen-lockfile
pnpm typecheck
bash scripts/check.sh
```

完整检查会运行 Java 测试容器、Runner HTTP 测试和浏览器页面测试。首次运行浏览器前执行 `pnpm --filter @forge/ai-sub-app exec playwright install chromium`。Java 在线解析需要 FORGE_MAVEN_USERNAME 与 FORGE_MAVEN_TOKEN；已有匹配 SDK 的本地离线迭代可执行 `AI_MAVEN_OFFLINE=true bash scripts/check.sh`。离线构建不替代远端消费验收。

## 启动骨架

Java 启动前先准备 [部署前提](deploy/README.md)，把 ai-service/.env.example 复制为 ai-service/.env.local，填写账号与服务凭证，再在 ai-service 目录加载变量并执行 `./mvnw spring-boot:run`。应用不会自动加载该环境文件。共享环境按部署文档合并网关配置并登记应用；真实平台隔离验收使用 `AI_FORGE_RUNTIME_REPOSITORY=运行制品仓库路径 bash scripts/check.sh platform`，前置条件见部署文档。

```bash
# Runner：GET http://127.0.0.1:8181/health
AI_RUNNER_HOST=127.0.0.1 AI_RUNNER_PORT=8181 pnpm --filter @ai-platform/runner start
# Sub-App 独立开发宿主：http://localhost:5180
pnpm --filter @forge/ai-sub-app dev
```

Runner start 前先运行其 build。开发宿主复用平台登录；页面测试预置假会话并模拟 IAM，真实用户登录仍需要平台。构建 Sub-App 使用 `pnpm --filter @forge/ai-sub-app build`。

## 持续集成与规格

CI 分别检查 Java、Node、Vue，并每日以隔离 Forge 缓存验证已发布的 0.2.0-SNAPSHOT。统一检查入口、凭据和截图边界见 [CI 说明](docs/ci.md)。AI 工作台权限为 ai:conversation:use，Sub-App 编码 ai；Manifest 声明 `ai-user` / `ai-admin` 角色模板与配置管理权限；`/ai/context` 返回已验证的租户和成员上下文。会话 API 尚未实现。

[产品规格](.scratch/ai-platform/spec.md) · [工单](.scratch/ai-platform/issues/) · [术语](docs/GLOSSARY.md) · [架构决策](docs/adr/)
