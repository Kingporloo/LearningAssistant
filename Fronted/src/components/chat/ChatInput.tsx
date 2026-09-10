import { useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { IconSend, IconStop } from '@/components/Icons'

interface ChatInputProps {
  streaming: boolean
  disabled?: boolean
  onSend: (content: string) => void
  onStop: () => void
}

export function ChatInput({ streaming, disabled, onSend, onStop }: ChatInputProps) {
  const [value, setValue] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const resize = () => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`
  }

  const submit = () => {
    const content = value.trim()
    if (!content || streaming || disabled) return
    onSend(content)
    setValue('')
    requestAnimationFrame(() => {
      if (textareaRef.current) textareaRef.current.style.height = 'auto'
    })
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      submit()
    }
  }

  return (
    <div className="border-t border-slate-200 bg-white/80 backdrop-blur">
      <div className="mx-auto max-w-3xl px-4 py-4">
        <div className="flex items-end gap-2 rounded-2xl border border-slate-200 bg-white px-3 py-2 shadow-sm focus-within:border-brand-400 focus-within:ring-2 focus-within:ring-brand-100">
          <textarea
            ref={textareaRef}
            value={value}
            rows={1}
            placeholder="输入你的问题…（Enter 发送，Shift+Enter 换行）"
            className="max-h-[200px] flex-1 resize-none bg-transparent py-1.5 text-[15px] leading-6 outline-none placeholder:text-slate-400"
            onChange={(e) => {
              setValue(e.target.value)
              resize()
            }}
            onKeyDown={handleKeyDown}
            disabled={disabled}
          />
          {streaming ? (
            <button
              type="button"
              onClick={onStop}
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-slate-800 text-white transition-colors hover:bg-slate-700"
              title="停止生成"
            >
              <IconStop className="h-4 w-4" />
            </button>
          ) : (
            <button
              type="button"
              onClick={submit}
              disabled={!value.trim() || disabled}
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-brand-600 text-white transition-colors hover:bg-brand-700 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400"
              title="发送"
            >
              <IconSend className="h-4 w-4" />
            </button>
          )}
        </div>
        <p className="mt-2 text-center text-xs text-slate-400">
          回答基于你的知识库与长期记忆生成，请注意甄别关键信息
        </p>
      </div>
    </div>
  )
}
