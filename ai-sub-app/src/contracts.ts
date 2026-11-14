export interface Model {
  id: string
  name: string
  provider: string
  modelId: string
  contextWindow: number
  capabilities: string[]
}
export interface PromptOption {
  id: string
  name: string
  source: string
  publishedVersion: number
  variables: string[]
}
export interface Prompt extends PromptOption {
  content: string
}
export interface PromptVersion {
  version: number
  content: string
  variables: string[]
  publishedAt: string
  current: boolean
}
export interface Message {
  id: string
  turnId?: string
  role: string
  content: string
  toolCalls?: string
  toolName?: string
  createdAt?: string
}
export interface Turn {
  id: string
  status: string
  errorCode?: string
  usageReported: boolean
}
export interface Conversation {
  id: string
  title: string
  modelId: string
  activeTurnId?: string
  createdAt: string
  updatedAt: string
  messages: Message[]
  turns: Turn[]
}
export interface Skill {
  catalogId: string
  name: string
  description: string
  status: string
  catalogVersion: number
  draftEnabled: boolean
  publishedVersion: number
  enabled: boolean
  skillVersion?: number
}
export interface SkillVersion {
  version: number
  enabled: boolean
  skillVersion?: number
}
export interface ToolEvent {
  attemptId?: string
  phase: string
  name?: string
  nativeName?: string
  displayName?: string
  callId?: string
  isError?: boolean
  summary?: string
}
