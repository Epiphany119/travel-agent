/**
 * 计划费用的前端边界：null/非法值表示未知，只有真实的非负数字才可以展示为金额。
 * 不能用 `value || 0`，因为那会把“暂无数据”伪装成免费。
 */
export function normalizeOptionalCost(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const number = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(number) && number >= 0 ? Math.round(number) : null
}

export function formatCost(value: unknown): string {
  const cost = normalizeOptionalCost(value)
  return cost === null ? '费用待确认' : `¥${cost.toLocaleString()}`
}

const HOTEL_NAME_RE = /(酒店|宾馆|客栈|民宿|度假村|大饭店|青旅|住宿|返回酒店|回酒店)/i
const LUXURY_HOTEL_RE = /(国际|五星|豪华|度假|凯悦|希尔顿|万豪|香格里拉|洲际|威斯汀|瑞豪)/i
const BOUTIQUE_HOTEL_RE = /(精品|轻奢|亚朵|全季|桔子|美居|维也纳|花园)/i
const BUDGET_HOTEL_RE = /(快捷|青旅|如家|汉庭|7天|七天|格林豪泰|驿站)/i
const RETURN_HOTEL_RE = /(返回酒店|回酒店|酒店休息)/i

export function isHotelActivity(name?: string, type?: string): boolean {
  if (type === 'hotel' || type === 'lodging') return true
  return HOTEL_NAME_RE.test(String(name || ''))
}

/**
 * 为行程活动卡片格式化费用展示：
 * - 若有明确非零费用，直接展示 ¥XXX
 * - 若为酒店/住宿节点且费用缺失或为 0，根据所选酒店均价或城市基准 + 酒店档位关键词给出「预估 ¥XXX/晚」
 * - 若为餐饮节点且费用缺失，给出「餐饮预估 ¥XX/人」
 */
export function formatActivityCost(
  activity: { name?: string; type?: string; cost?: unknown },
  options?: { hotelNightlyRate?: number | null; mealPerPerson?: number | null }
): string {
  const cost = normalizeOptionalCost(activity?.cost)
  const name = String(activity?.name || '')
  const type = String(activity?.type || '')

  if (cost !== null && cost > 0) {
    return `¥${cost.toLocaleString()}`
  }

  if (isHotelActivity(name, type)) {
    const baseNight = options?.hotelNightlyRate && options.hotelNightlyRate > 0
      ? options.hotelNightlyRate
      : 380
    if (RETURN_HOTEL_RE.test(name)) {
      const est = Math.round(baseNight / 10) * 10
      return `含当晚住宿 · 预估 ¥${est.toLocaleString()}/晚`
    }
    let factor = 1.0
    if (LUXURY_HOTEL_RE.test(name)) factor = 1.35
    else if (BOUTIQUE_HOTEL_RE.test(name)) factor = 1.1
    else if (BUDGET_HOTEL_RE.test(name)) factor = 0.72
    const est = Math.max(150, Math.round((baseNight * factor) / 10) * 10)
    return `预估 ¥${est.toLocaleString()}/晚`
  }

  if (cost === 0) {
    return '¥0'
  }

  if (type === 'meal') {
    const mealEst = options?.mealPerPerson && options.mealPerPerson > 0
      ? Math.round(options.mealPerPerson)
      : 75
    return `餐饮预估 ¥${mealEst}/人`
  }

  return '费用待确认'
}
