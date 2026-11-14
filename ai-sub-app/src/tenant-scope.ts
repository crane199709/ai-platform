import { onBeforeUnmount } from 'vue'
import { ApiError, useTenantClient } from '@crane199709/sdk'

// Each mounted view owns its requests. Shell session changes remount the view; late results fail closed.
export function useTenantScope() {
  const client = useTenantClient()
  const controller = new AbortController()
  onBeforeUnmount(() => controller.abort())
  async function api<T>(path: string, init?: RequestInit): Promise<T> {
    const result = await client.api<T>(path, { ...init, signal: controller.signal })
    if (controller.signal.aborted) throw new DOMException('Session changed', 'AbortError')
    return result
  }
  return { client, api, signal: controller.signal }
}
export function json(method: string, body: unknown): RequestInit {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}
export function isAbort(error: unknown) {
  return error instanceof Error && error.name === 'AbortError'
}
export function errorTip(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.httpStatus === 403) return '没有执行此操作的权限，请联系租户管理员。'
    if (error.httpStatus === 401) return '会话已失效，请重新登录。'
    if (error.code === 'usage.planUnavailable') return '套餐配额尚未同步，请稍后重试或联系管理员。'
    if (/quota|tokenLimit/i.test(error.code))
      return '本月 AI 配额已耗尽，请联系租户管理员调整套餐。'
    if (error.httpStatus === 429)
      return `当前请求繁忙，请${error.retryAfter ? `在 ${error.retryAfter} 秒后` : '稍后'}重试。`

    if (error.httpStatus === 409) return error.userTip || '当前状态已变化，请刷新后重试。'
    return error.userTip || '操作失败，请稍后重试。'
  }
  return '连接中断，请检查网络后重试。'
}
export function toolTitle(tool: { displayName?: string; nativeName?: string; name?: string }) {
  const name = tool.displayName || tool.nativeName || tool.name
  return !name || /^(?:mcp__ai_service__)?m[a-f0-9]{32}$/.test(name) ? '已审核工具' : name
}
