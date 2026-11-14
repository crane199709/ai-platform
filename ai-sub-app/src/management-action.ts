import { ref } from 'vue'
import { errorTip, isAbort } from './tenant-scope'

// 三个配置页共用操作反馈；领域请求和成功提示由页面提供。
export function useManagementAction(signal: AbortSignal) {
  const saving = ref(false)
  const error = ref('')
  const success = ref('')
  async function action(task: () => Promise<void>) {
    saving.value = true
    error.value = ''
    success.value = ''
    try {
      await task()
    } catch (e) {
      if (!isAbort(e)) error.value = errorTip(e)
    } finally {
      if (!signal.aborted) saving.value = false
    }
  }
  return { saving, error, success, action }
}
