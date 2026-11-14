# 10: 配额、用量、审计与角色模板

**What to build:** 按 [spec](../spec.md) 的“实现决策”，消费 Plan token 配额、记录每回合用量、写 AI 审计，并提供 `ai-user`/`ai-admin` 角色模板。

**Blocked by:** 00, 02, 04

**Status:** resolved

- [x] 订阅 `TenantPlanChanged`，保存租户 token 配额；超限拒绝新回合
- [x] 每回合记录 token 用量（模型、输入/输出、工具调用），按租户/月聚合
- [x] 每日用量回传平台；平台可查看租户汇总
- [x] 审计：会话开始/结束、工具调用、模型切换、Prompt/Skill/MCP 发布与回滚；不记内容
- [x] 角色模板 `ai-user`、`ai-admin` 随 Manifest 预置；权限码与页面一致
- [x] 测试覆盖：配额耗尽拒绝、用量记录、事件乱序、审计落库、角色授权

## Comments


## Answer

实现与验收完成，功能源码已合入05（ea891eb）、09（d571fd2与89c48af），最终组合源码为 `cb23455f29ae6526e9a83deb9440c4e6d1341718`。平台接收端功能提交 `0647e0f1e1539f4882ee430a95d09f32f5ef909b`，平台分支当前包含额外视觉验收的 `d7a27a63d6d3a52a724f85bc5a0d5f1a825bcbd5`；二者后端源码一致。没有push、修改共享环境或合并原main。

### 交付行为

- V10新增 `ai_usage_plan`、`ai_usage_month`、`ai_usage_ledger`、`ai_usage_daily`，标准列、中文注释与租户RLS完整。公开TenantPlanChanged按持久版本保存quota及planId/planCode，重复/旧版本整体无变化；历史省略tokenQuota为已知不限，尚无可信快照时新回合503失败封闭，不能误认为不限。
- 业务日期/月界限仅使用必配 `forge.business-zone`。有限额度以租户/月数据库行锁串行准入，当前回合可以超额，随后拒绝新回合；已完成幂等回放不再准入或计量。
- 直接接入ConversationTurnService.prepare/finish同库事务，账本、日累计版本与Outbox原子提交；准入、模型请求尝试和工具计数也形成日快照，覆盖崩溃与停用前已提交数据，不依赖仅扫描active/trial的定时任务。
- input包含cached，cache是子集不重复加。提供方缺失/无效usage不当免费：实际请求尝试按模型contextWindow估计，独立记录unknown/estimated；未开始模型执行的拒绝为零模型成本。三分钟过期账本在独立事务恢复，避免一次取消/崩溃无恢复路径锁死整月。
- 运行中工具标识JSON去重并持久计数，不存args/content/result；重复call/result不重复加，结束/中断保留已提交数。后续11重试须按attemptId+callId传入scopedCallId，防止不同运行重用call_1少计。没有新增SDK字段或第五张表。
- 租户 `GET /ai/usage?month` 使用ai:configuration:manage；只有独立Platform Realm + platform:ops:manage能校正未知估计，保留unknown状态及原值/新值/原因/tenant/model/actor审计元数据。普通租户管理员不能归零估计绕配额。
- 平台复用forge-tenant接收同名公开record JSON，以tenant/day/version替换投影；检查CurrentTenant==payload、真实PlatformTenant、zone与计数。HTTP `GET /platform/tenants/{id}/ai-usage` 复用tenant:read并先调用TenantService.get实施真实DataPolicy，区域外404、缺权限403。
- 运营UI落在现有租户详情抽屉，月份只读查询，区分已知、估计、尚未上报、查询失败；只读运营可见，切换租户/关闭后丢弃旧响应。未增加导航或计费规则。
- 平台TenantAiUsageCleaner经公开TenantDataCleaner处理最终删除，显式租户条件逻辑删除，幂等且不影响其他租户。接收与最终删除共用真实租户行锁，最终删除后晚到旧快照不重建投影。AI四表清理留给11，此平台子项已完成。
- 审计复用04的会话开始/结束/模型切换、07/08/09发布/回滚与09工具审计，全部只记元数据。Manifest8的ai-user为会话使用+MCP读取，ai-admin14个权限，与页面一致；真实IAM预置及Plan交集均验证。

### 验证证据

按已确认的TDD接缝（真实HTTP/事件/IAM权限审计与用量）验证，未mock IAM授权，也未伪造Redis grants。A/B通过真实Outbox声明legacy不限；独立D租户由真实Platform Plan创建、分配与重新保存API产生配额事件。实际平台租户与Operator数据范围夹具齐备。

- AI常规统一脚本service：10/10，Architecture、SchemaComment、Spotless与permission-catalog通过。日志 `/private/tmp/usage-final-service-after37.log`。
- AI完整真实PlatformIntegrationIT：37/37，0跳过，含09全部31项与10六项。日志 `/private/tmp/usage-ai-platform-final37.log`。真实RocketMQ到新PlatformJar到Gateway查询，不以直接调Handler冒充MQ。
- 工具计数/恢复红灯先捕获运行中0（期望1）及过期恢复0（期望2）；修复后连同Plan身份目标3/3通过。日志 `/private/tmp/usage-tool-red.log`、`/private/tmp/usage-tool-green.log`。
- 平台完整清洁verify/install：68/68；Vue平台控制台全量104/104；build、样式和Prettier通过。日志 `/private/tmp/usage-platform-clean-verify.log`、`/private/tmp/usage-platform-ui-all.log`。
- Playwright7/7；1440x900、1024x900的有数据/unknown、未回报、错误与既有详情共七张PNG逐张查看并提交。Browser plugin not available，使用仓库Playwright；日志 `/private/tmp/usage-visual-final7.log`。

隔离Maven目录 `/private/tmp/ai-usage-forge-verify/m2`：先复制第三方缓存并排除io/saasforge，再从平台实现分支编译SDK及IAM/Platform/Gateway运行包。未覆盖~/.m2，未发布snapshot。运行实际SHA256与构建记录一致：

- forge-iam：`a17c6b250f4081beef51a9fa36b8c27fcc75b18f9ca82e0e23287358b9a67ade`
- forge-platform：`aae999ecda76a1b9df28867d5f057cfcdfa0ebbfca5f49cf4761c7011c75113a`
- forge-gateway：`e6f59ae28080fddf65e7578904cdfc9e1c6e7805f55b8c1e9756d0b40faf7d30`

完整证据 `/private/tmp/ai-usage-forge-verify/build-evidence.json`；运行日志与SHA位于 `ai-service/target/platform-it`，后续service门禁不覆盖此目录。

### 后续接缝与边界

[用量契约](../../../docs/usage-api.md)、[部署说明](../../../deploy/README.md) 已明确bootstrap：同Plan重新分配不广播，现有套餐保留内容重新保存会广播；未分配历史租户须运营显式分配合适套餐，不由AI推断不限。未操作共享环境。

11可直接使用 `UsageService.currentPlan(): Optional<UsagePlanSnapshot>`，无可信事件为empty，缺省身份字段为空、不改变quotaKnown；按planCode读取部署级留存策略，默认90天与tenant覆盖由11实现。自动重试必须提供每次运行的attempt命名空间。真实外部付费模型、共享Nacos/生产部署不在本次本地验收内；AI HTTP主链路使用已约定的确定性AgentRuntime，真实DSH升级契约由03/09独立覆盖。
