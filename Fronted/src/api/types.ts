/**
 * API 契约类型定义。
 *
 * 本文件是前后端的单一契约源：SSE 事件结构严格对齐 Python AgentLoop
 * （Agent/Loop/*.py）经 Java 网关透传后的格式。未来接入真实网关时，
 * 仅需切换 api/index.ts 的实现，类型保持不变。
 */

// ============ 用户 ============

export interface User {
  id: string
  username: string
  nickname: string
  createdAt: string
}

export interface AuthResult {
  token: string
  user: User
}

export interface RegisterRequest {
  username: string
  password: string
  nickname?: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface UpdateProfileRequest {
  nickname?: string
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

// ============ 会话 ============

export interface SessionInfo {
  id: string
  title: string
  createdAt: string
  updatedAt: string
  messageCount: number
}

// ============ 消息与运行时间线 ============

export type ToolCallStatus = 'running' | 'completed' | 'error' | 'skipped'

export interface ToolCallView {
  toolCallId: string
  name: string
  arguments: Record<string, unknown>
  status: ToolCallStatus
  outcome?: string
  businessStatus?: string | null
  /** 工具返回的原始文本内容 */
  content?: string
  /** 工具返回的业务载荷（如 RAG 检索结果） */
  result?: unknown
}

export interface TokenUsage {
  input_tokens?: number
  output_tokens?: number
  total_tokens?: number
}

export interface AgentErrorInfo {
  code: string
  message: string
  phase?: string
  retryable?: boolean
}

export interface UserMessage {
  id: string
  role: 'user'
  content: string
  createdAt: string
}

/** 运行时间线片段：文本与工具调用按发生顺序交错 */
export type RunSegment =
  | { kind: 'text'; text: string; modelStep: number }
  | { kind: 'tool'; toolCall: ToolCallView }

export interface AssistantMessage {
  id: string
  role: 'assistant'
  status: 'streaming' | 'completed' | 'failed'
  /** 最终可见回答（message_completed 的 content） */
  content: string
  /** 过程时间线：按发生顺序排列的文本段与工具调用 */
  segments: RunSegment[]
  usage?: TokenUsage
  modelSteps?: number
  toolRounds?: number
  error?: AgentErrorInfo
  createdAt: string
}

export type ChatMessage = UserMessage | AssistantMessage

// ============ SSE 事件（对齐 AgentLoop 输出） ============

export type AgentEventType =
  | 'run_started'
  | 'model_step_started'
  | 'text_delta'
  | 'model_step_finished'
  | 'tool_started'
  | 'tool_finished'
  | 'message_completed'
  | 'error'
  | 'run_finished'

export interface AgentEventEnvelope<P = Record<string, unknown>> {
  type: AgentEventType
  request_id: string
  session_id: string
  event_seq: number
  payload: P
}

export interface RunStartedPayload {
  message_id: string
}

export interface ModelStepStartedPayload {
  model_step: number
  input_tokens: number
  tools_enabled: boolean
}

export interface TextDeltaPayload {
  model_step: number
  delta: string
}

export interface ModelStepFinishedPayload {
  model_step: number
  outcome: string
  consumed_result_ids: string[]
  usage: TokenUsage
}

export interface ToolStartedPayload {
  model_step: number
  tool_call_id: string
  name: string
  arguments: Record<string, unknown>
  outcome?: string
}

export interface ToolFinishedPayload {
  tool_call_id: string
  name: string
  outcome: string
  business_status: string | null
  content: string
  result: unknown
}

export interface MessageCompletedPayload {
  model_step: number
  content: string
  usage: TokenUsage
}

export interface ErrorPayload {
  code: string
  message: string
  phase: string
  retryable: boolean
}

export interface RunFinishedPayload {
  status: 'completed' | 'failed'
  model_steps: number
  tool_rounds: number
  usage: TokenUsage
}

// ============ 聊天运行请求 ============

export interface ChatRunRequest {
  message: string
}

// ============ 文档（RAG 知识库） ============

export type DocumentStatus = 'converting' | 'building' | 'ready' | 'failed'

export interface DocumentItem {
  id: string
  name: string
  size: number
  status: DocumentStatus
  /** 状态为 ready 时可用 */
  chunkCount?: number
  pageCount?: number
  error?: string
  createdAt: string
  readyAt?: string
}

// ============ 通用 ============

export interface ApiError {
  status: number
  message: string
}

export interface RagSearchResultItem {
  chunk_id: string
  document_id: string
  source: string
  page?: number | null
  text: string
  score: number
}

export interface RagSearchResultPayload {
  status: 'ok' | 'empty' | 'no_match' | 'error'
  query: string
  results: RagSearchResultItem[]
}
