# 平台接入

工单 02 提供 AI 服务的 SDK 接入、配置片段与隔离平台验收。镜像、网络策略和完整产品部署由工单 12 交付。共享环境操作由对应管理员确认目标后执行；本仓库不自动改写已有平台的数据库、Nacos 或授权配置。

## 数据库

使用 `forge` 库的独立 `ai` schema。`ai_migrator` 持有 schema 并执行 Flyway；`ai_app` 仅持有数据读写与序列权限。两者均无超级用户、BYPASSRLS、建库或建角色权限。[provision-service.sql](postgres/lib/provision-service.sql) 供测试与部署共用。

管理员先在目标库安装共享的 `extensions.ltree`，收回 PUBLIC 对数据库及 schema 的不必要权限，再执行：

```bash
# PGHOST、PGPORT、PGDATABASE=forge、PGUSER、PGPASSFILE 由目标环境提供。
psql -X -c 'SELECT current_database(), current_user, inet_server_addr(), inet_server_port();'
psql -X -v service=ai \
  -v migrator_password="$AI_MIGRATOR_PASSWORD" \
  -v app_password="$AI_APP_PASSWORD" \
  -f deploy/postgres/lib/provision-service.sql
```

脚本事务执行、可重复运行，不轮换已有密码，不接管同名高权限账号。不要删除现有开发库以重新初始化。随后注入 `ai-service/.env.example` 中的变量；JDBC URL 的 `currentSchema` 使用 `ai,extensions`。已有 V1 SDK 迁移保持不变。

本阶段只建立 SDK 基础设施表。后续模型/MCP/Skill 平台目录与 Conversation/消息/Prompt/用量租户表使用不同的表，均位于 `ai` schema。平台目录显式加入 `forge.tenant.ignore-tables`，只由受限运营接口维护；租户业务表带 `tenant_id`、标准审计列与 `deleted`，启用并强制 RLS，使用 `app.tenant_id` 策略，实体继承 `AuditableEntity`。工单 02 用临时租户记录验证标准列、MyBatis 过滤和独立 RLS 防线；生产包不包含该表或探针接口。实际业务表分别随对应功能工单交付。

## 服务身份、网关与任务

1. Operator 在平台控制台按服务名 `ai-service` 登记 Service Credential，一次性明文注入 `FORGE_MANIFEST_TOKEN`；`FORGE_IAM_URI` 使用 IAM 内网地址。Manifest 版本为 2，声明工作台菜单、`ai:conversation:use`、`ai:configuration:manage` 与 `ai-user` / `ai-admin` 模板。后续发布与回滚权限随对应工单细化。
2. 独立 Ops Token 注入 `FORGE_OPS_TOKEN`，并在 Platform 的 `forge.platform.ops.tokens.ai-service` 配置相同秘密，不复用 Manifest 凭证。正确 Ops Token 可直连读取 `/internal/onboarding-status`；`phase=REPORTED`、`reportedVersion=2` 表示本实例上报成功。Service Credential 读取该端点应返回 401。
3. 将 [forge-gateway.yaml](forge-gateway.yaml) 的 AI 路由和 Realm 前缀**追加合并**到已有 Nacos 配置，保留其他服务。服务通过 Nacos 以 `ai-service` 注册。网关剥离 `/ai`：外部 `/ai/context` 对应服务 `/context`；浏览器 SDK 请求 `/api/ai/context`，Shell 剥离 `/api`。
4. 合并 [forge-platform.yaml](forge-platform.yaml) 的 Ops 映射与初始化服务名单，保留现有条目。`AI_SERVICE_OPS_TOKEN` 与 AI 服务的 `FORGE_OPS_TOKEN` 来自同一秘密。
5. 建立 `forge-tenant`、`forge-app`、`forge-audit` Topic，注入 RocketMQ proxy gRPC 地址和 Redis 配置。Provisioning 与 Audit 使用 SDK Outbox；Scheduling 已接入并复用 IAM 地址与 Service Credential，后续任务使用 `@TenantTask` / `@GlobalTask`。

确认匿名 `/ai/v3/api-docs` 可读且 `x-forge-service=ai-service`，`servers` 包含网关的 `/ai` 前缀；保留并核对环境的 `spring.cloud.gateway.server.webflux.trusted-proxies`，按实际代理地址配置。服务直连 `/actuator/health` 返回 UP。`/ai/context` 无 Token 返回 401、缺少使用权限返回 403、有权限的 Tenant Token 返回当前 tenant/identity/membership 的标准信封。Platform Token 被拒绝，网关拒绝 `/ai/internal/**`。

## App Registry 与套餐

先构建并发布 `ai-sub-app/dist`。本地使用 `pnpm --filter @forge/ai-sub-app start:ai` 启动 `http://localhost:5180`。在平台控制台应用注册页登记，或以有 `platform:sub-app:manage` 的 Operator Token 调用：

```bash
# 本地示例；其他环境先修改 environment 与浏览器可达的 remoteEntry URL。
curl --fail-with-body "$GATEWAY_URL/platform/sub-apps" \
  -H "Authorization: Bearer $PLATFORM_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @deploy/app-registry.local.json
```

[登记载荷](app-registry.local.json) 的编码 `ai` 与 Manifest 一致；环境名必须匹配 IAM 的 `forge.iam.sub-app.environment`，共享依赖最低版本须与实际 Shell 配套。读回注册表，再在目标 Plan 的 `subApps` 中加入 `ai`、`permissions` 中加入需要的 AI 权限，向租户角色授权。角色模板在后续租户初始化时预置，已有租户按平台流程处理。

事件同步后 IAM `/auth/access` 应返回 AI 菜单与当前环境的 remoteEntry，Shell 入口为 `/apps/ai/home`。工单 02 验证注册和 IAM 同步；完整 Shell 加载、页面主链路和发布环境验收由工单 05、12 完成。

## 隔离平台验收

```bash
AI_MAVEN_OFFLINE=true bash scripts/check.sh service
AI_MAVEN_OFFLINE=true \
AI_FORGE_RUNTIME_REPOSITORY="$HOME/.m2/repository" \
  bash scripts/check.sh platform
```

第二条命令要求 Docker 及与 SDK 配套的真实 `forge-iam`、`forge-platform`、`forge-gateway` 的 `0.2.0-SNAPSHOT` 可执行 jar，按 Maven 仓库目录布局存放。平台不发布这三个 Maven 应用制品；由平台提供运行分发，或在独立平台工作区构建安装后显式传入目录。AI 服务只依赖公开 SDK，测试不引用平台实现类。未提供制品时验收直接失败，不跳过，不以 mock 替代。

验收自动创建 PostgreSQL、Redis、RocketMQ 测试容器，以随机端口运行真实 IAM/Platform/Gateway，应用交付的网关配置片段，完成真实登录、PKCE 换 Token、权限拒绝、数据库凭证上报、租户过滤与 RLS、审计经 Outbox/MQ 写入 IAM、App Registry 登记与事件同步。数据只写本次临时库，结束后停止进程并回收容器。日志与制品 SHA-256 在 `ai-service/target/platform-it`，测试报告在 `ai-service/target/surefire-reports`。

隔离测试关闭 Nacos，以交付的路由配置直连随机端口，不验证共享环境的 Nacos 发布或服务发现。常规三工程 CI 不要求平台运行分发，远端 SDK 消费与隔离平台验收分别记录。

Runner 仍需 Node 24，仅提供内网 GET `/health`。模型密钥、MCP 凭据、DSH 和聊天能力由后续工单接入，本阶段不使用模型密钥。
