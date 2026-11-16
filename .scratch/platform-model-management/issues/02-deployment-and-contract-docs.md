# 02: 模型凭据的部署与契约文档

**What to build:** 把主密钥接进部署、本地与 CI，并让接口契约文档与实现对齐。

**Blocked by:** 01

**Status:** resolved

- [x] `deploy/kubernetes/secrets.example.yaml` 增加 `AI_MODEL_CREDENTIAL_KEY: REPLACE_MODEL_CREDENTIAL_KEY`
- [x] 复核 `docs/deployment.md` 的配置表与轮换说明，确保与实现一致
- [x] 本地 `ai-service/.env.example` 增加主密钥示例；`PlatformIntegrationIT` / `FullStackIT` 用动态属性注入确定性测试值，不依赖真实凭据
- [x] 删除 `docs/model-api.md` 顶部的“目标契约”提示，确认描述与实现一致
- [x] 执行 `cd ai-service && ./mvnw -o -s .mvn/settings.xml install -DskipTests`（仓库根目录没有 Maven wrapper），跑 `PlatformIntegrationIT` 与 `FullStackIT`

## Comments

## Answer

2026-11-16 在受管工作树 `/Users/liuhuan/.codex/worktrees/ai-model-deployment/ai-platform`、分支 `codex/model-credential-deployment` 完成。基线为集成分支 `3234f48`；交付前执行 `git merge --no-edit codex/platform-model-management`，返回 Already up to date。原始 AI 仓库中的未提交设计修改保持原样。

- Kubernetes Secret 样例增加主密钥占位；本地 `.env.example`、常规服务 CI 和完整全栈 CI 提供相同确定性测试主密钥。工单 01 的主配置、测试配置和两类集成测试动态注入继续沿用，均不依赖真实供应商凭据。
- `docs/model-api.md` 移除未实现提示；部署文档区分引用型供应商 Key、直接密钥与加密主密钥的更新方式，明确主密钥独立保管、丢失后重录、v1 不轮换。ADR 0005 改为表单仅写入，HTTP 响应、Runner 上下文、审计与日志不返回原始密钥。
- Spec 迁移编号修正为实际新增的 V12；验收命令改为从 `ai-service` 执行，本仓库没有根 Maven wrapper。`docs/ci.md` 明确完整 CI 的 40 位 `forge_ref` 必须包含 AI 模型控制台页面，本地证据不能代替远端 CI。
- FullStack 构建真实平台控制台与 Tenant Shell。控制台在独立随机 IPv4 端口 preview，经真实 Gateway 与 IAM Operator 会话执行新增 API Key、读取脱敏、留空保留编辑、确认禁用，未使用 `page.route`。新模型最终为 `DISABLED`，原租户用例的模型选择、供应商计数、用量与权限预期均未改动。
- 首轮真实浏览器实证发现：开关测试 ID 位于 Element Plus 外层，勾选断言已改到内部可访问开关；用户既有 IPv6 5174 服务使 localhost 登录落到旧服务并返回 502。沿平台现有测试约定，将本次测试 Chromium 的 localhost 解析固定到 IPv4，并仅把独立跨租户 APIRequestContext 请求及 readiness 指向本次 IPv4 preview；保留 IAM 已登记回调和用户 5173/5174 服务。截图禁用动画后已查看新增、禁用确认、禁用后三态。

### 验收

被测代码提交为 `56f5bf9e48c7cd71c97efa1bed49c6c0092d1bee`（包含候选实现 `1bbfa15` 和浏览器定位修正 `f189179`）；前端为 `4831e933221a57eccd470adb7995ac60701eebf0`，均由脚本在运行时写入 `source.json`。本次验收记录提交只修改工单与 Spec。

```bash
cd ai-service
./mvnw -o -s .mvn/settings.xml spotless:apply install -DskipTests
AI_FORGE_RUNTIME_REPOSITORY=/Users/liuhuan/.m2/repository \
  ./mvnw -o -s .mvn/settings.xml test -Dtest=PlatformIntegrationIT
cd ..
AI_MAVEN_OFFLINE=true \
AI_FORGE_RUNTIME_REPOSITORY=/Users/liuhuan/.m2/repository \
AI_FORGE_SOURCE=/Users/liuhuan/workspace/coding/saas-forge \
  bash scripts/check-fullstack.sh
```

- `PlatformIntegrationIT`：54/54，0 失败、0 跳过，包含直接密钥/引用、轮换/保留、迁移、权限与审计回归。
- `FullStackIT`：2/2，0 失败、0 跳过。Playwright：3/3（控制台 1440，租户端 1440/1024），34.5 秒；页面地址、标题、非空内容、无框架错误遮罩、控制台健康与交互均通过。
- 控制台真实响应：新增 201、编辑 200、禁用 200；读取与列表为 `credentialKind=API_KEY`，不含 `apiKey/secretRef/credentialCipher`；编辑和禁用请求均省略两项凭据字段，最终为 `DISABLED`；浏览器 `consoleErrors=[]`。
- 原真实 Shell 第一段供应商调用 6、MCP 调用 2、流片段 8 的断言保持通过；公开用量为 54 token、2 次工具调用。后续零用量、缺失用量、部分缺失回归继续通过，最终供应商总调用 10、MCP 3、流片段 14，授权校验为 true。
- 服务安装、Java 格式、Playwright 相关文件格式、Shell 语法与 `git diff --check` 通过。全栈运行日志扫描四类确定性密钥/引用标记，命中 0。生产服务代码未在工单 02 改动，不重复工单 01 已通过的完整 verify。
- 证据原始目录：`ai-service/target/fullstack`；完整 Maven 输出：`ai-service/target/ai-model02-fullstack.log`。仓库外交付目录：`/Users/liuhuan/.codex/visualizations/2026/11/16/01a1e2fa-176d-7701-bd1c-a1f527c05fd5/model-management/backend02`，含截图、浏览器/HTTP 证据、用量、指标、运行包 SHA、测试报告与 `SHA256SUMS`。未复制临时 Operator 会话、TLS 私钥或 truststore。

此次为本地真实平台链路验收，外部模型/MCP 在本地 HTTPS 边界确定性响应；没有调用真实供应商、推送、触发远端 CI 或部署生产。
