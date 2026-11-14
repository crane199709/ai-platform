import { createServer } from 'node:http'

/** Liveness only; agent execution is introduced by issue 03. */
export function createRunnerServer() {
  return createServer((request, response) => {
    response.setHeader('Content-Type', 'application/json')
    if (request.method === 'GET' && request.url === '/health') {
      response.writeHead(200).end(JSON.stringify({ status: 'ok' }))
      return
    }
    response.writeHead(404).end(JSON.stringify({ code: 'runner.notFound' }))
  })
}
