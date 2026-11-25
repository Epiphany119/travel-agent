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
.planner-hub { width: 100%; height: 100%; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: radial-gradient(circle at 90% 0, color-mix(in srgb, var(--roam-soft) 74%, transparent), transparent 28rem); color: var(--ink); }
.planner-body { flex: 1 1 auto; min-height: 0; display: flex; overflow: hidden; }
.planner-tabs { flex: 0 0 148px; align-self: stretch; display: flex; flex-direction: column; gap: 4px; margin: 0 0 0 20px; padding: 4px; border: 1px solid var(--line); border-radius: 12px; background: color-mix(in srgb, var(--card) 72%, transparent); overflow: auto; }
.planner-tabs button { display: grid; grid-template-columns: 24px 1fr; grid-template-rows: 1fr 1fr; column-gap: 6px; align-items: center; width: 100%; flex: 0 0 auto; min-width: 0; padding: 7px 10px; border: 0; border-radius: 9px; background: transparent; color: var(--ink-2); text-align: left; cursor: pointer; transition: background .18s, color .18s, transform .18s; }
.planner-tabs button > span { grid-row: 1 / 3; display: grid; place-items: center; width: 24px; height: 24px; border-radius: 7px; background: var(--roam-soft); color: var(--forest); font-size: 14px; }
.planner-tabs button b { font-size: 13px; }
.planner-tabs button small { font-size: 10px; color: var(--ink-3); }
.planner-tabs button:hover { transform: translateY(-1px); color: var(--forest); }
.planner-tabs button.active { background: var(--forest); color: #fff; box-shadow: var(--shadow-soft); }
.planner-tabs button.active > span { background: color-mix(in srgb, #fff 18%, transparent); color: #fff; }
.planner-tabs button.active small { color: color-mix(in srgb, #fff 70%, transparent); }
.planner-canvas { flex: 1 1 auto; min-width: 0; min-height: 0; margin: 0 20px 0 8px; padding-left: 12px; border-left: 1px solid color-mix(in srgb, var(--line) 78%, transparent); overflow: auto; }
.planner-canvas :deep(> *) { min-height: 100%; }
.planner-hub.is-embedded .planner-tabs { flex-basis: 130px; margin-left: 10px; }
.planner-hub.is-embedded .planner-canvas { margin-right: 10px; padding-left: 8px; }
@media (max-width: 700px) {
  .planner-body { flex-direction: column; overflow: hidden; }
  .planner-tabs,
  .planner-hub.is-embedded .planner-tabs { flex: 0 0 auto; flex-direction: row; align-self: auto; margin: 0 12px 6px; }
  .planner-tabs button { min-width: 0; flex: 1 1 0; padding: 6px 8px; }
  .planner-canvas,
  .planner-hub.is-embedded .planner-canvas { margin: 0; padding: 0 12px; border-left: 0; overflow: auto; }
}
</style>

<style scoped>
.planner-hub {
  position: relative;
  isolation: isolate;
  background:
    radial-gradient(circle at 80% 2%, color-mix(in srgb, var(--roam) 15%, transparent), transparent 22rem),
    linear-gradient(135deg, color-mix(in srgb, var(--sunset) 4%, transparent), transparent 42%),
    var(--paper);
}

/* Agent 模式内部（plan-pane / plan-scroll）自己滚，画布不再滚，避免双层滚动条 */
.planner-hub.is-agent-mode .planner-canvas { overflow: hidden; }
.planner-tabs { background: color-mix(in srgb, var(--card) 82%, transparent); box-shadow: inset 0 1px 0 rgba(255,255,255,.65); }
.planner-tabs button { min-height: 48px; }
.planner-tabs button.active { background: linear-gradient(145deg, var(--forest), var(--forest-deep)); box-shadow: 0 10px 20px color-mix(in srgb, var(--forest) 18%, transparent); }
.planner-canvas { padding-top: 2px; }
.planner-canvas::before { content: ''; display: block; height: 1px; margin: 0 0 6px; background: linear-gradient(90deg, color-mix(in srgb, var(--sunset) 50%, var(--line)), transparent 70%); }

</style>
