export interface SystemPalette {
  fg: string
  bg: string
  accent: string
  /** optional secondary accent used by highlights */
  highlight?: string
}

export const DEFAULT_SYSTEM_PALETTE: SystemPalette = {
  fg: '#1F2329',
  bg: '#FFFFFF',
  accent: '#0B57D0',
  highlight: '#1A73E8'
}

function validColor(value: unknown, fallback: string): string {
  const color = String(value ?? '').trim()
  return /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/i.test(color) ? color : fallback
}

/** 将全局浅色极简主题令牌应用到根节点，兼容个人主页保存的自定义三色主题。 */
export function applySystemPalette(palette: SystemPalette = DEFAULT_SYSTEM_PALETTE) {
  const root = document.documentElement
  const safePalette = {
    fg: validColor(palette.fg, DEFAULT_SYSTEM_PALETTE.fg),
    bg: validColor(palette.bg, DEFAULT_SYSTEM_PALETTE.bg),
    accent: validColor(palette.accent, DEFAULT_SYSTEM_PALETTE.accent),
    highlight: validColor(palette.highlight, DEFAULT_SYSTEM_PALETTE.highlight || '#1A73E8')
  }
  root.style.setProperty('--ink', safePalette.fg)
  root.style.setProperty('--paper', safePalette.bg)
  root.style.setProperty('--forest', safePalette.accent)
  root.style.setProperty('--forest-deep', 'color-mix(in srgb, var(--forest) 82%, #000)')
  root.style.setProperty('--roam', 'color-mix(in srgb, var(--forest) 76%, #fff)')
  root.style.setProperty('--roam-soft', 'color-mix(in srgb, var(--forest) 10%, #fff)')
  root.style.setProperty('--sunset', safePalette.highlight)
  root.style.setProperty('--sunset-soft', 'color-mix(in srgb, var(--sunset) 10%, #fff)')
  root.style.setProperty('--card', '#FFFFFF')
  root.style.setProperty('--wash', '#F0F4F9')
  root.style.setProperty('--ink-2', '#5F6368')
  root.style.setProperty('--ink-3', '#8C959F')
  root.style.setProperty('--line', '#E2E7EE')
  root.style.setProperty('--shadow-soft', '0 1px 3px rgba(15, 23, 42, 0.05), 0 4px 12px rgba(15, 23, 42, 0.03)')
  root.style.setProperty('--shadow-lift', '0 8px 24px rgba(15, 23, 42, 0.08)')
  root.style.setProperty('--notes-bg', safePalette.bg)
  root.style.setProperty('--notes-fg', safePalette.fg)
  root.style.setProperty('--notes-accent', safePalette.accent)
  root.style.setProperty('--notes-line', '#E2E7EE')
  root.style.setProperty('--notes-wash', '#F0F4F9')

  // Element Plus 组件同步使用统一极简浅色体系
  root.style.setProperty('--el-color-primary', safePalette.accent)
  root.style.setProperty('--el-color-primary-light-3', 'color-mix(in srgb, var(--forest) 70%, #fff)')
  root.style.setProperty('--el-color-primary-light-5', 'color-mix(in srgb, var(--forest) 50%, #fff)')
  root.style.setProperty('--el-color-primary-light-7', 'color-mix(in srgb, var(--forest) 30%, #fff)')
  root.style.setProperty('--el-color-primary-light-9', 'color-mix(in srgb, var(--forest) 10%, #fff)')
  root.style.setProperty('--el-color-primary-dark-2', 'var(--forest-deep)')
  root.style.setProperty('--el-bg-color-page', '#F7F9FC')
  root.style.setProperty('--el-bg-color', '#FFFFFF')
  root.style.setProperty('--el-bg-color-overlay', '#FFFFFF')
  root.style.setProperty('--el-fill-color-light', '#F0F4F9')
  root.style.setProperty('--el-border-color', '#DADCE0')
  root.style.setProperty('--el-border-color-light', '#E8ECF2')
  root.style.setProperty('--el-text-color-primary', '#1F2329')
  root.style.setProperty('--el-text-color-regular', '#5F6368')
  root.style.setProperty('--el-text-color-secondary', '#8C959F')
}

export function parseSystemPalette(value: unknown): SystemPalette {
  try {
    const saved = typeof value === 'string' ? JSON.parse(value) : value
    if (saved && typeof saved === 'object') {
      const p = saved as Partial<SystemPalette>
      return {
        fg: validColor(p.fg, DEFAULT_SYSTEM_PALETTE.fg),
        bg: validColor(p.bg, DEFAULT_SYSTEM_PALETTE.bg),
        accent: validColor(p.accent, DEFAULT_SYSTEM_PALETTE.accent),
        highlight: validColor(p.highlight, DEFAULT_SYSTEM_PALETTE.highlight || '#1A73E8')
      }
    }
  } catch { /* 使用默认主题 */ }
  return { ...DEFAULT_SYSTEM_PALETTE }
}
