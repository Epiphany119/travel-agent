import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { watchEffect } from 'vue'
import { renderMarkdown as renderSafeMarkdown } from '@/utils/markdown'
import { subscribeA2AStream, fetchPoiImages } from '@/api/agent'
import { fetchItineraryWeather } from '@/api/weather'
import { useStreamStore } from './stream'
import { useRouter } from 'vue-router'
import { useRightPanelStore } from './rightPanel'
import { normalizeOptionalCost } from '@/utils/planValues'

export interface ChatMsg {
  role: 'agent' | 'user' | 'tool' | 'info'
  content: string
}

const QUESTIONS: Array<{ field: string; prompt: string; options?: string[]; inputType?: string }> = [
  { field: 'destination', prompt: '你想去哪里旅行？比如：杭州、成都、上海…' },
  { field: 'madeAt', prompt: '这次你更想要什么旅行节奏？', options: ['轻松漫游', '紧凑高效', '慢节奏深度'] },
  { field: 'days', prompt: '打算玩几天？', options: ['1', '2', '3', '4', '5'] },
  { field: 'interests', prompt: '对什么主题感兴趣？比如：美食、人文、自然、摄影、购物…（可多个，逗号分隔）' },
  { field: 'budget', prompt: '这次出行总预算大概是多少元？', inputType: 'number' },
]
const TOTAL_STEPS = QUESTIONS.length

/** 天气文案 -> 图标 */
function weatherIcon(text = ''): string {
  if (/雷|thunder|storm/i.test(text)) return '⛈️'
  if (/雪|snow/i.test(text)) return '❄️'
  if (/雨|rain|drizzle|shower/i.test(text)) return '🌧️'
  if (/雾|霾|fog|mist|haze/i.test(text)) return '🌫️'
  if (/云|阴|cloud|overcast/i.test(text)) return '☁️'
  if (/晴|sunny|clear/i.test(text)) return '☀️'
  return '🌤️'
}

/**
 * 后端 WeatherResult -> UI 可直接渲染的结构。
 * 后端是 { city, success, data: { forecast: [{ date, text, tempMin, tempMax }] } }，
 * 之前原样塞进 plan.weather，UI 找不到 icon/text/tempMin，才显示成「天气 -- ~ --℃」。
 */
function normalizeWeather(raw: any) {
  if (!raw || typeof raw !== 'object') return null
  const data = raw.data && typeof raw.data === 'object' ? raw.data : raw
  const list = Array.isArray(data.forecast)
    ? data.forecast
    : Array.isArray(data.weather)
      ? data.weather
      : Array.isArray(data)
        ? data
        : []
  const forecast = list.map((d: any) => {
    const text = String(d?.text ?? d?.weatherDesc ?? '')
    const tempMin = d?.tempMin ?? d?.min ?? null
    const tempMax = d?.tempMax ?? d?.max ?? null
    return {
      date: d?.date || '',
      text,
      icon: weatherIcon(text),
      tempMin,
      tempMax,
      temp: tempMax ?? d?.temp ?? null,
    }
  })
  if (!forecast.length && raw.success === false) return null
  const first = forecast[0]
  return {
    city: data.city || raw.city || '',
    success: raw.success !== false && data.success !== false,
    icon: first?.icon || '☀️',
    text: first?.text || '',
    tempMin: first?.tempMin ?? null,
    tempMax: first?.tempMax ?? null,
    forecast,
  }
}

export const useAgentSessionStore = defineStore('agentSession', () => {
  // ── 会话状态（两个宿主实例共享） ──────────────────────────────
  const loading = ref(false)
  const sending = ref(false)
  const input = ref('')
  const messages = ref<ChatMsg[]>([])
  const done = ref(false)
  const plan = ref<any>(null)
  const activeDay = ref(0)
  // 每个地点保留多个候选图，首图失效时 PlanPane 会自动切换下一张。
  const imageMap = ref<Record<string, string[]>>({})

  // 问卷进度
  const stepIndex = ref(0)
  const answers = ref<Record<string, string>>({})

  let cancelStream: (() => void) | null = null

  // ── 计算属性（可被右栏 / 中间分别消费） ──────────────────────
  const currentQuestion = computed(() => QUESTIONS[stepIndex.value])
  const optionChips = computed(() => currentQuestion.value?.options || [])

  const currentActivities = computed(
    () => plan.value?.dayPlans?.[activeDay.value]?.activities || []
  )

  // 只把工具返回的真实、非负金额纳入已核验费用；null/非法值保持“待确认”。
  const verifiedPlanCost = computed<number | null>(() => {
    const activities = (plan.value?.dayPlans || []).flatMap((day: any) => day.activities || [])
    if (!activities.length || activities.some((activity: any) => normalizeOptionalCost(activity?.cost) === null)) {
      return null
    }
    return activities.reduce((sum: number, activity: any) =>
      sum + (normalizeOptionalCost(activity?.cost) ?? 0), 0)
  })

  /** 按行程第几天取对应天气预报；没查到天气就不显示，不编造 */
  function resolveDayWeather(index: number, day: any) {
    const forecast = plan.value?.weather?.forecast || []
    const f = forecast[index]
    const text = f?.text || (typeof day?.weather === 'string' ? day.weather : '')
    if (!text && f?.tempMin == null && f?.tempMax == null) return null
    return {
      icon: f?.icon || weatherIcon(text),
      text,
      temp: f?.temp ?? f?.tempMax ?? '',
      tempMin: f?.tempMin ?? null,
      tempMax: f?.tempMax ?? null,
    }
  }

  const dayTabs = computed(() => {
    if (!plan.value) return []
    if (plan.value.dayPlans && plan.value.dayPlans.length) {
      return plan.value.dayPlans.map((d: any, index: number) => {
        const activities = d.activities || []
        const withTime = (a: any) => a.time
        const morning = activities.filter((a: any) => withTime(a) && a.time < '12:00')
        const afternoon = activities.filter((a: any) => withTime(a) && a.time >= '12:00' && a.time < '18:00')
        const evening = activities.filter((a: any) => withTime(a) && a.time >= '18:00')
        if (!morning.length && !afternoon.length && !evening.length && activities.length) {
          const third = Math.ceil(activities.length / 3)
          activities.slice(0, third).forEach((a: any) => morning.push(a))
          activities.slice(third, third * 2).forEach((a: any) => afternoon.push(a))
          activities.slice(third * 2).forEach((a: any) => evening.push(a))
        }
        const fmt = (a: any) =>
          `- **${a.name}**${a.location ? `（${a.location}）` : ''}${a.time ? ` · ${a.time}` : ''}${a.notes ? `\n  ${a.notes}` : ''}`
        const md = [
          `## ${d.theme || ('第' + d.day + '天')}`,
          d.date ? `**日期：** ${d.date}` : '',
          d.dailyBudget ? `**预算：** ¥${d.dailyBudget}` : '',
          morning.length ? `\n### 🌅 上午\n${morning.map(fmt).join('\n')}` : '',
          afternoon.length ? `\n### ☀️ 下午\n${afternoon.map(fmt).join('\n')}` : '',
          evening.length ? `\n### 🌙 晚上\n${evening.map(fmt).join('\n')}` : '',
        ].filter(Boolean).join('\n')
        return {
          label: `Day ${d.day || 1}`,
          subLabel: d.date || '',
          html: renderSafeMarkdown(md),
          weather: resolveDayWeather(index, d),
          budget: d.dailyBudget || null,
        }
      })
    }
    const days = plan.value.days || 1
    return Array.from({ length: days }, (_, i) => ({
      label: `Day ${i + 1}`, subLabel: '', html: '', weather: resolveDayWeather(i, {}), budget: null,
    }))
  })

  /** 完整总览（所有天）— 目前 UI 没用到，预留做导出功能 */
  const overview = computed(() => {
    if (!plan.value?.dayPlans?.length) return ''
    return plan.value.dayPlans.map((d: any) => {
      const fmt = (a: any) =>
        `- **${a.name}**${a.location ? `（${a.location}）` : ''}${a.time ? ` · ${a.time}` : ''}${a.notes ? `\n  ${a.notes}` : ''}`
      const acts = d.activities || []
      return [
        `## 第 ${d.day || '?'} 天 · ${d.theme || ''}`,
        d.date ? `**日期：** ${d.date}` : '',
        d.dailyBudget ? `**预算：** ¥${d.dailyBudget}` : '',
        acts.length ? `\n${acts.map(fmt).join('\n')}` : '',
      ].filter(Boolean).join('\n')
    }).join('\n\n')
  })

  /** ★ 关键：底部详细总结按 activeDay 只出当天内容 — 和 tab 对齐 */
  const dayOverview = computed(() => {
    if (!plan.value?.dayPlans?.length) return ''
    const d = plan.value.dayPlans[activeDay.value]
    if (!d) return ''
    const fmt = (a: any) =>
      `- **${a.name}**${a.location ? `（${a.location}）` : ''}${a.time ? ` · ${a.time}` : ''}${a.notes ? `\n  ${a.notes}` : ''}`
    const acts = d.activities || []

    const morning = acts.filter((a: any) => a.time && a.time < '12:00')
    const afternoon = acts.filter((a: any) => a.time && a.time >= '12:00' && a.time < '18:00')
    const evening = acts.filter((a: any) => a.time && a.time >= '18:00')
    const untimed = acts.filter((a: any) => !a.time)

    const sections: string[] = []
    if (morning.length) sections.push('🌅 **上午**\n' + morning.map(fmt).join('\n'))
    if (afternoon.length) sections.push('☀️ **下午**\n' + afternoon.map(fmt).join('\n'))
    if (evening.length) sections.push('🌙 **晚上**\n' + evening.map(fmt).join('\n'))
    if (untimed.length) sections.push('📌 **其他**\n' + untimed.map(fmt).join('\n'))

    return [
      `## 第 ${d.day || '?'} 天${d.theme ? ` · ${d.theme}` : ''}`,
      d.date ? `**日期：** ${d.date}` : '',
      d.dailyBudget ? `**预算：** ¥${d.dailyBudget}` : '',
      sections.length ? '' : (acts.length ? '\n' + acts.map(fmt).join('\n') : ''),
      sections.join('\n\n'),
    ].filter(Boolean).join('\n')
  })

  const globalWeather = computed(() => {
    if (!plan.value) return null
    const w = plan.value.weather
    if (!w || w.success === false || !w.forecast?.length) return null
    return w
  })

  // ── 方法 ────────────────────────────────────────────────────────
  function push(m: ChatMsg) { messages.value.push(m) }

  function greet() {
    push({ role: 'info', content: '✦ 我是你的私人旅行规划 Agent。按几个问题，我就能整合天气与景点数据，为你定制一份可执行的计划。' })
  }

  function reset() {
    cancelStream?.()
    cancelStream = null
    const streamStore = useStreamStore()
    streamStore.reset()
    loading.value = false
    sending.value = false
    messages.value = []
    plan.value = null
    done.value = false
    stepIndex.value = 0
    answers.value = {}
    imageMap.value = {}
    activeDay.value = 0
    greet()
    push({ role: 'agent', content: currentQuestion.value.prompt })
  }

  function startQuestionnaire() { reset() }

  function selectOption(opt: string) { input.value = opt; send() }

  function send() {
    // type="number" 的 v-model 会被 Vue 转成 number，统一成字符串再处理
    const text = String(input.value ?? '').trim()
    if (!text || sending.value) return
    sending.value = true
    push({ role: 'user', content: text })
    input.value = ''
    const field = currentQuestion.value.field
    answers.value[field] = text
    stepIndex.value++
    if (stepIndex.value < TOTAL_STEPS) {
      sending.value = false
      push({ role: 'agent', content: currentQuestion.value.prompt })
    } else {
      push({ role: 'tool', content: '🔧 正在获取天气与景点数据，整合编排中…' })
      runA2APlan()
    }
  }

  function normalizeActivity(activity: any) {
    return {
      ...activity,
      name: String(activity?.name || activity?.title || activity?.poiName || '未命名地点'),
      type: activity?.type || 'sightseeing',
      location: activity?.location || activity?.address || '',
      time: activity?.time || '',
      notes: activity?.notes || activity?.description || activity?.reason || '',
      cost: normalizeOptionalCost(activity?.cost ?? activity?.price),
      duration: Number(activity?.duration || 0),
    }
  }

  function normalizeDayPlan(day: any, index: number) {
    const activities = Array.isArray(day?.activities)
      ? day.activities
      : Array.isArray(day?.items)
        ? day.items
        : Array.isArray(day?.schedule)
          ? day.schedule
          : []
    return {
      ...day,
      day: Number(day?.day || day?.dayNumber || index + 1),
      date: day?.date || '',
      theme: day?.theme || day?.title || '',
      dailyBudget: Number(day?.dailyBudget || day?.budget || 0),
      activities: activities.map(normalizeActivity),
    }
  }

  function fallbackOverview(dayPlans: any[]) {
    return dayPlans.map((day, index) => {
      const activities = day.activities || []
      const lines = activities.length
        ? activities.map((activity: any) => `- **${activity.name}**${activity.location ? `（${activity.location}）` : ''}${activity.time ? ` · ${activity.time}` : ''}${activity.notes ? `\n  ${activity.notes}` : ''}`).join('\n')
        : '- 保留机动时间，根据当天体力、天气和现场情况灵活调整。'
      return [`## 第 ${day.day || index + 1} 天${day.theme ? ` · ${day.theme}` : ''}`, day.date ? `**日期：** ${day.date}` : '', lines].filter(Boolean).join('\n\n')
    }).join('\n\n')
  }

  /** 用免费的 Open-Meteo 补查行程天气；后端天气工具失败时也能显示 */
  async function refreshWeather() {
    if (!plan.value) return false
    const startDate = plan.value.dayPlans?.find((d: any) => d?.date)?.date
    const weather = await fetchItineraryWeather(
      plan.value.destination,
      startDate,
      Math.max(plan.value.dayPlans?.length || 3, 3),
    )
    if (!weather) return false
    plan.value = { ...plan.value, weather }
    return true
  }

  function runA2APlan() {
    const dest = answers.value['destination'] || '未知'
    const days = parseInt(answers.value['days'] || '3')
    const budget = parseInt(answers.value['budget'] || '3000')
    const travelStyle = answers.value['madeAt'] || '轻松漫游'
    const interests = (answers.value['interests'] || '美食,人文').split(/[,，]/).map(s => s.trim()).filter(Boolean)

    cancelStream?.()
    cancelStream = subscribeA2AStream(
      { destination: dest, days, budget, travelers: 1, travelStyle, interests },
      (event) => {
        console.log('[agentSession A2A]', event.name, event.data)
        switch (event.name) {
          case 'tool_call':
            push({ role: 'tool', content: '🔧 ' + (event.data?.source === 'weather' ? '天气数据获取中…' : '景点数据获取中…') })
            break
          case 'tool_result':
            push({ role: 'tool', content: '✅ ' + (event.data?.summary || '数据已缓存') })
            break
          case 'token': break
          case 'task_done': {
            const result = event.data?.data && typeof event.data.data === 'object' ? event.data.data : event.data
            if (!result) {
              push({ role: 'info', content: '⚠️ 计划生成失败，请重试。' })
              sending.value = false
              return
            }
            const rawDayPlans = Array.isArray(result.dayPlans)
              ? result.dayPlans
              : Array.isArray(result.days_plan)
                ? result.days_plan
                : Array.isArray(result.days)
                  ? result.days
                  : []
            const normalizedDayPlans = Array.from({ length: Math.max(days, rawDayPlans.length, 1) }, (_, index) => normalizeDayPlan(rawDayPlans[index] || {}, index))
            const overview = String(result.finalPlan || result.overview || '').trim() || fallbackOverview(normalizedDayPlans)

            const weather = normalizeWeather(result.weather)
            plan.value = {
              destination: result.destination || dest,
              days: normalizedDayPlans.length,
              budget,
              weather,
              overview,
              dayPlans: normalizedDayPlans,
            }
            done.value = true
            sending.value = false
            push({ role: 'info', content: '🎉 旅行计划已生成：' })
            activeDay.value = 0
            void loadActivityImages()
            // 后端天气工具失败/无数据时，用免费 Open-Meteo 按行程日期补查
            if (!weather?.forecast?.length) void refreshWeather()
            _fireOnDone()         // ★ 通知 UI 自动路由
            break
          }
          case 'error':
            push({ role: 'info', content: '⚠️ ' + (event.data?.message || '出现错误') })
            sending.value = false
            break
        }
      },
    )
  }

  async function loadActivityImages() {
    if (!plan.value?.dayPlans?.length) return
    const dest = plan.value.destination
    for (const dp of plan.value.dayPlans) {
      for (const activity of dp.activities || []) {
        if (!activity?.name || activity.type === 'rest' || imageMap.value[activity.name]) continue
        const candidates = [...new Set([
          activity.name,
          `${dest} ${activity.name}`,
          activity.location ? `${dest} ${activity.location}` : '',
        ].map((value) => value.trim()).filter(Boolean))]
        const urls: string[] = []
        for (const keyword of candidates) {
          try {
            const res = await fetchPoiImages(keyword, dest)
            for (const url of res?.imageUrls || []) {
              if (/^https?:\/\//i.test(url) && !urls.includes(url)) urls.push(url)
            }
            if (urls.length >= 3) break
          } catch { /* 尝试下一个关键词 */ }
        }
        imageMap.value[activity.name] = urls.slice(0, 3)
      }
    }
  }

  function dropImage(name: string) {
    const current = imageMap.value[name] || []
    imageMap.value = { ...imageMap.value, [name]: current.slice(1) }
  }

  function renderMarkdown(md: string) { return renderSafeMarkdown(md) }

  // ── 完成计划后自动跳转到中间展示（三栏中间态） ──
  // 由组件里调用 agent.onDone() 触发（避免在 store 里硬耦合 useRouter）
  let onDoneCallback: (() => void) | null = null
  function setOnDoneCb(cb: () => void | Promise<void>) { onDoneCallback = cb }
  function _fireOnDone() {
    // 等下一次 tick，确保 store 已经 commit
    queueMicrotask(() => onDoneCallback?.())
  }

  return {
    setOnDoneCb,

    // state
    loading, sending, input, messages, done, plan, activeDay, imageMap,
    stepIndex, answers,
    // computed
    currentQuestion, optionChips,
    currentActivities, verifiedPlanCost, dayTabs, overview, dayOverview, globalWeather,
    // methods
    startQuestionnaire, reset, selectOption, send, refreshWeather,
    push, greet, renderMarkdown,
    loadActivityImages,
    dropImage,
    // cleanup
    dispose() { cancelStream?.() },
  }
})
