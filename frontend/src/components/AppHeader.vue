<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useContentTabsStore } from '@/stores/contentTabs'
import { useRightPanelStore } from '@/stores/rightPanel'
import { searchUsers } from '@/api/user'
import PanelOpenIcon from '@/assets/右边栏按钮-开.png'
import PanelCloseIcon from '@/assets/右边栏按钮-关.png'

const router = useRouter()
const keyword = ref('')
const userMatches = ref<any[]>([])
const searchLoading = ref(false)
const searchOpen = ref(false)
const searchRoot = ref<HTMLElement | null>(null)
const tabs = useContentTabsStore()
const rightPanel = useRightPanelStore()
const emit = defineEmits<{ (e: 'toggle-panel'): void }>()
let searchTimer: ReturnType<typeof setTimeout> | undefined
let searchRequestId = 0

function userId(user: any) {
  return String(user?.public_id || user?.publicId || user?.user_id || user?.userId || '').trim()
}

function userName(user: any) {
  return String(user?.nickname || user?.name || user?.username || userId(user) || '旅行者')
}

function userAvatar(value?: string) {
  const raw = String(value || '').trim()
  if (!raw) return ''
  if (/^(https?:|data:|blob:)/i.test(raw)) return raw
  const base = String(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
  return `${base}${raw.startsWith('/') ? raw : `/${raw}`}`
}

function invalidateSearch() {
  searchRequestId += 1
  userMatches.value = []
  searchLoading.value = false
}

async function loadUserMatches(value: string) {
  const requestId = ++searchRequestId
  searchLoading.value = true
  try {
    const result = await searchUsers(value)
    if (requestId === searchRequestId) userMatches.value = result.data || []
  } catch {
    if (requestId === searchRequestId) userMatches.value = []
  } finally {
    if (requestId === searchRequestId) searchLoading.value = false
  }
}

watch(keyword, (value) => {
  if (searchTimer) clearTimeout(searchTimer)
  const q = value.trim()
  searchOpen.value = Boolean(q)
  if (!q) {
    invalidateSearch()
    return
  }
  searchTimer = setTimeout(() => { void loadUserMatches(q) }, 180)
})

function openUser(user: any) {
  const id = userId(user)
  if (!id) return
  searchOpen.value = false
  keyword.value = ''
  router.push(`/users/${encodeURIComponent(id)}`)
}

function search() {
  const q = keyword.value.trim()
  searchOpen.value = false
  if (q) router.push({ path: '/users/search', query: { q } })
  else router.push('/users/search')
}

function handleSearchFocus() {
  searchOpen.value = Boolean(keyword.value.trim())
}

function handleOutsideClick(event: MouseEvent) {
  if (!searchRoot.value?.contains(event.target as Node)) searchOpen.value = false
}

onMounted(() => document.addEventListener('click', handleOutsideClick))
onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
  document.removeEventListener('click', handleOutsideClick)
})
</script>

<template>
  <header class="app-header">
    <div ref="searchRoot" class="search">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="11" cy="11" r="8" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
      </svg>
      <input
        v-model="keyword"
        placeholder="搜索用户、昵称或 ID…"
        aria-label="搜索用户、昵称或 ID"
        @focus="handleSearchFocus"
        @keyup.enter="search"
      />
      <div v-if="searchOpen && keyword.trim()" class="search-popover" @mousedown.prevent>
        <div class="search-popover-head">
          <span>旅行同好</span>
          <small>输入即匹配</small>
        </div>
        <div v-if="searchLoading" class="search-state">正在匹配用户…</div>
        <button
          v-for="user in userMatches"
          :key="userId(user)"
          type="button"
          class="search-user"
          @click="openUser(user)"
        >
          <span class="search-avatar">
            <img v-if="userAvatar(user.avatar || user.avatar_url || user.avatarUrl)" :src="userAvatar(user.avatar || user.avatar_url || user.avatarUrl)" alt="" />
            <span v-else>{{ userName(user).charAt(0) }}</span>
          </span>
          <span class="search-user-copy">
            <b>{{ userName(user) }}</b>
            <small>ID · {{ userId(user) }}</small>
          </span>
          <span class="search-user-arrow">↗</span>
        </button>
        <div v-if="!searchLoading && !userMatches.length" class="search-state">没有找到匹配用户</div>
        <button type="button" class="search-more" @click="search">查看全部用户 →</button>
      </div>
    </div>

    <!-- 嵌入式标签栏：仅在打开标签时展示底色，空态保持干净留白 -->
    <div class="inline-tabs" :class="{ 'has-tabs': tabs.tabs.length > 0 }">
      <div v-if="tabs.tabs.length" class="tabs-scroll">
        <button
          v-for="t in tabs.tabs"
          :key="t.id"
          class="tab-item"
          :class="{ active: tabs.activeId === t.id }"
          @click="tabs.activate(t.id)"
        >
          <span class="tab-label">{{ t.title }}</span>
          <span class="tab-close" title="关闭" @click.stop="tabs.close(t.id)">×</span>
        </button>
      </div>
      <button v-if="tabs.tabs.length > 1" class="tab-close-all" title="关闭全部" @click="tabs.closeAll()">✕</button>
    </div>

    <div class="actions">
      <!-- 右侧栏开关：固定在右上角同一位置，点一下开 / 再点一下关 -->
      <button
        class="icon-btn panel-toggle-btn"
        :class="{ active: rightPanel.show }"
        :title="rightPanel.show ? '收起右侧栏' : '打开右侧栏'"
        @click="emit('toggle-panel')"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="4" width="18" height="16" rx="3" />
          <line x1="15" y1="4" x2="15" y2="20" />
        </svg>
      </button>
      <button class="icon-btn" title="通知">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.73 21a2 2 0 0 1-3.46 0" />
        </svg>
        <i class="badge-dot"></i>
      </button>
      <button class="icon-btn" title="收藏" @click="router.push('/inspirations')">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
        </svg>
      </button>
      <button class="new-btn" @click="router.push('/journeys')">
        <span class="plus">＋</span> 新旅程
      </button>
    </div>
  </header>
</template>

<style scoped lang="scss">
.app-header {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  height: 50px;
  padding: 6px 14px;
  background: transparent;
  flex-shrink: 0;
}

/* 胶囊搜索框：Google Material 3 / Gemini 浅灰胶囊 */
.search {
  position: relative;
  flex: 0 1 320px;
  min-width: 180px;
  max-width: 360px;
  display: flex;
  align-items: center;
  gap: 9px;
  background: #F0F4F9;
  border: 1px solid transparent;
  border-radius: 999px;
  padding: 7px 14px;
  transition: border-color .16s ease, background .16s ease, box-shadow .16s ease;

  &:hover {
    background: #E8EFF7;
  }

  &:focus-within {
    background: #FFFFFF;
    border-color: #0B57D0;
    box-shadow: 0 0 0 3px rgba(11, 87, 208, 0.12);
  }

  svg {
    width: 15px;
    height: 15px;
    color: #5F6368;
    flex-shrink: 0;
  }

  input {
    width: 100%;
    min-width: 0;
    border: 0;
    background: transparent;
    outline: 0;
    color: #1F2329;
    font: 500 13px Manrope, "Noto Sans SC", sans-serif;

    &::placeholder { color: #747775; }
  }
}

.search-popover {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  width: min(360px, calc(100vw - 32px));
  padding: 8px;
  border: 1px solid #E2E7EE;
  border-radius: 16px;
  background: #FFFFFF;
  color: #1F2329;
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.12);
  z-index: 10020;
}

.search-popover-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 5px 8px 8px;
  color: #5F6368;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .04em;

  small { color: #8C959F; font-size: 10px; font-weight: 500; }
}

.search-user {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 8px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;

  &:hover { background: #F0F4F9; }
}

.search-avatar {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  overflow: hidden;
  border-radius: 50%;
  background: #E8F0FE;
  color: #0B57D0;
  font-size: 12px;
  font-weight: 800;

  img { width: 100%; height: 100%; object-fit: cover; }
}

.search-user-copy {
  min-width: 0;
  flex: 1;

  b, small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  b { font-size: 12px; }
  small { margin-top: 2px; color: #8C959F; font-size: 10px; }
}

.search-user-arrow { color: #8C959F; font-size: 15px; }
.search-state { padding: 16px 8px; color: #8C959F; font-size: 12px; text-align: center; }
.search-more { width: 100%; margin-top: 4px; padding: 8px; border: 0; border-top: 1px solid #EEF1F5; background: transparent; color: #0B57D0; font-size: 11px; font-weight: 700; text-align: left; cursor: pointer; }

/* 嵌入式标签栏：空态完全隐形，有标签时呈柔和浅灰胶囊轨 */
.inline-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1 1 auto;
  min-width: 40px;
  height: 34px;
  padding: 0 4px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 12px;
  overflow: hidden;
  transition: background .16s ease, border-color .16s ease;

  &.has-tabs {
    background: #F0F4F9;
    border-color: #E2E7EE;
    padding: 3px 6px;
  }
}

.tabs-scroll {
  display: flex;
  align-items: center;
  gap: 4px;
  overflow-x: auto;
  scrollbar-width: none;
  flex: 1;
  min-width: 0;

  &::-webkit-scrollbar { display: none; }
}

.tab-item {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 160px;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #5F6368;
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  height: 26px;
  cursor: pointer;
  white-space: nowrap;
  transition: color .15s, background .15s, box-shadow .15s;
  flex-shrink: 0;

  &:hover { color: #1F2329; background: rgba(15, 23, 42, 0.05); }

  &.active {
    background: #FFFFFF;
    color: #0B57D0;
    font-weight: 650;
    box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
  }
}

.tab-label {
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 112px;
}

.tab-close {
  font-size: 13px;
  line-height: 1;
  padding: 1px 3px;
  border-radius: 50%;
  opacity: .55;

  &:hover { opacity: 1; background: rgba(15, 23, 42, 0.08); }
}

.tab-close-all {
  border: 0;
  background: transparent;
  color: #5F6368;
  font-size: 11px;
  line-height: 1;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 6px;
  flex-shrink: 0;

  &:hover { color: #1F2329; background: rgba(15, 23, 42, 0.06); }
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

.new-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 0;
  background: #0B57D0;
  color: #FFFFFF;
  font-weight: 600;
  font-size: 13px;
  padding: 7px 16px;
  border-radius: 999px;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(11, 87, 208, 0.22);
  transition: transform 0.15s, box-shadow 0.15s, background 0.15s;

  &:hover {
    background: #0842A0;
    transform: translateY(-1px);
    box-shadow: 0 4px 12px rgba(11, 87, 208, 0.28);
  }

  .plus { font-size: 14px; line-height: 1; }
}

.icon-btn {
  position: relative;
  width: 34px;
  height: 34px;
  border-radius: 999px;
  border: 1px solid transparent;
  background: #F0F4F9;
  color: #444746;
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;

  svg { width: 16px; height: 16px; }

  img {
    width: 17px;
    height: 17px;
    display: block;
    pointer-events: none;
  }

  &:hover {
    background: #E4ECF7;
    color: #0B57D0;
  }
}

.panel-toggle-btn.active {
  background: #E8F0FE;
  color: #0B57D0;
  border-color: rgba(11, 87, 208, 0.22);
}

.badge-dot {
  position: absolute;
  top: 7px;
  right: 8px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #EA4335;
  box-shadow: 0 0 0 2px #F0F4F9;
}

@media (max-width: 640px) {
  .app-header { padding: 6px 10px; }
  .icon-btn { display: none; }
}
</style>
