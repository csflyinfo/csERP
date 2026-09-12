<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        商品进销存汇总表
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            恒等式：期初 + 本期收入 − 本期发出 = 期末。本期发生取库存流水日汇总（DWS，日结后刷新，
            盘中每 30 分钟增量）；期初不扫全历史流水，按「当前库存余额快照 − 本期净发生」倒推，
            与商品库存台账同一套底层元数据。金额为成本金额（移动加权平均），按列权限脱敏；
            单据类型明细列默认折叠。蓝色数字可钻取到「商品库存台账」（携带商品+仓库+期间+单据类型）。
            默认期间自然月本月（1 号至昨天）。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="openReconcile">与库存余额表对账</button>
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
      <div class="ff ff-check">
        <label>&nbsp;</label>
        <label class="chk-line"><input type="checkbox" v-model="filters.onlyNonZero" true-value="1" false-value="">仅结存不为 0</label>
      </div>
      <div class="ff ff-check">
        <label>&nbsp;</label>
        <label class="chk-line"><input type="checkbox" v-model="showDetail">展开单据类型明细列</label>
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
      :row-class-fn="rowClass"
      @sort="onSort"
      @drill="onDrill"
    />

    <!-- 与库存余额表对账：实物数量 vs (可用+锁定+冻结)，只列差异项 -->
    <div v-if="reconcileOpen" class="recon-mask" @click.self="reconcileOpen = false">
      <div class="recon-dialog">
        <div class="recon-head">
          <h3>与库存余额表对账</h3>
          <button class="btn-mini" @click="reconcileOpen = false">关闭</button>
        </div>
        <p class="recon-desc">
          口径：期末数量（实物结存）−（可用 + 锁定 + 冻结）。共 {{ rows.length }} 项，
          一致 <b class="recon-ok">{{ okCount }}</b> 项，
          不一致 <b :class="{ 'recon-bad': diffRows.length > 0 }">{{ diffRows.length }}</b> 项。
        </p>
        <div class="recon-scroll">
          <table class="recon-table" v-if="diffRows.length">
            <thead>
              <tr><th>商品编号</th><th>商品名称</th><th>仓库</th><th class="num">期末数量</th><th class="num">对账差异</th></tr>
            </thead>
            <tbody>
              <tr v-for="(r, i) in diffRows" :key="i">
                <td>{{ r.goodsCode }}</td>
                <td>{{ r.goodsName }}</td>
                <td>{{ r.warehouse }}</td>
                <td class="num">{{ fmtNum(r.endingQty) }}</td>
                <td class="num recon-bad">{{ fmtNum(r.reconcileDiff) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="recon-empty">全部一致，无差异项。</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ReportFilterBar from '@/components/report/ReportFilterBar.vue'
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows, fmtNum } from '@/components/report/report-table.js'
import { loadWarehouses } from '@/api/report-center.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'inventoryRollReport'
const CODE = 'inventory_roll'
const STORE_KEY = 'rpt:inventory_roll:state'
const router = useRouter()
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE, { naturalMonth: true })
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const warehouses = ref([])
const showDetail = ref(false)
const reconcileOpen = ref(false)

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
    if (saved.filters) Object.assign(filters.value, saved.filters)
    showDetail.value = !!saved.showDetail
  } catch { /* 忽略损坏缓存 */ }
}
function rememberSave() {
  localStorage.setItem(STORE_KEY, JSON.stringify({ filters: filters.value, showDetail: showDetail.value }))
}

/** 列 → #9 台账单据类型多选映射（与后端 rpt_dws_stock_move_d 透视口径一致）；空数组=全部类型 */
const DRILL_TYPES = {
  purchaseInQty: ['CGRK', 'WMS_INBOUND'], purchaseInPackage: ['CGRK', 'WMS_INBOUND'],
  salesReturnInQty: ['THRK'], salesReturnInPackage: ['THRK'],
  otherInQty: ['QTRK', 'JSRK', 'PDD', 'WMS_ADJUST_GAIN', 'OTHER'],
  otherInPackage: ['QTRK', 'JSRK', 'PDD', 'WMS_ADJUST_GAIN', 'OTHER'],
  transferInQty: ['DBRK'], transferInPackage: ['DBRK'],
  inQty: [], inPackage: [], inAmount: [],
  salesOutQty: ['XSCK'], salesOutPackage: ['XSCK'],
  purchaseReturnOutQty: ['CTCK'], purchaseReturnOutPackage: ['CTCK'],
  otherOutQty: ['QTCK', 'BSD', 'PDD', 'OTHER'],
  otherOutPackage: ['QTCK', 'BSD', 'PDD', 'OTHER'],
  transferOutQty: ['DBCK'], transferOutPackage: ['DBCK'],
  outQty: [], outPackage: [], outAmount: [],
  endingQty: [], endingPackage: [], endingAmount: [],
}

const BASE_COLUMNS = [
  { key: 'goodsCode', title: '商品编号', width: 100, sortable: true },
  { key: 'goodsName', title: '商品名称', width: 170 },
  { key: 'barcode', title: '条码', width: 90 },
  { key: 'baseUnit', title: '基本单位', width: 80 },
  { key: 'warehouse', title: '仓库', width: 90, sortable: true },
  { key: 'brandName', title: '品牌', width: 100 },
  { key: 'categoryName', title: '分类', width: 100 },
  { key: 'storageProperty', title: '存储属性', width: 80 },
  { key: 'openingQty', title: '期初数量', num: true, width: 100, sortable: true },
  { key: 'openingPackage', title: '期初件数', num: true, width: 90 },
  { key: 'openingAmount', title: '期初成本金额', num: true, money: true, sensitive: true, width: 120, sortable: true },
]
const DETAIL_IN_COLUMNS = [
  { key: 'purchaseInQty', title: '采购入库数量', num: true, width: 110, drill: true },
  { key: 'purchaseInPackage', title: '采购入库件数', num: true, width: 100, drill: true },
  { key: 'salesReturnInQty', title: '销售退货入库数量', num: true, width: 120, drill: true },
  { key: 'salesReturnInPackage', title: '销售退货入库件数', num: true, width: 120, drill: true },
  { key: 'otherInQty', title: '其他入库数量', num: true, width: 100, drill: true },
  { key: 'otherInPackage', title: '其他入库件数', num: true, width: 100, drill: true },
  { key: 'transferInQty', title: '调入数量', num: true, width: 90, drill: true },
  { key: 'transferInPackage', title: '调入件数', num: true, width: 90, drill: true },
]
const SUBTOTAL_IN_COLUMNS = [
  { key: 'inQty', title: '收入数量小计', num: true, width: 100, sortable: true, drill: true },
  { key: 'inPackage', title: '收入件数小计', num: true, width: 100, drill: true },
  { key: 'inAmount', title: '收入金额小计', num: true, money: true, sensitive: true, width: 120, sortable: true, drill: true },
  { key: 'adjustAmount', title: '成本调整金额', num: true, money: true, sensitive: true, width: 110 },
]
const DETAIL_OUT_COLUMNS = [
  { key: 'salesOutQty', title: '销售出库数量', num: true, width: 100, drill: true },
  { key: 'salesOutPackage', title: '销售出库件数', num: true, width: 100, drill: true },
  { key: 'purchaseReturnOutQty', title: '采购退货出库数量', num: true, width: 120, drill: true },
  { key: 'purchaseReturnOutPackage', title: '采购退货出库件数', num: true, width: 120, drill: true },
  { key: 'otherOutQty', title: '其他出库数量', num: true, width: 100, drill: true },
  { key: 'otherOutPackage', title: '其他出库件数', num: true, width: 100, drill: true },
  { key: 'transferOutQty', title: '调出数量', num: true, width: 90, drill: true },
  { key: 'transferOutPackage', title: '调出件数', num: true, width: 90, drill: true },
]
const SUBTOTAL_OUT_COLUMNS = [
  { key: 'outQty', title: '发出数量小计', num: true, width: 100, sortable: true, drill: true },
  { key: 'outPackage', title: '发出件数小计', num: true, width: 100, drill: true },
  { key: 'outAmount', title: '发出金额小计', num: true, money: true, sensitive: true, width: 120, sortable: true, drill: true },
]
const END_COLUMNS = [
  { key: 'endingQty', title: '期末数量', num: true, width: 100, sortable: true, drill: true },
  { key: 'endingPackage', title: '期末件数', num: true, width: 90, drill: true },
  { key: 'endingAmount', title: '期末成本金额', num: true, money: true, sensitive: true, width: 120, sortable: true, drill: true },
  { key: 'diffFlag', title: '勾稽标记', width: 90 },
]

const visibleColumns = computed(() => {
  const cols = [...BASE_COLUMNS]
  if (showDetail.value) cols.push(...DETAIL_IN_COLUMNS)
  cols.push(...SUBTOTAL_IN_COLUMNS)
  if (!showDetail.value) cols.pop() // 成本调整金额随明细列一起折叠
  if (showDetail.value) cols.push(...DETAIL_OUT_COLUMNS)
  cols.push(...SUBTOTAL_OUT_COLUMNS, ...END_COLUMNS)
  return cols.filter(c => canViewColumn(MODULE, c.title))
})

function rowClass(row) {
  return row.diffFlag && row.diffFlag !== '一致' ? 'diff-row' : ''
}

function onSearch() {
  pageNo.value = 1
  rememberSave()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = {}
  showDetail.value = false
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

/** 任一月度数/期末数钻取 #9：商品编号+仓库+期间+单据类型 */
function onDrill({ row, col }) {
  const types = DRILL_TYPES[col.key]
  const query = {
    drill: '1', from: CODE, start: start.value, end: end.value,
    goods: row.goodsCode, warehouse: row.warehouse,
  }
  if (types && types.length) query.billTypes = types
  router.push({ path: '/report/stock-ledger', query })
}

const diffRows = computed(() =>
  rows.value.filter(r => Math.abs(Number(r.reconcileDiff) || 0) > 0.0001))
const okCount = computed(() => rows.value.length - diffRows.value.length)
function openReconcile() {
  reconcileOpen.value = true
}

function filterText() {
  const f = filters.value
  const parts = [`日期：${start.value}~${end.value}`]
  if (f.warehouse) parts.push(`仓库：${f.warehouse}`)
  if (f.goods) parts.push(`商品：${f.goods}`)
  if (f.categoryName) parts.push(`分类：${f.categoryName}`)
  if (f.brandName) parts.push(`品牌：${f.brandName}`)
  if (f.storageProperty) parts.push(`存储属性：${f.storageProperty}`)
  if (f.onlyNonZero === '1') parts.push('仅结存不为0')
  return parts.join('；')
}

function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    const treeRows = buildTreeRows(rows.value, [], nums)
    exportRowsXlsx({
      reportName: '商品进销存汇总表',
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

.ff-check { justify-content: flex-start; }
.chk-line {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  cursor: pointer;
}
.recon-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, .35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.recon-dialog {
  background: #fff;
  border-radius: 8px;
  width: 720px;
  max-width: 92vw;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  padding: 18px 20px;
}
.recon-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.recon-head h3 {
  margin: 0;
  font-size: 16px;
}
.recon-desc {
  font-size: 13px;
  color: #606266;
  margin: 10px 0;
}
.recon-ok { color: #67c23a; }
.recon-bad { color: #f56c6c; }
.recon-scroll {
  overflow: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.recon-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.recon-table th,
.recon-table td {
  border-bottom: 1px solid #ebeef5;
  padding: 7px 10px;
}
.recon-table th {
  background: #f5f7fa;
  text-align: left;
}
.num { text-align: right; font-variant-numeric: tabular-nums; }
.recon-empty {
  padding: 30px 0;
  text-align: center;
  color: #909399;
}
</style>
