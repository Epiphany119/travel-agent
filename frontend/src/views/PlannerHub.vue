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
    <div class="planner-body">
      <nav class="planner-tabs" role="tablist" aria-label="旅行规划方式">
        <button type="button" role="tab" :aria-selected="mode === 'form'" :class="{ active: mode === 'form' }" @click="selectMode('form')">
          <span>▦</span><b>快速规划</b><small>固定表单</small>
        </button>
        <button type="button" role="tab" :aria-selected="mode === 'agent'" :class="{ active: mode === 'agent' }" @click="selectMode('agent')">
          <span>✦</span><b>对话 Agent</b><small>逐步协作</small>
        </button>
      </nav>

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
    </div>
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
  background: #FFFFFF;
  color: #1F2329;
}

.planner-body {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  overflow: hidden;
  padding: 14px 16px;
  gap: 14px;
}

.planner-tabs {
  flex: 0 0 168px;
  align-self: flex-start;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 6px;
  border: 1px solid #E5E9F0;
  border-radius: 16px;
  background: #F7F9FC;
}

.planner-tabs button {
  display: grid;
  grid-template-columns: 30px 1fr;
  grid-template-rows: auto auto;
  column-gap: 10px;
  align-items: center;
  width: 100%;
  min-height: 50px;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  color: #5F6368;
  text-align: left;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease, border-color 0.16s ease, box-shadow 0.16s ease;
}

.planner-tabs button > span {
  grid-row: 1 / 3;
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: #EDF2F8;
  color: #5F6368;
  font-size: 14px;
  transition: background 0.16s ease, color 0.16s ease;
}

.planner-tabs button b {
  font-size: 13px;
  font-weight: 600;
  color: #1F2329;
  line-height: 1.3;
}

.planner-tabs button small {
  font-size: 11px;
  color: #8C959F;
  line-height: 1.2;
}

.planner-tabs button:hover {
  background: #EEF2F8;
}

.planner-tabs button.active {
  background: #FFFFFF;
  border-color: #DCE5F2;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
}

.planner-tabs button.active > span {
  background: #E8F0FE;
  color: #0B57D0;
}

.planner-tabs button.active b {
  color: #0B57D0;
}

.planner-canvas {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  padding-left: 14px;
  border-left: 1px solid #EEF1F5;
  overflow: auto;
}

.planner-hub.is-agent-mode .planner-canvas {
  overflow: hidden;
}

.planner-canvas :deep(> *) {
  min-height: 100%;
}

.planner-hub.is-embedded .planner-body {
  padding: 8px;
  gap: 8px;
}

.planner-hub.is-embedded .planner-tabs {
  flex-basis: 140px;
}

.planner-hub.is-embedded .planner-canvas {
  padding-left: 8px;
}

@media (max-width: 700px) {
  .planner-body {
    flex-direction: column;
    padding: 10px;
    gap: 10px;
  }
  .planner-tabs,
  .planner-hub.is-embedded .planner-tabs {
    flex: 0 0 auto;
    flex-direction: row;
    align-self: stretch;
  }
  .planner-tabs button {
    min-width: 0;
    flex: 1 1 0;
    padding: 8px;
  }
  .planner-canvas,
  .planner-hub.is-embedded .planner-canvas {
    padding-left: 0;
    border-left: 0;
    overflow: auto;
  }
}
</style>
