import assert from 'node:assert/strict'
import { once } from 'node:events'
import { test } from 'node:test'
import { createRunnerServer } from './server.ts'

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
