/**
 * Mock API 客户端：localStorage 持久化 + 模拟 SSE 流。
 */

import type {
  AgentEventEnvelope,
  AuthResult,
  ChangePasswordRequest,
  ChatMessage,
  DocumentItem,
  LoginRequest,
  RegisterRequest,
  RunSegment,
  SessionInfo,
  ToolCallView,
  UpdateProfileRequest,
  User,
} from '../types'
import type {
  ApiClient,
  ChatRunHandle,
  ChatRunParams,
} from '../client'
import { ApiRequestError as RequestError, getToken } from '../client'
import { MockDb, id, seedDemoData } from './db'
import { mockAgentEvents } from './agent'

const db = new MockDb()
seedDemoData(db)

function requireUserId(): string {
  const token = getToken()
  if (!token) throw new RequestError(401, '未登录')
  const userId = db.resolveToken(token)
  if (!userId) throw new RequestError(401, '登录已过期，请重新登录')
  return userId
}

function stripUser(user: { id: string; username: string; nickname: string; createdAt: string; password: string }): User {
  const { id: userId, username, nickname, createdAt } = user
  return { id: userId, username, nickname, createdAt }
}

const USERNAME_RE = /^[a-zA-Z0-9_]{3,24}$/

// ---------- 文档状态推进定时器（模拟后端异步构建） ----------

function scheduleBuild(userId: string, docId: string): void {
  setTimeout(() => {
    db.updateDocument(userId, docId, { status: 'building' })
  }, 1800)
  setTimeout(() => {
    const doc = db.listDocuments(userId).find((d) => d.id === docId)
    if (!doc || doc.status === 'failed') return
    db.updateDocument(userId, docId, {
      status: 'ready',
      chunkCount: Math.max(8, Math.round((doc.pageCount ?? 10) * 5.5)),
      readyAt: new Date().toISOString(),
    })
  }, 4600)
}

export function createMockApiClient(): ApiClient {
  return {
    // ---- 认证 ----

    async register(req: RegisterRequest): Promise<AuthResult> {
      await delay(300)
      if (!USERNAME_RE.test(req.username)) {
        throw new RequestError(400, '用户名需为 3-24 位字母、数字或下划线')
      }
      if (req.password.length < 6) {
        throw new RequestError(400, '密码至少 6 位')
      }
      if (db.findUser(req.username)) {
        throw new RequestError(400, '用户名已被占用')
      }
      const user = db.createUser(
        req.username,
        req.password,
        req.nickname?.trim() || req.username,
      )
      return { token: db.issueToken(user.id), user: stripUser(user) }
    },

    async login(req: LoginRequest): Promise<AuthResult> {
      await delay(300)
      const user = db.findUser(req.username)
      if (!user || user.password !== req.password) {
        throw new RequestError(401, '用户名或密码错误')
      }
      return { token: db.issueToken(user.id), user: stripUser(user) }
    },

    async logout(): Promise<void> {
      const token = getToken()
      if (token) db.revokeToken(token)
    },

    async me(): Promise<User> {
      const userId = requireUserId()
      const user = db.listUsers().find((u) => u.id === userId)
      if (!user) throw new RequestError(401, '用户不存在')
      return stripUser(user)
    },

    async updateProfile(req: UpdateProfileRequest): Promise<User> {
      const userId = requireUserId()
      if (req.nickname !== undefined) {
        const nickname = req.nickname.trim()
        if (nickname.length < 1 || nickname.length > 24) {
          throw new RequestError(400, '昵称需为 1-24 个字符')
        }
        const user = db.updateUserProfile(userId, { nickname })
        if (!user) throw new RequestError(404, '用户不存在')
        return stripUser(user)
      }
      const current = db.listUsers().find((u) => u.id === userId)
      if (!current) throw new RequestError(404, '用户不存在')
      return stripUser(current)
    },

    async changePassword(req: ChangePasswordRequest): Promise<void> {
      await delay(200)
      const userId = requireUserId()
      const user = db.listUsers().find((u) => u.id === userId)
      if (!user) throw new RequestError(404, '用户不存在')
      if (user.password !== req.oldPassword) {
        throw new RequestError(400, '旧密码不正确')
      }
      if (req.newPassword.length < 6) {
        throw new RequestError(400, '新密码至少 6 位')
      }
      db.updateUserProfile(userId, { password: req.newPassword })
    },

    // ---- 会话 ----

    async listSessions(): Promise<SessionInfo[]> {
      const userId = requireUserId()
      return [...db.listSessions(userId)].sort((a, b) =>
        b.updatedAt.localeCompare(a.updatedAt),
      )
    },

    async createSession(title?: string): Promise<SessionInfo> {
      const userId = requireUserId()
      return db.createSession(userId, title ?? '新会话')
    },

    async deleteSession(sessionId: string): Promise<void> {
      const userId = requireUserId()
      db.deleteSession(userId, sessionId)
    },

    async listMessages(sessionId: string): Promise<ChatMessage[]> {
      const userId = requireUserId()
      return db.listMessages(userId, sessionId)
    },

    // ---- 聊天 ----

    runChat(params: ChatRunParams): ChatRunHandle {
      const { sessionId, message, onEvent, signal } = params
      const controller = new AbortController()
      const onAbort = () => controller.abort()
      signal.addEventListener('abort', onAbort, { once: true })

      const done = (async (): Promise<'completed' | 'failed' | 'aborted'> => {
        const userId = requireUserId()
        const session = db
          .listSessions(userId)
          .find((s) => s.id === sessionId)
        if (!session) throw new RequestError(404, '会话不存在')

        // 持久化用户消息
        const isFirstMessage = db.listMessages(userId, sessionId).length === 0
        const userMessage: ChatMessage = {
          id: id('msg'),
          role: 'user',
          content: message,
          createdAt: new Date().toISOString(),
        }
        db.appendMessage(userId, sessionId, userMessage)
        db.touchSession(userId, sessionId, {
          title: isFirstMessage ? message.slice(0, 24) : undefined,
          bumpMessageCount: true,
        })

        // 消费事件流，同步累积最终形态用于持久化
        const segments: RunSegment[] = []
        const toolCalls: ToolCallView[] = []
        let finalContent = ''
        let status: 'completed' | 'failed' = 'completed'
        let assistantId = id('msg')

        const consume = (event: AgentEventEnvelope) => {
          switch (event.type) {
            case 'run_started':
              assistantId = String(event.payload.message_id ?? assistantId)
              break
            case 'text_delta': {
              const delta = String(event.payload.delta ?? '')
              const step = Number(event.payload.model_step ?? 1)
              const last = segments[segments.length - 1]
              if (last && last.kind === 'text' && last.modelStep === step) {
                last.text += delta
              } else {
                segments.push({ kind: 'text', text: delta, modelStep: step })
              }
              break
            }
            case 'tool_started': {
              const call: ToolCallView = {
                toolCallId: String(event.payload.tool_call_id ?? ''),
                name: String(event.payload.name ?? ''),
                arguments: (event.payload.arguments as Record<string, unknown>) ?? {},
                status: 'skipped',
              }
              if (event.payload.outcome === 'skipped') call.status = 'skipped'
              else call.status = 'running'
              segments.push({ kind: 'tool', toolCall: call })
              toolCalls.push(call)
              break
            }
            case 'tool_finished': {
              const call = toolCalls.find(
                (c) => c.toolCallId === event.payload.tool_call_id,
              )
              if (call) {
                call.status = event.payload.outcome === 'completed' ? 'completed' : 'error'
                call.outcome = String(event.payload.outcome ?? '')
                call.businessStatus =
                  (event.payload.business_status as string | null) ?? null
                call.content = String(event.payload.content ?? '')
                call.result = event.payload.result
              }
              break
            }
            case 'message_completed':
              finalContent = String(event.payload.content ?? '')
              break
            case 'error':
              status = 'failed'
              break
            default:
              break
          }
          onEvent(event)
        }

        try {
          for await (const event of mockAgentEvents({
            db,
            userId,
            sessionId,
            message,
            signal: controller.signal,
          })) {
            consume(event)
          }
        } catch (error) {
          if (controller.signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) {
            status = 'failed'
            const finalMessage: ChatMessage = {
              id: assistantId,
              role: 'assistant',
              status: 'failed',
              content: finalContent,
              segments,
              createdAt: new Date().toISOString(),
              error: { code: 'aborted', message: '已停止生成', retryable: true },
            }
            db.finalizeMessage(userId, sessionId, finalMessage)
            db.touchSession(userId, sessionId, { bumpMessageCount: true })
            return 'aborted'
          }
          throw error
        }

        const finalMessage: ChatMessage = {
          id: assistantId,
          role: 'assistant',
          status,
          content: finalContent,
          segments,
          createdAt: new Date().toISOString(),
        }
        db.finalizeMessage(userId, sessionId, finalMessage)
        db.touchSession(userId, sessionId, { bumpMessageCount: true })
        return status
      })().finally(() => {
        signal.removeEventListener('abort', onAbort)
      })

      return {
        abort: () => controller.abort(),
        done,
      }
    },

    // ---- 文档 ----

    async listDocuments(): Promise<DocumentItem[]> {
      const userId = requireUserId()
      return [...db.listDocuments(userId)].sort((a, b) =>
        b.createdAt.localeCompare(a.createdAt),
      )
    },

    async uploadDocument(file: File): Promise<DocumentItem> {
      const userId = requireUserId()
      const lower = file.name.toLowerCase()
      if (!/\.(pdf|md|txt)$/.test(lower)) {
        throw new RequestError(400, '仅支持 PDF / Markdown / TXT 文件')
      }
      if (file.size > 50 * 1024 * 1024) {
        throw new RequestError(400, '文件大小不能超过 50MB')
      }
      await delay(500)
      const doc: DocumentItem = {
        id: id('doc'),
        name: file.name,
        size: file.size,
        status: 'converting',
        pageCount: Math.min(120, 6 + Math.floor(file.size / 40_000)),
        createdAt: new Date().toISOString(),
      }
      db.addDocument(userId, doc)
      scheduleBuild(userId, doc.id)
      return { ...doc }
    },

    async deleteDocument(documentId: string): Promise<void> {
      const userId = requireUserId()
      db.deleteDocument(userId, documentId)
    },
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}
