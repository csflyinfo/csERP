<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        司机配送绩效报表
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            司机归因一律以调度单司机为准（不使用签收人字段，避免未挂调度的签收错归系统账号）。
            计划单数按调度日；签收/拒收单数、签收数量、配送货值、客户数按<b>签收日</b>统计
            （DELIVERED/PARTIAL/RETURNED 计签收，REJECTED 计拒收，改期不计）；趟次取非取消行程；
            随车退货取已回库单；代收/缴款取已审核交账单（代收=现金+线上，差异=实际交回−应交回）；
            行驶时长=调度发车→回车时长（系统只记录发车里程读数，无回车里程，本期不产出里程列；
            准时率依赖预约时段，本期不做）。默认期间自然月本月。货值与代收按字段权限脱敏。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button class="btn-primary" @click="submitExport">异步导出</button>
      </div>
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end" initial-preset="thisMonth">
      <div class="ff"><label>司机</label><input v-model="filters.driver" placeholder="工号/姓名/手机号" @keyup.enter="onSearch"></div>
      <div class="ff"><label>线路</label><input v-model="filters.routeLine" @keyup.enter="onSearch"></div>
      <label class="rpt-check"><input type="checkbox" v-model="includeInactive" @change="onSearch">含无作业司机</label>
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
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import ReportFilterBar from '@/components/report/ReportFilterBar.vue'
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows } from '@/components/report/report-table.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'driverDeliveryPerfReport'
const CODE = 'driver_delivery_perf'
const STORE_KEY = 'rpt:driver_delivery_perf:state'
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE, { naturalMonth: true })
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const includeInactive = ref(false)
pageSize.value = 100000

onMounted(() => {
  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY) || '{}')
    Object.assign(filters.value, saved)
    includeInactive.value = filters.value.includeInactive === '1'
  } catch { /* ignore */ }
  r.query().catch(() => {})
})

const ALL_COLUMNS = [
  { key: 'driverId', title: '司机工号', width: 100 },
  { key: 'driverName', title: '司机', width: 100 },
  { key: 'mobile', title: '手机号', width: 120 },
  { key: 'tripCount', title: '趟次数', num: true, sortable: true },
  { key: 'plannedCount', title: '计划单数', num: true },
  { key: 'signedCount', title: '签收完成单数', num: true, sortable: true },
  { key: 'signRate', title: '签收率', num: true, percent: true, sortable: true },
  { key: 'rejectCount', title: '拒收单数', num: true, sortable: true },
  { key: 'rejectRate', title: '拒收率', num: true, percent: true },
  { key: 'returnCount', title: '随车退货单数', num: true },
  { key: 'customerCount', title: '配送客户数', num: true },
  { key: 'signedQty', title: '签收数量', num: true, sortable: true },
  { key: 'rejectQty', title: '拒收数量', num: true },
  { key: 'signAmount', title: '配送货值(签收含税)', num: true, money: true, sensitive: true, sortable: true },
  { key: 'codCollected', title: '代收货款金额', num: true, money: true, sensitive: true },
  { key: 'codSubmit', title: '实际缴款金额', num: true, money: true, sensitive: true },
  { key: 'codDiff', title: '缴款差异', num: true, money: true, sensitive: true },
  { key: 'driveMinutes', title: '行驶时长(分钟)', num: true },
  { key: 'avgMinutesPerOrder', title: '平均每单时长(分钟)', num: true, fixed: 1 },
  { key: 'exceptionCount', title: '异常次数', num: true, sortable: true },
]
const visibleColumns = computed(() => ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

function onSearch() {
  pageNo.value = 1
  filters.value.includeInactive = includeInactive.value ? '1' : ''
  localStorage.setItem(STORE_KEY, JSON.stringify(filters.value))
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = {}
  includeInactive.value = false
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
function filterText() {
  return `日期：${start.value}~${end.value}` + (filters.value.driver ? `；司机：${filters.value.driver}` : '')
}
function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    exportRowsXlsx({
      reportName: '司机配送绩效报表', filterText: filterText(),
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
.ff input { height: 30px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 13px; min-width: 120px; }
.rpt-check { font-size: 13px; color: #303133; display: flex; align-items: center; gap: 4px; }
</style>
