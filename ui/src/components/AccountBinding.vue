<script setup lang="ts">
import {
  Toast,
  VAlert,
  VAvatar,
  VButton,
  VDescription,
  VDescriptionItem,
  VLoading,
  VSpace,
  VStatusDot,
  VTag,
} from '@halo-dev/components'
import { submitForm } from '@formkit/core'
import { utils } from '@halo-dev/ui-shared'
import QRCode from 'qrcode'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { BindingApi } from '@/api'
import type { NcmAccountView } from '@/api/generated'
import { siteAccountConsoleUrl } from '@/consoleNavigation'
import MusicTabs from './MusicTabs.vue'
import NcmConfirmDialog, { type ConfirmAction } from './NcmConfirmDialog.vue'

/**
 * 网易云账号绑定：扫码 / 验证码 / 密码三种方式。
 * 后台的站长账号和个人中心的个人账号共用这个组件，只是传入的接口不同。
 */
const props = defineProps<{
  api: BindingApi
  /** 展示用，例如“站长网易云账号” */
  label: string
  unboundDescription?: string
}>()
const account = defineModel<NcmAccountView>('account')
const busy = defineModel<boolean>('busy', { default: false })

const PHONE_REGEX = /^\d{5,15}$/
const CTCODE_REGEX = /^\d{1,4}$/
const CAPTCHA_REGEX = /^\d{4,8}$/
const formId = `ncm-phone-form-${Math.random().toString(36).slice(2, 8)}`

const loading = ref(account.value === undefined)
const rebinding = ref(false)
const confirmingUnbind = ref(false)
const bindingModes = ref<InstanceType<typeof MusicTabs>>()
const rebindAction = ref<InstanceType<typeof VButton>>()
const unbindAction = ref<InstanceType<typeof VButton>>()
let active = true

const MODES = [
  { id: 'qr', label: '扫码登录' },
  { id: 'captcha', label: '验证码登录' },
  { id: 'password', label: '密码登录' },
]
const mode = ref<string>('qr')

async function load() {
  loading.value = true
  try {
    const result = await props.api.status()
    if (active) account.value = result
  } catch {
    if (active) account.value = undefined
  } finally {
    loading.value = false
  }
}

const bound = computed(() => account.value?.bound === true && !rebinding.value)
const managedInConsole = computed(() => account.value?.managedInConsole === true)
const unreadable = computed(
  () => account.value?.bound === true && account.value?.readable === false,
)
const isVip = computed(() => (account.value?.vipType ?? 0) > 0)

async function done(result?: NcmAccountView) {
  if (!active) return
  Toast.success('绑定成功')
  rebinding.value = false
  stopPolling()
  qrImg.value = ''
  qrState.value = 'idle'
  if (result) {
    account.value = result
  } else {
    await load()
  }
  await nextTick()
  focusRebindAction()
}

// ---------- 扫码 ----------
const qrImg = ref('')
const qrKey = ref('')
const qrState = ref<'idle' | 'waiting' | 'scanned' | 'authorized' | 'expired'>('idle')
const qrLoading = ref(false)
const qrSaving = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
let qrRun = 0

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = undefined
  }
}

async function startQr() {
  if (qrLoading.value || qrSaving.value) return
  resetQr()
  const run = qrRun
  qrLoading.value = true
  try {
    const { key, qrurl } = await props.api.qrKey()
    if (run !== qrRun) return
    if (!key || !qrurl) {
      Toast.error('获取二维码失败，请稍后重试')
      return
    }
    const image = await QRCode.toDataURL(qrurl, { width: 220, margin: 1 })
    if (run !== qrRun) return
    qrKey.value = key
    qrImg.value = image
    qrState.value = 'waiting'
    timer = setInterval(checkQr, 2000)
  } catch {
    // Halo 的 API Client 提示请求错误。
  } finally {
    if (run === qrRun) qrLoading.value = false
  }
}

let checking = false
async function checkQr() {
  if (checking || !qrKey.value) return
  checking = true
  const run = qrRun
  try {
    const r = await props.api.qrCheck(qrKey.value)
    if (run !== qrRun) return
    if (r.code === 802) {
      qrState.value = 'scanned'
    } else if (r.code === 800) {
      qrState.value = 'expired'
      stopPolling()
    } else if (r.code === 803) {
      qrState.value = 'authorized'
      stopPolling()
    }
  } catch {
    // 网络抖动时继续轮询
  } finally {
    checking = false
  }
}

async function confirmQr() {
  if (qrState.value !== 'authorized' || qrSaving.value) return
  const run = qrRun
  qrSaving.value = true
  try {
    const result = await props.api.qrConfirm(qrKey.value)
    if (run === qrRun && result.code === 200) await done(result.account)
  } catch (error) {
    const response = (error as { response?: { status?: number } }).response
    if (run === qrRun && response?.status === 410) qrState.value = 'expired'
  } finally {
    qrSaving.value = false
  }
}

function resetQr() {
  stopPolling()
  qrRun++
  qrLoading.value = false
  qrKey.value = ''
  qrImg.value = ''
  qrState.value = 'idle'
}

watch([mode, bound], ([currentMode, isBound]) => {
  if (currentMode !== 'qr' || isBound) resetQr()
})

const QR_STATE = {
  idle: { state: 'default', text: '' },
  waiting: { state: 'default', text: '请用网易云音乐 App 扫码' },
  scanned: { state: 'success', text: '已扫码，请在手机上确认登录' },
  authorized: { state: 'success', text: '网易云已授权，请确认绑定' },
  expired: { state: 'warning', text: '二维码已过期，请刷新' },
} as const

// ---------- 手机号 ----------
interface PhoneForm {
  countrycode: string
  phone: string
  captcha?: string
  password?: string
}
const phoneForm = ref<PhoneForm>({ countrycode: '86', phone: '' })
const countdown = ref(0)
const sending = ref(false)
const submitting = ref(false)
const savingBinding = computed(() => qrSaving.value || submitting.value)
let countdownTimer: ReturnType<typeof setInterval> | undefined
watch(savingBinding, (value) => { busy.value = value }, { flush: 'sync' })

async function sendCaptcha() {
  if (sending.value || countdown.value > 0 || savingBinding.value) return
  const { phone, countrycode } = phoneForm.value
  if (!PHONE_REGEX.test(phone ?? '') || !CTCODE_REGEX.test(countrycode ?? '')) {
    Toast.warning('请先填写正确的国家/地区代码和手机号')
    return
  }
  sending.value = true
  try {
    const r = await props.api.captcha(phone, countrycode)
    if (!active) return
    if (r.code !== 200) {
      Toast.error(r.message || `发送失败（${r.code}）`)
      return
    }
    Toast.success('验证码已发送')
    countdown.value = 60
    countdownTimer = setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0 && countdownTimer) clearInterval(countdownTimer)
    }, 1000)
  } catch {
    // 请求错误由 Halo 的拦截器提示。
  } finally {
    sending.value = false
  }
}

async function phoneLogin() {
  if (savingBinding.value) return
  const f = phoneForm.value
  submitting.value = true
  try {
    const r = await props.api.cellphone({
      phone: f.phone,
      countrycode: f.countrycode,
      ...(mode.value === 'password' ? { password: f.password } : { captcha: f.captcha }),
    })
    if (!active) return
    if (r.code !== 200) {
      // 网易云对新设备的密码登录经常要求安全验证，此时建议改用扫码或验证码
      Toast.error(r.message || `登录失败（${r.code}）`)
      return
    }
    phoneForm.value = { countrycode: f.countrycode, phone: f.phone }
    await done(r.account)
  } catch {
    // 请求错误由 Halo 的拦截器提示。
  } finally {
    submitting.value = false
  }
}

function focusRebindAction() {
  const button = rebindAction.value?.$el as HTMLButtonElement | undefined
  button?.focus({ preventScroll: true })
}

async function beginRebinding() {
  mode.value = 'qr'
  rebinding.value = true
  await nextTick()
  bindingModes.value?.focusSelected()
}

async function cancelRebinding() {
  if (savingBinding.value) return
  const { phone, countrycode } = phoneForm.value
  phoneForm.value = { phone, countrycode }
  rebinding.value = false
  await nextTick()
  focusRebindAction()
}

const unbindConfirmation = computed<ConfirmAction>(() => ({
  title: `解除${props.label}绑定`,
  description: '将删除服务端保存的网易云登录信息。再次使用该账号需重新绑定。',
  danger: true,
  confirmText: '解除绑定',
  run: async () => {
    await props.api.unbind()
    Toast.success('已解除绑定')
    await load()
  },
}))

async function closeUnbind() {
  confirmingUnbind.value = false
  await nextTick()
  const button = unbindAction.value?.$el as HTMLButtonElement | undefined
  if (button) button.focus({ preventScroll: true })
  else bindingModes.value?.focusSelected()
}

onMounted(() => {
  if (!account.value) void load()
})
onBeforeUnmount(() => {
  active = false
  busy.value = false
  resetQr()
  if (countdownTimer) clearInterval(countdownTimer)
})
</script>

<template>
  <VLoading v-if="loading" />
  <VAlert v-else-if="!account" type="error" title="网易云账号状态读取失败" :closable="false">
    <template #actions>
      <VButton size="sm" @click="load">重试</VButton>
    </template>
  </VAlert>

  <!-- 已绑定 -->
  <VSpace v-else-if="bound" direction="column" align="stretch" spacing="lg">
    <VAlert
      v-if="unreadable"
      type="error"
      title="登录信息无法读取"
      :description="
        managedInConsole ? '请在 Halo 后台重新绑定站长账号。' : '请重新绑定网易云账号。'
      "
      :closable="false"
    />
    <div class="ncm-account">
      <div class="ncm-account__identity">
        <VAvatar :src="account?.avatarUrl || undefined" alt="" size="lg" circle />
        <VSpace spacing="sm" class="ncm-account__name">
          <span class="ncm-account__nickname">{{ account?.nickname || '网易云账号' }}</span>
          <VTag :theme="isVip ? 'primary' : 'default'" rounded>{{ isVip ? '会员' : '非会员' }}</VTag>
        </VSpace>
      </div>
      <div class="ncm-account__info">
        <VDescription>
          <VDescriptionItem label="网易云 ID" :content="String(account?.userId ?? '-')" />
          <VDescriptionItem
            label="绑定时间"
            :content="utils.date.format(account?.boundAt) || '-'"
          />
          <VDescriptionItem
            v-if="managedInConsole"
            label="绑定来源"
            content="Halo 后台 · 站长账号"
          />
        </VDescription>
      </div>
    </div>
    <div
      role="group"
      :aria-label="`${label}操作`"
      class="ncm-account__actions flex flex-wrap items-center gap-2 border-t border-gray-100 pt-3"
    >
      <template v-if="managedInConsole">
        <VButton ref="rebindAction" @click="beginRebinding">单独绑定个人账号</VButton>
        <a :href="siteAccountConsoleUrl" class="ncm-account__manage">管理站长账号</a>
      </template>
      <template v-else>
        <VButton ref="rebindAction" @click="beginRebinding">重新绑定</VButton>
        <VButton ref="unbindAction" type="danger" @click="confirmingUnbind = true">
          解除绑定
        </VButton>
      </template>
    </div>
  </VSpace>

  <!-- 未绑定 / 重新绑定 -->
  <VSpace v-else direction="column" align="stretch" spacing="lg">
    <VAlert
      v-if="rebinding"
      type="warning"
      :title="
        managedInConsole
          ? '绑定成功后，曲库会切换到该账号'
          : '新账号绑定成功后会替换当前绑定'
      "
      :closable="false"
    >
      <template #actions>
        <VButton size="sm" class="ncm-binding__action" :disabled="savingBinding" @click="cancelRebinding">
          取消
        </VButton>
      </template>
    </VAlert>
    <VAlert
      v-else
      type="info"
      :title="`还没有绑定${label}`"
      :description="
        unboundDescription ??
        '绑定后可以使用每日推荐、私人 FM、听歌记录、点赞收藏等个人功能。登录信息只保存在服务端，并且加密存储，不会返回给浏览器。'
      "
      :closable="false"
    />

    <MusicTabs ref="bindingModes" v-model="mode" label="网易云登录方式" :items="MODES" :disabled="savingBinding" />

    <!-- 扫码 -->
    <VSpace v-if="mode === 'qr'" direction="column" align="start">
      <img
        v-if="qrImg"
        :src="qrImg"
        alt="网易云登录二维码"
        class="ncm-qr"
        :class="{ 'ncm-qr--expired': qrState === 'expired' }"
      />
      <VStatusDot
        v-if="qrState !== 'idle'"
        :state="QR_STATE[qrState].state"
        :animate="qrState === 'waiting'"
        :text="QR_STATE[qrState].text"
      />
      <VSpace spacing="sm" class="flex-wrap">
        <VButton
          v-if="qrState === 'authorized'"
          class="ncm-binding__action"
          type="secondary"
          :loading="qrSaving"
          @click="confirmQr"
        >
          确认绑定
        </VButton>
        <VButton
          class="ncm-binding__action"
          :type="qrState === 'authorized' ? 'default' : 'secondary'"
          :loading="qrLoading"
          :disabled="qrSaving"
          @click="startQr"
        >
          {{ qrImg ? '刷新二维码' : '生成二维码' }}
        </VButton>
      </VSpace>
    </VSpace>

    <!-- 手机号 -->
    <div v-else class="ncm-phone">
      <!-- @vue-ignore -->
      <FormKit
        :id="formId"
        v-model="phoneForm"
        type="form"
        :actions="false"
        :disabled="submitting"
        :config="{ validationVisibility: 'submit' }"
        @submit="phoneLogin"
      >
        <FormKit
          name="countrycode"
          label="国家/地区代码"
          type="text"
          :validation="[['required'], ['matches', CTCODE_REGEX]]"
          :validation-messages="{ matches: '只能填写 1~4 位数字，例如 86' }"
        />
        <FormKit
          name="phone"
          label="手机号"
          type="text"
          inputmode="tel"
          autocomplete="tel-national"
          :validation="[['required'], ['matches', PHONE_REGEX]]"
          :validation-messages="{ matches: '请输入正确的手机号' }"
        />
        <FormKit
          v-if="mode === 'captcha'"
          name="captcha"
          label="短信验证码"
          type="text"
          inputmode="numeric"
          autocomplete="one-time-code"
          :validation="[['required'], ['matches', CAPTCHA_REGEX]]"
          :validation-messages="{ matches: '请输入短信里的数字验证码' }"
        />
        <FormKit
          v-else
          name="password"
          label="网易云密码"
          type="password"
          validation="required"
          help="新设备用密码登录时，网易云经常会要求安全验证，失败时请改用扫码或验证码"
        />
      </FormKit>
      <VSpace class="ncm-phone__actions flex-wrap">
        <VButton
          v-if="mode === 'captcha'"
          :disabled="countdown > 0 || submitting"
          :loading="sending"
          @click="sendCaptcha"
        >
          {{ countdown > 0 ? `${countdown} 秒后重发` : '发送验证码' }}
        </VButton>
        <VButton type="secondary" :loading="submitting" @click="submitForm(formId)">
          登录并绑定
        </VButton>
      </VSpace>
    </div>
  </VSpace>
  <NcmConfirmDialog v-if="confirmingUnbind" :action="unbindConfirmation" @close="closeUnbind" />
</template>

<style scoped>
.ncm-account {
  display: grid;
  gap: .75rem;
}
.ncm-account__identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: .75rem;
}
.ncm-account__identity > :first-child {
  flex: none;
}
.ncm-account__info {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #f3f4f6;
  border-radius: 4px;
}
.ncm-account__name {
  min-width: 0;
  flex-wrap: wrap;
}
.ncm-account__nickname {
  min-width: 0;
  overflow-wrap: anywhere;
  font-weight: 600;
}
.ncm-account__manage {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  padding: 0 .25rem;
  font-size: .875rem;
  text-decoration: underline;
  text-underline-offset: .25rem;
}
@media (max-width: 640px), (pointer: coarse) {
  .ncm-account__info :deep(.description-item-wrapper) {
    display: grid;
    grid-template-columns: 5rem minmax(0, 1fr);
    gap: .5rem;
    padding: .625rem .75rem;
  }
  .ncm-account__info :deep(.description-item__label),
  .ncm-account__info :deep(.description-item__content) {
    width: auto;
    min-width: 0;
    margin: 0;
    padding: 0;
    overflow-wrap: anywhere;
  }
  .ncm-account__manage,
  .ncm-account__actions :deep(button),
  .ncm-phone__actions :deep(button),
  .ncm-binding__action {
    min-height: 44px;
  }
}
.ncm-qr {
  width: 220px;
  max-width: 100%;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
}
.ncm-qr--expired {
  opacity: 0.25;
}
.ncm-phone {
  max-width: 28rem;
}
.ncm-phone__actions {
  margin-top: 12px;
}
</style>
