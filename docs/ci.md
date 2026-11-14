# 构建与跨仓验收

Java 使用已发布的 `io.saasforge:*:0.2.0-SNAPSHOT`，ai-service/pom.xml 继承 Forge BOM。CI 不检出平台源码、不运行平台 reactor install。

`AI platform integration` 在 main 推送、PR、手动触发及每日北京时间 10:45（UTC 02:45）运行。service、runner、sub-app 三个 job 分别验证同一提交；统一入口为 `bash scripts/check.sh`，可指定单个子工程。

## 已发布 SDK

service job 的 `scripts/verify-published-sdk.sh` 创建临时 Maven 缓存，只复用第三方依赖，排除全部 io.saasforge 制品；以 -U 从 GitHub Maven Packages 解析快照后运行 service 的完整 verify。失败不会退回本地 SDK 或源码安装。GITHUB_TOKEN 仅在该 job 以 packages:read 读取公开平台包。

`PlatformSnapshotContractTest` 验证 AI Manifest、角色模板、Sub-App 编码，以及 TenantPlanChanged 的 tokenQuota 和历史 JSON 兼容。`HttpContractTest` 验证健康检查、401 信封和 OpenAPI；SchemaCommentTest 验证 SDK 必需表、独立 ai schema 与受限双账号；ArchitectureTest 检查共享架构规则。

工单 02 另提供 `bash scripts/check.sh platform`：通过 `AI_FORGE_RUNTIME_REPOSITORY` 指定真实平台运行 jar 的 Maven 布局目录，启动隔离 PostgreSQL/Redis/RocketMQ 和 IAM/Platform/Gateway，验证登录、权限、跨租户、审计及 App Registry 同步。此入口不包含在常规 `all` 或远端 SDK job 中；平台应用 jar 未发布到 Maven Packages。运行方式与验证边界见 [平台接入](../deploy/README.md#隔离平台验收)。测试类为 `PlatformIntegrationIT`，缺少依赖时直接失败。

本地在线验证设置 FORGE_MAVEN_USERNAME 与 FORGE_MAVEN_TOKEN（读取 Maven 包的凭据），然后运行 `bash scripts/verify-published-sdk.sh`。settings.xml 只引用环境变量，不保存明文凭据。普通离线迭代可在已有匹配 SDK 缓存时使用 `AI_MAVEN_OFFLINE=true bash scripts/check.sh service`；离线结果不作为跨仓发布验收证据。

平台每日 10:00 的 SDK 发布由 saas-forge 仓库执行，本仓库 10:45 消费当时可读的最新快照。一次运行的下载日志、代码提交和测试报告是对应证据；定时配置不是未来每天一定成功的承诺。

## Node 与 Vue

runner job 运行 TypeScript 类型检查、编译和真实 HTTP 存活测试，不连接模型。sub-app job 校验隔离、样式、类型、构建及 Playwright 页面行为。Linux CI 跳过 macOS 像素基线比对，交互断言继续运行；本机生成并查看 1440/1024 两档截图后提交基线。

前端三个 0.2.0-SNAPSHOT tarball 固定在 `.forge-packages/`，来源与升级方式见该目录 README；pnpm-lock.yaml 固定第三方依赖。这些文件用于前端配套版本，Java 的已发布快照仍必须通过远端 Maven 仓库验收。

## 首轮验收

源码提交 `c36f02f` 的[远端运行](https://github.com/crane199709/ai-platform/actions/runs/43558951586)中，三套检查全部通过。Java已从发布仓库下载实际快照并通过5项测试和Manifest动作策略校验，Runner的HTTP测试与Vue的两档页面用例也通过。这是工单00的跨仓消费证据；不代表后续工单的实际业务平台接入已完成。
