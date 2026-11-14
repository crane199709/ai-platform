# AI 用量 HTTP 契约

网关 `GET /ai/usage?month=2026-11`（服务内 `/usage`），Tenant Realm，`ai:configuration:manage`。省略 month 使用 `forge.business-zone` 当前月；非法月份返回 400。标准 `ApiResponse` 信封。

```json
{"code":"ok","httpStatus":200,"data":{"month":"2026-11","businessZone":"Asia/Shanghai","quotaKnown":true,"planVersion":2,"tokenQuota":100000,"inputTokens":1200,"outputTokens":200,"cachedInputTokens":100,"totalTokens":1400,"estimatedTokens":0,"chargedTokens":1400,"remainingTokens":98600,"turnCount":1,"toolCalls":1,"unknownUsageTurns":0,"activeTurns":0,"days":[{"usageDate":"2026-11-14","version":1,"inputTokens":1200,"outputTokens":200,"cachedInputTokens":100,"turnCount":1,"toolCalls":1,"unknownUsageTurns":0,"estimatedTokens":0,"reportedAt":"2026-11-14T05:10:00Z"}]}}
```

`inputTokens` 已包含 `cachedInputTokens`，总量仅 input+output；`estimatedTokens` 是尚无提供方完整用量的估计占额，独立于精确 token。`chargedTokens=totalTokens+estimatedTokens`。`remainingTokens` 在不限额时 null，否则 max(0,quota-charged)。`tokenQuota:null` 仅在 `quotaKnown:true` 时表示不限额，`quotaKnown:false` 表示尚未收到可信 Plan 副本；新回合返回 503 `usage.planUnavailable`，页面显示配额尚未同步，不显示不限额。只有已收到的当前/历史消息明确 `tokenQuota:null` 或历史消息省略此字段才为已知不限额。未知计量必须提示估计、不能宣称精确用量或免费。有限配额执行中的回合串行准入，`activeTurns>0` 新回合 409 `usage.busy`；已消费达到额度时 429 `usage.quotaExceeded`。历史幂等回放不再消费额度。

本回合任一次模型请求缺失完整合法的提供方 usage 时，已执行回合按每次实际模型请求的上下文窗口估计占额；DSH/pi-ai 合成的零对象不算提供方计量，显式合法零值仍可正常结算。工具回合仅部分模型请求有 usage 也保留未知占额；提供方发送之前持久记录请求尝试，执行结果仍以提供方 usage 为准，不能视为精确账单。超过三分钟的运行账本会在准入或用量查询时以独立事务恢复成已结束未知估计；运营校正可恢复可用额度。运营人员可用 `PUT /ai-platform/usage/tenants/{tenantId}/turns/{turnId}/estimate`，body `{"estimatedTokens":2000,"reasonCode":"PROVIDER_RECONCILIATION"}` 修正估计，使用独立 Platform Realm 与现有 `platform:ops:manage`，审计 `ai.usage.adjustEstimate`；仍标记 unknown，不伪造精确 token，调整与日快照/Outbox 同事务。未开始执行的池满拒绝为零计量。

平台运营查询 `GET /platform/tenants/{tenantId}/ai-usage?month=2026-11` 使用 `platform:tenant:read` 且受既有 tenant Data Policy 限制。响应含 `month,businessZone,received,inputTokens,outputTokens,cachedInputTokens,totalTokens,estimatedTokens,turnCount,toolCalls,unknownUsageTurns,days`。`received:false` 为尚未收到快照，不宣称确定为零。

日累计事件 `AiUsageDailyReported` 通过现有 `forge-tenant` Topic 投递，字段为 tenantId,usageDate,businessZone,version,inputTokens,outputTokens,cachedInputTokens,turnCount,toolCalls,unknownUsageTurns,estimatedTokens,reportedAt。两仓 JSON 同契约；按 tenant/day 的持久 version 仅接受更新版本，每回合事务发布累计快照，覆盖停用前已提交用量，无需扫描停用租户。

Plan 副本保留公开事件已有的 planId/planCode，并在较新版本 whole-snapshot 更新时与 quota 一起替换；旧/重复版本不能回退任何字段。UsageService.currentPlan() 供其他租户能力只读获取 Optional<UsagePlanSnapshot>；未收到可信事件时 empty，历史缺省身份字段为空且不合成新副本。HTTP响应附带 planId/planCode，既有页面字段保持兼容。工单11可按 planCode 查部署配置，缺省使用90天，不增加SDK字段。

工具调用以 UsageService.recordToolCall(turnId, scopedCallId) 在运行中持久计数；V10 ledger 的 tool_call_ids 仅保存标识 JSON，不保存参数、结果或内容。相同 scopedCallId 的重复 call/result 不重复计数，终态与过期恢复保留已提交次数。准入、模型请求尝试、工具计数和终态各自事务更新当天累计快照与 Outbox；回合数包含已准入且正在运行的回合，执行中的未知 token 以估计占额展示，平台投影不承担实时准入判断。

工单11重试约束：同一 turn 的每次 AgentRuntime 运行必须创建新的 attemptId，并把它加到 callId 前形成 scopedCallId（例如 attemptId + ":" + callId）；提供方不同尝试可能都返回 call_1，不能把它们当同一次工具调用。工单10 v1 单次运行直接使用其 callId；11负责在自动重试入口建立命名空间，不增加SDK字段或新表。AI租户删除仍只需清理原四张用量表；平台投影 TenantAiUsageCleaner 已由10交付。
