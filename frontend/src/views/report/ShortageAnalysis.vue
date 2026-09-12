<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        缺货商品分析
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            缺货判定（满足其一）：①可用+采购在途≤0；②可用+在途低于库存下限；③可销天数（可用÷近30天签收日均）
            小于主供应商交货周期。今日缺货页：库存与在途实时取数（在途=已审核未终止采购订单行−已审核入库实收），
            日均取最近一天日结快照；建议补货量=库存下限+日均×交货周期−可用−在途；连续缺货天数按日结快照回推，
            估算流失销量=日均×连续缺货天数。历史页：按期间扫描日结快照，统计缺货天数/最长连续缺货/估算流失销量
            （断货段内最高日均×连续天数）。测试/预发同样跑日结，无流水倒推。
          </div>
        </details>
      </div>
      <div class="rpt-ops">
        <button v-if="canAdmin" class="btn-plain" @click="rebuildSnapshot">立即重算快照</button>
        <button v-action-perms="actionHidden" class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button v-action-perms="actionHidden" class="btn-primary" @click="submitExport">异步导出</button>
      </div>
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end">
      <div class="ff">
        <label>缺货类型</label>
        <select v-model="filters.shortageType">
          <option value="">全部</option>
          <option value="ZERO">库存为0</option>
          <option value="SAFETY">低于库存下限</option>
          <option value="COVER">可销天数不足</option>
        </select>
      </div>
      <div class="ff">
        <label>仓库</label>
        <select v-model="filters.warehouse">
          <option value="">全部仓库</option>
          <option v-for="w in warehouses" :key="w" :value="w">{{ w }}</option>
        </select>
      </div>
      <div class="ff"><label>商品</label><input v-model="filters.goods" placeholder="编码/名称/条码" @keyup.enter="onSearch"></div>
      <div class="ff"><label>分类</label><input v-model="filters.categoryName" @keyup.enter="onSearch"></div>
      <div class="ff"><label>品牌</label><input v-model="filters.brandName" @keyup.enter="onSearch"></div>
      <div class="ff"><label>主供应商</label><input v-model="filters.supplier" @keyup.enter="onSearch"></div>
      <div class="ff"><label>采购员</label><input v-model="filters.buyer" @keyup.enter="onSearch"></div>
      <div v-if="tab !== 'realtime'" class="ff"><label>连续缺货≥N天</label><input v-model="filters.minConsecDays" type="number" min="1" @keyup.enter="onSearch"></div>
      <template #actions>
        <button class="btn-primary" @click="onSearch">查询</button>
        <button class="btn-plain" @click="onReset">重置</button>
      </template>
    </ReportFilterBar>

    <div class="rpt-tabs">
      <button :class="{ active: tab === 'realtime' }" @click="switchTab('realtime')">今日缺货（实时）</button>
      <button :class="{ active: tab === 'history' }" @click="switchTab('history')">缺货历史分析</button>
      <button :class="{ active: tab === 'trend' }" @click="switchTab('trend')">缺货趋势</button>
    </div>

    <div v-if="tab === 'trend'" class="rpt-chart-card">
      <div ref="trendChart" style="width:100%;height:340px"></div>
    </div>

    <DrillGridReport
      v-else
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
      @drill="onDrill"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import ReportFilterBar from '@/components/report/ReportFilterBar.vue'
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows } from '@/components/report/report-table.js'
import { loadWarehouses, rebuildStockSnapshot } from '@/api/report-center.js'
import { useRbac } from '@/composables/useRbac.js'
import { usePermStore } from '@/stores/perm.js'

const MODULE = 'shortageAnalysisReport'
const CODE = 'shortage_analysis'
const STORE_KEY = 'rpt:shortage_analysis:state'
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)
const canAdmin = usePermStore().hasFunc('report.admin.view')

const r = useCenterReport(CODE)
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])
const tab = ref('realtime')
const trendChart = ref(null)
let chart = null
filters.value = { tab: 'realtime' }
pageSize.value = 100000

const REALTIME_COLS = [
  { key: 'warehouse', title: '仓库', width: 120 },
  { key: 'goodsCode', title: '商品编号', width: 110, drill: true },
  { key: 'goodsName', title: '商品名称', width: 180 },
  { key: 'barcode', title: '条码', width: 110 },
  { key: 'brandName', title: '品牌', width: 90 },
  { key: 'categoryName', title: '分类', width: 100 },
  { key: 'storageProperty', title: '存储属性', width: 80 },
  { key: 'physicalQty', title: '实物数量', num: true },
  { key: 'availableQty', title: '可用数量', num: true, sortable: true },
  { key: 'onWayQty', title: '采购在途', num: true },
  { key: 'lowerLimit', title: '库存下限', num: true },
  { key: 'upperLimit', title: '库存上限', num: true },
  { key: 'salesQty7d', title: '近7天签收', num: true },
  { key: 'salesQty30d', title: '近30天签收', num: true },
  { key: 'avgDailySales', title: '日均销量', num: true, fixed: 2 },
  { key: 'coverDays', title: '可销天数', num: true, fixed: 1, sortable: true },
  { key: 'deliveryDays', title: '交货周期(天)', num: true },
  { key: 'suggestedQty', title: '建议补货量', num: true, sortable: true, drill: true },
  { key: 'consecShortDays', title: '连续缺货天数', num: true, sortable: true },
  { key: 'lostSalesQty', title: '估算流失销量', num: true, fixed: 1 },
  { key: 'supplierName', title: '主供应商', width: 160 },
  { key: 'buyer', title: '采购员', width: 90 },
]
const HISTORY_COLS = [
  { key: 'snapshotDate', title: '最近缺货日期', width: 110 },
  { key: 'warehouse', title: '仓库', width: 120 },
  { key: 'goodsCode', title: '商品编号', width: 110, drill: true },
  { key: 'goodsName', title: '商品名称', width: 180 },
  { key: 'brandName', title: '品牌', width: 90 },
  { key: 'categoryName', title: '分类', width: 100 },
  { key: 'availableQty', title: '最近可用量', num: true },
  { key: 'onWayQty', title: '最近在途', num: true },
  { key: 'lowerLimit', title: '库存下限', num: true },
  { key: 'avgDailySales', title: '日均销量', num: true, fixed: 2 },
  { key: 'shortDays', title: '缺货天数', num: true, sortable: true },
  { key: 'consecShortDays', title: '最长连续缺货天数', num: true, sortable: true },
  { key: 'lostSalesQty', title: '估算流失销量', num: true, fixed: 1, sortable: true },
  { key: 'supplierName', title: '主供应商', width: 160 },
  { key: 'buyer', title: '采购员', width: 90 },
]
const visibleColumns = computed(() =>
  (tab.value === 'history' ? HISTORY_COLS : REALTIME_COLS).filter(c => canViewColumn(MODULE, c.title)))

onMounted(() => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  try { Object.assign(filters.value, JSON.parse(localStorage.getItem(STORE_KEY) || '{}')) } catch { /* ignore */ }
  tab.value = filters.value.tab || 'realtime'
  r.query().catch(() => {})
})
watch(() => rows.value, () => { if (tab.value === 'trend') nextTick(renderTrend) })

function switchTab(t) {
  tab.value = t
  filters.value.tab = t
  pageNo.value = 1
  localStorage.setItem(STORE_KEY, JSON.stringify(filters.value))
  r.query().then(() => { if (t === 'trend') nextTick(renderTrend) }).catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onSearch() {
  pageNo.value = 1
  localStorage.setItem(STORE_KEY, JSON.stringify(filters.value))
  r.query().then(() => { if (tab.value === 'trend') nextTick(renderTrend) })
    .catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  const keep = { tab: tab.value }
  filters.value = keep
  sortField.value = ''; sortOrder.value = ''
  localStorage.removeItem(STORE_KEY)
  r.resetDate()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onSort({ field, order }) {
  sortField.value = field
  sortOrder.value = order
  r.query().catch(() => {})
}
function onDrill({ row, col }) {
  if (col.key === 'suggestedQty') {
    // 建议补货量 → #5 采购预测（带仓库与期间，落地后点查询）
    router.push({
      path: '/report/purchase-forecast',
      query: {
        drill: '1', from: 'shortage_analysis',
        start: start.value, end: end.value,
        warehouse: row.warehouse || '', goods: row.goodsCode,
      },
    })
  } else if (col.key === 'goodsCode') {
    // 商品 → #9 库存台账（历史行按最近缺货日期单日，实时行按查询期间）
    const d = tab.value === 'history' ? row.snapshotDate : null
    router.push({
      path: '/report/stock-ledger',
      query: {
        drill: '1', from: 'shortage_analysis',
        start: d || start.value, end: d || end.value,
        goods: row.goodsCode, warehouse: row.warehouse || '',
      },
    })
  }
}

function renderTrend() {
  if (!trendChart.value) return
  if (!chart) chart = echarts.init(trendChart.value)
  const data = [...rows.value].sort((a, b) => String(a.snapshotDate).localeCompare(String(b.snapshotDate)))
  const dates = [...new Set(data.map(x => x.snapshotDate))]
  const whs = [...new Set(data.map(x => x.warehouse || ''))]
  const series = whs.map(w => ({
    name: w || '（空仓）', type: 'line', smooth: true, connectNulls: true,
    data: dates.map(d => {
      const row = data.find(x => x.snapshotDate === d && (x.warehouse || '') === w)
      return row ? Number(row.shortSkuCount || 0) : 0
    }),
  }))
  chart.setOption({
    title: { text: '每日缺货 SKU 数趋势', left: 'center', textStyle: { fontSize: 14, color: '#12385f' } },
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, type: 'scroll' },
    grid: { left: 60, right: 24, top: 45, bottom: 50 },
    xAxis: { type: 'category', data: dates },
    yAxis: { type: 'value', minInterval: 1 },
    series,
  }, true)
}
onUnmounted(() => chart?.dispose())

async function rebuildSnapshot() {
  if (!confirm('立即按当前库存与签收数据重建昨天日结快照？（历史分析依赖每日快照）')) return
  try {
    const res = await rebuildStockSnapshot({})
    alert(`快照重建完成：${res.date} 共 ${res.rows} 行`)
    onSearch()
  } catch (e) { alert('重建失败：' + (e.message || '未知错误')) }
}
function filterText() {
  return `日期：${start.value}~${end.value}；页签：${tab.value}`
}
function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    exportRowsXlsx({
      reportName: tab.value === 'history' ? '缺货历史分析' : '缺货商品分析', filterText: filterText(),
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
.rpt-tabs { display: flex; gap: 8px; margin-bottom: 10px; }
.rpt-tabs button { border: 1px solid #dcdfe6; background: #fff; border-radius: 4px; padding: 6px 16px; cursor: pointer; font-size: 13px; }
.rpt-tabs button.active { background: #1677ff; color: #fff; border-color: #1677ff; }
</style>
