import { useEffect, useRef, useState } from 'react'
import type { DragEvent } from 'react'
import { useDocumentsStore } from '@/stores/documents'
import type { DocumentItem } from '@/api/types'
import {
  IconAlert,
  IconBook,
  IconCheck,
  IconFile,
  IconSpinner,
  IconTrash,
  IconUpload,
} from '@/components/Icons'

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

const STATUS_VIEWS: Record<
  DocumentItem['status'],
  { label: string; className: string; busy?: boolean }
> = {
  converting: { label: '转换中', className: 'bg-blue-50 text-blue-600', busy: true },
  building: { label: '构建索引', className: 'bg-amber-50 text-amber-600', busy: true },
  ready: { label: '已就绪', className: 'bg-emerald-50 text-emerald-600' },
  failed: { label: '失败', className: 'bg-rose-50 text-rose-600' },
}

function StatusBadge({ status }: { status: DocumentItem['status'] }) {
  const view = STATUS_VIEWS[status]
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${view.className}`}
    >
      {view.busy && <IconSpinner className="h-3 w-3" />}
      {status === 'ready' && <IconCheck className="h-3 w-3" />}
      {status === 'failed' && <IconAlert className="h-3 w-3" />}
      {view.label}
    </span>
  )
}

function DocumentCard({ doc }: { doc: DocumentItem }) {
  const remove = useDocumentsStore((s) => s.remove)
  const [confirming, setConfirming] = useState(false)

  const handleDelete = async () => {
    if (!confirming) {
      setConfirming(true)
      setTimeout(() => setConfirming(false), 2500)
      return
    }
    await remove(doc.id)
  }

  return (
    <div className="flex items-center gap-4 rounded-xl border border-slate-200 bg-white p-4 shadow-sm transition-shadow hover:shadow-md">
      <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-brand-50 text-brand-600">
        <IconFile className="h-5 w-5" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <p className="truncate text-sm font-medium text-slate-800" title={doc.name}>
            {doc.name}
          </p>
          <StatusBadge status={doc.status} />
        </div>
        <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-slate-400">
          <span>{formatSize(doc.size)}</span>
          {doc.pageCount !== undefined && <span>约 {doc.pageCount} 页</span>}
          {doc.chunkCount !== undefined && (
            <span>{doc.chunkCount} 个知识块</span>
          )}
          <span>{new Date(doc.createdAt).toLocaleString('zh-CN')}</span>
        </div>
        {doc.status === 'failed' && doc.error && (
          <p className="mt-1 text-xs text-rose-500">{doc.error}</p>
        )}
      </div>
      <button
        type="button"
        onClick={handleDelete}
        className={`shrink-0 rounded-lg p-2 transition-colors ${
          confirming
            ? 'bg-rose-100 text-rose-600'
            : 'text-slate-400 hover:bg-rose-50 hover:text-rose-500'
        }`}
        title={confirming ? '再次点击确认删除' : '删除文档'}
      >
        {confirming ? (
          <span className="text-xs font-medium">确认</span>
        ) : (
          <IconTrash className="h-4 w-4" />
        )}
      </button>
    </div>
  )
}

function UploadZone() {
  const upload = useDocumentsStore((s) => s.upload)
  const uploading = useDocumentsStore((s) => s.uploading)
  const uploadError = useDocumentsStore((s) => s.uploadError)
  const inputRef = useRef<HTMLInputElement>(null)
  const [dragOver, setDragOver] = useState(false)

  const handleFiles = (files: FileList | null) => {
    const file = files?.[0]
    if (file) void upload(file)
  }

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    setDragOver(false)
    handleFiles(event.dataTransfer.files)
  }

  return (
    <div>
      <div
        onDragOver={(e) => {
          e.preventDefault()
          setDragOver(true)
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
        onClick={() => inputRef.current?.click()}
        className={`flex cursor-pointer flex-col items-center justify-center rounded-2xl border-2 border-dashed px-6 py-10 transition-colors ${
          dragOver
            ? 'border-brand-400 bg-brand-50'
            : 'border-slate-300 bg-white hover:border-brand-300 hover:bg-brand-50/40'
        }`}
      >
        <div className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-100 text-brand-600">
          {uploading ? (
            <IconSpinner className="h-6 w-6" />
          ) : (
            <IconUpload className="h-6 w-6" />
          )}
        </div>
        <p className="mt-3 text-sm font-medium text-slate-700">
          {uploading ? '正在上传…' : '点击或拖拽文件到此处上传'}
        </p>
        <p className="mt-1 text-xs text-slate-400">
          支持 PDF / Markdown / TXT，单文件不超过 50MB；
          上传后自动解析、分块并建立向量索引
        </p>
        <input
          ref={inputRef}
          type="file"
          accept=".pdf,.md,.txt"
          className="hidden"
          onChange={(e) => {
            handleFiles(e.target.files)
            e.target.value = ''
          }}
        />
      </div>
      {uploadError && (
        <div className="mt-3 flex items-center gap-2 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-600">
          <IconAlert className="h-4 w-4 shrink-0" />
          {uploadError}
        </div>
      )}
    </div>
  )
}

export default function DocumentsPage() {
  const docs = useDocumentsStore((s) => s.docs)
  const loading = useDocumentsStore((s) => s.loading)
  const load = useDocumentsStore((s) => s.load)

  const readyCount = docs.filter((d) => d.status === 'ready').length
  const buildingCount = docs.filter(
    (d) => d.status === 'converting' || d.status === 'building',
  ).length

  useEffect(() => {
    void load()
  }, [load])

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-3xl space-y-6 px-4 py-8">
        <header>
          <h2 className="flex items-center gap-2 text-xl font-bold text-slate-900">
            <IconBook className="h-5 w-5 text-brand-600" />
            我的文档
          </h2>
          <p className="mt-1 text-sm text-slate-500">
            上传的学习资料会转换为 Markdown、分块并向量化，作为问答的知识库依据
            {docs.length > 0 &&
              ` · ${readyCount}/${docs.length} 已就绪${
                buildingCount > 0 ? ` · ${buildingCount} 个构建中` : ''
              }`}
          </p>
        </header>

        <UploadZone />

        {loading && docs.length === 0 ? (
          <div className="flex items-center justify-center py-10 text-slate-400">
            <IconSpinner className="h-5 w-5" />
          </div>
        ) : docs.length === 0 ? (
          <div className="rounded-xl border border-dashed border-slate-200 py-12 text-center">
            <p className="text-sm text-slate-400">
              还没有文档，上传第一个学习资料开始构建知识库
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {docs.map((doc) => (
              <DocumentCard key={doc.id} doc={doc} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
