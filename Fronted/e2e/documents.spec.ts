import { expect, test } from '@playwright/test'
import { authenticate, installFakeGateway } from './support/fakeGateway'

test('上传文档、轮询建库状态、重建并删除', async ({ page }) => {
  await authenticate(page)
  const gateway = await installFakeGateway(page)
  await page.goto('/documents')

  await expect(page.getByRole('heading', { name: '我的文档' })).toBeVisible()
  await page.locator('input[type="file"]').setInputFiles({
    name: 'lesson.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from('# 注意力机制\n这是一份端到端测试资料。'),
  })

  await expect(page.getByText('lesson.md')).toBeVisible()
  await expect(page.getByText('已就绪')).toBeVisible({ timeout: 4_000 })
  await expect(page.getByText('12 个知识块')).toBeVisible()
  await expect(page.getByText('约 3 页')).toBeVisible()

  const upload = gateway.requests.find(
    (request) => request.path === '/documents' && request.method === 'POST',
  )
  expect(upload).toMatchObject({
    authorization: 'Bearer e2e-token',
    contentType: 'application/octet-stream',
  })
  expect(upload?.body).toContain('注意力机制')

  await page.getByTitle('重新构建索引').click()
  await expect(page.getByText('构建索引')).toBeVisible()
  await expect(page.getByText('已就绪')).toBeVisible({ timeout: 4_000 })

  await page.getByTitle('删除文档').click()
  await page.getByTitle('再次点击确认删除').click()
  await expect(page.getByText('还没有文档，上传第一个学习资料开始构建知识库')).toBeVisible()
})
