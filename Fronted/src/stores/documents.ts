import { create } from 'zustand'
import { api } from '@/api'
import type { DocumentItem } from '@/api/types'

interface DocumentsState {
  docs: DocumentItem[]
  loading: boolean
  uploading: boolean
  uploadError: string | null
  load: (force?: boolean) => Promise<void>
  upload: (file: File) => Promise<void>
  remove: (documentId: string) => Promise<void>
}

/** 有构建中的文档时轮询刷新 */
let pollTimer: ReturnType<typeof setTimeout> | null = null

function schedulePoll(get: () => DocumentsState) {
  if (pollTimer) clearTimeout(pollTimer)
  const inFlight = get().docs.some(
    (d) => d.status === 'converting' || d.status === 'building',
  )
  if (inFlight) {
    pollTimer = setTimeout(async () => {
      await get().load(true)
      schedulePoll(get)
    }, 1200)
  }
}

export const useDocumentsStore = create<DocumentsState>((set, get) => ({
  docs: [],
  loading: false,
  uploading: false,
  uploadError: null,

  async load(force = false) {
    if (get().loading) return
    if (!force && get().docs.length > 0) {
      schedulePoll(get)
      return
    }
    set({ loading: true })
    try {
      const docs = await api.listDocuments()
      set({ docs })
      schedulePoll(get)
    } finally {
      set({ loading: false })
    }
  },

  async upload(file) {
    set({ uploading: true, uploadError: null })
    try {
      const doc = await api.uploadDocument(file)
      set({ docs: [doc, ...get().docs] })
      schedulePoll(get)
    } catch (error) {
      set({
        uploadError: error instanceof Error ? error.message : '上传失败',
      })
    } finally {
      set({ uploading: false })
    }
  },

  async remove(documentId) {
    await api.deleteDocument(documentId)
    set({ docs: get().docs.filter((d) => d.id !== documentId) })
  },
}))
