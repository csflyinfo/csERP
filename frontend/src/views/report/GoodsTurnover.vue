<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        商品周转率分析
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            周转率(次)=期间销售成本（K8 签收配比成本：出库成本−拒收回库−销售退货成本，按签收日落期间）÷平均库存成本；
            平均库存=（期初+期末）÷2，取日结快照（期初=开始日前一天、期末=截止日）；周转天数=期间日历天数÷周转率。
            呆滞标记：期末有库存且零周转，或周转天数 &gt; 系统参数 REPORT_SLOW_TURNOVER_DAYS（默认 60 天）。
            默认周转率升序（最慢在前）。合计周转率为加权值。期初/期末/入库金额按库存成本权限、
            销售成本按成本权限、毛利率按毛利权限分别脱敏。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button class="btn-primary" @click="submitExport">异步导出</button>
      </div>
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end">
      <div class="ff">
        <label>汇总粒度</label>
        <select v-model="filters.level">
          <option value="goods">按商品汇总</option>
          <option value="warehouse">商品+仓库</option>
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
      <div class="ff">
        <label>存储属性</label>
        <select v-model="filters.storageProperty">
          <option value="">全部</option>
          <option value="常温">常温</option>
          <option value="冷藏">冷藏</option>
          <option value="冷冻">冷冻</option>
        </select>
      </div>
      <label class="rpt-check"><input type="checkbox" v-model="slowOnly" @change="onSearch">仅看呆滞品</label>
      <template #actions>
        <button class="btn-primary" @click="onSearch">查询</button>
        <button class="btn-plain" @click="onReset">重置</button>
      </template>
    </ReportFilterBar>

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
      @drill="onDrill"
      :row-class-fn="rowClass"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ReportFilterBar from '@/components/report/ReportFilterBar.vue'
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows } from '@/components/report/report-table.js'
import { loadWarehouses } from '@/api/report-center.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'goodsTurnoverReport'
const CODE = 'goods_turnover'
const STORE_KEY = 'rpt:goods_turnover:state'
const { actionHidden, canViewColumn } = useRbac(MODULE)
const router = useRouter()

const r = useCenterReport(CODE)
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])
const slowOnly = ref(false)
filters.value = { level: 'goods' }
pageSize.value = 100000

onMounted(() => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY) || '{}')
    Object.assign(filters.value, saved)
    slowOnly.value = filters.value.slowOnly === '1'
  } catch { /* ignore */ }
  r.query().catch(() => {})
})

const ALL_COLUMNS = [
  { key: 'goodsCode', title: '商品编号', width: 110, drill: true },
  { key: 'goodsName', title: '商品名称', width: 180 },
  { key: 'brandName', title: '品牌', width: 100 },
  { key: 'categoryName', title: '商品类别', width: 110 },
  { key: 'storageProperty', title: '存储属性', width: 90 },
  { key: 'baseUnit', title: '单位', width: 70 },
  { key: 'warehouse', title: '仓库', width: 120 },
  { key: 'openQty', title: '期初数量', num: true },
  { key: 'openAmount', title: '期初成本金额', num: true, money: true, sensitive: true },
  { key: 'inAmount', title: '本期入库金额', num: true, money: true, sensitive: true },
  { key: 'salesCost', title: '本期销售成本', num: true, money: true, sensitive: true, sortable: true },
  { key: 'closeQty', title: '期末数量', num: true },
  { key: 'closeAmount', title: '期末成本金额', num: true, money: true, sensitive: true },
  { key: 'avgStockAmount', title: '平均库存金额', num: true, money: true, sensitive: true },
  { key: 'turnoverRate', title: '周转率(次)', num: true, fixed: 3, sortable: true },
  { key: 'turnoverDays', title: '周转天数', num: true, fixed: 1, sortable: true },
  { key: 'salesAmount', title: '期间销售额', num: true, money: true, sensitive: true, sortable: true },
  { key: 'grossMarginRate', title: '毛利率', num: true, percent: true, sensitive: true },
  { key: 'slowFlag', title: '呆滞标记', width: 90 },
]
const visibleColumns = computed(() => ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

function rowClass(row) { return row.slowFlag === '呆滞' ? 'row-slow' : '' }

function onSearch() {
  pageNo.value = 1
  filters.value.slowOnly = slowOnly.value ? '1' : ''
  localStorage.setItem(STORE_KEY, JSON.stringify(filters.value))
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = { level: 'goods' }
  slowOnly.value = false
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
function onDrill({ row }) {
  // 商品 → #9 库存台账（按当前期间，商品+仓库）
  router.push({
    path: '/report/stock-ledger',
    query: {
      drill: '1', from: 'goods_turnover',
      start: start.value, end: end.value,
      goods: row.goodsCode, warehouse: row.warehouse || filters.value.warehouse || '',
    },
  })
}
function filterText() {
  return `日期：${start.value}~${end.value}；粒度：${filters.value.level === 'warehouse' ? '商品+仓' : '商品'}`
}
function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    exportRowsXlsx({
      reportName: '商品周转率分析', filterText: filterText(),
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
.rpt-check { font-size: 13px; color: #303133; display: flex; align-items: center; gap: 4px; }
:deep(.row-slow) { background: #fff7e6 !important; }
:deep(.row-slow td:last-child) { color: #d46b08; font-weight: 600; }
</style>
