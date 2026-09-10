/**
 * Mock 后端的 localStorage 持久化存储。
 *
 * 结构：全局用户表 + token 表；每个用户独立的会话/消息/文档数据。
 * 真实网关接入后本目录整体删除即可。
 */

import type {
  ChatMessage,
  DocumentItem,
  SessionInfo,
  User,
} from '../types'

const PREFIX = 'pdflearning.mock.v1'

interface StoredUser extends User {
  password: string
}

interface UserData {
  sessions: SessionInfo[]
  messages: Record<string, ChatMessage[]>
  documents: DocumentItem[]
  memories: MockMemory[]
}

export interface MockMemory {
  id: string
  content: string
  memory_type: 'working' | 'semantic' | 'episodic'
  importance: number | null
  createdAt: string
}

function read<T>(key: string, fallback: T): T {
  const raw = localStorage.getItem(key)
  if (raw === null) return fallback
  try {
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

function write<T>(key: string, value: T): void {
  localStorage.setItem(key, JSON.stringify(value))
}

function dataKey(userId: string): string {
  return `${PREFIX}.data.${userId}`
}

function ensureData(userId: string): UserData {
  const existing = read<UserData | null>(dataKey(userId), null)
  if (
    existing &&
    existing.sessions &&
    existing.messages &&
    existing.documents &&
    existing.memories
  ) {
    return existing
  }
  const fresh: UserData = {
    sessions: [],
    messages: {},
    documents: [],
    memories: [],
  }
  write(dataKey(userId), fresh)
  return fresh
}

function saveData(userId: string, data: UserData): void {
  write(dataKey(userId), data)
}

export function id(prefix: string): string {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
}

export class MockDb {
  // ---------- 用户 ----------

  listUsers(): StoredUser[] {
    return read<StoredUser[]>(`${PREFIX}.users`, [])
  }

  saveUsers(users: StoredUser[]): void {
    write(`${PREFIX}.users`, users)
  }

  findUser(username: string): StoredUser | undefined {
    return this.listUsers().find((u) => u.username === username)
  }

  createUser(username: string, password: string, nickname: string): StoredUser {
    const users = this.listUsers()
    const user: StoredUser = {
      id: id('user'),
      username,
      password,
      nickname,
      createdAt: new Date().toISOString(),
    }
    users.push(user)
    this.saveUsers(users)
    return user
  }

  updateUserProfile(userId: string, patch: Partial<Pick<StoredUser, 'nickname' | 'password'>>): StoredUser | undefined {
    const users = this.listUsers()
    const index = users.findIndex((u) => u.id === userId)
    if (index === -1) return undefined
    users[index] = { ...users[index], ...patch }
    this.saveUsers(users)
    return users[index]
  }

  // ---------- token ----------

  issueToken(userId: string): string {
    const tokens = read<Record<string, string>>(`${PREFIX}.tokens`, {})
    const token = `mock.${userId}.${Math.random().toString(36).slice(2)}`
    tokens[token] = userId
    write(`${PREFIX}.tokens`, tokens)
    return token
  }

  resolveToken(token: string): string | null {
    const tokens = read<Record<string, string>>(`${PREFIX}.tokens`, {})
    return tokens[token] ?? null
  }

  revokeToken(token: string): void {
    const tokens = read<Record<string, string>>(`${PREFIX}.tokens`, {})
    delete tokens[token]
    write(`${PREFIX}.tokens`, tokens)
  }

  // ---------- 会话与消息 ----------

  listSessions(userId: string): SessionInfo[] {
    return ensureData(userId).sessions
  }

  createSession(userId: string, title: string): SessionInfo {
    const data = ensureData(userId)
    const now = new Date().toISOString()
    const session: SessionInfo = {
      id: id('session'),
      title,
      createdAt: now,
      updatedAt: now,
      messageCount: 0,
    }
    data.sessions.unshift(session)
    saveData(userId, data)
    return session
  }

  deleteSession(userId: string, sessionId: string): void {
    const data = ensureData(userId)
    data.sessions = data.sessions.filter((s) => s.id !== sessionId)
    delete data.messages[sessionId]
    saveData(userId, data)
  }

  touchSession(userId: string, sessionId: string, patch: { title?: string; bumpMessageCount?: boolean }): SessionInfo | undefined {
    const data = ensureData(userId)
    const index = data.sessions.findIndex((s) => s.id === sessionId)
    if (index === -1) return undefined
    const session = data.sessions[index]
    session.updatedAt = new Date().toISOString()
    if (patch.title) session.title = patch.title
    if (patch.bumpMessageCount) session.messageCount += 1
    data.sessions[index] = session
    saveData(userId, data)
    return session
  }

  listMessages(userId: string, sessionId: string): ChatMessage[] {
    return ensureData(userId).messages[sessionId] ?? []
  }

  appendMessage(userId: string, sessionId: string, message: ChatMessage): void {
    const data = ensureData(userId)
    if (!data.messages[sessionId]) data.messages[sessionId] = []
    data.messages[sessionId].push(message)
    saveData(userId, data)
  }

  /** 用流式结束后的最终消息替换占位消息（按 id 匹配） */
  finalizeMessage(userId: string, sessionId: string, message: ChatMessage): void {
    const data = ensureData(userId)
    const list = data.messages[sessionId]
    if (!list) return
    const index = list.findIndex((m) => m.id === message.id)
    if (index === -1) {
      list.push(message)
    } else {
      list[index] = message
    }
    saveData(userId, data)
  }

  // ---------- 文档 ----------

  listDocuments(userId: string): DocumentItem[] {
    return ensureData(userId).documents
  }

  addDocument(userId: string, doc: DocumentItem): void {
    const data = ensureData(userId)
    data.documents.unshift(doc)
    saveData(userId, data)
  }

  updateDocument(userId: string, docId: string, patch: Partial<DocumentItem>): DocumentItem | undefined {
    const data = ensureData(userId)
    const index = data.documents.findIndex((d) => d.id === docId)
    if (index === -1) return undefined
    data.documents[index] = { ...data.documents[index], ...patch }
    saveData(userId, data)
    return data.documents[index]
  }

  deleteDocument(userId: string, docId: string): void {
    const data = ensureData(userId)
    data.documents = data.documents.filter((d) => d.id !== docId)
    saveData(userId, data)
  }

  listReadyDocuments(userId: string): DocumentItem[] {
    return this.listDocuments(userId).filter((d) => d.status === 'ready')
  }

  // ---------- 记忆（模拟长期记忆，供 memory 工具演示） ----------

  listMemories(userId: string): MockMemory[] {
    return ensureData(userId).memories
  }

  addMemory(userId: string, memory: MockMemory): void {
    const data = ensureData(userId)
    data.memories.unshift(memory)
    saveData(userId, data)
  }
}

/** 首次访问时预置演示账号，方便直接体验 */
export function seedDemoData(db: MockDb): void {
  const seeded = localStorage.getItem(`${PREFIX}.seeded`)
  if (seeded) return
  if (db.findUser('demo') === undefined) {
    db.createUser('demo', 'demo123456', '演示用户')
  }
  localStorage.setItem(`${PREFIX}.seeded`, '1')
}
