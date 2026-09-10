import { useState } from 'react'
import type { ToolCallView } from '@/api/types'
import type { RagSearchResultPayload, RagSearchResultItem } from '@/api/types'
import {
  IconAlert,
  IconBrain,
  IconCheck,
  IconChevronDown,
  IconChevronRight,
  IconSearch,
  IconSpinner,
  IconX,
} from '@/components/Icons'

const TOOL_LABELS: Record<string, string> = {
  'rag__rag_search': '知识库检索',
  'memory__memory_query': '记忆查询',
  'memory__memory_store': '保存记忆',
  'memory__memory_forget': '删除记忆',
}

const STATUS_STYLES: Record<
  ToolCallView['status'],
  { label: string; className: string }
> = {
  running: { label: '执行中', className: 'bg-blue-50 text-blue-600' },
  completed: { label: '完成', className: 'bg-emerald-50 text-emerald-600' },
  error: { label: '失败', className: 'bg-rose-50 text-rose-600' },
  skipped: { label: '跳过', className: 'bg-slate-100 text-slate-500' },
}

function ToolIcon({ name }: { name: string }) {
  if (name.startsWith('rag__')) {
    return <IconSearch className="h-3.5 w-3.5" />
  }
  return <IconBrain className="h-3.5 w-3.5" />
}

function StatusChip({ status }: { status: ToolCallView['status'] }) {
  const style = STATUS_STYLES[status]
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${style.className}`}
    >
      {status === 'running' && <IconSpinner className="h-3 w-3" />}
      {status === 'completed' && <IconCheck className="h-3 w-3" />}
      {status === 'error' && <IconX className="h-3 w-3" />}
      {style.label}
    </span>
  )
}

function RagResults({ result }: { result: unknown }) {
  const payload = result as Partial<RagSearchResultPayload>
  if (!payload || typeof payload !== 'object' || !Array.isArray(payload.results)) {
    return <JsonBlock value={result} />
  }
  if (payload.results.length === 0) {
    return (
      <p className="text-xs text-slate-500">
        未检索到相关内容（status: {payload.status ?? 'unknown'}）
      </p>
    )
  }
  return (
    <div className="space-y-2">
      {payload.results.map((item: RagSearchResultItem) => (
        <div
          key={item.chunk_id}
          className="rounded-lg border border-slate-200 bg-slate-50 p-3"
        >
          <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-500">
            <span className="font-medium text-slate-700">{item.source}</span>
            {item.page != null && <span>第 {item.page} 页</span>}
            <span className="ml-auto tabular-nums text-brand-600">
              相关度 {item.score.toFixed(2)}
            </span>
          </div>
          <p className="line-clamp-3 text-xs leading-5 text-slate-600">
            {item.text}
          </p>
        </div>
      ))}
    </div>
  )
}

function JsonBlock({ value }: { value: unknown }) {
  if (value === undefined || value === null) {
    return <p className="text-xs text-slate-400">（无返回数据）</p>
  }
  let text: string
  try {
    text = typeof value === 'string' ? value : JSON.stringify(value, null, 2)
  } catch {
    text = String(value)
  }
  return (
    <pre className="max-h-60 overflow-auto rounded-lg bg-slate-900 p-3 text-xs leading-5 text-slate-100">
      {text}
    </pre>
  )
}

export function ToolCallCard({ toolCall }: { toolCall: ToolCallView }) {
  const [open, setOpen] = useState(false)
  const label = TOOL_LABELS[toolCall.name] ?? toolCall.name
  const isRag = toolCall.name === 'rag__rag_search'

  return (
    <div className="my-2 overflow-hidden rounded-xl border border-slate-200 bg-white">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-2 px-3 py-2 text-left transition-colors hover:bg-slate-50"
      >
        <span className="flex h-6 w-6 items-center justify-center rounded-md bg-brand-50 text-brand-600">
          <ToolIcon name={toolCall.name} />
        </span>
        <span className="text-sm font-medium text-slate-700">{label}</span>
        <span className="text-xs text-slate-400">{toolCall.name}</span>
        {toolCall.businessStatus && (
          <span className="text-xs text-slate-400">· {toolCall.businessStatus}</span>
        )}
        <span className="ml-auto flex items-center gap-2">
          <StatusChip status={toolCall.status} />
          {open ? (
            <IconChevronDown className="h-4 w-4 text-slate-400" />
          ) : (
            <IconChevronRight className="h-4 w-4 text-slate-400" />
          )}
        </span>
      </button>

      {open && (
        <div className="space-y-3 border-t border-slate-100 px-3 py-3">
          <section>
            <h4 className="mb-1.5 text-xs font-semibold tracking-wide text-slate-400 uppercase">
              调用参数
            </h4>
            <JsonBlock value={toolCall.arguments} />
          </section>
          <section>
            <h4 className="mb-1.5 text-xs font-semibold tracking-wide text-slate-400 uppercase">
              返回结果
            </h4>
            {isRag ? (
              <RagResults result={toolCall.result ?? toolCall.content} />
            ) : (
              <JsonBlock value={toolCall.result ?? toolCall.content} />
            )}
          </section>
        </div>
      )}

      {toolCall.status === 'error' && !open && (
        <div className="flex items-center gap-2 border-t border-rose-100 bg-rose-50 px-3 py-1.5 text-xs text-rose-600">
          <IconAlert className="h-3.5 w-3.5" />
          {toolCall.content?.slice(0, 120) ?? '工具调用失败'}
        </div>
      )}
    </div>
  )
}
