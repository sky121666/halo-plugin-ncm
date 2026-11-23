import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useSongPagination } from '../useSongPagination'

type TestPage = { items: []; offset: number; total: number }

function mountPager() {
  const reads: Array<{ offset: number; signal: AbortSignal; resolve: (value: TestPage) => void }> = []
  const focusHeading = vi.fn()
  let pager!: ReturnType<typeof useSongPagination<TestPage>>

  const wrapper = mount(defineComponent({
    setup() {
      pager = useSongPagination<TestPage>((offset, _size, signal) =>
        new Promise((resolve) => { reads.push({ offset, signal, resolve }) }),
      )
      pager.panel.value = { focusHeading }
      return () => h('div')
    },
  }))

  function deliver(index: number, total: number) {
    const read = reads[index]!
    read.resolve({ items: [], offset: read.offset, total })
  }

  return { wrapper, pager, reads, deliver, focusHeading }
}

describe('曲目分页', () => {
  it('连续切页时只接收最新请求并定位到最新结果', async () => {
    const { wrapper, pager, reads, deliver, focusHeading } = mountPager()
    deliver(0, 200)
    await flushPromises()

    pager.page.value = 2
    await nextTick()
    pager.page.value = 3
    await nextTick()

    expect(reads.map((read) => read.offset)).toEqual([0, 50, 100])
    expect(reads[1]?.signal.aborted).toBe(true)

    deliver(2, 200)
    await flushPromises()
    deliver(1, 200)
    await flushPromises()

    expect(pager.data.value?.offset).toBe(100)
    expect(focusHeading).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('歌曲减少导致当前页消失时请求最后一个有效页', async () => {
    const { wrapper, pager, reads, deliver, focusHeading } = mountPager()
    deliver(0, 101)
    await flushPromises()

    pager.page.value = 3
    await nextTick()
    deliver(1, 100)
    await flushPromises()

    expect(pager.page.value).toBe(2)
    expect(reads.map((read) => read.offset)).toEqual([0, 100, 50])
    expect(focusHeading).not.toHaveBeenCalled()

    deliver(2, 100)
    await flushPromises()
    expect(pager.data.value?.offset).toBe(50)
    expect(pager.loading.value).toBe(false)
    expect(focusHeading).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
