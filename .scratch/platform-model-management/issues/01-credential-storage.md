# 01: 模型凭据双来源与加密存储

**What to build:** 按 [spec](../spec.md) 让模型目录同时支持直接密钥（加密存储）与部署引用，并让编辑不再强制重填凭据。

**Blocked by:** —

**Status:** resolved

- [x] `V12` 迁移：新增 `credential_kind`、`credential_cipher`，放开 `secret_ref` 非空约束，加 CHECK 与全部新字段的中文注释
- [x] `ModelCredentialCipher`：AES-256-GCM，`v1:` + Base64(nonce12 ‖ 密文 ‖ tag)，AAD 为模型目录 ID；主密钥从 `AI_MODEL_CREDENTIAL_KEY` 读取并在启动时校验（Base64 32 字节，缺失即启动失败）
- [x] `ModelRequest` 增加 `apiKey` 并与 `secretRef` 互斥；`POST` 必须给一个，`PUT` 可以都不给
- [x] `ModelResponse` 增加 `credentialKind`；响应、审计、日志与 `toString` 都不出现 `apiKey`、密文和引用
- [x] `ModelCatalogService` 写入语义：新增时加密，编辑时缺省保留、给了替换，引用型改成直接密钥时清空 `secret_ref`
- [x] `ModelRuntimeConfiguration` 携带凭据类型与密文；`RunnerModelProxyService` 按类型解析，任何失败统一 503 `runner.secretUnavailable`
- [x] `PlatformIntegrationIT` 覆盖 spec 的用例清单，包括迁移后存量引用型模型仍能完成回合

## Comments

## Answer

2026-11-16 在 `codex/model-credential-storage` 完成后端凭据双来源。工作树为 `/Users/liuhuan/.codex/worktrees/ai-model-credential-storage/ai-platform`，基线 `9a88d85`；交付前执行 `git merge --no-edit codex/platform-model-management`，最新集成 tip 仍为 `9a88d85`，返回 Already up to date。

- 现有迁移已占用 `V10__usage_quota.sql` 与 `V11__hardening.sql`，经协调确认新增 `V12__model_credentials.sql`，没有重写历史迁移。V12 回填旧行为 `REFERENCE`，新增来源/密文字段、互斥 CHECK 与中文字段注释，并允许直接密钥行的引用为空。
- `ModelCredentialCipher` 用 AES-256-GCM、随机 12 字节 nonce 和目录 ID 的 UTF-8 字符串 AAD；持久化格式为 `v1:` 加 Base64(nonce、密文、tag)。主密钥用配置 record 绑定 `AI_MODEL_CREDENTIAL_KEY`，缺失、非法 Base64 或非 32 字节都会在启动绑定时失败；配置对象和三个凭据持有对象的 `toString` 固定脱敏。
- 创建凭据必须二选一，更新可省略凭据；更新保留、替换、引用与直接密钥双向转换都沿用原 HTTP 接口。API Key 只接受 1–8192 个 ASCII 可见字符，响应新增 `credentialKind` 并继续隐藏引用、Key 和密文。实体两项凭据字段显式允许写 NULL，来源切换通过数据库 CHECK 的验证。
- Runner 只在供应商请求前取得当前凭据：引用继续经过允许列表与 Environment，直接密钥解密。每次请求尝试重新解析，不缓存明文或把凭据传给 Runner 上下文。凭据空值、未允许引用、错误主密钥和 GCM 失败统一转换为无原始 cause 的 `runner.secretUnavailable`（503）。
- Platform 权限链与原审计注解保持原契约；成功创建/更新和失败更新的真实 IAM 审计均验证 `detail={}`，不包含凭据。主配置、测试配置、PlatformIntegrationIT/FullStackIT 动态注入已适配必配主密钥，其余部署/本地/CI 入口和页面联调由工单 02 完成。

### 验证证据

测试接缝为已确认的 HTTP 契约和加密组件公开 `encrypt/decrypt`；没有 mock 内部服务或绕过真实平台鉴权。红灯先后观察到加密组件不存在、直接 Key 创建返回 400、旧 Runner 对直接 Key 返回 500，再逐切片转绿。

```bash
cd ai-service
AI_FORGE_RUNTIME_REPOSITORY=/Users/liuhuan/.m2/repository \
  ./mvnw -o -s .mvn/settings.xml test -Dtest=PlatformIntegrationIT
./mvnw -o -s .mvn/settings.xml verify
```

- 完整 `PlatformIntegrationIT`：**54/54 通过，0 跳过**，日志 `/private/tmp/ai-model01-platform-final.log`。覆盖创建与列表/读取脱敏、凭据省略后保留、下一回合轮换、双向来源转换、两来源互斥与缺失、非法字符/超长、错误主密钥与 GCM 认证失败/空引用值的相同 503、真实 Platform/Tenant 授权、成功/失败审计与既有业务回归。
- 专用 `V11_1__legacy_model_reference_fixture.sql` 在 V12 之前插入旧版引用目录行；迁移顺序和 HTTP 的 `credentialKind=REFERENCE`、实际模型回合成功共同验证旧行兼容，没有从环境变量回写数据库。
- 完整 `verify`：**19/19 通过，0 跳过**，日志 `/private/tmp/ai-model01-service-verify.log`。其中加密/配置/脱敏公开行为 6 项；同时通过架构、Schema/SDK 迁移注释、HTTP 基础契约、编译打包和 Spotless。
- 权限目录策略校验与 `git diff --check` 均通过。最终平台日志扫描未发现直接 Key、轮换 Key、错误主密钥夹具明文、引用名与非法 Key 测试标记。
- 对本次 PostgreSQL Testcontainer 做单独只读落库实证，仅输出数量与布尔值：`API_KEY` 行的引用为空，密文带 `v1:` 且不等于测试明文，结果 `1|t`；证据 `/private/tmp/ai-model01-storage-evidence.txt`。HTTP 行为测试没有用查库代替公开契约断言。

### 限制

未访问真实供应商或计费 API，模型转发使用本地 ProviderTransport 桩；没有修改生产、现有运行数据库或原始后端工作树。本工单未运行 FullStackIT 的浏览器链路，仅完成其必配属性适配；真实控制台联调与部署入口验收仍由工单 02 完成。主密钥保管和遗失后重录约束沿用 ADR-0005，v1 不提供轮换或恢复功能。

### 最终 review 修复（2026-11-16）

代码提交 `752c26a42294dcf4d2e83c28c8ac7387186cb47e` 修复重试期间目录配置混用：每次尝试在权限、启用状态复验后，以同次解析的完整配置检查上下文窗口、构造供应商 endpoint 和 model，并取得凭据。瞬时 HTTP 状态、三次尝试上限与已打开响应流不重放的行为保持原契约。`ModelCredentialCipherTest` 的 Base64/List 改为显式 import 与简单类名。

两条新增回归从真实平台与 Runner HTTP 接口触发，供应商使用外部 `ProviderTransport` 适配器桩，没有替换模型选择或加密服务。首个 503 响应返回前，经真实平台 PUT 同步更换地址、模型 ID 和直接 Key；断言旧地址只收到旧 Key，第二次调用同时使用新地址、新模型和新 Key。另一条把目录窗口缩为 128，在输出 100 加输入消息超出新窗口时返回 400，供应商只收到一次请求。

- 配置切换 Red：1/1 失败，第二次仍请求旧地址；随后 Green：1/1 通过。
- 窗口缩小 Red：1/1 失败，预期 400、实际 200；最终两个重试回归 Green：2/2 通过。
- 完整 `PlatformIntegrationIT`：56/56，0 失败、0 跳过；`AI_MAVEN_OFFLINE=true bash scripts/check.sh service`：verify 19/19，架构、迁移注释、Spotless 与权限目录门禁均通过。
- 完整 FullStackIT 与三项浏览器回归通过，证据与命令见工单 02 的最终 review 验收。仓库外证据目录为 `/Users/liuhuan/.codex/visualizations/2026/11/16/01a1e2fa-176d-7701-bd1c-a1f527c05fd5/model-management/review-fixes`，包含两轮 Red/Green 日志、完整平台集成报告与服务 verify 日志。
