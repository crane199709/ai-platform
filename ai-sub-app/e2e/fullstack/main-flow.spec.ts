import { expect, test } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'

const output = process.env.AI_E2E_OUTPUT!
const a = '019a2525-0000-7000-8000-000000000031'
for (const [label, width] of [
  ['desktop', 1440],
  ['laptop', 1024],
] as const) {
  test(`真实Shell登录、分批回答、MCP审批、历史与租户隔离 ${label}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
    const deniedPaths = new Set([
      '/api/ai/conversations',
      '/api/ai/models',
      '/api/ai/prompts/published',
    ])
    const deniedResponses = new Set<string>()
    const deniedConsole = new Set<string>()
    let verifyingDeniedTenant = false
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('console', (message) => {
      if (!['error', 'warning'].includes(message.type())) return
      const url = message.location().url
      const path = url ? new URL(url).pathname : ''
      if (
        verifyingDeniedTenant &&
        message.type() === 'error' &&
        message.text() ===
          'Failed to load resource: the server responded with a status of 403 (Forbidden)' &&
        deniedPaths.has(path)
      ) {
        deniedConsole.add(path)
        return
      }
      errors.push(message.text())
    })
    const remoteResponses: string[] = []
    page.on('response', (response) => {
      if (response.url().includes('5180/remoteEntry.js') && response.ok())
        remoteResponses.push(response.url())
      const path = new URL(response.url()).pathname
      if (verifyingDeniedTenant && response.status() === 403 && deniedPaths.has(path))
        deniedResponses.add(path)
    })
    await page.goto('/login?redirect=/apps/ai/home')
    await page.locator('#tenant-account').fill('ai-member')
    await page.locator('#tenant-password').fill('Demo-password-123!')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.getByTestId(`tenant-option-${a}`).click()
    await expect(page).toHaveURL(/\/apps\/ai\/home/)
    await expect(page.getByRole('heading', { name: 'AI 工作台', exact: true })).toBeVisible()
    await expect(page).toHaveTitle('SaaS Forge 租户端')
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    expect(remoteResponses.length).toBeGreaterThan(0)
    await page.getByRole('button', { name: '新建会话', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '新建会话' })
    await dialog.locator('input').first().fill(`真实链路-${label}`)
    const created = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/ai/conversations') && response.request().method() === 'POST',
    )
    await dialog.getByRole('button', { name: '创建', exact: true }).click()
    const conversation = (await (await created).json()).data.id as string
    await page.getByLabel('消息', { exact: true }).fill('请验证真实流式回答')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(page.getByLabel('消息记录')).toContainText('真实 DSH')
    await expect(page.getByLabel('消息记录')).not.toContainText('真实 DSH 分批回答已完成。')
    await expect(page.getByRole('button', { name: '取消生成', exact: true })).toBeVisible()
    writeFileSync(`${output}/stream-release`, 'continue')
    await expect(page.getByLabel('消息记录')).toContainText('真实 DSH 分批回答已完成。')
    await expect(page.getByRole('button', { name: '发送', exact: true })).toBeVisible()
    await page.screenshot({ path: `${output}/chat-${label}.png`, fullPage: false })
    await page.reload()
    await page.getByTestId(`conversation-${conversation}`).click()
    await expect(page.getByLabel('消息记录')).toContainText('真实 DSH 分批回答已完成。')
    await page.getByLabel('消息', { exact: true }).fill('APPROVAL_TOOL 查询敏感只读状态')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(page.getByRole('button', { name: '批准本次调用', exact: true })).toBeVisible()
    await page.screenshot({ path: `${output}/approval-${label}.png`, fullPage: false })
    // Upstream only receives tools/call after the real Java approval gate completes.
    const beforeApproval = JSON.parse(readFileSync(`${output}/provider-evidence.json`, 'utf8'))
    expect(beforeApproval.mcpCalls).toBe(label === 'desktop' ? 0 : 1)
    await page.getByRole('button', { name: '批准本次调用', exact: true }).click()
    await expect(page.getByLabel('消息记录')).toContainText('真实 DSH 已完成只读工具查询。')
    await expect(page.getByLabel('消息记录')).toContainText('lookup · 已完成')
    await expect(page.getByRole('button', { name: '发送', exact: true })).toBeVisible()
    await page.getByTestId('tenant-switcher').click()
    await Promise.all([
      page.waitForURL((url) => url.pathname === '/'),
      page.getByRole('option', { name: 'AI 验收租户 B', exact: true }).click(),
    ])
    await expect(page.getByTestId('tenant-switcher')).toContainText('AI 验收租户 B')
    const tenantList = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/ai/conversations' &&
        response.request().method() === 'GET',
    )
    await page.goto('/apps/ai/home')
    const tenantResponse = await tenantList
    expect((await tenantResponse.json()).data).toEqual([])
    const otherTenant = await page.request.get(
      `http://127.0.0.1:5174/api/ai/conversations/${conversation}`,
      {
        headers: { authorization: tenantResponse.request().headers().authorization },
      },
    )
    expect(otherTenant.status()).toBe(404)
    await expect(page.getByTestId(`conversation-${conversation}`)).toHaveCount(0)
    await expect(page.getByText(`真实链路-${label}`, { exact: true })).toHaveCount(0)
    await page.getByTestId('tenant-switcher').click()
    await Promise.all([
      page.waitForURL((url) => url.pathname === '/'),
      page.getByRole('option', { name: 'AI 验收租户 A', exact: true }).click(),
    ])
    await expect(page.getByTestId('tenant-switcher')).toContainText('AI 验收租户 A')
    await page.goto('/apps/ai/home')
    await page.getByTestId(`conversation-${conversation}`).click()
    await expect(page.getByLabel('消息记录')).toContainText('真实 DSH 已完成只读工具查询。')
    await page.getByRole('button', { name: '会话操作' }).click()
    await page.getByRole('menuitem', { name: '删除会话', exact: true }).click()
    await page
      .getByRole('dialog', { name: '删除会话' })
      .getByRole('button', { name: '确认删除', exact: true })
      .click()
    await expect(page.getByTestId(`conversation-${conversation}`)).toHaveCount(0)
    await page.getByTestId('tenant-switcher').click()
    verifyingDeniedTenant = true
    await Promise.all([
      page.waitForURL((url) => url.pathname === '/'),
      page.getByRole('option', { name: 'AI 无授权租户', exact: true }).click(),
    ])
    await expect(page.getByTestId('tenant-switcher')).toContainText('AI 无授权租户')
    await expect(page.getByRole('menuitem', { name: 'AI 工作台', exact: true })).toHaveCount(0)
    await page.goto('/apps/ai/home')
    await expect(page.getByText('页面不存在', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '新建会话', exact: true })).toHaveCount(0)
    await expect(page.getByText('真实 DSH 分批回答已完成。', { exact: true })).toHaveCount(0)
    await page.screenshot({ path: `${output}/no-permission-${label}.png`, fullPage: false })
    expect([...deniedConsole].filter((path) => !deniedResponses.has(path))).toEqual([])
    expect(errors).toEqual([])
    writeFileSync(
      `${output}/browser-${label}.json`,
      JSON.stringify(
        {
          url: page.url(),
          title: await page.title(),
          width,
          consoleErrors: errors,
          expectedForbiddenResponses: [...deniedResponses],
          expectedForbiddenConsole: [...deniedConsole],
          remoteResponses,
          conversation,
          login: 'real IAM PKCE',
          runtime: 'real DSH',
          isolation: 'A → B → A → C',
          provider: 'local HTTPS',
        },
        null,
        2,
      ),
    )
  })
}
