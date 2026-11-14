/** Product-facing port. DSH wire types and process details stay in its adapter. */
export interface TranscriptMessage {
  role: 'user' | 'assistant' | 'tool'
  content: string
  name?: string
}
export interface Usage {
  inputTokens: number
  outputTokens: number
  cachedInputTokens?: number
}
export interface ReadOnlyTool {
  name: string
  description: string
  inputSchema: Record<string, unknown>
  readOnly: true
}
export interface SkillSnapshot {
  catalogId: string
  code: string
  name: string
  description: string
  version: number
  content: string
}
export interface SessionInput {
  sessionId: string
  model: string
  transcript?: TranscriptMessage[]
  tools?: ReadOnlyTool[]
  instructions?: string
  skills?: SkillSnapshot[]
}
export interface MessageInput {
  tools?: ReadOnlyTool[]
  text: string
  contextToken: string
  model?: string
}
export interface RuntimeEvent {
  type: string
  data: Record<string, unknown>
}
export interface AgentRuntime {
  initialize(model: string): Promise<void>
  createSession(
    input: SessionInput,
  ): Promise<{ sessionId: string; model: string; recovery: string }>
  sendMessage(
    sessionId: string,
    input: MessageInput,
    emit: (event: RuntimeEvent) => void,
  ): Promise<void>
  cancel(sessionId: string): Promise<boolean>
  exportSession(sessionId: string): {
    sessionId: string
    model: string
    transcript: TranscriptMessage[]
  }
  close(): Promise<void>
}
export class RuntimeError extends Error {
  readonly code: string
  readonly status: number
  readonly retryable: boolean
  constructor(code: string, status = 502, retryable = false) {
    super(code)
    this.code = code
    this.status = status
    this.retryable = retryable
  }
}
