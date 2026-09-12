<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        采购明细查询（入库/退货）
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            取已审核采购入库、采购退货单明细，实时取数；入库为正、退货为负，净数量/净额为轧抵后结果。
            金额为含税口径；数量按基本单位归一，件数=基本数量÷大单位换算率，未配置大单位时件数=数量；
            小单位单价=含税金额÷基本数量，退货行单价为正、金额为负。无订单来源的入库单源单号显示「无订单采购」。
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
          <option value="采购入库">采购入库</option>
          <option value="采购退货">采购退货</option>
        </select>
      </div>
      <div class="ff"><label>单据号</label><input v-model="filters.billNo" placeholder="入库/退货单号" @keyup.enter="onSearch"></div>
      <div class="ff"><label>源单号</label><input v-model="filters.sourceBillNo" placeholder="采购订单号" @keyup.enter="onSearch"></div>
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

const MODULE = 'purchaseMoveReport'
const CODE = 'purchase_move_detail'
const STORE_KEY = 'rpt:purchase_move_detail:filters'
const FROM_NAME = {
  purchase_goods_summary: '商品采购汇总表',
  purchase_supplier_summary: '供应商商品采购汇总表',
}
const FROM_PATH = {
  purchase_goods_summary: '/report/purchase-goods-summary',
  purchase_supplier_summary: '/report/purchase-supplier-summary',
}

const route = useRoute()
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE)
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])
const drillBanner = ref(null)

const ALL_COLUMNS = [
  { key: 'billNo', title: '单据号', link: true, width: 150, sortable: true },
  { key: 'billDate', title: '单据日期', width: 100, sortable: true },
  { key: 'billType', title: '单据类型', width: 90 },
  { key: 'sourceBillNo', title: '源单号', width: 150 },
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
  { key: 'amount', title: '采购金额', num: true, money: true, sensitive: true, width: 120, sortable: true },
  { key: 'warehouse', title: '仓库', width: 90 },
  { key: 'brandName', title: '品牌', width: 100 },
  { key: 'categoryName', title: '商品类别', width: 100 },
  { key: 'storageProperty', title: '存储属性', width: 90 },
]
const visibleColumns = computed(() =>
  ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

const DRILL_FILTER_KEYS = ['billType', 'supplier', 'buyer', 'warehouse', 'goods', 'categoryName', 'brandName', 'storageProperty']

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
    fromName: FROM_NAME[q.from] || '采购汇总表',
    text: texts.join('；'),
  }
}

function filterLabel(k) {
  return {
    billType: '类型', supplier: '供应商', buyer: '采购员', warehouse: '仓库',
    goods: '商品', categoryName: '分类', brandName: '品牌', storageProperty: '存储属性',
  }[k] || k
}

function goBack() {
  const path = FROM_PATH[drillBanner.value?.from]
  if (window.history.state?.back) {
    router.back()
  } else if (path) {
    router.push(path)
  }
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

/** 单据号联查原始业务单据（预填筛选条件）。
 *  注意：业务列表后端走 PageResult 统一「全值包含」过滤，每个键值都会变成必须命中的子串；
 *  入库/退货页的日期字段又是普通文本框（不识别 _from/_to 区间键），带日期反而会把结果筛空，
 *  单据号本身已唯一定位，故只传单值键。 */
function openBill({ row }) {
  const no = row.billNo
  if (row.billType === '采购入库') {
    router.push({
      path: '/purchase-inbound',
      query: { rptFilters: JSON.stringify({ '入库单号': no }) },
    })
  } else if (row.billType === '采购退货') {
    router.push({
      path: '/purchase-return',
      query: { rptFilters: JSON.stringify({ '退货单号': no }) },
    })
  }
}

function filterText() {
  const f = filters.value
  const parts = [`日期：${start.value}~${end.value}`]
  if (f.billType) parts.push(`类型：${f.billType}`)
  if (f.billNo) parts.push(`单据号：${f.billNo}`)
  if (f.sourceBillNo) parts.push(`源单号：${f.sourceBillNo}`)
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
      reportName: '采购明细查询',
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
