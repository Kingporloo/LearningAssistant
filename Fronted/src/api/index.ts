/**
 * API 入口：默认使用 Mock，网关就绪后通过环境变量切换。
 *
 * .env.local:
 *   VITE_USE_MOCK=false
 *   VITE_API_BASE_URL=/api
 *   VITE_USER_API_BASE_URL=http://127.0.0.1:8081
 */

import type { ApiClient } from './client'
import { createMockApiClient } from './mock'
import { createRealApiClient } from './real'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

export const api: ApiClient = useMock
  ? createMockApiClient()
  : createRealApiClient()

export { ApiRequestError, getToken, setToken, clearToken } from './client'
export type { ApiClient, ChatRunHandle, ChatRunParams } from './client'
