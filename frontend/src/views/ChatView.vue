<script setup lang="ts">
import { ref, computed, watch, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCurrentUserId, saveAiPlan } from '@/api/user'
import { createCommerceOrder, listCommerceOffers, payCommerceOrder, recordCommerceClick, type CommerceOffer } from '@/api/commerce'
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
const commerceOffers = ref<CommerceOffer[]>([])
const commerceLoading = ref(false)
async function loadCommerceOffers(planId: string) {
  commerceLoading.value = true
  try { commerceOffers.value = await listCommerceOffers(planId, destination.value) } catch { commerceOffers.value = [] }
  finally { commerceLoading.value = false }
}
async function buyCommerceOffer(offer: CommerceOffer) {
  const planId = travelPlan.value?.planId
  if (!planId) return
  try {
    await recordCommerceClick(planId, offer.offer_id)
    const order = await createCommerceOrder(planId, offer.offer_id)
    await payCommerceOrder(order.order_no)
    ElMessage.success('沙箱订单已支付，权益已写入账户')
  } catch { ElMessage.error('订单暂时无法完成，请稍后重试') }
}
async function addToSchedule(target: 'inspiration' | 'journey') {
  if (scheduling.value) return
  scheduling.value = true
  try {
    const currentUserId = getCurrentUserId()
    if (!currentUserId) throw new Error('current user is not ready')
    const result: any = travelPlan.value || { destination: destination.value }
    const noteContent = {
      overview: { destination: result.destination || destination.value, preferences: preferenceSummary.value },
      strategies: agentSteps.value.map(s => ({ title: s.title, text: s.text })),
      budget: { limit: budget.value, knownTotal: totalPlanCost.value, total: confirmedPlanCost.value, hasUnknownCosts: hasUnknownPlanCost.value, items: [] },
      days: structuredDays.value.map((items: any[], i: number) => ({ day: i + 1, items })),
      reminders: [], packing: [], reflections: {}
    }
    let planMarkdown = ''
    if (target === 'inspiration') {
      planMarkdown = structuredDays.value.map((items: any[], i: number) => {
        const rows = items.map((a: any) => `- **${a.time || ''} ${a.name || ''}**\n  - 地点：${a.location || ''}\n  - 交通：${a.transport || ''}\n  - 备注：${a.notes || ''}\n  - 费用：${formatCost(a.cost)}`).join('\n')
        return `## Day ${i + 1}\n${rows}`
      }).join('\n\n')
    }
    const start = new Date(); start.setDate(start.getDate() + 1)
    const end = new Date(start); end.setDate(start.getDate() + days.value - 1)
    const points = structuredDays.value.flat().filter((a: any) => a.type === 'sightseeing').map((a: any, i: number) => {
      const dayIndex = Math.max(0, Number(a.day ?? a.dayNo ?? 1) - 1)
      const visitDate = new Date(start)
      visitDate.setDate(start.getDate() + dayIndex)
      return { name: a.name, visitDate: visitDate.toISOString().slice(0, 10), description: a.notes || a.location, sortOrder: i }
    })
    const firstImage = structuredDays.value.flat().find((a: any) => a.image || a.imageUrl)?.image || structuredDays.value.flat().find((a: any) => a.imageUrl)?.imageUrl || ''
    await saveAiPlan({
      planId: result.planId || travelPlan.value?.planId || undefined,
      target,
      note: { userId: currentUserId, title: `${result.destination || destination.value} · ${days.value}日旅行计划`, destination: result.destination || destination.value, noteType: target, sourceType: 'agent', totalDays: days.value, travelers: travelers.value, budget: budget.value, contentJson: JSON.stringify(noteContent), status: target === 'journey' ? 'planned' : 'draft' },
      inspiration: target === 'inspiration' ? { userId: currentUserId, name: result.destination || destination.value, imageUrl: firstImage, description: planMarkdown || 'Roamly 为你生成的旅行方案', quote: preferenceSummary.value, tags: 'AI规划,旅行计划', estimatedBudget: confirmedPlanCost.value, status: 0 } : undefined,
      journey: target === 'journey' ? { userId: currentUserId, destination: result.destination || destination.value, totalDays: days.value, startDate: start.toISOString().slice(0, 10), endDate: end.toISOString().slice(0, 10), totalCost: confirmedPlanCost.value, summary: preferenceSummary.value, travelType: travelStyle.value, status: 1 } : undefined,
      points: target === 'journey' ? points : []
    })
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
            planId: result.planId || '',
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
          if (result.planId) void loadCommerceOffers(result.planId)

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
    <section class="page-head">
      <div class="page-head__left">
        <img class="page-head__logo" :src="roamlySymbol" alt="Roamly" />
        <div>
          <h1>快速旅行规划</h1>
          <p class="sub">告诉我目的地、天数和预算，Roamly 会自动串联每日路线、餐饮与预算。</p>
        </div>
      </div>
      <div class="page-head__actions">
        <button class="btn-ghost" @click="router.push('/explore')">探索灵感 →</button>
      </div>
    </section>

    <section id="planner" class="planner-card">
      <div class="planner-title">
        <div>
          <h2>行程参数</h2>
          <p class="planner-subtitle">确认基本信息与偏好，点击右侧按钮立即生成</p>
        </div>
        <div class="trust-inline">
          <span>✦ 区域串联</span>
          <span>◌ 预算可控</span>
          <span>⌁ 避坑提醒</span>
        </div>
      </div>

      <div class="form-grid">
        <label class="field destination">
          <span>目的地</span>
          <input v-model="destination" maxlength="30" placeholder="例如：杭州" />
        </label>
        <label class="field">
          <span>旅行天数</span>
          <div class="number-control">
            <button type="button" @click="days = Math.max(1, days - 1)">−</button>
            <b>{{ days }} 天</b>
            <button type="button" @click="days = Math.min(14, days + 1)">+</button>
          </div>
        </label>
        <label class="field">
          <span>总预算（元）</span>
          <div class="currency-input">
            <em>¥</em>
            <input v-model.number="budget" type="number" min="300" max="200000" />
          </div>
        </label>
        <label class="field">
          <span>同行人数</span>
          <div class="number-control">
            <button type="button" @click="travelers = Math.max(1, travelers - 1)">−</button>
            <b>{{ travelers }} 人</b>
            <button type="button" @click="travelers = Math.min(12, travelers + 1)">+</button>
          </div>
        </label>
      </div>

      <div class="choice-row">
        <div class="choice-group">
          <span>旅行节奏</span>
          <div class="chips">
            <button
              v-for="item in styles"
              :key="item"
              type="button"
              :class="{ selected: travelStyle === item }"
              @click="travelStyle = item"
            >{{ item }}</button>
          </div>
        </div>
        <div class="choice-group">
          <span>这次更想要</span>
          <div class="chips">
            <button
              v-for="item in interestOptions"
              :key="item"
              type="button"
              :class="{ selected: interests.includes(item) }"
              @click="interests.includes(item) ? interests = interests.filter(x => x !== item) : interests = [...interests, item]"
            >{{ item }}</button>
          </div>
        </div>
      </div>

      <div class="generate-bar">
        <div class="summary-preview">
          <span class="summary-label">当前配置</span>
          <span class="summary-text">{{ destination || '未填目的地' }} · {{ days }}天 · {{ preferenceSummary }}</span>
        </div>
        <div class="generate-row">
          <button
            v-if="streamStore.isStreaming"
            type="button"
            class="generate generate--cancel"
            @click="cancelStreaming"
          >
            <span>取消</span>
          </button>
          <button
            v-if="streamStore.error"
            type="button"
            class="generate generate--retry"
            @click="generateStream"
          >
            <span>重新生成</span>
            <i>↻</i>
          </button>
          <button
            type="button"
            class="generate generate--stream"
            :disabled="streamStore.isStreaming"
            @click="generateStream"
          >
            <span>{{ streamStore.isStreaming ? 'AI 正在为你规划行程…' : '✨ 生成专属旅行计划' }}</span>
            <i v-if="!streamStore.isStreaming">→</i>
            <i v-else class="spinner">⟳</i>
          </button>
        </div>
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
        <div v-if="commerceOffers.length" class="commerce-offers">
          <div class="commerce-offers__head"><span>为本次计划匹配的服务</span><small>{{ commerceLoading ? '加载中…' : '沙箱链路可验收' }}</small></div>
          <div class="commerce-offers__list">
            <button v-for="offer in commerceOffers" :key="offer.offer_id" class="commerce-offer" @click="buyCommerceOffer(offer)">
              <span><b>{{ offer.title }}</b><small>{{ offer.provider_code }} · {{ offer.offer_type }}</small></span>
              <strong>{{ offer.currency }} {{ offer.price }}</strong>
            </button>
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
  min-height: 100%;
  padding: 24px 24px 64px;
  background: #ffffff;
}

.page-head {
  width: min(1040px, 100%);
  margin: 0 auto 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-head__left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.page-head__logo {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border-radius: 10px;
}

.page-head h1 {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #1f2329;
  margin: 0;
  line-height: 1.25;
}

.sub {
  margin: 4px 0 0;
  color: #5f6368;
  line-height: 1.5;
  font-size: 13.5px;
}

.page-head__actions {
  flex-shrink: 0;
}

.eyebrow {
  color: #0b57d0;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
  margin: 0 0 6px;
}

.btn-ghost {
  border: 1px solid #dadce0;
  background: #ffffff;
  color: #1f2329;
  font-weight: 600;
  font-size: 13px;
  padding: 8px 16px;
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.16s ease, border-color 0.16s ease, color 0.16s ease;

  &:hover {
    background: #f0f4f9;
    border-color: #c2d7fa;
    color: #0b57d0;
  }
}

.planner-card,
.result {
  width: min(1040px, 100%);
  margin: 0 auto;
  background: #ffffff;
  border: 1px solid #e5e9f0;
  border-radius: 20px;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.03);
}

.planner-card {
  padding: 24px 28px;
}

.planner-title,
.result-head,
.day-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.planner-title h2,
.result-head h2 {
  font-size: 17px;
  font-weight: 700;
  color: #1f2329;
  margin: 0;
}

.planner-subtitle {
  margin: 3px 0 0;
  font-size: 12.5px;
  color: #80868b;
}

.trust-inline {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  span {
    background: #f4f6fa;
    color: #5f6368;
    font-size: 11.5px;
    font-weight: 500;
    padding: 4px 10px;
    border-radius: 999px;
  }
}

.form-grid {
  display: grid;
  grid-template-columns: 1.6fr repeat(3, 1fr);
  gap: 12px;
  margin: 20px 0;
}

.field {
  border: 1px solid #e2e7ee;
  background: #f8fafd;
  border-radius: 14px;
  padding: 11px 14px;
  display: block;
  transition: border-color 0.16s ease, background 0.16s ease;

  &:focus-within {
    border-color: #0b57d0;
    background: #ffffff;
  }
}

.field span,
.choice-group > span {
  font-size: 11.5px;
  color: #5f6368;
  font-weight: 600;
  display: block;
  margin-bottom: 6px;
}

.field input {
  width: 100%;
  border: 0;
  background: transparent;
  font-size: 15px;
  font-weight: 600;
  color: #1f2329;
  outline: 0;
}

.currency-input {
  display: flex;
  align-items: center;
  gap: 4px;

  em {
    font-style: normal;
    font-size: 14px;
    font-weight: 600;
    color: #5f6368;
  }
}

.number-control {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.number-control button {
  border: 1px solid #e2e7ee;
  background: #ffffff;
  border-radius: 8px;
  width: 26px;
  height: 26px;
  color: #1f2329;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;

  &:hover {
    background: #e8f0fe;
    border-color: #c2d7fa;
    color: #0b57d0;
  }
}

.number-control b {
  font-size: 14px;
  font-weight: 600;
  color: #1f2329;
}

.choice-row {
  border-top: 1px solid #eef1f5;
  padding-top: 18px;
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: 24px;
}

.chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.chips button {
  border: 1px solid #e2e7ee;
  background: #ffffff;
  padding: 6px 14px;
  border-radius: 999px;
  color: #5f6368;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    background: #f0f4f9;
    color: #1f2329;
  }
}

.chips button.selected {
  background: #e8f0fe;
  color: #0b57d0;
  border-color: #c2d7fa;
  font-weight: 600;
}

.generate-bar {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid #eef1f5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.summary-preview {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1 1 260px;
}

.summary-label {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  color: #0b57d0;
  background: #e8f0fe;
  padding: 3px 9px;
  border-radius: 999px;
}

.summary-text {
  font-size: 13px;
  color: #5f6368;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.generate-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.generate {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 999px;
  padding: 11px 24px;
  font-weight: 600;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.16s ease, transform 0.16s ease;
}

.generate:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.generate i {
  font-size: 16px;
  font-style: normal;
}

.generate--stream {
  background: #0b57d0;
  color: #ffffff;

  &:hover:not(:disabled) {
    background: #0842a0;
    transform: translateY(-1px);
  }
}

.generate--cancel {
  background: #f0f4f9;
  color: #3c4043;
  padding: 11px 18px;

  &:hover {
    background: #e4e9f2;
  }
}

.generate--retry {
  background: #e8f0fe;
  color: #0b57d0;
  padding: 11px 18px;

  &:hover {
    background: #d2e3fc;
  }
}

.spinner {
  animation: spin 1s linear infinite;
  display: inline-block;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.result {
  margin-top: 22px;
  padding: 28px 32px;
}

.stream-result {
  border: 1px solid #d2e3fc;
  background: #ffffff;
}

.result-head > div > p:not(.eyebrow) {
  color: #5f6368;
  font-size: 13px;
  max-width: 580px;
  line-height: 1.65;
  margin: 6px 0 0;
}

.budget {
  min-width: 170px;
  background: #f8fafd;
  border: 1px solid #e5e9f0;
  padding: 14px 16px;
  border-radius: 14px;
}

.budget span,
.budget small {
  display: block;
  font-size: 11.5px;
  color: #5f6368;
}

.budget b {
  display: block;
  font-size: 24px;
  font-weight: 700;
  color: #0b57d0;
  margin: 4px 0;
}

/* ─── 商业化匹配服务 ─────────────────────────────────────────── */
.commerce-offers {
  margin: 14px 20px 16px;
  padding: 14px 16px;
  border: 1px solid #e5e9f0;
  border-radius: 14px;
  background: #f8fafd;
}

.commerce-offers__head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-bottom: 10px;
  color: #1f2329;
  font-size: 13px;
  font-weight: 700;
}

.commerce-offers__head small {
  color: #5f6368;
  font-weight: 500;
}

.commerce-offers__list {
  display: grid;
  gap: 8px;
}

.commerce-offer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 11px 14px;
  border: 1px solid #e2e7ee;
  border-radius: 12px;
  background: #ffffff;
  color: #1f2329;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, transform 0.15s ease;

  &:hover {
    border-color: #0b57d0;
    transform: translateY(-1px);
  }
}

.commerce-offer span {
  display: grid;
  gap: 3px;
}

.commerce-offer small {
  color: #5f6368;
  font-weight: 500;
}

.commerce-offer strong {
  color: #0b57d0;
  white-space: nowrap;
}

/* ─── 流式打字效果 ─────────────────────────────────────────── */
.stream-text {
  margin-top: 16px;
  padding: 16px 20px;
  background: #f8fafd;
  border: 1px solid #e5e9f0;
  border-radius: 14px;
  line-height: 1.75;
  color: #1f2329;
  font-size: 14px;
  max-height: 300px;
  overflow-y: auto;
}

/* ─── Agent 状态卡片 ───────────────────────────────────────── */
.agent-status {
  margin: 18px 0 20px;
  padding: 16px 18px;
  border: 1px solid #e5e9f0;
  border-radius: 16px;
  background: #f8fafd;
}

.agent-status-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: #1f2329;
  font-size: 13.5px;
  font-weight: 700;
}

.agent-status-head small {
  color: #0b57d0;
  font-weight: 600;
}

.agent-step-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-top: 14px;
}

.agent-step {
  display: flex;
  gap: 10px;
  min-width: 0;
}

.agent-step-icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: 9px;
  background: #e8f0fe;
  color: #0b57d0;
  font-weight: 700;
  font-size: 13px;
}

.agent-step b {
  font-size: 12.5px;
  color: #1f2329;
}

.agent-step p {
  margin: 3px 0 0;
  color: #5f6368;
  font-size: 11.5px;
  line-height: 1.45;
}

/* ─── 计划区域 ─────────────────────────────────────────────── */
.plan-section {
  margin-top: 18px;
  background: #ffffff;
  border: 1px solid #e5e9f0;
  border-radius: 16px;
  overflow: hidden;
}

.plan-head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid #eef1f5;
}

.plan-head h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #1f2329;
}

.plan-preferences {
  margin: 4px 0 0;
  color: #5f6368;
  font-size: 12.5px;
}

.plan-cost {
  color: #0b57d0;
  background: #e8f0fe;
  font-size: 12px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 999px;
  white-space: nowrap;
}

.plan-cost--pending {
  background: #fef7e0;
  color: #b06000;
}

.plan-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.schedule-btn {
  border: 1px solid #0b57d0;
  background: #0b57d0;
  color: #ffffff;
  border-radius: 999px;
  padding: 7px 14px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover:not(:disabled) {
    background: #0842a0;
  }
}

.schedule-btn--ghost {
  background: #ffffff;
  color: #1f2329;
  border-color: #dadce0;

  &:hover:not(:disabled) {
    background: #f0f4f9;
    border-color: #c2d7fa;
    color: #0b57d0;
  }
}

.decision-panel {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  margin: 14px 20px;
  padding: 14px 16px;
  border-left: 3px solid #0b57d0;
  background: #f8fafd;
  border-radius: 0 12px 12px 0;
}

.decision-panel strong {
  display: block;
  margin-top: 4px;
  color: #1f2329;
  font-size: 14px;
}

.decision-panel p {
  max-width: 480px;
  margin: 0;
  color: #5f6368;
  font-size: 12.5px;
  line-height: 1.6;
}

.decision-kicker {
  color: #0b57d0;
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

/* 按天 Tabs */
.tab-bar {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 12px 20px;
  border-bottom: 1px solid #eef1f5;
  scrollbar-width: none;
}

.tab-bar::-webkit-scrollbar {
  display: none;
}

.tab-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 8px 16px;
  border-radius: 12px;
  border: 1px solid transparent;
  background: #f4f6fa;
  cursor: pointer;
  transition: all 0.16s ease;
  flex-shrink: 0;
  min-width: 66px;

  &:hover {
    background: #eef3fc;
  }
}

.tab-label {
  font-size: 13px;
  font-weight: 600;
  color: #5f6368;
}

.tab-temp {
  font-size: 11px;
  color: #80868b;
}

.tab-btn.active {
  border-color: #c2d7fa;
  background: #e8f0fe;
}

.tab-btn.active .tab-label,
.tab-btn.active .tab-temp {
  color: #0b57d0;
}

.tab-content {
  padding: 16px 20px 20px;
}

.day-weather-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.weather-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: #f0f4f9;
  border: 1px solid #e2e7ee;
  color: #3c4043;
  font-size: 12px;
  font-weight: 500;
  padding: 4px 10px;
  border-radius: 999px;
}

.budget-chip {
  background: #fef7e0;
  border-color: #fde293;
  color: #b06000;
}

/* Markdown 内容 */
.plan-md {
  background: #f8fafd;
  border: 1px solid #e5e9f0;
  border-radius: 14px;
  padding: 18px 20px;
  font-size: 13.5px;
  line-height: 1.75;
  color: #1f2329;

  :deep(h2) {
    font-size: 16.5px;
    font-weight: 700;
    color: #1f2329;
    margin: 0 0 10px;
    border-bottom: 1px solid #e5e9f0;
    padding-bottom: 6px;
  }
  :deep(h3) {
    font-size: 14px;
    font-weight: 700;
    color: #3c4043;
    margin: 14px 0 6px;
  }
  :deep(p) {
    margin: 0 0 8px;
  }
  :deep(strong) {
    color: #0b57d0;
  }
  :deep(ul),
  :deep(ol) {
    margin: 6px 0 8px;
    padding-left: 20px;
  }
  :deep(li) {
    margin-bottom: 4px;
  }
}

.day-narrative {
  margin-top: 14px;
}

.plan-md :deep(.nav-hl) {
  color: #0b57d0;
  font-weight: 700;
}

/* ─── 结构化行程卡 ─────────────────────────────────────────── */
.itin-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.itin-card {
  display: flex;
  gap: 14px;
  align-items: stretch;
  background: #ffffff;
  border: 1px solid #e5e9f0;
  border-radius: 14px;
  overflow: hidden;
}

.itin-card--meal {
  border-color: #e5e9f0;
}

.itin-photo {
  width: 118px;
  min-width: 118px;
  min-height: 96px;
  background: #f0f4f9;
}

.itin-photo img {
  width: 100%;
  height: 100%;
  min-height: 96px;
  object-fit: cover;
  display: block;
}

.itin-info {
  flex: 1;
  min-width: 0;
  padding: 12px 14px;
}

.itin-photo + .itin-info {
  padding-left: 0;
}

.itin-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.itin-time {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: #0b57d0;
  background: #e8f0fe;
  padding: 2px 9px;
  border-radius: 999px;
}

.itin-place,
.itin-food {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #1f2329;
  line-height: 1.35;
}

.itin-loc {
  color: #5f6368;
  font-size: 12px;
  margin-bottom: 3px;
}

.itin-note {
  color: #5f6368;
  font-size: 12.5px;
  line-height: 1.6;
  margin-bottom: 6px;
}

.itin-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.itin-meta span {
  font-size: 11px;
  color: #5f6368;
  background: #f4f6fa;
  border: 1px solid #e5e9f0;
  padding: 2px 9px;
  border-radius: 999px;
}

/* ─── 兼容：传统同步结果 ──────────────────────────────────── */
.days {
  display: flex;
  gap: 8px;
  margin: 24px 0 16px;
}

.days button {
  border: 1px solid #e2e7ee;
  background: #f8fafd;
  border-radius: 12px;
  padding: 8px 16px;
  color: #5f6368;
  cursor: pointer;
}

.days small,
.days b {
  display: block;
}

.days small {
  font-size: 9px;
  letter-spacing: 0.1em;
}

.days b {
  font-size: 16px;
}

.days button.active {
  background: #0b57d0;
  border-color: #0b57d0;
  color: #ffffff;
}

.day-card {
  border: 1px solid #e5e9f0;
  border-radius: 16px;
  padding: 22px;
}

.day-title p {
  color: #0b57d0;
  font-size: 11px;
  font-weight: 700;
  margin: 0;
}

.day-title h3 {
  margin: 6px 0;
  font-size: 20px;
  color: #1f2329;
}

.day-title > b {
  font-size: 13px;
  color: #0b57d0;
}

.timeline {
  padding: 18px 0 6px;
}

.timeline-item {
  display: grid;
  grid-template-columns: 32px 1fr;
  gap: 12px;
  padding-bottom: 16px;
}

.timeline-item > span {
  width: 24px;
  height: 24px;
  background: #e8f0fe;
  color: #0b57d0;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
}

.timeline-item h4,
.meal h4 {
  margin: 0 0 4px;
  font-size: 14px;
  color: #1f2329;
}

.timeline-item p,
.meal p {
  margin: 0;
  color: #5f6368;
  font-size: 12.5px;
  line-height: 1.6;
}

.timeline-item small,
.meal small {
  color: #0b57d0;
  font-size: 11.5px;
}

.meal-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.meal {
  background: #f8fafd;
  border: 1px solid #e5e9f0;
  border-radius: 12px;
  padding: 14px;
}

.meal > span {
  font-size: 11px;
  font-weight: 700;
  color: #0b57d0;
}

.ai-content {
  margin-top: 18px;
  padding: 22px;
  background: #f8fafd;
  border-radius: 16px;
  border: 1px solid #e5e9f0;
  line-height: 1.75;
  font-size: 14px;
  color: #1f2329;
}

.ai-content :deep(h1),
.ai-content :deep(h2),
.ai-content :deep(h3) {
  color: #1f2329;
  margin: 1em 0 0.5em;
  font-weight: 700;
}

.ai-content :deep(p) { margin: 0.7em 0; }
.ai-content :deep(ul), .ai-content :deep(ol) { padding-left: 22px; margin: 0.7em 0; }
.ai-content :deep(li) { margin: 0.35em 0; }
.ai-content :deep(strong) { color: #0b57d0; font-weight: 600; }

.stream-error {
  margin-top: 16px;
  padding: 14px 18px;
  background: #fce8e6;
  border: 1px solid #f2b8b5;
  border-radius: 12px;
  color: #c5221f;
  font-size: 13.5px;
}

@media (max-width: 700px) {
  .hero { padding: 28px 0 24px; }
  .planner-card, .result { width: calc(100% - 24px); padding: 20px 16px; }
  .form-grid, .choice-row { grid-template-columns: 1fr; }
  .agent-step-grid { grid-template-columns: repeat(2, 1fr); }
  .decision-panel { display: block; }
  .decision-panel p { margin-top: 8px; }
  .result-head { display: block; }
  .budget { margin-top: 14px; }
  .meal-grid { grid-template-columns: 1fr; }
  .days { overflow: auto; }
  .days button { flex-shrink: 0; }
  .itin-card { flex-direction: column; }
  .itin-photo { width: 100%; min-width: 100%; min-height: 120px; }
  .itin-photo img { min-height: 120px; }
  .itin-photo + .itin-info { padding: 10px 14px 14px; }
}
</style>
