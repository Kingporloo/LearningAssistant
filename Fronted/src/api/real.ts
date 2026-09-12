/**
 * 真实服务客户端。联调时在 .env 设 VITE_USE_MOCK=false，UserServer 与 Agent
 * 网关可分别通过 VITE_USER_API_BASE_URL、VITE_API_BASE_URL 配置。
 *
 * 约定（对齐后端设计）：
 * - 认证：Authorization: Bearer <token>
 * - 聊天：POST /sessions/:id/runs，响应为 text/event-stream，
 *   请求携带稳定 request_id / message_id，事件与 Python AgentLoop 输出一致
 */

import type {
  AuthResult,
  ChangePasswordRequest,
  ChatMessage,
  DocumentItem,
  LoginRequest,
  RegisterRequest,
  SessionInfo,
  UpdateProfileRequest,
  User,
} from './types'
import type {
  ApiClient,
  ChatRunHandle,
  ChatRunParams,
} from './client'
import { ApiRequestError, getToken } from './client'

const AGENT_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '/api'
const USER_BASE_URL: string = import.meta.env.VITE_USER_API_BASE_URL ?? AGENT_BASE_URL

async function request<T>(
  path: string,
  init?: RequestInit,
  baseUrl: string = AGENT_BASE_URL,
): Promise<T> {
  const token = getToken()
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  })
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const body = (await response.json()) as { message?: string }
      if (body.message) message = body.message
    } catch {
      // 保留默认 message
    }
    throw new ApiRequestError(response.status, message)
  }
  return (await response.json()) as T
}

/** 解析 text/event-stream 为事件回调 */
async function consumeSse(
  response: Response,
  onEvent: (event: { type: string; data: unknown }) => void,
  signal: AbortSignal,
): Promise<void> {
  if (!response.body) {
    throw new ApiRequestError(502, '网关没有返回事件流')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let terminal = false
  try {
    for (;;) {
      if (signal.aborted) throw new DOMException('已中止', 'AbortError')
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let boundary = buffer.indexOf('\n\n')
      while (boundary !== -1) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        boundary = buffer.indexOf('\n\n')
        let type = 'message'
        let data = ''
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) type = line.slice(6).trim()
          else if (line.startsWith('data:')) data += line.slice(5).trim()
        }
        if (data) {
          let parsed: unknown
          try {
            parsed = JSON.parse(data)
          } catch {
            // 跳过无法解析的块
            continue
          }
          onEvent({ type, data: parsed })
          terminal = terminal || type === 'run_finished'
        }
      }
    }
    if (!terminal) {
      throw new ApiRequestError(502, 'Agent 事件流未正常结束')
    }
  } finally {
    reader.releaseLock()
  }
}

export function createRealApiClient(): ApiClient {
  return {
    async register(req: RegisterRequest): Promise<AuthResult> {
      return request(
        '/auth/register',
        {
          method: 'POST',
          body: JSON.stringify(req),
        },
        USER_BASE_URL,
      )
    },

    async login(req: LoginRequest): Promise<AuthResult> {
      return request(
        '/auth/login',
        {
          method: 'POST',
          body: JSON.stringify(req),
        },
        USER_BASE_URL,
      )
    },

    async logout(): Promise<void> {
      await request('/auth/logout', { method: 'POST' }, USER_BASE_URL)
    },

    async me(): Promise<User> {
      return request('/auth/me', undefined, USER_BASE_URL)
    },

    async updateProfile(req: UpdateProfileRequest): Promise<User> {
      return request(
        '/users/me',
        {
          method: 'PATCH',
          body: JSON.stringify(req),
        },
        USER_BASE_URL,
      )
    },

    async changePassword(req: ChangePasswordRequest): Promise<void> {
      await request(
        '/users/me/password',
        {
          method: 'POST',
          body: JSON.stringify(req),
        },
        USER_BASE_URL,
      )
    },

    async listSessions(): Promise<SessionInfo[]> {
      return request('/sessions')
    },

    async createSession(title?: string): Promise<SessionInfo> {
      return request('/sessions', {
        method: 'POST',
        body: JSON.stringify({ title }),
      })
    },

    async deleteSession(sessionId: string): Promise<void> {
      await request(`/sessions/${sessionId}`, { method: 'DELETE' })
    },

    async listMessages(sessionId: string): Promise<ChatMessage[]> {
      return request(`/sessions/${sessionId}/messages`)
    },

    runChat(params: ChatRunParams): ChatRunHandle {
      const { sessionId, requestId, messageId, message, onEvent, signal } = params
      const controller = new AbortController()
      const onAbort = () => controller.abort()
      signal.addEventListener('abort', onAbort, { once: true })

      const done = (async (): Promise<'completed' | 'failed' | 'aborted'> => {
        let outcome: 'completed' | 'failed' | 'aborted' = 'completed'
        try {
          const token = getToken()
          const response = await fetch(
            `${AGENT_BASE_URL}/sessions/${sessionId}/runs`,
            {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                Accept: 'text/event-stream',
                ...(token ? { Authorization: `Bearer ${token}` } : {}),
              },
              body: JSON.stringify({
                message,
                request_id: requestId,
                message_id: messageId,
              }),
              signal: controller.signal,
            },
          )
          if (!response.ok) {
            let detail = `请求失败（${response.status}）`
            try {
              const body = (await response.json()) as { message?: string }
              if (body.message) detail = body.message
            } catch {
              // 保留默认
            }
            throw new ApiRequestError(response.status, detail)
          }
          await consumeSse(
            response,
            ({ data }) => onEvent(data as Parameters<typeof onEvent>[0]),
            controller.signal,
          )
        } catch (error) {
          if (error instanceof DOMException && error.name === 'AbortError') {
            outcome = 'aborted'
          } else {
            outcome = 'failed'
            throw error
          }
        } finally {
          signal.removeEventListener('abort', onAbort)
        }
        return outcome
      })()

      return {
        abort: () => controller.abort(),
        done,
      }
    },

    async listDocuments(): Promise<DocumentItem[]> {
      return request('/documents')
    },

    async uploadDocument(file: File): Promise<DocumentItem> {
      const token = getToken()
      const form = new FormData()
      form.append('file', file)
      const response = await fetch(`${AGENT_BASE_URL}/documents`, {
        method: 'POST',
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        body: form,
      })
      if (!response.ok) {
        let message = `上传失败（${response.status}）`
        try {
          const body = (await response.json()) as { message?: string }
          if (body.message) message = body.message
        } catch {
          // 保留默认
        }
        throw new ApiRequestError(response.status, message)
      }
      return (await response.json()) as DocumentItem
    },

    async deleteDocument(documentId: string): Promise<void> {
      await request(`/documents/${documentId}`, { method: 'DELETE' })
    },
  }
}
