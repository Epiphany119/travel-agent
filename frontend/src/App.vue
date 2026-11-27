<template>
  <router-view v-if="isAuthPage" />
  <div v-else class="app-layout">
    <CommandPalette />
    <AppSidebar />
    <div class="app-right-column">
      <div class="app-header-row">
        <AppHeader @toggle-panel="onPanelToggle" />
      </div>
      <div class="app-main">
        <div class="app-body">
          <!-- 中间工作区 -->
          <div class="workspace">
            <!-- 底层路由页面保持存活（如 NotesView） -->
            <router-view v-slot="{ Component, route: r }">
              <component :is="Component" :key="r.fullPath" class="workspace-route-view" />
            </router-view>
          </div>

          <!-- 右侧查看栏 -->
          <GlobalRightPanel />

          <!-- 独立 sibling 拖拽手柄 -->
          <div
            v-if="rightPanel.show"
            class="app-resize-handle"
            :style="{'--rpw': rightPanel.width + 'px'}"
            @mousedown="onRightPanelResizeStart"
          ></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'
import AppHeader from '@/components/AppHeader.vue'
import GlobalRightPanel from '@/components/GlobalRightPanel.vue'
import CommandPalette from '@/components/CommandPalette.vue'
import { useContentTabsStore } from '@/stores/contentTabs'
import { useRightPanelStore } from '@/stores/rightPanel'
import { getPreferences } from '@/api/user'
import { applySystemPalette as applyPalette, parseSystemPalette, DEFAULT_SYSTEM_PALETTE } from '@/utils/theme'

const route = useRoute()
const router = useRouter()
const isAuthPage = computed(() => route.path === '/auth' || route.path.startsWith('/auth/'))
const rightPanel = useRightPanelStore()
const contentTabs = useContentTabsStore()

/* 替换式预览联动：点卡片 open → activeId 变化 → 路由切到 /card-detail（不覆盖） */
watch(
  () => contentTabs.activeId,
  (id) => {
    if (id && route.path !== '/card-detail') {
      router.push('/card-detail')
    }
  }
)

/* 全部标签关闭后，若停留在预览页则返回来源页 */
watch(
  () => contentTabs.tabs.length,
  (n) => {
    if (n === 0 && route.path === '/card-detail') {
      router.push(contentTabs.lastRoute || '/notes')
    }
  }
)

/* 记录来源路由：离开预览页时记录位置，供关闭预览后返回 */
watch(
  () => route.fullPath,
  (fp) => {
    if (fp && route.path !== '/card-detail' && route.path !== '/auth') {
      contentTabs.lastRoute = fp
    }
  },
  { immediate: true }
)

/**
 * 右侧面板只显示低优先级辅助 tab（由侧边栏 navigate 送入右栏），
 * 不按中间路由联动，避免出现「中间和右边显示同一份内容」。
 * 笔记界面自带面板，无需额外辅助。
 */
const exploreAssist = { key: 'explore', label: '发现灵感' }

/**
 * 三栏模式下，低优先级页面只在右侧辅助面板停留，中间主区域固定为高优先级页
 * （笔记 /notes、个人信息 /profile、预览 /card-detail）。
 * 打开右栏时若中间停在低优先级路由，先把它送回 /notes，再在右栏显示该内容。
 */
const defaultAssist: Record<string, { key: string; label: string }> = {
  '/explore': exploreAssist,
  '/inspirations': { key: 'inspirations', label: '灵感目的地' },
  '/journeys': { key: 'journeys', label: '我的旅程' },
  '/chat': { key: 'planner', label: 'Agent 旅行规划' },
  '/journey-map': { key: 'map', label: '足迹地图' },
}

let paletteLoaded = false

/** 右上角固定开关：隐藏 -> 打开右栏（按当前页面给对应辅助，低优先级页面移入右栏）；显示 -> 关闭 */
function onPanelToggle() {
  if (rightPanel.show) {
    rightPanel.close()
    return
  }
  const assist = defaultAssist[route.path]
  if (assist) {
    rightPanel.openView(assist.key, assist.label)
    // 中间不是高优先级页时，送回 /notes，避免中间和右栏重复
    if (route.path !== '/notes') {
      router.push('/notes')
    }
    return
  }
  // /profile、/card-detail 等：保持中间，右栏给一个通用辅助
  if (!rightPanel.type || rightPanel.type === 'empty' || !rightPanel.viewKey) {
    rightPanel.openView(exploreAssist.key, exploreAssist.label)
  } else {
    rightPanel.show = true
  }
}

function onRightPanelResizeStart(e: MouseEvent) {
  e.preventDefault()
  e.stopPropagation()
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'

  const handleMove = (ev: MouseEvent) => {
    const panel = document.querySelector('.global-right-panel') as HTMLElement | null
    if (!panel) return
    // panel 右边界减去鼠标 X = 新宽度（鼠标越往左，面板越宽）
    const right = panel.getBoundingClientRect().right
    const w = Math.min(860, Math.max(260, right - ev.clientX))
    rightPanel.setWidth(w)
  }
  const handleUp = () => {
    document.removeEventListener('mousemove', handleMove)
    document.removeEventListener('mouseup', handleUp)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
  }
  document.addEventListener('mousemove', handleMove)
  document.addEventListener('mouseup', handleUp)
}
async function applySystemPalette() {
  // 主题只在应用首次进入和重新登录后加载一次，避免每次切换页面都产生额外请求。
  if (paletteLoaded && route.path !== '/auth') return
  let palette = DEFAULT_SYSTEM_PALETTE
  let loadedFromServer = false
  try {
    const result = await getPreferences()
    const p = result.data || {}
    palette = parseSystemPalette(p.systemThemeJson)
    loadedFromServer = true
  } catch { /* keep defaults when the profile cannot be loaded */ }
  applyPalette(palette)
  paletteLoaded = loadedFromServer && route.path !== '/auth'
}

watch(() => route.path, () => {
  if (route.path === '/auth') paletteLoaded = false
  void applySystemPalette()
}, { immediate: true })
</script>

<style>
@import url('https://fonts.googleapis.com/css2?family=DM+Serif+Display:ital@0;1&family=Manrope:wght@400;500;600;700;800&family=Noto+Sans+SC:wght@400;500;600;700;800&display=swap');

/* ─── Roamly Design Tokens ─────────────────────────────────────── */
:root {
  --forest: #164E42;        /* Primary · Forest Green   */
  --forest-deep: #0E382E;   /* Primary deep             */
  --roam: #4F8F78;          /* Secondary · Roam Green   */
  --roam-soft: #E9F1EC;     /* Roam 8% surface          */
  --sunset: #F27A4F;        /* Accent · Sunset Orange   */
  --sunset-soft: #FDEEE6;   /* Sunset surface           */
  --paper: #F7F3EA;         /* Background · Paper       */
  --card: #FFFDF8;          /* Card surface             */
  --wash: #F2EDE1;          /* Sunken wash              */
  --ink: #1D2B27;           /* Primary text             */
  --ink-2: #5C6B65;         /* Secondary text           */
  --ink-3: #8C9993;         /* Tertiary text            */
  --line: #E7E0D2;          /* Hairline border          */
  --shadow-soft: 0 2px 12px rgba(22, 78, 66, 0.06);
  --shadow-lift: 0 12px 30px rgba(22, 78, 66, 0.10);
  --radius-card: 24px;
}

* { margin: 0; padding: 0; box-sizing: border-box; }

html, body, #app { height: 100%; }

body {
  background: var(--paper);
  color: var(--ink);
  font-family: Manrope, "Noto Sans SC", -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  -webkit-font-smoothing: antialiased;
}

/* ─── 顶层两列布局：AppSidebar | app-main ─────────────────────────
   AppHeader + app-body(workspace + GlobalRightPanel) 都在 app-main 里。
   Header 是一整条横跨 app-main 剩余宽度的绿色栏，不再被右侧面板切断。
*/
.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  min-height: 0;
  overflow: visible;
  padding: 0;
  background: var(--paper, #F7F3EA);
}

/* 顶部深绿色导航栏：横跨整个系统宽度（左栏 + 右栏） */
.app-header-row {
  flex-shrink: 0;
  display: flex;
  align-items: stretch;
  padding: 0 8px;                    /* 和下方主体的 8px 外边距对齐 */
  padding-top: 8px;                   /* 顶上加 8px，和 AppSidebar 顶部圆角对齐 */
  box-sizing: border-box;
  background: transparent;
}

.app-header-left-spacer {
  flex-shrink: 0;
  width: 56px;                       /* 和 AppSidebar 同宽 */
  height: 48px;
  margin-right: 8px;                 /* 和 .app-main-row 的 gap 对齐 */
}

/* AppHeader 本身在 AppHeader.vue 里 height:48px */
.app-header-row > .app-header {
  flex: 1;
  min-width: 0;
  height: 48px;
  background: var(--forest);
  color: #fff;
  flex-shrink: 0;
  border-radius: 18px;               /* 整个顶栏独立圆角（只有上半部分能看到完整圆角） */
  margin-right: 8px;                 /* 和右栏右边 8px 外边距对齐 */
}

/* 底部主体：左栏 + 中间大卡片 */
.app-main-row {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
  padding: 0 8px 8px 8px;   /* 右下左 8px，顶部 0 交给 header row */
  gap: 0;
  overflow: hidden;
  align-items: stretch;
}

/* 顶层两列里的 app-main：撑满整个剩余宽度，右上、右下都留 8px 外边距 */
.app-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: visible;
  margin: 0 0 0 0;
  height: auto;                      /* 让 flex row 自己算高度 */
  align-self: stretch;
  background: var(--card, #FFFDF8);
  border: 1px solid var(--line, #E7E0D2);
  border-radius: 14px;
  overflow: hidden;
}

/* app-body：横向排布 workspace | GlobalRightPanel */
.app-body {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
  overflow: hidden;
  background: var(--wash, #F2EDE1);       /* 显式继承，防止 isolation/contain 切断 */
  gap: 8px;
  padding: 8px;
}

/* 中间工作区 */
.workspace {
  position: relative;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--card, #FFFDF8);       /* 显式继承主题 paper 色 */
  isolation: auto;
  border-radius: 14px;
  overflow: hidden;
}

/* 底层路由页面容器：让 NotesView / JourneyView 真正填满 workspace */
.workspace-route-view {
  position: relative;
  width: 100%;
  flex: 1;
  min-height: 0;
  overflow: auto;
  background: var(--card, #FFFDF8);
  border-radius: inherit;
}

/* ─── 独立 sibling 拖拽手柄 ──────────────────────────
   挂在 app-body 里、GlobalRightPanel 的旁边，绝对定位到面板左边缘。
   它是 workspace 和 right-panel 的 sibling，不嵌在 panel 里面，
   所以不受 GlobalRightPanel 的 overflow / isolation / z-index 影响。
   注意：本 style 是原生 CSS（无 lang="scss"），必须展开成纯 CSS 选择器，
   不能使用 & 嵌套语法。 */
.app-resize-handle {
  position: absolute;
  top: 0;           /* 视觉竖线从 app-body 顶部贯穿到底 */
  bottom: 0;
  width: 14px;      /* 加宽热区，面板窄时也好命中 */
  right: calc(var(--rpw, 360px) + 8px);
  transform: translateX(0);
  cursor: col-resize;
  z-index: 999999;  /* 压过 NotesView / 面板内任何高 z-index 元素 */
  background: transparent;
  pointer-events: auto;
}

/* 视觉竖线贯穿整个 app-body */
.app-resize-handle::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  transform: translateX(-45%);   /* 让竖线跨在 workspace 与右侧面板的缝隙正中间 */
  width: 2px;
  background: #d5d8dc;
  box-shadow: 1px 0 2px rgba(0,0,0,.04);
  transition: background .15s, width .15s, box-shadow .15s;
}

.app-resize-handle:hover::before {
  width: 3px;
  background: var(--forest, #164E42);
  box-shadow: 0 0 6px rgba(22,78,66,.25);
}

/* Notes page 自己管自己的满屏布局 */
.notes-app {
  border-radius: 0 !important;
  border: none !important;
  margin: 0 !important;
  height: 100% !important;
  min-height: 100%;
  width: 100%;
  position: relative;
}

/* Element Plus 主色对齐品牌色 */
.el-button--primary {
  --el-button-bg-color: var(--forest);
  --el-button-border-color: var(--forest);
  --el-button-hover-bg-color: var(--roam);
  --el-button-hover-border-color: var(--roam);
  --el-button-active-bg-color: var(--forest-deep);
  --el-button-active-border-color: var(--forest-deep);
}

/* 滚动条统一风格 */
::-webkit-scrollbar { width: 9px; height: 9px; }
::-webkit-scrollbar-thumb { background: #D8D1C0; border-radius: 8px; }
::-webkit-scrollbar-thumb:hover { background: #C4BCA8; }
::-webkit-scrollbar-track { background: transparent; }
</style>

<style>
/* ─── 现代极简浅色壳层（Google Material 3 / Gemini 风格） ────────────────
   保留用户保存的系统主题令牌（--paper / --forest / --sunset），
   同时用干净的浅色画布、细描边与轻量阴影替代厚重色块和强投影。 */
:root {
  --surface-canvas: #F5F7FA;
  --surface-elevated: #FFFFFF;
  --surface-pill: #F0F4F9;
  --surface-pill-hover: #E4ECF7;
  --border-hairline: #E2E7EE;
  --ink-primary: #1F2329;
  --ink-secondary: #5F6368;
  --ink-muted: #8C959F;
  --paper-deep: color-mix(in srgb, var(--paper) 92%, var(--ink) 8%);
  --surface-tint: color-mix(in srgb, var(--paper) 92%, var(--forest) 8%);
  --forest-wash: color-mix(in srgb, var(--forest) 6%, var(--paper));
  --sunset-wash: color-mix(in srgb, var(--sunset) 6%, var(--paper));
  --surface-raised: #FFFFFF;
}

body {
  background: var(--surface-canvas);
  color: var(--ink-primary);
}

.app-layout {
  flex-direction: row;
  background: var(--surface-canvas);
}

.app-right-column {
  flex: 1;
  min-width: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 10px;
  gap: 10px;
  overflow: hidden;
}

.app-header-row {
  padding: 0;
}

.app-header-row > .app-header {
  height: 50px;
  margin-right: 0;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(12px);
  color: var(--ink-primary);
  border: 1px solid var(--border-hairline);
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04), 0 4px 14px rgba(15, 23, 42, 0.03);
}

.app-main {
  margin-top: 0;
  background: var(--surface-elevated);
  border: 1px solid var(--border-hairline);
  border-radius: 18px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.03), 0 8px 24px rgba(15, 23, 42, 0.04);
}

.app-body {
  background: var(--surface-canvas);
  gap: 10px;
  padding: 10px;
}

.workspace {
  background: var(--surface-elevated);
  border: 1px solid var(--border-hairline);
  border-radius: 14px;
  box-shadow: none;
}

.workspace-route-view {
  background: transparent;
}

.app-resize-handle::before {
  background: var(--border-hairline);
}

::-webkit-scrollbar { width: 6px; height: 6px; }
::-webkit-scrollbar-thumb { background: rgba(15, 23, 42, 0.14); border-radius: 999px; }
::-webkit-scrollbar-thumb:hover { background: rgba(15, 23, 42, 0.28); }
::-webkit-scrollbar-track { background: transparent; }

@media (max-width: 860px) {
  .app-main { border-radius: 14px; }
  .app-body { padding: 6px; gap: 6px; }
}
</style>
