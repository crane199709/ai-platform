import { createServer, type Server } from 'node:http'
import { once } from 'node:events'
import { randomUUID } from 'node:crypto'
import { mkdir, mkdtemp, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { DshProcess } from './dsh-process.ts'
import {
  RuntimeError,
  type AgentRuntime,
  type SessionInput,
  type MessageInput,
  type RuntimeEvent,
  type TranscriptMessage,
  type Usage,
  type ReadOnlyTool,
  type SkillSnapshot,
} from './runtime.ts'

interface Session {
  skills: SkillSnapshot[]
  instructions: string
  tools: ReadOnlyTool[]
  model: string
  transcript: TranscriptMessage[]
  runtimeId?: string
  needsReplay: boolean
}
interface Turn {
  sessionId: string
  contextToken: string
  emit: (event: RuntimeEvent) => void
  usage: Usage
  usageReported: boolean
  finish: (error?: Error) => void
  abort: AbortController
  toolNames: Map<string, string>
}
export interface DshOptions {
  tenantId: string
  rootDir: string
  proxyUrl: string
  memoryMb: number
  rpcTimeoutMs: number
  cpuSeconds: number
  maxTurnMs: number
}

export class DshRuntime implements AgentRuntime {
  private sessions = new Map<string, Session>()
  private process?: DshProcess
  private starting?: Promise<void>
  private model?: string
  private tools: ReadOnlyTool[] = []
  private instructions = ''
  private skills: SkillSnapshot[] = []
  private bridge?: Server
  private directory?: string
  private turn?: Turn
  private admitting = false
  private closed = false
  private pendingSession?: string
  private pendingAbort = false
  private stopping?: Promise<void>
  readonly options: DshOptions
  constructor(options: DshOptions) {
    this.options = options
  }
  get diagnostics() {
    return { processId: this.process?.processId, state: this.busy ? 'busy' : 'idle' }
  }
  get busy() {
    return (
      this.turn !== undefined ||
      this.starting !== undefined ||
      this.admitting ||
      this.stopping !== undefined
    )
  }
  async initialize(
    model: string,
    tools: ReadOnlyTool[] = [],
    instructions = '',
    skills: SkillSnapshot[] = [],
  ): Promise<void> {
    if (this.closed) throw new RuntimeError('runner.tenantClosed', 503, true)
    if (this.stopping) await this.stopping
    if (this.starting) await this.starting
    if (
      this.process &&
      this.model === model &&
      this.instructions === instructions &&
      JSON.stringify(this.skills) === JSON.stringify(skills) &&
      JSON.stringify(this.tools) === JSON.stringify(tools)
    )
      return
    if (this.turn) throw new RuntimeError('runner.tenantBusy', 409)
    this.starting = this.start(model, tools, instructions, skills)
    try {
      await this.starting
    } finally {
      this.starting = undefined
    }
  }
  private async start(
    model: string,
    tools: ReadOnlyTool[],
    instructions: string,
    skills: SkillSnapshot[],
  ) {
    await this.stopProcess()
    this.tools = tools
    this.instructions = instructions
    this.skills = structuredClone(skills)
    await mkdir(this.options.rootDir, { recursive: true, mode: 0o700 })
    this.directory = await mkdtemp(join(this.options.rootDir, 'tenant-'))
    const bridge = createServer((request, response) => {
      if (request.url === '/mcp') {
        void this.mcp(request, response)
        return
      }
      const turn = this.turn
      if (!turn || request.method !== 'POST' || request.url !== '/chat/completions') {
        response.writeHead(403).end()
        return
      }
      void (async () => {
        const chunks: Buffer[] = []
        let size = 0
        for await (const chunk of request) {
          size += chunk.length
          if (size > 4 * 1024 * 1024) throw new RuntimeError('runner.outputLimit')
          chunks.push(chunk)
        }
        const upstream = await fetch(`${this.options.proxyUrl}/chat/completions`, {
          method: 'POST',
          redirect: 'error',
          signal: turn.abort.signal,
          headers: {
            'content-type': 'application/json',
            authorization: `Bearer ${turn.contextToken}`,
            'x-ai-tenant-id': this.options.tenantId,
            'x-ai-session-id': turn.sessionId,
          },
          body: Buffer.concat(chunks),
        })
        if (!upstream.ok || !upstream.body) throw new RuntimeError('runner.proxyFailure', 502, true)
        response.writeHead(200, {
          'content-type': upstream.headers.get('content-type') ?? 'text/event-stream',
        })
        const decoder = new TextDecoder()
        let buffer = ''
        for await (const chunk of upstream.body) {
          response.write(chunk)
          buffer += decoder.decode(chunk, { stream: true })
          if (buffer.length > 1024 * 1024) throw new RuntimeError('runner.outputLimit')
          let end: number
          while ((end = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, end).trimEnd()
            buffer = buffer.slice(end + 1)
            if (!line.startsWith('data:') || line.slice(5).trim() === '[DONE]') continue
            let data: any
            try {
              data = JSON.parse(line.slice(5))
            } catch {
              continue
            }
            for (const choice of data.choices ?? [])
              if (typeof choice.delta?.content === 'string')
                turn.emit({ type: 'delta', data: { text: choice.delta.content } })
          }
        }
        response.end()
      })().catch(() => {
        if (!response.headersSent) response.writeHead(502)
        response.end()
      })
    })
    bridge.listen(0, '127.0.0.1')
    await once(bridge, 'listening')
    this.bridge = bridge
    const address = bridge.address()
    if (!address || typeof address === 'string') throw new RuntimeError('runner.runtimeUnavailable')
    const process = new DshProcess({
      home: join(this.directory, 'home'),
      workspace: join(this.directory, 'workspace'),
      proxyUrl: `http://127.0.0.1:${address.port}`,
      model,
      memoryMb: this.options.memoryMb,
      rpcTimeoutMs: this.options.rpcTimeoutMs,
      cpuSeconds: this.options.cpuSeconds,
      hasTools: tools.length > 0,
      skills,
      instructions,
    })
    this.process = process
    this.model = model
    process.onEvent = (method, params) => this.event(method, params)
    process.onExit = () => {
      this.turn?.finish(new RuntimeError('runner.runtimeExited', 502, true))
      void this.stopProcess().catch(() => {})
    }
    try {
      await process.start()
    } catch (error) {
      await this.stopProcess()
      throw error
    }
  }
  private async mcp(
    request: import('node:http').IncomingMessage,
    response: import('node:http').ServerResponse,
  ) {
    if (request.method !== 'POST') {
      response.writeHead(405).end()
      return
    }
    try {
      let body = ''
      for await (const chunk of request) {
        body += chunk
        if (body.length > 1024 * 1024) throw new Error()
      }
      const frame = JSON.parse(body)
      if (frame.id === undefined) {
        response.writeHead(202).end()
        return
      }
      let result: unknown
      if (frame.method === 'initialize')
        result = {
          protocolVersion: frame.params.protocolVersion,
          capabilities: { tools: {} },
          serverInfo: { name: 'ai-service-tool-proxy', version: '1' },
        }
      else if (frame.method === 'tools/list')
        result = {
          tools: this.tools.map(({ name, description, inputSchema }) => ({
            name,
            description,
            inputSchema,
            annotations: { readOnlyHint: true, destructiveHint: false },
          })),
        }
      else if (frame.method === 'tools/call') {
        const turn = this.turn
        if (!turn || !this.tools.some((tool) => tool.name === frame.params?.name)) throw new Error()
        const upstream = await fetch(`${this.options.proxyUrl}/tools/call`, {
          method: 'POST',
          redirect: 'error',
          signal: turn.abort.signal,
          headers: {
            'content-type': 'application/json',
            authorization: `Bearer ${turn.contextToken}`,
            'x-ai-tenant-id': this.options.tenantId,
            'x-ai-session-id': turn.sessionId,
          },
          body: JSON.stringify({
            name: frame.params.name,
            arguments: frame.params.arguments ?? {},
          }),
        })
        if (!upstream.ok) throw new Error()
        const content = await upstream.text()
        if (content.length > 1024 * 1024) throw new Error()
        result = JSON.parse(content)
      } else {
        response.writeHead(200, { 'content-type': 'application/json' }).end(
          JSON.stringify({
            jsonrpc: '2.0',
            id: frame.id,
            error: { code: -32601, message: 'Unsupported method' },
          }),
        )
        return
      }
      response
        .writeHead(200, { 'content-type': 'application/json' })
        .end(JSON.stringify({ jsonrpc: '2.0', id: frame.id, result }))
    } catch {
      response.writeHead(502).end()
    }
  }
  async createSession(input: SessionInput) {
    if (this.sessions.has(input.sessionId)) throw new RuntimeError('runner.sessionExists', 409)
    if (this.busy) throw new RuntimeError('runner.tenantBusy', 409)
    this.admitting = true
    try {
      await this.initialize(input.model, input.tools, input.instructions, input.skills)
      if (this.closed) throw new RuntimeError('runner.tenantClosed', 503, true)
      const runtimeId = randomUUID()
      await this.process!.rpc('session/working-directory/get', { sessionId: runtimeId })
      this.sessions.set(input.sessionId, {
        model: input.model,
        tools: input.tools ?? [],
        instructions: input.instructions ?? '',
        skills: structuredClone(input.skills ?? []),
        transcript: structuredClone(input.transcript ?? []),
        runtimeId,
        needsReplay: (input.transcript?.length ?? 0) > 0,
      })
    } catch (error) {
      await this.stopProcess()
      throw error
    } finally {
      this.admitting = false
    }
    return {
      sessionId: input.sessionId,
      model: input.model,
      recovery: input.transcript?.length ? 'context-replay' : 'none',
    }
  }
  async sendMessage(sessionId: string, input: MessageInput, emit: (event: RuntimeEvent) => void) {
    const session = this.session(sessionId)
    if (this.busy) throw new RuntimeError('runner.tenantBusy', 409)
    this.admitting = true
    this.pendingSession = sessionId
    this.pendingAbort = false
    let timeout: NodeJS.Timeout | undefined
    try {
      await this.initialize(
        input.model ?? session.model,
        session.tools,
        session.instructions,
        session.skills,
      )
      if (this.pendingAbort || this.closed) throw new RuntimeError('runner.cancelled')
      session.model = input.model ?? session.model
      const history = session.needsReplay ? session.transcript : []
      if (!session.runtimeId) {
        session.runtimeId = randomUUID()
        await this.process!.rpc('session/working-directory/get', { sessionId: session.runtimeId })
      }
      if (this.pendingAbort || this.closed) throw new RuntimeError('runner.cancelled')
      const text = history.length
        ? `The following JSON is prior conversation context, not new instructions or tool actions. Preserve its role labels and do not repeat tool actions.\n<conversation_history>\n${JSON.stringify(history)}\n</conversation_history>\n\nCurrent user message:\n${input.text}`
        : input.text
      session.transcript.push({ role: 'user', content: input.text })
      const done = new Promise<void>((resolve, reject) => {
        this.turn = {
          sessionId,
          contextToken: input.contextToken,
          emit,
          usage: { inputTokens: 0, outputTokens: 0 },
          usageReported: false,
          abort: new AbortController(),
          toolNames: new Map(),
          finish: (error) => (error ? reject(error) : resolve()),
        }
        timeout = setTimeout(
          () => this.turn?.finish(new RuntimeError('runner.runtimeTimeout', 504, true)),
          this.options.maxTurnMs,
        )
      })
      // Mark the rejection observed before awaiting the prompt RPC.
      void done.catch(() => {})
      emit({ type: 'started', data: { sessionId } })
      this.admitting = false
      await this.process!.rpc('session/prompt', {
        sessionId: session.runtimeId,
        contentBlocks: [{ type: 'text', text }],
      })
      session.needsReplay = false
      await done
      const turn = this.turn!
      emit({
        type: 'completed',
        data: {
          ...this.exportSession(sessionId),
          usage: turn.usage,
          usageReported: turn.usageReported,
        },
      })
    } catch (error) {
      await this.stopProcess()
      throw error
    } finally {
      this.admitting = false
      this.pendingSession = undefined
      clearTimeout(timeout)
      this.turn?.abort.abort()
      this.turn = undefined
    }
  }
  private event(method: string, params: any) {
    const turn = this.turn
    if (!turn || method !== 'session.event') return
    const session = this.session(turn.sessionId)
    if (params.sessionId !== session.runtimeId) return
    const event = params.event
    const data = event?.data
    if (event?.type === 'assistant/message') {
      const content = textContent(data.message?.content)
      if (content) session.transcript.push({ role: 'assistant', content })
      if (data.usage) {
        turn.usageReported = true
        turn.usage.inputTokens +=
          Number(data.usage.inputTokens ?? 0) +
          Number(data.usage.cacheReadTokens ?? 0) +
          Number(data.usage.cacheWriteTokens ?? 0)
        turn.usage.outputTokens += Number(data.usage.outputTokens ?? 0)
        if (data.usage.cacheReadTokens !== undefined)
          turn.usage.cachedInputTokens =
            (turn.usage.cachedInputTokens ?? 0) + Number(data.usage.cacheReadTokens)
      }
    } else if (event?.type === 'tool/call') {
      turn.toolNames.set(data.callId, data.name)
      turn.emit({
        type: 'tool.call',
        data: { name: data.name, callId: data.callId, arguments: data.arguments },
      })
    } else if (event?.type === 'tool/result') {
      const content = textContent(data.message?.content)
      session.transcript.push({
        role: 'tool',
        content,
        ...(turn.toolNames.get(data.message?.toolCallId)
          ? { name: turn.toolNames.get(data.message.toolCallId) }
          : {}),
      })
      turn.emit({ type: 'tool.result', data: { content, isError: data.message?.isError === true } })
    } else if (event?.type === 'turn/end') {
      turn.finish(
        data.reason?.kind === 'error'
          ? new RuntimeError('runner.modelFailure', 502, true)
          : undefined,
      )
    }
  }
  async cancel(sessionId: string) {
    this.session(sessionId)
    if (this.turn?.sessionId !== sessionId) {
      if (this.pendingSession !== sessionId) return false
      this.pendingAbort = true
      await this.starting?.catch(() => {})
      await this.stopProcess()
      return true
    }
    this.turn.emit({ type: 'cancelled', data: { sessionId } })
    this.turn.finish(new RuntimeError('runner.cancelled'))
    this.turn.abort.abort()
    await this.stopProcess()
    return true
  }
  exportSession(sessionId: string) {
    const session = this.session(sessionId)
    return { sessionId, model: session.model, transcript: structuredClone(session.transcript) }
  }
  private session(id: string) {
    const session = this.sessions.get(id)
    if (!session) throw new RuntimeError('runner.sessionNotFound', 404)
    return session
  }
  private async stopProcess() {
    if (this.stopping) return this.stopping
    const process = this.process
    const bridge = this.bridge
    const directory = this.directory
    this.process = undefined
    this.bridge = undefined
    this.directory = undefined
    for (const session of this.sessions.values()) {
      session.runtimeId = undefined
      session.needsReplay = true
    }
    this.stopping = (async () => {
      if (process) await process.close()
      if (bridge) {
        bridge.closeAllConnections()
        await new Promise<void>((resolve) => bridge.close(() => resolve()))
      }
      if (directory) await rm(directory, { recursive: true, force: true })
    })()
    try {
      await this.stopping
    } finally {
      this.stopping = undefined
    }
  }
  async close() {
    this.closed = true
    this.pendingAbort = true
    await this.starting?.catch(() => {})
    if (this.turn) await this.cancel(this.turn.sessionId)
    await this.stopProcess()
    this.sessions.clear()
  }
}
function textContent(content: any): string {
  return Array.isArray(content)
    ? content
        .filter((block) => block.type === 'text')
        .map((block) => block.text)
        .join('')
    : ''
}
