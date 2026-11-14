# 07: Prompt 管理与版本

**What to build:** 按 [spec](../spec.md) 的“实现决策”，实现 Prompt 模板、变量、草稿/发布/历史/回滚，并在会话启动时应用。

**Blocked by:** 02

**Status:** resolved

- [x] Prompt 模板与版本表：模板、版本、内容、变量、状态、发布人/时间
- [x] 草稿保存、发布、历史、回滚；发布写审计
- [x] 平台预置 + 租户自定义；租户只能管理自己的 Prompt
- [x] 会话启动接缝按租户选择已发布版本并渲染；实际 Runner 注入由工单 04 消费接通
- [x] 变量校验与预览，非法变量保存失败
- [x] 测试覆盖：草稿/发布/回滚、变量校验、权限、跨租户隔离、注入内容正确

## Comments

## Answer

本轮按执行范围交付后端 Prompt 管理和会话启动公开接缝，管理页面由工单 05 接入，实际 Runner 注入由工单 04 完成；不将 HTTP 渲染验收记为 Runner 联调完成。

- V7 迁移建立租户草稿、不可变发布版本和独立的平台预置表，全部带标准列与中文注释；租户表启用强制 RLS。草稿保存、发布、回滚锁定同一模板行。回滚只切换发布指针，不改草稿或旧版本。
- 平台默认模板由迁移发布为 v1，租户可以读取/使用，不能修改、发布或回滚平台预置。租户自定义模板受 SDK 租户隔离。
- 新增 read/update/publish/rollback 四项权限，ai-admin 角色包含这些权限。只有 use 权限的普通成员可读取发布选择项，不能读取草稿；草稿管理员仍没有发布/回滚权限。Prompt 对齐时 Manifest 递增至 5，联合 Skill 合并后版本为 6。
- 变量声明与占位符集合必须一致，拒绝非法名称、未闭合、重复声明、缺失/多余渲染值；预览无写入，变量值只做一次字面替换。含正文/变量值的模型覆写安全 toString。
- 发布/回滚使用真实 AuditLog → Outbox → RocketMQ → IAM 链路，只记录模板 ID、租户、操作人等元数据，detail 为 {}，不写名称、正文、变量值或密钥。
- 公开接口为 `prompt.service.PromptService.resolvePublished(UUID, Integer, Map<String,String>)`，返回 `ResolvedPromptResponse(templateId, version, content)`；templateId 为 null 时选默认预置，version 为 null 时选当前发布版本。HTTP 管理、预览、默认渲染、只含元数据的发布选择列表见 [Prompt 管理契约](../../../docs/prompt-management.md)。

### 验证与证据

TDD 在已确认的 Java HTTP / PostgreSQL / 真实 IAM 权限与审计接缝进行。四个垂直切片分别观察到草稿接口 404、版本渲染接口 404、非法模板错误地返回 201、默认模板路径未实现返回 400 的红灯，然后补实现并观察绿灯。

- Prompt 自身阶段：完整 PlatformIntegrationIT **9/9 通过**，含原有平台契约和五个 Prompt 场景；日志 `/private/tmp/ai-prompt-green4.log`。
- 合入模型目录 2fbac4b 后：`AI_MAVEN_OFFLINE=true MAVEN_ARGS=-DforkCount=1 bash scripts/check.sh service` **通过**，9 个常规测试、架构/注释/格式/权限目录门禁通过；日志 `/private/tmp/ai-prompt-verify-after-merge.log`。
- 合并后完整真实平台：12 个场景中 **11 通过、1 错误**；全部 Prompt 场景通过，唯一错误是原有模型平台审计等待 60 秒仍为空。日志 `/private/tmp/ai-prompt-platform-after-merge.log`，环境日志与测试摘要保留在 `/private/tmp/ai-prompt-audit-timeout-evidence/`。
- 相同代码、相同 60 秒断言复验：初始化阶段 RocketMQ 消费者报 `forge-tenant` 无 topic route（40402，三次获取失败），尚未进入业务场景。日志 `/private/tmp/ai-prompt-platform-after-merge-retry.log`，环境日志与摘要保留在 `/private/tmp/ai-prompt-topic-route-evidence/`。未放宽、跳过或 mock 权限与审计断言；组合验收需 fresh merger 在独立环境复验。
- topic 准备与启动逻辑位于 `PlatformEnvironment`：broker clusterList 启动门禁、Proxy 端口监听门禁、updateTopic/topicRoute 循环。上述 Maven 日志保留了消费者启动异常；既有夹具没有记录 mqadmin 命令的完整 stdout/stderr，后续 readiness 排查应补有边界的证据。
- 已合入最新集成基线 90431a6；其相对 2fbac4b 仅涉及 Runner/pnpm/文档，没有 Java 变更。按集成协调要求不因该合并重复无关 Runner 测试。
- 本轮使用本机缓存 SDK 离线验证，没有重跑排除本机 Forge 缓存的已发布 SDK 下载验收。


### 2026-11-14 集成权限对齐与组合诊断

- 依据 Sub-App 页面规范的草稿 `update` 动作，将 Controller、Manifest、管理员角色、真实 IAM seed、Golden 契约与接口文档统一为 `ai:prompt:update`；根权限 `ai:configuration:manage` 保持原契约。Manifest 从 4 递增至 5。
- 先独立修改 Golden 期望，观察缺少 `ai:prompt:update`、多出 `ai:prompt:manage` 的红灯，再修改声明与接口，窄契约 2/2 通过。日志 `/private/tmp/ai-prompt-update-red.log`、`/private/tmp/ai-prompt-update-green.log`。
- 真实权限场景覆盖仅草稿编辑权限可创建、保存和预览，但不能发布/回滚；只有 use 权限的成员对创建、保存、预览均被拒绝。本轮完整组合首次复验 11/12 通过，Prompt 全部场景通过，模型审计超时；`/private/tmp/ai-prompt-combined-first.log`。
- `AI_MAVEN_OFFLINE=true MAVEN_ARGS=-DforkCount=1 bash scripts/check.sh service` 通过全部常规测试及架构、注释、格式、权限目录检查；日志 `/private/tmp/ai-prompt-update-service.log`。
- 模型单方法与租户审计→模型两方法真实复验分别 1/1、2/2 通过。原完整 12 项再次复验时 Prompt 审计也出现缺失；隔离 Broker `pop.log` 已捕获 `OutOfDirectMemoryError`：堆外内存使用 131727360、上限 134217728，POP checkpoint 申请 4259840 字节失败，伴随 Proxy 超时。Outbox 均已发送，IAM 对应去重/死信记录不存在。证据 `/private/tmp/ai-prompt-combined-probe-evidence/`，尚待调整测试资源后验证因果，不能据此宣称组合验收通过。


### 2026-11-14 Model / Prompt / Skill 联合验收完成

- 已顺序合入工单 07、Prompt 权限对齐 `7801400` 与工单 08 `57f09d9`；联合合并提交 `8a5f484`，Manifest 为 6。原有 Model、Prompt 与新增 Skill 的全部 15 项真实 IT 保留。
- 审计超时根因经原 12 项红灯、Broker POP 堆外 OOM 证据和修改后的联合绿灯验证：测试 MQ 的 128 MiB 堆外内存上限不足，POP checkpoint 编码器按线程分配约 4 MiB 缓冲失败，Proxy 随之超时。只将夹具 `MaxDirectMemorySize` 提升至 512 MiB，没有修改平台/SDK、审计超时、权限或事件断言。
- `AI_MAVEN_OFFLINE=true AI_FORGE_RUNTIME_REPOSITORY=/Users/liuhuan/.m2/repository MAVEN_ARGS=-DforkCount=1 bash scripts/check.sh platform`：**15/15 通过，0 跳过**，包含 Model Platform 审计、Prompt Tenant 发布/回滚、Skill 两个 Realm 的审计以及独立 update/publish/rollback 权限。日志 `/private/tmp/ai-prompt-skill-platform-direct512.log`；制品校验摘要、平台与 Broker 日志 `/private/tmp/ai-prompt-skill-direct512-evidence/`。采样中没有 OOM、POP message error 或 Proxy timeout。
- `AI_MAVEN_OFFLINE=true MAVEN_ARGS=-DforkCount=1 bash scripts/check.sh service`：**9/9 通过**，架构、迁移/SDK 注释、格式、权限目录门禁通过；日志 `/private/tmp/ai-prompt-skill-service-final.log`。`git diff --check` 通过。
- 已移除临时 IAM PID/退出方式探针。历史 `forge-tenant` 40402 启动错误未在本次最小、二方法与联合复验中重现，未据此增改 readiness；本轮未执行排除本机缓存的已发布 SDK 下载验收。

### 2026-11-14 正式审查修复补充

单Controller使用的RenderPromptRequest已内嵌，字段、校验与安全toString保持；共用UI操作反馈不改变Prompt请求。完整49项HTTP/权限回归与前端14/26通过。 两轴原6项均关闭且新增0；准确提交、红绿日志、制品摘要与未验证边界见[当前验收记录](../acceptance.md)。
