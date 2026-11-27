<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useRightPanelStore } from '@/stores/rightPanel'
import { useUserStore } from '@/stores/user'
import roamlySymbol from '@/assets/brand/logo-app-icon.png'

const router = useRouter()
const route = useRoute()
const rightPanel = useRightPanelStore()
const userStore = useUserStore()

const collapsed = ref(localStorage.getItem('roamly-sidebar-collapsed') === '1')
watch(collapsed, (val) => {
  localStorage.setItem('roamly-sidebar-collapsed', val ? '1' : '0')
})

function toggleSidebar() {
  collapsed.value = !collapsed.value
}

interface NavItem {
  path: string
  label: string
  icon: string
  description: string
}

// 线性图标（内联 SVG，描边跟随 currentColor）
const icons: Record<string, string> = {
  compass: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polygon points="3 11 22 2 13 21 11 13 3 11"/></svg>`,
  star: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>`,
  map: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polygon points="1 6 8 3 16 6 23 3 23 18 16 21 8 18 1 21 1 6"/><line x1="8" y1="3" x2="8" y2="18"/><line x1="16" y1="6" x2="16" y2="21"/></svg>`,
  flag: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z"/><line x1="4" y1="22" x2="4" y2="15"/></svg>`,
  chat: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>`,
  note: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>`,
  plus: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>`
}

const navMain: NavItem[] = [
  { path: '/chat', label: 'Agent 旅行规划', icon: 'chat', description: '用表单或对话 Agent 规划个性化旅行行程。' },
  { path: '/explore', label: '发现灵感', icon: 'compass', description: '浏览全球旅行灵感，发现新目的地与热门路线。' },
  { path: '/inspirations', label: '灵感目的地', icon: 'star', description: '收藏与管理你心仪的旅行目的地卡片。' },
  { path: '/notes', label: '旅行笔记', icon: 'note', description: '类飞书的 Markdown 在线笔记编辑器，支持导入、预览与主题定制。' },
  { path: '/journeys', label: '我的旅程', icon: 'map', description: '查看和管理你创建的旅行行程记录。' }
]

const navMore: NavItem[] = [
  { path: '/journey-map', label: '足迹地图', icon: 'flag', description: '在地图上查看你去过的地方，留下足迹标记。' },
]

function isActive(item: NavItem) {
  if (item.path === '/') return route.path === '/chat'
  return route.path.startsWith(item.path)
}

const primaryNav = new Set(['/notes', '/profile'])

const viewKeyMap: Record<string, string> = {
  '/explore': 'explore',
  '/chat': 'planner',
  '/inspirations': 'inspirations',
  '/journeys': 'journeys',
  '/journey-map': 'map',
}

function navPath(item: NavItem) {
  return item.path
}

function navigate(item: NavItem) {
  const path = navPath(item)
  const viewKey = viewKeyMap[path]

  if (rightPanel.show && !primaryNav.has(item.path) && viewKey) {
    rightPanel.openView(viewKey, item.label)
    return
  }
  router.push(path)
}

const avatarStyle = computed(() => {
  if (userStore.avatar) {
    return { backgroundImage: `url(${userStore.avatar})`, backgroundSize: 'cover', backgroundPosition: 'center' }
  }
  return {}
})

const avatarFallback = computed(() => (userStore.nickname || '旅人').charAt(0))
</script>

<template>
  <aside class="sidebar" :class="{ collapsed }">
    <div class="sidebar-top">
      <a class="brand" @click="router.push('/')">
        <img class="brand-mark" :src="roamlySymbol" alt="Roamly" />
        <span class="brand-name">Roamly</span>
      </a>
      <button
        type="button"
        class="collapse-toggle"
        :title="collapsed ? '展开侧边栏' : '收起侧边栏'"
        @click="toggleSidebar"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="4" width="18" height="16" rx="3" />
          <line x1="9" y1="4" x2="9" y2="20" />
        </svg>
      </button>
    </div>

    <div class="sidebar-scroll">
      <nav class="menu">
        <a
          v-for="item in navMain"
          :key="item.path"
          class="menu-item"
          :class="{ active: isActive(item) }"
          :title="collapsed ? item.label : undefined"
          @click="navigate(item)"
        >
          <span class="ico" :aria-label="item.label" v-html="icons[item.icon]"></span>
          <span class="label">{{ item.label }}</span>
        </a>
      </nav>

      <div class="section-block">
        <p class="group-label">探索与记录</p>
        <nav class="menu">
          <a
            v-for="item in navMore"
            :key="item.path"
            class="menu-item"
            :class="{ active: isActive(item) }"
            :title="collapsed ? item.label : undefined"
            @click="navigate(item)"
          >
            <span class="ico" :aria-label="item.label" v-html="icons[item.icon]"></span>
            <span class="label">{{ item.label }}</span>
          </a>
          <a
            class="menu-item quick-create"
            :title="collapsed ? '新建旅程' : undefined"
            @click="router.push('/journeys')"
          >
            <span class="ico" aria-label="新建旅程" v-html="icons.plus"></span>
            <span class="label">新建旅程</span>
          </a>
        </nav>
      </div>
    </div>

    <div class="side-foot">
      <a class="user" :title="collapsed ? (userStore.nickname || '个人主页') : undefined" @click="router.push('/profile')">
        <span class="user-avatar" :style="avatarStyle">
          <span v-if="!userStore.avatar" class="fallback">{{ avatarFallback }}</span>
        </span>
        <span class="user-meta">
          <b>{{ userStore.nickname || '旅人' }}</b>
          <small>个人主页</small>
        </span>
      </a>
    </div>
  </aside>
</template>

<style scoped lang="scss">
.sidebar {
  width: 224px;
  flex-shrink: 0;
  height: calc(100vh - 20px);
  margin: 10px 0 10px 10px;
  border-radius: 18px;
  position: relative;
  z-index: 10000;
  display: flex;
  flex-direction: column;
  background: #FFFFFF;
  padding: 14px 10px 12px;
  border: 1px solid #E2E7EE;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04), 0 6px 18px rgba(15, 23, 42, 0.03);
  transition: width 0.22s cubic-bezier(.22, 1, .36, 1), padding 0.22s ease;
  overflow: visible;

  &.collapsed {
    width: 62px;
    padding: 14px 8px 12px;

    .sidebar-top {
      flex-direction: column;
      gap: 10px;
      padding: 0 0 12px;
    }

    .brand-name,
    .group-label,
    .menu-item .label,
    .user-meta {
      display: none;
    }

    .menu-item {
      justify-content: center;
      padding: 0;
      width: 44px;
      height: 40px;
      margin: 0 auto;
    }

    .section-block {
      margin-top: 8px;
      padding-top: 8px;
      border-top: 1px solid #EEF1F5;
    }

    .user {
      justify-content: center;
      padding: 4px;
    }
  }
}

.sidebar-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 2px 6px 14px;
  flex-shrink: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  cursor: pointer;
  user-select: none;
}

.brand-mark {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: block;
  flex-shrink: 0;
}

.brand-name {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #1F2329;
  white-space: nowrap;
}

.collapse-toggle {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #5F6368;
  display: grid;
  place-items: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: background 0.15s ease, color 0.15s ease;

  svg {
    width: 17px;
    height: 17px;
  }

  &:hover {
    background: #F0F4F9;
    color: #1F2329;
  }
}

.sidebar-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 1px;
}

.menu {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.section-block {
  margin-top: 18px;
}

.group-label {
  padding: 0 12px 7px;
  font-size: 11.5px;
  font-weight: 600;
  color: #8C959F;
  letter-spacing: 0.02em;
  user-select: none;
}

.menu-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  height: 38px;
  padding: 0 12px;
  border-radius: 999px;
  color: #3C4043;
  font-size: 13.5px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background 0.15s ease, color 0.15s ease;
  user-select: none;

  &:hover {
    background: #F0F4F9;
    color: #1F2329;
  }

  &.active {
    background: #E8F0FE;
    color: #0B57D0;
    font-weight: 600;
  }

  &.quick-create {
    color: #5F6368;
    &:hover {
      color: #0B57D0;
      background: #EDF3FE;
    }
  }
}

.menu-item .label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ico {
  position: relative;
  z-index: 2;
  width: 19px;
  height: 19px;
  display: grid;
  place-items: center;
  flex-shrink: 0;

  :deep(svg) {
    width: 18px;
    height: 18px;
  }
}

/* 仅在收起态显示右侧悬浮文字提示 */
.sidebar.collapsed .ico::after {
  content: attr(aria-label);
  position: absolute;
  left: calc(100% + 14px);
  top: 50%;
  transform: translateY(-50%);
  white-space: nowrap;
  z-index: 99999;
  padding: 6px 11px;
  border-radius: 8px;
  background: #1F2329;
  color: #FFFFFF;
  font-size: 12px;
  font-weight: 500;
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.18);
  pointer-events: none;
  opacity: 0;
  visibility: hidden;
  transition: opacity .14s ease, visibility .14s ease;
}

.sidebar.collapsed .menu-item:hover .ico::after {
  opacity: 1 !important;
  visibility: visible !important;
}

.side-foot {
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid #EEF1F5;
  flex-shrink: 0;
}

.user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 8px;
  border-radius: 12px;
  background: transparent;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover {
    background: #F0F4F9;
  }
}

.user-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #E8F0FE;
  color: #0B57D0;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 14px;
  overflow: hidden;
  flex-shrink: 0;

  .fallback { line-height: 1; }
}

.user-meta {
  flex: 1;
  min-width: 0;

  b {
    display: block;
    color: #1F2329;
    font-size: 13px;
    font-weight: 600;
    line-height: 1.3;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  small {
    display: block;
    margin-top: 1px;
    color: #8C959F;
    font-size: 11px;
    line-height: 1.2;
  }
}

@media (max-width: 860px) {
  .sidebar {
    width: 60px;
    padding: 12px 6px;
  }
  .brand-name, .group-label, .menu-item .label, .user-meta {
    display: none;
  }
}
</style>
