<template>
  <el-card class="day-plan-card" shadow="hover">
    <template #header>
      <div class="day-header">
        <el-tag type="primary">Day {{ plan.dayNumber }}</el-tag>
        <span class="day-theme">{{ plan.theme }}</span>
        <span class="day-date">{{ plan.date }}</span>
      </div>
    </template>

    <div class="plan-slots">
      <div v-if="plan.morning" class="plan-slot plan-slot--morning">
        <div class="slot-label">🌅 上午</div>
        <div class="slot-content">{{ plan.morning.plan }}</div>
        <div class="slot-meta">{{ plan.morning.duration }} · {{ formatCost(plan.morning.budget) }}</div>
      </div>
      <div v-if="plan.afternoon" class="plan-slot plan-slot--afternoon">
        <div class="slot-label">☀️ 下午</div>
        <div class="slot-content">{{ plan.afternoon.plan }}</div>
        <div class="slot-meta">{{ plan.afternoon.duration }} · {{ formatCost(plan.afternoon.budget) }}</div>
      </div>
      <div v-if="plan.evening" class="plan-slot plan-slot--evening">
        <div class="slot-label">🌙 晚上</div>
        <div class="slot-content">{{ plan.evening.plan }}</div>
        <div class="slot-meta">{{ plan.evening.duration }} · {{ formatCost(plan.evening.budget) }}</div>
      </div>
    </div>

    <div v-if="plan.tips" class="plan-tips">
      💡 {{ plan.tips }}
    </div>
  </el-card>
</template>

<script setup lang="ts">
import type { DayPlan } from '@/types/stream'
import { formatCost } from '@/utils/planValues'

defineProps<{ plan: DayPlan }>()
</script>

<style scoped>
.day-plan-card {
  margin: 16px 0;
}

.day-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.day-theme {
  font-weight: 600;
  font-size: 16px;
}

.day-date {
  color: #909399;
  font-size: 13px;
  margin-left: auto;
}

.plan-slots {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 12px 0;
}

.plan-slot {
  padding: 10px 14px;
  border-radius: 8px;
}

.plan-slot--morning {
  background: #fef0e6;
}

.plan-slot--afternoon {
  background: #fdf6ec;
}

.plan-slot--evening {
  background: var(--roam-soft);
}

.slot-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.slot-content {
  font-weight: 500;
}

.slot-meta {
  font-size: 12px;
  color: #67c23a;
  margin-top: 4px;
}

.plan-tips {
  color: #909399;
  font-size: 13px;
  padding-top: 8px;
  border-top: 1px solid #eee;
}
</style>
