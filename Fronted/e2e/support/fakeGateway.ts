import type { Page, Request, Route } from '@playwright/test'

type User = {
  id: string
  username: string
  nickname: string
  createdAt: string
}

type Session = {
  id: string
  title: string
  createdAt: string
  updatedAt: string
  messageCount: number
}

type Document = {
  id: string
  name: string
  size: number
  status: 'building' | 'ready' | 'failed'
  chunkCount?: number
  pageCount?: number
  createdAt: string
  readyAt?: string
}

export type CapturedRequest = {
  method: string
  path: string
  authorization: string | undefined
  contentType: string | undefined
  body: string | null
}

export type FakeGateway = {
  user: User
  sessions: Session[]
  documents: Document[]
  requests: CapturedRequest[]
}

const NOW = '2026-09-18T08:00:00Z'
const SESSION_ID = 'session_20260918_160000_user-e2e'

export async function installFakeGateway(page: Page): Promise<FakeGateway> {
  const state: FakeGateway = {
    user: {
      id: 'user-e2e',
      username: 'student',
      nickname: '测试同学',
      createdAt: NOW,
    },
    sessions: [],
    documents: [],
    requests: [],
  }

  await page.route(/^http:\/\/127\.0\.0\.1:4173\/api(?:\/|$)/, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api/, '')
    state.requests.push(capture(request, path))

    if (path === '/auth/register' && request.method() === 'POST') {
      const body = request.postDataJSON() as {
        username: string
        nickname?: string
      }
      state.user = {
        ...state.user,
        username: body.username,
        nickname: body.nickname ?? body.username,
      }
      await json(route, { token: 'e2e-token', user: state.user })
      return
    }
    if (path === '/auth/login' && request.method() === 'POST') {
      await json(route, { token: 'e2e-token', user: state.user })
      return
    }
    if (path === '/auth/me' && request.method() === 'GET') {
      await json(route, state.user)
      return
    }
    if (path === '/auth/logout' && request.method() === 'POST') {
      await route.fulfill({ status: 204 })
      return
    }
    if (path === '/users/me' && request.method() === 'PATCH') {
      const body = request.postDataJSON() as { nickname: string }
      state.user = { ...state.user, nickname: body.nickname }
      await json(route, state.user)
      return
    }
    if (path === '/users/me/password' && request.method() === 'POST') {
      await route.fulfill({ status: 204 })
      return
    }

    if (path === '/sessions' && request.method() === 'GET') {
      await json(route, state.sessions)
      return
    }
    if (path === '/sessions' && request.method() === 'POST') {
      const session: Session = {
        id: SESSION_ID,
        title: '新会话',
        createdAt: NOW,
        updatedAt: NOW,
        messageCount: 0,
      }
      state.sessions = [session]
      await json(route, session, 201)
      return
    }
    if (/^\/sessions\/[^/]+\/messages$/.test(path) && request.method() === 'GET') {
      await json(route, [])
      return
    }
    if (/^\/sessions\/[^/]+\/compact$/.test(path) && request.method() === 'POST') {
      await json(route, {
        status: 'saved',
        trigger: 'manual',
        reason: null,
        beforeTokens: 6200,
        afterTokens: 1700,
        compactTriggerTokens: 5900,
        belowTrigger: true,
        summarySaveStatus: 'saved',
        sessionSummary: { version: 1 },
        compact: { status: 'saved' },
      })
      return
    }
    if (/^\/sessions\/[^/]+\/runs$/.test(path) && request.method() === 'POST') {
      const body = request.postDataJSON() as {
        request_id: string
        message_id: string
      }
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream; charset=utf-8',
        headers: { 'Cache-Control': 'no-cache' },
        body: agentEvents(body.request_id, body.message_id),
      })
      return
    }
    if (/^\/sessions\/[^/]+$/.test(path) && request.method() === 'DELETE') {
      state.sessions = []
      await route.fulfill({ status: 204 })
      return
    }

    if (path === '/documents' && request.method() === 'GET') {
      state.documents = state.documents.map((document) =>
        document.status === 'building'
          ? {
              ...document,
              status: 'ready',
              chunkCount: 12,
              pageCount: 3,
              readyAt: NOW,
            }
          : document,
      )
      await json(route, state.documents)
      return
    }
    if (path === '/documents' && request.method() === 'POST') {
      const document: Document = {
        id: 'document-e2e',
        name: url.searchParams.get('filename') ?? 'lesson.md',
        size: request.postDataBuffer()?.byteLength ?? 0,
        status: 'building',
        createdAt: NOW,
      }
      state.documents = [document]
      await json(route, document, 202)
      return
    }
    if (/^\/documents\/[^/]+\/rebuild$/.test(path) && request.method() === 'POST') {
      const document = { ...state.documents[0], status: 'building' as const }
      state.documents = [document]
      await json(route, document, 202)
      return
    }
    if (/^\/documents\/[^/]+$/.test(path) && request.method() === 'PUT') {
      const document = {
        ...state.documents[0],
        name: url.searchParams.get('filename') ?? state.documents[0].name,
        status: 'building' as const,
      }
      state.documents = [document]
      await json(route, document, 202)
      return
    }
    if (/^\/documents\/[^/]+$/.test(path) && request.method() === 'DELETE') {
      state.documents = []
      await route.fulfill({ status: 204 })
      return
    }

    await json(route, { message: `未模拟的接口：${request.method()} ${path}` }, 404)
  })
  return state
}

export async function authenticate(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem('pdflearning.token', 'e2e-token')
  })
}

function capture(request: Request, path: string): CapturedRequest {
  return {
    method: request.method(),
    path,
    authorization: request.headers()['authorization'],
    contentType: request.headers()['content-type'],
    body: request.postData(),
  }
}

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

function agentEvents(requestId: string, messageId: string): string {
  const result = {
    status: 'ok',
    query: '解释注意力机制',
    results: [
      {
        chunk_id: 'chunk-1',
        document_id: 'document-e2e',
        source: 'lesson.md',
        page: 2,
        text: '注意力机制会根据查询与键的相关性，为不同信息分配权重。',
        score: 0.94,
      },
    ],
  }
  const envelopes = [
    envelope('run_started', requestId, 1, { message_id: messageId }),
    envelope('model_step_started', requestId, 2, {
      model_step: 1,
      input_tokens: 320,
      tools_enabled: true,
    }),
    envelope('tool_started', requestId, 3, {
      model_step: 1,
      tool_call_id: 'tool-1',
      name: 'rag__rag_search',
      arguments: { query: '解释注意力机制' },
    }),
    envelope('tool_finished', requestId, 4, {
      tool_call_id: 'tool-1',
      name: 'rag__rag_search',
      outcome: 'success',
      business_status: 'ok',
      content: JSON.stringify(result),
      result,
    }),
    envelope('text_delta', requestId, 5, {
      model_step: 2,
      delta: '注意力机制可以理解为“按相关性分配注意力”。',
    }),
    envelope('message_completed', requestId, 6, {
      model_step: 2,
      content: '注意力机制可以理解为“按相关性分配注意力”。',
      usage: { input_tokens: 520, output_tokens: 80, total_tokens: 600 },
    }),
    envelope('run_finished', requestId, 7, {
      status: 'completed',
      model_steps: 2,
      tool_rounds: 1,
      usage: { input_tokens: 520, output_tokens: 80, total_tokens: 600 },
    }),
  ]
  return envelopes.map(({ type, data }) => `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`).join('')
}

function envelope(
  type: string,
  requestId: string,
  eventSeq: number,
  payload: Record<string, unknown>,
): { type: string; data: Record<string, unknown> } {
  return {
    type,
    data: {
      type,
      request_id: requestId,
      session_id: SESSION_ID,
      event_seq: eventSeq,
      payload,
    },
  }
}
