import { useState } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useDocumentsStore } from '@/stores/documents'
import { useSessionsStore } from '@/stores/sessions'
import {
  IconBook,
  IconChat,
  IconLogout,
  IconPlus,
  IconSettings,
  IconSparkles,
  IconTrash,
  IconUser,
  IconX,
} from '@/components/Icons'

function SessionRow({ sessionId }: { sessionId: string }) {
  const navigate = useNavigate()
  const session = useSessionsStore((s) =>
    s.sessions.find((item) => item.id === sessionId),
  )
  const removeSession = useSessionsStore((s) => s.remove)
  const clearSessionMessages = useChatStore((s) => s.clearSession)
  const [confirming, setConfirming] = useState(false)
  if (!session) return null

  const handleDelete = async (event: React.MouseEvent) => {
    event.preventDefault()
    event.stopPropagation()
    if (!confirming) {
      setConfirming(true)
      setTimeout(() => setConfirming(false), 2500)
      return
    }
    await removeSession(session.id)
    clearSessionMessages(session.id)
    navigate('/', { replace: true })
  }

  return (
    <NavLink
      to={`/chat/${session.id}`}
      className={({ isActive }) =>
        `group flex items-center gap-2 rounded-lg px-3 py-2 text-sm transition-colors ${
          isActive
            ? 'bg-brand-50 text-brand-700'
            : 'text-slate-600 hover:bg-slate-100'
        }`
      }
    >
      <IconChat className="h-4 w-4 shrink-0 opacity-60" />
      <span className="min-w-0 flex-1 truncate">{session.title}</span>
      <button
        type="button"
        onClick={handleDelete}
        className={`shrink-0 rounded p-1 transition-colors ${
          confirming
            ? 'bg-rose-100 text-rose-600'
            : 'text-slate-400 opacity-0 hover:bg-slate-200 hover:text-rose-500 group-hover:opacity-100'
        }`}
        title={confirming ? '再次点击确认删除' : '删除会话'}
      >
        {confirming ? (
          <IconX className="h-3.5 w-3.5" />
        ) : (
          <IconTrash className="h-3.5 w-3.5" />
        )}
      </button>
    </NavLink>
  )
}

export function Sidebar({ onClose }: { onClose?: () => void }) {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const sessions = useSessionsStore((s) => s.sessions)
  const createSession = useSessionsStore((s) => s.create)

  const handleNewSession = async () => {
    const session = await createSession()
    navigate(`/chat/${session.id}`)
    onClose?.()
  }

  const handleLogout = async () => {
    // 停止进行中的流，重置各 store，避免账号间数据残留
    useChatStore.getState().stop()
    useChatStore.setState({
      messagesBySession: {},
      loadedSessions: new Set(),
      streamingSessionId: null,
      streamingMessageId: null,
      currentHandle: null,
    })
    useSessionsStore.setState({ sessions: [], loaded: false })
    useDocumentsStore.setState({ docs: [], uploadError: null })
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <aside className="flex h-full w-64 flex-col border-r border-slate-200 bg-white">
      {/* 品牌区 */}
      <div className="flex items-center gap-2.5 px-4 py-4">
        <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-brand-500 to-brand-700 text-white shadow-sm">
          <IconSparkles className="h-5 w-5" />
        </div>
        <div className="min-w-0 flex-1">
          <h1 className="text-sm font-bold text-slate-900">智能体老师</h1>
          <p className="text-xs text-slate-400">个人学习知识库</p>
        </div>
        {onClose && (
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 md:hidden"
          >
            <IconX className="h-4 w-4" />
          </button>
        )}
      </div>

      {/* 新建会话 */}
      <div className="px-3">
        <button
          type="button"
          onClick={handleNewSession}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-brand-600 px-3 py-2.5 text-sm font-medium text-white shadow-sm transition-colors hover:bg-brand-700"
        >
          <IconPlus className="h-4 w-4" />
          新建会话
        </button>
      </div>

      {/* 导航 */}
      <nav className="mt-4 space-y-1 px-3">
        <NavLink
          to="/"
          end
          className={({ isActive }) =>
            `flex items-center gap-2 rounded-lg px-3 py-2 text-sm transition-colors ${
              isActive
                ? 'bg-slate-100 font-medium text-slate-900'
                : 'text-slate-600 hover:bg-slate-100'
            }`
          }
        >
          <IconChat className="h-4 w-4 opacity-60" />
          对话
        </NavLink>
        <NavLink
          to="/documents"
          className={({ isActive }) =>
            `flex items-center gap-2 rounded-lg px-3 py-2 text-sm transition-colors ${
              isActive
                ? 'bg-slate-100 font-medium text-slate-900'
                : 'text-slate-600 hover:bg-slate-100'
            }`
          }
        >
          <IconBook className="h-4 w-4 opacity-60" />
          文档
          <span className="ml-auto text-xs text-slate-400">RAG</span>
        </NavLink>
      </nav>

      {/* 会话列表 */}
      <div className="mt-4 min-h-0 flex-1 overflow-y-auto px-3 pb-2">
        <p className="px-3 pb-1.5 text-xs font-medium tracking-wider text-slate-400 uppercase">
          历史会话
        </p>
        <div className="space-y-0.5">
          {sessions.length === 0 ? (
            <p className="px-3 py-4 text-xs text-slate-400">暂无会话</p>
          ) : (
            sessions.map((session) => (
              <SessionRow key={session.id} sessionId={session.id} />
            ))
          )}
        </div>
      </div>

      {/* 用户区 */}
      <div className="border-t border-slate-100 p-3">
        <div className="flex items-center gap-2.5 rounded-lg px-2 py-1.5">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-200 text-slate-500">
            <IconUser className="h-4 w-4" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-slate-800">
              {user?.nickname ?? user?.username ?? '未登录'}
            </p>
            <p className="truncate text-xs text-slate-400">@{user?.username}</p>
          </div>
          <NavLink
            to="/settings"
            title="设置"
            className={({ isActive }) =>
              `rounded-lg p-1.5 transition-colors ${
                isActive
                  ? 'bg-slate-100 text-slate-700'
                  : 'text-slate-400 hover:bg-slate-100 hover:text-slate-600'
              }`
            }
          >
            <IconSettings className="h-4 w-4" />
          </NavLink>
          <button
            type="button"
            onClick={handleLogout}
            title="退出登录"
            className="rounded-lg p-1.5 text-slate-400 transition-colors hover:bg-rose-50 hover:text-rose-500"
          >
            <IconLogout className="h-4 w-4" />
          </button>
        </div>
      </div>
    </aside>
  )
}
