# 11: 失败恢复、留存、搜索与导出

**What to build:** 按 [spec](../spec.md) 的“实现决策”，补齐重试/恢复、429、90 天留存、租户删除清理、搜索与导出。

**Blocked by:** 04, 05, 10

**Status:** resolved

- [x] 模型瞬时错误有限重试；写操作不自动重试；幂等键防重复
- [x] runner 崩溃后用 transcript 重建 DSH 会话并重试当前轮
- [x] 池满 429 + `Retry-After`，前端可解释
- [x] 会话默认 90 天留存，可按 Plan/租户配置；用户可删除自己的会话
- [x] 订阅 `TenantDeleted`，清理会话、消息、Prompt、Skill、MCP 配置、用量与 runner home
- [x] PostgreSQL trigram 搜索；会话导出 Markdown/JSON
- [x] 密钥轮换后 runner 能拿到新引用，不需要重建产品数据
- [x] 测试覆盖：重试、崩溃恢复、429、留存清理、租户删除、搜索、导出

## Comments

2026-11-14：沿用规格确认的 HTTP/SSE、真实 IAM/MQ/DB、Runner 公开 HTTP 契约和 Sub-App 组件/Playwright 接缝。恢复、搜索/导出和清理以 [hardening.md](../../../docs/hardening.md) 为最终契约；不新增 SDK 字段或产品配置 CRUD。留存采用部署 PlanCode 映射、租户覆盖、默认 90 天，并枚举停用状态。

页面沿用现有会话工作台：成员的主任务仍是提问/续聊，搜索放左侧会话列表，Markdown/JSON 放低频“更多”菜单；恢复提示显示当前状态并保持取消/重试可达。Browser plugin not available，使用仓库 Playwright。16 张本轮新/变基线已逐张查看：home、chat、busy、approval、search-empty、search-results、runner-recovery、stream-interrupted，各 1440×900/1024×768；新增搜索框令前八张变化，恢复和断流状态压缩消息区以保留首屏操作。


## Answer

已实现并完成本地验收。实现基于集成分支 `8d4e7e4297507d8cba69c08067d7f09fe5be8028`，约定与边界见 [hardening.md](../../../docs/hardening.md)、[conversation-api.md](../../../docs/conversation-api.md)、[runner-contract.md](../../../docs/runner-contract.md)。

- 模型代理只在打开流之前对 HTTP 429/502/503/504 最多重试两次，逐次检查当前能力/IAM 与模型授权；认证/参数错误、已打开流和写工具不自动重试。实际模型尝试和未知成本保留，成功 usage 不抹去先前估计。
- Runner 崩溃/断连最多一次可见整轮重放：相同 turn/user/key/ledger，保留旧 partial 和工具结果，撤销旧能力/审批并重新授权，以 DB 权威 transcript 与冻结配置重建指定 session。敏感只读查询须再次批准；第二次崩溃终止。健康且相同的快照保留 PID，不重复加入本轮 user；其他会话历史仍保留。
- 累计恢复快照超过 2 MiB 曾实测返回 persistenceFailure：终态前快照写入抛错会跳过结算。已用增量 UTF-8 预算在接受 delta/tool 之前拒绝超限事件，停止执行并正常 ERROR/结算，完整保留已接受结果；实际落库仍校验 2 MiB，同一尝试再次保存幂等。单尝试、跨恢复尝试、中文结果及逐字符 delta 均通过，原键回放不重新计量。没有放宽超时/断言或截断工具结果。
- Runner/Java 本地容量在返回 SseEmitter 前准入，HTTP 429 带 Retry-After，拒绝回合不遗留活跃账本。前端给出等待、重新生成和断流核对提示，恢复时移除旧 partial/工具/审批；终态后显式重试另建新回合。
- V11 建立 PostgreSQL trigram 索引；字面标题/正文搜索转义 !/%/_，按 owner、tenant、deleted 限制并稳定分页。Markdown/JSON 下载只含公开有序消息，不导出配置、工具参数、恢复快照或凭据；前端复用 Shell TenantClient 与租户 signal。
- 留存优先级为租户覆盖、PlanCode、默认 90 天；GlobalTask 通过 IAM 公开目录分页遍历包括 suspended/expired/archived 的状态，绑定租户后开事务。用户删除、留存和 TenantDeleted 清空正文及恢复快照；TenantDeleted 还清理全部租户功能表、旧逻辑删除行和 Runner home，Runner 失败使同一消费事务回滚并由 MQ 重投，重复清理幂等，平台目录和无内容审计保留。
- 模型/MCP 下一次调用重新从 Java Environment 解引用同一允许引用，Runner 不持有 key 或 secret_ref。动态 PropertySource 轮换已验证；OS 环境变量/K8s Secret 注入更新需要滚动重启 Java。DSH 固定 profile 无聊天 JSONL，启动只清理已认领专属 root 的进程目录。

### 最终验证

均在本工单工作树执行，Node 24.14.1、pnpm 12.4.2；未操作共享数据库、用户服务或生产环境。

- `AI_MAVEN_OFFLINE=true AI_MAVEN_REPOSITORY=/private/tmp/ai-usage-forge-verify/m2 AI_FORGE_RUNTIME_REPOSITORY=/private/tmp/ai-usage-forge-verify/m2 ... -Dtest=PlatformIntegrationIT test`：**48/48，0 跳过**，20:27:34 完成。日志 `/private/tmp/ai11-platform-final48.log`，包含真实 IAM 权限/审批、MQ 审计与 Plan/删除事件、用量投影、搜索/导出、留存与恢复快照清空。旧中断的 47 项运行不计通过。
- `AI_MAVEN_OFFLINE=true AI_MAVEN_REPOSITORY=/private/tmp/ai-usage-forge-verify/m2 bash scripts/check.sh service`：**12/12**，Architecture、SchemaComment、Spotless 和 permission-catalog 通过。日志 `/private/tmp/ai11-service-final12.log`。
- `bash scripts/check.sh runner`：严格类型检查/build 与 **26/26 真实 DSH HTTP 契约**通过。日志 `/private/tmp/ai11-runner-final26.log`。
- `bash scripts/check.sh sub-app`：**14/14 组件用例、26/26 Playwright**，隔离 lint、stylelint、Prettier、类型检查和构建通过。两档截图比较通过，16 张新/变截图已逐张查看；console warning/error 和 pageerror 无相关应用错误。日志 `/private/tmp/ai11-sub-app-final26.log`。
- 预算回归红灯：`/private/tmp/ai11-budget-red.log`；修复后恢复/审批目标 3/3：`/private/tmp/ai11-budget-green.log`。最终增量预算版以完整 48 项结果为准。此前 Skill 404 已定位为 TenantDeleted 测试夹具缺少平台发布版本，补齐合法 Skill/MCP 版本后完整回归通过。

真实平台运行包来自私有隔离 Maven 仓库 `/private/tmp/ai-usage-forge-verify/m2`，平台后端提交 `0647e0f1e1539f4882ee430a95d09f32f5ef909b`（keeper `d7a27a63d6d3a52a724f85bc5a0d5f1a825bcbd5` 仅另含视觉验收变化）；本轮 `target/platform-it/*.sha256` 与 `/private/tmp/ai-usage-forge-verify/build-evidence.json` 一致。未改 SDK、未发布 snapshot。真实外部付费模型、生产 K8s/CNI、Shell 登录全链路属于工单 12；本工单用规格已确认的替换 AgentRuntime 与真实 DSH 独立契约接缝验收。整轮重放不承诺只读查询恰好执行一次，v1 保持单 AI/Runner 实例约束。
