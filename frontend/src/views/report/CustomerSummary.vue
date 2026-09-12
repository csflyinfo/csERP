<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        客户销售汇总表
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            客户粒度的销售总览（一行一客户，不分分组层级）：签收单数/销售金额/退货/净销售/成本/毛利/客单价，
            以及本期回款额、期末应收余额、其中逾期（取自财务应收台账，截止期末）。
            销售按司机签收日期口径（含税），退货按退货审核日期；客户数占比=该客户签收单数÷全部客户签收单数；
            客单价=净销售额÷签收单数。回款/应收列按角色橘色脱敏。点击客户名或销售/退货金额可钻取到
            「客户商品销售汇总表」核对该客户的商品构成。默认期间为截止昨天的最近一个月。
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
      <div class="ff"><label>路线</label><input v-model="filters.routeLine" @keyup.enter="onSearch"></div>
      <div class="ff"><label>业务员</label><input v-model="filters.salesman" @keyup.enter="onSearch"></div>
      <div class="ff">
        <label>仓库</label>
        <select v-model="filters.warehouse">
          <option value="">全部仓库</option>
          <option v-for="w in warehouses" :key="w" :value="w">{{ w }}</option>
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
import { loadWarehouses } from '@/api/report-center.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'customerSummaryReport'
const CODE = 'customer_summary'
const STORE_KEY = 'rpt:customer_summary:state'
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE)
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])

// 汇总表一次性取全部叶子行（护栏上限 10 万）
pageSize.value = 100000

onMounted(async () => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  rememberLoad()
  await r.query()
})

function rememberLoad() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY) || '{}')
    Object.assign(filters.value, saved)
  } catch { /* 忽略损坏缓存 */ }
}
function rememberSave() {
  localStorage.setItem(STORE_KEY, JSON.stringify(filters.value))
}

// 键名与后端 CustomerSummaryDefinition 列定义严格一致
const ALL_COLUMNS = [
  { key: 'customerCode', title: '客户编号', width: 100, drill: true, sortable: true },
  { key: 'customerName', title: '客户名称', width: 160, drill: true, sortable: true },
  { key: 'customerLevel', title: '客户等级', width: 90 },
  { key: 'territory', title: '区域', width: 90 },
  { key: 'routeLine', title: '路线', width: 90 },
  { key: 'salesman', title: '业务员', width: 90 },
  { key: 'receiptCount', title: '签收单数', num: true, width: 90, sortable: true },
  { key: 'signedAmount', title: '销售金额', num: true, money: true, sensitive: true, width: 120, drill: true, sortable: true },
  { key: 'returnAmount', title: '退货金额', num: true, money: true, sensitive: true, width: 120, drill: true, sortable: true },
  { key: 'netAmount', title: '净销售额', num: true, money: true, sensitive: true, width: 120, drill: true, sortable: true },
  { key: 'netQtyBase', title: '净销售数量(小单位)', num: true, width: 150 },
  { key: 'costAmount', title: '成本金额', num: true, money: true, sensitive: true, width: 120, sortable: true },
  { key: 'grossProfit', title: '毛利额', num: true, money: true, sensitive: true, width: 120, sortable: true },
  { key: 'grossProfitRate', title: '毛利率', num: true, percent: true, sensitive: true, width: 100 },
  { key: 'customerShare', title: '客户数占比', num: true, percent: true, width: 100 },
  { key: 'avgOrderAmount', title: '客单价', num: true, money: true, sensitive: true, width: 110, sortable: true },
  { key: 'receivedAmount', title: '本期回款额', num: true, money: true, sensitive: true, width: 120, sortable: true },
  { key: 'arBalance', title: '期末应收余额', num: true, money: true, sensitive: true, width: 130, sortable: true },
  { key: 'overdueAmount', title: '其中逾期', num: true, money: true, sensitive: true, width: 120, sortable: true },
]
const visibleColumns = computed(() =>
  ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

function onSearch() {
  pageNo.value = 1
  rememberSave()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = {}
  pageNo.value = 1
  sortField.value = ''
  sortOrder.value = ''
  rememberSave()
  r.resetDate()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onSort({ field, order }) {
  sortField.value = field
  sortOrder.value = order
  r.query().catch(() => {})
}

/** 钻取到 #11 客户商品销售汇总：客户+期间；签收列带「销售签收」、退货列带「销售退货」 */
function onDrill({ row, col }) {
  const q = {
    drill: '1', from: CODE, start: start.value, end: end.value,
    customer: row.customerName,
  }
  if (col.key === 'signedAmount') q.billType = '销售签收'
  else if (col.key === 'returnAmount') q.billType = '销售退货'
  const f = filters.value
  if (f.customerLevel) q.customerLevel = f.customerLevel
  if (f.territory) q.territory = f.territory
  if (f.routeLine) q.routeLine = f.routeLine
  if (f.salesman) q.salesman = f.salesman
  if (f.warehouse) q.warehouse = f.warehouse
  router.push({ path: '/report/customer-goods-summary', query: q })
}

function filterText() {
  const f = filters.value
  const parts = [`日期：${start.value}~${end.value}`]
  if (f.customer) parts.push(`客户：${f.customer}`)
  if (f.customerLevel) parts.push(`等级：${f.customerLevel}`)
  if (f.territory) parts.push(`区域：${f.territory}`)
  if (f.routeLine) parts.push(`路线：${f.routeLine}`)
  if (f.salesman) parts.push(`业务员：${f.salesman}`)
  if (f.warehouse) parts.push(`仓库：${f.warehouse}`)
  return parts.join('；')
}

function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    const treeRows = buildTreeRows(rows.value, [], nums)
    exportRowsXlsx({
      reportName: '客户销售汇总表',
      filterText: filterText(),
      columns: visibleColumns.value,
      treeRows,
      summary: summary.value,
    })
  } catch (e) {
    alert('导出失败：' + (e.message || '未知错误'))
  }
}

async function submitExport() {
  try {
    const res = await r.exportAsync(filterText())
    alert(res.message + '（任务号 ' + res.taskNo + '）')
  } catch (e) {
    alert('导出失败：' + (e.message || '未知错误'))
  }
}
</script>

<style scoped>
@import './report-page.css';
</style>
