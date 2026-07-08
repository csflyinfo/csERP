<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { get } from '../api/client.js'

const loading = ref(true)
const feedback = ref('')

const chartRefs = {
  salesTrend: ref(null),
  purchaseTrend: ref(null),
  customerPie: ref(null),
  stockPie: ref(null),
  categoryBar: ref(null),
  financeGauge: ref(null),
}

const charts = {}

function show(msg) {
  feedback.value = msg
  setTimeout(() => (feedback.value = ''), 2000)
}

async function loadAllCharts() {
  loading.value = true
  try {
    await Promise.all([
      loadSalesTrend(),
      loadPurchaseTrend(),
      loadCustomerSales(),
      loadStockDistribution(),
      loadCategorySales(),
      loadFinanceOverview(),
    ])
  } catch (e) {
    show('部分图表数据加载失败')
  } finally {
    loading.value = false
  }
}

async function loadSalesTrend() {
  try {
    const data = await get('/report/chart/sales-trend')
    const dates = data.map(d => d.date).reverse()
    const amounts = data.map(d => Number(d.amount || 0)).reverse()
    const counts = data.map(d => Number(d.count || 0)).reverse()
    initLineChart(chartRefs.salesTrend.value, '销售趋势', dates, [
      { name: '销售金额', data: amounts, color: '#1677ff' },
      { name: '订单数', data: counts, color: '#52c41a', yAxisIndex: 1 },
    ])
  } catch (e) {}
}

async function loadPurchaseTrend() {
  try {
    const data = await get('/report/chart/purchase-trend')
    const dates = data.map(d => d.date).reverse()
    const amounts = data.map(d => Number(d.amount || 0)).reverse()
    initLineChart(chartRefs.purchaseTrend.value, '采购趋势', dates, [
      { name: '采购金额', data: amounts, color: '#fa8c16' },
    ])
  } catch (e) {}
}

async function loadCustomerSales() {
  try {
    const data = await get('/report/chart/customer-sales')
    const pieData = data.map(d => ({ name: d.name, value: Number(d.value || 0) }))
    initPieChart(chartRefs.customerPie.value, '客户销售TOP10', pieData)
  } catch (e) {}
}

async function loadStockDistribution() {
  try {
    const data = await get('/report/chart/stock-distribution')
    const pieData = data.map(d => ({ name: d.name, value: Number(d.value || 0) }))
    initPieChart(chartRefs.stockPie.value, '库存金额分布', pieData, ['#1677ff', '#52c41a', '#fa8c16', '#f5222d', '#722ed1'])
  } catch (e) {}
}

async function loadCategorySales() {
  try {
    const data = await get('/report/chart/category-sales')
    const names = data.map(d => d.name)
    const values = data.map(d => Number(d.value || 0))
    initBarChart(chartRefs.categoryBar.value, '分类销售金额', names, values)
  } catch (e) {}
}

async function loadFinanceOverview() {
  try {
    const data = await get('/report/chart/finance-overview')
    initFinanceGauge(chartRefs.financeGauge.value, data)
  } catch (e) {}
}

function initLineChart(el, title, xData, seriesList) {
  if (!el) return
  const chart = echarts.init(el)
  charts[title] = chart
  const series = seriesList.map((s, idx) => ({
    name: s.name,
    type: 'line',
    data: s.data,
    smooth: true,
    yAxisIndex: s.yAxisIndex || 0,
    itemStyle: { color: s.color },
    areaStyle: s.yAxisIndex ? undefined : { opacity: 0.1 },
  }))
  const yAxes = seriesList.some(s => s.yAxisIndex)
    ? [
        { type: 'value', name: '金额', axisLabel: { formatter: '¥{value}' } },
        { type: 'value', name: '数量', position: 'right' },
      ]
    : [{ type: 'value', axisLabel: { formatter: '¥{value}' } }]

  chart.setOption({
    title: { text: title, left: 'center', textStyle: { fontSize: 14, color: '#12385f' } },
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, data: seriesList.map(s => s.name) },
    grid: { left: 60, right: yAxes.length > 1 ? 60 : 20, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: xData, axisLabel: { rotate: 45 } },
    yAxis: yAxes,
    series,
  })
}

function initPieChart(el, title, data, colors) {
  if (!el) return
  const chart = echarts.init(el)
  charts[title] = chart
  chart.setOption({
    title: { text: title, left: 'center', textStyle: { fontSize: 14, color: '#12385f' } },
    tooltip: { trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' },
    legend: { bottom: 0, type: 'scroll' },
    color: colors,
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      center: ['50%', '45%'],
      avoidLabelOverlap: true,
      itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
      label: { show: true, formatter: '{b}\n{d}%' },
      data,
    }],
  })
}

function initBarChart(el, title, names, values) {
  if (!el) return
  const chart = echarts.init(el)
  charts[title] = chart
  chart.setOption({
    title: { text: title, left: 'center', textStyle: { fontSize: 14, color: '#12385f' } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 60, right: 20, top: 40, bottom: 60 },
    xAxis: { type: 'category', data: names, axisLabel: { rotate: 30 } },
    yAxis: { type: 'value', axisLabel: { formatter: '¥{value}' } },
    series: [{
      type: 'bar',
      data: values,
      itemStyle: { color: '#1677ff', borderRadius: [4, 4, 0, 0] },
      label: { show: true, position: 'top', formatter: '¥{c}' },
    }],
  })
}

function initFinanceGauge(el, data) {
  if (!el) return
  const chart = echarts.init(el)
  charts['finance'] = chart
  chart.setOption({
    title: { text: '财务概览', left: 'center', textStyle: { fontSize: 14, color: '#12385f' } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { bottom: 0 },
    grid: { left: 60, right: 20, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: ['应收账款', '应付账款', '资金余额'] },
    yAxis: { type: 'value', axisLabel: { formatter: '¥{value}' } },
    series: [
      {
        name: '总额',
        type: 'bar',
        data: [Number(data.arTotal || 0), Number(data.apTotal || 0), Number(data.fundBalance || 0)],
        itemStyle: { color: '#1677ff', borderRadius: [4, 4, 0, 0] },
      },
      {
        name: '已收/已付',
        type: 'bar',
        data: [Number(data.arReceived || 0), Number(data.apPaid || 0), 0],
        itemStyle: { color: '#52c41a', borderRadius: [4, 4, 0, 0] },
      },
      {
        name: '未收/未付',
        type: 'bar',
        data: [Number(data.arUnreceived || 0), Number(data.apUnpaid || 0), 0],
        itemStyle: { color: '#f5222d', borderRadius: [4, 4, 0, 0] },
      },
    ],
  })
}

function handleResize() {
  Object.values(charts).forEach(chart => chart?.resize())
}

onMounted(() => {
  loadAllCharts()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  Object.values(charts).forEach(chart => chart?.dispose())
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn primary" @click="loadAllCharts" :disabled="loading">{{ loading ? '加载中...' : '刷新报表' }}</button>
    </div>

    <div v-if="loading" class="tips-inline"><span>正在加载报表数据...</span></div>

    <!-- 第一行：趋势图 -->
    <div class="cards" style="grid-template-columns: repeat(2, 1fr)">
      <div class="card" style="padding:12px;height:360px">
        <div ref="chartRefs.salesTrend" style="width:100%;height:100%"></div>
      </div>
      <div class="card" style="padding:12px;height:360px">
        <div ref="chartRefs.purchaseTrend" style="width:100%;height:100%"></div>
      </div>
    </div>

    <!-- 第二行：饼图 -->
    <div class="cards" style="grid-template-columns: repeat(2, 1fr)">
      <div class="card" style="padding:12px;height:360px">
        <div ref="chartRefs.customerPie" style="width:100%;height:100%"></div>
      </div>
      <div class="card" style="padding:12px;height:360px">
        <div ref="chartRefs.stockPie" style="width:100%;height:100%"></div>
      </div>
    </div>

    <!-- 第三行：柱状图 + 财务概览 -->
    <div class="cards" style="grid-template-columns: repeat(2, 1fr)">
      <div class="card" style="padding:12px;height:360px">
        <div ref="chartRefs.categoryBar" style="width:100%;height:100%"></div>
      </div>
      <div class="card" style="padding:12px;height:360px">
        <div ref="chartRefs.financeGauge" style="width:100%;height:100%"></div>
      </div>
    </div>
  </div>
  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
</template>
