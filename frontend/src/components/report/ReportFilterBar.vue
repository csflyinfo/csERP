<template>
  <div class="rpt-filter-card">
    <div class="rpt-filter-row">
      <template v-if="!hideDates">
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
      </template>
      <slot />
      <div class="rpt-filter-actions">
        <button v-if="hasMore" type="button" class="btn-more" @click="expanded = !expanded">
          {{ expanded ? '收起条件' : '展开更多条件' }}
          <span :class="{ rotated: expanded }">▾</span>
        </button>
        <slot name="actions" />
      </div>
    </div>
    <!-- 第二行及更多：默认折叠，避免筛选区占用过多纵向空间 -->
    <div v-if="hasMore && expanded" class="rpt-filter-row rpt-filter-more">
      <slot name="more" />
    </div>
  </div>
</template>

<script setup>
import { ref, useSlots } from 'vue'

/**
 * 报表统一筛选条：K2 口径日期预设（默认「最近一个月，截止昨天」）。
 * 默认插槽字段在第一行；条件较多时其余字段放 #more 插槽，默认折叠。
 */
const props = defineProps({
  start: { type: String, required: true },
  end: { type: String, required: true },
  /** 初始日期预设（月结类报表传 'thisMonth'，与后端自然月默认一致） */
  initialPreset: { type: String, default: 'month' },
  /** 默认展开更多条件（如时点报表） */
  defaultExpanded: { type: Boolean, default: false },
  /** 时点报表（账龄表）：隐藏期间日期字段，只展示截止日等自定义条件 */
  hideDates: { type: Boolean, default: false },
})
const emit = defineEmits(['update:start', 'update:end'])

const slots = useSlots()
const hasMore = !!slots.more
const expanded = ref(props.defaultExpanded)
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
      s = new Date(today.getFullYear(), today.getMonth() - 1, 1)
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
.rpt-filter-more {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #ebeef5;
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
  align-items: center;
}
.btn-more {
  border: none; background: none; color: #409eff; font-size: 13px; cursor: pointer;
  display: inline-flex; align-items: center; gap: 2px;
}
.btn-more span { display: inline-block; transition: transform .15s; }
.btn-more span.rotated { transform: rotate(180deg); }
</style>
