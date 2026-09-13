<template>
  <div ref="root" class="rpt-filter-card">
    <div class="rpt-filter-grid">
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
          <label>开始</label>
          <input type="date" :value="start" @input="emit('update:start', $event.target.value); preset = 'custom'">
        </div>
        <div class="rpt-filter-field">
          <label>截止</label>
          <input type="date" :value="end" @input="emit('update:end', $event.target.value); preset = 'custom'">
        </div>
      </template>
      <!-- 默认条件与更多条件在同一个等宽网格内连续排布，自动逐行排满 -->
      <slot />
      <slot name="more" />
    </div>

    <!-- 无页头导航可挂载时（独立嵌入场景）的兜底内联渲染 -->
    <div v-if="!navTarget" class="rpt-filter-fallback">
      <slot name="actions" />
    </div>

    <!-- 查询/重置 teleport 到报表页头导航栏（与导出同一行） -->
    <Teleport v-if="navTarget" :to="navTarget">
      <span class="rpt-nav-actions">
        <slot name="actions" />
      </span>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'

/**
 * 报表统一筛选条（紧凑网格版）：
 * - 全部字段在同一等宽自适应网格内连续排布，逐行排满；
 * - 查询/重置 teleport 到页头 .rpt-ops（与导出同一行），最大化数据区空间；
 * - K2 口径日期预设（默认「最近一个月，截止昨天」）。
 */
const props = defineProps({
  start: { type: String, required: true },
  end: { type: String, required: true },
  /** 初始日期预设（月结类报表传 'thisMonth'，与后端自然月默认一致） */
  initialPreset: { type: String, default: 'month' },
  /** 时点报表（账龄表）：隐藏期间日期字段，只展示截止日等自定义条件 */
  hideDates: { type: Boolean, default: false },
})
const emit = defineEmits(['update:start', 'update:end'])

const preset = ref(props.initialPreset)

const root = ref(null)
const navTarget = ref(null)
let host = null

onMounted(async () => {
  await nextTick()
  const page = root.value?.closest?.('.rpt-page')
  host = page?.querySelector?.('.rpt-ops') || null
  if (host) {
    host.classList.add('rpt-nav-host')
    navTarget.value = host
  }
})
onBeforeUnmount(() => {
  if (host) host.classList.remove('rpt-nav-host')
})

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
  padding: 8px 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .06);
  margin-bottom: 8px;
}
/* 等宽自适应网格：字段均匀铺满，逐行排满 */
.rpt-filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 6px 10px;
  align-items: center;
}
.rpt-filter-field {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #606266;
  min-width: 0;
}
.rpt-filter-field label { white-space: nowrap; }
.rpt-filter-field input,
.rpt-filter-field select {
  flex: 1;
  min-width: 0;
  height: 28px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 0 8px;
  font-size: 13px;
  color: #303133;
  background: #fff;
}
.rpt-filter-fallback {
  margin-top: 8px;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
:deep(.rpt-nav-actions) {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
</style>
