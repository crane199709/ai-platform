#!/usr/bin/env node
// Public Runner HTTP contract + Linux container/resource boundary. No paid provider.
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { once } from 'node:events'
import { writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { promisify } from 'node:util'
const exec = promisify(execFile)
const image = process.env.AI_RUNNER_IMAGE
if (!image) throw new Error('AI_RUNNER_IMAGE is required (an already built immutable local image)')
const name = `ai-runner-contract-${randomUUID()}`
const docker = async (...args) => (await exec('docker', args, { timeout: 45000, maxBuffer: 2 * 1024 * 1024 })).stdout.trim()
let requests = 0
const proxy = createServer(async (request, response) => {
  let body = ''; for await (const chunk of request) body += chunk
  assert.equal(request.url, '/internal/runner/chat/completions')
  assert.equal(request.headers.authorization, 'Bearer isolated-turn-capability')
  assert.equal(request.headers['x-ai-tenant-id'], 'container-tenant')
  requests++
  response.writeHead(200, { 'content-type': 'text/event-stream' })
  for (const content of ['Linux DSH', ' streamed']) {
    response.write(`data: ${JSON.stringify({ id: 'linux-runtime', choices: [{ index: 0, delta: { content }, finish_reason: null }] })}\n\n`)
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  response.end(`data: ${JSON.stringify({ id: 'linux-runtime', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 7, completion_tokens: 2, total_tokens: 9 } })}\n\ndata: [DONE]\n\n`)
})
proxy.listen(0, '0.0.0.0'); await once(proxy, 'listening')
const proxyPort = proxy.address().port
try {
  await docker('run', '--detach', '--rm', '--pull=never', '--name', name,
    '--read-only', '--user', '1000:1000', '--cpus', '1', '--memory', '384m', '--pids-limit', '128',
    '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
    // Official native binaries are integrity-checked and cached in TMPDIR before loading.
    '--tmpfs', '/runtime:rw,nosuid,exec,size=64m,uid=1000,gid=1000,mode=0700',
    '--tmpfs', '/tmp:rw,nosuid,exec,size=64m,uid=1000,gid=1000,mode=0700',
    '-p', '127.0.0.1::8181', '--add-host', 'host.docker.internal:host-gateway',
    '-e', 'AI_RUNNER_HOST=0.0.0.0', '-e', 'AI_RUNNER_PORT=8181', '-e', 'AI_RUNNER_MANAGEMENT_PORT=9091',
    '-e', 'AI_RUNNER_ROOT=/runtime/tenants', '-e', 'AI_RUNNER_SERVICE_TOKEN=image-contract-token',
    '-e', `AI_SERVICE_PROXY_URL=http://host.docker.internal:${proxyPort}/internal/runner`, image)
  const binding = await docker('port', name, '8181/tcp')
  const base = `http://${binding}`
  let ready = false
  for (let attempt = 0; attempt < 60; attempt++) {
    try { ready = (await fetch(`${base}/ready`)).ok } catch {}
    if (ready) break
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  assert.equal(ready, true, await docker('logs', name))
  const headers = { authorization: 'Bearer image-contract-token', 'content-type': 'application/json' }
  const created = await fetch(`${base}/v1/tenants/container-tenant/sessions`, { method: 'POST', headers, body: JSON.stringify({ sessionId: 'container-session', model: 'container-model' }) })
  assert.equal(created.status, 201, await created.text())
  const stream = await fetch(`${base}/v1/tenants/container-tenant/sessions/container-session/messages`, { method: 'POST', headers, body: JSON.stringify({ text: 'verify Linux runtime', contextToken: 'isolated-turn-capability' }) })
  const events = await stream.text()
  assert.equal(stream.status, 200)
  assert.match(events, /event: delta\ndata: \{"text":"Linux DSH"\}/)
  assert.match(events, /event: completed/)
  assert.match(events, /"usageReported":true/)
  assert.equal(requests, 1)
  const snapshot = await (await fetch(`${base}/v1/tenants/container-tenant/sessions/container-session/export`, { headers })).json()
  assert.equal(snapshot.transcript.at(-1).content, 'Linux DSH streamed')
  const runtime = await (await fetch(`${base}/v1/runtime`, { headers })).json()
  assert.equal(runtime.dshVersion, '0.2.2-rc.1')
  const pid = runtime.workers[0].processId
  assert.ok(Number.isSafeInteger(pid) && pid > 0)
  const details = JSON.parse(await docker('exec', name, 'node', '--input-type=module', '-e', `
    import fs from 'node:fs'; import { createRequire } from 'node:module';
    const require=createRequire(import.meta.url);
    const binding=createRequire(require.resolve('@deepseek-ai/dsh/package.json'))('node-addon-require-builtin').getBindingInfo();
    console.log(JSON.stringify({uid:process.getuid(),platform:process.platform,arch:process.arch,binding,memoryMax:fs.readFileSync('/sys/fs/cgroup/memory.max','utf8').trim(),cpuMax:fs.readFileSync('/sys/fs/cgroup/cpu.max','utf8').trim(),limits:fs.readFileSync('/proc/${pid}/limits','utf8'),command:fs.readFileSync('/proc/${pid}/cmdline','utf8').split('\\0'),mounts:fs.readFileSync('/proc/mounts','utf8').split('\\n').map(line=>line.split(' ')).filter(parts=>['/runtime','/tmp'].includes(parts[1])).map(parts=>({path:parts[1],options:parts[3]})),hasModelSecrets:Object.keys(process.env).some(k=>k.startsWith('MODEL_')||k.startsWith('AI_MCP_'))}));`))
  assert.equal(details.uid, 1000)
  assert.equal(details.platform, 'linux')
  assert.equal(details.memoryMax, String(384 * 1024 * 1024))
  assert.equal(details.cpuMax, '100000 100000')
  assert.match(details.limits, /Max open files\s+128\s+128/)
  assert.match(details.limits, /Max cpu time\s+120\s+120/)
  assert.ok(details.command.includes('--max-old-space-size=256'))
  assert.equal(details.hasModelSecrets, false)
  assert.equal(details.mounts.length, 2)
  assert.ok(details.mounts.every(mount => mount.options.split(',').includes('nosuid') && !mount.options.split(',').includes('noexec')))
  const inspect = JSON.parse(await docker('inspect', name))[0]
  assert.equal(inspect.HostConfig.ReadonlyRootfs, true)
  assert.deepEqual(inspect.HostConfig.CapDrop, ['ALL'])
  const evidence = { image, imageId: inspect.Image, dshVersion: runtime.dshVersion, ...details, transcriptVerified: true, streamedChunks: 2, modelRequests: requests, readonlyRootfs: true, networkPolicyValidated: false }
  const destination = process.env.AI_IMAGE_EVIDENCE ?? '/tmp/ai-runner-image.json'
  await writeFile(destination, JSON.stringify(evidence, null, 2))
  console.log(`Runner image real DSH HTTP/resource contract passed: ${destination}`)
} finally {
  await docker('rm', '--force', name).catch(() => {})
  proxy.closeAllConnections(); await new Promise((resolve) => proxy.close(resolve))
}
