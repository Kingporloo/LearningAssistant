import { create } from 'zustand'
import { api } from '@/api'
import type { SessionInfo } from '@/api/types'

interface SessionsState {
  sessions: SessionInfo[]
  loading: boolean
  loaded: boolean
  load: (force?: boolean) => Promise<void>
  create: (title?: string) => Promise<SessionInfo>
  setFirstMessageTitle: (sessionId: string, message: string) => void
  remove: (sessionId: string) => Promise<void>
}

function firstMessageTitle(message: string): string {
  const title = message.trim().replace(/\s+/g, ' ')
  return title.length <= 100 ? title : `${title.slice(0, 99)}…`
}

export const useSessionsStore = create<SessionsState>((set, get) => ({
  sessions: [],
  loading: false,
  loaded: false,

  async load(force = false) {
    if (get().loading) return
    if (get().loaded && !force) return
    set({ loading: true })
    try {
      const sessions = await api.listSessions()
      set({ sessions, loaded: true })
    } finally {
      set({ loading: false })
    }
  },

  async create(title) {
    const session = await api.createSession(title)
    set({ sessions: [session, ...get().sessions] })
    return session
  },

  setFirstMessageTitle(sessionId, message) {
    set({
      sessions: get().sessions.map((session) =>
        session.id === sessionId
          && session.title === '新会话'
          && session.messageCount === 0
          ? { ...session, title: firstMessageTitle(message) }
          : session,
      ),
    })
  },

  async remove(sessionId) {
    await api.deleteSession(sessionId)
    set({ sessions: get().sessions.filter((s) => s.id !== sessionId) })
  },
}))
