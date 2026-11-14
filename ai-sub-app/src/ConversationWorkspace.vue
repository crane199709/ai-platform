<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError } from '@crane199709/sdk'
import { formatDateTime, overlaySize } from '@crane199709/ui'
import type { Conversation, Message, Model, PromptOption, ToolEvent } from './contracts'
import { errorTip, isAbort, json, toolTitle, useTenantScope } from './tenant-scope'

const { client, api, signal } = useTenantScope()
const conversations = ref<Conversation[]>([])
const models = ref<Model[]>([])
const prompts = ref<PromptOption[]>([])
const selected = ref<Conversation>()
const loading = ref(true)
const loaded = ref(false)
const detailLoading = ref(false)
const error = ref('')
const notice = ref('')
const composer = ref('')
const modelId = ref('')
const streaming = ref(false)
const tools = ref<ToolEvent[]>([])
interface Approval {
  approvalId: string
  displayName: string
  description: string
  expiresAt: string
  status: string
}
const approvals = ref<Approval[]>([])
const approvalBusy = ref('')
const currentTime = ref(Date.now())
const timer = setInterval(() => {
  currentTime.value = Date.now()
}, 1000)
onBeforeUnmount(() => clearInterval(timer))
const remaining = (approval: Approval) =>
  Math.max(0, Math.ceil((Date.parse(approval.expiresAt) - currentTime.value) / 1000))
const approvalLabel = (approval: Approval) =>
  ({ APPROVED: '已批准', REJECTED: '已拒绝', EXPIRED: '已过期', CANCELLED: '已关闭' })[
    approval.status
  ] || (remaining(approval) ? '等待批准' : '已过期')
function closeApprovals() {
  for (const approval of approvals.value) {
    if (approval.status === 'PENDING') approval.status = 'CANCELLED'
  }
}
async function decide(approval: Approval, approved: boolean) {
  approvalBusy.value = approval.approvalId
  try {
    const result = await api<{ status: string }>(
      `/ai/mcp/approvals/${approval.approvalId}/decision`,
      json('POST', { approved }),
    )
    approval.status = result.status
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  } finally {
    if (!signal.aborted) approvalBusy.value = ''
  }
}
const transcript = ref<HTMLElement>()
const createOpen = ref(false)
const createTitle = ref('新会话')
const promptId = ref('')
const variables = ref<Record<string, string>>({})
const saving = ref(false)
const renameOpen = ref(false)
const renameTitle = ref('')
const deleteOpen = ref(false)
let stream: AbortController | undefined
let selectedRequest = 0
let pending:
  { text: string; idempotencyKey: string; modelId: string; uncertain: boolean } | undefined
const canSend = computed(
  () =>
    !!selected.value &&
    !!composer.value.trim() &&
    models.value.some((model) => model.id === modelId.value) &&
    !streaming.value &&
    !selected.value.activeTurnId,
)
const chosenPrompt = computed(() => prompts.value.find((p) => p.id === promptId.value))
const modelName = (id: string) => models.value.find((m) => m.id === id)?.name || '当前模型不可用'
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [items, available, options] = await Promise.all([
      api<Conversation[]>('/ai/conversations'),
      api<Model[]>('/ai/models'),
      api<PromptOption[]>('/ai/prompts/published'),
    ])
    loaded.value = true
    conversations.value = items
    models.value = available
    prompts.value = options
    modelId.value = available[0]?.id || ''
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  } finally {
    if (!signal.aborted) loading.value = false
  }
}
onMounted(load)
onBeforeUnmount(() => stream?.abort())
async function openConversation(id: string) {
  const request = ++selectedRequest
  detailLoading.value = true
  error.value = ''
  notice.value = ''
  pending = undefined
  tools.value = []
  approvals.value = []
  try {
    const item = await api<Conversation>(`/ai/conversations/${id}`)
    if (request !== selectedRequest) return
    selected.value = item
    modelId.value = item.modelId
    const last = item.turns.at(-1)
    if (last && !['COMPLETED', 'RUNNING'].includes(last.status))
      notice.value =
        last.status === 'CANCELLED'
          ? '已取消生成，已保留部分回答。'
          : '上次回答未完成，已保留历史。'
  } catch (e) {
    if (!isAbort(e) && request === selectedRequest) error.value = errorTip(e)
  } finally {
    if (!signal.aborted && request === selectedRequest) detailLoading.value = false
  }
}
function beginCreate() {
  selectedRequest++
  detailLoading.value = false
  createOpen.value = true
  variables.value = {}
  promptId.value = ''
}
function menuCommand(command: string) {
  if (command === 'rename' && selected.value) {
    renameTitle.value = selected.value.title
    renameOpen.value = true
  }
  if (command === 'delete') deleteOpen.value = true
}
async function create() {
  saving.value = true
  error.value = ''
  try {
    const prompt = chosenPrompt.value
    const item = await api<Conversation>(
      '/ai/conversations',
      json('POST', {
        title: createTitle.value.trim() || '新会话',
        modelId: modelId.value,
        ...(prompt
          ? {
              promptTemplateId: prompt.id,
              promptVersion: prompt.publishedVersion,
              variables: variables.value,
            }
          : {}),
      }),
    )
    conversations.value.unshift(item)
    selected.value = item
    tools.value = []
    notice.value = ''
    pending = undefined
    createOpen.value = false
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  } finally {
    if (!signal.aborted) saving.value = false
  }
}
async function rename() {
  if (!selected.value || !renameTitle.value.trim()) return
  saving.value = true
  try {
    const item = await api<Conversation>(
      `/ai/conversations/${selected.value.id}`,
      json('PATCH', { title: renameTitle.value.trim() }),
    )
    selected.value.title = item.title
    const row = conversations.value.find((c) => c.id === item.id)
    if (row) row.title = item.title
    renameOpen.value = false
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  } finally {
    if (!signal.aborted) saving.value = false
  }
}
async function remove() {
  if (!selected.value) return
  saving.value = true
  try {
    await api(`/ai/conversations/${selected.value.id}`, { method: 'DELETE' })
    conversations.value = conversations.value.filter((c) => c.id !== selected.value?.id)
    selected.value = undefined
    deleteOpen.value = false
    pending = undefined
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  } finally {
    if (!signal.aborted) saving.value = false
  }
}
async function scroll() {
  await nextTick()
  transcript.value?.scrollTo?.({ top: transcript.value.scrollHeight })
}
async function send(retry = false) {
  const item = selected.value
  if (!item || streaming.value) return
  const text = retry && pending ? pending.text : composer.value.trim()
  if (!text || !modelId.value) return
  const attempt =
    retry && pending?.uncertain
      ? pending
      : { text, idempotencyKey: crypto.randomUUID(), modelId: modelId.value, uncertain: true }
  pending = attempt
  composer.value = ''
  error.value = ''
  notice.value = ''
  tools.value = []
  approvals.value = []
  streaming.value = true
  if (!(retry && attempt.uncertain && item.messages.some((m) => m.id === attempt.idempotencyKey))) {
    item.messages.push({ id: attempt.idempotencyKey, role: 'user', content: text })
  }
  const draftReply: Message = {
    id: `${attempt.idempotencyKey}-reply`,
    role: 'assistant',
    content: '',
  }
  const previous = item.messages.find((m) => m.id === draftReply.id)
  if (previous) item.messages.splice(item.messages.indexOf(previous), 1)
  item.messages.push(draftReply)
  const reply = item.messages.at(-1)!
  stream = new AbortController()
  const abort = () => stream?.abort()
  signal.addEventListener('abort', abort, { once: true })
  let ended = false
  try {
    const response = await client.request(`/api/ai/conversations/${item.id}/messages`, {
      ...json('POST', {
        text: attempt.text,
        idempotencyKey: attempt.idempotencyKey,
        modelId: attempt.modelId,
      }),
      signal: stream.signal,
    })
    if (!response.ok) {
      const body = await response
        .json()
        .catch(() => ({ code: 'system.error', httpStatus: response.status }))
      throw new ApiError(
        { ...body, httpStatus: response.status },
        Number(response.headers.get('retry-after')) || undefined,
      )
    }
    if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body)
      throw Error('Invalid stream')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    try {
      while (true) {
        const chunk = await reader.read()
        if (signal.aborted || stream.signal.aborted) break
        buffer = (buffer + decoder.decode(chunk.value, { stream: !chunk.done })).replace(
          /\r\n/g,
          '\n',
        )
        let boundary: number
        while ((boundary = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          const event = frame
            .split('\n')
            .find((line) => line.startsWith('event:'))
            ?.slice(6)
            .trim()
          const data = frame
            .split('\n')
            .filter((line) => line.startsWith('data:'))
            .map((line) => line.slice(5).trimStart())
            .join('\n')
          if (!event || !data) continue
          const value = JSON.parse(data)
          if (event === 'token') {
            reply.content += String(value.text || '')
            await scroll()
          }
          if (event === 'tool') {
            const index = tools.value.findIndex(
              (tool) => value.callId && tool.callId === value.callId,
            )
            if (index >= 0) tools.value[index] = { ...tools.value[index], ...value }
            else tools.value.push(value)
            await scroll()
          }
          if (event === 'tool.approval') {
            approvals.value.push(value)
            await scroll()
          }
          if (event === 'tool.approval.resolved') {
            const approval = approvals.value.find((item) => item.approvalId === value.approvalId)
            if (approval) approval.status = value.status
          }
          if (event === 'done') {
            ended = true
            attempt.uncertain = false
            notice.value = value.status === 'CANCELLED' ? '已取消生成，已保留部分回答。' : ''
            pending = undefined
          }
          if (event === 'error') {
            ended = true
            attempt.uncertain = false
            error.value = /quota/i.test(value.code)
              ? '本月 AI 配额已耗尽，请联系租户管理员。'
              : '回答未完成，已保留部分内容。可以重试发送。'
          }
        }
        if (chunk.done || ended) break
      }
    } finally {
      await reader.cancel().catch(() => {})
    }
    if (!ended && !signal.aborted)
      error.value = '连接中断，已保留部分回答。重试将核对同一条消息，不会重复提交。'
    if (ended && !signal.aborted) {
      const fresh = await api<Conversation>(`/ai/conversations/${item.id}`)
      if (selected.value?.id === item.id) selected.value = fresh
      const index = conversations.value.findIndex((c) => c.id === item.id)
      if (index >= 0) conversations.value.splice(index, 1)
      conversations.value.unshift(fresh)
    }
  } catch (e) {
    if (!isAbort(e) && !signal.aborted) error.value = errorTip(e)
  } finally {
    closeApprovals()
    signal.removeEventListener('abort', abort)
    if (!signal.aborted) streaming.value = false
  }
}
async function cancel() {
  const item = selected.value
  if (!item) return
  try {
    await api(`/ai/conversations/${item.id}/cancel`, json('POST', {}))
    stream?.abort()
    closeApprovals()
    streaming.value = false
    pending = undefined
    error.value = ''
    notice.value = '已取消生成，已保留部分回答。'
    const fresh = await api<Conversation>(`/ai/conversations/${item.id}`)
    if (selected.value?.id === item.id) selected.value = fresh
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  }
}
function historicalTools(message: Message): ToolEvent[] {
  if (!message.toolCalls) return []
  try {
    const value = JSON.parse(message.toolCalls)
    return Array.isArray(value) ? value : [value]
  } catch {
    return []
  }
}
</script>

<template>
  <div v-loading="loading" class="workspace">
    <aside class="history" aria-label="会话历史">
      <el-button
        type="primary"
        :disabled="loading || streaming || !models.length"
        @click="beginCreate"
        >新建会话</el-button
      >
      <el-input aria-label="搜索历史会话" placeholder="搜索历史会话" disabled />
      <el-empty v-if="loaded && !conversations.length" description="暂无会话" :image-size="72" />
      <button
        v-for="item in conversations"
        :key="item.id"
        class="conversation-row"
        :class="{ selected: item.id === selected?.id }"
        :data-testid="`conversation-${item.id}`"
        :disabled="streaming"
        @click="openConversation(item.id)"
      >
        <strong>{{ item.title }}</strong
        ><span>{{ formatDateTime(item.updatedAt) }}</span>
      </button>
    </aside>
    <main v-loading="detailLoading" class="chat" aria-label="会话内容">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button v-if="error && !loaded" @click="load">重试加载</el-button>
      <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
      <el-alert
        v-if="loaded && !models.length"
        title="暂无可用模型，请联系管理员配置模型。"
        type="warning"
        :closable="false"
      />
      <el-empty v-if="!selected" description="新建会话，开始你的第一个问题" />
      <template v-else>
        <div class="chat-toolbar">
          <h2>{{ selected.title }}</h2>
          <el-select
            v-model="modelId"
            aria-label="当前模型"
            :disabled="streaming || !!selected.activeTurnId"
          >
            <el-option
              v-for="model in models"
              :key="model.id"
              :label="model.name"
              :value="model.id"
            />
            <el-option
              v-if="!models.some((model) => model.id === modelId)"
              :value="modelId"
              :label="modelName(modelId)"
              disabled
            />
          </el-select>
          <el-dropdown
            trigger="click"
            :disabled="streaming || !!selected.activeTurnId"
            @command="menuCommand"
          >
            <el-button aria-label="会话操作">更多</el-button>
            <template #dropdown
              ><el-dropdown-menu
                ><el-dropdown-item command="rename">重命名</el-dropdown-item
                ><el-dropdown-item command="delete" divided
                  >删除会话</el-dropdown-item
                ></el-dropdown-menu
              ></template
            >
          </el-dropdown>
        </div>
        <div ref="transcript" class="messages" aria-live="polite" aria-label="消息记录">
          <el-empty
            v-if="!selected.messages.length"
            description="输入问题，开始对话"
            :image-size="72"
          />
          <article
            v-for="message in selected.messages"
            :key="message.id"
            class="message"
            :class="message.role"
          >
            <strong>{{
              message.role === 'user'
                ? '你'
                : message.role === 'assistant'
                  ? '助手'
                  : toolTitle({ name: message.toolName })
            }}</strong>
            <div class="message-content">
              {{
                message.content || (streaming && message.role === 'assistant' ? '正在思考…' : '—')
              }}
            </div>
            <div v-for="(tool, i) in historicalTools(message)" :key="i" class="tool-row">
              {{ toolTitle(tool) }} ·
              {{ tool.isError ? '执行失败' : tool.phase === 'tool.result' ? '已完成' : '已调用'
              }}<span v-if="tool.summary"> · {{ tool.summary }}</span>
            </div>
          </article>
          <div v-for="(tool, i) in tools" :key="i" class="tool-row" role="status">
            {{ toolTitle(tool) }} ·
            {{ tool.isError ? '执行失败' : tool.phase === 'tool.result' ? '已完成' : '调用中'
            }}<span v-if="tool.summary"> · {{ tool.summary }}</span>
          </div>
          <article
            v-for="approval in approvals"
            :key="approval.approvalId"
            class="approval"
            role="status"
          >
            <strong>{{ approval.displayName }} · {{ approvalLabel(approval) }}</strong>
            <p>{{ approval.description }}</p>
            <div
              v-if="approval.status === 'PENDING' && remaining(approval) > 0"
              class="approval-actions"
            >
              <span>{{ remaining(approval) }} 秒后过期</span>
              <el-button :disabled="!!approvalBusy" @click="decide(approval, false)"
                >拒绝</el-button
              >
              <el-button
                type="primary"
                :loading="approvalBusy === approval.approvalId"
                :disabled="!!approvalBusy"
                @click="decide(approval, true)"
                >批准本次调用</el-button
              >
            </div>
          </article>
        </div>
        <el-alert
          v-if="selected.activeTurnId && !streaming"
          title="此会话仍有未结束的回答，可以取消后继续。"
          type="info"
          :closable="false"
        />
        <div class="composer">
          <el-input
            v-model="composer"
            type="textarea"
            :rows="3"
            maxlength="128000"
            aria-label="消息"
            placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
            :disabled="streaming || !!selected.activeTurnId"
            @keydown.enter.exact.prevent="canSend && send()"
          />
          <div class="composer-actions">
            <el-button v-if="pending && error" :disabled="streaming" @click="send(true)"
              >重试</el-button
            >
            <el-button v-if="streaming || selected.activeTurnId" type="primary" @click="cancel"
              >取消生成</el-button
            >
            <el-button v-else type="primary" :disabled="!canSend" @click="send()">发送</el-button>
          </div>
        </div>
      </template>
    </main>
  </div>
  <el-dialog
    align-center
    :body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
    v-model="createOpen"
    title="新建会话"
    :width="overlaySize.dialogSm"
    destroy-on-close
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="create">
      <el-form-item label="标题"><el-input v-model="createTitle" maxlength="200" /></el-form-item>
      <el-form-item label="模型"
        ><el-select v-model="modelId" aria-label="新会话模型"
          ><el-option
            v-for="model in models"
            :key="model.id"
            :label="model.name"
            :value="model.id" /></el-select
      ></el-form-item>
      <el-form-item label="Prompt 模板"
        ><el-select v-model="promptId" aria-label="Prompt 模板" @change="variables = {}"
          ><el-option label="平台默认" value="" /><el-option
            v-for="prompt in prompts"
            :key="prompt.id"
            :label="`${prompt.name} · v${prompt.publishedVersion}`"
            :value="prompt.id" /></el-select
      ></el-form-item>
      <el-form-item
        v-for="variable in chosenPrompt?.variables || []"
        :key="variable"
        :label="variable"
        required
        ><el-input v-model="variables[variable]" :aria-label="`变量 ${variable}`"
      /></el-form-item>
    </el-form>
    <template #footer
      ><el-button @click="createOpen = false">取消</el-button
      ><el-button
        type="primary"
        :loading="saving"
        :disabled="!modelId || (chosenPrompt?.variables || []).some((key) => !variables[key])"
        @click="create"
        >创建</el-button
      ></template
    >
  </el-dialog>
  <el-dialog
    align-center
    :body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
    v-model="renameOpen"
    title="重命名会话"
    :width="overlaySize.dialogSm"
    ><el-input v-model="renameTitle" aria-label="会话标题" maxlength="200" /><template #footer
      ><el-button @click="renameOpen = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="rename">保存</el-button></template
    ></el-dialog
  >
  <el-dialog
    align-center
    :body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
    v-model="deleteOpen"
    title="删除会话"
    :width="overlaySize.dialogSm"
    ><p>删除后会话将从历史中移除，无法继续对话。</p>
    <template #footer
      ><el-button @click="deleteOpen = false">保留会话</el-button
      ><el-button type="danger" :loading="saving" @click="remove">确认删除</el-button></template
    ></el-dialog
  >
</template>

<style scoped>
.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 3fr);
  gap: var(--forge-space-4);
  min-height: 70vh;
}
.history {
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-2);
  padding-right: var(--forge-space-4);
  border-right: 1px solid var(--el-border-color-lighter);
}
.conversation-row {
  display: flex;
  flex-direction: column;
  text-align: left;
  gap: var(--forge-space-2);
  padding: var(--forge-space-3);
  border: none;
  border-radius: var(--forge-radius-md);
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-regular);
  cursor: pointer;
}
.conversation-row strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  width: 100%;
}
.conversation-row span {
  font-size: var(--forge-font-size-sm);
  color: var(--el-text-color-secondary);
}
.conversation-row:hover,
.conversation-row.selected {
  background: var(--el-fill-color-light);
}
.chat {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-3);
}
.chat-toolbar {
  display: flex;
  align-items: center;
  gap: var(--forge-space-3);
}
.chat-toolbar h2 {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--forge-font-size-lg);
  margin: 0;
}
.chat-toolbar .el-select {
  width: 30%;
}
.messages {
  flex: 1;
  max-height: 52vh;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-4);
  padding: var(--forge-space-3);
}
.message {
  padding: var(--forge-space-3);
  border-radius: var(--forge-radius-md);
  background: var(--el-fill-color-lighter);
}
.message.user {
  margin-left: var(--forge-space-5);
  background: var(--el-color-primary-light-9);
}
.message-content {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin-top: var(--forge-space-2);
  line-height: 1.7;
}
.tool-row {
  color: var(--el-text-color-secondary);
  font-size: var(--forge-font-size-sm);
  overflow-wrap: anywhere;
  padding: var(--forge-space-2);
}
.composer-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--forge-space-2);
}
.approval {
  padding: var(--forge-space-3);
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: var(--forge-radius-md);
  background: var(--el-color-warning-light-9);
}
.approval p {
  margin: var(--forge-space-2) 0;
}
.approval-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--forge-space-2);
}
.approval-actions span {
  margin-right: auto;
  color: var(--el-text-color-secondary);
}
</style>
