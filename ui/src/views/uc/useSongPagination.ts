import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { MeSong } from './meApi'
import { libraryProblem, type LibraryProblem } from '@/composables/libraryRead'

type SongPage = { items: MeSong[]; offset: number; total: number }

export function useSongPagination<T extends SongPage>(
  read: (offset: number, size: number, signal: AbortSignal) => Promise<T>,
) {
  const page = ref(1)
  const size = ref(50)
  const loading = ref(true)
  const error = shallowRef<LibraryProblem>()
  const data = shallowRef<T>()
  const panel = ref<{ focusHeading: () => void }>()
  let current: AbortController | undefined

  async function load(focusResults = false) {
    current?.abort()
    const request = new AbortController()
    current = request
    loading.value = true
    error.value = undefined

    try {
      const result = await read((page.value - 1) * size.value, size.value, request.signal)
      if (request.signal.aborted) return

      const lastPage = Math.max(1, Math.ceil(result.total / size.value))
      if (page.value > lastPage) {
        page.value = lastPage
        return
      }
      data.value = result

      if (focusResults) {
        await nextTick()
        if (!request.signal.aborted) panel.value?.focusHeading()
      }
    } catch (cause) {
      if (!request.signal.aborted) error.value = libraryProblem(cause)
    } finally {
      if (current === request) loading.value = false
    }
  }

  watch([page, size], () => { void load(true) })
  onMounted(() => { void load() })
  onBeforeUnmount(() => current?.abort())

  return { page, size, loading, error, data, panel, load }
}
