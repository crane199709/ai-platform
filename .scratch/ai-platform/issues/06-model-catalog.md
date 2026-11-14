# 06: 模型目录与租户选择

**What to build:** 按 [spec](../spec.md) 的“实现决策”，实现平台级模型目录、DeepSeek/OpenAI 兼容 provider 配置，以及租户可用模型选择。

**Blocked by:** 02

**Status:** resolved

- [x] 平台级模型表：provider、model id、base URL、能力标记、状态、上下文窗口
- [x] 运营端模型目录维护；密钥只存引用，不存明文
- [x] 租户可用模型选择，按 Plan/Feature Pack 裁剪
- [x] 模型目录变更写审计；租户不能修改平台目录
- [x] 测试覆盖：目录 CRUD、租户选择、禁用模型不可选、跨租户隔离、密钥不出现在响应

## Comments

## Answer

已实现平台目录 HTTP CRUD 与租户可用模型选择，接口及部署契约见 [docs/model-api.md](../../../docs/model-api.md)。运营页面按协调约定由后续 Sub-App 工单统一接入，本工单交付可调用的 HTTP API。

- `V6__model_catalog.sql` 建立平台模型目录和租户模型选择，含中文表/列注释、标准列、逻辑删除、部分唯一索引；租户选择启用并强制 RLS。provider 为 DeepSeek/OpenAI 兼容，支持模型标识、HTTPS URL、能力、状态、上下文窗口。
- `/ai-platform/models` 由独立 Platform JWT 链保护，真实 IAM 的 `platform:ops:manage` 控制维护；Tenant Token 直连同样拒绝。复用现有平台运维授权，没有把平台权限伪装进 Tenant Manifest，也未改动已有用户授权。显式启用方法安全保证 `@PreAuthorize` 生效。
- `/ai/models`、`/ai/models/{id}` 返回当前租户可用项；`/ai/models/catalog` 与 `/ai/models/selection`（GET/PUT）供管理员配置。未配置默认全部平台启用项，空选择关闭全部；平台禁用/删除后不能再取得可用配置。读取要求 AI 使用权限，配置另要求 AI 管理权限；真实 IAM 已按 Plan Feature Pack 裁剪有效 grants，服务没有新增 Plan 事件 Handler。
- `model.service.ModelSelectionService.resolveForUse(UUID)` 向后续 Conversation 提供服务端 `ModelRuntimeConfiguration`（仅引用，不解析真实密钥），并再次检查启用/租户选择/有效权限；不得直接序列化给浏览器或 Runner 上下文。HTTP 响应无密钥引用，请求/实体/配置的 toString 省略字段，URL 禁止夹带 userinfo/query/fragment。
- `ai.model.create/update/delete`、`ai.model.selection.update` 经 SDK 审计，目录审计在真实 IAM Platform Realm 落库；审计不记 URL、载荷、引用或密钥。

### 验证证据

2026-11-14 在 `codex/ai-platform-06` 独立工作树完成。执行 `git merge --no-edit codex/ai-platform-v1`，最新集成 tip 仍为 `92edf73`，返回 Already up to date。

```bash
cd ai-service
AI_FORGE_RUNTIME_REPOSITORY=/Users/liuhuan/.m2/repository \
  ./mvnw -o --batch-mode -s .mvn/settings.xml spotless:apply verify \
  '-Dtest=*Test,PlatformIntegrationIT' -DforkCount=1
```

16 项测试通过，0 失败/错误/跳过：真实平台 HTTP 集成 7、架构 1、HTTP 基础契约 3、Manifest/公开 SDK 契约 2、Schema/SDK 迁移检查 3。测试覆盖目录 CRUD、真实平台授权拒绝、Tenant/Platform Realm 不可互换、租户选择隔离、只读成员禁止配置、角色已授权但套餐无 AI 时拒绝、已选模型禁用后不可用、服务端配置接缝、引用不出响应及目录三类审计真实落库。真实平台测试沿用 `PlatformEnvironment`，无权限或审计 mock。报告在 `ai-service/target/surefire-reports`，真实平台日志和制品 SHA-256 在 `ai-service/target/platform-it`。

Manifest 权限目录动作策略校验与 `git diff --check` 均通过。TDD 红灯依次观察到平台目录 401、租户选择端点 404；真实无权限 Operator 测试还抓到方法安全未启用导致 200，补齐 `@EnableMethodSecurity` 后全量复验通过。新增只读成员夹具初次违反 IAM Membership 唯一约束，已改为独立 Identity 后重跑，未绕过约束。

### 未验证限制

未访问真实模型密钥，未调用 DeepSeek/OpenAI 兼容端点或计费 API；密钥解析、实际模型调用与运营页面由后续链路接入。平台运维权限当前涵盖其他运维操作，尚无模型专属细粒度权限，需要平台后续提供正式契约。验证消费本机已有 SDK/运行快照，未重新执行远端 Maven 发布消费验收；本工单未更改 SDK 版本。Nacos/共享部署环境未操作，隔离测试使用交付网关配置和随机直连端口。
