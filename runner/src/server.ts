import { timingSafeEqual } from 'node:crypto'
import { createServer, type IncomingMessage, type Server } from 'node:http'
import { DshRuntime } from './dsh-runtime.ts'
import { DSH_PROFILE, DSH_VERSION } from './dsh-process.ts'
import {
  RuntimeError,
  type SessionInput,
  type MessageInput,
  type TranscriptMessage,
  type ReadOnlyTool,
  type SkillSnapshot,
} from './runtime.ts'

export interface RunnerOptions {
  serviceToken: string
  proxyUrl?: string
  rootDir?: string
  memoryMb?: number
  rpcTimeoutMs?: number
  maxTurnMs?: number
  cpuSeconds?: number
  maxTenants?: number
  idleTimeoutMs?: number
}
export type RunnerServer = Server & { closeRuntime(): Promise<void> }
export function createRunnerServer(options: RunnerOptions): RunnerServer {
  if (!options.serviceToken) throw new Error('AI_RUNNER_SERVICE_TOKEN is required')
  if (options.proxyUrl) {
    const url = new URL(options.proxyUrl)
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash
    )
      throw new Error('Invalid AI_SERVICE_PROXY_URL')
  }
  const maxTenants = options.maxTenants ?? 8
  const idleTimeoutMs = options.idleTimeoutMs ?? 15 * 60 * 1000
  for (const value of [
    maxTenants,
    idleTimeoutMs,
    options.memoryMb ?? 256,
    options.rpcTimeoutMs ?? 15000,
    options.cpuSeconds ?? 120,
    options.maxTurnMs ?? 120000,
  ])
    if (!Number.isSafeInteger(value) || value <= 0) throw new Error('Invalid Runner limit')
  const tenants = new Map<string, DshRuntime>()
  const lastUsed = new Map<string, number>()
  const reaping = new Set<string>()
  const reaper = setInterval(
    () => {
      for (const [id, runtime] of tenants) {
        if (
          !runtime.busy &&
          !reaping.has(id) &&
          Date.now() - (lastUsed.get(id) ?? Date.now()) >= idleTimeoutMs
        ) {
          reaping.add(id)
          void runtime.close().finally(() => {
            tenants.delete(id)
            lastUsed.delete(id)
            reaping.delete(id)
          })
        }
      }
    },
    Math.max(10, Math.min(1000, idleTimeoutMs / 2)),
  )
  reaper.unref()
  const server = createServer((request, response) => {
    void (async () => {
      const json = (status: number, data: unknown) => {
        response.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(data))
      }
      if (request.method === 'GET' && request.url === '/health') {
        json(200, { status: 'ok' })
        return
      }
      const provided = Buffer.from(request.headers.authorization ?? '')
      const expected = Buffer.from(`Bearer ${options.serviceToken}`)
      if (provided.length !== expected.length || !timingSafeEqual(provided, expected)) {
        json(401, { code: 'runner.unauthorized', retryable: false })
        return
      }
      if (request.method === 'GET' && request.url === '/v1/runtime') {
        json(200, {
          dshVersion: DSH_VERSION,
          profile: DSH_PROFILE,
          tenants: tenants.size,
          workers: [...tenants].map(([tenantId, runtime]) => ({
            tenantId,
            ...runtime.diagnostics,
          })),
          limits: {
            maxTenants,
            idleTimeoutMs,
            nodeOldSpaceMb: options.memoryMb ?? 256,
            cpuSeconds: options.cpuSeconds ?? 120,
            nodeSemiSpaceMb: 8,
            openFiles: 128,
            maxTurnMs: options.maxTurnMs ?? 120000,
          },
        })
        return
      }
      const route =
        /^\/v1\/tenants\/([A-Za-z0-9_-]{1,128})(?:\/sessions(?:\/([A-Za-z0-9_-]{1,128})(?:\/(messages|cancel|export))?)?)?$/.exec(
          request.url ?? '',
        )
      if (!route) {
        json(404, { code: 'runner.notFound', retryable: false })
        return
      }
      const [, tenantId, sessionId, action] = route
      if (reaping.has(tenantId)) throw new RuntimeError('runner.tenantBusy', 409)
      let runtime = tenants.get(tenantId)
      if (runtime) lastUsed.set(tenantId, Date.now())
      response.once('finish', () => {
        if (tenants.has(tenantId)) lastUsed.set(tenantId, Date.now())
      })
      if (request.method === 'POST' && request.url?.endsWith('/sessions')) {
        const body = await readJson(request)
        keys(body, ['sessionId', 'model', 'transcript', 'tools', 'instructions', 'skills'])
        identifier(body.sessionId)
        model(body.model)
        if (
          body.instructions !== undefined &&
          (typeof body.instructions !== 'string' || body.instructions.length > 128000)
        )
          throw new RuntimeError('runner.invalidRequest', 400)
        const transcript = parseTranscript(body.transcript)
        const tools = parseTools(body.tools)
        const skills = parseSkills(body.skills)
        runtime = tenants.get(tenantId)
        if (reaping.has(tenantId)) throw new RuntimeError('runner.tenantBusy', 409)
        const isNewTenant = !runtime
        if (!runtime) {
          if (!options.rootDir || !options.proxyUrl)
            throw new RuntimeError('runner.notConfigured', 503)
          if (tenants.size >= maxTenants) {
            response.setHeader('Retry-After', '5')
            throw new RuntimeError('runner.poolFull', 429, true)
          }
          runtime = new DshRuntime({
            tenantId,
            rootDir: options.rootDir,
            proxyUrl: options.proxyUrl.replace(/\/$/, ''),
            memoryMb: options.memoryMb ?? 256,
            rpcTimeoutMs: options.rpcTimeoutMs ?? 15000,
            cpuSeconds: options.cpuSeconds ?? 120,
            maxTurnMs: options.maxTurnMs ?? 120000,
          })
          tenants.set(tenantId, runtime)
          lastUsed.set(tenantId, Date.now())
        }
        const input: SessionInput = {
          sessionId: body.sessionId,
          model: body.model,
          transcript,
          tools,
          instructions: body.instructions,
          skills,
        }
        try {
          json(201, await runtime.createSession(input))
        } catch (error) {
          if (isNewTenant) {
            await runtime.close()
            tenants.delete(tenantId)
            lastUsed.delete(tenantId)
          }
          throw error
        }
      } else if (request.method === 'DELETE' && !sessionId && !request.url?.endsWith('/sessions')) {
        await runtime?.close()
        tenants.delete(tenantId)
        response.writeHead(204).end()
      } else if (!runtime || !sessionId) throw new RuntimeError('runner.sessionNotFound', 404)
      else if (request.method === 'GET' && action === 'export')
        json(200, runtime.exportSession(sessionId))
      else if (request.method === 'POST' && action === 'cancel')
        json(200, { cancelled: await runtime.cancel(sessionId) })
      else if (request.method === 'POST' && action === 'messages') {
        const body = await readJson(request)
        keys(body, ['text', 'contextToken', 'model'])
        if (
          typeof body.text !== 'string' ||
          !body.text.trim() ||
          body.text.length > 128000 ||
          typeof body.contextToken !== 'string' ||
          !/^[\x21-\x7e]{1,8192}$/.test(body.contextToken)
        )
          throw new RuntimeError('runner.invalidRequest', 400)
        if (body.model !== undefined) model(body.model)
        runtime.exportSession(sessionId)
        if (runtime.busy) throw new RuntimeError('runner.tenantBusy', 409)
        const input: MessageInput = {
          text: body.text,
          contextToken: body.contextToken,
          model: body.model,
        }
        let terminal = false
        const emit = (event: { type: string; data: unknown }) => {
          if (terminal || response.destroyed) return
          if (!response.headersSent)
            response.writeHead(200, {
              'content-type': 'text/event-stream',
              'cache-control': 'no-cache',
              'x-accel-buffering': 'no',
            })
          response.write(`event: ${event.type}\ndata: ${JSON.stringify(event.data)}\n\n`)
          if (['completed', 'cancelled', 'error'].includes(event.type)) terminal = true
        }
        const disconnect = () => {
          if (!terminal) void runtime!.cancel(sessionId).catch(() => {})
        }
        response.on('close', disconnect)
        try {
          await runtime.sendMessage(sessionId, input, emit)
        } catch (error) {
          if (!response.headersSent) throw error
          if (!terminal) emit({ type: 'error', data: errorData(error) })
        } finally {
          response.off('close', disconnect)
          if (response.headersSent) response.end()
        }
      } else json(404, { code: 'runner.notFound', retryable: false })
    })().catch((error) => {
      if (!response.headersSent) {
        const status = error instanceof RuntimeError ? error.status : 500
        response
          .writeHead(status, { 'content-type': 'application/json' })
          .end(JSON.stringify(errorData(error)))
      } else response.end()
    })
  }) as RunnerServer
  server.closeRuntime = async () => {
    clearInterval(reaper)
    await Promise.all([...tenants.values()].map((runtime) => runtime.close()))
    tenants.clear()
  }
  return server
}
function errorData(error: unknown) {
  return {
    code: error instanceof RuntimeError ? error.code : 'runner.internalError',
    retryable: error instanceof RuntimeError && error.retryable,
  }
}
async function readJson(request: IncomingMessage): Promise<Record<string, any>> {
  if (!request.headers['content-type']?.startsWith('application/json'))
    throw new RuntimeError('runner.invalidRequest', 400)
  if (Number(request.headers['content-length'] ?? 0) > 1024 * 1024)
    throw new RuntimeError('runner.requestTooLarge', 413)
  let size = 0
  const chunks: Buffer[] = []
  for await (const chunk of request) {
    size += chunk.length
    if (size > 1024 * 1024) throw new RuntimeError('runner.requestTooLarge', 413)
    chunks.push(chunk)
  }
  try {
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    if (!body || Array.isArray(body) || typeof body !== 'object') throw new Error()
    return body
  } catch {
    throw new RuntimeError('runner.invalidRequest', 400)
  }
}
function keys(body: Record<string, unknown>, allowed: string[]) {
  if (Object.keys(body).some((key) => !allowed.includes(key)))
    throw new RuntimeError('runner.invalidRequest', 400)
}
function identifier(value: unknown): asserts value is string {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(value))
    throw new RuntimeError('runner.invalidRequest', 400)
}
function model(value: unknown): asserts value is string {
  if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_./:-]{0,127}$/.test(value))
    throw new RuntimeError('runner.invalidRequest', 400)
}
function parseTranscript(value: unknown): TranscriptMessage[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > 1000)
    throw new RuntimeError('runner.invalidRequest', 400)
  return value.map((message) => {
    if (!message || typeof message !== 'object' || Array.isArray(message))
      throw new RuntimeError('runner.invalidRequest', 400)
    keys(message, ['role', 'content', 'name'])
    if (
      !['user', 'assistant', 'tool'].includes(message.role) ||
      typeof message.content !== 'string' ||
      message.content.length > 128000 ||
      (message.name !== undefined &&
        (typeof message.name !== 'string' || message.name.length > 128))
    )
      throw new RuntimeError('runner.invalidRequest', 400)
    return {
      role: message.role,
      content: message.content,
      ...(message.name === undefined ? {} : { name: message.name }),
    }
  })
}

function parseTools(value: unknown): ReadOnlyTool[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > 32)
    throw new RuntimeError('runner.invalidRequest', 400)
  const names = new Set<string>()
  return value.map((tool) => {
    if (!tool || typeof tool !== 'object' || Array.isArray(tool))
      throw new RuntimeError('runner.invalidRequest', 400)
    keys(tool, ['name', 'description', 'inputSchema', 'readOnly'])
    if (
      typeof tool.name !== 'string' ||
      !/^[A-Za-z0-9_-]{1,64}$/.test(tool.name) ||
      names.has(tool.name) ||
      typeof tool.description !== 'string' ||
      tool.description.length > 4096 ||
      tool.readOnly !== true ||
      !tool.inputSchema ||
      typeof tool.inputSchema !== 'object' ||
      Array.isArray(tool.inputSchema) ||
      tool.inputSchema.type !== 'object'
    )
      throw new RuntimeError('runner.invalidRequest', 400)
    names.add(tool.name)
    return {
      name: tool.name,
      description: tool.description,
      inputSchema: tool.inputSchema,
      readOnly: true,
    }
  })
}

function parseSkills(value: unknown): SkillSnapshot[] {
  if (value === undefined) return []
  if (
    !Array.isArray(value) ||
    value.length > 32 ||
    Buffer.byteLength(JSON.stringify(value)) > 512 * 1024
  )
    throw new RuntimeError('runner.invalidRequest', 400)
  const codes = new Set<string>()
  const ids = new Set<string>()
  return value.map((skill) => {
    if (!skill || typeof skill !== 'object' || Array.isArray(skill))
      throw new RuntimeError('runner.invalidRequest', 400)
    keys(skill, ['catalogId', 'code', 'name', 'description', 'version', 'content'])
    if (
      typeof skill.catalogId !== 'string' ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(skill.catalogId) ||
      ids.has(skill.catalogId) ||
      typeof skill.code !== 'string' ||
      skill.code.length > 64 ||
      !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(skill.code) ||
      codes.has(skill.code) ||
      typeof skill.name !== 'string' ||
      !skill.name.trim() ||
      skill.name.length > 120 ||
      typeof skill.description !== 'string' ||
      !skill.description.trim() ||
      skill.description.length > 1000 ||
      !Number.isSafeInteger(skill.version) ||
      skill.version < 1 ||
      typeof skill.content !== 'string' ||
      !skill.content.trim() ||
      skill.content.length > 64000 ||
      skill.content.includes('\0')
    )
      throw new RuntimeError('runner.invalidRequest', 400)
    ids.add(skill.catalogId)
    codes.add(skill.code)
    return {
      catalogId: skill.catalogId,
      code: skill.code,
      name: skill.name,
      description: skill.description,
      version: skill.version,
      content: skill.content,
    }
  })
}
