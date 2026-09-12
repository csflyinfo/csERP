<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        商品销售汇总表
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            销售按<b>司机签收日期/数量/金额</b>口径（K8，含税）：销售=当期签收，退货=当期销售退货审核入库
            （按退货审核日、当期移动平均成本），拒收不计销售仅在订单明细中体现；净销售=签收−退货。
            数据来自销售域日汇总（DWS，日结后刷新），按所选维度（最多 3 级）聚合；
            各组小计与合计为前端对叶子行实时汇总，毛利率不可加、按「组毛利÷组净额」现算，与合计同口径。
            成本/毛利列按角色橘色脱敏。签收/退货列蓝色数字可钻取到「商品销售明细表」逐笔核对
            （最多返回 10 万行叶子）。默认期间为截止昨天的最近一个月。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button class="btn-primary" @click="submitExport">异步导出</button>
      </div>
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
        <option value="">{{ i === 0 ? '（默认：商品+业务员）' : `第${i + 1}级：不分组` }}</option>
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
import { useRouter } from 'vue-router'
import ReportFilterBar from '@/components/report/ReportFilterBar.vue'
import ReportSalesFilters from '@/components/report/ReportSalesFilters.vue'
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows } from '@/components/report/report-table.js'
import { loadWarehouses } from '@/api/report-center.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'salesGoodsSummaryReport'
const CODE = 'sales_goods_summary'
const STORE_KEY = 'rpt:sales_goods_summary:state'
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)

// 可选分组维度（白名单与后端 groupWhitelist 严格一致）
const GROUP_META = [
  { key: 'category', title: '商品类别', outputKey: 'categoryName' },
  { key: 'brand', title: '品牌', outputKey: 'brandName' },
  { key: 'salesman', title: '业务员', outputKey: 'salesman' },
  { key: 'storage', title: '存储属性', outputKey: 'storageProperty' },
  { key: 'goods', title: '商品', outputKey: 'goodsName' },
]

const r = useCenterReport(CODE)
const { start, end, filters, groupBy, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])

// 汇总表一次性取全部叶子行（护栏上限 10 万）
pageSize.value = 100000

onMounted(async () => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  rememberLoad()
  if (groupBy.value.length === 0) groupBy.value = ['goods', 'salesman']
  await r.query()
})

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

// 十二列标准度量（键名与后端 measureColumns 严格一致；毛利率 rate 不参与累加，按组现算）
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
]

const visibleColumns = computed(() => {
  const groups = groupBy.value
  const cols = []
  for (const g of groups) {
    if (g === 'category') cols.push({ key: 'categoryName', title: '商品类别', width: 110 })
    else if (g === 'brand') cols.push({ key: 'brandName', title: '品牌', width: 110 })
    else if (g === 'salesman') cols.push({ key: 'salesman', title: '业务员', width: 90 })
    else if (g === 'storage') cols.push({ key: 'storageProperty', title: '存储属性', width: 90 })
    else if (g === 'goods') {
      cols.push({ key: 'goodsCode', title: '商品编号', width: 100, sortable: true })
      cols.push({ key: 'goodsName', title: '商品名称', width: 170 })
      cols.push({ key: 'barcode', title: '条码', width: 90 })
      cols.push({ key: 'baseUnit', title: '基本单位', width: 80 })
    }
  }
  // 商品叶子行附带属性列（属性自身已作为分组层级时不重复）
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
  groupBy.value = ['goods', 'salesman']
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

/** 钻取到 #16 商品销售明细表：签收列→销售签收，退货列→销售退货；维度值回带为筛选 */
function onDrill({ group, dims, row, col }) {
  const q = { drill: '1', from: CODE, start: start.value, end: end.value }
  if (col.key.startsWith('signed')) q.billType = '销售签收'
  else if (col.key.startsWith('return')) q.billType = '销售退货'
  const src = group ? Object.fromEntries(dims.map(d => [d.key, d.value])) : (row || {})
  if (src.categoryName) q.categoryName = src.categoryName
  if (src.brandName) q.brandName = src.brandName
  if (src.storageProperty) q.storageProperty = src.storageProperty
  if (src.salesman) q.salesman = src.salesman
  if (src.goodsName) q.goods = src.goodsName
  // 筛选条上的客户/仓库等条件同样下传，保证钻取口径不放宽
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
  if (f.categoryName) parts.push(`分类：${f.categoryName}`)
  if (f.brandName) parts.push(`品牌：${f.brandName}`)
  return parts.join('；')
}

function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    const treeRows = buildTreeRows(rows.value, treeGroupKeys.value, nums)
    exportRowsXlsx({
      reportName: '商品销售汇总表',
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
