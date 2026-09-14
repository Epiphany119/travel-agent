<script setup lang="ts">
import { ref, computed, watch, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { addInspiration, addJourney, USER_ID, saveJourneyPoints, saveTravelNote } from '@/api/user'
import { renderMarkdown as renderSafeMarkdown } from '@/utils/markdown'
import { subscribeA2AStream, fetchPoiImages, type TravelPlan } from '@/api/agent'
import { useStreamStore } from '@/stores/stream'
import { useUserStore } from '@/stores/user'
import roamlySymbol from '@/assets/brand/logo-app-icon.png'
import { formatCost, normalizeOptionalCost } from '@/utils/planValues'

const router = useRouter()
const streamStore = useStreamStore()
const userStore = useUserStore()

// 地点/餐厅图片懒加载缓存（按名称）
const imageMap = reactive(new Map<string, string[]>())
const imagePending = reactive(new Map<string, boolean>())
const imageQueue: Array<{ name: string; city: string }> = []
let imageQueueRunning = false
const normalizePoiName = (name: string) => name.replace(/[（(].*?[）)]/g, '').replace(/^在/, '').trim()
function loadImages(name: string, city: string) {
  const key = normalizePoiName(name)
  if (!key || imagePending.get(key) || imageMap.has(key)) return
  imagePending.set(key, true)
  imageQueue.push({ name: key, city })
  void drainImageQueue()
}
async function drainImageQueue() {
  if (imageQueueRunning) return
  imageQueueRunning = true
  while (imageQueue.length) {
    const item = imageQueue.shift()!
    try {
      const res = await fetchPoiImages(item.name, item.city)
      imageMap.set(item.name, (res?.imageUrls || []).filter((u) => /^https?:\/\//.test(u)))
    } catch { imageMap.set(item.name, []) }
    finally { imagePending.set(item.name, false) }
  }
  imageQueueRunning = false
}
function imagesOf(name: string): string[] { return imageMap.get(normalizePoiName(name)) || [] }
function onImgError(name: string) {
  const key = normalizePoiName(name)
  const cur = imageMap.get(key)
  if (cur && cur.length > 1) imageMap.set(key, cur.slice(1))
  else if (cur) imageMap.set(key, [])
}

// 结构化每日行程（地点 type='sightseeing'、餐厅 type='meal'、休息 type='rest'）
const structuredDays = ref<any[][]>([])

function scrollToPlanner() {
  document.getElementById('planner')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const destination = ref('杭州')
const days = ref(3)
const budget = ref(3000)
const travelers = ref(2)
const travelStyle = ref('轻松漫游')
const interests = ref(['美食', '人文'])
const travelPlan = ref<TravelPlan | null>(null)
const activeDay = ref(0)
const globalWeather = ref<any>(null)
const styles = ['轻松漫游', '深度人文', '美食优先', '亲子友好']
const interestOptions = ['美食', '人文', '自然', '摄影', '购物', '夜生活']
const preferenceSummary = computed(() => `${travelers.value}人 · ¥${budget.value.toLocaleString()}预算 · ${travelStyle.value} · ${interests.value.length ? interests.value.join(' + ') : '综合体验'}`)
const agentSteps = computed(() => [
  { icon: '☀', title: '天气适配', text: globalWeather.value ? '根据天气调整户外与室内比例' : '结合当地天气安排每日节奏' },
  { icon: '🍜', title: '偏好匹配', text: interests.value.includes('美食') ? '优先筛选本地餐饮，控制排队时间' : '按你的兴趣筛选体验项目' },
  { icon: '↗', title: '路线优化', text: '按区域串联地点，减少折返与通勤' },
  { icon: '¥', title: '预算平衡', text: `控制在 ¥${budget.value.toLocaleString()} 总预算内` }
])
const totalPlanCost = computed(() => structuredDays.value.flat()
  .reduce((sum, item) => sum + (normalizeOptionalCost(item.cost) || 0), 0))
const hasPlanActivities = computed(() => structuredDays.value.flat().length > 0)
const hasUnknownPlanCost = computed(() => structuredDays.value.flat()
  .some(item => normalizeOptionalCost(item.cost) === null))
const confirmedPlanCost = computed(() => hasPlanActivities.value && !hasUnknownPlanCost.value
  ? totalPlanCost.value : null)
const scheduling = ref(false)
async function addToSchedule(target: 'inspiration' | 'journey') {
  if (scheduling.value) return
  scheduling.value = true
  try {
    const result: any = travelPlan.value || { destination: destination.value }
    const noteContent = {
      overview: { destination: result.destination || destination.value, preferences: preferenceSummary.value },
      strategies: agentSteps.value.map(s => ({ title: s.title, text: s.text })),
      budget: { limit: budget.value, knownTotal: totalPlanCost.value, total: confirmedPlanCost.value, hasUnknownCosts: hasUnknownPlanCost.value, items: [] },
      days: structuredDays.value.map((items: any[], i: number) => ({ day: i + 1, items })),
      reminders: [], packing: [], reflections: {}
    }
    await saveTravelNote({ userId: USER_ID, title: `${result.destination || destination.value} · ${days.value}日旅行计划`, destination: result.destination || destination.value, noteType: target, sourceType: 'agent', totalDays: days.value, travelers: travelers.value, budget: budget.value, contentJson: JSON.stringify(noteContent), status: target === 'journey' ? 'planned' : 'draft' })
    if (target === 'inspiration') {
      const planMarkdown = structuredDays.value.map((items: any[], i: number) => {
        const rows = items.map((a: any) => `- **${a.time || ''} ${a.name || ''}**\n  - 地点：${a.location || ''}\n  - 交通：${a.transport || ''}\n  - 备注：${a.notes || ''}\n  - 费用：${formatCost(a.cost)}`).join('\n')
        return `## Day ${i + 1}\n${rows}`
      }).join('\n\n')
      const firstImage = structuredDays.value.flat().find((a: any) => a.image || a.imageUrl)?.image || structuredDays.value.flat().find((a: any) => a.imageUrl)?.imageUrl || ''
      await addInspiration({ userId: USER_ID, name: result.destination || destination.value, imageUrl: firstImage, description: planMarkdown || 'Roamly 为你生成的旅行方案', quote: preferenceSummary.value, tags: 'AI规划,旅行计划', estimatedBudget: confirmedPlanCost.value, status: 0 })
    } else {
      const start = new Date(); start.setDate(start.getDate() + 1)
      const end = new Date(start); end.setDate(start.getDate() + days.value - 1)
      const points = structuredDays.value.flat().filter((a: any) => a.type === 'sightseeing').map((a: any, i: number) => ({ name: a.name, visitDate: a.time, description: a.notes || a.location, sortOrder: i }))
      const created = await addJourney({ userId: USER_ID, destination: result.destination || destination.value, totalDays: days.value, startDate: start.toISOString().slice(0,10), endDate: end.toISOString().slice(0,10), totalCost: confirmedPlanCost.value, summary: preferenceSummary.value, travelType: travelStyle.value, status: 1 })
      if (created.data?.id && points.length) await saveJourneyPoints(created.data.id, points)
    }
    ElMessage.success(target === 'inspiration' ? '已加入灵感目的地' : '已加入我的旅程')
  } catch { ElMessage.error('加入失败，请稍后重试') } finally { scheduling.value = false }
}

// 与 AgentPanel.vue 保持一致的 dayTabs 结构，统一渲染
const dayTabs = computed(() => {
  const plans = streamStore.dayPlans
  if (plans.length === 0) return []
  return plans.map((p) => {
    const md = [
      p.theme ? `## ${p.theme}` : '',
      p.date ? `**日期：** ${p.date}` : '',
      p.morning ? `### 🌅 上午\n${p.morning.plan}\n\n⏱ ${p.morning.duration} · ${formatCost(p.morning.budget)}` : '',
      p.afternoon ? `### ☀️ 下午\n${p.afternoon.plan}\n\n⏱ ${p.afternoon.duration} · ${formatCost(p.afternoon.budget)}` : '',
      p.evening ? `### 🌙 晚上\n${p.evening.plan}\n\n⏱ ${p.evening.duration} · ${formatCost(p.evening.budget)}` : '',
      p.tips ? `### 💡 出行贴士\n${p.tips}` : '',
    ].filter(Boolean).join('\n\n')
    const slotBudgets = [p.morning?.budget, p.afternoon?.budget, p.evening?.budget].filter((value) => value !== undefined)
    const normalizedSlotBudgets = slotBudgets.map(normalizeOptionalCost)
    const dayCost = normalizedSlotBudgets.length && normalizedSlotBudgets.every((value) => value !== null)
      ? normalizedSlotBudgets.reduce<number>((sum, value) => sum + (value ?? 0), 0)
      : null
    return {
      label: `Day ${p.dayNumber}`,
      subLabel: p.date || '',
      html: renderSafeMarkdown(md),
      weather: null,
      budget: dayCost
    }
  })
})

function formatContent(content: string) {
  return renderSafeMarkdown(content)
}

// 把完整行程按「第N天」拆成每天一小节，供各 tab 分别展示
const daySections = computed<string[]>(() => {
  const md = travelPlan.value?.overview || streamStore.fullText || ''
  if (!md) return []
  const lines = md.split('\n')
  const headerIdx: number[] = []
  lines.forEach((l, i) => { if (/(?:第\s*\d+\s*天|Day\s*\d+)/i.test(l)) headerIdx.push(i) })
  if (headerIdx.length === 0) return []
  const out: string[] = []
  headerIdx.forEach((start, k) => {
    const end = k + 1 < headerIdx.length ? headerIdx[k + 1] : lines.length
    out.push(lines.slice(start, end).join('\n'))
  })
  return out
})

// 稳定地把叙述中的地点/餐厅标橙：
// 1) 优先精确匹配已知名称；2) 对"标签：地点"或"时间：地点"行，把冒号后的短地点内容标橙。
function highlightNames(md: string, names: string[]): string {
  if (!md) return md
  const uniq = [...new Set(names.filter((n): n is string => !!n && n.length >= 2))]
    .sort((a, b) => b.length - a.length)

  return md.split('\n').map((line) => {
    const trimmed = line.trim()
    // 标题/列表项不做行级高亮（避免整行变橙）
    const isHeading = /^#{1,6}\s/.test(trimmed) || trimmed.startsWith('-') || trimmed.startsWith('*') || trimmed.startsWith('>')
    let out = line
    // 1) 名称匹配（任何位置）
    if (uniq.length && !isHeading) {
      for (const n of uniq) {
        const escaped = n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
        out = out.replace(new RegExp(escaped, 'g'), `<span class="nav-hl">${n}</span>`)
      }
    }
    return out
  }).join('\n')
}

function normalizePlanActivity(activity: any) {
  return {
    ...activity,
    type: activity?.type || 'sightseeing',
    name: String(activity?.name || activity?.title || activity?.poiName || '待安排地点'),
    location: activity?.location || activity?.address || '',
    time: activity?.time || '',
    transport: activity?.transport || activity?.travelMode || '',
    notes: activity?.notes || activity?.description || activity?.reason || '',
    cost: normalizeOptionalCost(activity?.cost ?? activity?.price),
    duration: Number(activity?.duration || 0),
  }
}

function normalizePlanDays(result: any, requestedDays: number) {
  const rawDays = Array.isArray(result?.dayPlans)
    ? result.dayPlans
    : Array.isArray(result?.days_plan)
      ? result.days_plan
      : Array.isArray(result?.days)
        ? result.days
        : []
  return Array.from({ length: Math.max(requestedDays, rawDays.length, 1) }, (_, index) => {
    const raw = rawDays[index] || {}
    const rawActivities = Array.isArray(raw.activities) ? raw.activities : Array.isArray(raw.items) ? raw.items : []
    return {
      ...raw,
      day: Number(raw.day || raw.dayNumber || index + 1),
      date: raw.date || '',
      theme: raw.theme || raw.title || `第${index + 1}天`,
      dailyBudget: Number(raw.dailyBudget || raw.budget || 0),
      weather: raw.weather || '',
      temperature: raw.temperature || '',
      activities: rawActivities.map(normalizePlanActivity),
    }
  })
}

function fallbackPlanText(planDays: any[]) {
  return planDays.map((day, index) => {
    const activities = day.activities || []
    const body = activities.length
      ? activities.map((activity: any) => `- **${activity.name}**${activity.location ? `（${activity.location}）` : ''}${activity.time ? ` · ${activity.time}` : ''}${activity.notes ? `\n  ${activity.notes}` : ''}`).join('\n')
      : '- 保留机动时间，根据当天体力、天气和现场情况灵活调整。'
    return [`## 第${day.day || index + 1}天 · ${day.theme || ''}`, day.date ? `**日期：** ${day.date}` : '', body].filter(Boolean).join('\n\n')
  }).join('\n\n')
}

// 行程就绪后，按地点/餐厅名称懒加载图片
watch(() => structuredDays.value, (days) => {
  for (const items of days) {
    for (const it of items) {
      if (it.name) loadImages(it.name, destination.value)
    }
  }
})

let cancelStream: (() => void) | null = null

async function generateStream() {
  if (!destination.value.trim()) return ElMessage.warning('先告诉我想去哪里')
  if (streamStore.isStreaming) return

  streamStore.reset()
  imageMap.clear()
  imagePending.clear()
  imageQueue.splice(0, imageQueue.length)
  structuredDays.value = []
  streamStore.isStreaming = true
  travelPlan.value = null
  activeDay.value = 0
  globalWeather.value = null

  cancelStream = subscribeA2AStream({
    destination: destination.value,
    days: days.value,
    budget: budget.value,
    travelers: travelers.value,
    travelStyle: travelStyle.value,
    interests: interests.value
  }, (event) => {
    switch (event.name) {
      case 'task_update':
        console.log('[Plan] task_update:', event.data)
        break
      case 'tool_call':
        streamStore.toolCalls.push({
          name: event.data?.source || event.data?.name || '未知工具',
          args: event.data
        })
        break
      case 'tool_result':
        streamStore.currentToolResult = event.data?.message || event.data?.summary || event.data?.type || '完成'
        break
      case 'token':
        if (typeof event.data === 'string') {
          streamStore.fullText += event.data
        } else if (event.data && typeof event.data === 'object') {
          streamStore.fullText += event.data.delta || event.data.content || ''
        }
        break
      case 'task_done': {
        console.log('[Plan] task_done received:', event.data)
          const result = event.data?.data && typeof event.data.data === 'object' ? event.data.data : event.data
          if (result && typeof result === 'object') {
          const backendDays = normalizePlanDays(result, days.value)

          const streamDayPlans = backendDays.map((dp: any) => {
            const activities = dp.activities || []
            const morningAct = activities.find((a: any) => a.time?.startsWith('0') || a.time?.startsWith('1'))
            const afternoonAct = activities.find((a: any) => a.time?.startsWith('1') || a.time?.startsWith('2'))
            const eveningAct = activities.find((a: any) => a.type === 'rest' || a.type === 'meal')

            const mkSlot = (act: any) => act ? {
              plan: `${act.name}${act.location ? ' @ ' + act.location : ''}${act.notes ? ' - ' + act.notes : ''}`,
              duration: act.duration ? Math.round(act.duration / 60) + '小时' : '1小时',
              tips: act.notes || '',
              budget: normalizeOptionalCost(act.cost)
            } : undefined

            return {
              dayNumber: dp.day || 0,
              theme: morningAct?.name || afternoonAct?.name || `第${dp.day}天`,
              date: dp.date || '',
              morning: mkSlot(morningAct),
              afternoon: mkSlot(afternoonAct),
              evening: mkSlot(eveningAct),
              tips: dp.weather ? `天气: ${dp.weather} ${dp.temperature || ''}` : ''
            }
          })

          const detailDayPlans = backendDays.map((dp: any) => ({
            dayNumber: dp.day || 0,
            date: dp.date || '',
            theme: dp.activities?.find((a: any) => a.type === 'sightseeing')?.name || `第${dp.day}天`,
            dayBudget: dp.dailyBudget || 0,
            transportation: '',
            notes: dp.weather || '',
            attractions: (dp.activities || [])
              .filter((a: any) => a.type === 'sightseeing')
              .map((a: any) => ({
                name: a.name || '',
                description: a.notes || a.location || '',
                duration: Math.round((a.duration || 0) / 60),
                ticketPrice: normalizeOptionalCost(a.cost)
              })),
            meals: (dp.activities || [])
              .filter((a: any) => a.type === 'meal')
              .map((a: any) => ({
                mealType: getMealType(a.time),
                restaurantName: a.name || '',
                cuisine: a.notes || '',
                avgPrice: normalizeOptionalCost(a.cost),
                reason: a.notes || ''
              }))
          }))

          streamStore.dayPlans = streamDayPlans

          // 构建结构化每日行程（供卡片渲染：地点橙色 + 地点/餐厅图片）
          structuredDays.value = backendDays.map((dp: any) => (dp.activities || []).map((a: any) => ({
            type: a.type || 'sightseeing',
            name: a.name || '',
            location: a.location || '',
            time: a.time || '',
            transport: a.transport || a.travelMode || '',
            notes: a.notes || '',
            cost: normalizeOptionalCost(a.cost),
            duration: a.duration ? Math.round(a.duration / 60) : 0
          })))

          travelPlan.value = {
            planId: result.success ? 'plan_' + Date.now() : '',
            destination: destination.value,
            days: days.value,
            totalBudget: result.budget?.totalBudget || budget.value,
            estimatedCost: confirmedPlanCost.value,
            budgetStatus: result.budget?.success ? 'ok' : 'limited',
            overview: result.finalPlan || streamStore.fullText,
            travelTips: [],
            packingList: [],
            dayPlans: detailDayPlans
          }

          // 按"第1天/第2天/..."拆分 overview，分别塞入各 Tab
          const overview = String(result.finalPlan || result.overview || streamStore.fullText || '').trim() || fallbackPlanText(backendDays)
          console.log('[Plan] overview length:', overview?.length, 'first 200 chars:', overview?.slice(0, 200))
        }
        streamStore.isStreaming = false
        ElMessage.success('行程规划完成！')
        break
      }
      case 'error':
        console.error('[Plan] error:', event.data)
        streamStore.error = event.data?.message || '未知错误'
        streamStore.isStreaming = false
        break
      default:
        console.log('[Plan] unknown event:', event.name, event.data)
        break
    }
  })
}

function cancelStreaming() {
  if (cancelStream) {
    cancelStream()
    cancelStream = null
  }
  streamStore.reset()
}

function getMealType(time: string): string {
  if (!time) return '餐饮'
  const h = parseInt(time.split(':')[0])
  if (h < 10) return '早餐'
  if (h < 14) return '午餐'
  if (h < 17) return '下午茶'
  return '晚餐'
}
</script>

<template>
  <main class="app-shell">
    <section class="hero">
      <img class="hero-logo" :src="roamlySymbol" alt="Roamly" />
      <p class="eyebrow">TRAVEL, THOUGHTFULLY</p>
      <h1>
        <span>把期待，变成一趟</span><br/>
        <em>刚刚好的旅行。</em>
      </h1>
      <p class="sub">告诉我目的地、时间和预算。Roamly 会把复杂的功课，整理成可以立刻出发的每一天。</p>
      <div class="hero-actions">
        <button class="btn-primary" @click="scrollToPlanner">开始规划</button>
        <button class="btn-ghost" @click="router.push('/explore')">探索灵感</button>
      </div>
      <div class="trust">
        <span>✦ 路线按区域串联</span>
        <span>◌ 预算清晰可控</span>
        <span>⌁ 出发前避坑提醒</span>
      </div>
    </section>

    <section id="planner" class="planner-card">
      <div class="planner-title">
        <div>
          <p class="eyebrow">START PLANNING</p>
          <h2>你的旅行偏好</h2>
        </div>
        <span>01 / 02</span>
      </div>

      <div class="form-grid">
        <label class="field destination">
          <span>目的地</span>
          <input v-model="destination" maxlength="30" placeholder="例如：杭州" />
        </label>
        <label class="field">
          <span>旅行天数</span>
          <div class="number-control">
            <button @click="days = Math.max(1, days - 1)">−</button>
            <b>{{ days }} 天</b>
            <button @click="days = Math.min(14, days + 1)">+</button>
          </div>
        </label>
        <label class="field">
          <span>总预算（元）</span>
          <input v-model.number="budget" type="number" min="300" max="200000" />
        </label>
        <label class="field">
          <span>同行人数</span>
          <div class="number-control">
            <button @click="travelers = Math.max(1, travelers - 1)">−</button>
            <b>{{ travelers }} 人</b>
            <button @click="travelers = Math.min(12, travelers + 1)">+</button>
          </div>
        </label>
      </div>

      <div class="choice-row">
        <div>
          <span>旅行节奏</span>
          <div class="chips">
            <button
              v-for="item in styles"
              :key="item"
              :class="{ selected: travelStyle === item }"
              @click="travelStyle = item"
            >{{ item }}</button>
          </div>
        </div>
        <div>
          <span>这次更想要</span>
          <div class="chips">
            <button
              v-for="item in interestOptions"
              :key="item"
              :class="{ selected: interests.includes(item) }"
              @click="interests.includes(item) ? interests = interests.filter(x => x !== item) : interests = [...interests, item]"
            >{{ item }}</button>
          </div>
        </div>
      </div>

      <div class="generate-row">
        <button
          class="generate generate--stream"
          :disabled="streamStore.isStreaming"
          @click="generateStream"
        >
          <span>{{ streamStore.isStreaming ? 'AI 正在为你规划行程…' : '✨ 生成专属旅行计划' }}</span>
          <i v-if="!streamStore.isStreaming">→</i>
          <i v-else class="spinner">⟳</i>
        </button>
        <button
          v-if="streamStore.isStreaming"
          class="generate generate--cancel"
          @click="cancelStreaming"
        >
          <span>取消</span>
        </button>
        <button
          v-if="streamStore.error"
          class="generate generate--retry"
          @click="generateStream"
        >
          <span>重新生成</span>
          <i>↻</i>
        </button>
      </div>
    </section>

    <!-- ─── 流式 / 最终结果展示 ───────────────────────────────────────────── -->
    <section
      v-if="streamStore.isStreaming || streamStore.fullText || streamStore.dayPlans.length > 0"
      class="result stream-result"
    >
      <div class="result-head">
        <div>
          <p class="eyebrow">{{ streamStore.isStreaming ? 'STREAMING OUTPUT' : 'YOUR ITINERARY' }}</p>
          <h2>{{ destination }} · {{ days }} 天旅程</h2>
          <p>{{ streamStore.isStreaming ? 'Roamly 正在替你完成一次旅行规划' : 'Roamly 已把偏好、天气、路线和预算整理成可执行方案' }}</p>
        </div>
        <div class="budget">
          <span>预算</span>
          <b>¥{{ budget.toLocaleString() }}</b>
          <small>{{ budget >= 5000 ? '高端享受' : budget >= 2000 ? '舒适经济' : '经济实惠' }}</small>
        </div>
      </div>

      <div class="agent-status">
        <div class="agent-status-head"><span>✦ Roamly 私人旅行管家</span><small>{{ streamStore.isStreaming ? '正在规划…' : '规划完成' }}</small></div>
        <div class="agent-step-grid">
          <div v-for="step in agentSteps" :key="step.title" class="agent-step">
            <span class="agent-step-icon">{{ step.icon }}</span><div><b>{{ step.title }}</b><p>{{ step.text }}</p></div>
          </div>
        </div>
      </div>

      <!-- Markdown 打字效果（流式中实时展示） -->
      <div v-if="streamStore.fullText && streamStore.isStreaming" class="stream-text" v-html="formatContent(streamStore.fullText)"></div>

      <!-- ─── Tab + Markdown 计划展示（与 AgentPanel.vue 一致） ─────────────── -->
      <div v-if="dayTabs.length > 0" class="plan-section">
        <div class="plan-head">
          <div><p class="eyebrow">YOUR PERSONAL TRIP</p><h3>{{ destination }} · {{ dayTabs.length }}日慢旅行</h3><p class="plan-preferences">{{ preferenceSummary }}</p></div>
          <span class="plan-cost" v-if="confirmedPlanCost != null">已核验费用下限 ¥{{ confirmedPlanCost.toLocaleString() }}</span>
          <span class="plan-cost plan-cost--pending" v-if="hasUnknownPlanCost">部分费用待确认</span>
          <div class="plan-actions">
            <button class="schedule-btn" :disabled="scheduling" @click="addToSchedule('inspiration')">✦ {{ scheduling ? '保存中…' : '存到灵感目的地' }}</button>
            <button class="schedule-btn schedule-btn--ghost" :disabled="scheduling" @click="addToSchedule('journey')">＋ 保存为我的旅程</button>
          </div>
        </div>
        <div class="decision-panel">
          <div><span class="decision-kicker">AI 旅行策略</span><strong>{{ travelStyle }} × {{ interests.length ? interests.join(' · ') : '综合体验' }}</strong></div>
          <p>每天安排 2–4 个核心地点，优先按区域串联；你可以直接按时间轴出发，也可以替换任意一个地点。</p>
        </div>

        <!-- 按天 Tabs -->
        <div class="day-tabs">
          <div class="tab-bar">
            <button
              v-for="(tab, i) in dayTabs"
              :key="i"
              class="tab-btn"
              :class="{ active: activeDay === i }"
              @click="activeDay = i"
            >
              <span class="tab-label">{{ tab.label }}</span>
              <span v-if="tab.budget != null" class="tab-temp">¥{{ tab.budget }}</span>
            </button>
          </div>

          <!-- Tab 内容 -->
          <div v-if="dayTabs[activeDay]" class="tab-content">
            <div class="day-weather-row">
              <div v-if="dayTabs[activeDay].budget != null" class="weather-chip budget-chip">
                <span>💰</span>
                <span>¥{{ dayTabs[activeDay].budget }}</span>
              </div>
            </div>

            <!-- 结构化行程卡：地点橙色、地点/餐厅左侧配图 -->
            <div v-if="structuredDays[activeDay]?.length" class="itin-cards">
              <div v-for="(slot, si) in structuredDays[activeDay]" :key="si"
                   class="itin-card" :class="'itin-card--' + slot.type">
                <div v-if="imagesOf(slot.name)[0]" class="itin-photo">
                  <img :src="imagesOf(slot.name)[0]" :alt="slot.name" loading="lazy"
                       referrerpolicy="no-referrer" @error="onImgError(slot.name)" />
                </div>
                <div class="itin-info">
                  <div class="itin-title">
                    <span v-if="slot.time" class="itin-time">{{ slot.time }}</span>
                    <h4 :class="slot.type === 'sightseeing' ? 'itin-place' : 'itin-food'">{{ slot.name }}</h4>
                  </div>
                  <div v-if="slot.location" class="itin-loc">📍 {{ slot.location }}</div>
                  <div v-if="slot.transport" class="itin-loc">↗ {{ slot.transport }}</div>
                  <div v-if="slot.notes" class="itin-note">{{ slot.notes }}</div>
                  <div v-if="slot.cost != null || slot.duration" class="itin-meta">
                    <span v-if="slot.duration">⏱ {{ slot.duration }} 小时</span>
                    <span>{{ formatCost(slot.cost) }}</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 当天详细行程（从完整行程按「第N天」拆分到当前 tab） -->
            <div v-if="daySections[activeDay]" class="plan-md day-narrative"
                 v-html="formatContent(highlightNames(daySections[activeDay], (structuredDays[activeDay] || []).map((s: any) => s.name)))"></div>

            <!-- 无结构化数据也无拆分正文时，回退原 markdown -->
            <div v-if="!structuredDays[activeDay]?.length && !daySections[activeDay]"
                 class="plan-md" v-html="dayTabs[activeDay].html"></div>
          </div>
        </div>
      </div>

      <!-- 流式错误 -->
      <div v-if="streamStore.error" class="stream-error">
        <strong>⚠️ 出错了：</strong>{{ streamStore.error }}
      </div>
    </section>

    <!-- ─── 传统同步结果（保持兼容）───────────────────────────────────────── -->
    <section v-if="travelPlan && !streamStore.isStreaming && dayTabs.length === 0" class="result">
      <div class="result-head">
        <div>
          <p class="eyebrow">YOUR ITINERARY</p>
          <h2>{{ destination }} · {{ days }} 天旅程</h2>
          <p>AI 生成的个性化旅行方案</p>
        </div>
        <div class="budget">
          <span>预算</span>
          <b>¥{{ budget.toLocaleString() }}</b>
          <small>{{ budget >= 5000 ? '高端享受' : budget >= 2000 ? '舒适经济' : '经济实惠' }}</small>
        </div>
      </div>

      <template v-if="travelPlan">
        <div class="days">
          <button
            v-for="day in days"
            :key="day"
            :class="{ active: activeDay === day }"
            @click="activeDay = day"
          >
            <small>DAY</small><b>{{ String(day).padStart(2, '0') }}</b>
          </button>
        </div>

        <div v-if="travelPlan.dayPlans?.[activeDay]" class="day-card">
          <div class="day-title">
            <div>
              <p>DAY {{ String(travelPlan.dayPlans[activeDay].dayNumber).padStart(2, '0') }}</p>
              <h3>{{ travelPlan.dayPlans[activeDay].theme }}</h3>
            </div>
            <b>预算 ¥{{ travelPlan.dayPlans[activeDay].dayBudget?.toLocaleString() }}</b>
          </div>

          <div class="timeline">
            <div v-for="(attraction, idx) in travelPlan.dayPlans[activeDay].attractions" :key="idx" class="timeline-item">
              <span>{{ idx + 1 }}</span>
              <div>
                <h4>{{ attraction.name }}</h4>
                <p>{{ attraction.description }}</p>
                <small>游览约 {{ attraction.duration }} 小时 | 门票 {{ formatCost(attraction.ticketPrice) }}</small>
              </div>
            </div>
          </div>

          <div v-if="travelPlan.dayPlans[activeDay].meals?.length" class="meal-grid">
            <div v-for="meal in travelPlan.dayPlans[activeDay].meals" :key="meal.mealType" class="meal">
              <span>{{ meal.mealType }}</span>
              <h4>{{ meal.restaurantName }}</h4>
              <p>{{ meal.cuisine }} · 人均 {{ formatCost(meal.avgPrice) }}</p>
              <small>{{ meal.reason }}</small>
            </div>
          </div>
        </div>

        <div v-if="travelPlan.overview" class="ai-content" v-html="formatContent(travelPlan.overview)"></div>
      </template>
    </section>
  </main>
</template>

<style scoped lang="scss">
:global(*) { box-sizing: border-box; }

.app-shell {
  padding-bottom: 80px;
  background: radial-gradient(circle at 86% 5%, #d8ede2 0, transparent 23rem),
              radial-gradient(circle at 5% 30%, #fff7e4 0, transparent 24rem);
}

.hero {
  width: min(860px, calc(100% - 40px));
  margin: 0 auto;
  text-align: center;
  padding: 40px 0 45px;
}

.eyebrow {
  color: var(--sunset);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.18em;
  margin: 0 0 10px;
}

.hero h1 {
  font: 54px/1.08 "DM Serif Display", "Noto Sans SC";
  letter-spacing: -1.6px;
  margin: 0;
  text-align: center;
}

.hero h1 em { font-style: normal; color: var(--roam); }

.sub {
  max-width: 560px;
  margin: 19px auto;
  color: #687873;
  line-height: 1.8;
  font-size: 14px;
}

.trust {
  display: flex;
  justify-content: center;
  gap: 22px;
  color: #587368;
  font-size: 11px;
  font-weight: 700;
  margin-top: 28px;
}

.hero-logo { width: 32px; height: 32px; margin-bottom: 16px; }

.hero-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 28px;
}

.btn-primary {
  border: 0;
  background: var(--forest);
  color: #fff;
  font-weight: 800;
  font-size: 14px;
  padding: 14px 36px;
  border-radius: 999px;
  cursor: pointer;
  box-shadow: 0 8px 18px rgba(22, 78, 66, 0.22);
  transition: background 0.2s, transform 0.2s;

  &:hover { background: var(--roam); transform: translateY(-1px); }
}

.btn-ghost {
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--forest);
  font-weight: 800;
  font-size: 14px;
  padding: 14px 36px;
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.2s;

  &:hover { background: var(--roam-soft); }
}

.planner-card, .result {
  width: min(1020px, calc(100% - 40px));
  margin: auto;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 24px;
  box-shadow: var(--shadow-soft);
}

.planner-card { padding: 34px 38px; }

.planner-title, .result-head, .day-title {
  display: flex;
  justify-content: space-between;
  gap: 20px;
}

.planner-title h2, .result-head h2 { font-size: 25px; margin: 0; }
.planner-title > span { font-size: 12px; color: #98a59f; }

.form-grid {
  display: grid;
  grid-template-columns: 1.5fr repeat(3, 1fr);
  gap: 12px;
  margin: 27px 0;
}

.field {
  border: 1px solid var(--line);
  border-radius: 13px;
  padding: 10px 14px;
  display: block;
}

.field span, .choice-row > div > span {
  font-size: 11px;
  color: #7c8a84;
  font-weight: 700;
  display: block;
  margin-bottom: 6px;
}

.field input {
  width: 100%;
  border: 0;
  background: transparent;
  font: 700 16px Manrope;
  color: var(--ink);
  outline: 0;
}

.number-control { display: flex; justify-content: space-between; align-items: center; }

.number-control button {
  border: 0;
  background: #edf3ec;
  border-radius: 7px;
  width: 22px;
  height: 22px;
  color: var(--roam);
  font-size: 16px;
  cursor: pointer;
}

.number-control b { font-size: 14px; }

.choice-row {
  border-top: 1px solid var(--line);
  padding-top: 21px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 26px;
}

.chips { display: flex; gap: 7px; flex-wrap: wrap; }

.chips button {
  border: 1px solid var(--line);
  background: white;
  padding: 7px 11px;
  border-radius: 24px;
  color: #66756f;
  font-size: 12px;
  cursor: pointer;
}

.chips button.selected {
  background: var(--forest);
  color: white;
  border-color: var(--forest);
}

.generate-row { display: flex; gap: 12px; margin-top: 28px; }

.generate {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border: 0;
  border-radius: 13px;
  padding: 15px 18px 15px 21px;
  font-weight: 800;
  font-size: 14px;
  cursor: pointer;
  flex: 1;
}

.generate:disabled { opacity: 0.65; cursor: not-allowed; }
.generate i { font-size: 24px; font-style: normal; }

.generate--stream {
  background: var(--forest);
  color: white;
  box-shadow: 0 8px 18px rgba(22,78,66,.22);
}

.generate--cancel {
  background: #909399;
  color: white;
  flex: 0;
  min-width: 80px;
}

.generate--retry {
  background: var(--sunset);
  color: white;
  box-shadow: 0 8px 18px rgba(242,122,79,.28);
  flex: 0;
  min-width: 120px;
}

.spinner { animation: spin 1s linear infinite; display: inline-block; }

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.result { margin-top: 25px; padding: 35px 38px; }

.stream-result {
  border: 2px dashed var(--roam);
  background: var(--roam-soft);
}

.result-head > div > p:not(.eyebrow) {
  color: #687873;
  font-size: 13px;
  max-width: 600px;
  line-height: 1.7;
}

.budget {
  min-width: 190px;
  background: var(--roam-soft);
  padding: 17px;
  border-radius: 15px;
}

.budget span, .budget small { display: block; font-size: 11px; color: #6f847b; }
.budget b { display: block; font: 27px "DM Serif Display"; color: var(--forest); margin: 4px 0; }

/* ─── 工具调用进度 ─────────────────────────────────────────── */
.tool-progress {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-calling { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

.tool-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border-radius: 24px;
  font-size: 13px;
}

.tool-badge--calling {
  background: #fff7e6;
  color: #e6a23c;
  border: 1px solid #f5dab1;
}

.tool-badge--done {
  background: #f0f9eb;
  color: #67c23a;
  border: 1px solid #c2e7b0;
}

.loading-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #e6a23c;
  animation: pulse 1s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

/* ─── 流式打字效果 ─────────────────────────────────────────── */
.stream-text {
  margin-top: 16px;
  padding: 16px 20px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 12px;
  line-height: 1.8;
  color: #303133;
  font-size: 14px;
  max-height: 300px;
  overflow-y: auto;
}

/* ─── 计划区域（与 AgentPanel.vue 一致） ───────────────────── */
.plan-section {
  margin-top: 20px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 16px;
  overflow: hidden;
}

.plan-head {
  padding: 18px 22px 14px;
  border-bottom: 1px solid var(--line);
}

.plan-head h3 { margin: 0; font-size: 18px; color: var(--ink); }

/* 按天 Tabs */
.day-tabs { }
.tab-bar {
  display: flex; gap: 6px; overflow-x: auto; padding: 14px 20px 12px;
  border-bottom: 2px solid var(--line); scrollbar-width: none;
}
.tab-bar::-webkit-scrollbar { display: none; }

.tab-btn {
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  padding: 8px 16px; border-radius: 12px; border: 1.5px solid transparent;
  background: transparent; cursor: pointer; transition: all 0.2s;
  flex-shrink: 0; min-width: 60px;
}

.tab-label { font-size: 13px; font-weight: 700; color: #98a59f; transition: color 0.2s; }
.tab-temp { font-size: 11px; color: #c0cac4; transition: color 0.2s; }

.tab-btn.active {
  border-color: var(--forest);
  background: var(--roam-soft);
}
.tab-btn.active .tab-label { color: var(--forest); }
.tab-btn.active .tab-temp { color: var(--forest); }

.tab-content { padding: 16px 20px 20px; }

.day-weather-row { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 14px; }

.weather-chip {
  display: inline-flex; align-items: center; gap: 5px;
  background: #f0f7f4; border: 1px solid #d5e4da;
  color: #3d6e5a; font-size: 12px; padding: 5px 10px; border-radius: 20px;
}

.budget-chip { background: #fef9ec; border-color: #f0dfb5; color: #a0712e; }

/* ─── 完整行程正文 ─────────────────────────────────────── */
.plan-detail {
  margin-top: 20px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 16px;
  overflow: hidden;
}

.plan-detail-inner {
  padding: 22px 24px;
  line-height: 1.8;
  font-size: 14px;
  color: #333;

  :deep(h1), :deep(h2), :deep(h3) {
    color: var(--forest);
    margin: 1em 0 0.5em;
    font-weight: 700;
  }
  :deep(h1) { font-size: 22px; }
  :deep(h2) { font-size: 18px; border-bottom: 1.5px solid var(--line); padding-bottom: 6px; }
  :deep(h3) { font-size: 15px; }
  :deep(p) { margin: 0.7em 0; }
  :deep(ul), :deep(ol) { padding-left: 22px; margin: 0.7em 0; }
  :deep(li) { margin: 0.3em 0; }
  :deep(strong) { color: var(--sunset); font-weight: 600; }
  :deep(em) { color: var(--sunset); font-style: italic; }
  :deep(code) {
    background: var(--roam-soft);
    padding: 2px 6px;
    border-radius: 4px;
    font-size: 13px;
  }
  :deep(blockquote) {
    border-left: 4px solid var(--sunset);
    margin: 1em 0;
    padding: 10px 16px;
    background: var(--sunset-soft);
    color: #66756f;
  }
  :deep(table) {
    width: 100%;
    border-collapse: collapse;
    margin: 1em 0;
    font-size: 13px;
  }
  :deep(th) {
    background: var(--roam-soft);
    color: var(--forest);
    padding: 8px 12px;
    text-align: left;
    border-bottom: 2px solid var(--line);
  }
  :deep(td) {
    padding: 8px 12px;
    border-bottom: 1px solid var(--line);
  }
  :deep(tr:last-child td) { border-bottom: none; }
}

/* Markdown 内容 */
.plan-md {
  background: #fafaf8; border: 1px solid var(--line); border-radius: 14px;
  padding: 18px 20px; font-size: 13.5px; line-height: 1.75; color: var(--ink);

  h2 { font-size: 17px; font-weight: 700; color: var(--ink); margin: 0 0 10px; border-bottom: 1.5px solid var(--line); padding-bottom: 6px; }
  h3 { font-size: 14px; font-weight: 700; color: var(--ink-2); margin: 14px 0 6px; }
  p { margin: 0 0 8px; }
  strong { color: var(--forest); }
  ul, ol { margin: 6px 0 8px; padding-left: 20px; }
  li { margin-bottom: 4px; }
}

.agent-status { margin: 18px 0 22px; padding: 18px 20px; border: 1px solid var(--line); border-radius: 16px; background: linear-gradient(135deg,#fffdf8,#f8fbf7); }
.agent-status-head { display:flex; justify-content:space-between; align-items:center; color:var(--ink); font-weight:800; }
.agent-status-head small { color:var(--forest); font-weight:700; }
.agent-step-grid { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin-top:14px; }
.agent-step { display:flex; gap:9px; min-width:0; }
.agent-step-icon { display:grid; place-items:center; width:28px; height:28px; flex:none; border-radius:9px; background:var(--sunset-soft); color:var(--sunset); font-weight:800; }
.agent-step b { font-size:12px; color:var(--ink); }
.agent-step p { margin:3px 0 0; color:var(--ink-2); font-size:11px; line-height:1.45; }
.plan-preferences { margin:4px 0 0; color:var(--ink-2); font-size:12px; }
.plan-cost { color:var(--forest); font-size:12px; font-weight:800; white-space:nowrap; }
.plan-actions { display:flex; align-items:center; gap:7px; flex-wrap:wrap; justify-content:flex-end; }
.schedule-btn { border:1px solid var(--sunset); background:var(--sunset); color:#fff; border-radius:9px; padding:8px 12px; font-size:12px; font-weight:800; cursor:pointer; }
.schedule-btn--ghost { background:transparent; color:var(--forest); border-color:var(--line); }
.schedule-copy { color:var(--ink-2); font-size:13px; margin-top:0; }
.schedule-options { display:grid; gap:10px; }
.schedule-options button { text-align:left; border:1px solid var(--line); background:#fff; border-radius:12px; padding:14px; cursor:pointer; }
.schedule-options button:hover { border-color:var(--sunset); background:var(--sunset-soft); }
.schedule-options b, .schedule-options small { display:block; }
.schedule-options b { color:var(--ink); font-size:14px; }
.schedule-options small { margin-top:4px; color:var(--ink-2); font-size:12px; }
.decision-panel { display:flex; justify-content:space-between; gap:20px; margin:0 0 16px; padding:14px 16px; border-left:3px solid var(--sunset); background:var(--sunset-soft); border-radius:0 12px 12px 0; }
.decision-panel strong { display:block; margin-top:4px; color:var(--ink); }
.decision-panel p { max-width:480px; margin:0; color:var(--ink-2); font-size:12px; line-height:1.6; }
.decision-kicker { color:var(--sunset); font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:.08em; }
@media (max-width: 700px) { .agent-step-grid { grid-template-columns:repeat(2,1fr); } .decision-panel { display:block; } .decision-panel p { margin-top:8px; } .plan-head { align-items:flex-start; } }

/* 当天详细行程与上方卡片/概览的距离 */
.day-narrative { margin-top: 14px; }

/* markdown 正文里地点/餐厅名称橙色高亮 */
.plan-md :deep(.nav-hl) { color: var(--sunset); font-weight: 800; }

/* ─── 结构化行程卡（地点橙色 + 地点/餐厅配图） ─────────────── */
.itin-cards { display: flex; flex-direction: column; gap: 12px; }

.itin-card {
  display: flex; gap: 14px; align-items: stretch;
  background: #fff; border: 1px solid var(--line);
  border-radius: 14px; overflow: hidden;
  box-shadow: var(--shadow-soft);
}
.itin-card--meal { border-color: #ecdfcf; }

.itin-photo {
  width: 118px; min-width: 118px; min-height: 96px;
  background: var(--sunset-soft);
}
.itin-photo img {
  width: 100%; height: 100%; min-height: 96px;
  object-fit: cover; display: block;
}

.itin-info { flex: 1; min-width: 0; padding: 12px 14px 12px 0; }
.itin-title { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.itin-time {
  flex-shrink: 0; font-size: 11px; font-weight: 800; color: #fff;
  background: var(--ink-3); padding: 2px 9px; border-radius: 20px;
}
/* 景点：橙色醒目 */
.itin-place {
  margin: 0; font-size: 16px; font-weight: 800; color: var(--sunset);
  line-height: 1.3;
}
/* 餐厅：同样橙色（与景点一致，用户要求统一点缀色） */
.itin-food {
  margin: 0; font-size: 15px; font-weight: 800; color: var(--sunset); line-height: 1.3;
}
.itin-loc { color: var(--ink-2); font-size: 12px; margin-bottom: 3px; }
.itin-note { color: #6e7d77; font-size: 12px; line-height: 1.6; margin-bottom: 6px; }

.itin-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.itin-meta span {
  font-size: 11px; color: var(--ink-2);
  background: var(--roam-soft); border: 1px solid #d5e4da;
  padding: 2px 9px; border-radius: 20px;
}
.itin-card--meal .itin-meta span { background: var(--sunset-soft); border-color: #f2c4a4; }

@media (max-width: 700px) {
  .itin-card { flex-direction: column; }
  .itin-photo { width: 100%; min-width: 100%; min-height: 120px; }
  .itin-photo img { min-height: 120px; }
  .itin-info { padding: 8px 14px 14px; }
}

/* ─── 兼容：传统同步结果 ──────────────────────────────────── */
.days { display: flex; gap: 8px; margin: 28px 0 16px; }

.days button {
  border: 0;
  background: var(--wash);
  border-radius: 11px;
  padding: 9px 17px;
  color: #788780;
  cursor: pointer;
}

.days small, .days b { display: block; }
.days small { font-size: 8px; letter-spacing: 0.1em; }
.days b { font-size: 18px; }
.days button.active { background: var(--forest); color: white; }

.day-card {
  border: 1px solid var(--line);
  border-radius: 18px;
  padding: 25px;
}

.day-title p { color: var(--sunset); font-size: 11px; font-weight: 800; margin: 0; }
.day-title h3 { margin: 7px 0; font-size: 21px; }
.day-title > b { font-size: 13px; color: var(--roam); }

.timeline { padding: 20px 0 7px; }

.timeline-item {
  display: grid;
  grid-template-columns: 32px 1fr;
  gap: 12px;
  padding-bottom: 18px;
  position: relative;
}

.timeline-item > span {
  width: 25px;
  height: 25px;
  background: var(--roam-soft);
  color: var(--roam);
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 800;
}

.timeline-item h4, .meal h4 { margin: 0 0 5px; font-size: 14px; }
.timeline-item p, .meal p { margin: 0; color: #6e7d77; font-size: 12px; line-height: 1.6; }
.timeline-item small, .meal small { color: var(--sunset); font-size: 11px; }

.meal-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }

.meal {
  background: var(--sunset-soft);
  border-radius: 12px;
  padding: 15px;
}

.meal > span { font-size: 10px; font-weight: 800; color: var(--sunset); }

/* AI 内容样式 */
.ai-content {
  margin-top: 20px;
  padding: 25px;
  background: #fff;
  border-radius: 18px;
  border: 1px solid var(--line);
  line-height: 1.8;
  font-size: 14px;
  color: #333;
}

.ai-content :deep(h1),
.ai-content :deep(h2),
.ai-content :deep(h3) {
  color: var(--forest);
  margin: 1em 0 0.5em;
  font-weight: 700;
}

.ai-content :deep(h1) { font-size: 24px; }
.ai-content :deep(h2) { font-size: 20px; }
.ai-content :deep(h3) { font-size: 16px; }

.ai-content :deep(p) { margin: 0.8em 0; }
.ai-content :deep(ul), .ai-content :deep(ol) { padding-left: 24px; margin: 0.8em 0; }
.ai-content :deep(li) { margin: 0.4em 0; }
.ai-content :deep(strong) { color: var(--sunset); font-weight: 600; }

.stream-error {
  margin-top: 16px;
  padding: 14px 18px;
  background: var(--sunset-soft);
  border: 1px solid #F2C4B4;
  border-radius: 8px;
  color: #c0392b;
  font-size: 14px;
}

@media (max-width: 700px) {
  .hero { padding: 45px 0 30px; }
  .trust { gap: 9px; font-size: 9px; }
  .planner-card, .result { width: calc(100% - 26px); padding: 23px 18px; }
  .form-grid, .choice-row, .tips, .generate-row { grid-template-columns: 1fr; }
  .result-head { display: block; }
  .budget { margin-top: 15px; }
  .meal-grid { grid-template-columns: 1fr; }
  .days { overflow: auto; }
  .days button { flex-shrink: 0; }
}
</style>
