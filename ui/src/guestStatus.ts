import { utils } from '@halo-dev/ui-shared'

export const guestStatusLabels = {
  registered: { state: 'success', text: '已注册' },
  pending: { state: 'warning', text: '待初始化' },
  failed: { state: 'error', text: '初始化异常' },
} as const

export function formatGuestAttempt(value?: string | null) {
  if (!value || value === '1970-01-01T00:00:00Z') return '暂无'
  return utils.date.format(value) || '暂无'
}
