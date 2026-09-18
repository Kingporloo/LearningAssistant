import { expect, test } from '@playwright/test'
import { authenticate, installFakeGateway } from './support/fakeGateway'

test('创建会话、消费 Agent SSE、展示工具结果并手动压缩', async ({ page }) => {
  await authenticate(page)
  const gateway = await installFakeGateway(page)
  await page.goto('/')

  await page.getByRole('button', { name: '新建会话' }).click()
  await expect(page).toHaveURL(/\/chat\/session_20260918_160000_user-e2e$/)

  const question = '请结合资料解释注意力机制'
  await page.getByPlaceholder(/输入你的问题/).fill(question)
  await page.getByTitle('发送').click()

  await expect(page.getByText(question, { exact: true })).toBeVisible()
  await expect(page.getByText('知识库检索')).toBeVisible()
  await expect(page.getByText('注意力机制可以理解为“按相关性分配注意力”。')).toBeVisible()
  await expect(page.getByText('600 tokens')).toBeVisible()
  await expect(page.getByText('2 步推理')).toBeVisible()
  await expect(page.getByText('1 轮工具调用')).toBeVisible()

  await page.getByText('知识库检索').click()
  await expect(page.getByText('lesson.md')).toBeVisible()
  await expect(page.getByText('第 2 页')).toBeVisible()
  await expect(page.getByText('相关度 0.94')).toBeVisible()

  await page.getByRole('button', { name: '压缩上下文' }).click()
  await expect(page.getByText('上下文已从 6200 压缩到 1700 token')).toBeVisible()

  const run = gateway.requests.find((request) => request.path.endsWith('/runs'))
  expect(run).toMatchObject({
    method: 'POST',
    authorization: 'Bearer e2e-token',
    contentType: 'application/json',
  })
  expect(JSON.parse(run?.body ?? '{}')).toMatchObject({ message: question })
})
