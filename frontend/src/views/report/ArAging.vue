<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        应收账款账龄分析表
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            时点报表，以「截止日期」为准（默认今天）：仅统计立账日 ≤ 截止日的应收，回款核销流水按核销发生时刻回放，
            截止日后的立账/核销一律不参与。未到期=到期日晚于截止日（含当天）或余额为负（预收）；
            逾期分桶 1-30/31-60/61-90/91-180/180 以上（边界按天数）。金额为含税口径，按角色脱敏。
            信用额度占用率 ≥ 80% 红色提示。可切换「按客户汇总 / 按单据明细」两个视图。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button class="btn-primary" @click="submitExport">异步导出</button>
      </div>
    </div>

    <div class="rpt-filter-card">
      <div class="rpt-filter-row">
        <div class="rpt-filter-field">
          <label>截止日期</label>
          <input type="date" v-model="filters.cutoff">
        </div>
        <div class="rpt-filter-field">
          <label>视图</label>
          <select v-model="filters.viewMode">
            <option value="customer">按客户汇总</option>
            <option value="bill">按单据明细</option>
          </select>
        </div>
        <div class="rpt-filter-field"><label>客户</label><input v-model="filters.customer" placeholder="编号/名称" @keyup.enter="onSearch"></div>
        <div class="rpt-filter-field"><label>客户等级</label><input v-model="filters.customerLevel" @keyup.enter="onSearch"></div>
        <div class="rpt-filter-field"><label>区域</label><input v-model="filters.territory" @keyup.enter="onSearch"></div>
        <div class="rpt-filter-field"><label>业务员</label><input v-model="filters.salesman" @keyup.enter="onSearch"></div>
        <div class="rpt-filter-field"><label>最低未收余额</label><input v-model="filters.minOutstanding" type="number" @keyup.enter="onSearch"></div>
        <label class="rpt-check"><input type="checkbox" v-model="includeSettled" @change="onSearch">含已结清</label>
        <label class="rpt-check"><input type="checkbox" :checked="filters.onlyOverdue === '1'" @change="onOnlyOverdue">仅看逾期</label>
        <div class="rpt-filter-actions">
          <button class="btn-primary" @click="onSearch">查询</button>
          <button class="btn-plain" @click="onReset">重置</button>
        </div>
      </div>
    </div>

    <div class="rpt-chart-card">
      <AgingBucketChart :buckets="bucketData" title="应收账龄分布（合计行口径）" />
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
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import AgingBucketChart from '@/components/report/AgingBucketChart.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows } from '@/components/report/report-table.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'arAgingReport'
const CODE = 'ar_aging'
const STORE_KEY = 'rpt:ar_aging:state'
const { actionHidden, canViewColumn } = useRbac(MODULE)

const today = () => {
  const d = new Date(); const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
const r = useCenterReport(CODE)
const { filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const includeSettled = ref(false)
filters.value = { cutoff: today(), viewMode: 'customer' }
pageSize.value = 100000

const route = useRoute()
onMounted(() => {
  rememberLoad()
  // 汇总表钻取带入（cutoff/customer），优先级高于本地记忆
  if (route.query.cutoff) filters.value.cutoff = route.query.cutoff
  if (route.query.customer) filters.value.customer = route.query.customer
  r.query().catch(() => {})
})

function rememberLoad() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY) || '{}')
    Object.assign(filters.value, saved)
  } catch { /* ignore */ }
  includeSettled.value = filters.value.includeSettled === '1'
}
function rememberSave() {
  const f = { ...filters.value }
  if (includeSettled.value) f.includeSettled = '1'
  else delete f.includeSettled
  delete f.onlyOverdue
  localStorage.setItem(STORE_KEY, JSON.stringify(f))
}

function onOnlyOverdue(e) {
  filters.value.onlyOverdue = e.target.checked ? '1' : ''
  onSearch()
}

const CUST_COLS = [
  { key: 'customerCode', title: '客户编号', width: 110 },
  { key: 'customerName', title: '客户名称', width: 170 },
  { key: 'customerLevel', title: '等级', width: 80 },
  { key: 'territory', title: '区域', width: 90 },
  { key: 'salesman', title: '业务员', width: 90 },
  { key: 'billCount', title: '单据数', num: true, width: 80 },
  { key: 'arAmount', title: '应收金额', num: true, money: true, sensitive: true, sortable: true },
  { key: 'receivedAmount', title: '已核销', num: true, money: true, sensitive: true },
  { key: 'outstanding', title: '截至日余额', num: true, money: true, sensitive: true, sortable: true },
  { key: 'unexpiredAmount', title: '未到期', num: true, money: true, sensitive: true },
  { key: 'age1To30', title: '逾期1-30天', num: true, money: true, sensitive: true },
  { key: 'age31To60', title: '逾期31-60天', num: true, money: true, sensitive: true },
  { key: 'age61To90', title: '逾期61-90天', num: true, money: true, sensitive: true },
  { key: 'age91To180', title: '逾期91-180天', num: true, money: true, sensitive: true },
  { key: 'age180Plus', title: '逾期180天以上', num: true, money: true, sensitive: true },
  { key: 'maxOverdueDays', title: '最长逾期天数', num: true, sortable: true },
  { key: 'overdueBillCount', title: '逾期单据数', num: true },
  { key: 'creditLimit', title: '信用额度', num: true, money: true, sensitive: true },
  { key: 'creditOccupancy', title: '信用占用率', num: true, percent: true, sortable: true,
    cellClass: row => Number(row.creditOccupancy) >= 0.8 ? 'cell-danger' : '' },
  { key: 'lastReceiptDate', title: '最近回款日期', width: 110 },
]
const BILL_COLS = [
  { key: 'arNo', title: '应收单号', width: 150 },
  { key: 'sourceBill', title: '来源单号', width: 150 },
  { key: 'billSourceType', title: '单据类型', width: 100 },
  { key: 'customerName', title: '客户名称', width: 170 },
  { key: 'billDate', title: '立账日期', width: 100 },
  { key: 'dueDate', title: '到期日期', width: 100 },
  { key: 'invoiceStatus', title: '开票状态', width: 90 },
  { key: 'agingBucket', title: '账龄区间', width: 100 },
  { key: 'arAmount', title: '应收金额', num: true, money: true, sensitive: true },
  { key: 'receivedAmount', title: '已核销', num: true, money: true, sensitive: true },
  { key: 'outstanding', title: '截至日余额', num: true, money: true, sensitive: true },
]
const visibleColumns = computed(() => {
  const all = filters.value.viewMode === 'bill' ? BILL_COLS : CUST_COLS
  return all.filter(c => canViewColumn(MODULE, c.title))
})

const bucketData = computed(() => [
  { label: '未到期', value: summary.value.unexpiredAmount || 0, overdue: false },
  { label: '1-30天', value: summary.value.age1To30 || 0, overdue: true, color: '#faad14' },
  { label: '31-60天', value: summary.value.age31To60 || 0, overdue: true, color: '#fa8c16' },
  { label: '61-90天', value: summary.value.age61To90 || 0, overdue: true, color: '#f56a00' },
  { label: '91-180天', value: summary.value.age91To180 || 0, overdue: true },
  { label: '180天以上', value: summary.value.age180Plus || 0, overdue: true, color: '#a8071a' },
])

function onSearch() {
  pageNo.value = 1
  rememberSave()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = { cutoff: today(), viewMode: filters.value.viewMode || 'customer' }
  includeSettled.value = false
  sortField.value = ''; sortOrder.value = ''
  rememberSave()
  r.resetDate()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onSort({ field, order }) {
  sortField.value = field
  sortOrder.value = order
  r.query().catch(() => {})
}
function filterText() {
  const f = filters.value
  return `截止：${f.cutoff}；视图：${f.viewMode === 'bill' ? '按单据' : '按客户'}`
}
function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    exportRowsXlsx({
      reportName: '应收账款账龄分析表',
      filterText: filterText(),
      columns: visibleColumns.value,
      treeRows: buildTreeRows(rows.value, [], nums),
      summary: summary.value,
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
.rpt-chart-card { background: #fff; border-radius: 6px; padding: 10px 14px; box-shadow: 0 1px 3px rgba(0,0,0,.06); margin-bottom: 10px; }
.rpt-check { font-size: 13px; color: #303133; display: flex; align-items: center; gap: 4px; }
:deep(.cell-danger) { color: #f5222d !important; font-weight: 600; }
</style>
