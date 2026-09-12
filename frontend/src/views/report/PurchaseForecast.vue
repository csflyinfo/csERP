<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        商品采购预测分析
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            销量取司机签收净销量（签收−退货，仅已审核，拒收不计）；日均销量=期间销量÷日历天数；
            建议采购量=ROUND(日均×预销天数−可用库存−采购在途, 0)，下限 0；无销量商品若设了库存下限，
            可用+在途低于下限时建议补到下限。在途按已审核采购订单未入库余量实时聚合；
            最近采购价取期间内最近入库单小单位含税价，箱价=小单位价×大单位换算率。
            点「计算预测」才取数，不会自动重查询。
          </div>
        </details>
      </div>
      <div v-if="rows.length" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button v-permission="'report.purchase_forecast.add'" class="btn-primary" @click="openGenerate">生成采购订单</button>
      </div>
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end">
      <div class="ff">
        <label>预销天数（1~90）</label>
        <input type="number" min="1" max="90" v-model="preSaleDays" style="min-width: 90px;">
      </div>
      <div class="ff">
        <label>在途占用</label>
        <select v-model="includeOnWay" style="min-width: 90px;">
          <option :value="true">扣减在途</option>
          <option :value="false">不扣在途</option>
        </select>
      </div>
      <div class="ff">
        <label>供应商取价</label>
        <select v-model="supplierMode" style="min-width: 100px;">
          <option value="main">主供应商</option>
          <option value="recent">最近供应商</option>
        </select>
      </div>
      <div class="ff">
        <label>仓库</label>
        <select v-model="filters.warehouse">
          <option value="">默认仓库（全部）</option>
          <option v-for="w in warehouses" :key="w" :value="w">{{ w }}</option>
        </select>
      </div>
      <div class="ff"><label>供应商</label><input v-model="filters.supplier" placeholder="编号/名称" @keyup.enter="compute"></div>
      <div class="ff"><label>商品分类</label><input v-model="filters.categoryName" @keyup.enter="compute"></div>
      <div class="ff"><label>品牌</label><input v-model="filters.brandName" @keyup.enter="compute"></div>
      <div class="ff"><label>采购员</label><input v-model="filters.buyer" @keyup.enter="compute"></div>
      <div class="ff" style="justify-content: flex-end;">
        <label style="display:flex;align-items:center;gap:4px;height:30px;">
          <input type="checkbox" v-model="showUnsold" style="min-width:auto;"> 无销量商品
        </label>
      </div>
      <div class="ff" style="justify-content: flex-end;">
        <label style="display:flex;align-items:center;gap:4px;height:30px;">
          <input type="checkbox" v-model="showZero" style="min-width:auto;"> 零建议商品
        </label>
      </div>
      <template #actions>
        <button class="btn-primary" @click="compute">计算预测</button>
        <button class="btn-plain" @click="onReset">重置</button>
      </template>
    </ReportFilterBar>

    <div v-if="lastParams" class="rpt-group-bar">
      <span>计算结果：{{ rows.length }} 种商品</span>
      <span class="muted">|</span>
      <span>已勾选 <b>{{ selectedCodes.size }}</b> 种</span>
      <span class="muted">|</span>
      <span class="muted">预销 {{ lastParams.preSaleDays }} 天 · {{ lastParams.includeOnWay ? '扣减在途' : '不扣在途' }} · {{ lastParams.supplierMode === 'recent' ? '最近供应商' : '主供应商' }}</span>
      <input v-model="displayKeyword" placeholder="结果内筛选商品" style="height:28px;border:1px solid #dcdfe6;border-radius:4px;padding:0 8px;margin-left:auto;min-width:180px;">
    </div>

    <div class="drill-card">
      <div class="drill-scroll rpt-forecast-grid">
        <table v-if="rows.length" class="drill-table">
          <thead>
            <tr>
              <th style="width:36px;position:sticky;left:0;background:#f5f7fa;z-index:3;"><input type="checkbox" :checked="allVisibleChecked" @change="toggleAllVisible"></th>
              <th v-for="col in visibleColumns" :key="col.key"
                  :style="col.width ? { width: typeof col.width === 'number' ? col.width+'px' : col.width } : null"
                  :class="{ num: col.num, sensitive: col.sensitive }">{{ col.title }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in displayRows" :key="row.goodsCode" class="leaf-row">
              <td style="position:sticky;left:0;background:#fff;"><input type="checkbox" :checked="selectedCodes.has(row.goodsCode)" @change="toggleOne(row.goodsCode)"></td>
              <td>
                <span v-if="!row.supplierName" class="warn-text">未设置供应商</span>
                <template v-else>
                  <div>{{ row.supplierCode || '' }}</div>
                  <div>{{ row.supplierName }}</div>
                </template>
              </td>
              <td>{{ row.buyer || '' }}</td>
              <td>{{ row.goodsCode }}</td>
              <td style="text-align:left;">
                {{ row.goodsName }}
                <div v-if="row.pendingOrderNos" class="warn-text" style="font-size:11px;">⚠ 待审核预测单：{{ row.pendingOrderNos }}</div>
              </td>
              <td>{{ row.barcode || '' }}</td>
              <td>{{ row.baseUnit }}</td>
              <td class="num">{{ fmt(row.salesQtyPeriod) }}</td>
              <td class="num">{{ fmt(row.salesQty7d) }}</td>
              <td class="num">{{ fmt(row.salesQty30d) }}</td>
              <td class="num">{{ fmt(row.avgDailySales) }}</td>
              <td class="num">{{ fmt(row.availableBase) }}</td>
              <td class="num">{{ fmt(row.availablePackage) }}</td>
              <td class="num">{{ row.saleDays == null ? '-' : fmt(row.saleDays) }}</td>
              <td class="num">{{ fmt(row.onWayQty) }}</td>
              <td class="num">{{ fmt(row.suggestBase) }}</td>
              <td class="num">{{ fmt(row.suggestPackage) }}</td>
              <td class="num"><input class="num-input" :class="{ changed: Number(row.purchaseBase) !== Number(row.suggestBase) }"
                  v-model.number="row.purchaseBase" @change="onBaseChange(row)"></td>
              <td class="num"><input class="num-input" :class="{ changed: Number(row.purchasePackage) !== Number(row.suggestPackage) }"
                  v-model.number="row.purchasePackage" @change="onPackageChange(row)"></td>
              <td>{{ row.largeUnit || '' }}</td>
              <td class="num">{{ fmt(row.largeConvertQty) }}</td>
              <td v-if="canViewColumn(MODULE, '最近采购价')" class="num sensitive">{{ fmtMoney(row.latestPrice) }}</td>
              <td v-if="canViewColumn(MODULE, '箱价')" class="num sensitive">{{ fmtMoney(row.boxPrice) }}</td>
              <td>{{ row.defaultWarehouse || '' }}</td>
              <td>{{ supplierMode === 'recent' ? (row.recentSupplierName || '') : '' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-cell" style="padding:40px;text-align:center;color:#909399;">
          {{ computedOnce ? '当前条件下无预测结果，可勾选「无销量商品/零建议商品」或放宽条件后重新计算' : '请设置预销天数等条件后点击「计算预测」' }}
        </div>
      </div>
      <div v-if="loading" class="drill-loading">计算中…</div>
    </div>

    <!-- 生成采购订单确认 -->
    <div v-if="genDialog" class="rpt-modal-mask" @click.self="genDialog = null">
      <div class="rpt-modal">
        <div class="rpt-modal-title">生成采购订单（待审核）</div>
        <div class="rpt-modal-body">
          <div class="ff" style="margin-bottom:10px;">
            <label>生成口径</label>
            <select v-model="genMode">
              <option value="qty">按基本数量（采购量小单位）</option>
              <option value="package">按件数（采购件数×换算率）</option>
            </select>
          </div>
          <p>将按「供应商 + 采购员 + 仓库」拆分为多张 <b>待审核</b> 采购订单，共勾选 <b>{{ selectedRows.length }}</b> 种商品。</p>
          <p v-if="canViewColumn(MODULE, '最近采购价')">预计采购总额（含税，以订单实际为准）：<b class="warn-text">¥{{ estimatedAmount }}</b></p>
          <p class="warn-text">注意：生成后可在采购订单列表修改/审核；同一商品已存在待审核预测单时已在列表中黄色提示，请勿重复生成。</p>
          <p v-if="!filters.warehouse" class="warn-text">未选择查询仓库时，将使用各商品的默认仓库；默认仓库为空的商品会生成失败。</p>
        </div>
        <div class="rpt-modal-ops">
          <button class="btn-plain" @click="genDialog = false">取消</button>
          <button class="btn-primary" :disabled="generating" @click="confirmGenerate">{{ generating ? '生成中…' : '确认生成' }}</button>
        </div>
      </div>
    </div>

    <!-- 生成结果 -->
    <div v-if="genResult" class="rpt-modal-mask" @click.self="genResult = null">
      <div class="rpt-modal">
        <div class="rpt-modal-title">生成完成</div>
        <div class="rpt-modal-body">
          <p>批次号：{{ genResult.batchNo }}</p>
          <p>共生成 <b>{{ genResult.orderCount }}</b> 张待审核采购订单，含 <b>{{ genResult.goodsKinds }}</b> 行商品：</p>
          <p v-for="no in genResult.orderNos" :key="no">
            <a class="link-num" @click="openOrder(no)">{{ no }}</a>
          </p>
        </div>
        <div class="rpt-modal-ops">
          <button class="btn-primary" @click="genResult = null">知道了</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import ReportFilterBar from '@/components/report/ReportFilterBar.vue'
import { defaultRange } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows, fmtNum } from '@/components/report/report-table.js'
import { forecastCompute, forecastGenerate, loadWarehouses } from '@/api/report-center.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'purchaseForecastReport'
const STORE_KEY = 'rpt:purchase_forecast:params'
const router = useRouter()
const route = useRoute()
const { canViewColumn } = useRbac(MODULE)

const dr = defaultRange()
const start = ref(dr.start)
const end = ref(dr.end)
const filters = ref({})
const warehouses = ref([])
const preSaleDays = ref(20)
const includeOnWay = ref(true)
const supplierMode = ref('main')
const showUnsold = ref(false)
const showZero = ref(false)

const rows = ref([])
const loading = ref(false)
const computedOnce = ref(false)
const lastParams = ref(null)
const selectedCodes = ref(new Set())
const displayKeyword = ref('')

const genDialog = ref(false)
const genMode = ref('qty')
const generating = ref(false)
const genResult = ref(null)

const visibleColumns = computed(() => {
  const cols = [
    { key: 'supplierName', title: '供应商', width: 150 },
    { key: 'buyer', title: '采购员', width: 90 },
    { key: 'goodsCode', title: '商品编号', width: 100 },
    { key: 'goodsName', title: '商品名称', width: 200 },
    { key: 'barcode', title: '条码', width: 90 },
    { key: 'baseUnit', title: '基本单位', width: 80 },
    { key: 'salesQtyPeriod', title: '期间销量', num: true, width: 90 },
    { key: 'salesQty7d', title: '近7天销量', num: true, width: 90 },
    { key: 'salesQty30d', title: '近30天销量', num: true, width: 100 },
    { key: 'avgDailySales', title: '日均销量', num: true, width: 90 },
    { key: 'availableBase', title: '可用库存(小单位)', num: true, width: 130 },
    { key: 'availablePackage', title: '可用件数', num: true, width: 90 },
    { key: 'saleDays', title: '可销天数', num: true, width: 80 },
    { key: 'onWayQty', title: '采购在途', num: true, width: 90 },
    { key: 'suggestBase', title: '建议采购量(小单位)', num: true, width: 140 },
    { key: 'suggestPackage', title: '建议件数', num: true, width: 90 },
    { key: 'purchaseBase', title: '采购量(小单位)', num: true, width: 130 },
    { key: 'purchasePackage', title: '采购件数', num: true, width: 100 },
    { key: 'largeUnit', title: '大单位', width: 80 },
    { key: 'largeConvertQty', title: '换算率', num: true, width: 80 },
  ]
  if (canViewColumn(MODULE, '最近采购价')) cols.push({ key: 'latestPrice', title: '最近采购价', num: true, sensitive: true, width: 100 })
  if (canViewColumn(MODULE, '箱价')) cols.push({ key: 'boxPrice', title: '箱价', num: true, sensitive: true, width: 100 })
  cols.push({ key: 'defaultWarehouse', title: '默认仓库', width: 90 })
  if (supplierMode.value === 'recent') cols.push({ key: 'recentSupplierName', title: '最近供应商', width: 140 })
  return cols
})

const displayRows = computed(() => {
  const kw = displayKeyword.value.trim()
  if (!kw) return rows.value
  return rows.value.filter(r =>
    (r.goodsCode || '').includes(kw) || (r.goodsName || '').includes(kw) || (r.barcode || '').includes(kw))
})
const allVisibleChecked = computed(() =>
  displayRows.value.length > 0 && displayRows.value.every(r => selectedCodes.value.has(r.goodsCode)))
const selectedRows = computed(() =>
  rows.value.filter(r => selectedCodes.value.has(r.goodsCode) && Number(genMode.value === 'package' ? r.purchasePackage : r.purchaseBase) > 0))
const estimatedAmount = computed(() => {
  const total = selectedRows.value.reduce((s, r) => {
    const qty = Number(genMode.value === 'package' ? r.purchasePackage : r.purchaseBase) || 0
    const price = Number(genMode.value === 'package' ? r.boxPrice : r.latestPrice) || 0
    return s + qty * price
  }, 0)
  return total.toFixed(2)
})

onMounted(() => {
  loadWarehouses().then(ws => { warehouses.value = ws }).catch(() => {})
  // #6 缺货分析「建议补货量」钻取带入（仓库/期间 + 结果内商品关键字），不自动计算，点查询生效
  if (route.query.drill === '1') {
    if (route.query.start) start.value = String(route.query.start)
    if (route.query.end) end.value = String(route.query.end)
    if (route.query.warehouse) filters.value.warehouse = String(route.query.warehouse)
    if (route.query.goods) displayKeyword.value = String(route.query.goods)
    return
  }
  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY) || '{}')
    if (saved.start) start.value = saved.start
    if (saved.end) end.value = saved.end
    if (saved.filters) Object.assign(filters.value, saved.filters)
    if (saved.preSaleDays != null) preSaleDays.value = saved.preSaleDays
    if (saved.includeOnWay != null) includeOnWay.value = saved.includeOnWay
    if (saved.supplierMode) supplierMode.value = saved.supplierMode
    if (saved.showUnsold != null) showUnsold.value = saved.showUnsold
    if (saved.showZeroSuggestion != null) showZero.value = saved.showZeroSuggestion
  } catch { /* 忽略损坏缓存 */ }
})

function rememberSave() {
  localStorage.setItem(STORE_KEY, JSON.stringify({
    start: start.value, end: end.value, filters: filters.value,
    preSaleDays: preSaleDays.value, includeOnWay: includeOnWay.value,
    supplierMode: supplierMode.value, showUnsold: showUnsold.value, showZeroSuggestion: showZero.value,
  }))
}

function cleanFilters(obj) {
  const out = {}
  for (const [k, v] of Object.entries(obj || {})) {
    if (v === null || v === undefined) continue
    if (typeof v === 'string' && v.trim() === '') continue
    out[k] = v
  }
  return out
}

function buildBody() {
  const days = Number(preSaleDays.value)
  if (!Number.isInteger(days) || days < 1 || days > 90) {
    throw new Error('预销天数必须是 1~90 的正整数')
  }
  return {
    dateRange: { startDate: start.value, endDate: end.value },
    preSaleDays: days,
    includeOnWay: includeOnWay.value,
    supplierMode: supplierMode.value,
    showUnsold: showUnsold.value,
    showZeroSuggestion: showZero.value,
    filters: cleanFilters(filters.value),
  }
}

async function compute() {
  let body
  try {
    body = buildBody()
  } catch (e) {
    alert(e.message)
    return
  }
  loading.value = true
  try {
    const list = await forecastCompute(body)
    rows.value = list || []
    computedOnce.value = true
    lastParams.value = body
    selectedCodes.value = new Set(rows.value.filter(r => Number(r.purchaseBase) > 0).map(r => r.goodsCode))
    rememberSave()
  } catch (e) {
    alert('计算失败：' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

function onReset() {
  const d = defaultRange()
  start.value = d.start; end.value = d.end
  filters.value = {}
  preSaleDays.value = 20
  includeOnWay.value = true
  supplierMode.value = 'main'
  showUnsold.value = false
  showZero.value = false
  rows.value = []
  computedOnce.value = false
  lastParams.value = null
  selectedCodes.value = new Set()
}

function toggleOne(code) {
  const next = new Set(selectedCodes.value)
  if (next.has(code)) next.delete(code); else next.add(code)
  selectedCodes.value = next
}
function toggleAllVisible() {
  const next = new Set(selectedCodes.value)
  if (allVisibleChecked.value) {
    displayRows.value.forEach(r => next.delete(r.goodsCode))
  } else {
    displayRows.value.forEach(r => next.add(r.goodsCode))
  }
  selectedCodes.value = next
}

/** 改基本量联动件数、改件数联动基本量（按换算率，无大单位时 1:1） */
function onBaseChange(row) {
  const base = Number(row.purchaseBase) || 0
  const conv = Number(row.largeConvertQty) || 0
  row.purchasePackage = conv > 0 ? Number((base / conv).toFixed(4)) : base
}
function onPackageChange(row) {
  const pkg = Number(row.purchasePackage) || 0
  const conv = Number(row.largeConvertQty) || 0
  row.purchaseBase = conv > 0 ? Number((pkg * conv).toFixed(4)) : pkg
}

function openGenerate() {
  if (selectedRows.value.length === 0) {
    alert('请勾选采购量大于 0 的商品')
    return
  }
  genMode.value = 'qty'
  genDialog.value = true
}

async function confirmGenerate() {
  const items = selectedRows.value.map(r => ({ ...r }))
  const missingSupplier = items.filter(r => !r.supplierCode && !r.supplierName)
  if (missingSupplier.length) {
    alert(`以下商品未设置供应商，无法生成：${missingSupplier.slice(0, 5).map(r => r.goodsName).join('、')}${missingSupplier.length > 5 ? ' 等' : ''}`)
    return
  }
  generating.value = true
  try {
    const res = await forecastGenerate({
      genMode: genMode.value,
      warehouse: filters.value.warehouse || null,
      params: JSON.stringify(lastParams.value),
      items,
    })
    genDialog.value = false
    genResult.value = res
    selectedCodes.value = new Set()
  } catch (e) {
    alert('生成失败：' + (e.message || '未知错误'))
  } finally {
    generating.value = false
  }
}

function openOrder(no) {
  genResult.value = null
  router.push({ path: '/purchase-order', query: { rptFilters: JSON.stringify({ '采购单号': no }) } })
}

function fmt(v) { return fmtNum(v, 'qty') }
function fmtMoney(v) { return v == null || v === '' ? '' : fmtNum(v, 'money') }

function filterText() {
  const parts = [
    `日期：${start.value}~${end.value}`,
    `预销天数：${preSaleDays.value}`,
    includeOnWay.value ? '扣减在途' : '不扣在途',
    supplierMode.value === 'recent' ? '最近供应商' : '主供应商',
  ]
  if (filters.value.warehouse) parts.push(`仓库：${filters.value.warehouse}`)
  if (filters.value.supplier) parts.push(`供应商：${filters.value.supplier}`)
  return parts.join('；')
}

function exportCurrent() {
  try {
    // 导出列与屏幕一致（含编辑后的采购量），行取当前结果（受结果内筛选影响时仅导出可见行）
    const cols = visibleColumns.value
    const treeRows = buildTreeRows(displayRows.value, [], cols.filter(c => c.num).map(c => c.key))
    exportRowsXlsx({
      reportName: '商品采购预测分析',
      filterText: filterText(),
      columns: cols,
      treeRows,
      summary: null,
    })
  } catch (e) {
    alert('导出失败：' + (e.message || '未知错误'))
  }
}
</script>

<style scoped>
@import './report-page.css';

.rpt-modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, .4);
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
}
.rpt-modal {
  background: #fff;
  border-radius: 8px;
  width: 480px;
  max-width: 90vw;
  max-height: 80vh;
  overflow: auto;
  box-shadow: 0 8px 30px rgba(0, 0, 0, .2);
}
.rpt-modal-title {
  padding: 14px 18px;
  font-size: 15px;
  font-weight: 700;
  border-bottom: 1px solid #ebeef5;
}
.rpt-modal-body {
  padding: 14px 18px;
  font-size: 13px;
  color: #606266;
  line-height: 1.9;
}
.rpt-modal-body .ff input,
.rpt-modal-body .ff select {
  height: 30px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 0 8px;
  font-size: 13px;
  min-width: 240px;
}
.rpt-modal-ops {
  padding: 12px 18px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  border-top: 1px solid #ebeef5;
}
.sensitive { color: #e6a23c; }
.link-num {
  color: #409eff;
  text-decoration: underline;
  cursor: pointer;
}
</style>
