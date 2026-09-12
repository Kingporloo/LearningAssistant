/**
 * API 客户端统一接口。Mock 与真实网关实现同一份契约，
 * 切换时仅改 api/index.ts 的选择逻辑。
 */

import type {
  AgentEventEnvelope,
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

export class ApiRequestError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
  }
}

export interface ChatRunParams {
  sessionId: string
  requestId: string
  messageId: string
  message: string
  onEvent: (event: AgentEventEnvelope) => void
  signal: AbortSignal
}

export interface ChatRunHandle {
  abort(): void
  /** 流结束后 resolve：completed / failed / aborted */
  done: Promise<'completed' | 'failed' | 'aborted'>
}

export interface ApiClient {
  // ---- 认证与用户 ----
  register(req: RegisterRequest): Promise<AuthResult>
  login(req: LoginRequest): Promise<AuthResult>
  logout(): Promise<void>
  me(): Promise<User>
  updateProfile(req: UpdateProfileRequest): Promise<User>
  changePassword(req: ChangePasswordRequest): Promise<void>

  // ---- 会话 ----
  listSessions(): Promise<SessionInfo[]>
  createSession(title?: string): Promise<SessionInfo>
  deleteSession(sessionId: string): Promise<void>
  listMessages(sessionId: string): Promise<ChatMessage[]>

  // ---- 聊天 ----
  runChat(params: ChatRunParams): ChatRunHandle

  // ---- 文档 ----
  listDocuments(): Promise<DocumentItem[]>
  uploadDocument(file: File): Promise<DocumentItem>
  deleteDocument(documentId: string): Promise<void>
}

// ---- token 存取（两种实现共用） ----

const TOKEN_KEY = 'pdflearning.token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}
