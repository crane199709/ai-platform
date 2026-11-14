# 部署前提

当前只交付工程与 CI 骨架，没有完整 AI 运行环境或生产部署清单。真实平台接入由工单 02 实现，镜像、网络策略和端到端部署由工单 12 实现。

- Java 服务需要 Java 17、独立 PostgreSQL schema、迁移与运行账号、Redis、Nacos、RocketMQ，以及 IAM 的地址、Tenant Realm issuer/JWKS、Manifest 和 Ops 服务凭证。变量见 `ai-service/.env.example`，实际绑定见 application.yml。
- Runner 需要 Node 24，AI_RUNNER_HOST 和 AI_RUNNER_PORT 显式配置。当前仅实现 GET /health 存活检查；没有 DSH、会话或模型调用能力。未来只允许 AI 服务访问内网 HTTP/SSE。
- Sub-App 产物为 ai-sub-app/dist，应用编码 ai。通过 App Registry、Plan 和 Manifest 接入租户 Shell；本工单尚未登记真实环境。首次页面为 /apps/ai/home，后续工作台由工单 05 实现。
- 模型和 MCP 密钥在后续功能接入时由平台密钥管理注入。骨架构建和 CI 不使用模型密钥。
