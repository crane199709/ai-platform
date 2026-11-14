# 08: Skill 目录与租户启用

**What to build:** 按 [spec](../spec.md) 的“实现决策”，实现 Skill 目录、租户启用与 runner 物化。

**Blocked by:** 02

**Status:** resolved

- [x] 平台级 Skill 目录：编码、名称、说明、版本、文件内容/引用、状态
- [x] 租户启用/停用 Skill；不能上传任意 Skill 代码（v1）
- [x] 会话启动时把启用 Skill 物化到租户 DSH home 的 skills 目录
- [x] 变更写审计；停用后新会话不再加载
- [x] 测试覆盖：启用/停用、物化内容、权限、跨租户隔离、停用后新会话不可见

## Comments


### 2026-11-14 实现交接（保持 claimed，待组合验收）

实现提交 `b95b12e`；合入工单 03 的真实 DSH 与工单 07 后提交 `7aa7e03`，基线包含集成分支 `764b682`。仅在隔离分支 `codex/ai-platform-08` 本地提交，未推送。

- 平台 `/platform/skills` 复用独立 Platform Realm 与真实 `platform:ops:manage`，保存审核 Markdown 草稿、不可变发布历史、回滚为新版本及停用状态。
- 租户 `/skills` 使用 `ai:skill:read/update/publish/rollback`，启停配置独立草稿、发布、历史与回滚；发布时锁定平台审核正文版本。V8 创建平台目录/版本和带标准列、RLS 的租户配置/版本表。
- `skill.service.SkillService.resolvePublished()` 返回不可变 `List<SkillSnapshot>`，字段为 `catalogId,code,name,description,version,content`，供工单 04 在创建 Conversation 时持久化并传入 Runner。恢复必须复用 Conversation 原快照。HTTP 与接入细节见 [Skill 契约](../../../docs/skill-api.md) 和 [Runner 契约](../../../docs/runner-contract.md)。管理页面由工单 05 接入。
- Runner HTTP 建会话接收 `skills`，拒绝路径、脚本附件、重复编码与无效版本；只向随机私有 home 写受控 `skills/<code>/SKILL.md`，官方 `skill-filesystem` 关闭默认根目录和 watch，仅非空快照启用 `skill` 加载工具，执行工具继续禁用。Skill 快照参与每租户进程配置比较，原会话快照不受新配置改变。
- 已修复未发布目录通过 `enabled:false` 草稿返回平台草稿名称/说明的问题。已发布目录的租户元数据来自发布版本，会话正文及元数据来自租户锁定的历史版本。

已通过的验证：

- `AI_MAVEN_OFFLINE=true bash scripts/check.sh service`：合入工单 07 后 9/9，通过架构、HTTP、迁移/SDK 注释、格式及权限目录检查；日志 `/private/tmp/ai08-service-final.log`。
- `bash scripts/check.sh runner`：19/19，包含真实固定版 DSH；日志 `/private/tmp/ai08-runner-all.log`。加强的同一 Skill v1/v2 切换用例单独通过，证明新版会话收到新正文、旧版仍保留原正文；日志 `/private/tmp/ai08-runner-versions.log`。
- 真实 IAM 单方法 `PlatformIntegrationIT#skillCatalogPublishesImmutableReviewedVersionsAndRollsBack`：1/1；日志 `/private/tmp/ai08-green1.log`。
- 合入工单 07 前真实 IAM 单方法 `PlatformIntegrationIT#tenantSkillPublicationIsAuthorizedIsolatedVersionedAndAudited`：1/1，包含租户发布/回滚与平台发布的真实审计、不含正文断言；日志 `/private/tmp/ai08-green2.log`。
- 合入工单 07 后真实 IAM 单方法 `PlatformIntegrationIT#tenantCannotDiscoverOrConfigureUnpublishedSkillDraft`：1/1；日志 `/private/tmp/ai08-boundary-final.log`。

此前待闭环的组合验收：完整 `PlatformIntegrationIT` 曾为 10 条中 7 通过、1 个上述边界红灯及 2 个审计超时（新增 Skill Tenant 审计与原有 Model Platform 审计均未读到事件），日志 `/private/tmp/ai08-platform-all-red3.log`。边界已修复并单独验证；审计超时已交协调代理 `merge07` 统一诊断，当前没有产品根因结论，不以单方法成功替代全量通过。当时按协调要求暂停重复全量 IT；随后由当前集成 merger 顺序合并工单 07/08 并完成下述闭环。


### 2026-11-14 集成 merger 联合验收完成

- 已合入 `codex/ai-platform-v1` 的联合提交 `8a5f484`。保留 Skill 与 Prompt 全部权限、IAM seed 和 15 个真实 IT；Prompt 草稿独立使用 `ai:prompt:update`，Skill 保持 `ai:skill:update`，联合 Manifest 递增至 6。
- 公共审计失败来自隔离 RocketMQ 的 128 MiB 堆外内存上限，Broker `POP checkpoint` 申请编码缓冲时抛 `OutOfDirectMemoryError`，并导致 Proxy 超时；Outbox 已发送而 IAM 尚未消费。证据 `/private/tmp/ai-prompt-combined-probe-evidence/pop.txt`。只把测试夹具堆外预算调为 512 MiB，保留全部真实权限与 60 秒审计断言，未改产品或 SDK。
- 联合 `bash scripts/check.sh platform`（离线、真实 Forge 运行制品、forkCount=1）：**15/15 通过，0 跳过**，含 Skill 目录发布/回滚、Tenant 启停/草稿/历史/跨租户隔离、未发布内容边界、Tenant 发布/回滚审计与 Platform 发布审计；Model 与 Prompt 审计同时通过。日志 `/private/tmp/ai-prompt-skill-platform-direct512.log`，平台与 MQ 证据 `/private/tmp/ai-prompt-skill-direct512-evidence/`，新采样未见 OOM/POP 错误/Proxy 超时。
- 最终 service verify：**9/9 通过**，含架构、HTTP、迁移/SDK 注释、格式及权限目录门禁；日志 `/private/tmp/ai-prompt-skill-service-final.log`。Runner 与 `57f09d9` 的已验收内容一致，沿用上述 **19/19** 真实 DSH/物化回归结果；本轮没有改 Runner。`git diff --check` 通过。
- 会话创建时持久化并传递正式 `SkillService.resolvePublished()` 快照的接入由工单 04 完成，管理页面由工单 05 接入。本轮使用本机缓存 Forge 制品，未重跑排除缓存的已发布 SDK 下载验收。
