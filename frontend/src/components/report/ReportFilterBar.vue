<template>
  <div class="rpt-filter-card">
    <div class="rpt-filter-row">
      <div class="rpt-filter-field">
        <label>日期预设</label>
        <select v-model="preset" @change="onPreset">
          <option value="month">最近一个月（截止昨天）</option>
          <option value="today">今天</option>
          <option value="7d">近 7 天</option>
          <option value="30d">近 30 天</option>
          <option value="thisMonth">本月</option>
          <option value="lastMonth">上月</option>
          <option value="custom">自定义</option>
        </select>
      </div>
      <div class="rpt-filter-field">
        <label>开始日期</label>
        <input type="date" :value="start" @input="emit('update:start', $event.target.value); preset = 'custom'">
      </div>
      <div class="rpt-filter-field">
        <label>截止日期</label>
        <input type="date" :value="end" @input="emit('update:end', $event.target.value); preset = 'custom'">
      </div>
      <slot />
      <div class="rpt-filter-actions">
        <slot name="actions" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

/**
 * 报表统一筛选条：K2 口径日期预设（默认「最近一个月，截止昨天」）。
 * 各报表特有筛选走默认插槽，查询/重置走 actions 插槽。
 */
const props = defineProps({
  start: { type: String, required: true },
  end: { type: String, required: true },
  /** 初始日期预设（月结类报表传 'thisMonth'，与后端自然月默认一致） */
  initialPreset: { type: String, default: 'month' },
})
const emit = defineEmits(['update:start', 'update:end'])

const preset = ref(props.initialPreset)

function fmt(d) {
  // 用本地时区，不能用 toISOString（UTC 在东八区会把日期往前拨一天）
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function onPreset() {
  if (preset.value === 'custom') return
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1)
  let s, e = yesterday
  switch (preset.value) {
    case 'today':
      s = new Date(today); e = new Date(today); break
    case '7d':
      s = new Date(yesterday); s.setDate(s.getDate() - 6); break
    case '30d':
      s = new Date(yesterday); s.setDate(s.getDate() - 29); break
    case 'thisMonth':
      s = new Date(today.getFullYear(), today.getMonth(), 1)
      e = new Date(today.getFullYear(), today.getMonth() + 1, 0)
      if (e > yesterday) e = yesterday
      break
    case 'lastMonth': {
      const first = new Date(today.getFullYear(), today.getMonth() - 1, 1)
      s = first
      e = new Date(today.getFullYear(), today.getMonth(), 0)
      break
    }
    case 'month':
    default:
      // K2：截止昨天，起始=截止「上月同日的前一天」
      s = new Date(yesterday); s.setMonth(s.getMonth() - 1); s.setDate(s.getDate() - 1)
  }
  emit('update:start', fmt(s))
  emit('update:end', fmt(e))
}
</script>

<style scoped>
.rpt-filter-card {
  background: #fff;
  border-radius: 6px;
  padding: 12px 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .06);
  margin-bottom: 10px;
}
.rpt-filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 14px;
  align-items: flex-end;
}
.rpt-filter-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #606266;
}
.rpt-filter-field input,
.rpt-filter-field select {
  height: 30px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 0 8px;
  font-size: 13px;
  color: #303133;
  background: #fff;
  min-width: 120px;
}
.rpt-filter-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
</style>
