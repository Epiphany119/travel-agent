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
