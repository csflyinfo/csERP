<template>
  <div class="aging-chart">
    <div class="aging-chart-head">
      <span class="aging-chart-title">{{ title }}</span>
      <button type="button" class="aging-toggle" @click="collapsed = !collapsed">
        {{ collapsed ? '展开图表' : '收起图表' }}<span :class="{ rotated: !collapsed }">▾</span>
      </button>
    </div>
    <div v-show="!collapsed" ref="el" class="aging-chart-box" :style="{ height: height }"></div>
  </div>
</template>

<script setup>
import * as echarts from 'echarts'
import { ref, watch, nextTick, onMounted, onUnmounted } from 'vue'

/**
 * 账龄分布柱图（#20/#21 上部图表），可折叠收起。
 * buckets：[{ label, value, overdue, color }]；只读图，无敏感字段。
 */
const props = defineProps({
  buckets: { type: Array, default: () => [] },
  title: { type: String, default: '账龄分布' },
  height: { type: String, default: '260px' },
})
const el = ref(null)
const collapsed = ref(false)
let chart = null
function init() {
  if (!el.value || chart) return
  chart = echarts.init(el.value)
  render()
}
function render() {
  if (!chart) return
  const names = props.buckets.map(b => b.label)
  const vals = props.buckets.map(b => Number(b.value || 0))
  const colors = props.buckets.map(b => b.color || (b.overdue ? '#f56a00' : '#52c41a'))
  chart.setOption({
    title: { text: '', left: 'center' },
    tooltip: { trigger: 'axis', valueFormatter: v => Number(v || 0).toLocaleString() },
    grid: { left: 70, right: 24, top: 45, bottom: 30 },
    xAxis: { type: 'category', data: names, axisLabel: { rotate: 0 } },
    yAxis: { type: 'value', axisLabel: { formatter: v => v >= 10000 ? (v / 10000) + '万' : v } },
    series: [{
      name: '金额', type: 'bar', data: vals.map((v, i) => ({ value: v, itemStyle: { color: colors[i] }, borderRadius: [4, 4, 0, 0] })),
      label: { show: true, position: 'top', formatter: p => p.value === 0 ? '' : fmtShort(p.value), fontSize: 11 },
    }],
  })
}
function fmtShort(v) {
  return Math.abs(v) >= 10000 ? (v / 10000).toFixed(1) + '万' : String(v)
}
function onResize() { chart?.resize() }
watch(() => props.buckets, () => nextTick(() => { if (!collapsed.value) { init(); render() } }), { deep: true })
watch(collapsed, async v => {
  if (!v) { await nextTick(); init(); render(); chart?.resize() }
})
onMounted(() => { nextTick(init); window.addEventListener('resize', onResize) })
onUnmounted(() => { window.removeEventListener('resize', onResize); chart?.dispose() })
</script>

<style scoped>
.aging-chart { background: #fff; border-radius: 6px; padding: 10px 14px; box-shadow: 0 1px 3px rgba(0, 0, 0, .06); margin-bottom: 10px; }
.aging-chart-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.aging-chart-title { font-size: 14px; font-weight: 600; color: #12385f; }
.aging-toggle { border: none; background: none; color: #409eff; font-size: 13px; cursor: pointer; display: inline-flex; align-items: center; gap: 2px; }
.aging-toggle span { display: inline-block; transition: transform .15s; }
.aging-toggle span.rotated { transform: rotate(180deg); }
</style>
