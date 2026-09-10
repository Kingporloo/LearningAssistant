import { memo, useLayoutEffect, useRef } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { AssistantMessage, ChatMessage } from '@/api/types'
import { ToolCallCard } from './ToolCallCard'
import { IconAlert, IconSparkles, IconSpinner } from '@/components/Icons'

function UserMessageItem({ message }: { message: ChatMessage & { role: 'user' } }) {
  return (
    <div className="flex justify-end">
      <div className="max-w-[85%] rounded-2xl rounded-br-md bg-brand-600 px-4 py-2.5 text-[15px] leading-7 whitespace-pre-wrap text-white">
        {message.content}
      </div>
    </div>
  )
}

function ThinkingIndicator() {
  return (
    <div className="flex items-center gap-2 text-sm text-slate-400">
      <IconSpinner className="h-4 w-4" />
      正在思考…
    </div>
  )
}

function ErrorBanner({ message }: { message: NonNullable<AssistantMessage['error']> }) {
  return (
    <div className="mt-2 flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
      <IconAlert className="mt-0.5 h-4 w-4 shrink-0" />
      <div>
        <p className="font-medium">{message.message}</p>
        <p className="mt-0.5 text-xs text-rose-400">
          错误码 {message.code}
          {message.phase ? ` · 阶段 ${message.phase}` : ''}
        </p>
      </div>
    </div>
  )
}

function AssistantMessageItem({
  message,
  streaming,
}: {
  message: AssistantMessage
  streaming: boolean
}) {
  const hasContent = message.segments.some(
    (s) => s.kind === 'text' && s.text.length > 0,
  )
  const lastSegment = message.segments[message.segments.length - 1]
  const showCursor =
    streaming && lastSegment !== undefined && lastSegment.kind === 'text'

  return (
    <div className="flex gap-3">
      <div className="mt-1 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-brand-500 to-brand-700 text-white shadow-sm">
        <IconSparkles className="h-4 w-4" />
      </div>
      <div className="min-w-0 flex-1">
        {!hasContent && streaming && <ThinkingIndicator />}
        {message.segments.map((segment, index) => {
          if (segment.kind === 'tool') {
            return <ToolCallCard key={segment.toolCall.toolCallId} toolCall={segment.toolCall} />
          }
          if (segment.text.length === 0) return null
          return (
            <div
              key={index}
              className={`markdown-body ${showCursor && index === message.segments.length - 1 ? 'stream-cursor' : ''}`}
            >
              <Markdown remarkPlugins={[remarkGfm]}>{segment.text}</Markdown>
            </div>
          )
        })}
        {message.error && <ErrorBanner message={message.error} />}
        {message.status === 'completed' && (
          <div className="mt-2 flex items-center gap-3 text-xs text-slate-400">
            {message.usage?.total_tokens !== undefined && (
              <span className="tabular-nums">
                {message.usage.total_tokens.toLocaleString()} tokens
              </span>
            )}
            {message.modelSteps !== undefined && message.modelSteps > 1 && (
              <span>{message.modelSteps} 步推理</span>
            )}
            {message.toolRounds !== undefined && message.toolRounds > 0 && (
              <span>{message.toolRounds} 轮工具调用</span>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

export const MessageList = memo(function MessageList({
  messages,
  streamingMessageId,
}: {
  messages: ChatMessage[]
  streamingMessageId: string | null
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const stickToBottomRef = useRef(true)

  const handleScroll = () => {
    const el = containerRef.current
    if (!el) return
    stickToBottomRef.current =
      el.scrollHeight - el.scrollTop - el.clientHeight < 120
  }

  // 发送新消息（用户消息到达）时强制贴底，不受之前滚动位置影响；
  // 流式期间仅在用户本就贴底时跟随，向上翻阅不被打断
  const prevCountRef = useRef(messages.length)
  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el) return
    if (messages.length > prevCountRef.current) {
      // send() 会同时追加用户消息与助手占位，检查新增切片而非最后一条
      const added = messages.slice(prevCountRef.current)
      if (added.some((m) => m.role === 'user')) {
        stickToBottomRef.current = true
      }
    }
    prevCountRef.current = messages.length
    if (stickToBottomRef.current) {
      el.scrollTop = el.scrollHeight
    }
  }, [messages])

  return (
    <div
      ref={containerRef}
      onScroll={handleScroll}
      className="flex-1 overflow-y-auto"
    >
      <div className="mx-auto flex max-w-3xl flex-col gap-6 px-4 py-6">
        {messages.map((message) =>
          message.role === 'user' ? (
            <UserMessageItem key={message.id} message={message} />
          ) : (
            <AssistantMessageItem
              key={message.id}
              message={message}
              streaming={message.id === streamingMessageId}
            />
          ),
        )}
      </div>
    </div>
  )
})
