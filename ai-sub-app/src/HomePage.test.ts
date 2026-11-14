import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, expect, test, vi } from 'vitest'
import HomePage from './HomePage.vue'

const boundary = vi.hoisted(() => ({
  api: vi.fn(),
  page: vi.fn(),
  request: vi.fn(),
  session: { tenantId: 'tenant-a', membershipId: 'member-a' } as {
    tenantId: string
    membershipId: string
  } | null,
  listener: undefined as undefined | ((session: unknown) => void),
  permissions: ['ai:conversation:use'],
}))
vi.mock('@crane199709/sdk', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@crane199709/sdk')>()),
  useTenantClient: () => ({
    api: boundary.api,
    page: boundary.page,
    request: boundary.request,
    session: () => boundary.session,
    onSessionChange: (listener: (session: unknown) => void) => {
      boundary.listener = listener
      return () => {}
    },
  }),
  useAccessControl: () => ({ has: (code: string) => boundary.permissions.includes(code) }),
}))
const conversation = {
  id: 'c1',
  title: '季度报告',
  modelId: 'm1',
  createdAt: '2026-11-14T08:00:00Z',
  updatedAt: '2026-11-14T08:00:00Z',
  messages: [],
  turns: [],
}
beforeEach(() => {
  boundary.session = { tenantId: 'tenant-a', membershipId: 'member-a' }
  boundary.permissions = ['ai:conversation:use']
  boundary.api.mockReset().mockImplementation(async (path: string) => {
    if (path === '/ai/models') return [{ id: 'm1', name: 'DeepSeek Chat' }]
    if (path === '/ai/prompts/published') return []
    if (path === '/ai/conversations') return [structuredClone(conversation)]
    if (path === '/ai/conversations/c1') return structuredClone(conversation)
    throw Error(path)
  })
  boundary.page.mockReset().mockImplementation(async (path: string) => {
    const items = await boundary.api(
      path.startsWith('/ai/conversations?query=') ? path : '/ai/conversations',
    )
    return {
      items,
      page: { number: 1, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 },
    }
  })
  boundary.request.mockReset()
})
test('成员能看到自己的历史会话并打开消息', async () => {
  const wrapper = mount(HomePage, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  expect(wrapper.text()).toContain('季度报告')
  await wrapper.get('[data-testid="conversation-c1"]').trigger('click')
  await flushPromises()
  expect(wrapper.find('textarea').exists()).toBe(true)
  wrapper.unmount()
})

async function openChat() {
  const wrapper = mount(HomePage, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  await wrapper.get('[data-testid="conversation-c1"]').trigger('click')
  await flushPromises()
  return wrapper
}
test('token 分批呈现且取消保留已显示回答', async () => {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const encoder = new TextEncoder()
  boundary.request.mockResolvedValue(
    new Response(
      new ReadableStream({
        start(value) {
          controller = value
        },
      }),
      { headers: { 'content-type': 'text/event-stream' } },
    ),
  )
  let cancelled = false
  boundary.api.mockImplementation(async (path: string) => {
    if (path === '/ai/models') return [{ id: 'm1', name: 'DeepSeek Chat' }]
    if (path === '/ai/prompts/published') return []
    if (path === '/ai/conversations') return [structuredClone(conversation)]
    if (path.endsWith('/cancel')) {
      cancelled = true
      return { cancelled: true }
    }
    if (path === '/ai/conversations/c1')
      return {
        ...conversation,
        messages: cancelled ? [{ id: 'partial', role: 'assistant', content: '第一段第二段' }] : [],
      }
    throw Error(path)
  })
  const wrapper = await openChat()
  await wrapper.get('textarea').setValue('请总结本季度工作')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '发送')!
    .trigger('click')
  await flushPromises()
  controller.enqueue(encoder.encode('event:token\ndata:{"text":"第一段"}\n\n'))
  await flushPromises()
  expect(wrapper.text()).toContain('第一段')
  expect(boundary.request.mock.calls[0][0]).toBe('/api/ai/conversations/c1/messages')
  expect(wrapper.text()).not.toContain('第二段')
  controller.enqueue(encoder.encode('event:token\ndata:{"text":"第二段"}\n\n'))
  await flushPromises()
  expect(wrapper.text()).toContain('第一段第二段')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '取消生成')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('已取消生成')
  expect(wrapper.text()).toContain('第一段第二段')
  wrapper.unmount()
})
test('权限不足时不加载任何业务数据', async () => {
  boundary.permissions = []
  const wrapper = mount(HomePage, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  expect(wrapper.text()).toContain('暂无 AI 使用权限')
  expect(boundary.api).not.toHaveBeenCalled()
  wrapper.unmount()
})
test('断流提示可重试并复用幂等消息键', async () => {
  const encoder = new TextEncoder()
  boundary.request.mockImplementation(
    async () =>
      new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(encoder.encode('event:token\ndata:{"text":"部分回答"}\n\n'))
            controller.close()
          },
        }),
        { headers: { 'content-type': 'text/event-stream' } },
      ),
  )
  const wrapper = await openChat()
  await wrapper.get('textarea').setValue('同一条消息')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '发送')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('连接中断，已保留部分回答')
  const first = JSON.parse(boundary.request.mock.calls[0][1].body)
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试')!
    .trigger('click')
  await flushPromises()
  const second = JSON.parse(boundary.request.mock.calls[1][1].body)
  expect(second.idempotencyKey).toBe(first.idempotencyKey)
  expect(
    wrapper.findAll('.message.user').filter((item) => item.text().includes('同一条消息')),
  ).toHaveLength(1)
  wrapper.unmount()
})
test('切租户后丢弃延迟旧列表并清除旧内容', async () => {
  let resolveOld!: (value: unknown) => void
  boundary.api.mockImplementation((path: string) => {
    if (path === '/ai/models') return Promise.resolve([{ id: 'm1', name: 'DeepSeek Chat' }])
    if (path === '/ai/prompts/published') return Promise.resolve([])
    if (path === '/ai/conversations')
      return new Promise((resolve) => {
        resolveOld = resolve
      })
    throw Error(path)
  })
  const wrapper = mount(HomePage, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  const old = resolveOld
  boundary.session = { tenantId: 'tenant-b', membershipId: 'member-b' }
  boundary.listener?.(boundary.session)
  await flushPromises()
  old([conversation])
  resolveOld([])
  await flushPromises()
  expect(wrapper.text()).not.toContain('季度报告')
  expect(wrapper.text()).toContain('暂无会话')
  wrapper.unmount()
})
test('切租户中止进行中的流且旧token不能写回', async () => {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const encoder = new TextEncoder()
  boundary.request.mockResolvedValue(
    new Response(
      new ReadableStream({
        start(value) {
          controller = value
        },
      }),
      { headers: { 'content-type': 'text/event-stream' } },
    ),
  )
  const wrapper = await openChat()
  await wrapper.get('textarea').setValue('租户A问题')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '发送')!
    .trigger('click')
  await flushPromises()
  controller.enqueue(encoder.encode('event:token\ndata:{"text":"租户A回答"}\n\n'))
  await flushPromises()
  expect(wrapper.text()).toContain('租户A回答')
  boundary.session = null
  boundary.listener?.(null)
  await flushPromises()
  expect(boundary.request.mock.calls[0][1].signal.aborted).toBe(true)
  controller.enqueue(encoder.encode('event:token\ndata:{"text":"不应出现的旧回答"}\n\n'))
  controller.close()
  await flushPromises()
  expect(wrapper.text()).not.toContain('租户A回答')
  expect(wrapper.text()).not.toContain('不应出现的旧回答')
  expect(wrapper.text()).toContain('请重新登录')
  wrapper.unmount()
})

test('SSE错误显示部分回答并允许用户显式再次发送', async () => {
  const encoder = new TextEncoder()
  const originalApi = boundary.api.getMockImplementation()!
  let failed = false
  boundary.api.mockImplementation(async (path: string, init: unknown) => {
    if (failed && path === '/ai/conversations/c1')
      return {
        ...conversation,
        messages: [{ id: 'partial', role: 'assistant', content: '保留的部分回答' }],
      }
    return originalApi(path, init)
  })
  boundary.request.mockImplementation(
    async () =>
      new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(
              encoder.encode(
                'event:token\ndata:{"text":"保留的部分回答"}\n\nevent:error\ndata:{"code":"runner.failed","retryable":false}\n\n',
              ),
            )
            failed = true
            controller.close()
          },
        }),
        { headers: { 'content-type': 'text/event-stream' } },
      ),
  )
  const wrapper = await openChat()
  await wrapper.get('textarea').setValue('请分析业务风险')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '发送')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('回答未完成，已保留部分内容。点击重试将重新发送为新回合。')
  expect(wrapper.text()).toContain('保留的部分回答')
  expect(wrapper.findAll('button').find((button) => button.text() === '重试')).toBeDefined()
  const first = JSON.parse(boundary.request.mock.calls[0][1].body)
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试')!
    .trigger('click')
  await flushPromises()
  const second = JSON.parse(boundary.request.mock.calls[1][1].body)
  expect(second.idempotencyKey).not.toBe(first.idempotencyKey)
  wrapper.unmount()
})

test('敏感只读工具显示可信名称，owner批准后才继续回答', async () => {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const encoder = new TextEncoder()
  boundary.request.mockResolvedValue(
    new Response(
      new ReadableStream({
        start(value) {
          controller = value
        },
      }),
      { headers: { 'content-type': 'text/event-stream' } },
    ),
  )
  const original = boundary.api.getMockImplementation()!
  boundary.api.mockImplementation(async (path: string, init: RequestInit) => {
    if (path === '/ai/mcp/approvals/approval-1/decision')
      return {
        approvalId: 'approval-1',
        status: JSON.parse(init.body as string).approved ? 'APPROVED' : 'REJECTED',
      }
    return original(path, init)
  })
  const wrapper = await openChat()
  await wrapper.get('textarea').setValue('查询财务汇总')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '发送')!
    .trigger('click')
  await flushPromises()
  controller.enqueue(
    encoder.encode(
      `event:tool.approval\ndata:${JSON.stringify({ approvalId: 'approval-1', displayName: '财务汇总查询', description: '读取本成员数据范围内的财务汇总', toolName: 'm0123456789abcdef0123456789abcdef', expiresAt: new Date(Date.now() + 20000).toISOString(), status: 'PENDING' })}\n\n`,
    ),
  )
  await flushPromises()
  expect(wrapper.text()).toContain('财务汇总查询 · 等待批准')
  expect(wrapper.text()).not.toContain('m0123456789abcdef0123456789abcdef')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '批准本次调用')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('财务汇总查询 · 已批准')
  controller.enqueue(encoder.encode('event:token\ndata:{"text":"批准后返回的回答"}\n\n'))
  await flushPromises()
  expect(wrapper.text()).toContain('批准后返回的回答')
  wrapper.unmount()
})

test('审批过期后不可批准，成员可以拒绝仍有效的调用', async () => {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const encoder = new TextEncoder()
  boundary.request.mockResolvedValue(
    new Response(
      new ReadableStream({
        start(value) {
          controller = value
        },
      }),
      { headers: { 'content-type': 'text/event-stream' } },
    ),
  )
  const original = boundary.api.getMockImplementation()!
  boundary.api.mockImplementation(async (path: string, init: RequestInit) => {
    if (path === '/ai/mcp/approvals/current/decision')
      return {
        approvalId: 'current',
        status: JSON.parse(init.body as string).approved ? 'APPROVED' : 'REJECTED',
      }
    return original(path, init)
  })
  const wrapper = await openChat()
  await wrapper.get('textarea').setValue('读取统计')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '发送')!
    .trigger('click')
  await flushPromises()
  const frame = (id: string, expiresAt: string) =>
    `event:tool.approval\ndata:${JSON.stringify({ approvalId: id, displayName: '统计查询', description: '只读统计', expiresAt, status: 'PENDING' })}\n\n`
  controller.enqueue(encoder.encode(frame('expired', new Date(Date.now() - 1000).toISOString())))
  await flushPromises()
  expect(wrapper.text()).toContain('统计查询 · 已过期')
  expect(wrapper.findAll('button').some((button) => button.text() === '批准本次调用')).toBe(false)
  controller.enqueue(encoder.encode(frame('current', new Date(Date.now() + 20000).toISOString())))
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '拒绝')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('统计查询 · 已拒绝')
  wrapper.unmount()
})
test.each([
  ['usage.quotaExceeded', 429, '本月 AI 配额已耗尽，请联系租户管理员调整套餐。'],
  ['usage.planUnavailable', 503, '套餐配额尚未同步，请稍后重试或联系管理员。'],
] as const)('%s 有明确提示', async (code, status, tip) => {
  boundary.request.mockResolvedValue(
    new Response(JSON.stringify({ code, httpStatus: status }), {
      status,
      headers: { 'content-type': 'application/json' },
    }),
  )
  const wrapper = await openChat()
  await wrapper.get('textarea').setValue('请继续分析')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '发送')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain(tip)
  wrapper.unmount()
})

test('搜索按输入查询并显示空结果，下载复用 Shell 客户端', async () => {
  const wrapper = await openChat()
  boundary.api.mockImplementation(async (path: string) =>
    path.startsWith('/ai/conversations?') ? [] : structuredClone(conversation),
  )
  const search = wrapper.get('input[aria-label="搜索历史会话"]')
  expect(search.attributes('disabled')).toBeUndefined()
  await search.setValue('100%_中文')
  await search.trigger('keydown', { key: 'Enter' })
  await flushPromises()
  expect(boundary.page).toHaveBeenCalledWith(
    '/ai/conversations?query=100%25_%E4%B8%AD%E6%96%87&page=1&size=20',
    expect.objectContaining({ signal: expect.any(AbortSignal) }),
  )
  expect(wrapper.text()).toContain('没有匹配的会话')
  boundary.request.mockResolvedValue(
    new Response('# 中文', { headers: { 'content-type': 'text/markdown' } }),
  )
  const createUrl = vi.fn(() => 'blob:download')
  Object.defineProperty(URL, 'createObjectURL', { value: createUrl, configurable: true })
  Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true })
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  await wrapper.get('[aria-label="会话操作"]').trigger('click')
  await flushPromises()
  const menu = document.body.querySelectorAll('.el-dropdown-menu__item')
  const markdown = Array.from(menu).find((item) => item.textContent?.includes('导出 Markdown'))
  expect(markdown).toBeTruthy()
  markdown?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
  expect(boundary.request).toHaveBeenCalledWith(
    '/api/ai/conversations/c1/export?format=markdown',
    expect.objectContaining({ signal: expect.any(AbortSignal) }),
  )
  expect(createUrl).toHaveBeenCalledOnce()
  wrapper.unmount()
})

test('加载下一页并忽略旧搜索的迟到结果', async () => {
  const wrapper = await openChat()
  let oldResult!: (value: unknown) => void
  boundary.page.mockImplementation(async (path: string) => {
    if (path.includes('query=old'))
      return new Promise((resolve) => {
        oldResult = resolve
      })
    const second = path.includes('page=2')
    return {
      items: [
        { ...conversation, id: second ? 'older' : 'new', title: second ? '较早历史' : '新查询' },
      ],
      page: { number: second ? 2 : 1, size: 20, totalElements: 21, totalPages: 2 },
    }
  })
  const search = wrapper.get('input[aria-label="搜索历史会话"]')
  await search.setValue('old')
  await search.trigger('keydown', { key: 'Enter' })
  await flushPromises()
  await search.setValue('new')
  await search.trigger('keydown', { key: 'Enter' })
  await flushPromises()
  expect(wrapper.text()).toContain('新查询')
  oldResult({
    items: [{ ...conversation, title: '旧请求不应覆盖' }],
    page: { number: 1, size: 20, totalElements: 1, totalPages: 1 },
  })
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧请求不应覆盖')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '加载更多会话')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('新查询')
  expect(wrapper.text()).toContain('较早历史')
  expect(wrapper.text()).not.toContain('加载更多会话')
  wrapper.unmount()
})

test('崩溃恢复提示重新生成并清空本轮旧partial和审批，再显示新回答', async () => {
  let stream!: ReadableStreamDefaultController<Uint8Array>
  boundary.request.mockResolvedValue(
    new Response(
      new ReadableStream({
        start(controller) {
          stream = controller
        },
      }),
      { headers: { 'content-type': 'text/event-stream' } },
    ),
  )
  const wrapper = await openChat()
  await wrapper.get('textarea').setValue('恢复当前轮')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '发送')!
    .trigger('click')
  await flushPromises()
  const encoder = new TextEncoder()
  stream.enqueue(
    encoder.encode(
      'event:token\ndata:{"text":"旧的部分回答"}\n\nevent:tool.approval\ndata:{"approvalId":"old","displayName":"旧审批","description":"待确认","expiresAt":"2099-01-01T00:00:00Z","status":"PENDING"}\n\n',
    ),
  )
  await flushPromises()
  expect(wrapper.text()).toContain('旧的部分回答')
  stream.enqueue(
    encoder.encode(
      'event:retrying\ndata:{"attempt":2,"reason":"runner.recovered"}\n\nevent:token\ndata:{"text":"恢复后的回答"}\n\n',
    ),
  )
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧的部分回答')
  expect(wrapper.text()).not.toContain('旧审批')
  expect(wrapper.text()).toContain('正在重新生成本次回答')
  expect(wrapper.text()).toContain('恢复后的回答')
  wrapper.unmount()
})
