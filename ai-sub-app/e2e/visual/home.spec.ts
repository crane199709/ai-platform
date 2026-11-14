import { expect, test } from '@playwright/test'

for (const [label, width, height] of [
  ['desktop', 1440, 900],
  ['laptop', 1024, 768],
] as const) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })
    test('AI workbench entry renders without calling an unfinished conversation API', async ({
      page,
    }) => {
      const errors: string[] = []
      const businessRequests: string[] = []
      page.on('pageerror', (error) => errors.push(error.message))
      page.on('request', (request) => {
        if (new URL(request.url()).pathname.startsWith('/api/ai/'))
          businessRequests.push(request.url())
      })
      await page.addInitScript(() =>
        localStorage.setItem(
          'forge.tenant.session',
          JSON.stringify({
            accessToken: 'visual',
            refreshToken: 'visual',
            membershipId: 'm',
            tenantId: 't',
            tenantName: '示例租户',
            identityName: '测试成员',
          }),
        ),
      )
      await page.route(
        (url) => url.pathname.includes('/iam/'),
        (route) =>
          route.fulfill({
            json: {
              code: 'ok',
              httpStatus: 200,
              data: { permissions: ['ai:conversation:use'], menus: [], subApps: [], layout: null },
              traceId: 'visual',
            },
          }),
      )
      await page.goto('/apps/ai/home')
      await expect(page).toHaveURL(/\/apps\/ai\/home$/)
      await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()
      await expect(page.getByText('暂无会话', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`home-${label}.png`)
      expect(errors).toEqual([])
      expect(businessRequests).toEqual([])
    })
  })
}
