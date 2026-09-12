<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        销售订单明细查询
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            默认只取<b>已审核</b>销售订单（待审核/已关闭/已作废可在状态中切换）；一行一订单商品行。
            已出库/未出库量按已审核出库单实时聚合；已签收/拒收量按司机签收回单聚合，
            <b>已签收金额为签收口径（含税）</b>，拒收数量仅在此表与拒收入库台账体现、不计入销售汇总。
            头级窗口路径保证大订单量下的分页性能；按出库/签收状态筛选时自动切换全量口径路径。
            单价/箱价/金额列按角色橘色脱敏。点击订单号联查销售订单列表。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button class="btn-primary" @click="submitExport">异步导出</button>
      </div>
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end">
      <div class="ff"><label>订单号</label><input v-model="filters.orderNo" placeholder="订单号模糊" @keyup.enter="onSearch"></div>
      <div class="ff">
        <label>审核状态</label>
        <select v-model="filters.status">
          <option value="">已审核（默认）</option>
          <option value="已审核">已审核</option>
          <option value="待审核">待审核</option>
          <option value="已关闭">已关闭</option>
          <option value="已作废">已作废</option>
        </select>
      </div>
      <div class="ff">
        <label>出库状态</label>
        <select v-model="filters.outboundStatus">
          <option value="">全部</option>
          <option value="未出库">未出库</option>
          <option value="部分出库">部分出库</option>
          <option value="已出库">已出库</option>
        </select>
      </div>
      <div class="ff">
        <label>签收状态</label>
        <select v-model="filters.signStatus">
          <option value="">全部</option>
          <option value="未签收">未签收</option>
          <option value="部分签收">部分签收</option>
          <option value="已签收">已签收</option>
          <option value="全部拒收">全部拒收</option>
        </select>
      </div>
      <div class="ff"><label>客户</label><input v-model="filters.customer" placeholder="编号/名称" @keyup.enter="onSearch"></div>
      <div class="ff"><label>业务员</label><input v-model="filters.salesman" @keyup.enter="onSearch"></div>
      <div class="ff"><label>区域</label><input v-model="filters.territory" @keyup.enter="onSearch"></div>
      <div class="ff"><label>路线</label><input v-model="filters.routeLine" @keyup.enter="onSearch"></div>
      <div class="ff">
        <label>仓库</label>
        <select v-model="filters.warehouse">
          <option value="">全部仓库</option>
          <option v-for="w in warehouses" :key="w" :value="w">{{ w }}</option>
        </select>
      </div>
      <div class="ff"><label>商品</label><input v-model="filters.goods" placeholder="编号/名称/条码" @keyup.enter="onSearch"></div>
      <div class="ff"><label>商品分类</label><input v-model="filters.categoryName" @keyup.enter="onSearch"></div>
      <div class="ff"><label>品牌</label><input v-model="filters.brandName" @keyup.enter="onSearch"></div>
      <div class="ff">
        <label>存储属性</label>
        <select v-model="filters.storageProperty">
          <option value="">全部</option>
          <option value="常温">常温</option>
          <option value="冷藏">冷藏</option>
          <option value="冷冻">冷冻</option>
          <option value="恒温">恒温</option>
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
      paged
      :total="total"
      :page-no="pageNo"
      :page-size="pageSize"
      :sort-field="sortField"
      :sort-order="sortOrder"
      @page-change="onPage"
      @size-change="onSize"
      @sort="onSort"
      @link="openOrder"
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

const MODULE = 'salesOrderDetailReport'
const CODE = 'sales_order_detail'
const STORE_KEY = 'rpt:sales_order_detail:filters'
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE)
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])

// 键名与后端 SalesOrderDetailDefinition 32 列严格一致
const ALL_COLUMNS = [
  { key: 'orderNo', title: '订单号', link: true, width: 150, sortable: true },
  { key: 'billDate', title: '订单日期', width: 100, sortable: true },
  { key: 'statusText', title: '审核状态', width: 90 },
  { key: 'outboundStatusText', title: '出库状态', width: 90 },
  { key: 'signStatusText', title: '签收状态', width: 90 },
  { key: 'customerCode', title: '客户编号', width: 100 },
  { key: 'customerName', title: '客户名称', width: 150, sortable: true },
  { key: 'salesman', title: '业务员', width: 90 },
  { key: 'territory', title: '区域', width: 90 },
  { key: 'routeLine', title: '路线', width: 90 },
  { key: 'goodsCode', title: '商品编号', width: 100, sortable: true },
  { key: 'goodsName', title: '商品名称', width: 170 },
  { key: 'barcode', title: '条码', width: 90 },
  { key: 'baseUnit', title: '基本单位', width: 80 },
  { key: 'baseQty', title: '数量(小单位)', num: true, width: 120, sortable: true },
  { key: 'unitPriceBase', title: '单价(小单位)', num: true, money: true, sensitive: true, width: 110 },
  { key: 'packageQty', title: '件数', num: true, width: 80 },
  { key: 'largeUnit', title: '大单位', width: 80 },
  { key: 'boxPrice', title: '箱价', num: true, money: true, sensitive: true, width: 100 },
  { key: 'amount', title: '订单金额', num: true, money: true, sensitive: true, width: 120, sortable: true },
  { key: 'outboundBase', title: '已出库数量(小单位)', num: true, width: 140, sortable: true },
  { key: 'outboundPackage', title: '已出库件数', num: true, width: 100 },
  { key: 'unoutboundBase', title: '未出库数量(小单位)', num: true, width: 140 },
  { key: 'unoutboundPackage', title: '未出库件数', num: true, width: 100 },
  { key: 'rejectBase', title: '已拒收数量(小单位)', num: true, width: 140 },
  { key: 'signedBase', title: '已签收数量(小单位)', num: true, width: 140, sortable: true },
  { key: 'signedPackage', title: '已签收件数', num: true, width: 100 },
  { key: 'signedAmount', title: '签收金额', num: true, money: true, sensitive: true, width: 120 },
  { key: 'signDate', title: '签收日期', width: 100 },
  { key: 'brandName', title: '品牌', width: 100 },
  { key: 'categoryName', title: '商品类别', width: 100 },
  { key: 'storageProperty', title: '存储属性', width: 90 },
]
const visibleColumns = computed(() =>
  ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

onMounted(async () => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  rememberLoad()
  // 与后端默认一致：不选状态时只看已审核
  filters.value.status = filters.value.status ?? '已审核'
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

function onSearch() {
  pageNo.value = 1
  rememberSave()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = { status: '已审核' }
  pageNo.value = 1
  sortField.value = ''
  sortOrder.value = ''
  r.resetDate()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onPage(n) { pageNo.value = n; r.query().catch(() => {}) }
function onSize(n) { pageSize.value = n; pageNo.value = 1; r.query().catch(() => {}) }
function onSort({ field, order }) {
  sortField.value = field
  sortOrder.value = order
  r.query().catch(() => {})
}

function openOrder({ row }) {
  // 联查销售订单列表并预填筛选（键与 QueryBar 默认键一致：订单号）
  router.push({
    path: '/sales-order',
    query: { rptFilters: JSON.stringify({ 订单号: row.orderNo }) },
  })
}

function filterText() {
  const f = filters.value
  const parts = [`日期：${start.value}~${end.value}`]
  if (f.orderNo) parts.push(`订单号：${f.orderNo}`)
  parts.push(`审核状态：${f.status || '已审核'}`)
  if (f.outboundStatus) parts.push(`出库状态：${f.outboundStatus}`)
  if (f.signStatus) parts.push(`签收状态：${f.signStatus}`)
  if (f.customer) parts.push(`客户：${f.customer}`)
  if (f.salesman) parts.push(`业务员：${f.salesman}`)
  if (f.warehouse) parts.push(`仓库：${f.warehouse}`)
  if (f.goods) parts.push(`商品：${f.goods}`)
  return parts.join('；')
}

function exportCurrent() {
  try {
    const treeRows = buildTreeRows(rows.value, [], visibleColumns.value.filter(c => c.num).map(c => c.key))
    exportRowsXlsx({
      reportName: '销售订单明细查询',
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
