import assert from 'node:assert/strict'
import { once } from 'node:events'
import { test } from 'node:test'
import { createRunnerServer, createRunnerMetricsServer } from './server.ts'

test('only the AI service can call runtime endpoints; liveness stays public', async (t) => {
  const server = createRunnerServer({ serviceToken: 'contract-service-token' })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(
    () =>
      new Promise<void>((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      ),
  )
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const base = `http://127.0.0.1:${address.port}`
  assert.deepEqual(await (await fetch(`${base}/health`)).json(), { status: 'ok' })
  assert.equal((await fetch(`${base}/v1/tenants/acme/sessions`, { method: 'POST' })).status, 401)
  assert.equal(
    (
      await fetch(`${base}/v1/tenants/acme/shell`, {
        method: 'POST',
        headers: { authorization: 'Bearer contract-service-token' },
      })
    ).status,
    404,
  )
})


test('readiness requires its fixed AI proxy and metrics expose only aggregate process counts', async (t) => {
  const server = createRunnerServer({ serviceToken: 'metrics-contract', proxyUrl: 'http://127.0.0.1:1/internal/runner', rootDir: '/tmp/runner-metrics-contract' })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(async () => { await server.closeRuntime(); server.closeAllConnections(); await new Promise<void>((resolve) => server.close(() => resolve())) })
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const base = `http://127.0.0.1:${address.port}`
  assert.equal((await fetch(`${base}/ready`)).status, 200)
  assert.equal((await fetch(`${base}/metrics`)).status, 401)
  const response = await fetch(`${base}/metrics`, { headers: { authorization: 'Bearer metrics-contract' } })
  assert.equal(response.status, 200)
  assert.match(response.headers.get('content-type') ?? '', /text\/plain/)
  const metrics = await response.text()
  assert.match(metrics, /ai_runner_processes 0/)
  assert.match(metrics, /ai_runner_pool_tenants 0/)
  assert.match(metrics, /ai_runner_pool_capacity 8/)
  assert.doesNotMatch(metrics, /metrics-contract|tenantId|processId|contextToken/)
})


test('monitoring port exposes metrics without granting access to runtime or transcript routes', async (t) => {
  const runner = createRunnerServer({ serviceToken: 'private-runtime-token' })
  const monitoring = createRunnerMetricsServer(runner)
  monitoring.listen(0, '127.0.0.1')
  await once(monitoring, 'listening')
  t.after(async () => { await runner.closeRuntime(); monitoring.closeAllConnections(); await new Promise<void>((resolve) => monitoring.close(() => resolve())) })
  const address = monitoring.address()
  assert.ok(address && typeof address !== 'string')
  const base = `http://127.0.0.1:${address.port}`
  assert.equal((await fetch(`${base}/metrics`)).status, 200)
  assert.equal((await fetch(`${base}/v1/runtime`)).status, 404)
  assert.equal((await fetch(`${base}/v1/tenants/acme/sessions/private/export`)).status, 404)
  assert.equal((await fetch(`${base}/metrics`, { method: 'POST' })).status, 404)
})
