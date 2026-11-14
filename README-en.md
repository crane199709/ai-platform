# AI Platform

[简体中文](README.md) · [Deployment and rollback](docs/deployment.md) · [HTTP contract](docs/conversation-api.md)

A tenant AI workspace for internal employees. It uses SaaS Forge identity, authorization, tenancy and audit capabilities to provide conversations, Prompt templates, Skills, reviewed read-only MCP tools, model catalogs and token quotas.

AI Platform is a separate product repository, integrated through **public Forge SDKs and HTTP/MQ contracts**. DSH supplies the agent runtime; the product owns identity, tenancy, authoritative history and authorization.

## Capabilities

- Create, rename and delete conversations; stream and cancel replies; restore, search and export history as Markdown or JSON.
- Platform model catalogs and tenant model selection, with DeepSeek and OpenAI-compatible HTTPS endpoints.
- Drafts, published versions, history and rollback for Prompt, Skill and MCP configuration; conversations capture published snapshots.
- AI service tool proxy with real IAM RBAC/ABAC. v1 tools are read-only; sensitive calls require approval by the active user.
- Monthly token quotas, estimated reservations for unknown usage, daily cumulative reports to the platform, and metadata audits without content or secrets.
- A DSH process and private home per tenant, bounded pool capacity and idle cleanup, with recovery from the authoritative Java DB transcript.

Knowledge bases, write tools, Squad and customer-facing assistants are outside v1. See the [specification](.scratch/ai-platform/spec.md).

## Architecture

```text
Real Tenant Shell + ai-sub-app
         │ /api/ai/** (shared TenantClient)
      Gateway → Java AI Service → Node Runner → per-tenant DSH processes
                   │                │
                   ├─ PostgreSQL    └─ fixed AI model/tool proxy
                   ├─ IAM/Redis/MQ          │
                   └─ Secret references  HTTPS models / reviewed MCP
```

Browsers never connect directly to Runner. User JWTs and raw model/MCP credentials stay outside Runner. AI DB owns complete product history; DSH is a replaceable execution adapter. v1 uses **one AI replica and one Runner replica with Recreate**. In-memory capabilities, approvals and process ownership require fixed routing; adding replicas behind random load balancing is unsupported.

| Directory | Contents |
| --- | --- |
| `ai-service/` | JDK17 / Spring Boot, Forge SDKs, conversations/configuration, quotas, proxies and audits |
| `runner/` | Node24 / strict TypeScript, internal HTTP/SSE adapter pinned to DSH `0.2.2-rc.1` |
| `ai-sub-app/` | Vue Module Federation remote, sharing Shell session, router and client |
| `deploy/` | Images, Kustomize, network policies, database onboarding and Prometheus configuration |
| `docs/` | Glossary, ADRs, HTTP contracts and operations |

## Local development

Install JDK17, Node **24.14.1**, pnpm **12.4.2** and Docker. The complete environment also needs matching Forge IAM/Platform/Gateway, PostgreSQL17, Redis, RocketMQ and Nacos. Maven SDK snapshots come from GitHub Packages using environment-injected credentials. Published frontend packages are pinned under `.forge-packages/`.

```bash
pnpm install --frozen-lockfile
cp ai-service/.env.example ai-service/.env
cp runner/.env.example runner/.env
```

Follow [platform onboarding](deploy/README.md) to configure a separate schema, Service Credential, Gateway, App Registry, Plan and roles. Export variables through your shell or deployment tooling; example files are not loaded automatically.

```bash
# Run separately in terminals with the required environment variables
cd ai-service && ./mvnw spring-boot:run
pnpm --filter @ai-platform/runner build
pnpm --filter @ai-platform/runner start
pnpm --filter @forge/ai-sub-app start:ai
```

Register a browser-accessible `remoteEntry.js` in App Registry, then open `/apps/ai/home` in the matching real Shell. The standalone host from `pnpm --filter @forge/ai-sub-app dev` supports focused UI development; product acceptance uses the full-stack entry below.

> [!NOTE]
> Inject raw model/MCP keys only into AI service; DB stores allowlisted references. Unknown Plans return HTTP 503 until a trusted snapshot arrives. Role templates do not include a default MCP DataPolicy: administrators must configure an appropriate `ai_mcp_tool` scope in IAM.

## Validation

```bash
bash scripts/check.sh all                  # Three projects, architecture/migrations, real DSH HTTP contracts
bash scripts/verify-published-sdk.sh        # Published SDK consumption without local Forge cache
AI_FORGE_RUNTIME_REPOSITORY=/path/to/m2 bash scripts/check.sh platform
AI_FORGE_RUNTIME_REPOSITORY=/path/to/m2 \
AI_FORGE_SOURCE=/path/to/saas-forge bash scripts/check-fullstack.sh
```

Use `AI_MAVEN_OFFLINE=true` for cached offline validation and `AI_MAVEN_REPOSITORY` for a private dependency directory. Platform acceptance requires three real executable jars and fails if they are absent. [CI documentation](docs/ci.md) distinguishes published SDK, project, platform integration and complete Shell gates.

Full-stack tests use real Shell, IAM PKCE, Gateway, AI, Node DSH, DB/Redis/MQ. Only model/external MCP boundaries use deterministic local HTTPS servers, without production keys or billable calls. Playwright covers login, conversations, incremental streaming, approval, history, deletion, authorization and tenant isolation at widths of 1440 and 1024. It confirms partial output is visible before completion and verifies actual usage/platform reports. Artifacts default to `ai-service/target/fullstack`; set `AI_E2E_OUTPUT` to place screenshots/logs under `/tmp`.

## Deployment and operations

The [deployment guide](docs/deployment.md) covers database extensions/migrations, Secret injection, trusted Plan bootstrap, single-instance limits, container resources, metrics, releases and rollback. Adapt the [Kustomize sample](deploy/kubernetes/kustomization.yaml) to the target environment before an administrator deploys it.

```bash
AI_IMAGE_TAG=v0.1.0-g<commit> bash scripts/build-images.sh
AI_RUNNER_IMAGE=ai-platform/runner:v0.1.0-g<commit> node scripts/check-runner-image.mjs
kubectl kustomize deploy/kubernetes > /tmp/ai-platform.yaml
```

Image builds do not push or apply to an existing cluster. Set `AI_RUNNER_ARCH=arm64|x64` for the Linux/musl target. BuildKit metadata, including manifest digests, is saved under `target/images/`; the image contract exercises real DSH initialization, a streamed turn and container resource limits. Runner's 256 MiB limit is per-process V8 heap; container RSS/CPU limits apply to the whole pool. Target CNI enforcement must be tested; static rendering does not prove network isolation.

## Documentation

[Glossary](docs/GLOSSARY.md) · [ADRs](docs/adr/) · [Conversations](docs/conversation-api.md) · [Runner](docs/runner-contract.md) · [Models](docs/model-api.md) · [Prompt](docs/prompt-management.md) · [Skills](docs/skill-api.md) · [MCP](docs/mcp-api.md) · [Usage](docs/usage-api.md) · [Sub-App](docs/sub-app-workspace.md)
