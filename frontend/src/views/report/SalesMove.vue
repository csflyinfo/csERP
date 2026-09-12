<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        商品销售明细表（签收/退货）
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            销售按<b>司机签收日期</b>口径（K8，含税）：销售签收为正、销售退货为负，净金额=签收−退货。
            成本按签收配比：销售行=对应出库成本，退货行按退货审核日当期移动平均成本（拒收不计销售）；
            毛利=含税净额−成本，毛利率=毛利÷含税净额。数量按基本单位归一，件数=基本数量÷大单位换算率，
            未配置大单位时件数=数量；小单位单价=含税金额÷基本数量。单位成本/成本/毛利列按角色橘色脱敏。
            单据号可联查发货单/销售退货入库单。默认期间为截止昨天的最近一个月。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button class="btn-primary" @click="submitExport">异步导出</button>
      </div>
    </div>

    <div v-if="drillBanner" class="rpt-drill-banner">
      <span class="banner-label">钻取查询</span>
      <span>由【{{ drillBanner.fromName }}】钻取进入：{{ drillBanner.text }}</span>
      <button class="btn-plain" @click="goBack">返回上一层</button>
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end">
      <div class="ff">
        <label>单据类型</label>
        <select v-model="filters.billType">
          <option value="">全部</option>
          <option value="销售签收">销售签收</option>
          <option value="销售退货">销售退货</option>
        </select>
      </div>
      <div class="ff"><label>单据号</label><input v-model="filters.billNo" placeholder="发货/退货入库单号" @keyup.enter="onSearch"></div>
      <div class="ff"><label>订单号</label><input v-model="filters.sourceBillNo" placeholder="销售订单号" @keyup.enter="onSearch"></div>
      <div class="ff"><label>客户</label><input v-model="filters.customer" placeholder="编号/名称" @keyup.enter="onSearch"></div>
      <div class="ff"><label>业务员</label><input v-model="filters.salesman" @keyup.enter="onSearch"></div>
      <div class="ff"><label>司机</label><input v-model="filters.driver" @keyup.enter="onSearch"></div>
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
      @link="openBill"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ReportFilterBar from '@/components/report/ReportFilterBar.vue'
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows } from '@/components/report/report-table.js'
import { loadWarehouses } from '@/api/report-center.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'salesMoveReport'
const CODE = 'sales_move_detail'
const STORE_KEY = 'rpt:sales_move_detail:filters'
const FROM_NAME = {
  sales_goods_summary: '商品销售汇总表',
  customer_goods_summary: '客户商品销售汇总表',
  salesman_goods_summary: '业务员商品销售汇总表',
}
const FROM_PATH = {
  sales_goods_summary: '/report/sales-goods-summary',
  customer_goods_summary: '/report/customer-goods-summary',
  salesman_goods_summary: '/report/salesman-goods-summary',
}
const DRILL_FILTER_KEYS = ['billType', 'billNo', 'sourceBillNo', 'customer', 'salesman',
  'driver', 'territory', 'routeLine', 'warehouse', 'goods', 'categoryName', 'brandName', 'storageProperty']

const route = useRoute()
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE)
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])
const drillBanner = ref(null)

// 键名与后端 SalesMoveDetailDefinition 28 列严格一致
const ALL_COLUMNS = [
  { key: 'billNo', title: '单据号', link: true, width: 150, sortable: true },
  { key: 'billDate', title: '单据日期', width: 100, sortable: true },
  { key: 'billType', title: '单据类型', width: 90 },
  { key: 'sourceBillNo', title: '销售订单号', width: 150 },
  { key: 'driver', title: '司机', width: 90 },
  { key: 'customerCode', title: '客户编号', width: 100 },
  { key: 'customerName', title: '客户名称', width: 150 },
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
  { key: 'amount', title: '销售金额', num: true, money: true, sensitive: true, width: 120, sortable: true },
  { key: 'unitCostBase', title: '单位成本', num: true, money: true, sensitive: true, width: 100 },
  { key: 'costAmount', title: '成本金额', num: true, money: true, sensitive: true, width: 120 },
  { key: 'grossProfit', title: '毛利额', num: true, money: true, sensitive: true, width: 120 },
  { key: 'grossProfitRate', title: '毛利率', num: true, rate: true, percent: true, sensitive: true, width: 100 },
  { key: 'warehouse', title: '仓库', width: 90 },
  { key: 'brandName', title: '品牌', width: 100 },
  { key: 'categoryName', title: '商品类别', width: 100 },
  { key: 'storageProperty', title: '存储属性', width: 90 },
]
const visibleColumns = computed(() =>
  ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

onMounted(async () => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  if (route.query.drill === '1') {
    applyDrillQuery()
  } else {
    rememberLoad()
  }
  await r.query()
})

function applyDrillQuery() {
  const q = route.query
  if (q.start) start.value = q.start
  if (q.end) end.value = q.end
  const texts = []
  for (const k of DRILL_FILTER_KEYS) {
    if (q[k]) {
      filters.value[k] = String(q[k])
      texts.push(`${filterLabel(k)}=${q[k]}`)
    }
  }
  if (q.start && q.end) texts.unshift(`日期 ${q.start}~${q.end}`)
  drillBanner.value = {
    from: q.from || '',
    fromName: FROM_NAME[q.from] || '销售汇总表',
    text: texts.join('；'),
  }
}

function filterLabel(k) {
  return {
    billType: '类型', billNo: '单据号', sourceBillNo: '订单号', customer: '客户',
    salesman: '业务员', driver: '司机', territory: '区域', routeLine: '路线',
    warehouse: '仓库', goods: '商品', categoryName: '分类', brandName: '品牌',
    storageProperty: '存储属性',
  }[k] || k
}

function goBack() {
  const path = FROM_PATH[drillBanner.value?.from]
  if (window.history.state?.back) router.back()
  else if (path) router.push(path)
}

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
  filters.value = {}
  pageNo.value = 1
  sortField.value = ''
  sortOrder.value = ''
  drillBanner.value = null
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

/** 单据号联查：签收→销售发货单（发货单号），退货→销售退货入库单（入库单号） */
function openBill({ row }) {
  const no = row.billNo
  if (!no) return
  if (row.billType === '销售签收') {
    router.push({
      path: '/sales-receipt',
      query: { rptFilters: JSON.stringify({ 发货单号: no }) },
    })
  } else if (row.billType === '销售退货') {
    router.push({
      path: '/sales-return-inbound',
      query: { rptFilters: JSON.stringify({ 入库单号: no }) },
    })
  }
}

function filterText() {
  const f = filters.value
  const parts = [`日期：${start.value}~${end.value}`]
  if (f.billType) parts.push(`类型：${f.billType}`)
  if (f.billNo) parts.push(`单据号：${f.billNo}`)
  if (f.sourceBillNo) parts.push(`订单号：${f.sourceBillNo}`)
  if (f.customer) parts.push(`客户：${f.customer}`)
  if (f.salesman) parts.push(`业务员：${f.salesman}`)
  if (f.driver) parts.push(`司机：${f.driver}`)
  if (f.warehouse) parts.push(`仓库：${f.warehouse}`)
  if (f.goods) parts.push(`商品：${f.goods}`)
  return parts.join('；')
}

function exportCurrent() {
  try {
    const treeRows = buildTreeRows(rows.value, [], visibleColumns.value.filter(c => c.num).map(c => c.key))
    exportRowsXlsx({
      reportName: '商品销售明细表',
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
