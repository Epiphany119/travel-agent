<script setup lang="ts">
import { ref } from 'vue'
import { useAgentSessionStore } from '@/stores/agentSession'
import { addInspiration, getCurrentUserId } from '@/api/user'
import { fetchItineraryWeather } from '@/api/weather'
import { formatCost } from '@/utils/planValues'
const agent = useAgentSessionStore()

const savingInspiration = ref(false)
const savedInspiration = ref(false)
const weatherLoading = ref(false)

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
      // agent.plan.budget 是用户输入的预算上限，不是系统核验出的预计花费。
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
    const startDate = agent.plan.dayPlans?.find((d: any) => d?.date)?.date
    const weather = await fetchItineraryWeather(
      agent.plan.destination,
      startDate,
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
          <div v-if="agent.dayTabs[agent.activeDay].weather" class="day-weather-row">
            <div v-if="agent.dayTabs[agent.activeDay].weather.text" class="weather-chip"><span>{{ agent.dayTabs[agent.activeDay].weather.icon }}</span><span>{{ agent.dayTabs[agent.activeDay].weather.text }}</span></div>
            <div v-if="agent.dayTabs[agent.activeDay].weather.tempMin != null || agent.dayTabs[agent.activeDay].weather.tempMax != null" class="weather-chip"><span>🌡️</span><span>{{ agent.dayTabs[agent.activeDay].weather.tempMin ?? '--' }} ~ {{ agent.dayTabs[agent.activeDay].weather.tempMax ?? '--' }}℃</span></div>
            <div v-if="agent.dayTabs[agent.activeDay].budget != null" class="weather-chip budget-chip"><span>💰</span><span>¥{{ agent.dayTabs[agent.activeDay].budget }}</span></div>
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
                <small>⏱ {{ activity.duration ? Math.round(activity.duration / 60) : 1 }} 小时 · {{ formatCost(activity.cost) }}</small>
              </div>
            </article>
          </div>
          <div v-if="agent.dayTabs[agent.activeDay].html" class="plan-md" v-html="agent.dayTabs[agent.activeDay].html"></div>
        </div>
      </div>
    </div>

    <!-- 底部详细总结：按 activeDay 切，和 tab 对齐 -->
    <div v-if="agent.dayOverview" class="plan-detail">
      <div class="plan-detail-inner" v-html="agent.renderMarkdown(agent.dayOverview)"></div>
    </div>
    <!-- 兜底：如果 dayOverview 为空但 overview 有内容，显示完整总览（不太可能发生） -->
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
  gap: 8px;
  margin-bottom: 14px;
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
</style>
