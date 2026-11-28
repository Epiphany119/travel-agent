<script setup lang="ts">
import { computed, ref, watch, KeepAlive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ChatView from './ChatView.vue'
import AgentPanel from './AgentPanel.vue'

const props = defineProps<{ fromRightPanel?: boolean }>()
const route = useRoute()
const router = useRouter()
type PlannerMode = 'form' | 'agent'

const mode = ref<PlannerMode>(route.query.mode === 'agent' ? 'agent' : 'form')
const activeComponent = computed(() => mode.value === 'form' ? ChatView : AgentPanel)

watch(() => route.query.mode, (value) => {
  mode.value = value === 'agent' ? 'agent' : 'form'
})

function selectMode(next: PlannerMode) {
  mode.value = next
  router.replace({
    path: '/chat',
    query: next === 'agent' ? { mode: 'agent' } : {}
  })
}
</script>

<template>
  <main class="planner-hub" :class="{ 'is-embedded': props.fromRightPanel, 'is-agent-mode': mode === 'agent' }">
    <header class="planner-topbar">
      <nav class="planner-segmented" role="tablist" aria-label="旅行规划方式">
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'form'"
          :class="{ active: mode === 'form' }"
          @click="selectMode('form')"
        >
          <span class="seg-icon">▦</span>
          <span>快速规划</span>
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'agent'"
          :class="{ active: mode === 'agent' }"
          @click="selectMode('agent')"
        >
          <span class="seg-icon">✦</span>
          <span>对话 Agent</span>
        </button>
      </nav>
      <span class="planner-mode-hint">
        {{ mode === 'form' ? '填写偏好，一键生成完整每日行程' : '多轮对话协作，边聊边调整细节' }}
      </span>
    </header>

    <section class="planner-canvas">
      <KeepAlive>
        <component
          :is="activeComponent"
          :key="mode"
          :from-right-panel="props.fromRightPanel"
          :embedded="mode === 'agent' && !props.fromRightPanel"
        />
      </KeepAlive>
    </section>
  </main>
</template>

<style scoped lang="scss">
.planner-hub {
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #ffffff;
  color: #1f2329;
}

.planner-topbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 24px;
  border-bottom: 1px solid #eef1f5;
  background: #ffffff;
}

.planner-segmented {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px;
  border-radius: 999px;
  background: #f0f4f9;
  border: 1px solid #e5e9f0;
}

.planner-segmented button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #5f6368;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease, box-shadow 0.16s ease;
}

.planner-segmented button:hover {
  color: #1f2329;
}

.planner-segmented button.active {
  background: #ffffff;
  color: #0b57d0;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
}

.seg-icon {
  font-size: 12px;
  line-height: 1;
}

.planner-mode-hint {
  font-size: 12.5px;
  color: #80868b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.planner-canvas {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}

.planner-hub.is-agent-mode .planner-canvas {
  overflow: hidden;
}

.planner-canvas :deep(> *) {
  min-height: 100%;
}

.planner-hub.is-embedded .planner-topbar {
  padding: 8px 12px;
}

.planner-hub.is-embedded .planner-mode-hint {
  display: none;
}

@media (max-width: 700px) {
  .planner-topbar {
    padding: 8px 14px;
  }
  .planner-mode-hint {
    display: none;
  }
}
</style>
