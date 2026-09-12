<template>
  <div ref="el" class="aging-bucket-chart"></div>
</template>

<script setup>
/**
 * 账龄分布柱图（#20/#21 上部图表）：只读消费合计行桶金额，无额外查询。
 * props.buckets：[{ label, value, overdue }]，未到期绿色、逾期按档加深红。
 */
import { ref, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  buckets: { type: Array, default: () => [] },
  title: { type: String, default: '账龄分布' },
})
const el = ref(null)
let chart = null

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  const names = props.buckets.map(b => b.label)
  const vals = props.buckets.map(b => Number(b.value || 0))
  const colors = props.buckets.map(b => b.overdue ? b.color || '#f5222d' : '#52c41a')
  chart.setOption({
    title: { text: props.title, left: 'center', textStyle: { fontSize: 14, color: '#12385f' } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' },
      valueFormatter: v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) },
    grid: { left: 70, right: 20, top: 45, bottom: 30 },
    xAxis: { type: 'category', data: names },
    yAxis: { type: 'value', axisLabel: { formatter: v => v >= 10000 ? (v / 10000) + '万' : v } },
    series: [{
      type: 'bar', data: vals.map((v, i) => ({ value: v, itemStyle: { color: colors[i] } })),
      itemStyle: { borderRadius: [4, 4, 0, 0] },
      label: { show: true, position: 'top', formatter: p => p.value === 0 ? '' : p.value },
    }],
  })
}
function onResize() { chart?.resize() }
onMounted(() => { render(); window.addEventListener('resize', onResize) })
onUnmounted(() => { window.removeEventListener('resize', onResize); chart?.dispose() })
watch(() => props.buckets, render, { deep: true })
</script>

<style scoped>
.aging-bucket-chart { width: 100%; height: 260px; }
</style>
