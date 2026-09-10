import { create } from 'zustand'
import { api, clearToken, getToken, setToken } from '@/api'
import type { User } from '@/api/types'

export type AuthStatus = 'unknown' | 'authenticated' | 'unauthenticated'

interface AuthState {
  user: User | null
  status: AuthStatus
  init: () => Promise<void>
  login: (username: string, password: string) => Promise<void>
  register: (
    username: string,
    password: string,
    nickname?: string,
  ) => Promise<void>
  logout: () => Promise<void>
  updateProfile: (nickname: string) => Promise<void>
  changePassword: (oldPassword: string, newPassword: string) => Promise<void>
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  status: 'unknown',

  async init() {
    if (!getToken()) {
      set({ status: 'unauthenticated', user: null })
      return
    }
    try {
      const user = await api.me()
      set({ status: 'authenticated', user })
    } catch {
      clearToken()
      set({ status: 'unauthenticated', user: null })
    }
  },

  async login(username, password) {
    const result = await api.login({ username, password })
    setToken(result.token)
    set({ status: 'authenticated', user: result.user })
  },

  async register(username, password, nickname) {
    const result = await api.register({ username, password, nickname })
    setToken(result.token)
    set({ status: 'authenticated', user: result.user })
  },

  async logout() {
    try {
      await api.logout()
    } finally {
      clearToken()
      set({ status: 'unauthenticated', user: null })
    }
  },

  async updateProfile(nickname) {
    const user = await api.updateProfile({ nickname })
    set({ user })
  },

  async changePassword(oldPassword, newPassword) {
    await api.changePassword({ oldPassword, newPassword })
  },
}))
