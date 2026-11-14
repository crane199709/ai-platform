# 会话与流式 HTTP 契约

浏览器使用 Shell 的共享 TenantClient，通过 Gateway 的 `/ai` 前缀访问；服务内部路径去掉 `/ai`。普通 JSON 接口返回 Forge ApiResponse，租户和所有者只取已验证 Token。所有会话接口要求 `ai:conversation:use`；跨租户和同租户非所有者一律 404。

| 方法 | 服务路径 | 行为 |
| --- | --- | --- |
| POST | `/conversations` | `{title,modelId,promptTemplateId?,promptVersion?,variables?}` 创建，201 |
| GET | `/conversations` | 当前用户会话，按更新时间降序；列表的 messages/turns 为空 |
| GET | `/conversations/{id}` | 会话元数据、messages、turns、activeTurnId |
| PATCH | `/conversations/{id}` | `{title}` 重命名 |
| DELETE | `/conversations/{id}` | 逻辑删除会话、消息、回合，200；有活跃回合返回409，先取消 |
| POST | `/conversations/{id}/messages` | `{text,idempotencyKey,modelId?}` 接纳一个回合，真实 SSE |
| POST | `/conversations/{id}/cancel` | `{cancelled:boolean}`；仅所有者可取消 |

创建时默认选择平台发布 Prompt。指定租户模板时，`GET /prompts/published` 提供发布版本与 `variables` 声明；普通成员无需读取草稿。会话保存实际解析的 template ID/version/instructions 快照，后续发布和回滚不会改变它。原始用户文本单独保存，Prompt 不混入消息 transcript。创建时同时持久化 SkillService.resolvePublished() 返回的完整审核快照，后续回合和 Runner 恢复继续传入原快照；发布或停用配置只影响新会话。

会话元数据包含 id/ownerId/title/modelId/promptTemplateId/promptVersion/createdAt/updatedAt/activeTurnId。消息包含 id/turnId/role/content/toolCalls/toolName/createdAt；role 为 user、assistant 或 tool，toolCalls 是调用摘要的 JSON 字符串（phase/name/callId/isError），不含凭据。turns 包含 id/status/modelId/errorCode/inputTokens/outputTokens/cachedInputTokens/usageReported；未知用量的数值为空，usageReported=false，不能当作零成本。输入用量包含缓存读取/写入，缓存读取是总输入的子集。

## SSE

`POST /conversations/{id}/messages` 返回 `Content-Type: text/event-stream`、`Cache-Control: no-cache`、`X-Accel-Buffering: no`。使用带 Authorization 的 fetch stream，不能用 EventSource。帧为 `event:<name>\ndata:<JSON>\n\n`。

| event | data |
| --- | --- |
| token | `{turnId,text}`，实际 Runner delta，不在完成后拆字 |
| tool | `{phase,name?,callId?,isError?}`，phase 为 tool.call/tool.result |
| done | `{turnId,status,usageReported,usage?,replayed?}`；status 为 COMPLETED/CANCELLED |
| error | `{turnId,code,retryable:false,replayed?}`；终态，不携带上游正文或密钥 |

每秒发送 `:keep-alive` 注释帧以检测空闲连接断开；客户端关闭、显式取消、超时均取消 Runner 回合并保留已显示的部分输出。done/error 为互斥终态。流打开前的校验、授权、409/413 等错误仍为正常 Forge JSON 与真实 HTTP 状态；打开后的运行错误使用 error 事件。

专用 MVC `@Controller` 返回 SseEmitter；SSE 事件本身就是流的信封。它不将 SseEmitter 包装成 ApiResponse。普通 JSON 继续使用 `@RestController + ApiResponse`，共享 ArchitectureTest 保持启用。

幂等键满足 `[A-Za-z0-9_-]{1,128}`。数据库对租户/会话/幂等键建立部分唯一索引，并在锁定会话的事务中接纳。重复的已结束请求只回放终态，执行中返回409；相同键不同文本或显式不同模型返回409。用户消息、模型选择和 RUNNING 状态先提交，再调用 Runner；终态、工具/助手消息与用量在同一事务提交。服务中断遗留的 RUNNING 回合在三分钟后以 interrupted 结束，幂等重试不会再次执行。

Java DB 是 transcript 权威。完成时保存 Runner 已提交的 user/assistant/tool 语义历史；取消和失败保留部分可见输出。下一回合把完整 DB 历史与冻结 Prompt 交给 Runner；Runner 404 只在未执行本次消息前重建会话再发送一次。不存在失败后的盲目重试。每条输入/输出最多128000字符，历史最多1000条；恢复载荷以900000 UTF-8字节保守预检，超过限制明确413，保留完整历史，不截断。

## 内部模型代理与短期能力

`POST /internal/runner/chat/completions` 是 Runner 专用 OpenAI-compatible SSE。网关拒绝 `/internal`，专用精确 SecurityFilterChain 验证独立能力，Ops Token 和用户 JWT 不能直接调用。

能力为256位随机不透明令牌，最多150秒且不晚于原用户 JWT 到期。Java 实例内保存已验证 Authentication 与 tenant/user/membership/version/session/turn/catalogModel/scopes/expiry；原用户 Token 和模型密钥都不进入 Runner。每次代理请求重新读取真实 IAM 发布的当前 membership version、tenant status 与有效 grants，并核对能力范围及 X-AI-Tenant-Id/X-AI-Session-Id。结束或取消立即撤销。实例重启后未知令牌失败关闭。

能力注册表和活跃 SSE 均在签发实例内；v1 部署需单个 AI 服务实例，或让 Runner 回调精确路由至签发实例。随机多副本负载均衡不受支持。Runner 自己的进程池也有单实例约束，见 runner-contract.md。

Java 按目录 UUID 重新检查可用模型，将 body.model 改成提供方 modelId。上游只用目录里的 HTTPS 地址，禁止重定向和 URL 中的凭据/query/fragment。密钥引用须先命中部署的 `ai.model-proxy.secret-references` 允许列表（`AI_MODEL_SECRET_REFERENCES`，逗号分隔），再从注入环境读取；默认空列表拒绝解析，不允许借目录引用其他服务凭据。contextWindow 使用 UTF-8字节上界加输出预算作保守检查。未报告用量保留未知，不写成免费零值。

本工单默认 Runner tools 为空、能力只有 model:chat；不会提供未鉴权的 tools/call 占位实现。MCP Tool Proxy 与精确工具 scopes 由工单09接入。配额/计量在工单10接入 ConversationTurnService 的准入与事务终态；检索、有限重试与留存交给工单11。
