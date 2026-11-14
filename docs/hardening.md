# 失败恢复与会话数据生命周期

## 失败、重试与计量

每个用户消息必须有幂等键。相同键和相同文本的终态只回放，既不重新准入配额，也不执行模型；冲突返回 409。自动恢复保持同一 turn、user 行、幂等键与用量 ledger。明确终态后用户点击重试是重新发送的新回合；网络断流未确认终态时使用原键核对。

模型代理仅对尚未打开成功流的明确 HTTP 429、502、503、504 最多重试两次，退避 100/200ms。401、参数错误、未知网络错误不在此策略中。每次实际请求重新验证短期能力、IAM 成员状态与模型授权。已打开的模型流不自动重放，工具失败由 agent loop 处理；v1 仍只开放只读工具。

Runner 明确 `runtimeExited` 或连接/未结束流丢失时，业务 Job 最多执行一次有界整轮重放。先把旧尝试的部分正文、已见工具结果和公开摘要写入 V11 的私有恢复快照，保留原 user 行与用量 ledger；旧能力撤销、旧审批关闭，重新核验当前 RBAC/ABAC 与模型能力，再用新短期能力和数据库本轮之前的 transcript、冻结 Prompt/Skill/MCP 原子重建指定 session。敏感只读工具的新尝试必须再次批准；v1 只读声明是恢复硬前提，写操作不进入此路径。SSE `retrying` 明确提示重新生成，并清空本轮旧 partial/工具/审批后显示新回答。恢复快照不进入审计、metrics 或导出，用户删除/留存/TenantDeleted 同步清空；原有工具审计和已产生的未知成本保留。

恢复快照的累计上限为 2 MiB（UTF-8），覆盖所有尝试的部分正文、工具摘要和已接受结果。Job 对新增流片段和工具事件增量计数，并在落库时校准及再次检查；超限事件不再接受，取消 Runner 后以 `conversation.limit` 结束并结算同一回合，保留此前已接受内容，不截断结果或放宽上限。

这是可见且有上限的整轮重新生成，不是 DSH offset 续传，也不承诺只读查询恰好执行一次。DSH 没有可用的未完成回合 checkpoint 接缝。第二次崩溃、非崩溃模型错误、取消、授权变化或 Runner 未能恢复时，停止自动执行并保存当前部分回答，用户可以稍后重新发送新回合；断流不确定终态时仍用原幂等键核对。所有后续新回合也先对齐 DB 权威快照：快照与健康缓存完全一致时复用进程，存在差异才替换/重放，避免遗漏 Java 已保存而 DSH 尚未提交的 partial assistant。

内部执行 attempt 事件不对浏览器展示；工具计量和展示使用模型请求尝试命名空间与 callId，避免新尝试复用 call_1 时漏计或覆盖旧调用。
每次实际模型请求在发送前持久化调用证据与保守占额。重试前未结算尝试的估计占额保留，最后成功的精确 usage 不清除先前未知成本；对含多次模型调用的中断回合，估计可能偏高，由现有平台受权估计修正入口处理。未知 Plan 继续 503，繁忙优先，完成键回放不计新用量。

Runner 的建会话准入发生在 Java 返回 SseEmitter 之前。池满对浏览器返回真实 HTTP 429、Forge 错误信封及 Retry-After；流打开后的失败使用 SSE error。Java 本地并发满同样在打开流前拒绝。拒绝的回合会结算为未执行，不遗留活跃回合。

## 搜索与导出

GET `/conversations?query=字面子串&page=1&size=20`，页大小上限 100；返回 ApiResponse 数组与 meta.page。标题与 DB 消息正文使用 LOWER/LIKE，转义 !、%、_，按 updated_at/id 降序，EXISTS 防重复；每张业务表均保留租户/RLS 和所有者隔离、排除逻辑删除。V11 只建立 `extensions.gist_trgm_ops` 表达式索引；管理员须先在新库安装 pg_trgm 到 extensions schema，受限 migrator 不安装扩展。

GET `/conversations/{id}/export?format=markdown|json` 返回 UTF-8 附件，采用只含标题、会话 ID、时间和有序可见消息 role/content/createdAt 的专门公开投影。不输出数据库实体、系统指令、配置快照、工具参数、密钥或引用。跨租户/所有者与已删会话返回 404。文件名由固定前缀、UUID 与受控后缀组成。此专用 MVC 下载与 SSE 一样是规格明确的传输例外，普通 API 继续使用 ApiResponse；当前消息正文没有登记敏感字段，不能声称此下载自动执行 ExcelIo 脱敏。

Sub-App 搜索及加载更多使用 Shell TenantClient.page；下载使用 TenantClient.request('/api/ai/...') 并携带同一租户 signal。切换身份后旧结果与 Blob 不再交付，不创建独立 token/fetch 通道。

## 留存与租户删除

部署配置 `ai.retention.default-days` 默认 90，`ai.retention.plan-days.<planCode>` 与 `ai.retention.tenant-days.<tenantUUID>` 均为正整数。优先级：tenant override → 已同步 Plan code → default；没有 Plan 副本时使用默认值。截止点以 `forge.business-zone` 的当前时间减天数、会话最后更新时间计算。

`ai-conversation-retention` 全局任务每小时通过 IAM `/internal/tenants` 的 Service Credential 分页枚举 pending/trial/active/suspended/expired/archived/deleted。普通 TenantTask 不覆盖停用租户，因此不用于此处。每个租户先绑定 CurrentTenant 再开启事务，以 100 条批次锁定到期且无活跃回合的会话；仅到期清理使用 TenantWriteGuard 的窄维护豁免，RLS 不变。IAM 已逻辑删除的租户仍由 TenantDeleted 事件兜底。

用户删除自己的会话与到期清理共用正文清空路径。TenantDeleted 由公开 SDK 的唯一 handler 验证租户并在同一消费事务内调用 AiTenantDataCleaner：先取消活跃任务、关闭审批并撤销能力，确认 Runner home 清理成功，再清理会话/消息/回合及私有恢复快照、Prompt 及历史版本、租户 Skill/MCP 及版本/工具授权、模型选择与用量四表。包括以前逻辑删除的正文和 secret_ref；平台目录与审计元数据保留。Runner 失败抛出，消费事务回滚且 MQ 重投；重复清理幂等。

## 运行文件与密钥轮换

固定 DSH profile 已禁用 session-persistence-jsonl 与 session-projection-cache，真实进程契约检查完成回合后没有聊天 JSONL 或用户正文文件；成功回合保留进程复用。取消/失败/空闲回收/删除清理受控 home。Runner 启动先验证 root 的 `.ai-platform-runner` 所有权标记；未认领且非空目录拒绝启动，仅清理已认领专属 root 中符合进程目录格式的残留子目录，不触碰其他文件。部署须保证一个 root 仅由一个 Runner 实例拥有。旧版本留下的非空无标记目录不会自动认领；滚动升级使用新 Pod 的专属空目录，或在停旧实例后由运维选择新的空目录。

Java 每次模型/MCP 调用重新从 Environment 解引用允许名单内的同一引用，不将密钥与引用传给 Runner。动态 PropertySource 的变更可供后续请求读取；K8s Secret 作为 OS 环境变量注入时，必须滚动重启 Java 服务使新值生效，不宣称进程环境变量自动热更新。轮换不需要改写 Conversation 历史或产品数据。
