import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api'
import { ChatInput } from '@/components/chat/ChatInput'
import { MessageList } from '@/components/chat/MessageList'
import { IconBook, IconBrain, IconRefresh, IconSparkles } from '@/components/Icons'
import { useChatStore } from '@/stores/chat'
import { useDocumentsStore } from '@/stores/documents'
import { useSessionsStore } from '@/stores/sessions'

const SUGGESTIONS = [
  {
    title: '讲解文档内容',
    prompt: '帮我讲解一下文档里的核心概念',
    icon: IconBook,
  },
  {
    title: '记住我的偏好',
    prompt: '记住：我正在准备考试，偏好先看例子再学原理',
    icon: IconBrain,
  },
  {
    title: '了解我能做什么',
    prompt: '你能做什么？',
    icon: IconSparkles,
  },
]

function Welcome({ onPick }: { onPick: (prompt: string) => void }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center px-4 py-10">
      <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-brand-500 to-brand-700 text-white shadow-lg shadow-brand-200">
        <IconSparkles className="h-7 w-7" />
      </div>
      <h2 className="mt-5 text-2xl font-bold text-slate-900">
        你好，我是你的智能体老师
      </h2>
      <p className="mt-2 max-w-md text-center text-sm leading-6 text-slate-500">
        上传学习资料建立知识库，我会基于你的文档讲解知识、诊断作答；
        也会记住你的学习偏好，跨会话持续陪伴。
      </p>
      <div className="mt-8 grid w-full max-w-xl grid-cols-1 gap-3 sm:grid-cols-3">
        {SUGGESTIONS.map((item) => (
          <button
            key={item.title}
            type="button"
            onClick={() => onPick(item.prompt)}
            className="group flex flex-col items-start gap-2 rounded-xl border border-slate-200 bg-white p-4 text-left shadow-sm transition-all hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-md"
          >
            <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
              <item.icon className="h-4 w-4" />
            </span>
            <span className="text-sm font-medium text-slate-800">
              {item.title}
            </span>
            <span className="line-clamp-2 text-xs leading-5 text-slate-400">
              {item.prompt}
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}

export default function ChatPage() {
  const { sessionId } = useParams<{ sessionId: string }>()
  const navigate = useNavigate()

  const messages = useChatStore((s) =>
    sessionId ? s.messagesBySession[sessionId] : undefined,
  )
  const streamingSessionId = useChatStore((s) => s.streamingSessionId)
  const streamingMessageId = useChatStore((s) => s.streamingMessageId)
  const loadHistory = useChatStore((s) => s.loadHistory)
  const send = useChatStore((s) => s.send)
  const stop = useChatStore((s) => s.stop)

  const createSession = useSessionsStore((s) => s.create)
  const loadSessions = useSessionsStore((s) => s.load)
  const loadDocuments = useDocumentsStore((s) => s.load)
  const [compactFeedback, setCompactFeedback] = useState<{
    sessionId: string | null
    state: 'idle' | 'running' | 'saved' | 'skipped' | 'failed'
    message: string
  }>({ sessionId: null, state: 'idle', message: '' })

  useEffect(() => {
    void loadDocuments()
  }, [loadDocuments])

  useEffect(() => {
    if (sessionId) void loadHistory(sessionId)
  }, [sessionId, loadHistory])

  const handleSend = useCallback(
    async (content: string) => {
      let targetId = sessionId
      if (!targetId) {
        const session = await createSession()
        targetId = session.id
        navigate(`/chat/${session.id}`, { replace: true })
      }
      await send(targetId, content)
      void loadSessions(true)
    },
    [sessionId, createSession, send, navigate, loadSessions],
  )

  const list = sessionId ? messages ?? [] : []
  const streaming = streamingSessionId === sessionId && streamingSessionId !== null
  const compactState =
    compactFeedback.sessionId === sessionId ? compactFeedback.state : 'idle'
  const compactMessage =
    compactFeedback.sessionId === sessionId ? compactFeedback.message : ''

  const handleCompact = async () => {
    if (!sessionId) return
    setCompactFeedback({ sessionId, state: 'running', message: '' })
    try {
      const result = await api.compactSession(sessionId)
      if (result.status === 'saved') {
        setCompactFeedback({
          sessionId,
          state: 'saved',
          message: `上下文已从 ${result.beforeTokens} 压缩到 ${result.afterTokens} token`,
        })
      } else if (result.status === 'skipped') {
        setCompactFeedback({
          sessionId,
          state: 'skipped',
          message: result.reason ?? '当前没有可压缩的旧上下文',
        })
      } else {
        setCompactFeedback({
          sessionId,
          state: 'failed',
          message: result.reason ?? '上下文压缩未完成',
        })
      }
    } catch (error) {
      setCompactFeedback({
        sessionId,
        state: 'failed',
        message: error instanceof Error ? error.message : '上下文压缩失败',
      })
    }
  }

  return (
    <div className="flex h-full flex-col">
      {sessionId && list.length > 0 && (
        <div className="flex items-center justify-end gap-3 border-b border-slate-100 px-4 py-2">
          {compactMessage && (
            <span
              className={`text-xs ${
                compactState === 'failed' ? 'text-rose-500' : 'text-slate-500'
              }`}
            >
              {compactMessage}
            </span>
          )}
          <button
            type="button"
            onClick={() => void handleCompact()}
            disabled={streaming || compactState === 'running'}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 transition-colors hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 disabled:cursor-not-allowed disabled:opacity-50"
            title="将较早的会话内容整理为摘要"
          >
            <IconRefresh
              className={`h-3.5 w-3.5 ${compactState === 'running' ? 'animate-spin' : ''}`}
            />
            {compactState === 'running' ? '压缩中…' : '压缩上下文'}
          </button>
        </div>
      )}
      {list.length === 0 ? (
        <Welcome onPick={(prompt) => void handleSend(prompt)} />
      ) : (
        <MessageList
          key={sessionId ?? 'home'}
          messages={list}
          streamingMessageId={streaming ? streamingMessageId : null}
        />
      )}
      <ChatInput
        streaming={streaming}
        onSend={(content) => void handleSend(content)}
        onStop={stop}
      />
    </div>
  )
}
