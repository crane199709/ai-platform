# AI Platform

[English](README-en.md) · [部署与回滚](docs/deployment.md) · [HTTP 契约](docs/conversation-api.md)

面向租户内部员工的 AI 工作台。基于 SaaS Forge 的身份、权限、租户与审计能力，提供助手会话、Prompt、Skill、受审核的只读 MCP、模型目录和 token 配额。

AI Platform 是独立产品仓库，通过 Forge **公开 SDK 与 HTTP/MQ 契约**接入平台。DSH 只承担 agent runtime，不承担账号、租户、产品历史或授权判断。

## 能力

- 会话创建、重命名、删除、流式回答、取消、历史恢复、搜索与 Markdown/JSON 导出。
- 平台模型目录与租户模型选择；支持 DeepSeek 官方及 OpenAI 兼容 HTTPS 端点。
- Prompt、Skill、MCP 的草稿、发布版本、历史与回滚；会话捕获发布快照。
- 工具经 AI 服务代理，使用真实 IAM RBAC/ABAC；v1 只读，敏感调用需要当前用户批准。
- 月 token 配额、未知用量估计占额、按日累计回传平台和不含正文/密钥的元数据审计。
- 每租户 DSH 进程、私有 home、池容量与空闲回收，运行期丢失后以 Java DB transcript 重建。

不含知识库、写操作工具、Squad 或外部客户助手；后续范围见[规格](.scratch/ai-platform/spec.md)。

## 架构

```text
真实 Tenant Shell + ai-sub-app
         │ /api/ai/**（共享 TenantClient）
      Gateway → Java AI Service → Node Runner → 每租户 DSH 进程
                   │                │
                   ├─ PostgreSQL    └─ 固定 AI 模型/工具代理
                   ├─ IAM/Redis/MQ          │
                   └─ Secret引用        HTTPS 模型 / 审核 MCP
```

浏览器不直连 Runner。用户 JWT、模型 Key 和 MCP 凭据不进入 Runner；产品完整历史保存在 AI DB，DSH 是可替换的执行适配层。v1 的 AI/Runner 各**单副本 Recreate**，内存能力、审批和进程所有权要求固定路由，不能直接加副本随机负载均衡。

| 目录 | 内容 |
| --- | --- |
| `ai-service/` | JDK 17 / Spring Boot，Forge SDK、会话与配置、配额、代理、审计 |
| `runner/` | Node 24 / 严格 TypeScript，固定 DSH `0.2.2-rc.1` 的内网 HTTP/SSE 适配 |
| `ai-sub-app/` | Vue Module Federation 远程模块，复用 Shell 会话、路由与客户端 |
| `deploy/` | 镜像、Kustomize、网络策略、数据库接入与 Prometheus 配置 |
| `docs/` | 术语、ADR、HTTP 契约与运行说明 |

## 本地开发

需要 JDK17、Node **24.14.1**、pnpm **12.4.2**、Docker。完整环境还需配套 Forge IAM/Platform/Gateway、PostgreSQL17、Redis、RocketMQ与Nacos。SDK Maven快照从GitHub Packages消费，凭证仅通过环境注入；前端发布包随仓库固定在`.forge-packages/`。

```bash
pnpm install --frozen-lockfile
cp ai-service/.env.example ai-service/.env
cp runner/.env.example runner/.env
```

按[平台接入](deploy/README.md)准备独立 schema、Service Credential、Gateway、App Registry、Plan与角色。环境变量由终端或部署工具注入，示例文件不会自动加载。

```bash
# 分别在已注入环境变量的终端启动
cd ai-service && ./mvnw spring-boot:run
pnpm --filter @ai-platform/runner build
pnpm --filter @ai-platform/runner start
pnpm --filter @forge/ai-sub-app start:ai
```

把浏览器可达的`remoteEntry.js`登记到App Registry，用配套真实Shell访问`/apps/ai/home`。`pnpm --filter @forge/ai-sub-app dev`的独立宿主用于局部页面开发；真实产品验收使用下方全链路入口。

> [!NOTE]
> 模型/MCP原始密钥只注入AI服务，DB只存允许列表中的引用。未知Plan在收到可信事件前返回503；角色模板不带默认MCP DataPolicy，管理员须在IAM为`ai_mcp_tool`设置合适范围。

## 验证

```bash
bash scripts/check.sh all                  # 三工程、架构/迁移与真实DSH HTTP契约
bash scripts/verify-published-sdk.sh        # 排除本机Forge缓存的发布SDK消费
AI_FORGE_RUNTIME_REPOSITORY=/path/to/m2 bash scripts/check.sh platform
AI_FORGE_RUNTIME_REPOSITORY=/path/to/m2 \
AI_FORGE_SOURCE=/path/to/saas-forge bash scripts/check-fullstack.sh
```

离线验证设置`AI_MAVEN_OFFLINE=true`，私有依赖目录用`AI_MAVEN_REPOSITORY`。平台验收需要三个真实可执行jar，不提供时直接失败。[CI](docs/ci.md)明确区分发布SDK、三工程、平台集成和完整Shell门禁。

全栈测试使用真实Shell、IAM PKCE、Gateway、AI、Node DSH、DB/Redis/MQ；只有模型/外部MCP是本地确定性HTTPS提供方，无生产Key或计费请求。Playwright覆盖1440/1024两宽登录、会话、分批输出、审批、历史、删除、权限与租户隔离，确认部分回答在完成前可见，并核对真实用量/平台回传。截图/日志默认在`ai-service/target/fullstack`，可通过`AI_E2E_OUTPUT`放到`/tmp`。

## 部署与运维

[部署指南](docs/deployment.md)包含数据库扩展与迁移、Secret注入、Plan可信补同步、单实例限制、容器资源边界、指标、发布和回滚；[Kustomize样例](deploy/kubernetes/kustomization.yaml)需要按目标环境调整后再由管理员部署。

```bash
AI_IMAGE_TAG=v0.1.0-g<commit> bash scripts/build-images.sh
AI_RUNNER_IMAGE=ai-platform/runner:v0.1.0-g<commit> node scripts/check-runner-image.mjs
kubectl kustomize deploy/kubernetes > /tmp/ai-platform.yaml
```

镜像构建不push、不apply现有集群。`AI_RUNNER_ARCH=arm64|x64`指定Linux/musl目标，BuildKit元数据与manifest digest保存在`target/images/`；镜像契约验证实际DSH初始化、流式回合与容器资源约束。Runner的256MiB是每进程V8堆限制；容器RSS/CPU是整个池的总限额。NetworkPolicy需要目标CNI实际验证，静态渲染不能证明网络隔离已生效。

## 文档

[术语](docs/GLOSSARY.md) · [架构决策](docs/adr/) · [会话](docs/conversation-api.md) · [Runner](docs/runner-contract.md) · [模型](docs/model-api.md) · [Prompt](docs/prompt-management.md) · [Skill](docs/skill-api.md) · [MCP](docs/mcp-api.md) · [用量](docs/usage-api.md) · [Sub-App](docs/sub-app-workspace.md)
