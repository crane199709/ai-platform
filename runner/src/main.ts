import { createRunnerServer } from './server.js'

const host = process.env.AI_RUNNER_HOST
const port = Number(process.env.AI_RUNNER_PORT)
if (!host || !Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('AI_RUNNER_HOST and a valid AI_RUNNER_PORT are required')
}
const server = createRunnerServer()
server.listen(port, host)
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.on(signal, () => server.close())
}
