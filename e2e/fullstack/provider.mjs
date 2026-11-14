// Only external model/MCP boundaries are deterministic; Java, DSH and IAM stay real.
import { createServer } from 'node:https'
import { existsSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs'
const evidence = { modelRequests: 0, mcpCalls: 0, streamedChunks: 0, modelAuthorizationValid: true, usageScenarios: [] }
const server = createServer({ key: readFileSync(process.env.AI_E2E_TLS_KEY), cert: readFileSync(process.env.AI_E2E_TLS_CERT) }, async (req, res) => {
  let raw = ''; for await (const chunk of req) raw += chunk
  const body = raw ? JSON.parse(raw) : {}
  if (req.url === '/mcp') {
    let result
    if (body.method === 'initialize') result = { protocolVersion: '2025-03-26', capabilities: { tools: {} }, serverInfo: { name: 'local-approval-contract', version: '1' } }
    else if (body.method === 'tools/list') result = { tools: [{ name: 'lookup', description: '读取敏感只读状态', inputSchema: { type: 'object', properties: { query: { type: 'string' } } }, annotations: { readOnlyHint: true } }] }
    else if (body.method === 'tools/call') { evidence.mcpCalls++; result = { content: [{ type: 'text', text: 'verified-readonly-status: healthy' }], isError: false } }
    if (body.id === undefined) { res.writeHead(202).end(); return }
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ jsonrpc: '2.0', id: body.id, result }))
  } else if (req.url === '/v1/chat/completions') {
    evidence.modelRequests++
    evidence.modelAuthorizationValid &&= req.headers.authorization === 'Bearer deterministic-local-model-key'
    const messages = body.messages ?? []
    const lastUser = [...messages].reverse().find((item) => item.role === 'user')?.content ?? ''
    const last = messages.at(-1)
    const usageScenario = { 'local-usage-zero': 'USAGE_ZERO', 'local-usage-missing': 'USAGE_MISSING', 'local-usage-mixed': 'USAGE_MIXED' }[body.model]
    if (usageScenario) evidence.usageScenarios.push(usageScenario)
    const wantsTool = (String(lastUser).includes('APPROVAL_TOOL') || usageScenario === 'USAGE_MIXED') && last?.role !== 'tool'
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
    const frame = (delta, finish_reason = null, usage) => res.write(`data: ${JSON.stringify({ id: 'local-chat', object: 'chat.completion.chunk', model: body.model, choices: [{ index: 0, delta, finish_reason }], ...(usage ? { usage } : {}) })}\n\n`)
    if (wantsTool) {
      const name = body.tools?.find((tool) => tool.function?.name?.startsWith('mcp__ai_service__'))?.function.name
      frame({ tool_calls: [{ index: 0, id: 'call-approval', type: 'function', function: { name, arguments: '{"query":"status"}' } }] })
      frame({}, 'tool_calls', usageScenario === 'USAGE_MIXED' ? undefined : { prompt_tokens: 7, completion_tokens: 2, total_tokens: 9 })
    } else {
      for (const [index, content] of ['真实 DSH ', last?.role === 'tool' ? '已完成只读工具查询。' : '分批回答已完成。'].entries()) {
        frame({ content }); evidence.streamedChunks++
        // 浏览器先确认部分正文与取消入口，再释放普通回答的后半段。
        if (index === 0 && !String(lastUser).includes('APPROVAL_TOOL') && !usageScenario && process.env.AI_E2E_STREAM_RELEASE) {
          const deadline = Date.now() + 30000
          while (!existsSync(process.env.AI_E2E_STREAM_RELEASE) && !res.destroyed && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 50))
          if (existsSync(process.env.AI_E2E_STREAM_RELEASE)) unlinkSync(process.env.AI_E2E_STREAM_RELEASE)
        }
        await new Promise((resolve) => setTimeout(resolve, 350))
      }
      frame({}, 'stop', usageScenario === 'USAGE_MISSING' ? undefined : usageScenario === 'USAGE_ZERO' ? { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } : { prompt_tokens: 7, completion_tokens: 2, total_tokens: 9 })
    }
    res.end('data: [DONE]\n\n')
  } else { res.writeHead(404).end() }
  writeFileSync(process.env.AI_E2E_PROVIDER_EVIDENCE, JSON.stringify(evidence, null, 2))
})
server.listen(0, '127.0.0.1', () => writeFileSync(process.env.AI_E2E_PROVIDER_READY, JSON.stringify({ url: `https://127.0.0.1:${server.address().port}` })))
process.on('SIGTERM', () => { server.closeAllConnections(); server.close() })
