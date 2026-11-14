# 04: Conversation、消息与 SSE

**What to build:** 按 [spec](../spec.md) 的“实现决策”，实现会话与消息模型、AI 服务 API、SSE 流式输出、transcript 持久化与幂等。

**Blocked by:** 02, 03

**Status:** resolved

- [x] 会话表与消息表：租户、所有者、标题、模型、时间、标准列；消息保存角色、内容、工具调用摘要
- [x] 创建/列表/重命名/删除会话；发送消息；取消生成
- [x] SSE 输出：token、tool、done、error 事件；客户端断开触发取消
- [x] 一个 Conversation 对应一个 DSH session；DSH 会话丢失时用 transcript 重建
- [x] 用户消息幂等键，重复提交不重复执行
- [x] 权限与租户隔离：只能访问自己的会话；跨租户不可见
- [x] 集成测试：完整一轮对话、流式事件、取消、幂等、跨租户

## Comments


### 2026-11-14 实现与验收

基线合入 `codex/ai-platform-v1` 的 `63fe930`（含 Runner03、模型06、Prompt07、Skill08 与公共 MQ 测试夹具修复），实现位于 `codex/ai-platform-04`。

- V4 新增会话、消息、回合表，标准列、逻辑删除、租户拦截与强制 RLS；事务锁和数据库唯一索引保障接纳与幂等，不依赖 Map 去重。所有公开读写均验证当前 Identity 所有权。
- 正常 JSON 使用 ApiResponse；专用 MVC Controller 返回真实 SseEmitter，token/tool/done/error 事件与每秒注释心跳，显式取消、空闲断开取消、失败部分输出均持久化。活跃回合字段显式允许空值更新，避免终态后下一回合错误409。
- Java DB 保存权威 user/assistant/tool transcript、工具摘要和每回合模型/标准用量；未知用量保留 null + usageReported=false。Runner 丢失会话只在未执行的404处按 DB 快照重建，完成时采用 Runner 已提交历史。配置/历史超限明确拒绝，不裁掉权威数据。
- 创建时解析正式 PromptService 与 SkillService 的已发布版本，持久化模板 id/version/instructions 与完整 Skill 快照；发布、回滚和停用不改写旧会话。原始用户输入独立保存。发布 Prompt 选择项向普通使用者提供发布版本的变量声明，不开放草稿。
- 真实 Node Runner HTTP 适配已接通；Java 签发256位随机短期能力，绑定真实 Authentication、TenantContext、回合、会话、模型和 scopes。每次内部模型请求重查 IAM 当前会员版本、租户状态与有效权限；结束/取消撤销。精确认证链不会将能力当成 Ops 或用户 JWT。
- 模型代理只用目录 HTTPS，禁止重定向，将目录 UUID 改写为提供方 modelId。部署允许列表限制环境密钥引用，Runner/浏览器/审计不接收原始 Key 或用户 Token。内部原始 SSE 保存请求级认证，正常异步完成不产生 AccessDenied；断开的聊天 SSE 不再尝试 JSON 错误信封。

验证：

- `./mvnw -o -s .mvn/settings.xml spotless:apply verify -Dtest=ArchitectureTest,HttpContractTest,HttpAgentRuntimeContractTest,PlatformSnapshotContractTest,SchemaCommentTest,PlatformIntegrationIT -DforkCount=1`：34/34 通过，包含真实 IAM/Gateway/PostgreSQL/Redis/MQ 的24项组合测试。
- 修正断开后重复 flush 日志后，取消/空闲断开/连续回合/历史恢复真实 HTTP 用例再次1/1通过，日志无额外500或AccessDenied。
- 独立 Runner HTTP 契约证明404恢复、同一 session UUID、数据库历史与 Prompt传递、实际到达时的 delta（完成前可见）和取消；最高服务接缝使用 fake AgentRuntime，模型传输使用外部 ProviderTransport 接缝，授权与审计未 mock。
- 统一 `AI_MAVEN_OFFLINE=true MAVEN_ARGS=-DforkCount=1 bash scripts/check.sh service`：10/10通过，权限目录检查通过。

后续接缝与边界：

- HTTP/SSE、字段与配置见 `docs/conversation-api.md`。能力与活跃 SSE 在签发实例内，v1须单 AI 服务实例或精确回调路由；未知能力失败关闭。
- 默认 tools=[]、scope仅model:chat；不提供未鉴权的 tools/call 占位。工单09接工具快照、精确 scopes 与授权代理；工单10在 ConversationTurnService准入/终态事务接计量，工单11接搜索、有限重试、留存/租户删除，页面与部署分别由05/12完成。
- 本工单没有进行付费外部模型调用，没有提供真实密钥；生产 HTTPS 约束保留。Node真实DSH/Skill物化契约由03/08已交付，Java最高接缝并非声称真实付费上游的全链路验收。
