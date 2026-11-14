# 部署、升级与故障排查

AI Platform 是 SaaS Forge 之外的产品。AI 服务只依赖公开 SDK；部署需要配套 IAM、Platform、Gateway、PostgreSQL、Redis、RocketMQ 与 Nacos，以及已发布的 Tenant Shell。HTTP 边界见 [Runner](runner-contract.md)、[会话](conversation-api.md)、[MCP](mcp-api.md) 和 [用量](usage-api.md)。

## v1 拓扑和资源

[可渲染的 Kustomize 配置](../deploy/kubernetes/kustomization.yaml) 使用 AI **1 副本**、Runner **1 副本**、`Recreate`。能力令牌、原用户授权绑定、敏感工具审批和 SSE Job 在 AI 内存中；租户进程池在 Runner 内存中。不能直接加副本并随机负载均衡。扩容须先实现按回合/租户精确路由或安全的共享所有权，并重新验收取消、审批、恢复与池上限。Java DB transcript 是权威；升级会中断活跃回合，保留已提交历史，用户可重新发送。

[ADR0002](adr/0002-conversation-transcript-authority.md)的JSONL缓存/回合导出描述是设计意图。固定官方SDK没有历史export/import接口；实际适配禁用`session-persistence-jsonl`与projection缓存，透明流获取实时delta、公开DSH提交事件形成逻辑transcript，Java持续保存产品历史。健康DSH进程跨回合复用，不在每回合结束kill；进程丢失、取消或配置改变后，以DB完整快照通过公开prompt上下文重放恢复，历史工具不再次执行。Runner HTTP的export是适配器逻辑快照，不冒充上游SDK导出/JSONL导入。

Runner 每租户独立 DSH 子进程、0700 home/workspace、0600 profile，默认最多8个租户进程、空闲15分钟回收、每回合120秒。子进程 Node old-space 256MiB、semi-space 8MiB、FD128、累计CPU120秒。**V8 heap限额不是RSS上限，累计CPU不是每回合CPU预算，各租户进程并非独立容器/OS用户沙箱。** Runner容器统一限制2CPU/3GiB RSS与1GiB临时运行卷；AI限制2CPU/1536MiB。池大小和容器总额须按实测RSS留余量调整，OOM后从DB恢复，不能承诺8个进程各自得到完整堆预算。

Runner进程不继承模型/MCP密钥、用户JWT或用户home。只读官方profile禁用shell/FS/Web/插件安装等执行面，允许已审核Skill和AI代理工具；不是允许执行任意模型生成命令的通用沙箱。容器以非root、只读根文件系统、drop ALL、RuntimeDefault seccomp运行，home位于临时卷。

## 配置与凭据

完整环境项见 [AI示例](../ai-service/.env.example)、[Runner示例](../runner/.env.example) 和 [K8s配置](../deploy/kubernetes/config.yaml)。地址、issuer、schema、业务时区和凭证必须来自目标环境，`example.invalid`与`REPLACE_*`不可直接部署。

| 配置 | 作用与边界 |
| --- | --- |
| `AI_RUNNER_BASE_URI` / `AI_RUNNER_SERVICE_TOKEN` | AI到固定Runner内网HTTP；令牌与Runner一致，不能复用Manifest/Ops/模型Key |
| `AI_SERVICE_PROXY_URL` | Runner固定AI `/internal/runner` 地址；请求不能改URL |
| `AI_MODEL_SECRET_REFERENCES` | AI允许解引用的环境变量名列表；目录DB只保存引用，原始Key仅AI可见 |
| `AI_MCP_SECRET_REFERENCES` | AI允许的MCP凭据变量列表；服务端按 `AI_MCP_<TENANT_UUID无连字符大写>_<CATALOG_UUID无连字符大写>` 派生命名空间，租户不能任填引用 |
| `AI_MCP_TRUSTED_BUSINESS_ORIGINS` | 允许转发业务身份的准确HTTPS origin；外部MCP不接收用户JWT |
| `FORGE_MANIFEST_TOKEN` | Operator登记的ai-service Service Credential，只用于公开平台服务协议 |
| `FORGE_OPS_TOKEN` | AI实例健康接入状态的独立运维凭证；Platform Ops映射同值 |
| `FORGE_BUSINESS_ZONE` | 与平台一致的显式时区，决定月配额/日快照；不能依赖主机默认时区 |

[Secret样例](../deploy/kubernetes/secrets.example.yaml) 不参与kustomization，不含真实凭据。用环境的Secret/Vault控制器创建 `ai-service-secrets` 与 `ai-runner-identity`；不要把全部AI Secret通过共同 `envFrom` 注入Runner。Runner只有单独的服务身份Secret。模型/MCPKey轮换先更新AI Secret，重建AI实例后做本地/预发布回合验证，再撤销旧Key；不能把Key写进目录、前端、日志或镜像。

## 数据库与平台接入

操作目标共享环境前先确认数据库/命名空间与变更窗口。以下是管理员步骤，本仓库脚本不会自动执行共享环境操作。

1. 管理员在forge库安装 `extensions.ltree` 和 `extensions.pg_trgm`，再用[独立账号脚本](../deploy/postgres/lib/provision-service.sql)准备ai schema、ai_migrator与ai_app。显式执行 `CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA extensions;`，运行账号没有安装扩展权限。JDBC `currentSchema=ai,extensions`。迁移账号持有schema，运行账号不持表且没有BYPASSRLS。V11 trigram索引要求扩展预先存在。
2. AI启动自动执行Flyway。升级先备份并验证恢复；不要改已执行迁移，不用`flyway repair`掩盖checksum错误。旧版本代码回滚前检查新schema向后兼容；删除列或不可逆数据迁移只能走另行审核的恢复方案。
3. 登记Service Credential、独立Ops映射与Provisioning服务列表，合并[Gateway片段](../deploy/forge-gateway.yaml)和[Platform片段](../deploy/forge-platform.yaml)，保留已有路由/服务。`/ai/**`用Tenant Realm，`/ai-platform/**`用Platform Realm。模型/MCP/Skill平台目录维护用现有`platform:ops:manage`。
4. 创建`forge-tenant`、`forge-app`、`forge-audit`、`forge-notification` Topic，接入真实MQ、Redis与Nacos。等待 `/internal/onboarding-status` 为`REPORTED`，reportedVersion与当前 `forge.manifest.version` 相同；该端点只接受Ops Token。
5. 发布`ai-sub-app/dist`到浏览器可达静态站点，把[App Registry载荷](../deploy/app-registry.local.json)中的environment/remoteEntry/shared版本改为目标环境并登记。环境名与IAM `forge.iam.sub-app.environment`一致。Shell必须使用配套共享依赖，入口`/apps/ai/home`，浏览器请求`/api/ai/...`。
6. Plan Feature Pack加入AI权限与subApps=`ai`，向实际租户分配Plan，给成员分配`ai-user`/`ai-admin`模板或自定义角色。现有租户按平台流程配置。**SDK角色模板不附带DataPolicy**；需要调用MCP时管理员须在IAM角色中为`ai_mcp_tool`配置合适的all/self/custom范围，否则默认拒绝，普通聊天不受影响。
7. `GET /ai/usage`必须显示 `quotaKnown=true`与真实额度。未知Plan返回503 `usage.planUnavailable`。给同一租户重新分配同一个Plan不会广播；可信补同步方式是通过现有平台API读出Plan完整内容，原样重新保存该Plan，产生新版本事件。不得新增任意bootstrap API或自行猜不限额。

保留策略通过 `ai.retention.default-days`（默认90）、`plan-days.<planCode>`、`tenant-days.<UUID>` 正整数配置，优先级租户>Plan>默认。IAM目录任务覆盖所有状态的租户。租户删除清理产品数据和Runnerhome，审计元数据按平台策略保留。详情见[会话契约](conversation-api.md)。

## 构建与发布

本机或CI先运行三工程、真实平台、真实Shell全栈门禁，再构建镜像：

```bash
pnpm install --frozen-lockfile
bash scripts/check.sh all
AI_FORGE_RUNTIME_REPOSITORY=/path/to/runtime-m2 bash scripts/check.sh platform
AI_FORGE_RUNTIME_REPOSITORY=/path/to/runtime-m2 \
AI_FORGE_SOURCE=/path/to/saas-forge bash scripts/check-fullstack.sh
AI_IMAGE_TAG=v0.1.0-g<commit> bash scripts/build-images.sh
AI_RUNNER_IMAGE=ai-platform/runner:v0.1.0-g<commit> node scripts/check-runner-image.mjs
kubectl kustomize deploy/kubernetes > /tmp/ai-platform.yaml
```

Node固定24.14.1，pnpm12.4.2，DSH固定`0.2.2-rc.1`及根lockfile integrity。镜像脚本以`AI_RUNNER_ARCH=arm64|x64`（默认当前Node架构）生成匹配Linux/musl的pnpm生产包，并固定Docker目标platform；不能把macOS/glibc可选native包直接复制到Alpine。生产镜像只复制生产jar或Runner运行代码，不带test-classes、FakeAgentRuntime、TLS fixture、测试Key或浏览器夹具。`build-images.sh`仅构建本机镜像，不push；Sub-App静态产物随同一版本发布，remoteEntry保持短缓存、带hash资源长期缓存。记录Git SHA、SDK/平台准确ref、DSH版本、镜像digest和前一版本digest，不使用`latest`。

Kustomize先在仓库外overlay替换镜像registry/tag/digest、服务地址、命名空间选择器和pod标签，再由目标环境管理员部署。不要原样apply样例。NetworkPolicy要求CNI实际执行策略：Runner8181只准AI，9091只准observability namespace的Prometheus；Runner egress只准AI8086与集群DNS。AI8086只准Gateway、Platform与Runner，9090只准Prometheus；AI的外部模型/MCP/平台依赖egress由环境出口控制。不存在Runner公网Service或Ingress。探针是kubelet流量，实际CNI/节点策略须在预发布验证。

AI liveness/readiness位于独立9090端口；Runner `/health`只证明监听活着，`/ready`证明固定proxy/root已配置，不代表模型、Plan或MCP均可用。发布验收还需检查Manifest、MQ/Plan同步以及真实会话，不能用探针代替业务验收。

DSH升级先更新固定版本与lockfile，在Runner HTTP契约中验证初始化/流式/工具/取消/恢复/导出/隔离/资源；再跑完整Shell链路和N-1回滚。先升级Runner（需支持Java发送的 `tools`、create-session `replace:true`、单会话DELETE与工具事件`attemptId`协议），再升级AI与Sub-App。单实例不可并行金丝雀；使用隔离租户/独立预发布部署验证。生产变更窗口停止新回合、等待活跃回合结束后Recreate；回滚同样先排空，并恢复先前已验证的兼容镜像与remoteEntry。Runner临时home丢失可由DB恢复；回滚不删除DB、不倒退Flyway、不清MQ。

## 指标与故障排查

合并[Prometheus采集配置](../deploy/prometheus.yaml)和[告警规则](../deploy/alerts.yaml)。AI9090只公开health/Prometheus映射，Runner9091只公开聚合metrics，不提供运行、导出或租户诊断接口；监控不持有用户或Runner服务Token。禁止在Gateway代理管理端口。

| 目标 | 指标/解释 |
| --- | --- |
| 请求量/HTTP错误率 | `http_server_requests_seconds_count`的rate与status；SSE打开后的错误另看turn status |
| 回合终态 | `ai_conversation_turns_total{status="COMPLETED|CANCELLED|ERROR"}` |
| 首token延迟 | `ai_conversation_first_token_seconds_sum / _count`，从接纳回合到第一个非空delta，含Runner准入/重试时间；无token失败不产生样本 |
| 精确token用量 | `ai_conversation_tokens_total{direction="input|output"}`；仅完整提供方usage，缓存是input子集，不重复相加 |
| 进程/池容量 | `ai_runner_processes`, `ai_runner_pool_tenants`, `ai_runner_pool_capacity`, `ai_runner_busy_tenants`，实例级聚合 |

业务账单以持久化用量API/Platform投影为准，指标进程重启会归零且不能代表未知usage的估计占额。指标标签只有固定route/method/status/direction等低基数值，不加tenant/user/conversation、prompt、args、结果、JWT、能力Token或Key。错误日志用traceId关联AI、网关与元数据审计，不打印上游响应正文。

| 症状 | 核查与恢复 |
| --- | --- |
| 401/403 | 区分Tenant/Platform issuer、Service Credential与Ops身份；核对Plan裁剪、角色权限、MCP DataPolicy；不能放宽认证排障 |
| `usage.planUnavailable` 503 | 等可信Plan事件；按上面的Plan原样重新保存流程补同步 |
| `usage.quotaExceeded` 429 | 看用量/估计占额与Plan额度；运营校正必须走Platform授权与审计 |
| `runner.poolFull` 429 | 遵循Retry-After；看池/进程指标和空闲回收，调整经容量验证的池大小 |
| 409 busy | 同租户/同会话活跃回合；等待或取消，不并发重发新幂等键 |
| 首token慢/502/504 | 看Runner版本、process数、容器OOM/CPU与模型连接；DB历史可重建，瞬时失败按有限重试，显式重发用新幂等键 |
| 工具失败/审批过期 | 确认目录已审核发布、租户已发布启用、只读声明、权限范围和HTTPS来源；批准仅对本次调用有效 |
| Platform用量未收到 | 查AI日快照/Outbox、MQ投递与Platform接收版本；不要重复入账或修改权威计量 |
| checksum/pg_trgm失败 | 停止升级，核对已执行迁移和管理员扩展；从备份/兼容版本恢复，禁止repair绕过 |

## 可复现全栈验收

`check-fullstack.sh`要求Docker、JDK17、Node24、pnpm、openssl/keytool、Playwright Chromium、配套平台源码与真实三个可执行jar。`AI_MAVEN_REPOSITORY`可指向隔离依赖库，`AI_MAVEN_OFFLINE=true`用于已缓存环境。缺少运行分发时直接失败，不跳过。

脚本生成短期本地HTTPS证书，仅以测试JVM truststore信任该证书；生产HTTPS/密钥allowlist没有弱化。不读取生产Key、不调用计费端点。真实Tenant Shell经Module Federation加载AI远程模块，测试不拦截页面HTTP、不使用DevHost，也不替换AgentRuntime。真实IAM PKCE、PostgreSQL/Redis/MQ、Gateway、AI、Node DSH与MCP审批/用量贯通；模型和外部MCP仅在本地HTTPS边界确定性响应。

两宽1440/1024覆盖登录→建会话→分批输出→刷新恢复→敏感只读工具等待批准/结果→A/B切租户→删除→无权限不可见；并检查实际HTTP用量与Platform投影、受保护/内网指标。产物默认`ai-service/target/fullstack`，可用`AI_E2E_OUTPUT=/tmp/ai-e2e`放仓库外；含截图、浏览器报告、平台SHA与日志，TLS私钥不应上传报告。已有mock视觉回归另测布局，不能替代该真实链路。

当前跨仓运行基线是本地saas-forge `d7a27a63d6d3a52a724f85bc5a0d5f1a825bcbd5`（产品后端与`0647e0f1e1539f4882ee430a95d09f32f5ef909b`一致），尚未push。不得声称远端CI已验证此本地ref。完整CI入口需在该ref可检出后显式提供准确40位SHA；见[CI说明](ci.md)。本地Kustomize渲染和容器验证不等于已验证目标集群CNI、Nacos发布、实际出口或生产容量。
