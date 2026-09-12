<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        供应商应付汇总表
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            期间发生额滚动：期初应付 + 本期新增（采购立账，含税）− 本期付款核销 + 折让/核销 = 期末应付。
            默认期间：截止昨天，起始为截止日上月同日的前一天。默认仅显示期末有余额供应商。
            其中逾期=期末口径到期日 ≤ 期末的未核销余额；已收票/未收票取当前台账状态。金额按角色脱敏。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button class="btn-primary" @click="submitExport">异步导出</button>
      </div>
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end">
      <div class="ff"><label>供应商</label><input v-model="filters.supplier" placeholder="编号/名称" @keyup.enter="onSearch"></div>
      <div class="ff"><label>采购员</label><input v-model="filters.buyer" @keyup.enter="onSearch"></div>
      <div class="ff"><label>结算方式</label><input v-model="filters.settlementMethod" @keyup.enter="onSearch"></div>
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

const MODULE = 'supplierApSummaryReport'
const CODE = 'supplier_ap_summary'
const STORE_KEY = 'rpt:supplier_ap_summary:state'
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
  { key: 'supplierCode', title: '供应商编号', width: 120, drill: true },
  { key: 'supplierName', title: '供应商名称', width: 180, drill: true },
  { key: 'buyer', title: '采购员', width: 90 },
  { key: 'settlementMethod', title: '结算方式', width: 100 },
  { key: 'openingAmount', title: '期初应付', num: true, money: true, sensitive: true },
  { key: 'newAmount', title: '本期新增应付', num: true, money: true, sensitive: true },
  { key: 'paidAmount', title: '本期付款', num: true, money: true, sensitive: true },
  { key: 'discountAmount', title: '折让/核销', num: true, money: true, sensitive: true },
  { key: 'endingAmount', title: '期末应付', num: true, money: true, sensitive: true, sortable: true, drill: true },
  { key: 'overdueAmount', title: '其中逾期', num: true, money: true, sensitive: true, sortable: true, drill: true },
  { key: 'invoicedAmount', title: '已收票金额', num: true, money: true, sensitive: true },
  { key: 'uninvoicedAmount', title: '未收票金额', num: true, money: true, sensitive: true },
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
    path: '/report/ap-aging',
    query: { cutoff: end.value, supplier: row.supplierName },
  })
}
function filterText() {
  return `日期：${start.value}~${end.value}` + (filters.value.supplier ? `；供应商：${filters.value.supplier}` : '')
}
function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    exportRowsXlsx({
      reportName: '供应商应付汇总表', filterText: filterText(),
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
</style>
