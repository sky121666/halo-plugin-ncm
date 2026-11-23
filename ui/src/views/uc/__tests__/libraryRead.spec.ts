import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { describe, expect, it } from 'vitest'
import { libraryProblem, useLibraryRead } from '@/composables/libraryRead'

describe('曲库读取状态', () => {
  it.each([
    '网易云登录已失效，请在「账号」标签页重新绑定',
    '还没有绑定网易云账号，请先在「账号」标签页绑定',
    '保存的网易云登录信息无法读取，请在「账号」标签页重新绑定',
  ])('将绑定失败引导到账号页', (detail) => {
    const result = libraryProblem({ response: { status: 403, data: { detail } } })
    expect(result.recovery).toBe('account')
    expect(result.message).toContain('请检查账号绑定')
  })

  it('区分权限拦截、Halo 会话失效和临时网络失败', () => {
    expect(libraryProblem({ response: { status: 403 } }).recovery).toBe('none')
    expect(libraryProblem({ response: { status: 401 } })).toEqual({
      message: '请重新登录 Halo 后再试。',
      recovery: 'none',
    })
    expect(libraryProblem(new Error('Network Error'))).toEqual({
      message: '暂时无法读取，请稍后重试。',
      recovery: 'retry',
    })
  })

  it('取消过期读取及离页请求，晚到失败不会覆盖新结果', async () => {
    const requests: Array<{
      signal: AbortSignal
      resolve: (value: string) => void
      reject: (cause: Error) => void
    }> = []
    let reader!: ReturnType<typeof useLibraryRead<string>>
    const page = mount(defineComponent({
      setup() {
        reader = useLibraryRead((signal) =>
          new Promise((resolve, reject) => { requests.push({ signal, resolve, reject }) }),
        )
        return () => h('div')
      },
    }))

    void reader.load()
    void reader.load()
    expect(requests[0]?.signal.aborted).toBe(true)
    requests[1]?.resolve('新数据')
    await flushPromises()
    requests[0]?.reject(new Error('旧请求失败'))
    await flushPromises()
    expect(reader.data.value).toBe('新数据')
    expect(reader.error.value).toBeUndefined()
    expect(reader.loading.value).toBe(false)

    void reader.load()
    page.unmount()
    expect(requests[2]?.signal.aborted).toBe(true)
  })
})
