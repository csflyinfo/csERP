<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        采购订单明细查询
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            只取已审核采购订单（作废单可在状态中单独查）；行级已入库量按已审核入库单实时聚合，
            采购退货不冲减订单执行量。数量为基本单位（小单位），件数=基本数量÷大单位换算率，
            无大单位时件数=数量；单价/箱价/金额均为含税口径。
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
        <label>入库状态</label>
        <select v-model="filters.inboundStatus">
          <option value="">全部</option>
          <option value="未入库">未入库</option>
          <option value="部分入库">部分入库</option>
          <option value="已入库">已入库</option>
        </select>
      </div>
      <div class="ff"><label>供应商</label><input v-model="filters.supplier" placeholder="编号/名称" @keyup.enter="onSearch"></div>
      <div class="ff"><label>采购员</label><input v-model="filters.buyer" @keyup.enter="onSearch"></div>
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

const MODULE = 'purchaseOrderDetailReport'
const CODE = 'purchase_order_detail'
const STORE_KEY = 'rpt:purchase_order_detail:filters'
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE)
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])

const ALL_COLUMNS = [
  { key: 'orderNo', title: '订单号', link: true, width: 150, sortable: true },
  { key: 'billDate', title: '订单日期', width: 100, sortable: true },
  { key: 'statusText', title: '审核状态', width: 90 },
  { key: 'inboundStatusText', title: '入库状态', width: 90 },
  { key: 'supplierCode', title: '供应商编号', width: 100 },
  { key: 'supplierName', title: '供应商名称', width: 150, sortable: true },
  { key: 'buyer', title: '采购员', width: 90 },
  { key: 'goodsCode', title: '商品编号', width: 100, sortable: true },
  { key: 'goodsName', title: '商品名称', width: 170 },
  { key: 'barcode', title: '条码', width: 90 },
  { key: 'baseUnit', title: '基本单位', width: 80 },
  { key: 'baseQty', title: '数量(小单位)', num: true, width: 120, sortable: true },
  { key: 'unitPriceBase', title: '单价(小单位)', num: true, money: true, sensitive: true, width: 110 },
  { key: 'packageQty', title: '件数', num: true, width: 90 },
  { key: 'largeUnit', title: '大单位', width: 80 },
  { key: 'boxPrice', title: '箱价', num: true, money: true, sensitive: true, width: 100 },
  { key: 'amount', title: '订单金额', num: true, money: true, sensitive: true, width: 120, sortable: true },
  { key: 'receivedBase', title: '已入库数量(小单位)', num: true, width: 140, sortable: true },
  { key: 'receivedPackage', title: '已入库件数', num: true, width: 100 },
  { key: 'unreceivedBase', title: '未入库数量(小单位)', num: true, width: 140 },
  { key: 'unreceivedPackage', title: '未入库件数', num: true, width: 100 },
  { key: 'brandName', title: '品牌', width: 100 },
  { key: 'categoryName', title: '商品类别', width: 100 },
  { key: 'storageProperty', title: '存储属性', width: 90 },
]
const visibleColumns = computed(() =>
  ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

onMounted(async () => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  rememberLoad()
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
  // 联查采购订单列表并预填筛选（键与 QueryBar 默认键一致：采购单号）
  const prefill = encodeURIComponent(JSON.stringify({ 采购单号: row.orderNo }))
  router.push(`/purchase-order?rptFilters=${prefill}`)
}

function filterText() {
  const f = filters.value
  const parts = [`日期：${start.value}~${end.value}`]
  if (f.orderNo) parts.push(`订单号：${f.orderNo}`)
  parts.push(`审核状态：${f.status || '已审核'}`)
  if (f.inboundStatus) parts.push(`入库状态：${f.inboundStatus}`)
  if (f.supplier) parts.push(`供应商：${f.supplier}`)
  if (f.buyer) parts.push(`采购员：${f.buyer}`)
  if (f.warehouse) parts.push(`仓库：${f.warehouse}`)
  if (f.goods) parts.push(`商品：${f.goods}`)
  return parts.join('；')
}

function exportCurrent() {
  try {
    const treeRows = buildTreeRows(rows.value, [], visibleColumns.value.filter(c => c.num).map(c => c.key))
    exportRowsXlsx({
      reportName: '采购订单明细查询',
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
