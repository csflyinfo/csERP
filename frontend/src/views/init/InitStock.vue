<script setup>
/**
 * 库存期初初始化（PRD-34）。
 * 两个页签：按批次（未启用 WMS）/ 按库位（启用 WMS，过账同时写 wms_bin_stock）。
 * 同一商品+仓库+批次禁止两种流程混用（后端强校验，前端仅提示）。
 */
import { ref, computed } from 'vue'
import { post } from '../../api/client.js'
import OpeningShell from './OpeningShell.vue'
import OpeningInitPanel from './OpeningInitPanel.vue'

const shellRef = ref(null)
const activeTab = ref('BATCH')
const goods = ref([])
const warehouses = ref([])
/** 仓库名 → 库位选项缓存 */
const binCache = ref(new Map())
const binTick = ref(0)

async function loadMasters() {
  try {
    const [g, w] = await Promise.all([
      post('/base/goods/page', { pageNo: 1, pageSize: 9999, filters: {} }).catch(() => ({ records: [] })),
      post('/base/warehouse/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => ({ records: [] })),
    ])
    goods.value = (g.records || []).filter(x => x.status !== 'DELETED')
      .map(x => ({ value: x.goodsCode, label: `${x.goodsCode} ${x.goodsName}` }))
    warehouses.value = (w.records || [])
      .map(x => ({ value: x.warehouseCode, label: `${x.warehouseCode} ${x.warehouseName}` }))
  } catch (e) {
    goods.value = []
    warehouses.value = []
  }
}
loadMasters()

function warehouseNameOf(code) {
  const w = warehouses.value.find(x => x.value === code)
  return w ? w.label.split(' ').slice(1).join(' ') : ''
}

async function loadBins(warehouseCode) {
  if (!warehouseCode) return []
  const whName = warehouseNameOf(warehouseCode)
  if (!whName) return []
  if (binCache.value.has(whName)) return binCache.value.get(whName)
  const loading = []
  binCache.value.set(whName, loading)
  try {
    const res = await post('/wms/bin/page',
      { pageNo: 1, pageSize: 500, filters: { warehouse: whName } })
    const opts = (res.records || [])
      .filter(b => b.status === 'NORMAL' || !b.status)
      .map(b => ({ value: b.binCode, label: b.binCode }))
    binCache.value.set(whName, opts)
  } catch (e) {
    binCache.value.set(whName, [])
  }
  binTick.value++
  return binCache.value.get(whName)
}

function setupForm(form) {
  if (form.warehouseCode) loadBins(form.warehouseCode)
}
function fieldChanged(field, form) {
  if (field === 'warehouseCode') {
    form.binCode = ''
    if (form.warehouseCode) loadBins(form.warehouseCode)
  }
}
// 依赖 binTick 让库位选项在异步加载后刷新
function dynamicOptions(field, form) {
  void binTick.value
  if (field !== 'binCode' || !form.warehouseCode) return []
  return binCache.value.get(warehouseNameOf(form.warehouseCode)) || []
}

const perm = { view: 'inv.init_stock.view', edit: 'inv.init_stock.edit',
  delete: 'inv.init_stock.delete', import: 'inv.init_stock.import' }

const baseStockCols = [
  { f: 'goodsCode', t: '商品编码', w: '130px' },
  { f: 'goodsName', t: '商品名称' },
  { f: 'warehouseCode', t: '仓库编码', w: '110px' },
  { f: 'warehouseName', t: '仓库名称', w: '120px' },
]
const tailStockCols = [
  { f: 'batchNo', t: '批号', w: '110px' },
  { f: 'productionDate', t: '生产日期', w: '105px' },
  { f: 'expiryDate', t: '效期日期', w: '105px' },
  { f: 'qty', t: '数量', num: true, digits: 4, w: '110px' },
  { f: 'unitPrice', t: '成本单价', num: true, digits: 6, w: '120px' },
  { f: 'amount', t: '成本金额', num: true, digits: 2, w: '120px' },
  { f: 'remark', t: '备注', w: '140px' },
]
const batchColumns = [...baseStockCols, ...tailStockCols, { f: 'generatedLedgerIds', t: '台账来源号', w: '170px' }]
const binColumns = [...baseStockCols.slice(0, 4),
  { f: 'binCode', t: '库位', w: '100px' }, { f: 'containerCode', t: '容器', w: '90px' },
  ...tailStockCols, { f: 'generatedBinStockIds', t: '库位账ID', w: '150px' }]

function stockFormDef(mode) {
  const binFields = mode === 'BIN' ? [
    { f: 'binCode', l: '库位编码', required: true, type: 'select', placeholder: '先选仓库自动加载', hint: '库位须正常且未冻结' },
    { f: 'containerCode', l: '容器编码', type: 'text' },
  ] : []
  return [
    { f: 'goodsCode', l: '商品编码', required: true, type: 'select', options: goods.value, placeholder: '选择商品' },
    { f: 'goodsName', l: '商品名称（留空取档案）', type: 'text' },
    { f: 'warehouseCode', l: '仓库编码', required: true, type: 'select', options: warehouses.value, placeholder: '选择仓库' },
    { f: 'warehouseName', l: '仓库名称（留空取档案）', type: 'text' },
    ...binFields,
    { f: 'batchNoInput', l: '批号', type: 'text', hint: '留空且有生产日期时自动按 yyyyMMdd 生成' },
    { f: 'productionDate', l: '生产日期', type: 'date' },
    { f: 'expiryDate', l: '效期日期', type: 'date', hint: '留空按生产日期+保质期推算' },
    { f: 'qty', l: '数量', required: true, type: 'number', step: mode === 'BIN' ? '0.001' : '0.0001',
      hint: mode === 'BIN' ? '>0，最多 3 位小数' : '>0，最多 4 位小数' },
    { f: 'unitPrice', l: '成本单价', required: true, type: 'number', step: '0.000001',
      hint: '≥0，允许填 0（0 成本页面警示，不阻断建账）' },
    { f: 'remark', l: '备注', type: 'textarea' },
  ]
}
const batchFormDef = computed(() => stockFormDef('BATCH'))
const binFormDef = computed(() => stockFormDef('BIN'))

const panelRefs = ref({})
function onPanel(el, key) {
  if (el) panelRefs.value[key] = el
}
function reloadPanels() {
  Object.values(panelRefs.value).forEach(p => p && p.load && p.load())
}
function panelChanged() {
  reloadPanels()
  shellRef.value?.loadStatus()
}

function fmt(v, d = 2) {
  return (Number(v || 0)).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d })
}
</script>

<template>
  <OpeningShell ref="shellRef" api-base="/init/stock" entity-label="库存"
                perm-post="inv.init_stock.post" perm-reverse="inv.init_stock.reverse"
                post-confirm="确认将暂存的库存期初建账？将按商品+仓加权平均成本写入库存三账（按库位页签同时写库位账）；已有数量余额的商品仓会整批拒绝。">
    <template #summary="{ status }">
      <div v-if="!status.posted" class="summary-bar">
        <span>有效行 <b>{{ status.lineCount || 0 }}</b>
          <em class="muted">（批次 {{ status.batchCount || 0 }} / 库位 {{ status.binCount || 0 }}）</em>
        </span>
        <span class="sep">|</span>
        <span>错误行 <b :class="{ red: status.errorCount > 0 }">{{ status.errorCount || 0 }}</b></span>
        <span class="sep">|</span>
        <span>数量合计 <b>{{ fmt(status.totalQty, 4) }}</b></span>
        <span class="sep">|</span>
        <span>成本合计 <b class="money">￥{{ fmt(status.totalAmount) }}</b></span>
        <div class="spacer"></div>
        <span v-if="status.zeroCostCount > 0" class="warn-text">⚠ {{ status.zeroCostCount }} 行为 0 成本，建账后由首次采购入库重算</span>
        <span v-else-if="status.errorCount > 0" class="warn-text">存在错误行，修正或删除后才能建账</span>
      </div>
      <div v-else class="summary-bar">
        <span>建账行数 <b>{{ status.postLineCount || 0 }}</b></span>
        <span class="sep">|</span>
        <span>数量合计 <b>{{ fmt(status.postTotalQty, 4) }}</b></span>
        <span class="sep">|</span>
        <span>成本合计 <b class="money">￥{{ fmt(status.postTotalAmount) }}</b></span>
      </div>
    </template>

    <template #default="{ locked }">
      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'BATCH' }" @click="activeTab = 'BATCH'">按批次（未启用 WMS）</button>
        <button class="tab" :class="{ active: activeTab === 'BIN' }" @click="activeTab = 'BIN'">按库位（启用 WMS）</button>
      </div>
      <div class="tab-hint muted">
        同一商品+仓库+批次只能使用一种流程；批号留空时按生产日期自动生成，效期留空按保质期推算。
      </div>
      <div v-show="activeTab === 'BATCH'" class="tab-pane">
        <OpeningInitPanel
          :ref="el => onPanel(el, 'BATCH')"
          api-base="/init/stock" :perm="perm" mode="BATCH" preset-key="initStockBatch"
          entity-label="库存（按批次）" :columns="batchColumns" :form-def="batchFormDef"
          :locked="locked" @changed="panelChanged"
        />
      </div>
      <div v-show="activeTab === 'BIN'" class="tab-pane">
        <OpeningInitPanel
          :ref="el => onPanel(el, 'BIN')"
          api-base="/init/stock" :perm="perm" mode="BIN" preset-key="initStockBin"
          entity-label="库存（按库位）" :columns="binColumns" :form-def="binFormDef"
          :locked="locked"
          :dynamic-options="dynamicOptions" :on-field-change="fieldChanged" :on-form-setup="setupForm"
          @changed="panelChanged"
        />
      </div>
    </template>
  </OpeningShell>
</template>

<style scoped>
.summary-bar { display: flex; align-items: center; gap: 10px; background: #fff; border: 1px solid #f0f0f0;
  border-radius: 8px; padding: 10px 14px; margin-top: 10px; font-size: 13px; flex-wrap: wrap; }
.spacer { flex: 1; }
.sep { color: #e0e0e0; }
.money { color: #cf1322; font-size: 15px; }
.red { color: #cf1322; }
.warn-text { color: #d46b08; font-size: 12px; }
.muted { color: #999; font-size: 12px; }
.tabs { display: flex; gap: 4px; margin-top: 12px; }
.tab { padding: 7px 18px; border: 1px solid #d9d9d9; background: #fafafa; border-radius: 8px 8px 0 0;
  cursor: pointer; font-size: 13px; border-bottom: none; }
.tab.active { background: #fff; color: #1677ff; border-color: #91caff; position: relative; top: 1px; font-weight: 600; }
.tab-hint { background: #fff; border: 1px solid #f0f0f0; border-bottom: none; padding: 6px 14px; font-size: 12px; }
.tab-pane { background: #fff; border: 1px solid #f0f0f0; border-radius: 0 8px 8px 8px; padding: 10px; }
</style>
