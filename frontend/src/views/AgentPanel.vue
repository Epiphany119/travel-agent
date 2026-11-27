<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, onUnmounted, watch, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useRightPanelStore } from '@/stores/rightPanel'
import { useAgentSessionStore } from '@/stores/agentSession'
import PlanPane from './PlanPane.vue'

/** 由 GlobalRightPanel 传入：告诉子组件它被渲染在右栏宿主里 */
const props = defineProps<{ fromRightPanel?: boolean; embedded?: boolean }>()

const route = useRoute()
const router = useRouter()
const rightPanel = useRightPanelStore()
const agent = useAgentSessionStore()

// 预算这一步：直接在问题气泡里给数字输入框，用户只能输数字
// 用 field 判断，避免开发热更新后 store 里旧问题对象没有 inputType 导致输入框不出现
const askingNumber = computed(() =>
  !agent.done && (agent.currentQuestion?.field === 'budget' || agent.currentQuestion?.inputType === 'number')
)

// 数字输入框的 v-model 会得到 number，先转回字符串再交给 store 的 send()
function submit() {
  if (typeof agent.input === 'number') agent.input = String(agent.input)
  agent.send()
}

// 计划生成后默认收起左侧对话，把空间留给计划；用户可手动展开。
// 用组件本地状态：KeepAlive 会保留它，重新规划时随 done 变化自动展开。
const chatCollapsed = ref(agent.done)
watch(() => agent.done, (done) => { chatCollapsed.value = done })

// ── layout mode 自感知 ───────────────────────────────────────
// 两栏（右栏关）: 中间路由 -> split 左右分栏
// 三栏中间（右栏开但 viewKey !== agent）: 中间 /agent-panel -> results-only
// 三栏右栏（viewKey === agent）: GlobalRightPanel -> chat-only
// 兜底: chat-only
type LayoutMode = 'split' | 'results-only' | 'chat-only'
const layoutMode = computed<LayoutMode>(() => {
  // ★ 关键：GlobalRightPanel 通过 prop 显式告知宿主身份，
  // 因为两个实例共享同一个 Vue Router，route.path 都一样，无法靠路由区分。
  const isRightPanelHost = props.fromRightPanel === true
  const inRoute = props.embedded === true || route.path === '/agent-panel'

  // 被 GlobalRightPanel 渲染：显示对话 + 输入（不管中间路由是什么）
  if (isRightPanelHost) return 'chat-only'
  // 主路由 + 右栏关：两栏左右分栏
  if (inRoute && !rightPanel.show) return 'split'
  // 主路由 + 右栏开：三栏中间态，只展示结果（对话已挪到右栏）
  if (inRoute && rightPanel.show) return 'results-only'
  return 'chat-only'
})

// 容器内滚动用的 ref
const scrollBox = ref<HTMLElement | null>(null)
const inputBox = ref<HTMLInputElement | null>(null)

function scrollBottom() {
  nextTick(() => {
    if (scrollBox.value) scrollBox.value.scrollTop = scrollBox.value.scrollHeight
  })
}
watch(
  () => agent.messages.length,
  () => {
    // 仅在对话/分栏模式下自动滚到底部
    if (layoutMode.value !== 'results-only') scrollBottom()
  },
)
// 计划生成后切到 Day 0（store 已自动设好）
watch(
  () => agent.messages.length,
  () => { if (agent.done) scrollBottom() },
)

// ── 生命周期 ─────────────────────────────────────────────────
onMounted(() => {
  // 三栏中间 results-only 模式：不触发问卷初始化，store 为空就保持空
  if (layoutMode.value === 'results-only') return
  // 仅当会话还没启动（没问候消息）时才初始化，避免右栏/中间各自 mount 时重复 greet
  if (agent.messages.length === 0 && !agent.done) {
    agent.startQuestionnaire()
  }
})

// ★ 关键：chat-only 宿主（GlobalRightPanel）下，
// 一旦 done=true 自动把中间路由切到 /agent-panel（结果以预览身份覆盖笔记界面）。
// 注意：useRouter() 只能在 setup 顶层调用，watcher 里直接用顶层的 router 常量。
watch(
  [layoutMode, () => agent.done],
  ([mode, done]) => {
    if (mode === 'chat-only' && done) {
      if (route.path !== '/chat' || route.query.mode !== 'agent') {
        router.push({ path: '/chat', query: { mode: 'agent' } })
      }
    }
  },
  { immediate: true, flush: 'post' },
)

onUnmounted(() => {
  // 实例卸载时不 dispose store，让两栏/三栏切换时数据保留
  // 只在整个页面关闭时再 reset（通过侧边栏显式调用 startQuestionnaire 或 router-leave）
})

</script>

<template>
  <main
    class="agent-page"
    :class="[`mode-${layoutMode}`, `plan-state-${agent.done ? 'done' : 'chatting'}`]"
  >
    <!-- ═══════════ split 模式（两栏 /agent-panel + 右栏关）═══════════ -->
    <template v-if="layoutMode === 'split'">
      <!-- 页头 -->
      <section class="page-head">
        <div>
          <p class="eyebrow">PLAN AGENT</p>
          <h1>Agent 规划</h1>
          <p class="sub">流式问答 · 实时整合天气与景点数据 · 逐步生成计划</p>
        </div>
        <div class="head-actions">
          <template v-if="agent.done">
            <button class="head-btn" @click="agent.startQuestionnaire()">重新规划</button>
            <button class="head-btn primary" @click="chatCollapsed = !chatCollapsed">
              {{ chatCollapsed ? '展开对话' : '收起对话' }}
            </button>
          </template>
          <div class="status">
            <span class="dot" :class="{ on: !agent.done }"></span>
            {{ agent.done ? '已生成' : agent.sending ? '生成中' : agent.loading ? '启动中' : '就绪' }}
          </div>
        </div>
      </section>

      <!-- 左右分栏主体 -->
      <div class="split-body">
        <!-- 左栏：对话 + 输入（计划生成后默认收起） -->
        <section v-show="!chatCollapsed" class="chat-pane">
          <div class="chat-card">
            <div ref="scrollBox" class="log">
              <div v-for="(m, i) in agent.messages" :key="i" class="msg" :class="m.role">
                <p>{{ m.content }}</p>
                <div v-if="askingNumber && m.role === 'agent' && i === agent.messages.length - 1" class="inline-num">
                  <input
                    type="number"
                    min="300"
                    max="200000"
                    step="100"
                    v-model="agent.input"
                    :disabled="agent.sending"
                    placeholder="输入预算数字（元）"
                    @keyup.enter="submit"
                  />
                  <span class="unit">元</span>
                  <button class="confirm" :disabled="agent.sending || !agent.input" @click="submit">确定</button>
                </div>
              </div>
              <div v-if="agent.sending && !agent.done" class="typing"><span></span><span></span><span></span></div>
            </div>

            <div v-if="agent.optionChips.length && !agent.done" class="chips">
              <button
                v-for="opt in agent.optionChips"
                :key="opt"
                :disabled="agent.sending"
                @click="agent.selectOption(opt)"
              >{{ opt }}</button>
            </div>

            <footer v-if="!agent.done" class="composer">
              <div class="row" v-if="!askingNumber">
                <input
                  ref="inputBox"
                  v-model="agent.input"
                  :disabled="agent.sending"
                  placeholder="Write a message… 输入你的回答"
                  @keyup.enter="submit"
                />
                <button class="send" :disabled="agent.sending" @click="submit" title="发送">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="22" y1="2" x2="11" y2="13" />
                    <polygon points="22 2 15 22 11 13 2 9 22 2" />
                  </svg>
                </button>
              </div>
              <button class="ghost" :disabled="agent.loading || agent.sending" @click="agent.startQuestionnaire">重新开始</button>
            </footer>

            <footer v-else class="composer done-footer">
              <button class="ghost" @click="agent.startQuestionnaire">🔄 重新规划</button>
            </footer>
          </div>
        </section>

        <!-- 分隔竖线 -->
        <div v-show="!chatCollapsed" class="split-divider"></div>

        <!-- 右栏：结果展示 -->
        <section class="plan-pane">
          <PlanPane v-if="agent.done && agent.plan" />
          <div v-else class="plan-placeholder">
            <div class="placeholder-illustration">🗺️</div>
            <h3>旅行计划将在这里呈现</h3>
            <p>在左侧回答完 5 个问题后，Agent 会整合天气与景点数据，自动生成可执行的行程。</p>
          </div>
        </section>
      </div>
    </template>

    <!-- ═══════════ results-only 模式（三栏中间）═══════════ -->
    <template v-else-if="layoutMode === 'results-only'">
      <section class="page-head slim">
        <div>
          <p class="eyebrow">PLAN AGENT</p>
          <h1>Agent 规划</h1>
          <p class="sub">结果预览 · 对话在右侧辅助面板中</p>
        </div>
        <div class="status">
          <span class="dot" :class="{ on: !agent.done }"></span>
          {{ agent.done ? '已生成' : agent.sending ? '生成中' : agent.loading ? '启动中' : '就绪' }}
        </div>
      </section>
      <!-- ★ 关键：plan-scroll 是 results-only 模式下唯一的滚动容器，
           确保底部完整行程正文等内容可以滚出来 -->
      <div class="plan-scroll">
        <PlanPane v-if="agent.done && agent.plan" />
        <div v-else class="plan-placeholder standalone">
          <div class="placeholder-illustration">🗺️</div>
          <h3>旅行计划将在这里呈现</h3>
          <p>打开右侧辅助面板即可开始与 Agent 对话，完成问卷后结果会自动显示在中央。</p>
        </div>
      </div>
    </template>

    <!-- ═══════════ chat-only 模式（三栏右栏）═══════════ -->
    <template v-else>
      <div class="chat-only-head">
        <span class="dot" :class="{ on: !agent.done }"></span>
        <b>{{ agent.done ? '已生成' : agent.sending ? '生成中' : agent.loading ? '启动中' : '就绪' }}</b>
      </div>
      <div class="chat-only-body">
        <div ref="scrollBox" class="log">
          <div v-for="(m, i) in agent.messages" :key="i" class="msg" :class="m.role">
            <p>{{ m.content }}</p>
            <div v-if="askingNumber && m.role === 'agent' && i === agent.messages.length - 1" class="inline-num">
              <input
                type="number"
                min="300"
                max="200000"
                step="100"
                v-model="agent.input"
                :disabled="agent.sending"
                placeholder="输入预算数字（元）"
                @keyup.enter="submit"
              />
              <span class="unit">元</span>
              <button class="confirm" :disabled="agent.sending || !agent.input" @click="submit">确定</button>
            </div>
          </div>
          <div v-if="agent.sending && !agent.done" class="typing"><span></span><span></span><span></span></div>
        </div>

        <div v-if="agent.optionChips.length && !agent.done" class="chips">
          <button v-for="opt in agent.optionChips" :key="opt" :disabled="agent.sending" @click="agent.selectOption(opt)">{{ opt }}</button>
        </div>

        <footer v-if="!agent.done" class="composer">
          <div class="row" v-if="!askingNumber">
            <input ref="inputBox" v-model="agent.input" :disabled="agent.sending" placeholder="输入回答…" @keyup.enter="submit" />
            <button class="send" :disabled="agent.sending" @click="submit" title="发送">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="22" y1="2" x2="11" y2="13" />
                <polygon points="22 2 15 22 11 13 2 9 22 2" />
              </svg>
            </button>
          </div>
          <button class="ghost ghost-full" :disabled="agent.loading || agent.sending" @click="agent.startQuestionnaire">重新开始</button>
        </footer>

        <footer v-else class="composer done-footer">
          <button class="ghost ghost-full" @click="agent.startQuestionnaire">🔄 重新规划</button>
        </footer>
      </div>
    </template>
  </main>
</template>

<style scoped lang="scss">
:global(*) { box-sizing: border-box; }

.agent-page {
  width: 100%;
  max-width: 1600px;
  margin: 0 auto;
  padding: 4px 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: #FFFFFF;
}

/* ── 页头 ─────────────────────────────────────────────── */
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding-bottom: 10px;
  border-bottom: 1px solid #EEF1F5;

  &.slim { margin-bottom: 0; }
}
.eyebrow {
  color: #0B57D0;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
  margin: 0 0 2px;
}
.page-head h1 {
  font: 700 20px/1.25 Manrope, "Noto Sans SC", -apple-system, sans-serif;
  color: #1F2329;
  margin: 0;
  letter-spacing: -0.02em;
}
.sub {
  color: #5F6368;
  font-size: 12px;
  margin: 3px 0 0;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.head-btn {
  border: 1px solid #DADCE0;
  background: #FFFFFF;
  color: #3C4043;
  padding: 6px 14px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;

  &:hover {
    background: #F0F4F9;
    border-color: #C7D2E0;
    color: #1F2329;
  }

  &.primary {
    border-color: #0B57D0;
    background: #0B57D0;
    color: #FFFFFF;
    &:hover { background: #0842A0; border-color: #0842A0; color: #FFFFFF; }
  }
}
.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  font-weight: 600;
  color: #5F6368;
  border: 1px solid #E5E9F0;
  padding: 5px 12px;
  border-radius: 999px;
  background: #F7F9FC;
  flex-shrink: 0;

  .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #BDC1C6;
    &.on {
      background: #1E8E3E;
      box-shadow: 0 0 0 3px rgba(30, 142, 62, 0.16);
    }
  }
}

/* ── split 左右分栏 ───────────────────────────────────── */
.agent-page.mode-split { max-width: 1600px; }

.split-body {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: row;
  align-items: stretch;
  gap: 14px;
  overflow: hidden;
}
.chat-pane {
  flex: 0 0 34%;
  min-width: 290px;
  max-width: 440px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.plan-pane {
  flex: 1 1 0%;
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 4px;
  scroll-behavior: smooth;
  & > :deep(.plan-wrapper) {
    min-height: 0;
    overflow-y: visible !important;
    height: auto;
  }
}
.split-divider {
  width: 1px;
  background: #EEF1F5;
  flex-shrink: 0;
  margin: 4px 0;
}

/* ── chat-only 模式 ─────────────────────────────── */
.mode-chat-only {
  padding: 10px 8px 14px;
  gap: 8px;
}
.chat-only-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #3C4043;
  font-weight: 600;
  padding: 2px 4px;

  .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #BDC1C6;
    flex-shrink: 0;
    &.on {
      background: #1E8E3E;
      box-shadow: 0 0 0 3px rgba(30, 142, 62, 0.16);
    }
  }
}
.chat-only-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  background: #FFFFFF;
  border: 1px solid #E5E9F0;
  border-radius: 16px;
  overflow: hidden;
}

/* ── results-only 模式 ───────────────────── */
.mode-results-only {
  max-width: 1600px;
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.mode-results-only .page-head.slim h1 { font-size: 19px; }
.mode-results-only .plan-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 4px;
  scroll-behavior: smooth;
}
.mode-results-only :deep(.plan-wrapper) {
  min-height: 0;
  overflow-y: visible !important;
  height: auto;
}

/* ── 对话卡片 ─────────────────────── */
.chat-card {
  background: #FFFFFF;
  border: 1px solid #E5E9F0;
  border-radius: 18px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1 1 auto;
  overflow: hidden;
}
.log {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  scroll-behavior: smooth;
}
.mode-chat-only .log { padding: 14px; }

.msg {
  max-width: 84%;
  border-radius: 16px;
  padding: 10px 14px;
  line-height: 1.65;
  font-size: 13.5px;
}
.msg p { margin: 0; white-space: pre-wrap; word-break: break-word; }
.msg.agent {
  align-self: flex-start;
  background: #F0F4F9;
  border: 1px solid transparent;
  border-top-left-radius: 4px;
  color: #1F2329;
}
.msg.user {
  align-self: flex-end;
  background: #0B57D0;
  color: #FFFFFF;
  border-top-right-radius: 4px;
}
.msg.tool {
  align-self: flex-start;
  background: #F7F9FC;
  border: 1px dashed #DADCE0;
  color: #5F6368;
  font-size: 12px;
}
.msg.info {
  align-self: center;
  background: transparent;
  border: 0;
  color: #8C959F;
  font-size: 12px;
  text-align: center;
  max-width: 90%;
}

.inline-num {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;

  input {
    width: 150px;
    min-width: 0;
    padding: 7px 12px;
    font-size: 13px;
    border: 1px solid #DADCE0;
    border-radius: 10px;
    background: #FFFFFF;
    color: #1F2329;
    outline: none;
    &:focus {
      border-color: #0B57D0;
      box-shadow: 0 0 0 3px rgba(11, 87, 208, 0.12);
    }
  }
  .unit { font-size: 12px; color: #5F6368; }
  .confirm {
    border: 0;
    background: #0B57D0;
    color: #FFFFFF;
    cursor: pointer;
    padding: 7px 14px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 600;
    &:hover:not(:disabled) { background: #0842A0; }
    &:disabled { opacity: 0.5; cursor: default; }
  }
}

.typing {
  display: flex;
  gap: 5px;
  padding: 6px 4px;
  align-self: flex-start;
  span {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #0B57D0;
    animation: blink 1s infinite;
    &:nth-child(2) { animation-delay: .2s; }
    &:nth-child(3) { animation-delay: .4s; }
  }
}
@keyframes blink { 0%,100%{opacity:.22} 50%{opacity:1} }

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 4px 18px 14px;

  button {
    border: 1px solid #D2E3FC;
    background: #F7FAFF;
    color: #0B57D0;
    padding: 6px 14px;
    border-radius: 999px;
    cursor: pointer;
    font-size: 12px;
    font-weight: 600;
    transition: background 0.15s ease, border-color 0.15s ease;
    &:hover:not(:disabled) {
      background: #E8F0FE;
      border-color: #0B57D0;
    }
    &:disabled { opacity: 0.5; cursor: default; }
  }
}
.mode-chat-only .chips { padding: 4px 14px 12px; }

/* ── 输入区 ──────────────────────────────────── */
.composer {
  border-top: 1px solid #EEF1F5;
  padding: 12px 16px 14px;
  background: #FFFFFF;
}
.row {
  display: flex;
  gap: 8px;
  align-items: center;

  input {
    flex: 1;
    border: 1px solid transparent;
    background: #F0F4F9;
    color: #1F2329;
    padding: 10px 16px;
    border-radius: 999px;
    font-size: 13px;
    outline: none;
    transition: border-color 0.15s ease, background 0.15s ease, box-shadow 0.15s ease;
    &:focus {
      border-color: #0B57D0;
      background: #FFFFFF;
      box-shadow: 0 0 0 3px rgba(11, 87, 208, 0.12);
    }
    &:disabled { opacity: 0.5; }
    &::placeholder { color: #8C959F; }
  }
}
.send {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  border: 0;
  border-radius: 50%;
  background: #0B57D0;
  color: #FFFFFF;
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: background 0.15s ease, transform 0.15s ease;
  svg { width: 15px; height: 15px; }
  &:hover:not(:disabled) { background: #0842A0; transform: translateY(-1px); }
  &:disabled { opacity: 0.45; cursor: default; }
}
.ghost {
  margin-top: 8px;
  border: 1px solid #DADCE0;
  background: #FFFFFF;
  color: #5F6368;
  padding: 7px 14px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  flex-shrink: 0;
  transition: background 0.15s ease, color 0.15s ease;
  &:hover:not(:disabled) { background: #F0F4F9; color: #1F2329; }
  &:disabled { opacity: 0.5; cursor: default; }
}
.ghost-full { width: 100%; }
.done-footer { display: flex; justify-content: flex-start; }

/* ── 占位提示 ───────────────────── */
.plan-placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 48px 32px;
  background: #F7F9FC;
  border: 1px dashed #DADCE0;
  border-radius: 18px;
  min-height: 300px;
  gap: 12px;

  &.standalone {
    padding: 80px 32px;
    min-height: 400px;
  }

  .placeholder-illustration { font-size: 52px; line-height: 1; opacity: 0.75; }
  h3 { margin: 0; color: #1F2329; font-size: 17px; font-weight: 650; }
  p { margin: 0; color: #5F6368; font-size: 13px; max-width: 360px; line-height: 1.6; }
}

@media (max-width: 980px) {
  .split-body { flex-direction: column; }
  .chat-pane, .plan-pane { flex: none; max-width: none; width: 100%; }
  .split-divider { width: 100%; height: 1px; margin: 6px 0; }
}
@media (max-width: 640px) {
  .chat-pane { min-width: 0; }
  .page-head { flex-direction: column; align-items: flex-start; gap: 10px; }
}
</style>
