import { useState } from 'react'
import { useAuthStore } from '@/stores/auth'
import { IconCheck, IconSettings, IconSparkles, IconUser } from '@/components/Icons'

function SectionCard({
  title,
  description,
  children,
}: {
  title: string
  description?: string
  children: React.ReactNode
}) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
      <h3 className="text-base font-semibold text-slate-900">{title}</h3>
      {description && (
        <p className="mt-1 text-sm text-slate-500">{description}</p>
      )}
      <div className="mt-4">{children}</div>
    </section>
  )
}

function ProfileSection() {
  const user = useAuthStore((s) => s.user)
  const updateProfile = useAuthStore((s) => s.updateProfile)
  const [nickname, setNickname] = useState(user?.nickname ?? '')
  const [status, setStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const [error, setError] = useState('')

  const dirty = nickname !== (user?.nickname ?? '') && nickname.trim().length > 0

  const handleSave = async () => {
    setStatus('saving')
    setError('')
    try {
      await updateProfile(nickname.trim())
      setStatus('saved')
      setTimeout(() => setStatus('idle'), 2000)
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败')
      setStatus('error')
    }
  }

  return (
    <SectionCard title="个人资料" description="你的公开昵称，会话界面中展示">
      <div className="flex items-center gap-4">
        <div className="flex h-14 w-14 items-center justify-center rounded-full bg-slate-100 text-slate-400">
          <IconUser className="h-6 w-6" />
        </div>
        <div className="flex-1 space-y-1">
          <p className="text-sm font-medium text-slate-800">@{user?.username}</p>
          <p className="text-xs text-slate-400">
            注册于 {user ? new Date(user.createdAt).toLocaleDateString('zh-CN') : '-'}
          </p>
        </div>
      </div>
      <div className="mt-4 flex items-end gap-3">
        <label className="flex-1">
          <span className="mb-1 block text-xs font-medium text-slate-500">昵称</span>
          <input
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            maxLength={24}
            placeholder="输入新昵称"
            className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-brand-400 focus:ring-2 focus:ring-brand-100"
          />
        </label>
        <button
          type="button"
          onClick={handleSave}
          disabled={!dirty || status === 'saving'}
          className="flex items-center gap-1.5 rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-brand-700 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400"
        >
          {status === 'saved' ? (
            <>
              <IconCheck className="h-4 w-4" />
              已保存
            </>
          ) : status === 'saving' ? (
            '保存中…'
          ) : (
            '保存'
          )}
        </button>
      </div>
      {error && <p className="mt-2 text-xs text-rose-500">{error}</p>}
    </SectionCard>
  )
}

function PasswordSection() {
  const changePassword = useAuthStore((s) => s.changePassword)
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [status, setStatus] = useState<'idle' | 'saving' | 'saved'>('idle')
  const [error, setError] = useState('')

  const valid =
    oldPassword.length >= 6 &&
    newPassword.length >= 6 &&
    newPassword === confirm

  const handleSubmit = async () => {
    setStatus('saving')
    setError('')
    try {
      await changePassword(oldPassword, newPassword)
      setStatus('saved')
      setOldPassword('')
      setNewPassword('')
      setConfirm('')
      setTimeout(() => setStatus('idle'), 2000)
    } catch (e) {
      setError(e instanceof Error ? e.message : '修改失败')
      setStatus('idle')
    }
  }

  return (
    <SectionCard title="修改密码" description="修改后下次登录生效">
      <div className="grid max-w-md gap-3">
        <label>
          <span className="mb-1 block text-xs font-medium text-slate-500">当前密码</span>
          <input
            type="password"
            value={oldPassword}
            onChange={(e) => setOldPassword(e.target.value)}
            className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-brand-400 focus:ring-2 focus:ring-brand-100"
          />
        </label>
        <label>
          <span className="mb-1 block text-xs font-medium text-slate-500">新密码（至少 6 位）</span>
          <input
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-brand-400 focus:ring-2 focus:ring-brand-100"
          />
        </label>
        <label>
          <span className="mb-1 block text-xs font-medium text-slate-500">确认新密码</span>
          <input
            type="password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-brand-400 focus:ring-2 focus:ring-brand-100"
          />
        </label>
        {newPassword && confirm && newPassword !== confirm && (
          <p className="text-xs text-rose-500">两次输入的新密码不一致</p>
        )}
        {error && <p className="text-xs text-rose-500">{error}</p>}
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!valid || status === 'saving'}
          className="w-fit rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-brand-700 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400"
        >
          {status === 'saved' ? '已修改' : status === 'saving' ? '提交中…' : '修改密码'}
        </button>
      </div>
    </SectionCard>
  )
}

function AboutSection() {
  return (
    <SectionCard title="关于系统" description="当前前端运行于 Mock 模式，后端网关接入后自动切换">
      <ul className="space-y-2.5 text-sm text-slate-600">
        <li className="flex items-start gap-2.5">
          <IconSparkles className="mt-0.5 h-4 w-4 shrink-0 text-brand-500" />
          <span>
            <strong className="font-medium text-slate-800">智能体老师</strong>
            ：Java 负责用户、鉴权与会话归属；Python AgentLoop 负责上下文管理、
            工具调用与流式回答
          </span>
        </li>
        <li className="flex items-start gap-2.5">
          <IconSettings className="mt-0.5 h-4 w-4 shrink-0 text-brand-500" />
          <span>
            聊天事件流遵循后端 SSE 协议：
            <code className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-xs text-rose-600">
              run_started → text_delta → tool_* → run_finished
            </code>
          </span>
        </li>
        <li className="flex items-start gap-2.5">
          <IconUser className="mt-0.5 h-4 w-4 shrink-0 text-brand-500" />
          <span>
            每个用户拥有独立的知识库与长期记忆，文档与记忆数据严格按用户隔离
          </span>
        </li>
      </ul>
    </SectionCard>
  )
}

export default function SettingsPage() {
  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-2xl space-y-6 px-4 py-8">
        <h2 className="text-xl font-bold text-slate-900">设置</h2>
        <ProfileSection />
        <PasswordSection />
        <AboutSection />
      </div>
    </div>
  )
}
