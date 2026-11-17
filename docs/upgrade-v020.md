# Forge v0.2.0 候选升级

本次实际基线为 `c5cc07fdf396bc9098785edc5fd96d77a5f6ca6c`，当时工作区干净，后端父 POM 与前端 Forge 依赖已使用 `0.2.0-SNAPSHOT`，并非从已发布 `0.1.0` 升级。第一阶段消费平台 `54115992e00691be57b912e46593709c77c344a1` 的快照候选；最终阶段已改为平台 `9f61c8a0073c82a900abeff45ce21c5600b6afb3` 实际打包的 `0.2.0` 候选。精确输入见 [candidate.json](../.forge-packages/candidate.json)。两个阶段的候选均未发布到目标制品仓库。

## 最终坐标与改动

- 后端仅将 Forge 父版本改为 `0.2.0`。前端 SDK、UI、kit 三包对齐 `0.2.0`，导入最终候选的真实原始 tgz，更新 file override 与完整性锁记录；Runner importer、所有非 Forge 包与解析记录保留原文。
- 从最终候选实际生成的参考工程复制两份规范，合入 Agent 升级提示；业务服务仍为 `0.1.0-SNAPSHOT`、Sub-App 仍为 `0.1.0`、Manifest 仍为 `8`。
- 平台验证脚本与测试夹具的运行包路径改为 `0.2.0`。支持可选 `AI_MAVEN_SETTINGS`，复用候选缓存时沿用候选的原 settings 和仓库 ID；默认仍使用工程 `.mvn/settings.xml`，文件缺失时明确失败。
- 移除两个 CI job 的全局测试主密钥注入。该注入使“未配置主密钥必须启动失败”的负例失效；常规 Spring 配置及真实平台/全栈用例已有自己的隔离测试密钥，生产配置和测试期待均未改动。
- 保留 Vite 端口 `5180` 及显式传入的可选 `FORGE_DEV_GATEWAY`。业务主源码、后端配置、九条历史迁移保持原文，没有新增 IO/storage 依赖、表或配置，没有操作共享服务或业务数据库。

最终候选清单 SHA-256 为 `ceed5ff8789ffd51f5727e3861c63f2ae90c4750c7cbc387a991b979d98adc94`，全部 249 个制品摘要已核对。与快照候选相比，相关源码差异仅为版本元数据、SDK 的版本标识和规范/生成 Agent 提示中的升级指针；UI 与 kit 宿主没有功能变化。三包实际安装的 77 个文件逐字节匹配新 tgz，六项共享依赖与最终参考工程精确一致。

## 最终坐标验收

最终完整验收的 AI 源码为 `17a934e8ad8a9ad6c727f2a234ddd35466de0d33`，之后仅追加本验收文档。配套 IAM、Platform、Gateway 与 Shell/平台控制台源码均来自最终候选同一平台提交 `9f61c8a0073c82a900abeff45ce21c5600b6afb3`；三个实际执行的运行包 SHA 与固定分发一致。业务 Java 缓存仅由候选消费入口建立，没有被平台源码 install 覆盖。

| 验证 | 最终 `0.2.0` 结果 |
| --- | --- |
| Java 候选消费 | `verify` 19/19、权限目录通过；29 个实际解析的 Forge JAR/POM 全部匹配候选；可执行业务 JAR 内 12 个 Forge lib 同时匹配候选和解析缓存 |
| Sub-App | 14/14、格式、隔离、样式、类型检查和构建通过；锁范围修正后再次冻结安装，固定源码重新执行 14/14 与格式检查 |
| Scheduling | 三个 retention 回归 3/3；涵盖到期内容、遗留活跃标记、停用/过期租户及计划/租户保留期优先级，并观察到实际 TaskRunner 执行 |
| 完整全栈 | Maven 3/3，真实 Shell/平台控制台浏览器 3/3，真实独立宿主 1440/1024 两宽 2/2；真实登录、会话 HTTP、租户隔离、主题及相关控制台错误检查通过 |
| 视觉 | 沿用既有 30 张 PNG，比较 26/26，无基线更新；最终全栈 11 张原始截图逐张查看，独立宿主终态无弹窗关闭过渡残影 |
| 保留约束 | 原基线 187 个业务主源码/配置文件 SHA 不变，九条历史迁移逐字节保留；业务版本与 Manifest 不变 |

复现入口见 [构建与验收](ci.md#固定未发布候选验收)。最终证据在 `/private/tmp/v020-upgrade/08-ai-final/`：`java-summary.json`、`java/resolved-forge.json`、`boot-classpath.json`、`npm-resolved.json`、`lock-scope-audit.json`、`retention-summary.json`、`fullstack-summary.json`、`browser-summary.json`、`runtime-execution-audit.json`、`screenshot-review.json`、`visual-preservation.json` 和完整执行日志。`fullstack/source.json` 记录实际验收提交，安全汇总与复现环境见该目录的 `receipt.md` / `receipt.json`。

本轮保留两类已解决红灯：全局测试密钥干扰缺失密钥负例，以及离线构建从 `forge-candidate` 缓存切换到 `forge-release` 时的来源 ID 不匹配。对应原日志保留；没有修改 `_remote.repositories` 或用源码安装替代候选。pnpm 刷新时的无关 Runner peer 重绑也已恢复，并由冻结安装及逐块保留核对确认。

## 快照阶段证据与边界

快照阶段全部原始证据仍保存在 `/private/tmp/v020-upgrade/07-ai/`，没有被最终坐标结果覆盖：

| 验证 | `54115992` / `0.2.0-SNAPSHOT` 阶段结果 |
| --- | --- |
| Java | 19/19；58 个含快照别名的 Forge JAR/POM 与业务 JAR 的 12 个 Forge lib 摘要匹配 |
| 平台产品 HTTP/MQ | `PlatformIntegrationIT` 56/56，含三个 retention 行为与实际调度器执行 |
| Runner / Sub-App | 30/30 与 14/14；相关格式与构建通过 |
| 完整全栈 | Maven 3/3、Shell/控制台浏览器 3/3、独立宿主两宽 2/2 |
| 视觉 | 30 张 PNG 按统一主题更新并逐张查看，更新与最终比较均 26/26 |

快照阶段完整全栈源码为 `4a010916e8e644541a4307edf6e15b23bd8e862a`。最终候选的差异判断见 `08-ai-final/final-source-audit.json` 与 `npm-candidate-diff.json`；既有完整 56 项产品测试和 30 项 Runner 契约作为该阶段证据保留，本轮按受影响范围复验，不将这些计数写成最终坐标下重新执行。

原开发库此前仅以只读事务核对 Flyway：V1、V4、V6—V12 共九条均成功，V12 已执行；本轮未再访问或迁移该库。隔离环境按相同历史迁移到 V12。平台链路使用临时 PostgreSQL、Redis、RocketMQ 与真实 IAM/Gateway/AI/Runner/DSH；模型与外部 MCP 仅在既有本地确定性 HTTPS 边界替代，没有供应商计费外呼。

本次完成的是最终 `0.2.0` 候选的本地升级验收。没有发布制品、推送分支、创建正式标签、切换环境或执行远端 CI；目标 Maven/npm 仓库的只读消费复验留待版本发布后执行。
