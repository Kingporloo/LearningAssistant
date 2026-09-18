import { expect, test } from '@playwright/test'
import { installFakeGateway } from './support/fakeGateway'

test('受保护页面跳转登录，注册后进入个人学习空间', async ({ page }) => {
  const gateway = await installFakeGateway(page)

  await page.goto('/settings')
  await expect(page).toHaveURL(/\/login$/)
  await page.getByRole('link', { name: '注册一个' }).click()

  await page.getByLabel('用户名').fill('learner_01')
  await page.getByLabel(/昵称/).fill('小明')
  await page.getByLabel('密码', { exact: true }).fill('secret123')
  await page.getByLabel('确认密码').fill('secret123')
  await page.getByRole('button', { name: '注册并登录' }).click()

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('heading', { name: '你好，我是你的智能体老师' })).toBeVisible()
  await expect(page.getByText('小明', { exact: true })).toBeVisible()
  expect(gateway.requests.find((request) => request.path === '/auth/register')).toMatchObject({
    method: 'POST',
    authorization: undefined,
    contentType: 'application/json',
  })
  expect(
    gateway.requests.find((request) => request.path === '/sessions')?.authorization,
  ).toBe('Bearer e2e-token')
})
