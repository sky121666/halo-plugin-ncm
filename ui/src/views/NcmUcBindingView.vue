<script setup lang="ts">
import { VButton, VCard, VEmpty, VLoading, VPageHeader } from '@halo-dev/components'
import { computed, nextTick, onMounted, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import RiNeteaseCloudMusicLine from '~icons/ri/netease-cloud-music-line'
import { myBindingApi } from '@/api'
import type { NcmAccountView } from '@/api/generated'
import AccountBinding from '@/components/AccountBinding.vue'
import MusicTabs from '@/components/MusicTabs.vue'
import { musicPageUrl } from '@/consoleNavigation'
import LikedSongs from './uc/LikedSongs.vue'
import MyPlaylists from './uc/MyPlaylists.vue'
import ProfileStats from './uc/ProfileStats.vue'
import RankRecords from './uc/RankRecords.vue'
import RecentRecords from './uc/RecentRecords.vue'
import { openAccountBinding } from '@/composables/libraryRead'

const TABS = [
  { id: 'account', label: '账号绑定' },
  { id: 'playlists', label: '我的歌单' },
  { id: 'likes', label: '喜欢的歌' },
  { id: 'recent', label: '最近播放' },
  { id: 'rank', label: '听歌排行' },
]
const TAB_IDS = TABS.map((t) => t.id)

const route = useRoute()
const router = useRouter()

function tabFromQuery(): string | undefined {
  const t = route.query.tab
  return typeof t === 'string' && TAB_IDS.includes(t) ? t : undefined
}

const account = ref<NcmAccountView>()
const bindingBusy = ref(false)
const checking = ref(true)
const accountHeading = ref<HTMLElement>()
const tabOptions = ref<InstanceType<typeof MusicTabs>>()
const canReadLibrary = computed(() => account.value?.bound === true && account.value.readable !== false)
const activeTab = computed({
  get: () => tabFromQuery() ?? (canReadLibrary.value ? 'playlists' : 'account'),
  set: (tab: string) => {
    if (tab !== route.query.tab || route.query.playlist !== undefined) void selectTab(tab)
  },
})

function selectTab(tab: string) {
  return router.replace({ query: { ...route.query, tab, playlist: undefined } })
}

async function showAccount() {
  await selectTab('account')
  await nextTick()
  accountHeading.value?.focus()
}
provide(openAccountBinding, showAccount)

async function refreshBinding() {
  checking.value = true
  try {
    account.value = await myBindingApi.status()
  } catch {
    account.value = undefined
  } finally {
    checking.value = false
  }
}

onMounted(refreshBinding)

function retryBinding() {
  tabOptions.value?.focusSelected()
  void refreshBinding()
}
</script>

<template>
  <VPageHeader title="网易云音乐">
    <template #icon>
      <RiNeteaseCloudMusicLine />
    </template>
    <template #actions>
      <a
        :href="musicPageUrl"
        target="_blank"
        rel="noopener"
        aria-label="查看音乐页，在新标签页打开"
        class="ncm-uc__music-link"
      >
        查看音乐页
      </a>
    </template>
  </VPageHeader>

  <div class="ncm-uc m-0 md:m-4">
    <VCard :body-class="['!px-3', '!py-2']">
      <MusicTabs
        ref="tabOptions"
        v-model="activeTab"
        class="ncm-uc__tabs"
        label="个人音乐空间"
        :items="TABS"
        :disabled="bindingBusy"
      />
      <p
        v-if="!checking && canReadLibrary && account?.managedInConsole && activeTab !== 'account'"
        class="ncm-uc__hint"
      >
        当前曲库使用你在后台绑定的站长账号。
      </p>
    </VCard>

    <VCard v-if="checking">
      <div role="status">
        <VLoading />
        <span class="sr-only">正在读取网易云绑定</span>
      </div>
    </VCard>

    <VCard v-else-if="!account">
      <VEmpty title="账号状态读取失败">
        <template #actions>
          <VButton @click="retryBinding">重试</VButton>
        </template>
      </VEmpty>
    </VCard>

    <VCard v-else-if="activeTab === 'account'" class="ncm-uc__account">
      <template #header>
        <h3 ref="accountHeading" tabindex="-1" class="ncm-uc__account-title">网易云账号</h3>
      </template>
      <AccountBinding
        v-model:account="account"
        v-model:busy="bindingBusy"
        :api="myBindingApi"
        label="网易云账号"
        unbound-description="绑定后可查看歌单、喜欢的歌和听歌记录。"
      />
      <ProfileStats v-if="canReadLibrary" :key="`${account.userId ?? ''}:${account.boundAt ?? ''}`" />
    </VCard>

    <VCard v-else-if="!canReadLibrary">
      <VEmpty
        :title="account?.bound ? '网易云登录信息无法读取' : '尚未绑定网易云账号'"
        :message="account?.bound ? '重新绑定后可恢复曲库。' : '绑定后可查看歌单、喜欢的歌和听歌记录。'"
      >
        <template #actions>
          <VButton type="secondary" @click="showAccount">
            {{ account?.bound ? '查看绑定' : '去绑定' }}
          </VButton>
        </template>
      </VEmpty>
    </VCard>

    <div v-else class="ncm-uc__panel">
      <MyPlaylists v-if="activeTab === 'playlists'" />
      <LikedSongs v-else-if="activeTab === 'likes'" />
      <RecentRecords v-else-if="activeTab === 'recent'" />
      <RankRecords v-else-if="activeTab === 'rank'" />
    </div>
  </div>
</template>

<style scoped>
.ncm-uc {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: .75rem;
}
.ncm-uc__account {
  width: 100%;
  max-width: 48rem;
}
.ncm-uc__account-title {
  margin: 0;
  padding: .875rem 1rem;
  font-size: 14px;
  font-weight: 600;
}
.ncm-uc__music-link {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  padding: 0 8px;
  color: #374151;
  font-size: 14px;
  text-decoration: underline;
  text-underline-offset: 4px;
  white-space: nowrap;
}
.ncm-uc__music-link:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
.ncm-uc__hint {
  margin: 4px 4px 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 640px), (pointer: coarse) {
  .ncm-uc__music-link {
    min-height: 44px;
  }
}

@media (max-width: 640px) {
  .ncm-uc__tabs {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
  }
  .ncm-uc__tabs :deep(button) {
    padding-inline: 4px;
    font-size: 13px;
  }
  .ncm-uc__panel :deep(.entity-wrapper) {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }
  .ncm-uc__panel :deep(.entity-start-wrapper),
  .ncm-uc__panel :deep(.entity-end-wrapper) {
    min-width: 0;
    width: 100%;
  }
  .ncm-uc__panel :deep(.entity-end-wrapper) {
    padding-top: 0;
  }
  .ncm-uc__panel :deep(.entity-start),
  .ncm-uc__panel :deep(.entity-end) {
    gap: .625rem;
  }
  .ncm-uc__panel :deep(.entity-end) {
    justify-content: flex-start;
    flex-wrap: wrap;
  }
  .ncm-uc__panel :deep(.entity-field-wrapper) {
    min-width: 0;
    max-width: 100%;
  }
}
@media (max-width: 359px) {
  .ncm-uc__tabs {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
