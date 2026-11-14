import assert from 'node:assert/strict'
import { once } from 'node:events'
import { test } from 'node:test'
import { createRunnerServer } from './server.ts'

test('runner exposes liveness without exposing an agent or shell endpoint', async (t) => {
  const server = createRunnerServer()
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
  const health = await fetch(`${base}/health`)
  assert.equal(health.status, 200)
  assert.deepEqual(await health.json(), { status: 'ok' })
  const unavailable = await fetch(`${base}/sessions`, { method: 'POST' })
  assert.equal(unavailable.status, 404)
})
