/**
 * API 入口：默认连接真实网关，仅在显式配置时使用 Mock。
 *
 * .env.local:
 *   VITE_USE_MOCK=true
 *   VITE_API_BASE_URL=/api
 */

import type { ApiClient } from './client'
import { createMockApiClient } from './mock'
import { createRealApiClient } from './real'

const useMock = import.meta.env.VITE_USE_MOCK === 'true'

export const isMockApi = useMock

export const api: ApiClient = useMock
  ? createMockApiClient()
  : createRealApiClient()

export { ApiRequestError, getToken, setToken, clearToken } from './client'
export type { ApiClient, ChatRunHandle, ChatRunParams } from './client'
