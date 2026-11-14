import { access, mkdir } from 'node:fs/promises'
import { constants } from 'node:fs'
import { createRunnerServer, createRunnerMetricsServer } from './server.js'

const host = process.env.AI_RUNNER_HOST
const port = Number(process.env.AI_RUNNER_PORT)
const proxyUrl = process.env.AI_SERVICE_PROXY_URL
const rootDir = process.env.AI_RUNNER_ROOT
if (!host || !Number.isInteger(port) || port < 1 || port > 65535 || !proxyUrl || !rootDir) {
  throw new Error(
    'AI_RUNNER_HOST, AI_RUNNER_PORT, AI_SERVICE_PROXY_URL and AI_RUNNER_ROOT are required',
  )
}
await mkdir(rootDir, { recursive: true, mode: 0o700 })
await access(rootDir, constants.W_OK)
const numberOption = (name: string) =>
  process.env[name] === undefined ? undefined : Number(process.env[name])
const server = createRunnerServer({
  serviceToken: process.env.AI_RUNNER_SERVICE_TOKEN ?? '',
  proxyUrl,
  rootDir,
  maxTenants: numberOption('AI_RUNNER_MAX_TENANTS'),
  idleTimeoutMs: numberOption('AI_RUNNER_IDLE_TIMEOUT_MS'),
  memoryMb: numberOption('AI_RUNNER_HEAP_MB'),
  cpuSeconds: numberOption('AI_RUNNER_CPU_SECONDS'),
  maxTurnMs: numberOption('AI_RUNNER_MAX_TURN_MS'),
  rpcTimeoutMs: numberOption('AI_RUNNER_RPC_TIMEOUT_MS'),
})
server.listen(port, host)
const managementPort = numberOption('AI_RUNNER_MANAGEMENT_PORT')
if (managementPort !== undefined && (!Number.isInteger(managementPort) || managementPort < 1 || managementPort > 65535 || managementPort === port))
  throw new Error('Invalid AI_RUNNER_MANAGEMENT_PORT')
const management = managementPort === undefined ? undefined : createRunnerMetricsServer(server)
management?.listen(managementPort, host)
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.once(signal, async () => {
    management?.close()
    server.close()
    await server.closeRuntime()
    server.closeAllConnections()
  })
}
