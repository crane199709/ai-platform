import { expect, test } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'

const output = process.env.AI_E2E_OUTPUT!

test('真实平台控制台新增、保留凭据编辑和禁用模型', async ({ page }) => {
  const environment = JSON.parse(readFileSync(`${output}/browser-env.json`, 'utf8'))
  expect(
    environment.platformConsole,
    'FullStack 提供真实控制台地址与 IAM Operator 会话',
  ).toBeDefined()
  const { url, session, modelBaseUrl } = environment.platformConsole
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.addInitScript((value) => {
    localStorage.setItem('forge.platform.session', JSON.stringify(value))
  }, session)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) errors.push(message.text())
  })
  const name = '真实控制台凭据验收'
  const editedName = `${name}已编辑`
  const apiKey = 'deterministic-console-api-key'
  const modelId = 'local-console-credential-check'
  const modelsPath = '/api/ai-platform/models'
  const list = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === modelsPath && response.request().method() === 'GET',
  )
  await page.goto(`${url}/ai-models`)
  expect((await list).status()).toBe(200)
  await expect(page).toHaveURL(`${url}/ai-models`)
  await expect(page).toHaveTitle('SaaS Forge 平台控制台')
  await expect(page.getByRole('heading', { name: 'AI 模型', exact: true })).toBeVisible()
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  await page.getByRole('button', { name: '新增模型', exact: true }).click()
  let dialog = page.getByRole('dialog', { name: '新增模型', exact: true })
  await dialog.locator('#ai-model-name').fill(name)
  await dialog.locator('#ai-model-model-id').fill(modelId)
  await dialog.locator('#ai-model-base-url').fill(modelBaseUrl)
  await dialog.locator('#ai-model-api-key').fill(apiKey)
  const created = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === modelsPath && response.request().method() === 'POST',
  )
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  const createResponse = await created
  expect(createResponse.status()).toBe(201)
  expect(createResponse.request().postDataJSON()).toMatchObject({ apiKey, name, status: 'ENABLED' })
  expect(createResponse.request().postDataJSON()).not.toHaveProperty('secretRef')
  const model = (await createResponse.json()).data
  expect(model.credentialKind).toBe('API_KEY')
  const assertRedacted = (value: object) => {
    for (const field of ['apiKey', 'secretRef', 'credentialCipher'])
      expect(value).not.toHaveProperty(field)
    expect(JSON.stringify(value)).not.toContain(apiKey)
  }
  assertRedacted(model)
  await expect(dialog).not.toBeVisible()
  const row = page.getByRole('row').filter({ hasText: name })
  await expect(row).toContainText('直接密钥')
  await page.screenshot({
    path: `${output}/platform-model-created.png`,
    fullPage: false,
    animations: 'disabled',
  })
  const reloaded = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === modelsPath && response.request().method() === 'GET',
  )
  await page.reload()
  const listed = (await (await reloaded).json()).data.find(
    (item: { id: string }) => item.id === model.id,
  )
  expect(listed.credentialKind).toBe('API_KEY')
  assertRedacted(listed)
  await page.getByTestId(`ai-model-edit-${model.id}`).click()
  dialog = page.getByRole('dialog', { name: `编辑模型：${name}`, exact: true })
  await expect(dialog.locator('#ai-model-api-key')).toHaveValue('')
  await dialog.locator('#ai-model-name').fill(editedName)
  const updated = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `${modelsPath}/${model.id}` &&
      response.request().method() === 'PUT',
  )
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  const updateResponse = await updated
  expect(updateResponse.status()).toBe(200)
  const updateRequest = updateResponse.request().postDataJSON()
  expect(updateRequest).toMatchObject({ name: editedName, status: 'ENABLED' })
  expect(updateRequest).not.toHaveProperty('apiKey')
  expect(updateRequest).not.toHaveProperty('secretRef')
  const edited = (await updateResponse.json()).data
  expect(edited).toMatchObject({ name: editedName, credentialKind: 'API_KEY' })
  assertRedacted(edited)
  await expect(dialog).not.toBeVisible()
  await page.getByTestId(`ai-model-status-${model.id}`).click()
  const confirmation = page.getByRole('dialog', { name: `禁用模型：${editedName}`, exact: true })
  await expect(confirmation).toContainText('禁用后所有租户的后续模型调用都会失效')
  await page.screenshot({
    path: `${output}/platform-model-disable-confirm.png`,
    fullPage: false,
    animations: 'disabled',
  })
  const disabled = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `${modelsPath}/${model.id}` &&
      response.request().method() === 'PUT',
  )
  await confirmation.getByRole('button', { name: '确认禁用', exact: true }).click()
  const disableResponse = await disabled
  expect(disableResponse.status()).toBe(200)
  const disableRequest = disableResponse.request().postDataJSON()
  expect(disableRequest).toMatchObject({ name: editedName, status: 'DISABLED' })
  expect(disableRequest).not.toHaveProperty('apiKey')
  expect(disableRequest).not.toHaveProperty('secretRef')
  const disabledModel = (await disableResponse.json()).data
  expect(disabledModel).toMatchObject({ status: 'DISABLED', credentialKind: 'API_KEY' })
  assertRedacted(disabledModel)
  await expect(confirmation).not.toBeVisible()
  await expect(
    page.getByTestId(`ai-model-status-${model.id}`).getByRole('switch'),
  ).not.toBeChecked()
  await page.screenshot({
    path: `${output}/platform-model-disabled.png`,
    fullPage: false,
    animations: 'disabled',
  })
  expect(errors).toEqual([])
  writeFileSync(
    `${output}/platform-model-browser.json`,
    JSON.stringify(
      {
        url: page.url(),
        title: await page.title(),
        width: 1440,
        consoleErrors: errors,
        login: 'real IAM Operator PKCE',
        createStatus: createResponse.status(),
        updateStatus: updateResponse.status(),
        disableStatus: disableResponse.status(),
        model: disabledModel,
        editPreservedCredential: !('apiKey' in updateRequest) && !('secretRef' in updateRequest),
      },
      null,
      2,
    ),
  )
})
