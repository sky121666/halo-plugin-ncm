import { axiosInstance } from '@halo-dev/api-client'
import {
  ApiNcmHaloRunV1alpha1NcmApi,
  ConsoleApiNcmHaloRunV1alpha1ApiAccessSettingsApi,
  ConsoleApiNcmHaloRunV1alpha1DebugApi,
  ConsoleApiNcmHaloRunV1alpha1DeviceApi,
  ConsoleApiNcmHaloRunV1alpha1FeaturedPlaylistApi,
  ConsoleApiNcmHaloRunV1alpha1FrontstageSettingsApi,
  ConsoleApiNcmHaloRunV1alpha1GuestStatusApi,
  ConsoleApiNcmHaloRunV1alpha1SiteAccountApi,
  UcApiNcmHaloRunV1alpha1BindingApi,
  type NcmAccountView,
  type NcmApiMeta,
  type NcmCellphoneLoginRequest,
  type NcmLoginResult,
  type NcmQrKey,
} from './generated'

/*
 * ./generated 由 `./gradlew generateApiClient` 根据后端的 OpenAPI 描述生成，请勿手动修改。
 * 这里只创建复用 Halo axiosInstance（自带登录状态和统一错误处理）的客户端实例，并做少量适配。
 */

export const ncmApiClient = {
  ncm: new ApiNcmHaloRunV1alpha1NcmApi(undefined, '', axiosInstance),
}

export const ncmConsoleApiClient = {
  siteAccount: new ConsoleApiNcmHaloRunV1alpha1SiteAccountApi(undefined, '', axiosInstance),
  guestStatus: new ConsoleApiNcmHaloRunV1alpha1GuestStatusApi(undefined, '', axiosInstance),
  device: new ConsoleApiNcmHaloRunV1alpha1DeviceApi(undefined, '', axiosInstance),
  featuredPlaylist: new ConsoleApiNcmHaloRunV1alpha1FeaturedPlaylistApi(
    undefined,
    '',
    axiosInstance,
  ),
  debug: new ConsoleApiNcmHaloRunV1alpha1DebugApi(undefined, '', axiosInstance),
  apiAccessSettings: new ConsoleApiNcmHaloRunV1alpha1ApiAccessSettingsApi(
    undefined,
    '',
    axiosInstance,
  ),
  frontstageSettings: new ConsoleApiNcmHaloRunV1alpha1FrontstageSettingsApi(
    undefined,
    '',
    axiosInstance,
  ),
}

export const ncmUcApiClient = {
  binding: new UcApiNcmHaloRunV1alpha1BindingApi(undefined, '', axiosInstance),
}

/** 访客可直接访问的公开接口前缀（用于在调试台里拼出可复制的地址）。 */
export const PUBLIC_API_BASE = '/apis/api.ncm.halo.run/v1alpha1'

export type Tier = NcmApiMeta['tier']

export const TIER_LABEL: Record<Tier, string> = {
  public: '公开',
  personal: '个人',
  write: '写操作',
  auth: '登录',
  admin: '工具',
}

/** 站长账号（后台）和个人账号（个人中心）结构相同，AccountBinding 组件只依赖这个形状。 */
export interface BindingApi {
  status: () => Promise<NcmAccountView>
  qrKey: () => Promise<NcmQrKey>
  qrCheck: (key: string) => Promise<NcmLoginResult>
  qrConfirm: (key: string) => Promise<NcmLoginResult>
  captcha: (phone: string, ctcode: string) => Promise<NcmLoginResult>
  cellphone: (body: NcmCellphoneLoginRequest) => Promise<NcmLoginResult>
  unbind: () => Promise<unknown>
}

export const NCM_REQUEST_HEADER = 'X-NCM-Request'
export const ncmWrite = { xNCMRequest: '1' }

const site = ncmConsoleApiClient.siteAccount
export const siteAccountApi: BindingApi = {
  status: () => site.getNcmSiteAccount().then((r) => r.data),
  qrKey: () => site.createNcmSiteAccountQrKey(ncmWrite).then((r) => r.data),
  qrCheck: (key) => site.checkNcmSiteAccountQr({ key }).then((r) => r.data),
  qrConfirm: (key) =>
    site.confirmNcmSiteAccountQr({ ...ncmWrite, ncmQrConfirmRequest: { key } }).then((r) => r.data),
  captcha: (phone, ctcode) =>
    site
      .sendNcmSiteAccountCaptcha({ ...ncmWrite, ncmCaptchaRequest: { phone, ctcode } })
      .then((r) => r.data),
  cellphone: (body) =>
    site
      .bindNcmSiteAccountByCellphone({ ...ncmWrite, ncmCellphoneLoginRequest: body })
      .then((r) => r.data),
  unbind: () => site.deleteNcmSiteAccount(ncmWrite),
}

const mine = ncmUcApiClient.binding
export const myBindingApi: BindingApi = {
  status: () => mine.getMyNcmBinding().then((r) => r.data),
  qrKey: () => mine.createMyNcmBindingQrKey(ncmWrite).then((r) => r.data),
  qrCheck: (key) => mine.checkMyNcmBindingQr({ key }).then((r) => r.data),
  qrConfirm: (key) =>
    mine.confirmMyNcmBindingQr({ ...ncmWrite, ncmQrConfirmRequest: { key } }).then((r) => r.data),
  captcha: (phone, ctcode) =>
    mine
      .sendMyNcmBindingCaptcha({ ...ncmWrite, ncmCaptchaRequest: { phone, ctcode } })
      .then((r) => r.data),
  cellphone: (body) =>
    mine
      .bindMyNcmAccountByCellphone({ ...ncmWrite, ncmCellphoneLoginRequest: body })
      .then((r) => r.data),
  unbind: () => mine.deleteMyNcmBinding(ncmWrite),
}

export interface CallResult {
  status: number
  ms: number
  data: unknown
}

/** 通过后台权限校验后，使用游客身份执行只读调用。 */
export async function callApi(name: string, params: Record<string, string>): Promise<CallResult> {
  const start = performance.now()
  try {
    const res = await ncmConsoleApiClient.debug.debugCallNcmApi({ name }, { params })
    return { status: res.status, ms: Math.round(performance.now() - start), data: res.data }
  } catch (e: unknown) {
    const err = e as { response?: { status: number; data: unknown }; message?: string }
    return {
      status: err.response?.status ?? 0,
      ms: Math.round(performance.now() - start),
      data: err.response?.data ?? { message: err.message ?? String(e) },
    }
  }
}
