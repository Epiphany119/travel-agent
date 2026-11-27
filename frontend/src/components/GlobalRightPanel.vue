<script setup lang="ts">
import { ref, computed, defineAsyncComponent, watch, type Component } from 'vue'
import { useRightPanelStore } from '@/stores/rightPanel'

const panel = useRightPanelStore()
const linkLoading = ref(false)

watch(
  () => [panel.show, panel.type, panel.linkData?.url] as const,
  ([show, type, url]) => {
    linkLoading.value = Boolean(show && type === 'link' && url)
  },
  { immediate: true }
)

const viewComponents: Record<string, Component> = {
  explore: defineAsyncComponent(() => import('@/views/ExploreView.vue')),
  planner: defineAsyncComponent(() => import('@/views/PlannerHub.vue')),
  inspirations: defineAsyncComponent(() => import('@/views/InspirationView.vue')),
  journeys: defineAsyncComponent(() => import('@/views/JourneyView.vue')),
  search: defineAsyncComponent(() => import('@/views/UserSearchView.vue')),
  map: defineAsyncComponent(() => import('@/views/JourneyMapView.vue'))
}

const activeComponent = computed(() => {
  if (panel.type !== 'view' || !panel.viewKey) return null
  return viewComponents[panel.viewKey] || null
})

function openExternal(url: string) {
  window.open(url, '_blank', 'noopener')
}
function copyUrl(url: string) {
  navigator.clipboard?.writeText(url).then(() => {}).catch(() => {})
}
</script>

<template>
  <Transition name="panel-slide">
    <aside
      v-if="panel.show"
      class="global-right-panel"
      :style="{ width: panel.width + 'px', '--_rp-w': panel.width + 'px' }"
    >
      <div class="panel-inner">
        <!-- 面板标题栏 -->
        <div class="panel-header">
          <div class="panel-title">{{ panel.title }}</div>
          <div class="panel-actions">
            <button
              v-if="panel.type === 'link' && panel.linkData"
              class="panel-btn"
              title="在新标签页打开"
              @click="openExternal(panel.linkData!.url)"
            >↗</button>
            <button
              v-if="panel.type === 'link' && panel.linkData"
              class="panel-btn"
              title="复制链接"
              @click="copyUrl(panel.linkData!.url)"
            >📋</button>
          </div>
        </div>

        <!-- 面板内容 -->
        <div class="panel-body">
          <div v-if="panel.type === 'view' && activeComponent" class="view-wrapper">
            <!-- 传给子组件一个 prop：你来自右栏宿主，可据此调整布局 -->
            <component :is="activeComponent" :from-right-panel="true" />
          </div>

          <div v-else-if="panel.type === 'link' && panel.linkData" class="link-panel-content">
            <div class="link-meta">
              <span class="link-dot"></span>
              <span class="link-url" :title="panel.linkData.url">{{ panel.linkData.title }}</span>
            </div>
            <iframe
              :key="panel.linkData.url"
              :src="panel.linkData.url"
              class="link-iframe"
              :title="`网页预览：${panel.linkData.title || panel.linkData.url}`"
              frameborder="0"
              @load="linkLoading = false"
            ></iframe>
            <div v-if="linkLoading" class="link-loading">内容加载中…</div>
            <div class="link-fallback" @click="openExternal(panel.linkData!.url)">
              无法内嵌预览？点击这里在新标签页打开 →
            </div>
          </div>

          <div v-else class="empty-panel">
            <p>点击左侧侧边栏的功能，在右侧打开对应内容。</p>
          </div>
        </div>
      </div>
    </aside>
  </Transition>
</template>

<style scoped lang="scss">
.global-right-panel {
  position: relative;
  flex-shrink: 0;
  height: 100%;
  background: #FFFFFF;
  display: flex;
  flex-direction: column;
  overflow: visible;
  box-sizing: border-box;
  border: 1px solid #E2E7EE;
  border-radius: 14px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}

/* 内容起点从 header 下方对齐 */
.panel-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0 14px 14px;
  min-height: 0;
}

.panel-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 2px 10px;
  background: transparent;
  border-bottom: 1px solid #EEF1F5;
  margin-bottom: 6px;
  z-index: 10;
}

.panel-title {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: .01em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #1F2329;
}

.panel-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.panel-btn {
  width: 28px;
  height: 28px;
  border: 1px solid transparent;
  background: #F0F4F9;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #5F6368;
  transition: background .15s, color .15s;

  &:hover { background: #E8F0FE; color: #0B57D0; }
  &.close:hover { background: #FCE8E6; color: #D93025; }
}

.panel-body {
  flex: 1 1 auto;
  overflow: hidden;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: transparent;
}

.view-wrapper {
  flex: 1;
  min-height: 0;
  height: 100%;
  overflow: auto;
  background: linear-gradient(180deg, color-mix(in srgb, var(--paper, #f7f3ea) 64%, transparent), transparent 12rem);
  position: relative;


  pointer-events: auto;
  display: flex;
  flex-direction: column;

  :deep(.page),
  :deep([class$='-page']) {
    overflow: visible !important;
    overflow-y: visible !important;
    min-height: 0 !important;
    height: auto !important;
    max-width: none !important;
    width: 100% !important;
    margin: 0 !important;
    padding: 0 !important;
    box-sizing: border-box;
  }
}

.view-wrapper > :deep(*) {
  min-height: 100%;
  max-width: 100%;
  background: transparent;
}

.view-wrapper::-webkit-scrollbar { width: 6px; }
.view-wrapper::-webkit-scrollbar-thumb { background: rgba(0,0,0,.2); border-radius: 3px; }
.view-wrapper::-webkit-scrollbar-track { background: transparent; }

/* 链接预览 */
.link-panel-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  position: relative;
}

.link-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: var(--card, #fff);
  border-bottom: 1px solid var(--line, #e5e6e8);
  flex-shrink: 0;

  .link-dot { width: 8px; height: 8px; border-radius: 50%; background: #4caf50; flex-shrink: 0; }
  .link-url { font-size: 12px; color: #646a73; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}

.link-iframe {
  flex: 1;
  width: 100%;
  border: 0;
  background: var(--card, #fff);
  min-height: 0;
}

.link-loading {
  position: absolute;
  top: 40%; left: 50%;
  transform: translate(-50%, -50%);
  color: #8f959e; font-size: 13px;
}

.link-fallback {
  position: absolute;
  bottom: 12px; left: 50%;
  transform: translateX(-50%);
  font-size: 11px;
  color: #8f959e;
  background: rgba(255, 255, 255, 0.92);
  padding: 5px 12px;
  border-radius: 14px;
  cursor: pointer; white-space: nowrap;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);

  &:hover { color: #245bdb; }
}

.empty-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  color: #8f959e;
  font-size: 13px;
  line-height: 1.8;
  text-align: center;
}

.panel-slide-enter-active,
.panel-slide-leave-active {
  transition: width .2s ease, opacity .2s ease;
}
.panel-slide-enter-from,
.panel-slide-leave-to {
  width: 0 !important;
  opacity: 0;
  overflow: hidden;
}

/* ─── 拖拽手柄 ────────────────────────────────────────
   手柄是 aside 的子元素，但用 position:absolute + z-index 高于 workspace。
   手柄的热区完全在 aside 左侧 14px 内，不依赖父级 overflow 可见。 */
</style>
