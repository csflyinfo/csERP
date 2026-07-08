<script setup>
/**
 * 盘点单抽屉 —— 库存盘点全流程组件。
 *
 * 流程：
 *   create 阶段 — 选择仓库 + 盘点类型（全盘/抽盘）→ 生成盘点单
 *   edit 阶段 — 填写实盘数量，自动算差异 + 金额 → 保存实盘 → 审核
 *   view 阶段 — 已审核的盘点单只读查看
 *
 * 交互：
 *   - create 阶段：仓库下拉、盘点日期、全盘/抽盘 Radio、抽盘时 InlineGoodsPicker 选商品
 *   - edit 阶段：明细表含批次/账面数量(只读)/实盘数量(InputNumber)/差异(颜色)/成本单价/金额
 *   - 顶部汇总卡片：盘前 vs 盘后（数量+金额）
 *   - "仅看差异" Switch、"添加新批次商品" 按钮
 *   - 保存实盘 → 可审核 → 审核后更新库存及流水
 */
import { ref, computed, watch } from 'vue'
import { post, upload, downloadBlob } from '../api/client.js'
import InlineGoodsPicker from './InlineGoodsPicker.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'create' },
  editData: { type: Object, default: null },
})
const emit = defineEmits(['close', 'save'])

const loading = ref(false)
const warehouseList = ref([])
const allGoods = ref([])
const formErrors = ref({})

// 头部
const header = ref({
  warehouse: '',
  countDate: new Date().toISOString().slice(0, 10),
  countType: '1',
  remark: '',
  sheetNo: '',
  status: '',
})

// 抽盘商品暂存
const stagedGoods = ref([])
const stagedGoodsSearch = ref('')

// 明细
const detailList = ref([])

// 仅看差异
const onlyDiff = ref(false)

// 创建阶段显隐
const showCreateForm = computed(() => {
  return !header.value.sheetNo
})
const isApproved = computed(() => header.value.status === 'APPROVED')
const isView = computed(() => props.mode === 'view' || isApproved.value)

// 汇总
const summary = computed(() => {
  let bookQty = 0, realQty = 0, surplusQty = 0, shortageQty = 0
  let bookAmount = 0, realAmount = 0, surplusAmount = 0, shortageAmount = 0
  let surplusCount = 0, shortageCount = 0
  for (const d of detailList.value) {
    const bq = Number(d.bookQty || 0)
    const rq = Number(d.realQty || 0)
    const dq = Number(d.diffQty || 0)
    const ba = Number(d.bookAmount || 0)
    const ra = Number(d.realAmount || 0)
    const da = Number(d.diffAmount || 0)
    bookQty += bq; realQty += rq
    bookAmount += ba; realAmount += ra
    if (dq > 0) { surplusQty += dq; surplusAmount += da; surplusCount++ }
    if (dq < 0) { shortageQty += Math.abs(dq); shortageAmount += Math.abs(da); shortageCount++ }
  }
  return {
    bookQty: bookQty.toFixed(4), realQty: realQty.toFixed(4),
    diffQty: (realQty - bookQty).toFixed(4),
    bookAmount: bookAmount.toFixed(2), realAmount: realAmount.toFixed(2),
    diffAmount: (realAmount - bookAmount).toFixed(2),
    surplusQty: surplusQty.toFixed(4), surplusAmount: surplusAmount.toFixed(2), surplusCount,
    shortageQty: shortageQty.toFixed(4), shortageAmount: shortageAmount.toFixed(2), shortageCount,
  }
})

const displayedDetails = computed(() => {
  if (!onlyDiff.value) return detailList.value
  return detailList.value.filter(d => Number(d.diffQty || 0) !== 0)
})

function makeNewBatchRow() {
  return {
    detailId: null, goodsCode: '', goodsName: '', spec: '', unitName: '',
    batchNo: '', productionDate: '',
    bookQty: 0, realQty: 0, diffQty: 0,
    costPrice: 0, bookAmount: 0, realAmount: 0, diffAmount: 0,
    diffRemark: '', isNewBatch: true, goodsSearch: '',
  }
}

// ===== 生命周期 =====

watch(() => props.visible, async (v) => {
  if (!v) return
  formErrors.value = {}
  header.value = {
    warehouse: '', countDate: new Date().toISOString().slice(0, 10),
    countType: '1', remark: '', sheetNo: '', status: '',
  }
  detailList.value = []
  stagedGoods.value = []
  onlyDiff.value = false
  await loadBase()
  if (props.mode !== 'create' && props.editData) await loadEdit(props.editData)
})

async function loadBase() {
  try {
    const w = await post('/base/warehouse/page', { pageNo: 1, pageSize: 500, filters: {} })
    warehouseList.value = (w.records || []).filter(x => x.warehouseName || x.name)
  } catch (_) { warehouseList.value = [] }
  try {
    const g = await post('/base/goods/page', { pageNo: 1, pageSize: 2000, filters: {} })
    allGoods.value = (g.records || []).filter(x => String(x.status || '').toUpperCase() !== 'STOPPED')
  } catch (_) { allGoods.value = [] }
}

async function loadEdit(editData) {
  loading.value = true
  try {
    const raw = editData._raw || editData
    const sheetNo = raw.sheetNo || raw.c0
    const data = await post('/inventory/stock-take/detail', { sheetNo })
    const m = data.master
    header.value = {
      warehouse: m.warehouse || '',
      countDate: String(m.countDate || '').slice(0, 10),
      countType: String(m.countType || '1'),
      remark: m.remark || '',
      sheetNo: m.sheetNo || '',
      status: m.status || '',
    }
    detailList.value = (data.details || []).map(d => ({
      detailId: d.detailId,
      goodsCode: d.goodsCode || '',
      goodsName: d.goodsName || '',
      spec: d.spec || '',
      unitName: d.unitName || '',
      batchNo: d.batchNo || '',
      productionDate: d.productionDate ? String(d.productionDate).slice(0, 10) : '',
      bookQty: Number(d.bookQty || 0),
      realQty: Number(d.realQty || 0),
      diffQty: Number(d.diffQty || 0),
      costPrice: Number(d.costPrice || 0),
      bookAmount: Number(d.bookAmount || 0),
      realAmount: Number(d.realAmount || 0),
      diffAmount: Number(d.diffAmount || 0),
      diffRemark: d.diffRemark || '',
      isNewBatch: !!d.isNewBatch,
      goodsSearch: d.goodsCode || '',
    }))
  } catch (e) { formErrors.value = { header: e.message || '加载盘点单失败' } }
  finally { loading.value = false }
}

// ===== 创建盘点单 =====

async function handleCreate() {
  if (!header.value.warehouse) { formErrors.value = { wh: '请选择盘点仓库' }; return }
  if (header.value.countType === '2' && stagedGoods.value.length === 0) {
    formErrors.value = { goods: '抽盘请至少添加一个商品' }; return
  }
  loading.value = true
  try {
    const payload = {
      warehouse: header.value.warehouse,
      countType: header.value.countType,
      countDate: header.value.countDate,
      remark: header.value.remark,
    }
    if (header.value.countType === '2') {
      payload.itemNos = stagedGoods.value.map(s => s.goodsCode)
    }
    const res = await post('/inventory/stock-take/create', payload)
    header.value.sheetNo = res.sheetNo
    header.value.status = 'PENDING'
    await loadEdit({ _raw: { sheetNo: res.sheetNo } })
    emit('save')
  } catch (e) { alert('创建盘点单失败：' + (e.message || '未知错误')) }
  finally { loading.value = false }
}

// ===== 商品选择（抽盘）=====

function onGoodsSelect(g) {
  if (stagedGoods.value.some(s => s.goodsCode === g.goodsCode)) { alert('该商品已添加'); return }
  stagedGoods.value.push({
    goodsCode: g.goodsCode,
    goodsName: g.goodsName || '',
  })
}

function removeStagedGoods(code) {
  stagedGoods.value = stagedGoods.value.filter(s => s.goodsCode !== code)
}

function clearStagedGoods() {
  stagedGoods.value = []
}

// ===== 实盘数量输入 =====

function onRealQtyInput(row) {
  const bookQty = Number(row.bookQty || 0)
  const realQty = Number(row.realQty || 0)
  const diffQty = realQty - bookQty
  const costPrice = Number(row.costPrice || 0)
  row.diffQty = diffQty
  row.bookAmount = parseFloat((bookQty * costPrice).toFixed(2))
  row.realAmount = parseFloat((realQty * costPrice).toFixed(2))
  row.diffAmount = parseFloat((diffQty * costPrice).toFixed(2))
}

// ===== 导入商品（抽盘创建阶段）=====

const importDialogVisible = ref(false)
const importing = ref(false)
const importFileRef = ref(null)

async function handleImport() {
  const file = importFileRef.value?.files?.[0]
  if (!file) { alert('请选择 Excel 文件'); return }
  importing.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    // URL 中带 warehouse 参数
    const res = await upload(`/inventory/stock-take/parse-excel?warehouse=${encodeURIComponent(header.value.warehouse)}`, form)
    const items = res.items || []
    const notFound = res.notFound || []
    for (const item of items) {
      if (stagedGoods.value.some(s => s.goodsCode === item.goodsCode)) continue
      stagedGoods.value.push({ goodsCode: item.goodsCode, goodsName: item.goodsName || '' })
    }
    if (notFound.length) alert(`${notFound.length} 个编号未找到: ${notFound.slice(0, 5).join(', ')}`)
    importDialogVisible.value = false
  } catch (e) { alert('导入失败：' + (e.message || '未知错误')) }
  finally { importing.value = false }
}

async function handleExportDetail() {
  try {
    const blob = await downloadBlob('/inventory/stock-take/export-detail', { sheetNo: header.value.sheetNo })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = `盘点明细_${header.value.sheetNo}.xlsx`; a.click()
    URL.revokeObjectURL(url)
  } catch (e) { alert('导出失败：' + (e.message || '未知错误')) }
}

const importRealDialogVisible = ref(false)
const importRealFileRef = ref(null)
const importingReal = ref(false)

async function handleImportReal() {
  const file = importRealFileRef.value?.files?.[0]
  if (!file) { alert('请选择 Excel 文件'); return }
  importingReal.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    const res = await upload(`/inventory/stock-take/import-real?sheetNo=${encodeURIComponent(header.value.sheetNo)}`, form)
    alert(`导入成功，更新了 ${res.updated || 0} 条明细`)
    importRealDialogVisible.value = false
    await loadEdit({ _raw: { sheetNo: header.value.sheetNo } })
    emit('save')
  } catch (e) { alert('导入实盘失败：' + (e.message || '未知错误')) }
  finally { importingReal.value = false }
}

function downloadExcelTemplate() {
  // 生成一个简单的 CSV 作为模板
  const bom = '﻿'
  const csv = '商品编号\nS0001\nS0002\n'
  const blob = new Blob([bom + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = '盘点商品导入模板.csv'; a.click()
  URL.revokeObjectURL(url)
}

// ===== 添加新批次商品 =====

function onNewBatchGoodsSelect(row, g) {
  row.goodsCode = g.goodsCode
  row.goodsName = g.goodsName || ''
  row.spec = g.spec || ''
  row.unitName = g.baseUnit || g.unitName || ''
  row.goodsSearch = g.goodsCode
  fetchCostPrice(row)
}

async function fetchCostPrice(row) {
  try {
    const res = await post('/inventory/balance/page', {
      pageNo: 1, pageSize: 1,
      filters: { keyword: row.goodsCode, warehouses: [header.value.warehouse] }
    })
    const records = res.records || []
    if (records.length > 0) {
      row.costPrice = Number(records[0].costPrice || 0)
    }
  } catch (_) { }
}

function addNewBatchRow() {
  detailList.value.push(makeNewBatchRow())
}

// 生产日期变更时自动生成批次号（遵循 CLAUDE.md 批次号规则：YYYYMMDD 无前缀）
function onProdDateChange(row) {
  if (!row._batchManual && row.productionDate) {
    row.batchNo = String(row.productionDate).replace(/-/g, '')
  }
}
// 用户手动编辑批次号后标记，不再自动覆盖
function onBatchNoInput(row) {
  row._batchManual = true
}

function removeBatchRow(i) {
  const row = detailList.value[i]
  if (!row.isNewBatch) { alert('只能删除新增批次的行'); return }
  detailList.value.splice(i, 1)
}

// ===== 保存实盘 =====

async function saveReal() {
  const dirty = detailList.value.filter(d => d.detailId)
  if (!dirty.length) { alert('没有可保存的明细'); return }
  loading.value = true
  try {
    await post('/inventory/stock-take/update-real', {
      sheetNo: header.value.sheetNo,
      details: dirty.map(d => ({
        detailId: d.detailId,
        realQty: Number(d.realQty || 0),
        diffRemark: d.diffRemark || '',
      })),
    })
    emit('save')
    alert('实盘数量已保存')
  } catch (e) { alert('保存失败：' + (e.message || '未知错误')) }
  finally { loading.value = false }
}

// ===== 审核 =====

async function handleAudit() {
  if (!confirm('确认审核盘点单？\n审核后将以实盘数量更新库存并写入流水，审核时重新取最新成本单价。')) return
  loading.value = true
  try {
    await post('/inventory/stock-take/audit', { sheetNo: header.value.sheetNo })
    emit('save')
    emit('close')
  } catch (e) { alert('审核失败：' + (e.message || '未知错误')) }
  finally { loading.value = false }
}

// ===== 反审核 =====

async function handleReverseAudit() {
  if (!confirm('确认反审核？\n库存将回滚为审核前的状态。')) return
  loading.value = true
  try {
    await post('/inventory/stock-take/reverse-audit', { sheetNo: header.value.sheetNo })
    header.value.status = 'PENDING'
    emit('save')
  } catch (e) { alert('反审核失败：' + (e.message || '未知错误')) }
  finally { loading.value = false }
}

function close() { emit('close') }
</script>

<template>
  <div v-if="visible" class="apply-mask">
    <div class="apply-box">
      <!-- 头部 -->
      <div class="apply-head">
        <b>{{ showCreateForm ? '新建盘点单' : (isView ? '查看盘点单' : '编辑盘点单') }}</b>
        <span v-if="header.sheetNo" style="margin-left:10px;color:#606266;font-size:12px">{{ header.sheetNo }}</span>
        <span v-if="header.status" class="badge" :class="isApproved ? 'ok' : 'wait'" style="margin-left:8px">
          {{ isApproved ? '已审核' : '待审核' }}
        </span>
        <span v-if="header.countType === '2'" class="badge" style="margin-left:4px;background:#e6f7ff;color:#1890ff">抽盘</span>
        <div style="flex:1"></div>
        <button class="btn" @click="close">关闭</button>
        <button v-if="showCreateForm" class="btn primary" @click="handleCreate" :disabled="loading">生成盘点单</button>
        <button v-if="!showCreateForm && !isApproved" class="btn" @click="saveReal" :disabled="loading">保存</button>
        <button v-if="!showCreateForm && !isApproved" class="btn" @click="handleExportDetail">导出</button>
        <button v-if="!showCreateForm && !isApproved" class="btn" @click="importRealDialogVisible = true">导入实盘</button>
        <button v-if="!showCreateForm && !isApproved" class="btn primary" @click="handleAudit" :disabled="loading">审核</button>
        <button v-if="!showCreateForm && isApproved && !isView" class="btn" style="color:#fa8c16" @click="handleReverseAudit" :disabled="loading">反审核</button>
      </div>

      <!-- 内容 -->
      <div class="apply-body">
        <!-- 基本信息卡片 -->
        <div class="card" style="padding:10px 14px">
          <div v-if="formErrors.header" style="color:#f56c6c;font-size:12px;margin-bottom:6px">{{ formErrors.header }}</div>
          <div class="grid4">
            <div class="field">
              <label>盘点仓库 <span class="req">*</span></label>
              <select v-model="header.warehouse" :disabled="!showCreateForm">
                <option value="">请选择</option>
                <option v-for="w in warehouseList" :key="w.warehouseCode || w.code" :value="w.warehouseName || w.name">
                  {{ w.warehouseName || w.name }}
                </option>
              </select>
            </div>
            <div class="field">
              <label>盘点日期</label>
              <input type="date" v-model="header.countDate" :disabled="!showCreateForm" />
            </div>
            <div class="field">
              <label>盘点类型</label>
              <div style="display:flex;gap:10px;align-items:center;height:28px">
                <label class="radio-sm"><input type="radio" value="1" v-model="header.countType" :disabled="!showCreateForm" /> 全盘</label>
                <label class="radio-sm"><input type="radio" value="2" v-model="header.countType" :disabled="!showCreateForm" /> 抽盘</label>
              </div>
            </div>
            <div class="field field-full">
              <label>备注</label>
              <input v-model="header.remark" :disabled="!showCreateForm" placeholder="选填" style="width:100%" />
            </div>
          </div>
          <div v-if="formErrors.wh" style="color:#f56c6c;font-size:12px;margin-top:4px">{{ formErrors.wh }}</div>
          <div v-if="formErrors.goods" style="color:#f56c6c;font-size:12px;margin-top:4px">{{ formErrors.goods }}</div>

          <!-- 全盘提示 -->
          <div v-if="showCreateForm && header.countType === '1'" style="color:#909399;font-size:12px;margin-top:6px">
            全盘：自动拉取该仓库所有有库存商品的批次明细
          </div>

          <!-- 抽盘商品选择 -->
          <div v-if="showCreateForm && header.countType === '2'" style="margin-top:10px">
            <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
              <span style="font-weight:600;font-size:13px">已选商品</span>
              <span style="color:#909399;font-size:12px">{{ stagedGoods.length }} 种</span>
              <div style="flex:1"></div>
              <button class="btn" style="height:24px;font-size:11px;padding:0 8px" @click="importDialogVisible = true">批量导入</button>
              <button v-if="stagedGoods.length" class="btn" style="height:24px;font-size:11px;padding:0 8px;color:#f56c6c" @click="clearStagedGoods">清空</button>
            </div>
            <div style="margin-bottom:8px">
              <InlineGoodsPicker
                v-model="stagedGoodsSearch"
                :goods-list="allGoods"
                :existing-codes="stagedGoods.map(s => s.goodsCode)"
                @select="onGoodsSelect"
              />
            </div>
            <div v-if="stagedGoods.length" style="display:flex;flex-wrap:wrap;gap:6px;padding:6px 8px;background:#f9f9f9;border-radius:6px;max-height:100px;overflow-y:auto">
              <span v-for="s in stagedGoods" :key="s.goodsCode" style="display:inline-flex;align-items:center;gap:4px;padding:2px 8px;background:#e6f7ff;color:#1890ff;border-radius:4px;font-size:12px">
                {{ s.goodsName || s.goodsCode }}
                <button style="border:none;background:none;cursor:pointer;color:#ff4d4f;padding:0;font-size:14px;line-height:1" @click="removeStagedGoods(s.goodsCode)">&times;</button>
              </span>
            </div>
          </div>
        </div>

        <!-- 明细区（创建后显示） -->
        <div v-if="!showCreateForm" class="card detail-card">
          <!-- 汇总卡片 -->
          <div style="background:#fafbfc;border:1px solid #e5e7eb;border-radius:6px;padding:10px 14px;margin-bottom:8px">
            <div style="display:grid;grid-template-columns:repeat(4,1fr);gap:8px;font-size:12px">
              <div>
                <div style="color:#909399">账面总数量</div>
                <div style="font-size:16px;font-weight:700">{{ summary.bookQty }}</div>
                <div style="color:#909399;margin-top:2px">账面总金额</div>
                <div style="font-size:14px;font-weight:600">¥{{ summary.bookAmount }}</div>
              </div>
              <div>
                <div style="color:#909399">实盘总数量</div>
                <div style="font-size:16px;font-weight:700" :style="{color: Number(summary.diffQty) !== 0 ? (Number(summary.diffQty) > 0 ? '#52c41a' : '#f5222d') : ''}">{{ summary.realQty }}</div>
                <div style="color:#909399;margin-top:2px">实盘总金额</div>
                <div style="font-size:14px;font-weight:600" :style="{color: Number(summary.diffAmount) !== 0 ? (Number(summary.diffAmount) > 0 ? '#52c41a' : '#f5222d') : ''}">¥{{ summary.realAmount }}</div>
              </div>
              <div>
                <div style="color:#909399">净差异数量</div>
                <div style="font-size:16px;font-weight:700" :style="{color: Number(summary.diffQty) > 0 ? '#52c41a' : Number(summary.diffQty) < 0 ? '#f5222d' : ''}">
                  {{ Number(summary.diffQty) >= 0 ? '+' : '' }}{{ summary.diffQty }}
                </div>
                <div style="color:#909399;margin-top:2px">净差异金额</div>
                <div style="font-size:14px;font-weight:600" :style="{color: Number(summary.diffAmount) > 0 ? '#52c41a' : Number(summary.diffAmount) < 0 ? '#f5222d' : ''}">
                  {{ Number(summary.diffAmount) >= 0 ? '+¥' : '-¥' }}{{ Math.abs(Number(summary.diffAmount)).toFixed(2) }}
                </div>
              </div>
              <div>
                <div style="color:#52c41a">盘盈 {{ summary.surplusCount }} 行</div>
                <div style="font-size:14px;font-weight:600;color:#52c41a">{{ summary.surplusQty }}</div>
                <div style="color:#f5222d;margin-top:2px">盘亏 {{ summary.shortageCount }} 行</div>
                <div style="font-size:14px;font-weight:600;color:#f5222d">{{ summary.shortageQty }}</div>
              </div>
            </div>
          </div>

          <!-- 明细表头 -->
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
            <b style="font-size:13px">盘点明细</b>
            <span style="color:#909399;font-size:12px">共 {{ detailList.length }} 条</span>
            <div style="flex:1"></div>
            <label style="font-size:12px;color:#606266;display:flex;align-items:center;gap:4px;cursor:pointer">
              <input type="checkbox" v-model="onlyDiff" /> 仅看差异
            </label>
            <button v-if="!isView" class="btn" style="height:24px;font-size:11px;padding:0 8px" @click="addNewBatchRow">+ 新批次商品</button>
          </div>

          <!-- 明细表 -->
          <div class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:32px">#</th>
                  <th style="min-width:120px">商品编号</th>
                  <th style="min-width:140px">商品名称</th>
                  <th style="width:70px">规格</th>
                  <th style="width:80px">批次</th>
                  <th style="width:90px">生产日期</th>
                  <th style="width:90px">账面数量</th>
                  <th style="width:100px">实盘数量</th>
                  <th style="width:80px">差异</th>
                  <th style="width:80px">成本单价</th>
                  <th style="width:90px">账面金额</th>
                  <th style="width:90px">实盘金额</th>
                  <th style="width:90px">差异金额</th>
                  <th style="min-width:100px">差异备注</th>
                  <th v-if="!isView && detailList.some(d => d.isNewBatch)" style="width:46px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="displayedDetails.length === 0">
                  <td :colspan="detailList.some(d => d.isNewBatch) ? 15 : 14" style="text-align:center;color:#909399;padding:20px">
                    {{ onlyDiff ? '无差异行' : '暂无明细' }}
                  </td>
                </tr>
                <tr v-for="(row, i) in displayedDetails" :key="i"
                  :style="{
                    background: Number(row.diffQty) > 0 ? '#f6ffed' : Number(row.diffQty) < 0 ? '#fff2f0' : ''
                  }">
                  <td>{{ i + 1 }}</td>
                  <td>
                    <template v-if="row.goodsCode">{{ row.goodsCode }}</template>
                    <InlineGoodsPicker v-else-if="!isView && row.isNewBatch" v-model="row.goodsSearch" :goods-list="allGoods"
                      :existing-codes="detailList.filter((_, x) => x !== i).map(r => r.goodsCode).filter(Boolean)"
                      @select="g => onNewBatchGoodsSelect(row, g)" />
                  </td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.spec || '-' }}</td>
                  <td>
                    <template v-if="row.isNewBatch && !isView">
                      <input v-model="row.batchNo" @input="onBatchNoInput(row)" placeholder="输入批次号" style="width:100%;height:24px;font-size:11px" />
                    </template>
                    <template v-else>{{ row.batchNo || '-' }}</template>
                  </td>
                  <td>
                    <template v-if="row.isNewBatch && !isView">
                      <input type="date" v-model="row.productionDate" @change="onProdDateChange(row)" style="width:100%;height:24px;font-size:11px" />
                    </template>
                    <template v-else>{{ row.productionDate ? String(row.productionDate).slice(0, 10) : '-' }}</template>
                  </td>
                  <td style="text-align:right;font-weight:500">{{ Number(row.bookQty || 0).toFixed(4) }}</td>
                  <td>
                    <template v-if="isView">{{ Number(row.realQty || 0).toFixed(4) }}</template>
                    <input v-else type="number" min="0" step="0.0001" :value="Number(row.realQty || 0)"
                      @input="row.realQty = Number($event.target.value) || 0; onRealQtyInput(row)"
                      style="width:100%;height:24px;text-align:right;font-size:12px" />
                  </td>
                  <td style="text-align:right;font-weight:600"
                    :style="{ color: Number(row.diffQty) > 0 ? '#52c41a' : Number(row.diffQty) < 0 ? '#f5222d' : '#909399' }">
                    {{ Number(row.diffQty) !== 0 ? (Number(row.diffQty) > 0 ? '+' : '') + Number(row.diffQty).toFixed(4) : '—' }}
                  </td>
                  <td style="text-align:right;font-family:monospace">{{ Number(row.costPrice || 0).toFixed(6) }}</td>
                  <td style="text-align:right">{{ Number(row.bookAmount || 0).toFixed(2) }}</td>
                  <td style="text-align:right">{{ Number(row.realAmount || 0).toFixed(2) }}</td>
                  <td style="text-align:right;font-weight:600"
                    :style="{ color: Number(row.diffAmount) > 0 ? '#52c41a' : Number(row.diffAmount) < 0 ? '#f5222d' : '' }">
                    {{ Number(row.diffAmount) !== 0 ? (Number(row.diffAmount) > 0 ? '+' : '') + Number(row.diffAmount).toFixed(2) : '—' }}
                  </td>
                  <td>
                    <input v-if="!isView" v-model="row.diffRemark" placeholder="差异原因" style="width:100%;height:24px;font-size:11px"
                      :style="{ borderColor: Number(row.diffQty) !== 0 && !row.diffRemark ? '#faad14' : '#dcdfe6' }" />
                    <template v-else>{{ row.diffRemark || '-' }}</template>
                  </td>
                  <td v-if="!isView && row.isNewBatch">
                    <button class="link link-btn danger-link" @click="removeBatchRow(i)">删除</button>
                  </td>
                  <td v-else-if="!isView && detailList.some(d => d.isNewBatch)"></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- Excel 导入商品弹窗（抽盘创建阶段） -->
  <div v-if="importDialogVisible" class="dialog-mask" @click.self="importDialogVisible = false">
    <div class="dialog-box" style="width:460px">
      <div class="dialog-head">
        <b>导入商品 Excel</b>
        <button class="btn" style="border:none;font-size:16px;cursor:pointer" @click="importDialogVisible = false">&times;</button>
      </div>
      <div class="dialog-body">
        <div style="color:#606266;font-size:12px;margin-bottom:12px">
          模板第一列为"商品编号"。系统将自动查找商品并带入批次和成本价，已有商品不会重复添加。
        </div>
        <div style="margin-bottom:12px">
          <button class="btn" style="height:28px;font-size:12px" @click="downloadExcelTemplate">下载模板</button>
        </div>
        <div style="border:1px dashed #dcdfe6;border-radius:6px;padding:20px;text-align:center">
          <input ref="importFileRef" type="file" accept=".xlsx,.xls,.csv" style="display:block;margin:0 auto;font-size:12px" />
        </div>
      </div>
      <div class="dialog-foot">
        <button class="btn" @click="importDialogVisible = false">取消</button>
        <button class="btn primary" @click="handleImport" :disabled="importing">{{ importing ? '导入中...' : '开始导入' }}</button>
      </div>
    </div>
  </div>

  <!-- 导入实盘数量弹窗 -->
  <div v-if="importRealDialogVisible" class="dialog-mask" @click.self="importRealDialogVisible = false">
    <div class="dialog-box" style="width:460px">
      <div class="dialog-head">
        <b>导入实盘数量</b>
        <button class="btn" style="border:none;font-size:16px;cursor:pointer" @click="importRealDialogVisible = false">&times;</button>
      </div>
      <div class="dialog-body">
        <div style="color:#606266;font-size:12px;margin-bottom:12px">
          请先「导出」明细获取 Excel，填写实盘数量列后上传。系统将根据行号匹配更新实盘数量。
        </div>
        <div style="border:1px dashed #dcdfe6;border-radius:6px;padding:20px;text-align:center">
          <input ref="importRealFileRef" type="file" accept=".xlsx,.xls" style="display:block;margin:0 auto;font-size:12px" />
        </div>
      </div>
      <div class="dialog-foot">
        <button class="btn" @click="importRealDialogVisible = false">取消</button>
        <button class="btn primary" @click="handleImportReal" :disabled="importingReal">{{ importingReal ? '导入中...' : '开始导入' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.apply-mask { position: fixed; top: 48px; right: 0; bottom: 0; left: 299px; z-index: 900; display: flex; pointer-events: none; }
.apply-box { flex: 1; background: #fff; display: flex; flex-direction: column; min-width: 0; border-left: 1px solid #e5e7eb; box-shadow: -6px 0 24px rgba(15,46,88,.12); pointer-events: auto; }
.apply-head { display: flex; align-items: center; gap: 10px; height: 46px; padding: 0 16px; border-bottom: 1px solid #e5e7eb; flex-shrink: 0; }
.apply-head b { font-size: 15px; }
.apply-body { flex: 1; overflow: auto; padding: 12px 16px; display: flex; flex-direction: column; gap: 12px; background: #f5f7fa; }
.card { background: #fff; border: 1px solid #e5e7eb; border-radius: 6px; }
.grid4 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; }
.grid4 .field { display: grid; grid-template-columns: 78px 1fr; align-items: center; gap: 4px; }
.grid4 .field label { font-size: 12px; color: #606266; text-align: right; font-weight: 600; white-space: nowrap; }
.grid4 .field input, .grid4 .field select { height: 28px; padding: 0 6px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; }
.field-full { grid-column: 1 / -1; }
.req { color: #f56c6c; }
.detail-card { flex: 1; display: flex; flex-direction: column; min-height: 300px; padding: 10px 14px; }
.detail-scroll { flex: 1; overflow: auto; min-height: 0; }
.detail-scroll table { width: 100%; border-collapse: collapse; font-size: 12px; }
.detail-scroll th { background: #f5f7fa; padding: 6px 6px; text-align: left; font-weight: 600; border-bottom: 1px solid #e5e7eb; position: sticky; top: 0; z-index: 1; white-space: nowrap; }
.detail-scroll td { padding: 3px 6px; border-bottom: 1px solid #f0f0f0; white-space: nowrap; }
.detail-scroll td input[type="number"]::-webkit-inner-spin-button { opacity: 1; }
.badge { font-size: 11px; padding: 1px 8px; border-radius: 10px; }
.badge.ok { background: #f0f9eb; color: #67c23a; }
.badge.wait { background: #fdf6ec; color: #e6a23c; }
.radio-sm { font-weight:normal;display:flex;align-items:center;gap:3px;cursor:pointer;font-size:12px }
.radio-sm input[type="radio"] { width:13px;height:13px;margin:0;accent-color:#1677ff }
@media (max-width: 900px) { .apply-mask { left: 0; } }
.dialog-mask { position: fixed; inset: 0; z-index: 2000; background: rgba(0,0,0,.35); display: flex; align-items: center; justify-content: center; }
.dialog-box { background: #fff; border-radius: 8px; box-shadow: 0 8px 32px rgba(0,0,0,.16); display: flex; flex-direction: column; max-height: 80vh; }
.dialog-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border-bottom: 1px solid #e5e7eb; }
.dialog-head b { font-size: 14px; }
.dialog-body { padding: 16px; flex: 1; overflow: auto; }
.dialog-foot { display: flex; justify-content: flex-end; gap: 8px; padding: 10px 16px; border-top: 1px solid #e5e7eb; }
</style>
