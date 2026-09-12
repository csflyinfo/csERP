<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        商品综合分析
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            一张屏看购销存：KPI 期间值与等长上一期间环比；趋势按签收口径（销售额/毛利）与入库口径（采购额），
            可按日/按月切换；结构区为分类销售占比、TOP20 商品（金额/毛利切换）、TOP10 客户；
            明细表每行一个商品：采购数量金额（入库净额）、签收数量金额/客户数/单数、配比成本、毛利、
            期末库存与可销天数。全部金额含税；成本/毛利按字段权限橘色脱敏。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出明细表</button>
        <button class="btn-primary" @click="submitExport">异步导出</button>
      </div>
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end">
      <div class="ff">
        <label>仓库</label>
        <select v-model="filters.warehouse">
          <option value="">全部仓库</option>
          <option v-for="w in warehouses" :key="w" :value="w">{{ w }}</option>
        </select>
      </div>
      <div class="ff"><label>商品</label><input v-model="filters.goods" placeholder="编码/名称" @keyup.enter="onSearch"></div>
      <div class="ff"><label>分类</label><input v-model="filters.categoryName" @keyup.enter="onSearch"></div>
      <div class="ff"><label>品牌</label><input v-model="filters.brandName" @keyup.enter="onSearch"></div>
      <template #actions>
        <button class="btn-primary" @click="onSearch">查询</button>
        <button class="btn-plain" @click="onReset">重置</button>
      </template>
    </ReportFilterBar>

    <!-- KPI 卡片行 -->
    <div class="kpi-row">
      <div v-for="k in KPI_CARDS" :key="k.key" class="kpi-card">
        <div class="kpi-label">{{ k.label }}</div>
        <div class="kpi-value" :class="{ money: k.money }">{{ fmtKpi(kpi[k.key], k) }}</div>
        <div class="kpi-rate" :class="rateClass(kpi[k.key + 'Rate'])">{{ rateText(kpi[k.key + 'Rate']) }}</div>
      </div>
    </div>

    <div class="chart-toolbar">
      <div class="seg">
        <button :class="{ active: gran === 'day' }" @click="switchGran('day')">按日</button>
        <button :class="{ active: gran === 'month' }" @click="switchGran('month')">按月</button>
      </div>
      <div class="seg">
        <button :class="{ active: metric === 'sales_amount' }" @click="switchMetric('sales_amount')">按销售额</button>
        <button :class="{ active: metric === 'gross_profit' }" @click="switchMetric('gross_profit')">按毛利</button>
      </div>
    </div>

    <div class="chart-grid">
      <div class="chart-card wide"><div ref="trendEl" class="chart-box"></div></div>
      <div class="chart-card"><div ref="pieEl" class="chart-box"></div></div>
      <div class="chart-card"><div ref="goodsEl" class="chart-box"></div></div>
      <div class="chart-card"><div ref="custEl" class="chart-box"></div></div>
    </div>

    <DrillGridReport
      :columns="visibleColumns"
      :rows="rows"
      :summary="summary"
      :loading="loading"
      :total="total"
      :page-no="pageNo"
      :page-size="pageSize"
      :sort-field="sortField"
      :sort-order="sortOrder"
      @sort="onSort"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, nextTick, ref } from 'vue'
import * as echarts from 'echarts'
import ReportFilterBar from '@/components/report/ReportFilterBar.vue'
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows, fmtNum, fmtPercent } from '@/components/report/report-table.js'
import {
  loadWarehouses, goodsAnalysisKpi, goodsAnalysisTrend, goodsAnalysisStructure,
} from '@/api/report-center.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'goodsAnalysisReport'
const CODE = 'goods_analysis'
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE)
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])
const gran = ref('day')
const metric = ref('sales_amount')
const kpi = ref({})
const trendData = ref([])
const structure = ref({ categoryPie: [], topGoods: [], topCustomers: [] })
const trendEl = ref(null); const pieEl = ref(null); const goodsEl = ref(null); const custEl = ref(null)
const charts = {}
pageSize.value = 100000
filters.value = { level: 'goods' }

const KPI_CARDS = [
  { key: 'purchaseAmount', label: '采购额(入库净额)', money: true },
  { key: 'salesAmount', label: '销售额(签收净额)', money: true },
  { key: 'grossProfit', label: '毛利额', money: true },
  { key: 'grossProfitRate', label: '毛利率', percent: true },
  { key: 'billCount', label: '签收单数' },
  { key: 'customerCount', label: '签收客户数' },
  { key: 'avgBillAmount', label: '客单价', money: true },
  { key: 'endStockAmount', label: '期末库存金额', money: true },
]

const ALL_COLUMNS = [
  { key: 'goodsCode', title: '商品编号', width: 110 },
  { key: 'goodsName', title: '商品名称', width: 180 },
  { key: 'brandName', title: '品牌', width: 90 },
  { key: 'categoryName', title: '商品类别', width: 100 },
  { key: 'storageProperty', title: '存储属性', width: 80 },
  { key: 'baseUnit', title: '单位', width: 70 },
  { key: 'purchaseQty', title: '采购数量', num: true },
  { key: 'purchaseAmount', title: '采购金额', num: true, money: true, sensitive: true },
  { key: 'signedQty', title: '签收数量', num: true },
  { key: 'signedAmount', title: '签收金额', num: true, money: true, sensitive: true, sortable: true },
  { key: 'customerCount', title: '客户数', num: true },
  { key: 'billCount', title: '签收单数', num: true },
  { key: 'costAmount', title: '配比成本', num: true, money: true, sensitive: true },
  { key: 'grossProfit', title: '毛利额', num: true, money: true, sensitive: true, sortable: true },
  { key: 'grossProfitRate', title: '毛利率', num: true, percent: true, sensitive: true },
  { key: 'endQty', title: '期末库存数量', num: true },
  { key: 'endAmount', title: '期末库存金额', num: true, money: true, sensitive: true },
  { key: 'coverDays', title: '可销天数', num: true, fixed: 1, sortable: true },
]
const visibleColumns = computed(() => ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

onMounted(async () => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  await loadAll()
  window.addEventListener('resize', onResize)
})
onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  Object.values(charts).forEach(c => c?.dispose())
})

function body() {
  return r.buildBody()
}
async function loadAll() {
  loading.value = true
  try {
    const [k, t, s] = await Promise.all([
      goodsAnalysisKpi(body()), goodsAnalysisTrend({ ...body(), granularity: gran.value }),
      goodsAnalysisStructure(body()),
    ])
    kpi.value = k || {}
    trendData.value = t || []
    structure.value = s || { categoryPie: [], topGoods: [], topCustomers: [] }
    await r.query()
  } catch (e) {
    alert('查询失败：' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
  nextTick(renderCharts)
}
function onSearch() {
  pageNo.value = 1
  loadAll()
}
function onReset() {
  filters.value = { level: 'goods' }
  sortField.value = ''; sortOrder.value = ''
  r.resetDate()
  loadAll()
}
function onSort({ field, order }) {
  sortField.value = field
  sortOrder.value = order
  r.query().catch(() => {})
}
function switchGran(g) { gran.value = g; goodsAnalysisTrend({ ...body(), granularity: g }).then(t => {
  trendData.value = t || []; nextTick(renderTrend)
}).catch(() => {}) }
function switchMetric(m) {
  metric.value = m
  goodsAnalysisStructure({ ...body(), metric: m === 'gross_profit' ? 'profit' : 'amount' })
    .then(s => { structure.value = s || structure.value; nextTick(() => { renderGoods(); renderCust(); renderPie() }) })
    .catch(() => {})
}

function chartInit(key, el) {
  if (!el) return null
  if (!charts[key]) charts[key] = echarts.init(el)
  return charts[key]
}
function renderCharts() { renderTrend(); renderPie(); renderGoods(); renderCust() }
function onResize() { Object.values(charts).forEach(c => c?.resize()) }
function renderTrend() {
  const c = chartInit('trend', trendEl.value); if (!c) return
  const d = trendData.value
  c.setOption({
    title: { text: '购销存趋势（签收口径/入库口径）', left: 'center', textStyle: { fontSize: 14, color: '#12385f' } },
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { left: 70, right: 70, top: 45, bottom: 45 },
    xAxis: { type: 'category', data: d.map(x => x.period) },
    yAxis: [
      { type: 'value', name: '金额', axisLabel: { formatter: v => v >= 10000 ? (v / 10000) + '万' : v } },
      { type: 'value', name: '数量', position: 'right' },
    ],
    series: [
      { name: '销售额', type: 'line', smooth: true, itemStyle: { color: '#1677ff' },
        data: d.map(x => x.salesAmount) },
      { name: '采购额', type: 'line', smooth: true, itemStyle: { color: '#fa8c16' },
        data: d.map(x => x.purchaseAmount) },
      { name: '毛利额', type: 'line', smooth: true, itemStyle: { color: '#52c41a' },
        data: d.map(x => x.grossProfit) },
      { name: '签收数量', type: 'bar', yAxisIndex: 1, itemStyle: { color: '#91caff', opacity: .5 },
        data: d.map(x => x.salesQty) },
    ],
  }, true)
}
function renderPie() {
  const c = chartInit('pie', pieEl.value); if (!c) return
  c.setOption({
    title: { text: '分类销售占比', left: 'center', textStyle: { fontSize: 14, color: '#12385f' } },
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, type: 'scroll' },
    series: [{
      type: 'pie', radius: ['40%', '68%'], center: ['50%', '46%'],
      itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
      label: { formatter: '{b}\n{d}%' },
      data: (structure.value.categoryPie || []).map(x => ({ name: x.name, value: Number(x.value || 0) })),
    }],
  }, true)
}
function renderGoods() {
  const c = chartInit('goods', goodsEl.value); if (!c) return
  const d = [...(structure.value.topGoods || [])].reverse()
  c.setOption({
    title: { text: metric === 'gross_profit' ? 'TOP20 商品（毛利）' : 'TOP20 商品（销售额）',
      left: 'center', textStyle: { fontSize: 14, color: '#12385f' } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 130, right: 30, top: 45, bottom: 20 },
    xAxis: { type: 'value', axisLabel: { formatter: v => v >= 10000 ? (v / 10000) + '万' : v } },
    yAxis: { type: 'category', data: d.map(x => x.name), axisLabel: { width: 120, overflow: 'truncate' } },
    series: [{
      type: 'bar', itemStyle: { color: '#1677ff', borderRadius: [0, 4, 4, 0] },
      data: d.map(x => Number(x[metric] || 0)),
    }],
  }, true)
}
function renderCust() {
  const c = chartInit('cust', custEl.value); if (!c) return
  const d = [...(structure.value.topCustomers || [])].reverse()
  c.setOption({
    title: { text: 'TOP10 客户（签收净额）', left: 'center', textStyle: { fontSize: 14, color: '#12385f' } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 130, right: 30, top: 45, bottom: 20 },
    xAxis: { type: 'value', axisLabel: { formatter: v => v >= 10000 ? (v / 10000) + '万' : v } },
    yAxis: { type: 'category', data: d.map(x => x.name), axisLabel: { width: 120, overflow: 'truncate' } },
    series: [{
      type: 'bar', itemStyle: { color: '#52c41a', borderRadius: [0, 4, 4, 0] },
      data: d.map(x => Number(x.salesAmount || 0)),
    }],
  }, true)
}

function fmtKpi(v, k) {
  if (v === null || v === undefined) return '—'
  if (k.percent) return fmtPercent(v)
  return fmtNum(v)
}
function rateText(rate) {
  if (rate === null || rate === undefined) return '环比 —'
  return `环比 ${rate >= 0 ? '+' : ''}${(rate * 100).toFixed(1)}%`
}
function rateClass(rate) {
  if (rate === null || rate === undefined) return ''
  return rate >= 0 ? 'up' : 'down'
}
function filterText() {
  return `日期：${start.value}~${end.value}` + (filters.value.warehouse ? `；仓库：${filters.value.warehouse}` : '')
}
function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    exportRowsXlsx({
      reportName: '商品综合分析-明细', filterText: filterText(),
      columns: visibleColumns.value, treeRows: buildTreeRows(rows.value, [], nums), summary: summary.value,
    })
  } catch (e) { alert('导出失败：' + (e.message || '未知错误')) }
}
async function submitExport() {
  try {
    const res = await r.exportAsync(filterText())
    alert(res.message + '（任务号 ' + res.taskNo + '）')
  } catch (e) { alert('导出失败：' + (e.message || '未知错误')) }
}
</script>

<style scoped>
@import './report-page.css';
.ff { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #606266; }
.ff input, .ff select { height: 30px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 13px; min-width: 120px; }
.kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 10px; }
.kpi-card { background: #fff; border-radius: 6px; padding: 12px 16px; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.kpi-label { font-size: 12px; color: #909399; }
.kpi-value { font-size: 20px; font-weight: 600; color: #12385f; margin: 4px 0; }
.kpi-value.money { color: #d46b08; }
.kpi-rate { font-size: 12px; }
.kpi-rate.up { color: #f5222d; }
.kpi-rate.down { color: #52c41a; }
.chart-toolbar { display: flex; gap: 10px; margin-bottom: 10px; }
.seg { display: flex; border: 1px solid #dcdfe6; border-radius: 4px; overflow: hidden; }
.seg button { border: 0; background: #fff; padding: 5px 14px; cursor: pointer; font-size: 13px; }
.seg button.active { background: #1677ff; color: #fff; }
.chart-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; margin-bottom: 10px; }
.chart-card { background: #fff; border-radius: 6px; padding: 8px; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.chart-card.wide { grid-column: 1 / -1; }
.chart-box { width: 100%; height: 320px; }
</style>
