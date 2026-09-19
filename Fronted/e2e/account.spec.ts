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

  await page.goto('/settings')
  await expect(page.getByRole('heading', { name: '设置' })).toBeVisible()
  await page.getByLabel('教学人设与偏好').fill('先举例，再解释定义')
  await page.getByLabel('删除记忆').uncheck()
  await page.getByRole('button', { name: '保存智能体配置' }).click()
  await expect(page.getByRole('button', { name: '已保存' })).toBeVisible()

  const settings = gateway.requests.find(
    (request) => request.path === '/agent/settings' && request.method === 'PUT',
  )
  expect(JSON.parse(settings?.body ?? '{}')).toEqual({
    model: 'glm-4.7',
    persona: '先举例，再解释定义',
    enabled_tools: ['rag__rag_search', 'memory__memory_store'],
  })
})
