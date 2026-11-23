import { onBeforeUnmount, ref, shallowRef, type InjectionKey } from 'vue'

export type LibraryProblem = {
  message: string
  recovery: 'retry' | 'account' | 'none'
}

export const openAccountBinding: InjectionKey<() => Promise<void>> = Symbol('ncm-account-binding')

export function libraryProblem(cause: unknown, fallback = '暂时无法读取，请稍后重试。'): LibraryProblem {
  const response = (cause as { response?: { status?: number; data?: { detail?: unknown } } } | null)
    ?.response
  const detail = typeof response?.data?.detail === 'string' ? response.data.detail.trim() : ''
  if (response?.status === 403) {
    // 曲库接口使用 403 表示绑定不可用，Halo 权限校验也可返回 403。
    const reason = /^(还没有绑定网易云账号|保存的网易云登录信息无法读取|网易云登录已失效)/.exec(detail)?.[0]
    return reason
      ? { message: `${reason}，请检查账号绑定。`, recovery: 'account' }
      : { message: detail || '当前账号没有权限执行此操作。', recovery: 'none' }
  }
  if (response?.status === 401) {
    return { message: '请重新登录 Halo 后再试。', recovery: 'none' }
  }
  return { message: detail || fallback, recovery: 'retry' }
}

export function useLibraryRead<T>(read: (signal: AbortSignal) => Promise<T>) {
  const data = shallowRef<T>()
  const loading = ref(true)
  const error = shallowRef<LibraryProblem>()
  let current: AbortController | undefined

  async function load() {
    current?.abort()
    const request = new AbortController()
    current = request
    loading.value = true
    error.value = undefined
    try {
      const result = await read(request.signal)
      if (!request.signal.aborted) data.value = result
    } catch (cause) {
      if (!request.signal.aborted) error.value = libraryProblem(cause)
    } finally {
      if (current === request) loading.value = false
    }
  }

  onBeforeUnmount(() => current?.abort())
  return { data, loading, error, load }
}
