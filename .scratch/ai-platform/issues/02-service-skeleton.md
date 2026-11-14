# 02: AI 服务骨架与平台接入

**What to build:** 按 [spec](../spec.md) 的“实现决策”，用 `forge-service-archetype` 生成 Java AI 服务，接入 Forge SDK、独立 schema、Manifest 与网关。

**Blocked by:** 00, 01

**Status:** resolved

- [x] 服务继承 Forge 依赖与父 POM，接入 tenant、security、audit、web、manifest、provisioning、scheduling
- [x] 独立 schema 与迁移账号；平台级表与租户级表分开，租户表带标准列
- [x] 健康检查、统一响应信封、错误码、OpenAPI
- [x] Resource Manifest 声明权限码、菜单、角色模板与 Sub-App 编码 `ai`
- [x] App Registry 注册与网关 `/ai/**` 路由
- [x] 服务凭证、租户上下文、审计落库打通
- [x] 集成测试覆盖：健康检查、未认证 401、跨租户不可见、审计事件落库

## Comments


### 2026-11-14 实现与验收

- 复用工单 01 的 Forge archetype 工程，补齐 `forge-sdk-scheduling`，沿用公开 tenant/security/audit/web/manifest/provisioning SDK，不引用平台实现类。
- `ai` schema 使用 `ai_migrator` / `ai_app` 双账号；部署与 SharedContainers 共用参数化准备脚本。验证运行账号不拥有表/schema、无高权限，Flyway 确由迁移账号执行。既有 V1 迁移不改写。
- 新增 `/context`，经网关为 `/ai/context`，要求 `ai:conversation:use`，只返回已验证 JWT 的租户、身份与 Membership；健康、统一错误信封和 OpenAPI 沿用 SDK。
- Manifest 升级至版本 2，声明 `ai` 菜单、使用与配置管理权限及 `ai-user` / `ai-admin` 模板。交付 App Registry 登记载荷、网关路由/Realm 前缀与 Platform 配置片段，接入步骤见 [deploy/README.md](../../../deploy/README.md)。
- 平台目录与租户业务表的隔离约定已记录。工单 02 用仅测试可见的标准表验证租户过滤、标准列与独立 RLS 防线；Conversation/消息表、模型目录等实际业务表分别由 04、06 等所属工单实现，避免提前创建业务模型。
- `AI_MAVEN_OFFLINE=true bash scripts/check.sh` 通过：Java 9 项测试、架构/中间件/格式/Manifest 动作策略门禁，Runner 类型/构建与 1 项 HTTP 测试，Sub-App 类型/样式/构建及 1440/1024 两档截图回归。生产 jar 检查确认不含验收 Probe、夹具迁移或测试密钥。
- `AI_MAVEN_OFFLINE=true AI_FORGE_RUNTIME_REPOSITORY=<本机 Maven 仓库> bash scripts/check.sh platform` 最终 4/4 通过：真实 IAM 登录与 PKCE、Tenant/Platform Realm 隔离、无使用权限 403、数据库 Service Credential 上报和错误凭证 401、Ops 凭证隔离、MyBatis 与独立 RLS 跨租户不可见、审计经 Outbox/RocketMQ 写入 IAM 并保持租户隔离、App Registry 登记与 MQ 同步至 IAM、OpenAPI 外部 `/ai` 前缀。
- 真实平台运行使用本机 `0.2.0-SNAPSHOT` 可执行制品，隔离 PostgreSQL/Redis/RocketMQ 与随机端口；不修改已有开发平台。常规 CI 仍只消费已发布 SDK；平台运行 jar 未发布到 Maven Packages，真实平台入口的额外制品要求已写入文档。本轮没有重新做远端 SDK 发布/消费验收。
- Nacos 在隔离验收中关闭，网关使用实际交付配置片段和直连随机端口；共享环境的 Nacos 发布/服务发现与完整 Shell 页面加载不在本次动态验证范围，分别按接入文档和 05/12 验收。
- 规范审查发现的响应类型命名、显式 import、Awaitility 静态导入与测试创建状态码问题已修复并复核，最终剩余 0 项；规格审查 0 项。

平台运行制品 SHA-256（运行日志与报告位于 `ai-service/target/platform-it`、`target/surefire-reports`）：

| 制品 | SHA-256 |
| --- | --- |
| forge-iam | `e68e80d80861046f3a1ae459ad3a28024893a3c00636eb47ad97d8cda4ffa5ec` |
| forge-platform | `3a739133d7a19609cbed9f8420324d67f89e5620e67a5766b6379e27ea34a006` |
| forge-gateway | `8a1c4dbdf7c438fbf29b5132838cd6c3cd2571891a22328f267322727b8f7897` |
