import { expect, test } from '@playwright/test'
import {
  agentEvents,
  authenticate,
  installFakeGateway,
  SESSION_ID,
} from './support/fakeGateway'

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

test('刷新页面后等待原运行完成并重放结果', async ({ page }) => {
  await authenticate(page)
  await installFakeGateway(page)

  await page.route(/\/api\/sessions\/[^/]+\/messages$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 'message-recover',
          requestId: 'request-recover',
          role: 'user',
          content: '恢复之前的问题',
          createdAt: '2026-09-18T08:00:00Z',
        },
      ]),
    })
  })

  let runAttempts = 0
  await page.route(/\/api\/sessions\/[^/]+\/runs$/, async (route) => {
    runAttempts += 1
    const body = route.request().postDataJSON() as {
      request_id: string
      message_id: string
    }
    if (runAttempts === 1) {
      const events = agentEvents(body.request_id, body.message_id)
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream; charset=utf-8',
        body: events.slice(0, events.indexOf('event: tool_finished')),
      })
      return
    }
    if (runAttempts === 2) {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ message: '相同请求正在执行' }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream; charset=utf-8',
      body: agentEvents(body.request_id, body.message_id),
    })
  })

  await page.goto(`/chat/${SESSION_ID}`)

  await expect(page.getByText('恢复之前的问题', { exact: true })).toBeVisible()
  await expect(page.getByText('注意力机制可以理解为“按相关性分配注意力”。'))
    .toBeVisible({ timeout: 10_000 })
  await expect(page.getByText('知识库检索')).toHaveCount(1)
  expect(runAttempts).toBe(3)
})
