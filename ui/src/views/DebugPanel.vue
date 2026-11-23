<script setup lang="ts">
import {
  Toast,
  VAlert,
  VButton,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VPagination,
  VSpace,
  VStatusDot,
  VTag,
} from '@halo-dev/components'
import { computed, onMounted, ref, watch } from 'vue'
import {
  callApi,
  ncmApiClient,
  PUBLIC_API_BASE,
  TIER_LABEL,
  type CallResult,
  type Tier,
} from '@/api'
import type { NcmApiMeta } from '@/api/generated'

const apis = ref<NcmApiMeta[]>([])
const loading = ref(true)
const loadFailed = ref(false)

// ---------- 筛选与分页 ----------
const keyword = ref('')
const tier = ref<Tier | undefined>()
const availability = ref<string | undefined>('debuggable')
const page = ref(1)
const size = ref(20)

const TIER_ITEMS = [
  { label: '全部', value: undefined },
  ...(Object.keys(TIER_LABEL) as Tier[]).map((t) => ({ label: TIER_LABEL[t], value: t })),
]
const AVAILABILITY_ITEMS = [
  { label: '全部目录', value: undefined },
  { label: '可调试', value: 'debuggable' },
  { label: '不支持', value: 'unsupported' },
]

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return apis.value.filter((a) => {
    const usable = a.status === 'ready' || a.status === 'custom'
    return (
      (!k || a.name.includes(k)) &&
      (!tier.value || a.tier === tier.value) &&
      (availability.value !== 'debuggable' || a.debuggable === true) &&
      (availability.value !== 'unsupported' || !usable)
    )
  })
})
const paged = computed(() =>
  filtered.value.slice((page.value - 1) * size.value, page.value * size.value),
)
watch([keyword, tier, availability], () => (page.value = 1))

const TIER_THEME: Record<Tier, 'default' | 'primary' | 'secondary' | 'danger'> = {
  public: 'primary',
  personal: 'secondary',
  write: 'danger',
  auth: 'default',
  admin: 'default',
}
const STATUS_LABEL: Record<string, string> = {
  ready: '自动转换',
  custom: '手写',
  unsupported: '不支持',
  manual: '未移植',
}

// ---------- 调用 ----------
const current = ref<NcmApiMeta>()
const rows = ref<{ key: string; value: string }[]>([{ key: '', value: '' }])
const result = ref<CallResult>()
const calling = ref(false)

/** 常用接口的示例参数，选中时自动填入。 */
const EXAMPLES: Record<string, Record<string, string>> = {
  cloudsearch: { keywords: '晴天', limit: '10' },
  search: { keywords: '晴天', limit: '10' },
  song_detail: { ids: '186016' },
  song_url: { id: '186016' },
  song_url_v1: { id: '186016', level: 'standard' },
  lyric_new: { id: '186016' },
  playlist_detail: { id: '24381616' },
  playlist_track_all: { id: '24381616', limit: '10' },
  album: { id: '32311' },
  artist_detail: { id: '6452' },
  comment_hot: { id: '186016', type: '0', limit: '5' },
  user_playlist: { uid: '32953014', limit: '10' },
}

function select(a: NcmApiMeta) {
  if (calling.value) return
  current.value = a
  result.value = undefined
  const list = Object.entries(EXAMPLES[a.name] ?? {}).map(([key, value]) => ({ key, value }))
  rows.value = list.length ? list : [{ key: '', value: '' }]
}

const params = computed(() => {
  const p: Record<string, string> = {}
  rows.value.forEach((r) => {
    if (r.key.trim()) p[r.key.trim()] = r.value
  })
  return p
})

/** 公开接口给出访客可以直接访问的地址，方便写前端时复制。 */
const publicUrl = computed(() => {
  if (!current.value || current.value.tier !== 'public' || current.value.open === false) return ''
  const qs = new URLSearchParams(params.value).toString()
  return `${PUBLIC_API_BASE}${current.value.path}${qs ? `?${qs}` : ''}`
})

async function send() {
  if (!current.value?.debuggable || calling.value) return
  calling.value = true
  try {
    result.value = await callApi(current.value.name, params.value)
  } finally {
    calling.value = false
  }
}

const resultText = computed(() =>
  result.value === undefined ? '' : JSON.stringify(result.value.data, null, 2),
)

async function copy(text: string) {
  await navigator.clipboard.writeText(text)
  Toast.success('已复制')
}

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    apis.value = (await ncmApiClient.ncm.listNcmApis()).data.items
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="ncm-debug">
    <!-- 接口列表 -->
    <aside class="ncm-debug__list">
      <VSpace direction="column" align="stretch">
        <SearchInput v-model="keyword" placeholder="搜索接口名" />
        <VSpace spacing="lg">
          <FilterDropdown v-model="tier" label="等级" :items="TIER_ITEMS" />
          <FilterDropdown v-model="availability" label="状态" :items="AVAILABILITY_ITEMS" />
        </VSpace>
      </VSpace>

      <VLoading v-if="loading" />
      <VAlert v-else-if="loadFailed" type="error" title="接口列表读取失败" :closable="false">
        <template #actions>
          <VButton size="sm" @click="load">重试</VButton>
        </template>
      </VAlert>
      <VEmpty v-else-if="!filtered.length" title="没有匹配的接口" />
      <template v-else>
        <div class="ncm-debug__entities">
          <VEntityContainer>
            <VEntity
              v-for="a in paged"
              :key="a.name"
              :is-selected="current?.name === a.name"
              :aria-label="`选择接口 ${a.name}`"
              tabindex="0"
              class="cursor-pointer"
              @click="select(a)"
              @keydown.enter.self.prevent="select(a)"
              @keydown.space.self.prevent="select(a)"
            >
              <template #start>
                <VEntityField>
                  <template #title>
                    <span class="ncm-debug__api-name">{{ a.name }}</span>
                  </template>
                  <template #description>
                    <div class="ncm-debug__api-meta">
                      <span class="text-xs text-gray-500">
                        {{ STATUS_LABEL[a.status] ?? a.status }}
                      </span>
                      <VTag :theme="TIER_THEME[a.tier]">{{ TIER_LABEL[a.tier] }}</VTag>
                      <VTag
                        v-if="a.open === false && ['public', 'personal', 'write'].includes(a.tier)"
                        >未开放</VTag
                      >
                    </div>
                  </template>
                </VEntityField>
              </template>
            </VEntity>
          </VEntityContainer>
        </div>
        <VPagination
          v-model:page="page"
          v-model:size="size"
          class="ncm-debug__pagination"
          :total="filtered.length"
          :size-options="[20, 50, 100]"
        />
      </template>
    </aside>

    <!-- 调用面板 -->
    <section class="ncm-debug__main">
      <VEmpty v-if="!current" title="选择要调试的接口" />
      <VSpace v-else direction="column" align="stretch" spacing="lg" class="ncm-debug__controls">
        <VSpace class="ncm-debug__toolbar">
          <span class="ncm-debug__name">{{ current.name }}</span>
          <VTag :theme="TIER_THEME[current.tier]">{{ TIER_LABEL[current.tier] }}</VTag>
          <VTag>{{ STATUS_LABEL[current.status] ?? current.status }}</VTag>
        </VSpace>

        <VAlert
          v-if="current.status === 'unsupported'"
          type="warning"
          title="这个接口不支持"
          description="原因见 README 的“已知限制”。"
          :closable="false"
        />

        <div v-if="current.debuggable">
          <div class="ncm-debug__label">参数</div>
          <div v-for="(r, i) in rows" :key="i" class="ncm-debug__row">
            <FormKit
              v-model="r.key"
              type="text"
              placeholder="参数名"
              aria-label="参数名"
              outer-class="ncm-debug__parameter"
            />
            <FormKit
              v-model="r.value"
              type="text"
              placeholder="值"
              aria-label="参数值"
              outer-class="ncm-debug__parameter"
            />
            <VButton size="sm" @click="rows.splice(i, 1)">删除</VButton>
          </div>
          <VButton size="sm" @click="rows.push({ key: '', value: '' })">添加参数</VButton>
        </div>

        <VAlert
          v-if="!current.debuggable && current.status !== 'unsupported'"
          type="info"
          title="仅查看接口信息"
          description="游客只读调试限于已核对的公开接口。"
          :closable="false"
        />
        <VAlert
          v-if="current.open === false && current.debuggable"
          type="info"
          title="仅调试可用"
          description="通用接口入口尚未开放此接口。"
          :closable="false"
        />
        <VAlert v-if="publicUrl" type="success" title="访客可直接访问的地址" :closable="false">
          <template #description>
            <code class="ncm-debug__url">{{ publicUrl }}</code>
          </template>
          <template #actions>
            <VButton size="sm" @click="copy(publicUrl)">复制地址</VButton>
          </template>
        </VAlert>

        <VSpace class="ncm-debug__toolbar">
          <VButton type="secondary" :loading="calling" v-if="current.debuggable" @click="send">
            发送
          </VButton>
          <VStatusDot
            v-if="result"
            :state="result.status === 200 ? 'success' : 'error'"
            :text="`HTTP ${result.status || '请求失败'} · ${result.ms} ms`"
          />
          <VButton v-if="result" size="sm" @click="copy(resultText)">复制结果</VButton>
        </VSpace>

        <VCodemirror v-if="result" :model-value="resultText" language="json" height="480px" />
      </VSpace>
    </section>
  </div>
</template>

<style scoped>
.ncm-debug {
  container: ncm-debug / inline-size;
  display: grid;
  grid-template-columns: minmax(0, 20rem) minmax(0, 1fr);
  gap: 16px;
}
.ncm-debug__list {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ncm-debug__entities {
  max-height: min(32rem, 52vh);
  overflow: auto;
  border: 1px solid #f3f4f6;
  border-radius: 4px;
}
.ncm-debug__api-name,
.ncm-debug__name {
  overflow-wrap: anywhere;
  white-space: normal;
}
.ncm-debug__api-name {
  font-size: 14px;
  font-weight: 500;
}
.ncm-debug__api-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
}
.ncm-debug__main,
.ncm-debug__controls {
  min-width: 0;
}
.ncm-debug__controls {
  width: 100%;
}
.ncm-debug__name {
  font-size: 16px;
  font-weight: 600;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.ncm-debug__pagination {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}
.ncm-debug__toolbar {
  flex-wrap: wrap;
}
.ncm-debug__pagination :deep(.pagination__controller) {
  flex-wrap: wrap;
  justify-content: flex-start;
}
.ncm-debug__label {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 8px;
}
.ncm-debug__row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr)) auto;
  gap: 8px;
  align-items: center;
  max-width: 44rem;
  margin-bottom: 8px;
}
.ncm-debug__row :deep(.ncm-debug__parameter) {
  padding-block: 0;
  min-width: 0;
}
.ncm-debug__url {
  overflow-wrap: anywhere;
  font-size: 12px;
}
@container ncm-debug (max-width: 44rem) {
  .ncm-debug__list,
  .ncm-debug__main {
    grid-column: 1 / -1;
  }
  .ncm-debug__entities {
    max-height: 19rem;
  }
}
</style>
