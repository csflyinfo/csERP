<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        客户应收汇总表
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            期间发生额滚动：期初应收 + 本期新增（签收/退货审核立账，含税）− 本期回款核销 = 期末应收，
            恒等式代数恒成立（预收表现为负数）。核销按 fin_reconcile_record 实算，不取当前余额反推。
            默认期间：截止昨天，起始为截止日上月同日的前一天。默认仅显示期末有余额客户。
            其中逾期=期末口径到期日 ≤ 期末的未核销余额。金额列按角色橘色脱敏；占用率≥80% 红色。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button class="btn-primary" @click="submitExport">异步导出</button>
      </div>
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end">
      <div class="ff"><label>客户</label><input v-model="filters.customer" placeholder="编号/名称" @keyup.enter="onSearch"></div>
      <div class="ff"><label>客户等级</label><input v-model="filters.customerLevel" @keyup.enter="onSearch"></div>
      <div class="ff"><label>区域</label><input v-model="filters.territory" @keyup.enter="onSearch"></div>
      <div class="ff"><label>业务员</label><input v-model="filters.salesman" @keyup.enter="onSearch"></div>
      <div class="ff"><label>最低期末余额</label><input v-model="filters.minEnding" type="number" @keyup.enter="onSearch"></div>
      <label class="rpt-check"><input type="checkbox" v-model="includeSettled" @change="onSearch">含已结清</label>
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
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'customerArSummaryReport'
const CODE = 'customer_ar_summary'
const STORE_KEY = 'rpt:customer_ar_summary:state'
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE)
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const includeSettled = ref(false)
pageSize.value = 100000

onMounted(() => {
  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY) || '{}')
    Object.assign(filters.value, saved)
    includeSettled.value = filters.value.includeSettled === '1'
  } catch { /* ignore */ }
  r.query().catch(() => {})
})

const ALL_COLUMNS = [
  { key: 'customerCode', title: '客户编号', width: 110, drill: true },
  { key: 'customerName', title: '客户名称', width: 170, drill: true },
  { key: 'customerLevel', title: '客户等级', width: 90 },
  { key: 'territory', title: '区域', width: 90 },
  { key: 'salesman', title: '业务员', width: 90 },
  { key: 'openingAmount', title: '期初应收', num: true, money: true, sensitive: true },
  { key: 'newAmount', title: '本期新增应收', num: true, money: true, sensitive: true, sortable: true },
  { key: 'receivedAmount', title: '本期回款', num: true, money: true, sensitive: true },
  { key: 'writeoffAmount', title: '其中减免/抹零', num: true, money: true, sensitive: true },
  { key: 'endingAmount', title: '期末应收', num: true, money: true, sensitive: true, sortable: true, drill: true },
  { key: 'overdueAmount', title: '其中逾期', num: true, money: true, sensitive: true, sortable: true, drill: true },
  { key: 'creditLimit', title: '信用额度', num: true, money: true, sensitive: true },
  { key: 'creditOccupancy', title: '信用额度占用率', num: true, percent: true, sortable: true,
    cellClass: row => Number(row.creditOccupancy) >= 0.8 ? 'cell-danger' : '' },
]
const visibleColumns = computed(() => ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

function onSearch() {
  pageNo.value = 1
  filters.value.includeSettled = includeSettled.value ? '1' : ''
  localStorage.setItem(STORE_KEY, JSON.stringify(filters.value))
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = {}
  includeSettled.value = false
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
  router.push({
    path: '/report/ar-aging',
    query: { cutoff: end.value, customer: row.customerName },
  })
}
function filterText() {
  const f = filters.value
  return `日期：${start.value}~${end.value}` + (f.customer ? `；客户：${f.customer}` : '')
}
function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    exportRowsXlsx({
      reportName: '客户应收汇总表', filterText: filterText(),
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
.rpt-check { font-size: 13px; color: #303133; display: flex; align-items: center; gap: 4px; }
:deep(.cell-danger) { color: #f5222d !important; font-weight: 600; }
</style>
