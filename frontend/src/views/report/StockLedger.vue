<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        商品库存台账
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            实物账口径：日期取库存实际记账时刻（与销售签收口径各自独立，已出库未签收在途构成两套账差异）。
            每个「商品+仓库+批次」分区置顶一行灰色<b>期初结存</b>（= 当前库存余额快照 − 本期净发生，不扫全历史流水），
            其后逐笔列示收入/发出数量、单价、金额；结存数量取流水余额，结存金额按期初金额逐笔滚算。
            冲销/作废回库行以红字负数列示。为保障大表性能，<b>必须先选商品或仓库</b>，一次最多查询 92 天；
            更长周期请用「商品进销存汇总表」。单价/金额为成本口径，按列权限脱敏。默认期间自然月本月。
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

    <div v-if="!canSearch" class="rpt-guard-tip">
      请先选择「商品」或「仓库」至少一项，再查询台账（大表防护；单次最多 92 天）
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end" initial-preset="thisMonth">
      <div class="ff"><label>商品</label><input v-model="filters.goods" placeholder="编号/名称/条码" @keyup.enter="onSearch"></div>
      <div class="ff">
        <label>仓库</label>
        <select v-model="filters.warehouse">
          <option value="">全部仓库</option>
          <option v-for="w in warehouses" :key="w" :value="w">{{ w }}</option>
        </select>
      </div>
      <div class="ff"><label>批次</label><input v-model="filters.batchNo" @keyup.enter="onSearch"></div>
      <div class="ff"><label>单据号</label><input v-model="filters.billNo" @keyup.enter="onSearch"></div>
      <details class="ff ff-multi">
        <summary>单据类型{{ typeSummary }}</summary>
        <div class="multi-pop">
          <label v-for="t in TYPE_OPTIONS" :key="t.code">
            <input type="checkbox" :checked="filters.billTypes.includes(t.code)" @change="toggleType(t.code)">
            {{ t.label }}
          </label>
        </div>
      </details>
      <div class="ff">
        <label>方向</label>
        <select v-model="filters.direction">
          <option value="">全部</option>
          <option value="IN">收入</option>
          <option value="OUT">发出</option>
          <option value="成本调整">成本调整</option>
        </select>
      </div>
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
        <button class="btn-primary" :disabled="!canSearch" @click="onSearch">查询</button>
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
      :row-class-fn="rowClass"
      @page-change="onPage"
      @size-change="onSize"
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

const MODULE = 'stockLedgerReport'
const CODE = 'stock_ledger'
const STORE_KEY = 'rpt:stock_ledger:filters'
const FROM_NAME = { inventory_roll: '商品进销存汇总表' }
const FROM_PATH = { inventory_roll: '/report/inventory-roll' }
const MAX_SPAN_DAYS = 92

/** 单据类型码 → 文案（与后端 ALLOWED_TYPES / outerSelect CASE 严格一致） */
const TYPE_OPTIONS = [
  { code: 'CGRK', label: '采购入库' },
  { code: 'CGSH', label: '成本调整' },
  { code: 'CTCK', label: '采购退货出库' },
  { code: 'XSCK', label: '销售出库' },
  { code: 'THRK', label: '销售退货入库' },
  { code: 'JSRK', label: '客户拒收入库' },
  { code: 'QTRK', label: '其他入库' },
  { code: 'QTCK', label: '其他出库' },
  { code: 'DBCK', label: '调拨出库' },
  { code: 'DBRK', label: '调拨入库' },
  { code: 'BSD', label: '报损出库' },
  { code: 'PDD', label: '盘点单' },
  { code: 'WMS_INBOUND', label: 'WMS入库' },
  { code: 'WMS_ADJUST_GAIN', label: 'WMS盘盈调整' },
  { code: 'OTHER', label: '其他' },
]
const TYPE_LABEL = Object.fromEntries(TYPE_OPTIONS.map(t => [t.code, t.label]))

/** 单据类型文案 → 业务单据列表路径与 QueryBar 筛选名（rptFilters 键为中文筛选名） */
const BILL_LINK = {
  采购入库: { path: '/purchase-inbound', filter: '入库单号' },
  采购退货出库: { path: '/purchase-return', filter: '退货单号' },
  销售出库: { path: '/sales-outbound', filter: '出库单号' },
  销售退货入库: { path: '/sales-return-inbound', filter: '入库单号' },
  客户拒收入库: { path: '/reject-inbound', filter: '入库单号' },
  其他入库: { path: '/other-inbound', filter: '单号' },
  其他出库: { path: '/other-outbound', filter: '单号' },
  调拨出库: { path: '/transfer-outbound', filter: '调拨出库单号' },
  调拨入库: { path: '/transfer-inbound', filter: '调拨入库单号' },
  报损出库: { path: '/damage', filter: '报损单号' },
  盘点单: { path: '/stock-take', filter: '单号' },
  成本调整: { path: '/cost-adjust', filter: '单号' },
}

const route = useRoute()
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE, { naturalMonth: true })
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])
const drillBanner = ref(null)

// 多选单据类型用数组（空数组=不过滤）
filters.value.billTypes = []

const ALL_COLUMNS = [
  { key: 'moveDate', title: '日期', width: 90 },
  { key: 'billNo', title: '单据号', link: true, width: 150 },
  { key: 'baseBillNo', title: '原始单号', width: 150 },
  { key: 'billTypeText', title: '单据类型', width: 100 },
  { key: 'directionText', title: '方向', width: 70 },
  { key: 'reversalFlag', title: '冲销', bool: true, width: 60 },
  { key: 'goodsCode', title: '商品编号', width: 100 },
  { key: 'goodsName', title: '商品名称', width: 170 },
  { key: 'warehouse', title: '仓库', width: 90 },
  { key: 'batchNo', title: '批次', width: 90 },
  { key: 'inQty', title: '收入数量', num: true, width: 90 },
  { key: 'outQty', title: '发出数量', num: true, width: 90 },
  { key: 'baseUnit', title: '单位', width: 60 },
  { key: 'costPrice', title: '单价', num: true, money: true, sensitive: true, width: 90 },
  { key: 'signedAmount', title: '金额', num: true, money: true, sensitive: true, width: 110 },
  { key: 'balanceQty', title: '结存数量', num: true, width: 100 },
  { key: 'balanceAmount', title: '结存金额', num: true, money: true, sensitive: true, width: 110 },
  { key: 'operatorName', title: '经办人', width: 90 },
  { key: 'barcode', title: '条码', width: 90 },
  { key: 'brandName', title: '品牌', width: 90 },
  { key: 'categoryName', title: '商品类别', width: 100 },
  { key: 'storageProperty', title: '存储属性', width: 80 },
]
const visibleColumns = computed(() =>
  ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

const canSearch = computed(() => !!(filters.value.goods || filters.value.warehouse))
const typeSummary = computed(() => {
  const n = filters.value.billTypes.length
  return n ? `（已选 ${n}）` : '（全部）'
})

function toggleType(code) {
  const arr = filters.value.billTypes
  const i = arr.indexOf(code)
  if (i >= 0) arr.splice(i, 1)
  else arr.push(code)
}

const DRILL_KEYS = ['goods', 'warehouse', 'batchNo', 'billNo', 'direction',
  'categoryName', 'brandName', 'storageProperty']

onMounted(async () => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  if (route.query.drill === '1') {
    applyDrillQuery()
    await r.query()
  } else {
    rememberLoad()
    // 条件不足（无商品无仓库）不发查询，避免大表全扫 —— 与后端硬护栏一致
    if (canSearch.value) await r.query()
  }
})

function applyDrillQuery() {
  const q = route.query
  if (q.start) start.value = q.start
  if (q.end) end.value = q.end
  const texts = []
  for (const k of DRILL_KEYS) {
    if (q[k] !== undefined && q[k] !== '') {
      filters.value[k] = String(q[k])
      texts.push(`${filterLabel(k)}=${q[k]}`)
    }
  }
  if (Array.isArray(q.billTypes) || typeof q.billTypes === 'string') {
    const types = [].concat(q.billTypes)
    filters.value.billTypes = types
    texts.push(`单据类型=${types.map(t => TYPE_LABEL[t] || t).join('/')}`)
  }
  if (q.start && q.end) texts.unshift(`日期 ${q.start}~${q.end}`)
  drillBanner.value = {
    from: q.from || '',
    fromName: FROM_NAME[q.from] || '进销存汇总表',
    text: texts.join('；'),
  }
}

function filterLabel(k) {
  return {
    goods: '商品', warehouse: '仓库', batchNo: '批次', billNo: '单据号',
    direction: '方向', categoryName: '分类', brandName: '品牌', storageProperty: '存储属性',
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
    if (!Array.isArray(filters.value.billTypes)) filters.value.billTypes = []
  } catch { /* 忽略损坏缓存 */ }
}
function rememberSave() {
  localStorage.setItem(STORE_KEY, JSON.stringify(filters.value))
}

function guardOrQuery() {
  if (!canSearch.value) {
    alert('请先选择商品或仓库再查询台账（至少一项）')
    return Promise.resolve()
  }
  const span = Math.round((new Date(end.value) - new Date(start.value)) / 86400000) + 1
  if (span > MAX_SPAN_DAYS) {
    alert('台账一次最多查询 92 天，请缩小日期范围（需要更长周期请用商品进销存汇总表）')
    return Promise.resolve()
  }
  pageNo.value = 1
  rememberSave()
  return r.query()
}
function onSearch() {
  guardOrQuery().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = { billTypes: [] }
  pageNo.value = 1
  sortField.value = ''
  sortOrder.value = ''
  drillBanner.value = null
  r.resetDate()
  // 重置后必然不满足商品/仓库护栏，不发查询
}
function onPage(n) { pageNo.value = n; r.query().catch(() => {}) }
function onSize(n) { pageSize.value = n; pageNo.value = 1; r.query().catch(() => {}) }

function rowClass(row) {
  if (row.rowKind === 'OPENING') return 'opening-row'
  if (row.reversalFlag === true || row.reversalFlag === 'true') return 'reversal-row'
  return ''
}

/** 单据号联查源业务单据（期初行无单据不跳；WMS 入库无对应列表页，不跳） */
function openBill({ row }) {
  const no = row.baseBillNo || row.billNo
  const target = BILL_LINK[row.billTypeText]
  if (!no || !target) return
  router.push({ path: target.path, query: { rptFilters: JSON.stringify({ [target.filter]: no }) } })
}

function filterText() {
  const f = filters.value
  const parts = [`日期：${start.value}~${end.value}`]
  if (f.goods) parts.push(`商品：${f.goods}`)
  if (f.warehouse) parts.push(`仓库：${f.warehouse}`)
  if (f.batchNo) parts.push(`批次：${f.batchNo}`)
  if (f.billNo) parts.push(`单据号：${f.billNo}`)
  if (f.billTypes.length) parts.push(`类型：${f.billTypes.map(t => TYPE_LABEL[t] || t).join('/')}`)
  if (f.direction) parts.push(`方向：${f.direction === 'IN' ? '收入' : f.direction === 'OUT' ? '发出' : f.direction}`)
  return parts.join('；')
}

function exportCurrent() {
  try {
    if (!canSearch.value) {
      alert('请先选择商品或仓库再导出（至少一项）')
      return
    }
    const treeRows = buildTreeRows(rows.value, [], visibleColumns.value.filter(c => c.num).map(c => c.key))
    exportRowsXlsx({
      reportName: '商品库存台账',
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
  if (!canSearch.value) {
    alert('请先选择商品或仓库再导出（至少一项）')
    return
  }
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

.rpt-guard-tip {
  background: #fdf6ec;
  border: 1px solid #f5dab1;
  color: #b88230;
  border-radius: 4px;
  padding: 8px 14px;
  font-size: 13px;
  margin-bottom: 10px;
}
.ff-multi {
  position: relative;
  cursor: pointer;
}
.ff-multi summary {
  height: 30px;
  line-height: 30px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 0 8px;
  font-size: 13px;
  color: #303133;
  background: #fff;
  min-width: 130px;
  list-style: none;
}
.ff-multi summary::after {
  content: '▾';
  float: right;
  color: #c0c4cc;
}
.multi-pop {
  position: absolute;
  top: 32px;
  left: 0;
  z-index: 20;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, .12);
  padding: 6px 10px;
  display: grid;
  grid-template-columns: repeat(2, max-content);
  gap: 4px 14px;
  font-size: 12.5px;
  white-space: nowrap;
}
.multi-pop label {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  font-weight: normal;
}
button:disabled {
  opacity: .5;
  cursor: not-allowed;
}
</style>
