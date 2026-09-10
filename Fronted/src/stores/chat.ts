/**
 * 聊天状态：把 AgentLoop 的 SSE 事件流归约为 UI 时间线。
 *
 * 一条助手消息 = segments（文本段与工具调用交错）+ 最终回答 + 运行统计。
 */

import { create } from 'zustand'
import { api } from '@/api'
import type { ChatRunHandle } from '@/api/client'
import type {
  AgentEventEnvelope,
  AssistantMessage,
  ChatMessage,
  ToolCallView,
} from '@/api/types'

interface ChatState {
  messagesBySession: Record<string, ChatMessage[]>
  loadedSessions: Set<string>
  loadingHistory: boolean
  streamingSessionId: string | null
  streamingMessageId: string | null
  currentHandle: ChatRunHandle | null
  loadHistory: (sessionId: string, force?: boolean) => Promise<void>
  send: (sessionId: string, content: string) => Promise<void>
  stop: () => void
  clearSession: (sessionId: string) => void
}

function updateStreamingMessage(
  messages: ChatMessage[],
  messageId: string,
  update: (message: AssistantMessage) => AssistantMessage,
): ChatMessage[] {
  return messages.map((m) =>
    m.id === messageId && m.role === 'assistant' ? update(m) : m,
  )
}

function applyEvent(
  message: AssistantMessage,
  event: AgentEventEnvelope,
): AssistantMessage {
  const payload = event.payload as Record<string, unknown>
  switch (event.type) {
    case 'text_delta': {
      const delta = String(payload.delta ?? '')
      const step = Number(payload.model_step ?? 1)
      const segments = [...message.segments]
      const last = segments[segments.length - 1]
      if (last && last.kind === 'text' && last.modelStep === step) {
        segments[segments.length - 1] = { ...last, text: last.text + delta }
      } else {
        segments.push({ kind: 'text', text: delta, modelStep: step })
      }
      return { ...message, segments }
    }
    case 'tool_started': {
      const call: ToolCallView = {
        toolCallId: String(payload.tool_call_id ?? ''),
        name: String(payload.name ?? ''),
        arguments: (payload.arguments as Record<string, unknown>) ?? {},
        status: payload.outcome === 'skipped' ? 'skipped' : 'running',
      }
      return {
        ...message,
        segments: [...message.segments, { kind: 'tool', toolCall: call }],
      }
    }
    case 'tool_finished': {
      const toolCallId = String(payload.tool_call_id ?? '')
      const segments = message.segments.map((segment) => {
        if (segment.kind !== 'tool' || segment.toolCall.toolCallId !== toolCallId) {
          return segment
        }
        return {
          ...segment,
          toolCall: {
            ...segment.toolCall,
            status: payload.outcome === 'completed' ? 'completed' : 'error',
            outcome: String(payload.outcome ?? ''),
            businessStatus: (payload.business_status as string | null) ?? null,
            content: String(payload.content ?? ''),
            result: payload.result,
          } satisfies ToolCallView,
        }
      })
      return { ...message, segments }
    }
    case 'model_step_finished': {
      const usage = payload.usage as AssistantMessage['usage']
      return { ...message, usage: usage ?? message.usage }
    }
    case 'message_completed': {
      return {
        ...message,
        content: String(payload.content ?? ''),
        usage: (payload.usage as AssistantMessage['usage']) ?? message.usage,
      }
    }
    case 'error': {
      return {
        ...message,
        error: {
          code: String(payload.code ?? 'unknown'),
          message: String(payload.message ?? '运行失败'),
          phase: payload.phase === undefined ? undefined : String(payload.phase),
          retryable: Boolean(payload.retryable),
        },
      }
    }
    case 'run_finished': {
      const status = payload.status === 'completed' ? 'completed' : 'failed'
      return {
        ...message,
        status,
        modelSteps: Number(payload.model_steps ?? 0),
        toolRounds: Number(payload.tool_rounds ?? 0),
        usage: (payload.usage as AssistantMessage['usage']) ?? message.usage,
      }
    }
    default:
      return message
  }
}

export const useChatStore = create<ChatState>((set, get) => ({
  messagesBySession: {},
  loadedSessions: new Set<string>(),
  loadingHistory: false,
  streamingSessionId: null,
  streamingMessageId: null,
  currentHandle: null,

  async loadHistory(sessionId, force = false) {
    if (!force && get().loadedSessions.has(sessionId)) return
    if (get().loadingHistory) return
    set({ loadingHistory: true })
    try {
      const messages = await api.listMessages(sessionId)
      const loaded = new Set(get().loadedSessions)
      loaded.add(sessionId)
      set({
        messagesBySession: { ...get().messagesBySession, [sessionId]: messages },
        loadedSessions: loaded,
      })
    } finally {
      set({ loadingHistory: false })
    }
  },

  async send(sessionId, content) {
    if (get().streamingSessionId) return
    const trimmed = content.trim()
    if (!trimmed) return

    const now = new Date().toISOString()
    const userMessage: ChatMessage = {
      id: `local_user_${now}_${Math.random().toString(36).slice(2, 6)}`,
      role: 'user',
      content: trimmed,
      createdAt: now,
    }
    const assistantId = `local_assistant_${now}_${Math.random().toString(36).slice(2, 6)}`
    const assistantMessage: AssistantMessage = {
      id: assistantId,
      role: 'assistant',
      status: 'streaming',
      content: '',
      segments: [],
      createdAt: now,
    }

    const existing = get().messagesBySession[sessionId] ?? []
    const loaded = new Set(get().loadedSessions)
    loaded.add(sessionId)
    set({
      messagesBySession: {
        ...get().messagesBySession,
        [sessionId]: [...existing, userMessage, assistantMessage],
      },
      loadedSessions: loaded,
      streamingSessionId: sessionId,
      streamingMessageId: assistantId,
    })

    const handle = api.runChat({
      sessionId,
      message: trimmed,
      signal: new AbortController().signal,
      onEvent: (event) => {
        const state = get()
        const messages = state.messagesBySession[sessionId]
        if (!messages) return
        set({
          messagesBySession: {
            ...state.messagesBySession,
            [sessionId]: updateStreamingMessage(messages, assistantId, (m) =>
              applyEvent(m, event),
            ),
          },
        })
      },
    })
    set({ currentHandle: handle })

    try {
      await handle.done
    } catch {
      // 网关/网络异常：标记失败
      const state = get()
      const messages = state.messagesBySession[sessionId]
      if (messages) {
        set({
          messagesBySession: {
            ...state.messagesBySession,
            [sessionId]: updateStreamingMessage(
              messages,
              assistantId,
              (m) =>
                m.status === 'streaming'
                  ? {
                      ...m,
                      status: 'failed',
                      error: {
                        code: 'network_error',
                        message: '连接中断，请重试',
                        retryable: true,
                      },
                    }
                  : m,
            ),
          },
        })
      }
    } finally {
      // 中止时可能没有收到 run_finished，兜底置为失败
      const state = get()
      const messages = state.messagesBySession[sessionId]
      if (messages) {
        set({
          messagesBySession: {
            ...state.messagesBySession,
            [sessionId]: updateStreamingMessage(
              messages,
              assistantId,
              (m) =>
                m.status === 'streaming'
                  ? {
                      ...m,
                      status: 'failed',
                      error: m.error ?? {
                        code: 'aborted',
                        message: '已停止生成',
                        retryable: true,
                      },
                    }
                  : m,
            ),
          },
        })
      }
      set({
        streamingSessionId: null,
        streamingMessageId: null,
        currentHandle: null,
      })
    }
  },

  stop() {
    get().currentHandle?.abort()
  },

  clearSession(sessionId) {
    const messages = { ...get().messagesBySession }
    delete messages[sessionId]
    const loaded = new Set(get().loadedSessions)
    loaded.delete(sessionId)
    set({ messagesBySession: messages, loadedSessions: loaded })
  },
}))
