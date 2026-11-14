import assert from 'node:assert/strict'
import { once } from 'node:events'
import { createServer } from 'node:http'
import { mkdtemp, rm, readdir, readFile, mkdir, writeFile, lstat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test, type TestContext } from 'node:test'
import { createRunnerServer, prepareRunnerRoot, type RunnerOptions } from './server.ts'

async function fixture(t: TestContext, scenario = 'text', overrides: Partial<RunnerOptions> = {}) {
  const requests: Record<string, unknown>[] = []
  const proxyRequests: { path?: string; headers: Record<string, unknown>; body: unknown }[] = []
  const provider = createServer(async (req, res) => {
    let body = ''
    for await (const chunk of req) body += chunk
    proxyRequests.push({ path: req.url, headers: { ...req.headers }, body: JSON.parse(body) })
    if (req.url === '/tools/call') {
      if (scenario === 'tool-approval-wait')
        await new Promise((resolve) => setTimeout(resolve, 17000))
      if (scenario === 'tool-failure') {
        res.writeHead(503).end('never-echo-upstream-secret')
        return
      }
      res
        .writeHead(200, { 'content-type': 'application/json' })
        .end(JSON.stringify({ content: [{ type: 'text', text: 'status: healthy' }] }))
      return
    }
    requests.push(JSON.parse(body))
    if (scenario === 'failure' && requests.length === 1) {
      res.writeHead(503).end('never-echo-upstream-secret')
      return
    }
    res.writeHead(200, { 'content-type': 'text/event-stream' })
    if (scenario === 'hold' && requests.length === 1) {
      res.write(
        `data: ${JSON.stringify({ id: 'hold', choices: [{ index: 0, delta: { content: 'partial' }, finish_reason: null }] })}\n\n`,
      )
      return
    }
    if ((scenario.startsWith('tool') || scenario === 'skill') && requests.length === 1) {
      res.end(
        `data: ${JSON.stringify({ id: 'tool-1', choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: 'call-1', type: 'function', function: { name: scenario === 'skill' ? 'skill' : scenario === 'tool-alias' ? (JSON.parse(body).tools[0].function.name as string) : 'mcp__ai_service__lookup', arguments: scenario === 'skill' ? '{"name":"approved-summary"}' : '{"query":"status"}' } }] }, finish_reason: null }] })}\n\ndata: ${JSON.stringify({ id: 'tool-1', choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }] })}\n\ndata: [DONE]\n\n`,
      )
      return
    }
    for (const content of ['Hello', ' tenant']) {
      res.write(
        `data: ${JSON.stringify({ id: 'chat-1', object: 'chat.completion.chunk', model: 'test-model', choices: [{ index: 0, delta: { content }, finish_reason: null }] })}\n\n`,
      )
      await new Promise((resolve) => setTimeout(resolve, 20))
    }
    res.end(
      `data: ${JSON.stringify({ id: 'chat-1', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 7, completion_tokens: 2, total_tokens: 9, ...(scenario === 'cache' ? { prompt_tokens_details: { cached_tokens: 3 } } : {}) } })}\n\ndata: [DONE]\n\n`,
    )
  })
  provider.listen(0, '127.0.0.1')
  await once(provider, 'listening')
  const address = provider.address()
  assert.ok(address && typeof address !== 'string')
  const rootDir = await mkdtemp(join(tmpdir(), 'ai-runner-contract-'))
  await prepareRunnerRoot(rootDir)
  const runner = createRunnerServer({
    serviceToken: 'contract-service-token',
    proxyUrl: `http://127.0.0.1:${address.port}`,
    rootDir,
    ...overrides,
  })
  runner.listen(0, '127.0.0.1')
  await once(runner, 'listening')
  const runtimeAddress = runner.address()
  assert.ok(runtimeAddress && typeof runtimeAddress !== 'string')
  const base = `http://127.0.0.1:${runtimeAddress.port}/v1/tenants/acme`
  const call = (path: string, method = 'GET', body?: unknown) =>
    fetch(`${base}${path}`, {
      method,
      headers: {
        authorization: 'Bearer contract-service-token',
        'content-type': 'application/json',
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
  t.after(async () => {
    await runner.closeRuntime()
    await new Promise<void>((resolve) => runner.close(() => resolve()))
    provider.closeAllConnections()
    await new Promise<void>((resolve) => provider.close(() => resolve()))
    await rm(rootDir, { recursive: true, force: true })
  })
  return {
    call,
    requests,
    proxyRequests,
    rootDir,
    base: `http://127.0.0.1:${runtimeAddress.port}`,
    headers: { authorization: 'Bearer contract-service-token', 'content-type': 'application/json' },
  }
}

test(
  'real DSH initializes a session and streams only AI service model output',
  { timeout: 30000 },
  async (t) => {
    const { call, requests } = await fixture(t)
    const created = await call('/sessions', 'POST', {
      sessionId: 'conversation-1',
      model: 'test-model',
    })
    assert.equal(created.status, 201, await created.text())
    const stream = await call('/sessions/conversation-1/messages', 'POST', {
      text: 'say hello',
      contextToken: 'short-lived-context',
    })
    assert.equal(stream.status, 200, await stream.clone().text())
    const events = await stream.text()
    assert.match(events, /event: delta\ndata: \{"text":"Hello"\}/)
    assert.match(events, /event: completed/)
    assert.equal(requests.length, 1)
    assert.deepEqual(requests[0].tools ?? [], [])
  },
)

test(
  'real DSH calls only a declared read-only tool through the AI service proxy',
  { timeout: 30000 },
  async (t) => {
    const { call, requests, proxyRequests } = await fixture(t, 'tool')
    const created = await call('/sessions', 'POST', {
      sessionId: 'conversation-tool',
      model: 'test-model',
      tools: [
        {
          name: 'lookup',
          description: 'Read service status',
          inputSchema: { type: 'object', properties: { query: { type: 'string' } } },
          readOnly: true,
        },
      ],
    })
    assert.equal(created.status, 201, await created.text())
    const stream = await call('/sessions/conversation-tool/messages', 'POST', {
      text: 'look up status',
      contextToken: 'short-lived-context',
    })
    const events = await stream.text()
    assert.match(events, /event: tool.call/)
    assert.match(events, /status: healthy/)
    assert.match(events, /event: completed/)
    assert.deepEqual(
      (requests[0].tools as any[]).map((tool) => tool.function.name),
      ['mcp__ai_service__lookup'],
    )
    const exported = await (await call('/sessions/conversation-tool/export')).json()
    assert.equal(
      exported.transcript.find((message: any) => message.role === 'tool')?.name,
      'mcp__ai_service__lookup',
    )
    const toolRequest = proxyRequests.find((req) => req.path === '/tools/call')!
    assert.equal(toolRequest.headers.authorization, 'Bearer short-lived-context')
    assert.equal(toolRequest.headers['x-ai-tenant-id'], 'acme')
    assert.deepEqual(toolRequest.body, { name: 'lookup', arguments: { query: 'status' } })
  },
)

test(
  'real DSH preserves distinct short aliases within the final provider function-name limit',
  { timeout: 30000 },
  async (t) => {
    const { call, requests, proxyRequests } = await fixture(t, 'tool-alias')
    const aliases = ['m0123456789abcdef0123456789abcdef', 'mfedcba9876543210fedcba9876543210']
    const created = await call('/sessions', 'POST', {
      sessionId: 'safe-aliases',
      model: 'test-model',
      tools: aliases.map((name) => ({
        name,
        description: 'Reviewed read-only tool',
        inputSchema: { type: 'object' },
        readOnly: true,
      })),
    })
    assert.equal(created.status, 201, await created.text())
    const stream = await call('/sessions/safe-aliases/messages', 'POST', {
      text: 'lookup',
      contextToken: 'short-lived-context',
    })
    const events = await stream.text()
    assert.match(events, /event: completed/)
    const frames = [...events.matchAll(/event: ([^\n]+)\ndata: ([^\n]+)/g)].map((match) => ({
      type: match[1],
      data: JSON.parse(match[2]),
    }))
    const called = frames.find((frame) => frame.type === 'tool.call')!
    const result = frames.find((frame) => frame.type === 'tool.result')!
    assert.equal(result.data.callId, called.data.callId)
    assert.equal(result.data.name, `mcp__ai_service__${aliases[0]}`)
    const names = (requests[0].tools as { function: { name: string } }[]).map(
      (tool) => tool.function.name,
    )
    assert.deepEqual(
      names,
      aliases.map((name) => `mcp__ai_service__${name}`),
    )
    assert.equal(new Set(names).size, 2)
    for (const name of names) {
      assert.match(name, /^[A-Za-z0-9_-]+$/)
      assert.ok(name.length <= 64, name)
    }
    assert.deepEqual(proxyRequests.find((request) => request.path === '/tools/call')?.body, {
      name: aliases[0],
      arguments: { query: 'status' },
    })
  },
)

test(
  'real DSH keeps a tool request alive while Java waits for a user decision',
  { timeout: 30000 },
  async (t) => {
    const { call } = await fixture(t, 'tool-approval-wait')
    const created = await call('/sessions', 'POST', {
      sessionId: 'approval-wait',
      model: 'test-model',
      tools: [
        {
          name: 'lookup',
          description: 'Reviewed sensitive read-only lookup',
          inputSchema: { type: 'object' },
          readOnly: true,
        },
      ],
    })
    assert.equal(created.status, 201, await created.text())
    const stream = await call('/sessions/approval-wait/messages', 'POST', {
      text: 'lookup after approval',
      contextToken: 'short-lived-context',
    })
    const events = await stream.text()
    assert.match(events, /event: completed/)
    assert.match(events, /status: healthy/)
    assert.doesNotMatch(events, /runner.runtimeTimeout/)
  },
)

test(
  'real DSH refreshes tools on an existing session after Java revokes access',
  { timeout: 30000 },
  async (t) => {
    const { call, requests } = await fixture(t)
    const tool = {
      name: 'lookup',
      description: 'Reviewed lookup',
      inputSchema: { type: 'object' },
      readOnly: true,
    }
    assert.equal(
      (
        await call('/sessions', 'POST', {
          sessionId: 'tool-refresh',
          model: 'test-model',
          tools: [tool],
        })
      ).status,
      201,
    )
    assert.match(
      await (
        await call('/sessions/tool-refresh/messages', 'POST', {
          text: 'first context',
          contextToken: 'first-capability',
        })
      ).text(),
      /event: completed/,
    )
    const next = await call('/sessions/tool-refresh/messages', 'POST', {
      text: 'second context',
      contextToken: 'second-capability',
      tools: [],
    })
    assert.equal(next.status, 200, await next.clone().text())
    assert.match(await next.text(), /event: completed/)
    assert.deepEqual(
      (requests[0].tools as any[]).map((tool) => tool.function.name),
      ['mcp__ai_service__lookup'],
    )
    assert.deepEqual(requests[1].tools ?? [], [])
    assert.match(JSON.stringify(requests[1].messages), /first context/)
    const unsafe = await call('/sessions/tool-refresh/messages', 'POST', {
      text: 'unsafe',
      contextToken: 'third-capability',
      tools: [{ ...tool, readOnly: false }],
    })
    assert.equal(unsafe.status, 400)
    assert.equal(requests.length, 2)
  },
)

test(
  'a full pool returns 429 with Retry-After without starting another tenant',
  { timeout: 30000 },
  async (t) => {
    const { call, base, headers } = await fixture(t, 'text', { maxTenants: 1 })
    assert.equal(
      (await call('/sessions', 'POST', { sessionId: 'one', model: 'test-model' })).status,
      201,
    )
    const full = await fetch(`${base}/v1/tenants/other/sessions`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ sessionId: 'two', model: 'test-model' }),
    })
    assert.equal(full.status, 429)
    assert.match(full.headers.get('retry-after') ?? '', /^[1-9][0-9]*$/)
    assert.equal((await full.json()).code, 'runner.poolFull')
  },
)

test(
  'live deltas arrive before completion; cancellation permits transcript recovery in a fresh DSH process',
  { timeout: 30000 },
  async (t) => {
    const { call, requests } = await fixture(t, 'hold')
    const history = [
      { role: 'user', content: 'Remember tenant code BLUE' },
      { role: 'assistant', content: 'I will remember BLUE' },
      { role: 'tool', name: 'lookup', content: 'Previous lookup: healthy' },
    ]
    assert.equal(
      (
        await call('/sessions', 'POST', {
          sessionId: 'recover',
          model: 'dynamic-model',
          transcript: history,
        })
      ).status,
      201,
    )
    const stream = await call('/sessions/recover/messages', 'POST', {
      text: 'continue',
      contextToken: 'short-lived-context',
    })
    const reader = stream.body!.getReader()
    let early = ''
    while (!early.includes('partial'))
      early += new TextDecoder().decode((await reader.read()).value)
    assert.match(early, /event: delta/)
    assert.doesNotMatch(early, /event: completed/)
    assert.equal(
      (
        await call('/sessions/recover/messages', 'POST', {
          text: 'concurrent',
          contextToken: 'short-lived-context',
        })
      ).status,
      409,
    )
    assert.deepEqual(await (await call('/sessions/recover/cancel', 'POST')).json(), {
      cancelled: true,
    })
    let ending = ''
    for (;;) {
      const item = await reader.read()
      if (item.done) break
      ending += new TextDecoder().decode(item.value)
    }
    assert.match(ending, /event: cancelled/)
    assert.doesNotMatch(ending, /event: completed|event: error/)
    const recovered = await call('/sessions/recover/messages', 'POST', {
      text: 'What is the code?',
      contextToken: 'new-scoped-context',
    })
    const events = await recovered.text()
    assert.match(events, /event: completed/)
    assert.match(JSON.stringify(requests[1].messages), /BLUE/)
    assert.match(JSON.stringify(requests[1].messages), /Previous lookup: healthy/)
    const exported = await (await call('/sessions/recover/export')).json()
    assert.deepEqual(exported.transcript.slice(0, 3), history)
    assert.deepEqual(exported.transcript.at(-1), { role: 'assistant', content: 'Hello tenant' })
    assert.match(events, /"inputTokens":7,"outputTokens":2/)
  },
)

test(
  'separate tenants can use the same conversation id without sharing history or capability tokens',
  { timeout: 30000 },
  async (t) => {
    const { call, base, headers, proxyRequests } = await fixture(t)
    assert.equal(
      (
        await call('/sessions', 'POST', {
          sessionId: 'shared-id',
          model: 'test-model',
          transcript: [{ role: 'user', content: 'ACME secret context' }],
        })
      ).status,
      201,
    )
    const other = (path: string, method = 'GET', body?: unknown) =>
      fetch(`${base}/v1/tenants/other${path}`, {
        method,
        headers,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      })
    assert.equal((await other('/sessions/shared-id/export')).status, 404)
    assert.equal(
      (
        await other('/sessions', 'POST', {
          sessionId: 'shared-id',
          model: 'test-model',
          transcript: [{ role: 'user', content: 'OTHER context' }],
        })
      ).status,
      201,
    )
    await (
      await call('/sessions/shared-id/messages', 'POST', {
        text: 'hello',
        contextToken: 'acme-capability',
      })
    ).text()
    await (
      await other('/sessions/shared-id/messages', 'POST', {
        text: 'hello',
        contextToken: 'other-capability',
      })
    ).text()
    assert.match(JSON.stringify(proxyRequests[0].body), /ACME secret context/)
    assert.doesNotMatch(JSON.stringify(proxyRequests[1].body), /ACME secret context/)
    assert.equal(proxyRequests[0].headers.authorization, 'Bearer acme-capability')
    assert.equal(proxyRequests[1].headers.authorization, 'Bearer other-capability')
    assert.equal((await call('', 'DELETE')).status, 204)
    assert.equal((await call('/sessions/shared-id/export')).status, 404)
    assert.equal((await other('/sessions/shared-id/export')).status, 200)
  },
)

test(
  'idle tenants release their pool slot and duplicate session creation preserves the existing session',
  { timeout: 30000 },
  async (t) => {
    const { call, base, headers } = await fixture(t, 'text', { idleTimeoutMs: 100, maxTenants: 1 })
    assert.equal(
      (await call('/sessions', 'POST', { sessionId: 'idle', model: 'test-model' })).status,
      201,
    )
    assert.equal(
      (await call('/sessions', 'POST', { sessionId: 'idle', model: 'test-model' })).status,
      409,
    )
    assert.equal((await call('/sessions/idle/export')).status, 200)
    const deadline = Date.now() + 3000
    let count = 1
    while (Date.now() < deadline && count > 0) {
      await new Promise((resolve) => setTimeout(resolve, 50))
      count = (await (await fetch(`${base}/v1/runtime`, { headers })).json()).tenants
    }
    assert.equal(count, 0)
    assert.equal(
      (await call('/sessions', 'POST', { sessionId: 'replacement', model: 'test-model' })).status,
      201,
    )
  },
)

test('Runner rejects raw keys, endpoint overrides, and tools lacking an explicit read-only declaration', async (t) => {
  const { call } = await fixture(t)
  for (const extra of [
    { apiKey: 'never-log-this' },
    { endpoint: 'https://example.invalid' },
    {
      tools: [
        {
          name: 'write',
          description: 'write data',
          inputSchema: { type: 'object' },
          readOnly: false,
        },
      ],
    },
  ]) {
    const response = await call('/sessions', 'POST', {
      sessionId: 'unsafe',
      model: 'test-model',
      ...extra,
    })
    assert.equal(response.status, 400)
    assert.doesNotMatch(await response.text(), /never-log-this|example.invalid/)
  }
})

test(
  'simultaneous first messages reserve a single tenant turn before any SDK await',
  { timeout: 15000 },
  async (t) => {
    const { call } = await fixture(t, 'text', { maxTurnMs: 2000 })
    assert.equal(
      (await call('/sessions', 'POST', { sessionId: 'race', model: 'test-model' })).status,
      201,
    )
    const responses = await Promise.all([
      call('/sessions/race/messages', 'POST', { text: 'first', contextToken: 'first-capability' }),
      call('/sessions/race/messages', 'POST', {
        text: 'second',
        contextToken: 'second-capability',
      }),
    ])
    await Promise.all(responses.map((response) => response.text()))
    assert.deepEqual(responses.map((response) => response.status).sort(), [200, 409])
  },
)

test(
  'tool failure returns to the real agent loop without exposing upstream diagnostics',
  { timeout: 30000 },
  async (t) => {
    const { call } = await fixture(t, 'tool-failure')
    assert.equal(
      (
        await call('/sessions', 'POST', {
          sessionId: 'tool-fail',
          model: 'test-model',
          tools: [
            {
              name: 'lookup',
              description: 'Read status',
              inputSchema: { type: 'object' },
              readOnly: true,
            },
          ],
        })
      ).status,
      201,
    )
    const events = await (
      await call('/sessions/tool-fail/messages', 'POST', {
        text: 'read status',
        contextToken: 'scoped-context',
      })
    ).text()
    assert.match(events, /"isError":true/)
    assert.match(events, /event: completed/)
    assert.doesNotMatch(events, /never-echo-upstream-secret|scoped-context/)
  },
)

test(
  'model failure and time limits terminate the real process and permit the next recovered turn',
  { timeout: 30000 },
  async (t) => {
    for (const scenario of ['failure', 'hold']) {
      const { call, requests } = await fixture(t, scenario, { maxTurnMs: 500 })
      assert.equal(
        (await call('/sessions', 'POST', { sessionId: scenario, model: 'test-model' })).status,
        201,
      )
      const failed = await (
        await call(`/sessions/${scenario}/messages`, 'POST', {
          text: 'remember this failed attempt',
          contextToken: 'scoped-context',
        })
      ).text()
      assert.match(failed, /event: error/)
      assert.doesNotMatch(failed, /event: completed|never-echo-upstream-secret|scoped-context/)
      const recovered = await (
        await call(`/sessions/${scenario}/messages`, 'POST', {
          text: 'try again',
          contextToken: 'new-context',
        })
      ).text()
      assert.match(recovered, /event: completed/)
      assert.match(JSON.stringify(requests.at(-1)), /remember this failed attempt/)
    }
  },
)

test(
  'a new Runner restores exported DB history without DSH files and accepts a model switch',
  { timeout: 30000 },
  async (t) => {
    const first = await fixture(t)
    assert.equal(
      (await first.call('/sessions', 'POST', { sessionId: 'durable', model: 'test-model' })).status,
      201,
    )
    await (
      await first.call('/sessions/durable/messages', 'POST', {
        text: 'remember source transcript',
        contextToken: 'first-context',
      })
    ).text()
    const exported = await (await first.call('/sessions/durable/export')).json()
    await first.call('', 'DELETE')
    const second = await fixture(t)
    const restored = await second.call('/sessions', 'POST', {
      sessionId: 'durable',
      model: 'test-model',
      transcript: exported.transcript,
    })
    assert.equal((await restored.json()).recovery, 'context-replay')
    const events = await (
      await second.call('/sessions/durable/messages', 'POST', {
        text: 'continue with another model',
        model: 'new/catalog-model',
        contextToken: 'second-context',
      })
    ).text()
    assert.match(events, /event: completed/)
    assert.equal(second.requests[0].model, 'new/catalog-model')
    assert.match(JSON.stringify(second.requests[0].messages), /remember source transcript/)
    assert.match(JSON.stringify(second.requests[0].messages), /Hello tenant/)
  },
)

test(
  'a killed real DSH process emits a classified failure and can recover the next turn',
  { timeout: 30000 },
  async (t) => {
    const { call, base, headers, requests } = await fixture(t, 'hold')
    assert.equal(
      (await call('/sessions', 'POST', { sessionId: 'crash', model: 'test-model' })).status,
      201,
    )
    const stream = await call('/sessions/crash/messages', 'POST', {
      text: 'remember before crash',
      contextToken: 'crash-context',
    })
    const reader = stream.body!.getReader()
    let events = ''
    while (!events.includes('partial'))
      events += new TextDecoder().decode((await reader.read()).value)
    const runtime = await (await fetch(`${base}/v1/runtime`, { headers })).json()
    const pid = runtime.workers[0].processId
    assert.ok(Number.isInteger(pid) && pid !== process.pid)
    process.kill(pid, 'SIGKILL')
    for (;;) {
      const item = await reader.read()
      if (item.done) break
      events += new TextDecoder().decode(item.value)
    }
    assert.match(events, /runner.runtimeExited/)
    assert.doesNotMatch(events, /event: completed/)
    const recovered = await (
      await call('/sessions/crash/messages', 'POST', {
        text: 'continue',
        contextToken: 'recovery-context',
      })
    ).text()
    assert.match(recovered, /event: completed/)
    assert.match(JSON.stringify(requests[1].messages), /remember before crash/)
  },
)

test(
  'simultaneous tenant initialization shares one capacity reservation',
  { timeout: 15000 },
  async (t) => {
    const { call } = await fixture(t, 'text', { maxTenants: 1 })
    const responses = await Promise.all([
      call('/sessions', 'POST', { sessionId: 'first-session', model: 'test-model' }),
      call('/sessions', 'POST', { sessionId: 'second-session', model: 'test-model' }),
    ])
    assert.deepEqual(responses.map((response) => response.status).sort(), [201, 409])
  },
)

test(
  'an initialization whose body finishes late reuses the tenant created during body upload',
  { timeout: 15000 },
  async (t) => {
    const { call, base, headers } = await fixture(t, 'text', { maxTenants: 1 })
    let finishBody!: () => void
    const body = new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('{'))
        finishBody = () => {
          controller.enqueue(new TextEncoder().encode('"sessionId":"same","model":"test-model"}'))
          controller.close()
        }
      },
    })
    const slow = fetch(`${base}/v1/tenants/acme/sessions`, {
      method: 'POST',
      headers,
      body,
      duplex: 'half',
    } as RequestInit)
    await new Promise((resolve) => setTimeout(resolve, 50))
    assert.equal(
      (await call('/sessions', 'POST', { sessionId: 'same', model: 'test-model' })).status,
      201,
    )
    finishBody()
    const response = await slow
    assert.equal(response.status, 409)
    assert.equal((await response.json()).code, 'runner.sessionExists')
  },
)

test(
  'cached model tokens remain included in total input usage for quota accounting',
  { timeout: 15000 },
  async (t) => {
    const { call } = await fixture(t, 'cache')
    assert.equal(
      (await call('/sessions', 'POST', { sessionId: 'cache', model: 'test-model' })).status,
      201,
    )
    const events = await (
      await call('/sessions/cache/messages', 'POST', {
        text: 'hello',
        contextToken: 'cache-context',
      })
    ).text()
    assert.match(events, /"inputTokens":7,"outputTokens":2,"cachedInputTokens":3/)
  },
)

test(
  'published Prompt instructions reach the real model system context without altering product transcript',
  { timeout: 15000 },
  async (t) => {
    const { call, requests } = await fixture(t)
    const instructions =
      'You are the reviewed tenant assistant. Always include review marker PROMPT-17.'
    const created = await call('/sessions', 'POST', {
      sessionId: 'prompt',
      model: 'test-model',
      instructions,
    })
    assert.equal(created.status, 201, await created.text())
    const events = await (
      await call('/sessions/prompt/messages', 'POST', {
        text: 'original user input',
        contextToken: 'prompt-context',
      })
    ).text()
    assert.match(events, /event: completed/)
    const system = (requests[0].messages as any[]).filter((message) => message.role === 'system')
    assert.match(JSON.stringify(system), /PROMPT-17/)
    const exported = await (await call('/sessions/prompt/export')).json()
    assert.deepEqual(exported.transcript[0], { role: 'user', content: 'original user input' })
    assert.doesNotMatch(JSON.stringify(exported.transcript), /PROMPT-17/)
  },
)

test(
  'real DSH reads reviewed Skill snapshots and keeps disabled new sessions isolated',
  { timeout: 60000 },
  async (t) => {
    const { call, requests, base, headers } = await fixture(t, 'skill')
    const skills = [
      {
        catalogId: '019a2525-0000-7000-8000-000000000081',
        code: 'approved-summary',
        name: 'Approved summary',
        description: 'Summarize approved records',
        version: 1,
        content: 'SKILL_SNAPSHOT_ONE: include a verified evidence section.',
      },
    ]
    const created = await call('/sessions', 'POST', {
      sessionId: 'with-skill',
      model: 'test-model',
      skills,
    })
    assert.equal(created.status, 201, await created.text())
    const stream = await call('/sessions/with-skill/messages', 'POST', {
      text: 'use the approved summary skill',
      contextToken: 'skill-context',
    })
    const events = await stream.text()
    assert.match(events, /event: completed/)
    const frames = [...events.matchAll(/event: ([^\n]+)\ndata: ([^\n]+)/g)].map((match) => ({
      type: match[1],
      data: JSON.parse(match[2]),
    }))
    const called = frames.find((frame) => frame.type === 'tool.call')!
    const result = frames.find((frame) => frame.type === 'tool.result')!
    assert.equal(called.data.name, 'skill')
    assert.equal(result.data.callId, called.data.callId)
    assert.equal(result.data.name, 'skill')
    assert.equal(result.data.isError, false)
    assert.deepEqual(
      (requests[0].tools as any[]).map((tool) => tool.function.name),
      ['skill'],
    )
    assert.match(JSON.stringify(requests[0].messages), /approved-summary/)
    assert.match(JSON.stringify(requests[1].messages), /SKILL_SNAPSHOT_ONE/)
    assert.match(events, /event: tool.call/)
    assert.match(events, /SKILL_SNAPSHOT_ONE/)
    assert.equal(
      (
        await call('/sessions', 'POST', {
          sessionId: 'after-disable',
          model: 'test-model',
          skills: [],
        })
      ).status,
      201,
    )
    assert.match(
      await (
        await call('/sessions/after-disable/messages', 'POST', {
          text: 'summarize',
          contextToken: 'new-context',
        })
      ).text(),
      /event: completed/,
    )
    assert.deepEqual(requests[2].tools ?? [], [])
    assert.doesNotMatch(JSON.stringify(requests[2].messages), /approved-summary|SKILL_SNAPSHOT_ONE/)
    assert.match(
      await (
        await call('/sessions/with-skill/messages', 'POST', {
          text: '/approved-summary summarize again',
          contextToken: 'old-context',
        })
      ).text(),
      /event: completed/,
    )
    assert.deepEqual(
      (requests[3].tools as any[]).map((tool) => tool.function.name),
      ['skill'],
    )
    assert.match(JSON.stringify(requests[3].messages), /SKILL_SNAPSHOT_ONE/)
    const newer = [
      { ...skills[0], version: 2, content: 'SKILL_SNAPSHOT_TWO: compare newer records.' },
    ]
    assert.equal(
      (
        await call('/sessions', 'POST', {
          sessionId: 'new-version',
          model: 'test-model',
          skills: newer,
        })
      ).status,
      201,
    )
    assert.match(
      await (
        await call('/sessions/new-version/messages', 'POST', {
          text: '/approved-summary summarize',
          contextToken: 'version-two-context',
        })
      ).text(),
      /event: completed/,
    )
    assert.match(JSON.stringify(requests[4].messages), /SKILL_SNAPSHOT_TWO/)
    assert.doesNotMatch(JSON.stringify(requests[4].messages), /SKILL_SNAPSHOT_ONE/)
    assert.match(
      await (
        await call('/sessions/with-skill/messages', 'POST', {
          text: '/approved-summary original version',
          contextToken: 'version-one-context',
        })
      ).text(),
      /event: completed/,
    )
    assert.match(JSON.stringify(requests[5].messages), /SKILL_SNAPSHOT_ONE/)
    assert.doesNotMatch(JSON.stringify(requests[5].messages), /SKILL_SNAPSHOT_TWO/)
    const other = (path: string, method: string, body: unknown) =>
      fetch(`${base}/v1/tenants/other${path}`, { method, headers, body: JSON.stringify(body) })
    assert.equal(
      (await other('/sessions', 'POST', { sessionId: 'with-skill', model: 'test-model' })).status,
      201,
    )
    assert.match(
      await (
        await other('/sessions/with-skill/messages', 'POST', {
          text: 'summarize',
          contextToken: 'other-context',
        })
      ).text(),
      /event: completed/,
    )
    assert.deepEqual(requests[6].tools ?? [], [])
    assert.doesNotMatch(
      JSON.stringify(requests[6].messages),
      /approved-summary|SKILL_SNAPSHOT_ONE|SKILL_SNAPSHOT_TWO/,
    )
  },
)

test('Skill snapshots reject paths, executable attachments, duplicate codes and invalid versions', async (t) => {
  const { call } = await fixture(t)
  const valid = {
    catalogId: '019a2525-0000-7000-8000-000000000081',
    code: 'approved-summary',
    name: 'Approved',
    description: 'Reviewed',
    version: 1,
    content: 'Approved instructions',
  }
  for (const skills of [
    [{ ...valid, code: '../escape' }],
    [{ ...valid, code: 'invalid--name' }],
    [{ ...valid, path: '/tmp/unreviewed/SKILL.md' }],
    [{ ...valid, scripts: [{ path: 'run.sh', content: 'echo never' }] }],
    [{ ...valid, version: 0 }],
    [{ ...valid, content: 'bad\0content' }],
    [valid, valid],
  ]) {
    const result = await call('/sessions', 'POST', {
      sessionId: 'invalid',
      model: 'test-model',
      skills,
    })
    assert.equal(result.status, 400)
    assert.equal((await result.json()).code, 'runner.invalidRequest')
  }
})

test('authoritative replacement removes failed current input and preserves other sessions', { timeout: 60000 }, async (t) => {
  const { call } = await fixture(t, 'failure')
  const previous = [{ role: 'user', content: 'old question' }, { role: 'assistant', content: 'old answer' }]
  assert.equal((await call('/sessions', 'POST', { sessionId: 'other', model: 'test-model', transcript: previous })).status, 201)
  assert.equal((await call('/sessions', 'POST', { sessionId: 'recover', model: 'test-model', transcript: previous })).status, 201)
  const failed = await call('/sessions/recover/messages', 'POST', { text: 'current once', contextToken: 'capability' })
  assert.match(await failed.text(), /event: error/)
  assert.equal((await call('/sessions', 'POST', { sessionId: 'recover', model: 'test-model', transcript: previous, replace: true })).status, 201)
  const completed = await call('/sessions/recover/messages', 'POST', { text: 'current once', contextToken: 'capability' })
  assert.match(await completed.text(), /event: completed/)
  const recovered = await (await call('/sessions/recover/export')).json()
  assert.equal(recovered.transcript.filter((m: { content: string }) => m.content === 'current once').length, 1)
  assert.deepEqual((await (await call('/sessions/other/export')).json()).transcript, previous)
})


test('owned home keeps no chat JSONL after export and startup only reclaims owned process directories', { timeout: 30000 }, async (t) => {
  const { call, rootDir } = await fixture(t)
  await mkdir(join(rootDir, 'tenant-ABC123'))
  await writeFile(join(rootDir, 'tenant-ABC123', 'stale.jsonl'), 'stale private data')
  await writeFile(join(rootDir, 'unrelated.keep'), 'keep')
  await prepareRunnerRoot(rootDir)
  assert.equal((await readdir(rootDir)).includes('tenant-ABC123'), false)
  assert.equal(await readFile(join(rootDir, 'unrelated.keep'), 'utf8'), 'keep')
  await call('/sessions', 'POST', { sessionId: 'no-files', model: 'test-model' })
  const result=await call('/sessions/no-files/messages', 'POST', { text: 'private-user-content-no-file', contextToken: 'capability' })
  assert.match(await result.text(), /event: completed/)
  const files=await readdir(rootDir, { recursive: true })
  assert.equal(files.some(file => /\.jsonl(?:\.|$)/.test(file)), false)
  for (const file of files) {
    const path=join(rootDir,file)
    const stat=await lstat(path)
    if (stat.isFile()) assert.equal((await readFile(path,'utf8')).includes('private-user-content-no-file'), false)
  }
  assert.equal((await call('/sessions/no-files', 'DELETE')).status,204)
  assert.equal((await call('/sessions/no-files/export')).status,404)
  const unowned=await mkdtemp(join(tmpdir(),'ai-unowned-'))
  try {
    await writeFile(join(unowned,'keep.txt'),'keep')
    await assert.rejects(prepareRunnerRoot(unowned),/not owned/)
    assert.equal(await readFile(join(unowned,'keep.txt'),'utf8'),'keep')
  } finally { await rm(unowned,{recursive:true,force:true}) }
})

test('authoritative admission reuses an identical completed cache without restarting its process', { timeout: 30000 }, async (t) => {
  const { call, base, headers } = await fixture(t)
  await call('/sessions','POST',{sessionId:'same',model:'test-model'})
  await (await call('/sessions/same/messages','POST',{text:'first question',contextToken:'capability'})).text()
  const transcript=(await (await call('/sessions/same/export')).json()).transcript
  const before=(await (await fetch(`${base}/v1/runtime`,{headers})).json()).workers[0].processId
  assert.equal((await call('/sessions','POST',{sessionId:'same',model:'test-model',transcript,replace:true})).status,201)
  const after=(await (await fetch(`${base}/v1/runtime`,{headers})).json()).workers[0].processId
  assert.equal(after,before)
})

test('next explicit turn rebuilds from DB partial assistant history after cancellation', { timeout: 30000 }, async (t) => {
  const { call, requests }=await fixture(t,'hold')
  await call('/sessions','POST',{sessionId:'partial',model:'test-model'})
  const response=await call('/sessions/partial/messages','POST',{text:'incomplete question',contextToken:'capability'})
  const reader=response.body!.getReader()
  let frames=''
  while (!frames.includes('"text":"partial"')) frames+=new TextDecoder().decode((await reader.read()).value)
  await call('/sessions/partial/cancel','POST')
  await reader.cancel()
  const authoritative=[{role:'user',content:'incomplete question'},{role:'assistant',content:'partial'}]
  assert.equal((await call('/sessions','POST',{sessionId:'partial',model:'test-model',transcript:authoritative,replace:true})).status,201)
  assert.match(await (await call('/sessions/partial/messages','POST',{text:'follow up',contextToken:'new-capability'})).text(),/event: completed/)
  assert.ok(JSON.stringify(requests[1]).includes('partial'))
  const exported=(await (await call('/sessions/partial/export')).json()).transcript
  assert.deepEqual(exported.slice(0,2),authoritative)
  assert.equal(exported.filter((item:{content:string})=>item.content==='follow up').length,1)
})
