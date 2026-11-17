import { expect, test } from '@playwright/test'
import { writeFileSync } from 'node:fs'

for (const width of [1440, 1024]) {
  test(`独立宿主真实登录、会话HTTP与配套主题 ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
    const successful: string[] = []
    const anonymousPaths = new Set([
      '/api/ai/conversations',
      '/api/ai/models',
      '/api/ai/prompts/published',
    ])
    const anonymousResponses = new Set<string>()
    const anonymousConsole = new Set<string>()
    let authenticated = false
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('console', (message) => {
      if (!['error', 'warning'].includes(message.type())) return
      // 未登录的开发首页会先请求业务列表；仅接受此阶段对应的401网络日志。
      const url = message.location().url
      const path = url ? new URL(url).pathname : ''
      if (!authenticated && /status of 401/.test(message.text()) && anonymousPaths.has(path)) {
        anonymousConsole.add(path)
        return
      }
      errors.push(message.text())
    })
    page.on('response', (response) => {
      const path = new URL(response.url()).pathname
      if (!authenticated && response.status() === 401 && anonymousPaths.has(path))
        anonymousResponses.add(path)
      if (response.url().includes('/api/ai/') && response.ok()) successful.push(response.url())
    })
    await page.goto('/apps/ai/home')
    await expect(page.getByText('Sub-App 开发宿主 · ai', { exact: true })).toBeVisible()
    await expect(page).toHaveTitle('ai Sub-App 开发宿主')
    await page.getByRole('textbox', { name: '账号', exact: true }).fill('ai-reader')
    await page.getByLabel('密码', { exact: true }).fill('Demo-password-123!')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByRole('button', { name: '退出', exact: true })).toBeVisible()
    await expect(page.getByText('AI 只读成员 @ AI 验收租户 A', { exact: true })).toBeVisible()
    authenticated = true
    const models = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/ai/models',
    )
    await page.reload()
    expect((await models).status()).toBe(200)
    await expect(page.getByRole('heading', { name: 'AI 工作台', exact: true })).toBeVisible()
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    await page.getByRole('button', { name: '新建会话', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '新建会话' })
    const title = `候选独立宿主-${width}`
    await dialog.locator('input').first().fill(title)
    const created = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/ai/conversations' &&
        response.request().method() === 'POST',
    )
    await dialog.getByRole('button', { name: '创建', exact: true }).click()
    const response = await created
    expect(response.status()).toBe(201)
    const id = (await response.json()).data.id
    await expect(page.getByTestId(`conversation-${id}`)).toContainText(title)
    await expect(page.locator('.el-overlay:visible')).toHaveCount(0)
    await expect(page.locator('.el-loading-mask:visible')).toHaveCount(0)
    const primary = await page.evaluate(() =>
      getComputedStyle(document.documentElement)
        .getPropertyValue('--el-color-primary')
        .trim()
        .toLowerCase(),
    )
    expect(primary).toBe('#4f46e5')
    expect(successful.length).toBeGreaterThan(0)
    expect([...anonymousConsole].filter((path) => !anonymousResponses.has(path))).toEqual([])
    expect(errors).toEqual([])
    await page.screenshot({
      path: `${process.env.AI_E2E_OUTPUT}/dev-host-${width}.png`,
      fullPage: false,
    })
    writeFileSync(
      `${process.env.AI_E2E_OUTPUT}/dev-host-${width}.json`,
      JSON.stringify(
        {
          url: page.url(),
          title: await page.title(),
          width,
          primary,
          successful,
          anonymousResponses: [...anonymousResponses],
          anonymousConsole: [...anonymousConsole],
          errors,
          login: 'real IAM PKCE',
          backend: 'real AI HTTP',
        },
        null,
        2,
      ),
    )
  })
}
