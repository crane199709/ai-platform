# Forge v0.2.0 候选升级

本次基线为 `c5cc07fdf396bc9098785edc5fd96d77a5f6ca6c`，工作区干净。后端父 POM及前端 Forge 依赖已为 `0.2.0-SNAPSHOT`；前端由 `.forge-packages` 本地原包替代，之前说明来源为 `5a6fd0fa`。本次消费的是固定提交 `54115992e00691be57b912e46593709c77c344a1` 的未发布候选，输入摘要见 [candidate.json](../.forge-packages/candidate.json)，不能将其描述为已发布 `0.2.0`。

## 实际差异

- SDK npm 原包与候选相同；替换 UI 与 kit 原包，刷新同名 `file:` 包的锁记录，并逐文件核对实际安装内容。
- 从候选实际生成工程同步前端规范、补入主题接入说明；业务版本 `ai-service:0.1.0-SNAPSHOT`、Sub-App `0.1.0` 与 Manifest `8` 保持原值。
- Vite 配置显式将 `FORGE_DEV_GATEWAY` 传给 kit 的 `gateway`，使独立宿主指向本轮隔离网关；未设置时保留 kit 默认。真实登录红灯暴露了仅传环境变量不会自动改代理目标的问题。
- 业务主源码、后端环境配置和迁移未改写。原开发库仅只读核对 Flyway 记录：V1、V4、V6—V12 共九条均成功，V12 已执行。隔离环境依同一历史迁移到 V12，SDK 表与注释检查通过。本次无新增表结构要求，未引入 IO/storage。
- 新增固定候选 Java 验收入口，隔离本机 Forge 缓存并记录实际解析制品摘要。平台运行包另存；完整 Shell 入口没有用 Java 源码 install 覆盖候选。
- 复用原产品全栈接缝，并增加 Shell 结束后同源启动独立开发宿主的真实登录、会话 HTTP 与主题验收；主题变化对应的视觉基线单独走查。

## 验收入口与边界

候选消费命令见 [构建与验收](ci.md#固定未发布候选验收)。平台 IAM、Platform、Gateway 使用与候选源码一致的隔离运行 JAR；Shell 使用配套源码。真实平台测试使用临时 PostgreSQL、Redis、RocketMQ；独立宿主与 Shell 使用真实 IAM 登录和网关/AI HTTP。Runner 契约使用真实 DSH；完整产品链路的模型与外部 MCP 仅在本地确定性 HTTPS 边界替代，没有真实供应商计费外呼。

候选 Java `verify` 19/19、平台产品 `PlatformIntegrationIT` 56/56、Runner 30/30、Sub-App 14/14 与构建已通过。浏览器补充验收及主题视觉基线正在复验；全部结果与日志将在此记录收敛后交付。未运行目标制品仓库发布、生产/共享环境切换或远端 CI；当前 CI 使用目标 Maven 快照，不能替代本地固定候选证据。
