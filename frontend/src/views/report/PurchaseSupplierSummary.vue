<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        供应商商品采购汇总表
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            数据来自采购域日汇总（DWS，每日日结后刷新），按供应商相关维度（最多 3 级）聚合，
            分组必须包含「供应商」或「供应商分类」。各组小计与合计为前端对叶子行实时汇总；
            入库/退货为发生额（退货列正数展示），采购数量/金额=入库−退货轧抵净额。
            蓝色数字可钻取到「采购明细查询」逐笔核对（最多返回 10 万行叶子）。
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
      <div class="ff"><label>供应商分类</label><input v-model="filters.supplierType" @keyup.enter="onSearch"></div>
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

    <div class="rpt-group-bar">
      <span>分组层级：</span>
      <select v-for="(_, i) in 3" :key="i" :value="groupBy[i] || ''" @change="onGroupChange(i, $event.target.value)">
        <option value="">{{ i === 0 ? '（默认：供应商+商品+采购员）' : `第${i + 1}级：不分组` }}</option>
        <option v-for="o in groupOptions(i)" :key="o.key" :value="o.key">{{ o.title }}</option>
      </select>
      <span class="rpt-group-tip">最多 3 级，且至少包含「供应商」或「供应商分类」</span>
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
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows } from '@/components/report/report-table.js'
import { loadWarehouses } from '@/api/report-center.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'purchaseSupplierSummaryReport'
const CODE = 'purchase_supplier_summary'
const STORE_KEY = 'rpt:purchase_supplier_summary:state'
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)

// 可选分组维度（白名单与后端 groupWhitelist 严格一致）
const GROUP_META = [
  { key: 'supplier', title: '供应商', outputKey: 'supplierName' },
  { key: 'goods', title: '商品', outputKey: 'goodsName' },
  { key: 'supplierType', title: '供应商分类', outputKey: 'supplierType' },
  { key: 'buyer', title: '采购员', outputKey: 'buyer' },
  { key: 'brand', title: '品牌', outputKey: 'brandName' },
  { key: 'category', title: '商品类别', outputKey: 'categoryName' },
  { key: 'storage', title: '存储属性', outputKey: 'storageProperty' },
]

const r = useCenterReport(CODE)
const { start, end, filters, groupBy, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])

// 汇总表一次性取全部叶子行（护栏上限 10 万）
pageSize.value = 100000

onMounted(async () => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  rememberLoad()
  if (groupBy.value.length === 0) groupBy.value = ['supplier', 'goods', 'buyer']
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
  const next = arr.filter(Boolean)
  if (next.length && !next.includes('supplier') && !next.includes('supplierType')) {
    alert('供应商商品采购汇总表的分组必须包含「供应商」或「供应商分类」')
    return
  }
  groupBy.value = next
  pageNo.value = 1
  rememberSave()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}

/** 服务端叶子行字段 → 前端建树分组键 */
const treeGroupKeys = computed(() =>
  groupBy.value.map(k => GROUP_META.find(o => o.key === k)?.outputKey).filter(Boolean))

const MEASURE_COLUMNS = [
  { key: 'inboundQtyBase', title: '入库数量(小单位)', num: true, width: 140, drill: true, sortable: true },
  { key: 'inboundPackageQty', title: '入库件数', num: true, width: 100, drill: true, sortable: true },
  { key: 'inboundAmount', title: '入库金额', num: true, money: true, sensitive: true, width: 120, drill: true, sortable: true },
  { key: 'returnQtyBase', title: '退货数量(小单位)', num: true, width: 140, drill: true, sortable: true },
  { key: 'returnPackageQty', title: '退货件数', num: true, width: 100, drill: true, sortable: true },
  { key: 'returnAmount', title: '退货金额', num: true, money: true, sensitive: true, width: 120, drill: true, sortable: true },
  { key: 'netQtyBase', title: '采购数量(小单位)', num: true, width: 140, drill: true, sortable: true },
  { key: 'netPackageQty', title: '采购件数', num: true, width: 100, drill: true, sortable: true },
  { key: 'netAmount', title: '采购金额', num: true, money: true, sensitive: true, width: 120, drill: true, sortable: true },
]

const visibleColumns = computed(() => {
  const groups = groupBy.value
  const cols = []
  for (const g of groups) {
    if (g === 'supplier') {
      cols.push({ key: 'supplierCode', title: '供应商编号', width: 100, sortable: true })
      cols.push({ key: 'supplierName', title: '供应商名称', width: 160 })
    } else if (g === 'supplierType') {
      cols.push({ key: 'supplierType', title: '供应商分类', width: 100 })
    } else if (g === 'buyer') {
      cols.push({ key: 'buyer', title: '采购员', width: 90 })
    } else if (g === 'brand') {
      cols.push({ key: 'brandName', title: '品牌', width: 110 })
    } else if (g === 'category') {
      cols.push({ key: 'categoryName', title: '商品类别', width: 110 })
    } else if (g === 'storage') {
      cols.push({ key: 'storageProperty', title: '存储属性', width: 90 })
    } else if (g === 'goods') {
      cols.push({ key: 'goodsCode', title: '商品编号', width: 100, sortable: true })
      cols.push({ key: 'goodsName', title: '商品名称', width: 170 })
      cols.push({ key: 'barcode', title: '条码', width: 90 })
      cols.push({ key: 'baseUnit', title: '基本单位', width: 80 })
    }
  }
  // 商品叶子行附带属性列（属性自身已作为分组层级时不重复）
  if (groups.includes('goods')) {
    if (!groups.includes('brand')) cols.push({ key: 'brandName', title: '品牌', width: 100 })
    if (!groups.includes('category')) cols.push({ key: 'categoryName', title: '商品类别', width: 100 })
    if (!groups.includes('storage')) cols.push({ key: 'storageProperty', title: '存储属性', width: 90 })
  }
  cols.push(...MEASURE_COLUMNS)
  return cols.filter(c => canViewColumn(MODULE, c.title))
})

function validGroupsOrAlert() {
  if (!groupBy.value.includes('supplier') && !groupBy.value.includes('supplierType')) {
    alert('供应商商品采购汇总表的分组必须包含「供应商」或「供应商分类」')
    return false
  }
  return true
}

function onSearch() {
  if (!validGroupsOrAlert()) return
  pageNo.value = 1
  rememberSave()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = {}
  groupBy.value = ['supplier', 'goods', 'buyer']
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

/** 钻取到报表2：度量前缀定类型，供应商/商品等维度回带为筛选 */
function onDrill({ group, dims, row, col }) {
  const q = { drill: '1', from: CODE, start: start.value, end: end.value }
  if (col.key.startsWith('inbound')) q.billType = '采购入库'
  else if (col.key.startsWith('return')) q.billType = '采购退货'
  const src = group ? Object.fromEntries(dims.map(d => [d.key, d.value])) : (row || {})
  if (src.supplierName) q.supplier = src.supplierName
  if (src.categoryName) q.categoryName = src.categoryName
  if (src.brandName) q.brandName = src.brandName
  if (src.storageProperty) q.storageProperty = src.storageProperty
  if (src.buyer) q.buyer = src.buyer
  if (src.goodsName) q.goods = src.goodsName
  if (filters.value.warehouse) q.warehouse = filters.value.warehouse
  router.push({ path: '/report/purchase-move', query: q })
}

function filterText() {
  const f = filters.value
  const parts = [`日期：${start.value}~${end.value}`]
  const gtxt = groupBy.value.map(k => GROUP_META.find(o => o.key === k)?.title).filter(Boolean).join('/')
  if (gtxt) parts.push(`分组：${gtxt}`)
  if (f.supplier) parts.push(`供应商：${f.supplier}`)
  if (f.supplierType) parts.push(`供应商分类：${f.supplierType}`)
  if (f.buyer) parts.push(`采购员：${f.buyer}`)
  if (f.warehouse) parts.push(`仓库：${f.warehouse}`)
  if (f.goods) parts.push(`商品：${f.goods}`)
  return parts.join('；')
}

function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    const treeRows = buildTreeRows(rows.value, treeGroupKeys.value, nums)
    exportRowsXlsx({
      reportName: '供应商商品采购汇总表',
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
