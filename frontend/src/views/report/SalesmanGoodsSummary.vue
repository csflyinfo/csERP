<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        业务员商品销售汇总表
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            默认粒度「业务员 + 商品」，字段与商品销售汇总一致，另增<b>客户数</b>（期间该业务员+商品维度
            有签收的去重客户数；非可加指标，合计行不汇总）。销售按司机签收日期口径（含税），
            退货按退货审核日期。数据来自销售域日汇总（DWS），最多 3 级分组；
            毛利率按「组毛利÷组净额」现算，成本/毛利列按角色橘色脱敏。
            签收/退货列可钻取到「商品销售明细表」。
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
      <ReportSalesFilters :filters="filters" :warehouses="warehouses" @search="onSearch" />
      <template #actions>
        <button class="btn-primary" @click="onSearch">查询</button>
        <button class="btn-plain" @click="onReset">重置</button>
      </template>
    </ReportFilterBar>

    <div class="rpt-group-bar">
      <span>分组层级：</span>
      <select v-for="(_, i) in 3" :key="i" :value="groupBy[i] || ''" @change="onGroupChange(i, $event.target.value)">
        <option value="">{{ i === 0 ? '（默认：业务员+商品）' : `第${i + 1}级：不分组` }}</option>
        <option v-for="o in groupOptions(i)" :key="o.key" :value="o.key">{{ o.title }}</option>
      </select>
      <span class="rpt-group-tip">最多 3 级；小计/合计与钻取口径一致</span>
    </div>

    <DrillGridReport
      :columns="visibleColumns"
      :rows="rows"
      :group-keys="treeGroupKeys"
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
import { useRoute, useRouter } from 'vue-router'
import ReportFilterBar from '@/components/report/ReportFilterBar.vue'
import ReportSalesFilters from '@/components/report/ReportSalesFilters.vue'
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows } from '@/components/report/report-table.js'
import { loadWarehouses } from '@/api/report-center.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'salesmanGoodsSummaryReport'
const CODE = 'salesman_goods_summary'
const STORE_KEY = 'rpt:salesman_goods_summary:state'
const FROM_NAME = { salesman_summary: '业务员销售汇总表' }
const FROM_PATH = { salesman_summary: '/report/salesman-summary' }
const DRILL_FILTER_KEYS = ['salesman', 'customer', 'customerLevel', 'territory', 'routeLine',
  'warehouse', 'goods', 'categoryName', 'brandName', 'storageProperty']

const route = useRoute()
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)

// 可选分组维度（白名单与后端 groupWhitelist 严格一致）
const GROUP_META = [
  { key: 'salesman', title: '业务员', outputKey: 'salesman' },
  { key: 'category', title: '商品类别', outputKey: 'categoryName' },
  { key: 'brand', title: '品牌', outputKey: 'brandName' },
  { key: 'storage', title: '存储属性', outputKey: 'storageProperty' },
  { key: 'goods', title: '商品', outputKey: 'goodsName' },
]

const r = useCenterReport(CODE)
const { start, end, filters, groupBy, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])
const drillBanner = ref(null)

// 汇总表一次性取全部叶子行（护栏上限 10 万）
pageSize.value = 100000

onMounted(async () => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  if (route.query.drill === '1') {
    applyDrillQuery()
  } else {
    rememberLoad()
  }
  if (groupBy.value.length === 0) groupBy.value = ['salesman', 'goods']
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
  if (q.billType) texts.unshift(`类型=${q.billType}`)
  if (q.start && q.end) texts.unshift(`日期 ${q.start}~${q.end}`)
  drillBanner.value = {
    from: q.from || '',
    fromName: FROM_NAME[q.from] || '业务员销售汇总表',
    text: texts.join('；'),
  }
}

function filterLabel(k) {
  return {
    salesman: '业务员', customer: '客户', customerLevel: '客户等级', territory: '区域',
    routeLine: '路线', warehouse: '仓库', goods: '商品', categoryName: '分类',
    brandName: '品牌', storageProperty: '存储属性',
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
    if (saved.filters) Object.assign(filters.value, saved.filters)
    if (Array.isArray(saved.groupBy) && saved.groupBy.length) groupBy.value = saved.groupBy
  } catch { /* 忽略损坏缓存 */ }
}
function rememberSave() {
  localStorage.setItem(STORE_KEY, JSON.stringify({ filters: filters.value, groupBy: groupBy.value }))
}

function groupOptions(level) {
  const chosen = groupBy.value.filter((_, i) => i !== level)
  return GROUP_META.filter(o => !chosen.includes(o.key))
}
function onGroupChange(i, val) {
  const arr = [...groupBy.value]
  arr[i] = val
  groupBy.value = arr.filter(Boolean)
  pageNo.value = 1
  rememberSave()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}

/** 服务端叶子行字段 → 前端建树分组键 */
const treeGroupKeys = computed(() =>
  groupBy.value.map(k => GROUP_META.find(o => o.key === k)?.outputKey).filter(Boolean))

// 十二列标准度量 + 客户数（非可加；合计行后端给 NULL，前端小计按去重无法计算故不汇总）
const MEASURE_COLUMNS = [
  { key: 'signedQtyBase', title: '销售数量(小单位)', num: true, width: 140, drill: true, sortable: true },
  { key: 'signedPackageQty', title: '销售件数', num: true, width: 100, drill: true, sortable: true },
  { key: 'signedAmount', title: '销售金额', num: true, money: true, sensitive: true, width: 120, drill: true, sortable: true },
  { key: 'returnQtyBase', title: '退货数量(小单位)', num: true, width: 140, drill: true, sortable: true },
  { key: 'returnPackageQty', title: '退货件数', num: true, width: 100, drill: true, sortable: true },
  { key: 'returnAmount', title: '退货金额', num: true, money: true, sensitive: true, width: 120, drill: true, sortable: true },
  { key: 'netQtyBase', title: '净销售数量(小单位)', num: true, width: 150, sortable: true },
  { key: 'netPackageQty', title: '净销售件数', num: true, width: 110, sortable: true },
  { key: 'netAmount', title: '净销售额', num: true, money: true, sensitive: true, width: 120, sortable: true },
  { key: 'costAmount', title: '成本金额', num: true, money: true, sensitive: true, width: 120, sortable: true },
  { key: 'grossProfit', title: '毛利额', num: true, money: true, sensitive: true, width: 120, sortable: true },
  { key: 'grossProfitRate', title: '毛利率', num: true, rate: true, percent: true, sensitive: true, width: 100 },
  { key: 'customerCount', title: '客户数', num: true, noSum: true, width: 80 },
]

const visibleColumns = computed(() => {
  const groups = groupBy.value
  const cols = []
  for (const g of groups) {
    if (g === 'salesman') cols.push({ key: 'salesman', title: '业务员', width: 90 })
    else if (g === 'category') cols.push({ key: 'categoryName', title: '商品类别', width: 110 })
    else if (g === 'brand') cols.push({ key: 'brandName', title: '品牌', width: 110 })
    else if (g === 'storage') cols.push({ key: 'storageProperty', title: '存储属性', width: 90 })
    else if (g === 'goods') {
      cols.push({ key: 'goodsCode', title: '商品编号', width: 100, sortable: true })
      cols.push({ key: 'goodsName', title: '商品名称', width: 170 })
      cols.push({ key: 'barcode', title: '条码', width: 90 })
      cols.push({ key: 'baseUnit', title: '基本单位', width: 80 })
    }
  }
  if (groups.includes('goods')) {
    if (!groups.includes('salesman')) cols.push({ key: 'salesman', title: '业务员', width: 90 })
    if (!groups.includes('brand')) cols.push({ key: 'brandName', title: '品牌', width: 100 })
    if (!groups.includes('category')) cols.push({ key: 'categoryName', title: '商品类别', width: 100 })
    if (!groups.includes('storage')) cols.push({ key: 'storageProperty', title: '存储属性', width: 90 })
  }
  cols.push(...MEASURE_COLUMNS)
  return cols.filter(c => canViewColumn(MODULE, c.title))
})

function onSearch() {
  pageNo.value = 1
  rememberSave()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = {}
  groupBy.value = ['salesman', 'goods']
  pageNo.value = 1
  sortField.value = ''
  sortOrder.value = ''
  drillBanner.value = null
  rememberSave()
  r.resetDate()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onSort({ field, order }) {
  sortField.value = field
  sortOrder.value = order
  r.query().catch(() => {})
}

/** 钻取到 #16 商品销售明细表 */
function onDrill({ group, dims, row, col }) {
  const q = { drill: '1', from: CODE, start: start.value, end: end.value }
  if (col.key.startsWith('signed')) q.billType = '销售签收'
  else if (col.key.startsWith('return')) q.billType = '销售退货'
  const src = group ? Object.fromEntries(dims.map(d => [d.key, d.value])) : (row || {})
  if (src.salesman) q.salesman = src.salesman
  if (src.categoryName) q.categoryName = src.categoryName
  if (src.brandName) q.brandName = src.brandName
  if (src.storageProperty) q.storageProperty = src.storageProperty
  if (src.goodsName) q.goods = src.goodsName
  const f = filters.value
  if (f.customer) q.customer = f.customer
  if (f.customerLevel) q.customerLevel = f.customerLevel
  if (f.territory) q.territory = f.territory
  if (f.routeLine) q.routeLine = f.routeLine
  if (f.warehouse) q.warehouse = f.warehouse
  router.push({ path: '/report/sales-move', query: q })
}

function filterText() {
  const f = filters.value
  const parts = [`日期：${start.value}~${end.value}`]
  const gtxt = groupBy.value.map(k => GROUP_META.find(o => o.key === k)?.title).filter(Boolean).join('/')
  if (gtxt) parts.push(`分组：${gtxt}`)
  if (f.customer) parts.push(`客户：${f.customer}`)
  if (f.salesman) parts.push(`业务员：${f.salesman}`)
  if (f.warehouse) parts.push(`仓库：${f.warehouse}`)
  if (f.goods) parts.push(`商品：${f.goods}`)
  return parts.join('；')
}

function exportCurrent() {
  try {
    // 毛利率/客户数非可加，不进小计累加
    const nums = visibleColumns.value.filter(c => c.num && !c.rate && !c.noSum).map(c => c.key)
    const treeRows = buildTreeRows(rows.value, treeGroupKeys.value, nums)
    exportRowsXlsx({
      reportName: '业务员商品销售汇总表',
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
