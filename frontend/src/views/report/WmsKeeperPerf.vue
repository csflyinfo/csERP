<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        库管员绩效报表
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            直接汇总各类 WMS 已完成作业单（不依赖 wms_performance_daily 日报，该日报只覆盖收货/上架两类）：
            收货（结束收货）、上架、拣货（PICKED）、复核（PASS）、补货、盘点（审批通过）、库存调整、异常。
            作业归属日取完成时刻日期；一人期内跨岗位按岗位拆行。盘盈盘亏金额=差异×批次移动平均成本
            （取不到按商品仓均价），按库存成本字段权限脱敏。作业时长仅拣货/上架/补货可算，
            作业效率=可计量作业总行数÷作业小时；差错率=异常单数÷作业总行数。默认期间自然月本月。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button class="btn-primary" @click="submitExport">异步导出</button>
      </div>
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end" initial-preset="thisMonth">
      <div class="ff">
        <label>仓库</label>
        <select v-model="filters.warehouse">
          <option value="">全部仓库</option>
          <option v-for="w in warehouses" :key="w" :value="w">{{ w }}</option>
        </select>
      </div>
      <div class="ff"><label>库管员</label><input v-model="filters.operator" placeholder="账号/姓名" @keyup.enter="onSearch"></div>
      <div class="ff">
        <label>岗位</label>
        <select v-model="filters.roleCode">
          <option value="">全部岗位</option>
          <option v-for="r in ROLES" :key="r.v" :value="r.v">{{ r.t }}</option>
        </select>
      </div>
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
import { computed, onMounted } from 'vue'
import ReportFilterBar from '@/components/report/ReportFilterBar.vue'
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows } from '@/components/report/report-table.js'
import { loadWarehouses } from '@/api/report-center.js'
import { ref } from 'vue'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'wmsKeeperPerfReport'
const CODE = 'wms_keeper_perf'
const STORE_KEY = 'rpt:wms_keeper_perf:state'
const ROLES = [
  { v: 'RECEIVER', t: '收货员' }, { v: 'PUTAWAY', t: '上架员' },
  { v: 'PICKER', t: '拣货员' }, { v: 'CHECKER', t: '复核员' },
  { v: 'KEEPER', t: '仓管员' },
]
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE, { naturalMonth: true })
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])
pageSize.value = 100000

onMounted(() => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  try {
    Object.assign(filters.value, JSON.parse(localStorage.getItem(STORE_KEY) || '{}'))
  } catch { /* ignore */ }
  r.query().catch(() => {})
})

const ALL_COLUMNS = [
  { key: 'warehouse', title: '仓库', width: 120 },
  { key: 'operator', title: '库管员账号', width: 110 },
  { key: 'userName', title: '库管员', width: 100 },
  { key: 'roleName', title: '岗位', width: 90 },
  { key: 'workDays', title: '作业天数', num: true, width: 90, sortable: true },
  { key: 'receiveOrders', title: '收货单数', num: true },
  { key: 'receiveQty', title: '收货数量', num: true, sortable: true },
  { key: 'receiveLines', title: '收货行数', num: true },
  { key: 'putawayQty', title: '上架数量', num: true },
  { key: 'putawayLines', title: '上架行数', num: true },
  { key: 'pickQty', title: '拣货数量', num: true },
  { key: 'pickLines', title: '拣货行数', num: true },
  { key: 'checkOrders', title: '复核单数', num: true },
  { key: 'replenishCount', title: '补货次数', num: true },
  { key: 'stocktakeOrders', title: '盘点单数', num: true },
  { key: 'gainAmount', title: '盘盈金额', num: true, money: true, sensitive: true },
  { key: 'lossAmount', title: '盘亏金额', num: true, money: true, sensitive: true },
  { key: 'errorCount', title: '异常/差错次数', num: true, sortable: true },
  { key: 'workMinutes', title: '作业时长(分钟)', num: true },
  { key: 'efficiency', title: '作业效率(行/小时)', num: true, fixed: 2, sortable: true },
  { key: 'errorRate', title: '差错率', num: true, percent: true, sortable: true },
]
const visibleColumns = computed(() => ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

function onSearch() {
  pageNo.value = 1
  localStorage.setItem(STORE_KEY, JSON.stringify(filters.value))
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = {}
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
  const f = filters.value
  return `日期：${start.value}~${end.value}` + [
    f.warehouse && `仓库：${f.warehouse}`,
    f.operator && `库管员：${f.operator}`,
    f.roleCode && `岗位：${f.roleCode}`,
  ].filter(Boolean).join('；')
}
function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    exportRowsXlsx({
      reportName: '库管员绩效报表', filterText: filterText(),
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
</style>
