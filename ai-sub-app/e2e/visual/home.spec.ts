import { expect, test, type Page } from '@playwright/test'
import { createServer, type ServerResponse } from 'node:http'

const permissions = [
  'ai:conversation:use',
  'ai:configuration:manage',
  ...['prompt', 'skill', 'mcp'].flatMap((resource) =>
    ['read', 'update', 'publish', 'rollback'].map((action) => `ai:${resource}:${action}`),
  ),
]
const session = {
  accessToken: 'visual',
  refreshToken: 'visual',
  tokenType: 'Bearer',
  expiresIn: 3600,
  membershipId: 'member',
  tenantId: 'tenant',
  tenantName: '示例制造企业',
  identityName: '林晓',
}
const model = {
  id: 'model-1',
  name: 'DeepSeek Chat',
  provider: 'DEEPSEEK',
  modelId: 'deepseek-chat',
  contextWindow: 65536,
  capabilities: ['CHAT', 'TOOLS'],
}
const defaultPrompt = {
  id: 'prompt-default',
  name: '平台通用助手',
  source: 'PLATFORM',
  publishedVersion: 1,
  variables: [],
  content: '请提供准确、清晰的帮助。',
}
const tenantPrompt = {
  id: 'prompt-1',
  name: '季度报告助手',
  source: 'TENANT',
  publishedVersion: 2,
  variables: ['department'],
  content: '请为 {{department}} 整理季度报告。',
}
const skill = {
  catalogId: 'skill-1',
  name: '业务报告分析',
  description: '梳理业务数据与季度变化，形成清晰的报告提纲和行动建议。',
  status: 'REVIEWED',
  catalogVersion: 2,
  draftEnabled: true,
  publishedVersion: 2,
  enabled: true,
  skillVersion: 2,
}
const mcp = {
  id: 'mcp-1',
  name: '企业业务查询',
  version: 2,
  tools: [
    {
      name: '查询订单状态',
      description: '查询当前成员数据范围内的订单状态',
      permission: 'orders:order:read',
      readOnly: true,
    },
  ],
}
const now = '2026-11-14T08:00:00Z'
const envelope = (data: unknown) => ({ code: 'ok', httpStatus: 200, data, traceId: 'visual' })
async function setup(
  page: Page,
  options: {
    noPermission?: boolean
    login?: boolean
    failed?: boolean
    history?: boolean
    streamUrl?: string
    onDecision?: (approved: boolean) => void
  } = {},
) {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  page.on('console', (message) => {
    if (!['error', 'warning'].includes(message.type())) return
    if (options.failed && /status of 429/.test(message.text())) return
    errors.push(message.text())
  })
  if (!options.login)
    await page.addInitScript(
      (value) => localStorage.setItem('forge.tenant.session', JSON.stringify(value)),
      session,
    )
  await page.route('**/iam/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/login'))
      return route.fulfill({
        json: envelope({
          clientId: 'visual-client',
          redirectUri: 'http://127.0.0.1:5180/callback',
          memberships: [{ membershipId: 'member', tenantId: 'tenant', tenantName: '示例制造企业' }],
        }),
      })
    if (url.pathname === '/iam/oauth2/authorize')
      return route.fulfill({
        status: 302,
        headers: { location: `/callback?code=visual&state=${url.searchParams.get('state')}` },
      })
    if (url.pathname.endsWith('/exchange')) return route.fulfill({ json: envelope(session) })
    return route.fulfill({
      json: envelope({
        permissions: options.noPermission ? [] : permissions,
        menus: [],
        subApps: [],
        layout: null,
      }),
    })
  })
  await page.route('**/callback?**', (route) => route.fulfill({ body: 'ok' }))
  let conversations: any[] = options.history
    ? [
        {
          id: 'conversation-1',
          title: '季度经营报告与重点项目复盘',
          modelId: model.id,
          createdAt: now,
          updatedAt: now,
          messages: [
            { id: 'message-1', role: 'user', content: '请梳理第三季度业务报告中的重点变化。' },
            {
              id: 'message-2',
              role: 'assistant',
              content:
                '本季度重点关注三个方向：\n\n1. 订单交付周期缩短，关键项目按计划推进。\n2. 跨部门协作仍存在信息延迟，需要统一进度口径。\n3. 建议下季度围绕重点客户、交付质量和风险闭环制定行动清单。',
              toolCalls: JSON.stringify({
                phase: 'tool.result',
                name: 'm0123456789abcdef0123456789abcdef',
                displayName: '查询订单状态',
                callId: 'call-1',
                summary: '已完成只读查询',
              }),
            },
          ],
          turns: [{ id: 'turn-1', status: 'COMPLETED', usageReported: true }],
        },
      ]
    : []
  let promptRows = [defaultPrompt, tenantPrompt]
  let configuration = {
    catalogId: mcp.id,
    enabled: true,
    toolNames: ['查询订单状态'],
    publishedVersion: 2,
    publishedEnabled: true,
    catalogVersion: 2,
  }
  const requests: { path: string; method: string; body: any }[] = []
  await page.route('**/api/ai/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace('/api/ai', '')
    const method = request.method()
    const body = request.postDataJSON()
    requests.push({ path, method, body })
    const ok = (data: unknown) => route.fulfill({ json: envelope(data) })
    if (options.failed && path === '/conversations')
      return route.fulfill({
        status: 429,
        headers: { 'Retry-After': '5' },
        json: { code: 'runner.poolFull', httpStatus: 429, userTip: '请稍后重试' },
      })
    if (path === '/models' || path === '/models/catalog')
      return ok([
        model,
        { ...model, id: 'model-2', name: '推理助手', modelId: 'deepseek-reasoner' },
      ])
    if (path === '/models/selection')
      return ok({ configured: true, modelIds: method === 'PUT' ? body.modelIds : [model.id] })
    if (path === '/prompts/published') return ok(promptRows)
    if (path === '/prompts/preview')
      return ok({ content: body.content.replace('{{department}}', body.values.department || '') })
    if (path === '/prompts' && method === 'GET') return ok(promptRows)
    if (path === '/prompts' && method === 'POST') {
      const row = { ...body, id: 'prompt-new', source: 'TENANT', publishedVersion: null }
      promptRows.push(row)
      return ok(row)
    }
    if (path.startsWith('/prompts/')) {
      if (path.endsWith('/versions'))
        return ok([
          {
            version: 2,
            content: tenantPrompt.content,
            variables: ['department'],
            publishedAt: now,
            current: true,
          },
          { version: 1, content: '旧版', variables: [], publishedAt: now, current: false },
        ])
      if (path.endsWith('/draft')) {
        const row = promptRows.find((p) => path.includes(p.id))!
        Object.assign(row, body)
        return ok(row)
      }
      return ok({})
    }
    if (path === '/skills') return ok([skill])
    if (path.startsWith('/skills/')) {
      if (path.endsWith('/versions'))
        return ok([
          { version: 2, enabled: true, skillVersion: 2 },
          { version: 1, enabled: false, skillVersion: null },
        ])
      return ok({})
    }
    if (path === '/mcp/catalog') return ok([mcp])
    if (path === '/mcp/tools') return ok([{ catalogId: mcp.id, nativeName: '查询订单状态' }])
    if (path === '/mcp') return ok([configuration])
    if (path === '/mcp/approvals/approval-visual/decision') {
      const approved = Boolean(body.approved)
      conversations[0].messages.push({
        id: 'approval-result',
        role: 'assistant',
        content: approved ? '审批通过后已完成只读查询。' : '已拒绝本次工具调用。',
      })
      options.onDecision?.(approved)
      return ok({ approvalId: 'approval-visual', status: approved ? 'APPROVED' : 'REJECTED' })
    }
    if (path.startsWith('/mcp/')) {
      if (path.endsWith('/versions'))
        return ok([
          {
            version: 2,
            enabled: true,
            toolNames: ['查询订单状态'],
            catalogVersion: 2,
            publishedAt: now,
            current: true,
          },
          {
            version: 1,
            enabled: false,
            toolNames: [],
            catalogVersion: 1,
            publishedAt: now,
            current: false,
          },
        ])
      if (path.endsWith('/draft')) Object.assign(configuration, body)
      return ok(configuration)
    }
    if (path === '/usage')
      return ok({
        month: '2026-11',
        businessZone: 'Asia/Shanghai',
        quotaKnown: true,
        tokenQuota: 100000,
        inputTokens: 12400,
        outputTokens: 3200,
        cachedInputTokens: 2000,
        totalTokens: 15600,
        estimatedTokens: 65536,
        chargedTokens: 81136,
        remainingTokens: 18864,
        turnCount: 23,
        toolCalls: 8,
        unknownUsageTurns: 1,
        activeTurns: 0,
        days: [
          {
            usageDate: '2026-11-14',
            inputTokens: 12400,
            outputTokens: 3200,
            estimatedTokens: 65536,
            unknownUsageTurns: 1,
            reportedAt: now,
          },
        ],
      })
    if (path === '/conversations' && method === 'GET') {
      const url = new URL(request.url())
      const query = url.searchParams.get('query') || ''
      const items = conversations.filter((item) =>
        `${item.title} ${item.messages.map((message: any) => message.content).join(' ')}`.includes(
          query,
        ),
      )
      return route.fulfill({
        json: {
          ...envelope(items),
          meta: {
            page: {
              number: 1,
              size: 20,
              totalElements: items.length,
              totalPages: items.length ? 1 : 0,
            },
          },
        },
      })
    }
    if (path.endsWith('/export')) {
      const format = new URL(request.url()).searchParams.get('format')
      return route.fulfill({
        contentType: format === 'json' ? 'application/json' : 'text/markdown',
        headers: {
          'content-disposition': `attachment; filename="conversation.${format === 'json' ? 'json' : 'md'}"`,
        },
        body:
          format === 'json'
            ? JSON.stringify({ title: conversations[0].title, messages: conversations[0].messages })
            : '# ' +
              conversations[0].title +
              '\n\n' +
              conversations[0].messages.map((message: any) => message.content).join('\n'),
      })
    }
    if (path === '/conversations' && method === 'POST') {
      const item = {
        ...body,
        id: 'conversation-new',
        createdAt: now,
        updatedAt: now,
        messages: [],
        turns: [],
      }
      conversations.unshift(item)
      return ok(item)
    }
    const item = conversations.find((c) => path.includes(c.id))
    if (item && path.endsWith('/messages')) {
      if (options.streamUrl) return route.continue({ url: options.streamUrl })
      item.messages.push(
        { id: 'user-new', role: 'user', content: body.text },
        { id: 'assistant-new', role: 'assistant', content: '已完成季度报告初稿，请核对关键指标。' },
      )
      item.turns.push({ id: 'turn-new', status: 'COMPLETED', usageReported: true })
      return route.fulfill({
        contentType: 'text/event-stream',
        body: 'event:token\ndata:{"text":"已完成季度报告初稿，"}\n\nevent:tool\ndata:{"phase":"tool.result","name":"m0123456789abcdef0123456789abcdef","displayName":"查询订单状态","callId":"tool-1","summary":"已完成只读查询"}\n\nevent:token\ndata:{"text":"请核对关键指标。"}\n\nevent:done\ndata:{"turnId":"turn-new","status":"COMPLETED","usageReported":true}\n\n',
      })
    }
    if (item && method === 'PATCH') {
      item.title = body.title
      return ok(item)
    }
    if (item && method === 'DELETE') {
      conversations = conversations.filter((c) => c !== item)
      return ok(null)
    }
    if (item) return ok(item)
    return route.fulfill({ status: 404, json: { code: 'test.unexpectedRequest', httpStatus: 404 } })
  })
  await page.goto('/apps/ai/home')
  await expect(page).toHaveURL(/\/apps\/ai\/home$/)
  await expect(page).toHaveTitle('ai Sub-App 开发宿主')
  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()
  return { errors, requests }
}

for (const [label, width, height] of [
  ['desktop', 1440, 900],
  ['laptop', 1024, 768],
] as const) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })
    test('空状态与权限、繁忙错误清晰可见', async ({ page }) => {
      const state = await setup(page)
      await expect(page.getByText('暂无会话', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`home-${label}.png`)
      expect(state.errors).toEqual([])
    })
    test('权限不足不请求会话', async ({ page }) => {
      const state = await setup(page, { noPermission: true })
      await expect(
        page.getByText('暂无 AI 使用权限，请联系租户管理员检查成员授权和套餐。'),
      ).toBeVisible()
      expect(state.requests).toEqual([])
      await expect(page).toHaveScreenshot(`permission-${label}.png`)
      expect(state.errors).toEqual([])
    })
    test('429展示等待提示', async ({ page }) => {
      const state = await setup(page, { failed: true })
      await expect(page.getByText('当前请求繁忙，请在 5 秒后重试。')).toBeVisible()
      await expect(page).toHaveScreenshot(`busy-${label}.png`)
      expect(state.errors).toEqual([])
    })
    test('历史消息、工具友好名称与模型切换', async ({ page }) => {
      const state = await setup(page, { history: true })
      await page.getByTestId('conversation-conversation-1').click()
      await expect(page.getByText('查询订单状态 · 已完成')).toBeVisible()
      await expect(page.getByText('m0123456789abcdef0123456789abcdef')).toHaveCount(0)
      await expect(page).toHaveScreenshot(`chat-${label}.png`)
      await page.getByRole('combobox', { name: '当前模型', exact: true }).press('Enter')
      await page.getByRole('option', { name: '推理助手' }).click()
      await page.getByLabel('消息', { exact: true }).fill('换模型继续分析')
      await page.getByRole('button', { name: '发送', exact: true }).click()
      await expect(page.getByText('已完成季度报告初稿，请核对关键指标。')).toBeVisible()
      expect(state.requests.find((r) => r.path.endsWith('/messages'))?.body.modelId).toBe('model-2')
      await page.getByRole('button', { name: '会话操作' }).click()
      await page.getByRole('menuitem', { name: '重命名', exact: true }).click()
      await page.getByRole('dialog').getByLabel('会话标题').fill('季度复盘定稿')
      await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click()
      await expect(page.getByTestId('conversation-conversation-1')).toContainText('季度复盘定稿')
      await page.getByRole('button', { name: '会话操作' }).click()
      await page.getByRole('menuitem', { name: '删除会话', exact: true }).click()
      await page.getByRole('dialog').getByRole('button', { name: '确认删除', exact: true }).click()
      await expect(page.getByText('暂无会话', { exact: true })).toBeVisible()
      expect(state.errors).toEqual([])
    })
    test('敏感只读工具等待owner批准后继续', async ({ page }) => {
      let response: ServerResponse | undefined
      const server = createServer((request, result) => {
        result.setHeader('Access-Control-Allow-Origin', 'http://127.0.0.1:5180')
        result.setHeader('Access-Control-Allow-Headers', 'authorization,content-type')
        result.setHeader('Access-Control-Allow-Methods', 'POST,OPTIONS')
        if (request.method === 'OPTIONS') {
          result.writeHead(204)
          result.end()
          return
        }
        if (request.headers.authorization !== 'Bearer visual') {
          result.writeHead(401)
          result.end()
          return
        }
        response = result
        result.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
        result.write(
          `event:tool.approval\ndata:${JSON.stringify({ approvalId: 'approval-visual', conversationId: 'conversation-1', turnId: 'turn-approval', displayName: '财务汇总查询', description: '读取当前成员数据范围内的财务汇总，仅执行本次只读查询。', toolName: 'm0123456789abcdef0123456789abcdef', expiresAt: '2026-11-14T08:00:20Z', status: 'PENDING' })}\n\n`,
        )
      })
      await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
      const address = server.address()
      if (!address || typeof address === 'string') throw Error('Missing fixture port')
      try {
        await page.clock.setFixedTime(new Date('2026-11-14T08:00:00Z'))
        const state = await setup(page, {
          history: true,
          streamUrl: `http://127.0.0.1:${address.port}/events`,
          onDecision: (approved) => {
            response!.write(
              `event:tool.approval.resolved\ndata:${JSON.stringify({ approvalId: 'approval-visual', status: approved ? 'APPROVED' : 'REJECTED' })}\n\n`,
            )
            response!.write(
              'event:token\ndata:{"text":"审批通过后已完成只读查询。"}\n\nevent:done\ndata:{"turnId":"turn-approval","status":"COMPLETED","usageReported":true}\n\n',
            )
            response!.end()
          },
        })
        await page.getByTestId('conversation-conversation-1').click()
        await page.getByLabel('消息', { exact: true }).fill('查询本季度财务汇总')
        await page.getByRole('button', { name: '发送', exact: true }).click()
        await expect(page.getByText('财务汇总查询 · 等待批准')).toBeVisible()
        await expect(page.getByText('20 秒后过期')).toBeVisible()
        await expect(page).toHaveScreenshot(`approval-${label}.png`)
        await page.getByRole('button', { name: '批准本次调用', exact: true }).click()
        await expect(page.getByText('审批通过后已完成只读查询。')).toBeVisible()
        expect(state.requests.find((item) => item.path.endsWith('/decision'))?.body).toEqual({
          approved: true,
        })
        expect(state.errors).toEqual([])
      } finally {
        server.closeAllConnections()
        await new Promise<void>((resolve) => server.close(() => resolve()))
      }
    })
    test('登录、建会话、使用已发布Prompt、发送并刷新恢复', async ({ page }) => {
      const state = await setup(page, { login: true })
      await page.getByLabel('账号', { exact: true }).fill('linxiao')
      await page.getByLabel('密码', { exact: true }).fill('visual-only')
      await page.getByRole('button', { name: '登录', exact: true }).click()
      await page.getByRole('button', { name: '新建会话', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: '新建会话' })
      await dialog.getByRole('combobox', { name: 'Prompt 模板', exact: true }).press('Enter')
      await page.getByRole('option', { name: '季度报告助手 · v2' }).click()
      await dialog.getByLabel('变量 department').fill('运营部')
      await dialog.getByRole('button', { name: '创建', exact: true }).click()
      await page.getByLabel('消息', { exact: true }).fill('请整理季度报告')
      await page.getByRole('button', { name: '发送', exact: true }).click()
      await expect(page.getByText('已完成季度报告初稿，请核对关键指标。')).toBeVisible()
      expect(
        state.requests.find((r) => r.path === '/conversations' && r.method === 'POST')?.body,
      ).toMatchObject({
        promptTemplateId: 'prompt-1',
        promptVersion: 2,
        variables: { department: '运营部' },
      })
      await page.reload()
      await page.getByTestId('conversation-conversation-new').click()
      await expect(page.getByText('已完成季度报告初稿，请核对关键指标。')).toBeVisible()
      expect(state.errors).toEqual([])
    })
    test('搜索标题正文、空结果和两种下载', async ({ page }) => {
      const state = await setup(page, { history: true })
      const search = page.getByRole('textbox', { name: '搜索历史会话' })
      await search.fill('不存在的历史')
      await search.press('Enter')
      await expect(page.getByText('没有匹配的会话', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`search-empty-${label}.png`)
      await search.fill('第三季度')
      await search.press('Enter')
      await expect(page.getByTestId('conversation-conversation-1')).toBeVisible()
      await page.getByTestId('conversation-conversation-1').click()
      await expect(page.getByText('订单交付周期缩短', { exact: false })).toBeVisible()
      await expect(page).toHaveScreenshot(`search-results-${label}.png`)
      for (const [name, extension] of [
        ['Markdown', 'md'],
        ['JSON', 'json'],
      ] as const) {
        await page.getByRole('button', { name: '会话操作' }).click()
        const download = page.waitForEvent('download')
        await page.getByRole('menuitem', { name: `导出 ${name}`, exact: true }).click()
        const file = await download
        expect(file.suggestedFilename()).toMatch(new RegExp(`\\.${extension}$`))
        expect(await file.failure()).toBeNull()
      }
      expect(state.errors).toEqual([])
    })
    test('断流保留部分回答并按原幂等键核对', async ({ page }) => {
      const state = await setup(page, { history: true })
      const keys: string[] = []
      await page.route('**/api/ai/conversations/*/messages', (route) => {
        keys.push(route.request().postDataJSON().idempotencyKey)
        return route.fulfill({
          contentType: 'text/event-stream',
          body:
            keys.length === 1
              ? 'event:token\ndata:{"text":"断流前的部分回答"}\n\n'
              : 'event:done\ndata:{"status":"COMPLETED","replayed":true}\n\n',
        })
      })
      await page.getByTestId('conversation-conversation-1').click()
      await page.getByRole('textbox', { name: '消息', exact: true }).fill('断流验收')
      await page.getByRole('button', { name: '发送', exact: true }).click()
      await expect(
        page.getByText('连接中断，已保留部分回答。重试将核对同一条消息，不会重复提交。', {
          exact: true,
        }),
      ).toBeVisible()
      await expect(page.getByText('断流前的部分回答', { exact: true })).toBeInViewport()
      const retryBounds = await page
        .getByRole('button', { name: '重试', exact: true })
        .boundingBox()
      expect(retryBounds).not.toBeNull()
      expect(retryBounds!.y + retryBounds!.height).toBeLessThanOrEqual(height)
      await expect(page).toHaveScreenshot(`stream-interrupted-${label}.png`)
      await page.getByRole('button', { name: '重试', exact: true }).click()
      await expect.poll(() => keys.length).toBe(2)
      expect(keys[0]).toBe(keys[1])
      expect(state.errors).toEqual([])
    })
    test('崩溃后提示重新生成且本轮旧partial不重复显示', async ({ page }) => {
      let response: ServerResponse | undefined
      const server = createServer((request, result) => {
        result.setHeader('Access-Control-Allow-Origin', 'http://127.0.0.1:5180')
        result.setHeader('Access-Control-Allow-Headers', 'authorization,content-type')
        result.setHeader('Access-Control-Allow-Methods', 'POST,OPTIONS')
        if (request.method === 'OPTIONS') {
          result.writeHead(204).end()
          return
        }
        response = result
        result.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
        result.write('event:token\ndata:{"text":"将被重新生成的旧内容"}\n\n')
      })
      await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
      const address = server.address()
      if (!address || typeof address === 'string') throw Error('Missing recovery fixture')
      try {
        const state = await setup(page, {
          history: true,
          streamUrl: `http://127.0.0.1:${address.port}/events`,
        })
        await page.getByTestId('conversation-conversation-1').click()
        await page
          .getByRole('textbox', { name: '消息', exact: true })
          .fill('恢复并重新生成本轮回答')
        await page.getByRole('button', { name: '发送', exact: true }).click()
        await expect(page.getByText('将被重新生成的旧内容', { exact: true })).toBeVisible()
        response!.write(
          'event:retrying\ndata:{"attempt":2,"reason":"runner.recovered"}\n\nevent:token\ndata:{"text":"已恢复服务，重新整理的业务报告正在生成。"}\n\n',
        )
        await expect(page.getByText('将被重新生成的旧内容', { exact: true })).toHaveCount(0)
        await expect(
          page.getByText(
            '运行服务已恢复，正在重新生成本次回答；只读工具可能再次查询，敏感工具需要重新批准。',
            { exact: true },
          ),
        ).toBeVisible()
        await expect(
          page.getByText('已恢复服务，重新整理的业务报告正在生成。', { exact: true }),
        ).toBeVisible()
        const cancelBounds = await page
          .getByRole('button', { name: '取消生成', exact: true })
          .boundingBox()
        expect(cancelBounds).not.toBeNull()
        expect(cancelBounds!.y + cancelBounds!.height).toBeLessThanOrEqual(height)
        await expect(page).toHaveScreenshot(`runner-recovery-${label}.png`)
        response!.end('event:done\ndata:{"status":"COMPLETED"}\n\n')
        await expect(page.getByRole('button', { name: '发送', exact: true })).toBeVisible()
        expect(state.errors).toEqual([])
      } finally {
        server.closeAllConnections()
        await new Promise<void>((resolve) => server.close(() => resolve()))
      }
    })
    test('Prompt草稿预览、发布、历史和回滚', async ({ page }) => {
      const state = await setup(page)
      await page.getByRole('tab', { name: 'Prompt', exact: true }).click()
      await expect(page.getByText('季度报告助手', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`prompts-${label}.png`)
      await page.getByRole('button', { name: '新增 Prompt', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: '新增 Prompt' })
      await dialog.getByLabel('Prompt 名称').fill('部门简报')
      await dialog.getByLabel('声明变量').fill('department')
      await dialog.getByLabel('Prompt 内容').fill('请为 {{department}} 编写简报。')
      await dialog.getByLabel('预览变量 department').fill('研发部')
      await dialog.getByRole('button', { name: '预览', exact: true }).click()
      await expect(dialog.getByText('请为 研发部 编写简报。')).toBeVisible()
      await expect(page).toHaveScreenshot(`prompt-editor-${label}.png`)
      await dialog.getByRole('button', { name: '保存草稿', exact: true }).click()
      const row = page.getByRole('row').filter({ hasText: '部门简报' })
      await row.getByRole('button', { name: '发布', exact: true }).click()
      await expect(page.getByText('Prompt 已发布，新会话可选择此版本。')).toBeVisible()
      await row.getByRole('button', { name: '历史', exact: true }).click()
      await page
        .getByRole('dialog')
        .getByRole('button', { name: '回滚', exact: true })
        .last()
        .click()
      await expect(page.getByText('已回滚到 v1，现有会话保持原版本。')).toBeVisible()
      expect(state.errors).toEqual([])
    })
    test('Skill审核目录配置发布与回滚', async ({ page }) => {
      const state = await setup(page)
      await page.getByRole('tab', { name: 'Skill', exact: true }).click()
      await expect(page.getByText('业务报告分析', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`skills-${label}.png`)
      await page.getByRole('button', { name: '配置', exact: true }).click()
      await page.getByRole('dialog').locator('.el-switch').click()
      await page.getByRole('button', { name: '保存草稿', exact: true }).click()
      await page.getByRole('button', { name: '发布', exact: true }).click()
      await expect(page.getByText('Skill 配置已发布。')).toBeVisible()
      await page.getByRole('button', { name: '历史', exact: true }).click()
      await page
        .getByRole('dialog')
        .getByRole('button', { name: '回滚', exact: true })
        .last()
        .click()
      await expect(page.getByText('已回滚 Skill 配置，现有会话保持原快照。')).toBeVisible()
      expect(state.errors).toEqual([])
    })
    test('MCP只读白名单、权限范围、发布与回滚', async ({ page }) => {
      const state = await setup(page)
      await page.getByRole('tab', { name: 'MCP', exact: true }).click()
      await expect(page.getByText('企业业务查询', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`mcp-${label}.png`)
      await page.getByRole('button', { name: '配置', exact: true }).click()
      await expect(
        page.getByRole('dialog').getByText('orders:order:read', { exact: true }),
      ).toBeVisible()
      await page.getByRole('button', { name: '保存草稿', exact: true }).click()
      await page.getByRole('button', { name: '发布', exact: true }).click()
      await expect(page.getByText('MCP 配置已发布，工具仍受成员权限与数据范围限制。')).toBeVisible()
      await page.getByRole('button', { name: '历史', exact: true }).click()
      await page
        .getByRole('dialog')
        .getByRole('button', { name: '回滚', exact: true })
        .last()
        .click()
      await expect(page.getByText('MCP 配置已回滚。')).toBeVisible()
      expect(state.errors).toEqual([])
    })
    test('模型仅选择目录、用量保留估计状态', async ({ page }) => {
      const state = await setup(page)
      await page.getByRole('tab', { name: '模型', exact: true }).click()
      await expect(page.getByText('DeepSeek Chat', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`models-${label}.png`)
      await page.getByLabel('使用 推理助手', { exact: true }).check()
      await page.getByRole('button', { name: '保存选择', exact: true }).click()
      await expect(page.getByText('可用模型已更新。')).toBeVisible()
      await page.getByRole('tab', { name: '用量', exact: true }).click()
      await expect(
        page.getByText('1 个回合未取得完整计量，估计占额 65,536 token。精确用量尚不完整。'),
      ).toBeVisible()
      await expect(page).toHaveScreenshot(`usage-${label}.png`)
      expect(state.errors).toEqual([])
    })
  })
}
