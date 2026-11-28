<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAgentSessionStore } from '@/stores/agentSession'
import { addInspiration, getCurrentUserId } from '@/api/user'
import { fetchItineraryWeather } from '@/api/weather'
import {
  calculateDetailedBill,
  estimateTransport,
  optimizeDayTransport,
  fetchBookingPlaceholders,
  type DetailedBillResponse,
  type TransportEstimateResponse,
  type BookingPlaceholderResponse
} from '@/api/planning'
import {
  createBookingChecklist,
  confirmBookingItem,
  type BookingChecklistItem
} from '@/api/commerce'
import { formatCost, formatActivityCost, normalizeOptionalCost } from '@/utils/planValues'

const agent = useAgentSessionStore()

const savingInspiration = ref(false)
const savedInspiration = ref(false)
const weatherLoading = ref(false)

// ─── 第一/二阶段能力：出发地/日期联动、六大类账单、机酒占位选型与交通规划 ───
function defaultStartDate(): string {
  const fromPlan = agent.plan?.dayPlans?.find((d: any) => d?.date)?.date
  if (fromPlan) return String(fromPlan).slice(0, 10)
  const d = new Date()
  d.setDate(d.getDate() + 1)
  return d.toISOString().slice(0, 10)
}

const departureCity = ref('上海')
const startDate = ref(defaultStartDate())
const detailedBill = ref<DetailedBillResponse | null>(null)
const transportPlan = ref<TransportEstimateResponse | null>(null)
const bookingPlaceholders = ref<BookingPlaceholderResponse | null>(null)
const selectedTransportOfferId = ref('')
const selectedHotelOfferId = ref('')
const optimizingDay = ref(false)
const creatingChecklist = ref(false)
const createdBookings = ref<BookingChecklistItem[]>([])

const currentDayTransport = computed(() => transportPlan.value?.days?.[agent.activeDay] || null)
const selectedHotelNightlyRate = computed(() => {
  const h = bookingPlaceholders.value?.hotelOptions?.find((x) => x.offerId === selectedHotelOfferId.value)
  return h?.pricePerNight ?? bookingPlaceholders.value?.hotelOptions?.[1]?.pricePerNight ?? 380
})

async function submitBookingChecklist() {
  if (!bookingPlaceholders.value || creatingChecklist.value) return
  const t = bookingPlaceholders.value.transportOptions.find((x) => x.offerId === selectedTransportOfferId.value)
  const h = bookingPlaceholders.value.hotelOptions.find((x) => x.offerId === selectedHotelOfferId.value)
  const items: Array<{ category: string; offerId?: string; title: string; subtitle?: string; providerCode?: string; amount: number }> = []
  if (t) items.push({ category: t.category, offerId: t.offerId, title: t.title, subtitle: `${t.codeNo} · ${t.outboundSchedule}`, providerCode: t.dataSource, amount: t.totalPrice })
  if (h) items.push({ category: 'HOTEL', offerId: h.offerId, title: h.name, subtitle: `${h.checkInDate}~${h.checkOutDate} · ${h.roomType}`, providerCode: h.dataSource, amount: h.totalPrice })
  if (!items.length) return
  creatingChecklist.value = true
  try {
    createdBookings.value = await createBookingChecklist({
      destination: bookingPlaceholders.value.destination,
      startDate: bookingPlaceholders.value.startDate,
      endDate: bookingPlaceholders.value.endDate,
      items
    })
    ElMessage.success('已生成预订清单并写入 CPS 归因占位，可在「我的旅程」与「个人主页」查看')
  } catch {
    ElMessage.error('生成预订清单失败，请确认已登录且后端已重启')
  } finally {
    creatingChecklist.value = false
  }
}

async function confirmChecklistItem(bookingNo: string) {
  try {
    const updated = await confirmBookingItem(bookingNo)
    createdBookings.value = createdBookings.value.map((b) => (b.bookingNo === bookingNo ? updated : b))
    ElMessage.success('已完成占位预订确认，佣金已计入个人中心看板')
  } catch {
    ElMessage.error('确认失败，请稍后重试')
  }
}

function priceSourceLabel(source?: string) {
  switch (source) {
    case 'ITINERARY_CONFIRMED': return '行程核验'
    case 'PLACEHOLDER_QUOTE': return '占位报价'
    case 'PLACEHOLDER_ROUTING': return '路径占位'
    default: return '物价基准'
  }
}

async function recalculateBillWithSelection() {
  if (!agent.plan?.dayPlans?.length) return
  const flatActivities = agent.plan.dayPlans.flatMap((d: any) =>
    (d.activities || []).map((a: any) => ({
      type: a.type || 'sightseeing',
      name: a.name || '',
      cost: normalizeOptionalCost(a.cost)
    }))
  )
  const selectedTransport = bookingPlaceholders.value?.transportOptions?.find(
    (o) => o.offerId === selectedTransportOfferId.value
  )
  const selectedHotel = bookingPlaceholders.value?.hotelOptions?.find(
    (h) => h.offerId === selectedHotelOfferId.value
  )
  try {
    detailedBill.value = await calculateDetailedBill({
      destination: agent.plan.destination || '',
      departureCity: departureCity.value.trim(),
      startDate: startDate.value,
      days: agent.plan.days || agent.plan.dayPlans.length,
      travelers: 1,
      travelStyle: agent.answers?.madeAt || '轻松漫游',
      userBudget: Number(agent.plan.budget || 3000),
      activities: flatActivities,
      selectedTransportTotal: selectedTransport?.totalPrice,
      selectedHotelTotal: selectedHotel?.totalPrice
    })
  } catch {
    // 后端未重启时静默跳过
  }
}

async function loadEnrichment() {
  if (!agent.plan?.dayPlans?.length) {
    detailedBill.value = null
    transportPlan.value = null
    bookingPlaceholders.value = null
    return
  }
  const dest = agent.plan.destination || ''
  const dayStops = agent.plan.dayPlans.map((d: any, idx: number) => ({
    day: Number(d.day || idx + 1),
    stops: (d.activities || []).map((a: any) => ({
      name: a.name || '待定地点',
      location: a.location || '',
      type: a.type || 'sightseeing'
    }))
  }))

  const [transRes, bookRes] = await Promise.allSettled([
    estimateTransport({ destination: dest, days: dayStops }),
    fetchBookingPlaceholders({
      departureCity: departureCity.value.trim(),
      destination: dest,
      startDate: startDate.value,
      days: agent.plan.days || agent.plan.dayPlans.length,
      travelers: 1,
      travelStyle: agent.answers?.madeAt || '轻松漫游',
      budget: Number(agent.plan.budget || 3000)
    })
  ])

  if (transRes.status === 'fulfilled') transportPlan.value = transRes.value
  if (bookRes.status === 'fulfilled') {
    bookingPlaceholders.value = bookRes.value
    const preferredTransport = bookRes.value.transportOptions?.find((o) => o.recommended) || bookRes.value.transportOptions?.[0]
    if (preferredTransport) selectedTransportOfferId.value = preferredTransport.offerId
    const preferredHotel = bookRes.value.hotelOptions?.[1] || bookRes.value.hotelOptions?.[0]
    if (preferredHotel) selectedHotelOfferId.value = preferredHotel.offerId
  }
  await recalculateBillWithSelection()
}

function selectTransportOffer(offerId: string) {
  selectedTransportOfferId.value = offerId
  void recalculateBillWithSelection()
}

function selectHotelOffer(offerId: string) {
  selectedHotelOfferId.value = offerId
  void recalculateBillWithSelection()
}

async function optimizeCurrentDayRoute() {
  const dayObj = agent.plan?.dayPlans?.[agent.activeDay]
  const activities = dayObj?.activities || []
  if (activities.length <= 2 || optimizingDay.value) return
  optimizingDay.value = true
  try {
    const res = await optimizeDayTransport({
      destination: agent.plan.destination || '',
      dayPlan: {
        day: Number(dayObj.day || agent.activeDay + 1),
        stops: activities.map((a: any) => ({
          name: a.name || '待定地点',
          location: a.location || '',
          type: a.type || 'sightseeing'
        }))
      }
    })
    if (res.reordered && res.optimizedStops?.length) {
      const remaining = [...activities]
      const reordered: any[] = []
      for (const stop of res.optimizedStops) {
        const idx = remaining.findIndex((item) => item.name === stop.name && (item.location || '') === (stop.location || ''))
        if (idx >= 0) reordered.push(remaining.splice(idx, 1)[0])
      }
      reordered.push(...remaining)
      dayObj.activities = reordered
      if (transportPlan.value?.days) {
        transportPlan.value.days[agent.activeDay] = res.optimizedPlan
      }
      ElMessage.success(res.summary)
    } else {
      ElMessage.info(res.summary)
    }
  } catch {
    ElMessage.error('顺路优化暂时不可用，请确认后端已启动')
  } finally {
    optimizingDay.value = false
  }
}

watch(() => agent.plan, (nextPlan) => {
  if (nextPlan) {
    startDate.value = defaultStartDate()
    void loadEnrichment()
  }
}, { immediate: true })

let refreshTimer: number | null = null
watch([departureCity, startDate], () => {
  if (!agent.plan) return
  if (refreshTimer) window.clearTimeout(refreshTimer)
  refreshTimer = window.setTimeout(() => { void loadEnrichment() }, 280)
})

async function savePlanAsInspiration() {
  if (!agent.plan || savingInspiration.value) return
  savingInspiration.value = true
  try {
    const firstImage = Object.values(agent.imageMap).flat()[0] || ''
    await addInspiration({
      userId: getCurrentUserId(),
      name: agent.plan.destination || '未命名目的地',
      imageUrl: firstImage,
      quote: `${agent.plan.days || agent.dayTabs.length} 天 · ${agent.verifiedPlanCost == null ? '费用待确认' : `已核验费用下限 ${formatCost(agent.verifiedPlanCost)}`}`,
      description: agent.overview || 'Roamly AI 为你生成的旅行计划',
      tags: 'AI规划,旅行计划',
      estimatedBudget: agent.verifiedPlanCost,
      status: 0,
      priority: 1,
    })
    savedInspiration.value = true
  } catch { /* 页面主流程不因收藏失败中断 */ }
  finally { savingInspiration.value = false }
}

async function refreshWeather() {
  if (!agent.plan || weatherLoading.value) return
  weatherLoading.value = true
  try {
    const weather = await fetchItineraryWeather(
      agent.plan.destination,
      startDate.value,
      Math.max(agent.plan.dayPlans?.length || 3, 3),
    )
    if (weather) agent.plan.weather = weather
  } finally {
    weatherLoading.value = false
  }
}
</script>

<template>
  <div class="plan-wrapper">
    <div class="plan-head">
      <div>
        <h2>{{ agent.plan.destination }} · {{ agent.dayTabs.length }} 天</h2>
        <p v-if="agent.plan.budget">预算上限 ¥{{ agent.plan.budget?.toLocaleString() }}</p>
        <p>{{ agent.verifiedPlanCost == null ? '预计费用：待确认' : `已核验费用下限：${formatCost(agent.verifiedPlanCost)}` }}</p>
      </div>
      <div class="head-right">
        <button type="button" class="inspiration-btn" :disabled="savingInspiration || savedInspiration" @click="savePlanAsInspiration">
          {{ savingInspiration ? '保存中…' : savedInspiration ? '已存入灵感' : '✦ 存到灵感目的地' }}
        </button>
        <button v-if="!agent.globalWeather" type="button" class="weather-refresh" :disabled="weatherLoading" @click="refreshWeather">
          {{ weatherLoading ? '查询中…' : '查天气' }}
        </button>
        <div v-if="agent.globalWeather" class="weather-badge">
          <span class="weather-icon">{{ agent.globalWeather.icon || '☀️' }}</span>
          <div>
            <b>{{ agent.globalWeather.text || '天气' }}</b>
            <span>{{ agent.globalWeather.tempMin ?? '--' }} ~ {{ agent.globalWeather.tempMax ?? '--' }}℃</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 出发城市与出发日期快捷联动条 -->
    <div class="trip-context-bar">
      <label class="ctx-field">
        <span>出发城市</span>
        <input v-model="departureCity" maxlength="20" placeholder="例如：上海" />
      </label>
      <label class="ctx-field">
        <span>出发日期</span>
        <input v-model="startDate" type="date" />
      </label>
      <span class="ctx-note">修改出发地或日期将自动同步淡旺季系数、机酒方案与详细账单</span>
    </div>

    <!-- 六大类详细账单核算 -->
    <div v-if="detailedBill" class="bill-panel">
      <div class="bill-panel__head">
        <div>
          <span class="section-kicker">六大类详细账单核算</span>
          <h4>预计总花费 ¥{{ detailedBill.totalEstimatedCost.toLocaleString() }}</h4>
          <small>人均 ¥{{ detailedBill.perPersonCost.toLocaleString() }} · 日均 ¥{{ detailedBill.perDayCost.toLocaleString() }} · 预算使用率 {{ detailedBill.utilizationRate }}%</small>
        </div>
        <span class="bill-status" :class="'bill-status--' + detailedBill.status.toLowerCase()">
          {{ detailedBill.status === 'OVER_BUDGET' ? '超出预算 ¥' + Math.abs(detailedBill.deltaFromBudget).toLocaleString() : '预算结余 ¥' + Math.max(0, detailedBill.deltaFromBudget).toLocaleString() }}
        </span>
      </div>
      <div class="bill-grid">
        <div v-for="cat in detailedBill.categories" :key="cat.code" class="bill-item">
          <div class="bill-item__top">
            <b>{{ cat.label }}</b>
            <span class="source-pill">{{ priceSourceLabel(cat.priceSource) }}</span>
          </div>
          <div class="bill-item__val">
            <strong>¥{{ cat.amount.toLocaleString() }}</strong>
            <span>{{ cat.ratio }}%</span>
          </div>
          <div class="bill-bar"><i :style="{ width: Math.min(100, cat.ratio) + '%' }"></i></div>
          <small>{{ cat.note }}</small>
        </div>
      </div>
    </div>

    <!-- 日期联动机酒占位预订清单 + 第三阶段一键下单/CPS 归因 -->
    <div v-if="bookingPlaceholders" class="booking-panel">
      <div class="booking-panel__head">
        <div>
          <span class="section-kicker">机酒预订清单 · 点击切换实时重算账单</span>
          <h4>{{ bookingPlaceholders.departureCity }} ⇄ {{ bookingPlaceholders.destination }}（{{ bookingPlaceholders.startDate }} ~ {{ bookingPlaceholders.endDate }}）</h4>
        </div>
        <div class="booking-head-actions">
          <span class="placeholder-badge">占位演示 · 未对接外部平台</span>
          <button type="button" class="checklist-cta" :disabled="creatingChecklist" @click="submitBookingChecklist">
            {{ creatingChecklist ? '生成中…' : '🛒 一键加入预订清单' }}
          </button>
        </div>
      </div>
      <p class="booking-notice">{{ bookingPlaceholders.transportAdvice }}</p>
      <div class="booking-cols">
        <div class="booking-col">
          <h5>往返大交通（高铁 / 机票占位）</h5>
          <div
            v-for="opt in bookingPlaceholders.transportOptions"
            :key="opt.offerId"
            class="booking-card"
            :class="{ active: selectedTransportOfferId === opt.offerId }"
            @click="selectTransportOffer(opt.offerId)"
          >
            <div class="booking-card__top">
              <b>{{ opt.title }}</b>
              <span class="booking-tag">{{ opt.tag }}</span>
            </div>
            <small>{{ opt.codeNo }} · 去程 {{ opt.outboundSchedule }} · 返程 {{ opt.returnSchedule }}</small>
            <div class="booking-card__foot">
              <span>单人 ¥{{ opt.unitPrice.toLocaleString() }}</span>
              <strong>合计 ¥{{ opt.totalPrice.toLocaleString() }}</strong>
            </div>
          </div>
        </div>
        <div class="booking-col">
          <h5>推荐住宿（商圈酒店占位）</h5>
          <div
            v-for="hotel in bookingPlaceholders.hotelOptions"
            :key="hotel.offerId"
            class="booking-card"
            :class="{ active: selectedHotelOfferId === hotel.offerId }"
            @click="selectHotelOffer(hotel.offerId)"
          >
            <div class="booking-card__top">
              <b>{{ hotel.name }}</b>
              <span class="booking-tag">★ {{ hotel.rating }}</span>
            </div>
            <small>{{ hotel.checkInDate }} ~ {{ hotel.checkOutDate }} · 距核心区约 {{ hotel.distanceToCenterKm }} km</small>
            <div class="booking-card__foot">
              <span>¥{{ hotel.pricePerNight }}/晚 × {{ hotel.nights }}晚</span>
              <strong>合计 ¥{{ hotel.totalPrice.toLocaleString() }}</strong>
            </div>
          </div>
        </div>
      </div>
      <div v-if="createdBookings.length" class="created-checklist">
        <div class="created-checklist__title">已生成预订清单（已同步至「我的旅程」与「个人主页 · 订单与分佣」）</div>
        <div v-for="bk in createdBookings" :key="bk.bookingNo" class="created-checklist__row">
          <span><b>{{ bk.title }}</b> · ¥{{ Number(bk.amount).toLocaleString() }} · 预估 CPS 佣金 ¥{{ Number(bk.estimatedCommission).toFixed(2) }}</span>
          <span class="bk-status" :class="'bk-status--' + bk.status.toLowerCase()">{{ bk.status === 'CONFIRMED' ? '已确认' : bk.status === 'CANCELLED' ? '已取消' : '待预订' }}</span>
          <button v-if="bk.status === 'PENDING'" type="button" class="bk-confirm-btn" @click="confirmChecklistItem(bk.bookingNo)">模拟确认预订</button>
        </div>
      </div>
    </div>

    <div v-if="agent.dayTabs.length > 0" class="day-tabs">
      <div class="tab-bar">
        <button
          v-for="(tab, i) in agent.dayTabs"
          :key="i"
          class="tab-btn"
          :class="{ active: agent.activeDay === i }"
          @click="agent.activeDay = i"
        >
          <span class="tab-label">{{ tab.label }}</span>
          <span v-if="tab.weather?.temp" class="tab-temp">{{ tab.weather.temp }}°</span>
        </button>
      </div>
      <div class="tab-content">
        <div v-if="agent.dayTabs[agent.activeDay]" class="day-detail">
          <div class="day-weather-row">
            <div v-if="agent.dayTabs[agent.activeDay].weather?.text" class="weather-chip"><span>{{ agent.dayTabs[agent.activeDay].weather.icon }}</span><span>{{ agent.dayTabs[agent.activeDay].weather.text }}</span></div>
            <div v-if="agent.dayTabs[agent.activeDay].weather && (agent.dayTabs[agent.activeDay].weather.tempMin != null || agent.dayTabs[agent.activeDay].weather.tempMax != null)" class="weather-chip"><span>🌡️</span><span>{{ agent.dayTabs[agent.activeDay].weather.tempMin ?? '--' }} ~ {{ agent.dayTabs[agent.activeDay].weather.tempMax ?? '--' }}℃</span></div>
            <div v-if="agent.dayTabs[agent.activeDay].budget != null" class="weather-chip budget-chip"><span>💰</span><span>¥{{ agent.dayTabs[agent.activeDay].budget }}</span></div>
            <div v-if="currentDayTransport" class="weather-chip">
              <span>🗺</span>
              <span>当日通勤约 {{ currentDayTransport.totalDistanceKm }} km · {{ currentDayTransport.totalDurationMinutes }} 分钟 · 约 ¥{{ currentDayTransport.totalTransitCost }}</span>
            </div>
            <button
              v-if="agent.currentActivities.length > 2"
              type="button"
              class="optimize-route-btn"
              :disabled="optimizingDay"
              @click="optimizeCurrentDayRoute"
            >
              {{ optimizingDay ? '优化中…' : '✦ 一键顺路优化' }}
            </button>
          </div>
          <div v-if="currentDayTransport?.routeWarning" class="route-warning">
            ⚠️ {{ currentDayTransport.routeWarning }}
          </div>

          <div v-if="agent.currentActivities.length" class="activity-cards">
            <article v-for="(activity, ai) in agent.currentActivities" :key="ai" class="activity-card" :class="{ 'has-image': !!agent.imageMap[activity.name]?.length }">
              <div class="activity-card-media">
                <img v-if="agent.imageMap[activity.name]?.length" :src="agent.imageMap[activity.name][0]" :alt="activity.name" loading="lazy" @error="agent.dropImage(activity.name)" />
                <span v-else class="activity-card-placeholder">📍</span>
              </div>
              <div class="activity-card-body">
                <span>{{ activity.time || '行程' }}</span>
                <h4>{{ activity.name }}</h4>
                <p>{{ activity.location || activity.notes }}</p>
                <small>⏱ {{ activity.duration ? Math.round(activity.duration / 60) : 1 }} 小时 · {{ formatActivityCost(activity, { hotelNightlyRate: selectedHotelNightlyRate }) }}</small>
              </div>
            </article>
          </div>

          <!-- 当日相邻景点距离与交通规划明细 -->
          <div v-if="currentDayTransport?.segments?.length" class="transit-list">
            <div class="transit-list__title">当日景点间距离与交通规划（占位估算）</div>
            <div v-for="(seg, si) in currentDayTransport.segments" :key="si" class="transit-row">
              <span class="transit-route">{{ seg.fromName }} → {{ seg.toName }}</span>
              <span class="transit-mode">{{ seg.recommendedMode }}</span>
              <span>约 {{ seg.distanceKm }} km · {{ seg.durationMinutes }} 分钟 · 预计 ¥{{ seg.estimatedFare }}</span>
            </div>
          </div>

          <div v-if="agent.dayTabs[agent.activeDay].html" class="plan-md" v-html="agent.dayTabs[agent.activeDay].html"></div>
        </div>
      </div>
    </div>

    <div v-if="agent.dayOverview" class="plan-detail">
      <div class="plan-detail-inner" v-html="agent.renderMarkdown(agent.dayOverview)"></div>
    </div>
    <div v-else-if="agent.overview" class="plan-detail">
      <div class="plan-detail-inner" v-html="agent.renderMarkdown(agent.overview)"></div>
    </div>

    <div v-if="agent.plan.dataCache?.pois?.length" class="pois">
      <p class="lab">✓ 已整合景点数据</p>
      <span v-for="p in agent.plan.dataCache.pois" :key="p.name">{{ p.name }}</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.plan-wrapper {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.plan-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 16px 18px;
  border: 1px solid #E5E9F0;
  border-radius: 16px;
  background: #F7F9FC;

  h2 {
    margin: 0;
    font-size: 20px;
    font-weight: 700;
    color: #1F2329;
    letter-spacing: -0.02em;
  }

  p {
    margin: 5px 0 0;
    color: #5F6368;
    font-size: 12.5px;
  }
}

.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
  flex-shrink: 0;
}

.trip-context-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid #E5E9F0;
  border-radius: 14px;
  background: #FFFFFF;
}

.ctx-field {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: #F8FAFD;
  border: 1px solid #E2E7EE;
  border-radius: 10px;
  padding: 5px 10px;

  span {
    font-size: 11.5px;
    font-weight: 600;
    color: #5F6368;
    white-space: nowrap;
  }

  input {
    border: 0;
    background: transparent;
    font-size: 12.5px;
    font-weight: 600;
    color: #1F2329;
    outline: none;
    width: 118px;
  }
}

.ctx-note {
  font-size: 11.5px;
  color: #80868B;
}

.section-kicker {
  color: #0B57D0;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.bill-panel,
.booking-panel {
  padding: 14px 16px;
  border: 1px solid #E5E9F0;
  border-radius: 14px;
  background: #FFFFFF;
}

.bill-panel__head,
.booking-panel__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 10px;

  h4 {
    margin: 3px 0 2px;
    font-size: 15.5px;
    color: #1F2329;
  }

  small {
    font-size: 12px;
    color: #5F6368;
  }
}

.bill-status {
  font-size: 11.5px;
  font-weight: 700;
  padding: 4px 10px;
  border-radius: 999px;
  white-space: nowrap;
}
.bill-status--under_budget,
.bill-status--on_track { background: #E6F4EA; color: #137333; }
.bill-status--over_budget { background: #FCE8E6; color: #C5221F; }

.bill-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.bill-item {
  padding: 10px 11px;
  border: 1px solid #EEF1F5;
  border-radius: 12px;
  background: #F8FAFD;

  small { font-size: 11px; color: #80868B; }
}

.bill-item__top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: #1F2329;
}

.source-pill {
  font-size: 10px;
  color: #0B57D0;
  background: #E8F0FE;
  padding: 1px 7px;
  border-radius: 999px;
}

.bill-item__val {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin: 5px 0 4px;

  strong { font-size: 14.5px; color: #1F2329; }
  span { font-size: 11px; color: #5F6368; }
}

.bill-bar {
  height: 4px;
  background: #E5E9F0;
  border-radius: 999px;
  overflow: hidden;
  margin-bottom: 4px;

  i { display: block; height: 100%; background: #0B57D0; border-radius: 999px; }
}

.placeholder-badge {
  font-size: 11px;
  font-weight: 600;
  color: #B06000;
  background: #FEF7E0;
  border: 1px solid #FDE293;
  padding: 3px 10px;
  border-radius: 999px;
  white-space: nowrap;
}

.booking-notice {
  margin: 0 0 10px;
  font-size: 12px;
  color: #5F6368;
}

.booking-cols {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.booking-col h5 {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 700;
  color: #3C4043;
}

.booking-card {
  padding: 10px 12px;
  border: 1px solid #E5E9F0;
  border-radius: 12px;
  background: #F8FAFD;
  margin-bottom: 8px;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;

  &:hover { border-color: #C2D7FA; background: #FFFFFF; }
  &.active { border-color: #0B57D0; background: #F2F7FF; }

  small {
    display: block;
    margin: 4px 0 6px;
    font-size: 11px;
    color: #5F6368;
  }
}

.booking-card__top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12.5px;
  color: #1F2329;
}

.booking-tag {
  font-size: 10.5px;
  color: #0B57D0;
  background: #E8F0FE;
  padding: 1px 7px;
  border-radius: 999px;
  flex-shrink: 0;
}

.booking-card__foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11.5px;
  color: #5F6368;

  strong { color: #0B57D0; font-size: 13px; }
}

.inspiration-btn {
  border: 1px solid #D2E3FC;
  border-radius: 999px;
  padding: 7px 14px;
  background: #E8F0FE;
  color: #0B57D0;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s ease, transform 0.15s ease;

  &:hover:not(:disabled) {
    background: #D2E3FC;
    transform: translateY(-1px);
  }

  &:disabled { opacity: 0.6; cursor: default; }
}

.weather-refresh {
  border: 1px solid #DADCE0;
  border-radius: 999px;
  padding: 7px 14px;
  background: #FFFFFF;
  color: #3C4043;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;

  &:hover:not(:disabled) { background: #F0F4F9; }
  &:disabled { opacity: 0.6; cursor: default; }
}

.weather-badge {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #FFFFFF;
  border: 1px solid #E5E9F0;
  padding: 8px 14px;
  border-radius: 12px;
  flex-shrink: 0;

  .weather-icon { font-size: 24px; line-height: 1; }
  b { display: block; color: #1F2329; font-size: 13.5px; font-weight: 650; }
  span { font-size: 11.5px; color: #5F6368; }
}

.day-tabs { margin-top: 2px; }

.tab-bar {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 12px;
  border-bottom: 1px solid #EEF1F5;
  scrollbar-width: none;

  &::-webkit-scrollbar { display: none; }
}

.tab-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 7px 16px;
  border-radius: 999px;
  border: 1px solid transparent;
  background: #F0F4F9;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;
  flex-shrink: 0;
  min-width: 64px;

  .tab-label { font-size: 12.5px; font-weight: 600; color: #5F6368; }
  .tab-temp { font-size: 11px; color: #8C959F; }

  &:hover { background: #E4ECF7; }

  &.active {
    border-color: #0B57D0;
    background: #0B57D0;
    .tab-label, .tab-temp { color: #FFFFFF; }
  }
}

.tab-content { padding-top: 14px; }

.day-weather-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.weather-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: #F0F4F9;
  border: 1px solid #E5E9F0;
  color: #3C4043;
  font-size: 12px;
  font-weight: 500;
  padding: 5px 12px;
  border-radius: 999px;
}

.budget-chip {
  background: #E8F0FE;
  border-color: #D2E3FC;
  color: #0B57D0;
}

.optimize-route-btn {
  border: 1px solid #C2D7FA;
  background: #FFFFFF;
  color: #0B57D0;
  font-size: 12px;
  font-weight: 600;
  padding: 5px 12px;
  border-radius: 999px;
  cursor: pointer;
  &:hover:not(:disabled) { background: #E8F0FE; }
  &:disabled { opacity: 0.6; cursor: not-allowed; }
}

.route-warning {
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 10px;
  background: #FEF7E0;
  border: 1px solid #FDE293;
  color: #B06000;
  font-size: 12px;
}

.transit-list {
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid #E5E9F0;
  border-radius: 12px;
  background: #F8FAFD;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.transit-list__title {
  font-size: 12px;
  font-weight: 700;
  color: #3C4043;
}

.transit-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: #5F6368;
}

.transit-route {
  font-weight: 600;
  color: #1F2329;
}

.transit-mode {
  font-size: 11px;
  font-weight: 600;
  color: #0B57D0;
  background: #E8F0FE;
  padding: 1px 8px;
  border-radius: 999px;
}

.plan-md {
  background: #FFFFFF;
  border: 1px solid #E5E9F0;
  border-radius: 14px;
  padding: 18px 20px;
  font-size: 13.5px;
  line-height: 1.75;
  color: #1F2329;

  :deep(h2) { font-size: 16px; font-weight: 700; color: #1F2329; margin: 0 0 10px; border-bottom: 1px solid #EEF1F5; padding-bottom: 6px; }
  :deep(h3) { font-size: 14px; font-weight: 650; color: #3C4043; margin: 14px 0 6px; }
  :deep(p) { margin: 0 0 8px; }
  :deep(strong) { color: #0B57D0; }
  :deep(ul), :deep(ol) { margin: 6px 0 8px; padding-left: 20px; }
  :deep(li) { margin-bottom: 4px; }
}

.activity-cards {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 14px;
}

.activity-card {
  flex: 0 0 162px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #E5E9F0;
  border-radius: 14px;
  background: #FFFFFF;
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;

  &:hover {
    transform: translateY(-2px);
    border-color: #D2E3FC;
    box-shadow: 0 6px 18px rgba(15, 23, 42, 0.06);
  }
}

.activity-card-media {
  width: 100%;
  aspect-ratio: 4 / 3;
  flex-shrink: 0;
  background: #F0F4F9;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
}

.activity-card-placeholder {
  font-size: 32px;
  opacity: 0.5;
  user-select: none;
}

.activity-card-body {
  padding: 11px 12px 13px;
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;

  > span {
    color: #0B57D0;
    font-size: 11px;
    font-weight: 700;
  }

  h4 {
    margin: 0;
    color: #1F2329;
    font-size: 13.5px;
    font-weight: 650;
    line-height: 1.35;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  p {
    margin: 0;
    color: #5F6368;
    font-size: 11.5px;
    line-height: 1.45;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  small {
    color: #8C959F;
    font-size: 11px;
    margin-top: auto;
    padding-top: 4px;
  }
}

.plan-detail {
  margin-top: 6px;
  background: #FFFFFF;
  border: 1px solid #E5E9F0;
  border-radius: 16px;
  overflow: hidden;
}

.plan-detail-inner {
  padding: 20px 24px;
  line-height: 1.8;
  font-size: 14px;
  color: #1F2329;

  :deep(h1), :deep(h2), :deep(h3) { color: #1F2329; margin: 1em 0 0.5em; font-weight: 700; }
  :deep(h1) { font-size: 20px; }
  :deep(h2) { font-size: 17px; border-bottom: 1px solid #EEF1F5; padding-bottom: 6px; }
  :deep(h3) { font-size: 14.5px; }
  :deep(p) { margin: 0.7em 0; }
  :deep(ul), :deep(ol) { padding-left: 22px; margin: 0.7em 0; }
  :deep(li) { margin: 0.3em 0; }
  :deep(strong) { color: #0B57D0; font-weight: 600; }
  :deep(em) { color: #5F6368; font-style: italic; }
  :deep(code) { background: #F0F4F9; padding: 2px 6px; border-radius: 5px; font-size: 13px; color: #0B57D0; }
  :deep(blockquote) { border-left: 3px solid #0B57D0; margin: 1em 0; padding: 10px 16px; background: #F7FAFF; color: #3C4043; border-radius: 0 10px 10px 0; }
}

.pois {
  margin-top: 4px;

  .lab {
    color: #5F6368;
    font-size: 12px;
    font-weight: 650;
    margin: 0 0 8px;
  }

  span {
    display: inline-block;
    margin: 0 6px 6px 0;
    padding: 5px 12px;
    background: #F0F4F9;
    color: #3C4043;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 500;
  }
}

.booking-head-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.checklist-cta {
  border: 0;
  background: #0B57D0;
  color: #FFFFFF;
  font-size: 12px;
  font-weight: 600;
  padding: 6px 14px;
  border-radius: 999px;
  cursor: pointer;
  &:hover:not(:disabled) { background: #0842A0; }
  &:disabled { opacity: 0.6; cursor: not-allowed; }
}
.created-checklist {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #F4F8FF;
  border: 1px solid #D2E3FC;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.created-checklist__title { font-size: 11.5px; font-weight: 700; color: #0B57D0; }
.created-checklist__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  color: #1F2329;
}
.bk-status { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 999px; }
.bk-status--pending { background: #FEF7E0; color: #B06000; }
.bk-status--confirmed { background: #E6F4EA; color: #137333; }
.bk-status--cancelled { background: #EEF1F5; color: #5F6368; }
.bk-confirm-btn {
  border: 1px solid #0B57D0;
  background: #FFFFFF;
  color: #0B57D0;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 999px;
  cursor: pointer;
  &:hover { background: #E8F0FE; }
}
@media (max-width: 760px) {
  .bill-grid,
  .booking-cols {
    grid-template-columns: 1fr;
  }
}
</style>
