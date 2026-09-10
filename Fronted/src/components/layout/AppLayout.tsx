import { useEffect, useState } from 'react'
import { Outlet } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { useSessionsStore } from '@/stores/sessions'
import { IconMenu } from '@/components/Icons'

export function AppLayout() {
  const [drawerOpen, setDrawerOpen] = useState(false)
  const loadSessions = useSessionsStore((s) => s.load)

  useEffect(() => {
    void loadSessions()
  }, [loadSessions])

  return (
    <div className="flex h-full">
      {/* 桌面侧边栏 */}
      <div className="hidden md:block">
        <Sidebar />
      </div>

      {/* 移动端抽屉 */}
      {drawerOpen && (
        <div className="fixed inset-0 z-40 md:hidden">
          <button
            type="button"
            aria-label="关闭菜单"
            className="absolute inset-0 bg-slate-900/40"
            onClick={() => setDrawerOpen(false)}
          />
          <div className="absolute inset-y-0 left-0 shadow-xl">
            <Sidebar onClose={() => setDrawerOpen(false)} />
          </div>
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        {/* 移动端顶栏 */}
        <div className="flex items-center gap-3 border-b border-slate-200 bg-white px-4 py-2.5 md:hidden">
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            className="rounded-lg p-1.5 text-slate-500 hover:bg-slate-100"
          >
            <IconMenu className="h-5 w-5" />
          </button>
          <span className="text-sm font-semibold text-slate-800">智能体老师</span>
        </div>

        <main className="min-h-0 flex-1 bg-slate-50">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
