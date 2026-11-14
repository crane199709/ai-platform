import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { RuntimeError } from './runtime.ts'

export const DSH_VERSION = '0.2.2-rc.1'
export const DSH_PROFILE = 'sdk'

// Every model-facing native tool in the pinned sdk composition is disabled.
// The only tool ingress is our explicitly mounted AI-service MCP bridge.
const disabled = [
  'tool-plugin-manager',
  'tool-working-directory',
  'tool-bash',
  'tool-pwsh',
  'tool-jobs',
  'tool-fs',
  'tool-fs-search',
  'tool-skill',
  'tool-agent-team',
  'tool-subagent-control',
  'tool-subagent',
  'tool-subagent-fork',
  'tool-workflow',
  'tool-todo',
  'tool-goal',
  'tool-web',
  'tool-schedule',
  'tool-ask-user',
  'present',
  'tool-office',
  'workspace-dependencies',
  'skill-office',
  'skill-filesystem',
  'agent-instructions',
  'schedule',
  'goal-round-driver',
  'session-title-llm',
  'llm-deepseek',
  'llm-deepseek-account',
  'deepseek-account',
  'session-persistence-jsonl',
  'session-projection-cache',
  'mcp-resources',
]

export class DshProcess {
  private child?: ChildProcessWithoutNullStreams
  private pending = new Map<
    number,
    { resolve: (value: any) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }
  >()
  private nextId = 0
  private buffer = ''
  private stopping = false
  private exited?: Promise<void>
  onEvent: (method: string, params: any) => void = () => {}
  onExit: () => void = () => {}
  readonly options: {
    home: string
    workspace: string
    proxyUrl: string
    model: string
    memoryMb: number
    rpcTimeoutMs: number
    cpuSeconds: number
    hasTools: boolean
    instructions?: string
  }
  constructor(options: DshProcess['options']) {
    this.options = options
  }
  get processId() {
    return this.child?.pid
  }
  async start() {
    const { home, workspace, proxyUrl, model, memoryMb, cpuSeconds } = this.options
    await mkdir(home, { recursive: true, mode: 0o700 })
    await mkdir(workspace, { recursive: true, mode: 0o700 })
    const patch = join(home, 'runner.patch.json')
    await writeFile(
      patch,
      JSON.stringify([
        ...disabled.map((id) => ({ id, disabled: true })),
        ...(this.options.instructions
          ? [{ id: 'system-prompt', config: { personaPrefix: this.options.instructions } }]
          : []),
        ...(this.options.hasTools
          ? [
              {
                insert: [
                  {
                    id: 'ai-service-tools',
                    name: '@deepseek-ai/dsh-mcp-client',
                    config: {
                      serverName: 'ai_service',
                      transport: 'streamable-http',
                      url: `${proxyUrl}/mcp`,
                      startupTimeoutMs: 5000,
                      toolCallTimeoutMs: 30000,
                      reconnect: { enabled: false },
                      failOnStartupError: true,
                    },
                  },
                ],
              },
            ]
          : []),
        {
          id: 'llm-pi-ai',
          config: {
            providers: {
              'ai-service': {
                api: 'openai-completions',
                apiKeyEnv: 'AI_PROXY_PLACEHOLDER',
                baseURL: proxyUrl,
                models: [{ id: model, contextWindow: 128000, maxTokens: 4096 }],
                retryPolicy: { mode: 'normal', maxRetries: 0 },
              },
            },
          },
        },
      ]),
      { mode: 0o600 },
    )
    const pkg = createRequire(import.meta.url).resolve('@deepseek-ai/dsh/package.json')
    if (JSON.parse(await readFile(pkg, 'utf8')).version !== DSH_VERSION)
      throw new RuntimeError('runner.protocolMismatch')
    const bin = join(dirname(pkg), 'lib/bin.js')
    // Shell text is constant; caller data is passed only as positional argv.
    const child = spawn(
      '/bin/sh',
      [
        '-c',
        'set -e; ulimit -n 128; ulimit -t "$1"; shift; exec "$@"',
        'runner',
        String(cpuSeconds),
        process.execPath,
        `--max-old-space-size=${memoryMb}`,
        '--max-semi-space-size=8',
        bin,
        '--profile',
        DSH_PROFILE,
        '--patch',
        patch,
      ],
      {
        cwd: workspace,
        detached: true,
        env: {
          PATH: dirname(process.execPath),
          HOME: home,
          TMPDIR: workspace,
          DSH_HOME: home,
          DSH_AGENTS_HOME: join(home, 'agents'),
          DSH_TELEMETRY_DISABLED: '1',
          DSH_PRIMARY_RUNTIME: '',
          AI_PROXY_PLACEHOLDER: 'non-secret-proxy-placeholder',
          LANG: 'C.UTF-8',
        },
        stdio: ['pipe', 'pipe', 'pipe'],
      },
    )
    this.child = child
    this.exited = new Promise((resolve) => child.once('close', () => resolve()))
    child.stdout.setEncoding('utf8')
    child.stdout.on('data', (chunk) => this.read(String(chunk)))
    // Drain diagnostics without retaining prompt text, credentials or upstream error bodies.
    child.stderr.resume()
    child.on('error', () => this.fail(new RuntimeError('runner.runtimeUnavailable', 502, true)))
    child.on('exit', () => {
      this.fail(new RuntimeError('runner.runtimeExited', 502, true))
      if (!this.stopping) this.onExit()
    })
    const result = await this.rpc('initialize', {
      cwd: workspace,
      provider: 'ai-service',
      model,
      approvalRequests: true,
      maxTokens: 4096,
    })
    if (result?.serverInfo?.name !== 'deepseek-harness-sdk-runtime')
      throw new RuntimeError('runner.protocolMismatch')
  }
  rpc(method: string, params?: unknown): Promise<any> {
    const id = ++this.nextId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new RuntimeError('runner.runtimeTimeout', 504, true))
      }, this.options.rpcTimeoutMs)
      this.pending.set(id, { resolve, reject, timer })
      this.child?.stdin.write(
        `${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`,
        (error) => {
          if (error) this.fail(new RuntimeError('runner.runtimeExited', 502, true))
        },
      )
    })
  }
  private read(chunk: string) {
    this.buffer += chunk
    if (this.buffer.length > 8 * 1024 * 1024) {
      this.fail(new RuntimeError('runner.outputLimit'))
      void this.close()
      return
    }
    let index: number
    while ((index = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, index)
      this.buffer = this.buffer.slice(index + 1)
      let frame: any
      try {
        frame = JSON.parse(line)
      } catch {
        this.fail(new RuntimeError('runner.protocolMismatch'))
        void this.close()
        return
      }
      if (frame.method && frame.id !== undefined) {
        // No native tool grants or interactive questions are permitted.
        const result =
          frame.method === 'approval/request' ? { outcome: 'rejected' } : { cancelled: true }
        this.child?.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id: frame.id, result })}\n`)
      } else if (frame.method) this.onEvent(frame.method, frame.params)
      else {
        const pending = this.pending.get(frame.id)
        if (!pending) continue
        clearTimeout(pending.timer)
        this.pending.delete(frame.id)
        if (frame.error) pending.reject(new RuntimeError('runner.runtimeFailure', 502, true))
        else pending.resolve(frame.result)
      }
    }
  }
  private fail(error: Error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer)
      pending.reject(error)
    }
    this.pending.clear()
  }
  async close() {
    const child = this.child
    if (!child || child.exitCode !== null || child.signalCode !== null) return
    this.stopping = true
    const exited = this.exited
    try {
      process.kill(-child.pid!, 'SIGTERM')
    } catch {}
    const kill = setTimeout(() => {
      try {
        process.kill(-child.pid!, 'SIGKILL')
      } catch {}
    }, 1000)
    await exited
    clearTimeout(kill)
    this.fail(new RuntimeError('runner.cancelled'))
  }
}
