<script setup>
/**
 * 报损单抽屉 —— 新建 / 编辑 / 查看报损单。
 *
 * 流程：新建（保存即为「未审核」，无草稿态）→ 编辑 → 审核（扣库存）→ 反审核（回库）。
 * 支持作废和删除（仅未审核）。
 * 明细通过【添加商品】从商品档案选取（仅展示有库存的可用商品），批次可选。
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 编辑/查看已有单据时传入 { damageId, damageNo } */
  editData: { type: Object, default: null },
  /** 外部强制只读（列表点「查看」时传 true） */
  readonly: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'save'])

const headerForm = ref(emptyHeader())
const detailList = ref([])
const errors = ref({})
const loading = ref(false)
const damageStatus = ref('')

const warehouseOptions = ref([])

// ============ 商品选择弹窗 ============
const showGoodsPicker = ref(false)
const goodsPickerList = ref([])
const goodsPickerLoading = ref(false)
const goodsPickerKeyword = ref('')
const goodsPickerError = ref('')
const goodsPickerChecked = ref(new Set())
const goodsPickerConfirmedCount = ref(0)

const goodsPickerAllChecked = computed(() => {
  const selectable = goodsPickerList.value.filter(r => !existingBatchKeys.value.has(`${r.goodsCode}|${r.batchNo || ''}`))
  return selectable.length > 0 && selectable.every(r => goodsPickerChecked.value.has(`${r.goodsCode}|${r.batchNo || ''}`))
})

// 批次下拉
const batchCache = ref({})
const batchEditingIndex = ref(-1)

const isEdit = computed(() => !!props.editData?.damageId)

const canEdit = computed(() => {
  if (props.readonly) return false
  if (!isEdit.value) return true
  return damageStatus.value === 'DRAFT' || damageStatus.value === 'PENDING'
})

const readonlyReason = computed(() => {
  if (canEdit.value) return ''
  switch (damageStatus.value) {
    case 'APPROVED': return '该报损单已审核，只可查看。如需修改请先反审核。'
    case 'CANCELLED': return '该报损单已作废，只可查看。'
    default: return '该报损单当前状态不可编辑，只可查看。'
  }
})

const statusText = computed(() => ({
  DRAFT: '草稿',
  PENDING: '待审核',
  APPROVED: '已审核',
  CANCELLED: '已作废',
}[damageStatus.value] || damageStatus.value))

function emptyHeader() {
  return {
    warehouse: '',
    billDate: new Date().toISOString().slice(0, 10),
    remark: '',
  }
}

const totalQty = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.qty || 0), 0)
)
const totalAmount = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.amount || 0), 0).toFixed(2)
)
const totalCostAmount = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.costAmount || 0), 0).toFixed(2)
)

watch(() => props.visible, async (val) => {
  if (!val) return
  errors.value = {}
  detailList.value = []
  batchCache.value = {}
  batchEditingIndex.value = -1
  damageStatus.value = ''
  headerForm.value = emptyHeader()
  await loadBaseOptions()
  if (props.editData?.damageId) {
    await loadExisting(props.editData.damageId)
  }
})

async function loadBaseOptions() {
  try {
    const wh = await post('/base/warehouse/page', { pageNo: 1, pageSize: 200, filters: {} }).catch(() => ({ records: [] }))
    warehouseOptions.value = (wh.records || []).map(r => r.warehouseName).filter(Boolean)
    if (!headerForm.value.warehouse && warehouseOptions.value.length === 1) {
      headerForm.value.warehouse = warehouseOptions.value[0]
    }
  } catch {
    warehouseOptions.value = []
  }
}

async function loadExisting(damageId) {
  loading.value = true
  try {
    const data = await get(`/inventory/damage/detail?id=${encodeURIComponent(damageId)}`)
    damageStatus.value = data.status || ''
    headerForm.value = {
      warehouse: data.warehouse || '',
      billDate: String(data.billDate || '').slice(0, 10) || new Date().toISOString().slice(0, 10),
      remark: data.remark || '',
    }
    detailList.value = (data.details || []).map(d => ({
      detailId: d.detailId,
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      spec: d.spec || '',
      unitName: d.unitName || '',
      qty: Number(d.qty || 0),
      price: Number(d.price || 0),
      amount: Number(d.amount || 0),
      batchNo: d.batchNo || '',
      productionDate: String(d.productionDate || '').slice(0, 10),
      costPrice: Number(d.costPrice || 0),
      costAmount: Number(d.costAmount || 0),
      availableStock: 0,
    }))
  } catch (e) {
    errors.value.header = e.message || '加载报损单失败'
  } finally {
    loading.value = false
  }
}

// ============ 仓库切换 ============
function onWarehouseChange() {
  batchCache.value = {}
  detailList.value.forEach(r => {
    r.batchNo = ''
    r.productionDate = ''
    r.availableStock = 0
    r.costPrice = 0
    r.costAmount = 0
  })
}

// ============ 添加商品弹窗 ============

/** 已在明细中的「商品+批次」键集合，用于禁止重复添加同一批次 */
const existingBatchKeys = computed(() =>
  new Set(detailList.value.map(r => `${r.goodsCode}|${r.batchNo || ''}`))
)

/** 批次库存行唯一键 */
function stockRowKey(r) {
  return `${r.goodsCode}|${r.batchNo || ''}`
}

async function openGoodsPicker() {
  if (!canEdit.value) { errors.value.header = readonlyReason.value; return }
  if (!headerForm.value.warehouse) {
    errors.value.header = '请先选择仓库'
    return
  }
  errors.value = {}
  goodsPickerKeyword.value = ''
  goodsPickerError.value = ''
  goodsPickerChecked.value = new Set()
  goodsPickerConfirmedCount.value = 0
  showGoodsPicker.value = true
  await loadGoodsPickerList()
}

/** 查询报损仓库下有可用库存的批次库存记录（一行 = 一个批次） */
async function loadGoodsPickerList() {
  goodsPickerLoading.value = true
  goodsPickerError.value = ''
  try {
    const params = new URLSearchParams({ warehouse: headerForm.value.warehouse })
    if (goodsPickerKeyword.value.trim()) params.set('keyword', goodsPickerKeyword.value.trim())
    const rows = await get(`/inventory/damage/batch-stock-options?${params.toString()}`)
    goodsPickerList.value = (Array.isArray(rows) ? rows : [])
      .filter(r => Number(r.availableQty || 0) > 0)
      .map(r => ({
        goodsCode: r.goodsCode,
        goodsName: r.goodsName,
        spec: r.spec || '',
        baseUnit: r.baseUnit || '',
        barcode: r.barcode || '',
        batchNo: r.batchNo || '',
        productionDate: (r.productionDate || '').slice(0, 10),
        availableQty: Number(r.availableQty || 0),
        shelfLifeDays: Number(r.shelfLifeDays || 0),
        expiryDate: (r.expiryDate || '').slice(0, 10),
        remainingDays: r.remainingDays === null || r.remainingDays === undefined ? null : Number(r.remainingDays),
        brandName: r.brandName || '',
        categoryName: r.categoryName || '',
        costPrice: Number(r.costPrice || 0),
      }))
  } catch (e) {
    goodsPickerError.value = e.message || '加载批次库存失败'
    goodsPickerList.value = []
  } finally {
    goodsPickerLoading.value = false
  }
}

function toggleGoodsRow(key, checked) {
  const next = new Set(goodsPickerChecked.value)
  if (checked) next.add(key)
  else next.delete(key)
  goodsPickerChecked.value = next
}

function toggleGoodsAll(checked) {
  const next = new Set(goodsPickerChecked.value)
  goodsPickerList.value.forEach(r => {
    const key = stockRowKey(r)
    if (existingBatchKeys.value.has(key)) return
    if (checked) next.add(key)
    else next.delete(key)
  })
  goodsPickerChecked.value = next
}

/** 【勾选添加】：添加选中批次到明细，不关闭窗口 */
function confirmGoodsSelection() {
  const selected = goodsPickerList.value.filter(r => goodsPickerChecked.value.has(stockRowKey(r)))
  if (selected.length === 0) {
    goodsPickerError.value = '请先勾选要报损的批次'
    return
  }

  let added = 0
  let skipped = 0
  for (const r of selected) {
    // 去重：同「商品 + 批次」已在明细中则跳过
    if (existingBatchKeys.value.has(stockRowKey(r))) {
      skipped++
      continue
    }
    detailList.value.push({
      goodsCode: r.goodsCode,
      goodsName: r.goodsName,
      spec: r.spec || '',
      unitName: r.baseUnit || '',
      qty: 0,
      price: Number(r.costPrice || 0),
      amount: 0,
      batchNo: r.batchNo || '',
      productionDate: r.productionDate || '',
      costPrice: Number(r.costPrice || 0),
      costAmount: 0,
      availableStock: Number(r.availableQty || 0),
    })
    added++
  }

  goodsPickerConfirmedCount.value += added
  goodsPickerChecked.value = new Set()

  if (skipped > 0) {
    goodsPickerError.value = `已添加 ${added} 条；${skipped} 条因该批次已在明细中而跳过`
  } else {
    goodsPickerError.value = ''
  }
}

/** 【确定】：添加并关闭 */
function confirmGoodsAndClose() {
  confirmGoodsSelection()
  showGoodsPicker.value = false
}

function closeGoodsPicker() {
  showGoodsPicker.value = false
}

// ============ 明细行操作 ============

function removeRow(index) {
  detailList.value.splice(index, 1)
  if (batchEditingIndex.value === index) batchEditingIndex.value = -1
}

// ============ 批次选择 ============
function batchKey(goodsCode) {
  return `${goodsCode}|${headerForm.value.warehouse}`
}

async function openBatchPicker(index) {
  if (!canEdit.value) return
  const row = detailList.value[index]
  if (!row) return
  if (!headerForm.value.warehouse) {
    errors.value.details = '请先选择仓库，再选择批次'
    return
  }
  batchEditingIndex.value = batchEditingIndex.value === index ? -1 : index
  if (batchEditingIndex.value !== index) return

  const key = batchKey(row.goodsCode)
  if (batchCache.value[key]) return
  try {
    const rows = await get(
      `/inventory/damage/batch-options?goodsCode=${encodeURIComponent(row.goodsCode)}&warehouse=${encodeURIComponent(headerForm.value.warehouse)}`
    )
    batchCache.value = {
      ...batchCache.value,
      [key]: (Array.isArray(rows) ? rows : []).map(b => ({
        batchNo: b.batchNo || '',
        productionDate: String(b.productionDate || '').slice(0, 10),
        availableQty: Number(b.availableQty || 0),
        costPrice: Number(b.costPrice || 0),
      })),
    }
  } catch { /* 静默 */ }
}

function isBatchTaken(batch, index) {
  if (!batch.batchNo) return false
  return detailList.value.some((r, i) =>
    i !== index && r.goodsCode === detailList.value[index].goodsCode && r.batchNo === batch.batchNo
  )
}

function pickBatch(index, batch) {
  if (isBatchTaken(batch, index)) {
    errors.value.details = '该批次已被其他行使用'
    return
  }
  const row = detailList.value[index]
  row.batchNo = batch.batchNo
  row.productionDate = batch.productionDate
  row.costPrice = Number(batch.costPrice || 0)
  row.availableStock = Number(batch.availableQty || 0)
  recalcAmount(index)
  batchEditingIndex.value = -1
}

// ============ 数量变更 ============
function onQtyChange(index) {
  const row = detailList.value[index]
  const q = Number(row.qty || 0)
  if (row.availableStock > 0 && q > row.availableStock) {
    errors.value.details = `商品 ${row.goodsName} 报损数量 ${q} 超过可用库存 ${row.availableStock}`
  } else {
    errors.value.details = ''
  }
  recalcAmount(index)
}

function recalcAmount(index) {
  const row = detailList.value[index]
  const q = Number(row.qty || 0)
  const p = Number(row.price || 0)
  const cp = Number(row.costPrice || 0)
  row.amount = parseFloat((q * p).toFixed(2))
  row.costAmount = parseFloat((q * cp).toFixed(2))
}

function onPriceChange(index) {
  recalcAmount(index)
}

// ============ 保存 ============
function validate() {
  errors.value = {}
  if (!headerForm.value.warehouse) { errors.value.header = '请选择仓库'; return false }
  if (detailList.value.length === 0) { errors.value.details = '请添加报损商品'; return false }
  for (let i = 0; i < detailList.value.length; i++) {
    const r = detailList.value[i]
    if (!r.goodsCode) { errors.value.details = `第${i + 1}行：请选择商品`; return false }
    if (!r.qty || Number(r.qty) <= 0) { errors.value.details = `商品 ${r.goodsName} 的报损数量必须大于 0`; return false }
  }
  const seen = new Map()
  for (const r of detailList.value) {
    const key = `${r.goodsCode}|${r.batchNo || ''}`
    if (seen.has(key)) {
      errors.value.details = `商品 ${r.goodsName}（批次 ${r.batchNo || '无'}）重复`
      return false
    }
    seen.set(key, true)
  }
  return true
}

async function saveDamage() {
  if (!validate()) return
  loading.value = true
  try {
    const payload = {
      warehouse: headerForm.value.warehouse,
      billDate: headerForm.value.billDate,
      remark: headerForm.value.remark,
      details: detailList.value.map(r => ({
        goodsCode: r.goodsCode,
        goodsName: r.goodsName,
        spec: r.spec,
        unitName: r.unitName,
        qty: Number(r.qty || 0),
        price: Number(r.price || 0),
        amount: Number(r.amount || 0),
        batchNo: r.batchNo,
        productionDate: r.productionDate || null,
        costPrice: Number(r.costPrice || 0),
        costAmount: Number(r.costAmount || 0),
        availableStock: Number(r.availableStock || 0),
      })),
    }
    if (isEdit.value) {
      payload.damageId = props.editData.damageId
      await post('/inventory/damage/update', payload)
    } else {
      await post('/inventory/damage/create', payload)
    }
    emit('save', { status: 'PENDING' })
  } catch (e) {
    errors.value.header = e.message || '保存失败'
  } finally {
    loading.value = false
  }
}

async function handleAudit() {
  if (!confirm('确认审核该报损单？审核后将扣减库存并记入成本。')) return
  loading.value = true
  try {
    const res = await post('/inventory/damage/audit', { bizId: props.editData.damageId })
    damageStatus.value = 'APPROVED'
    errors.value = {}
    alert(res?.effect || '审核成功')
    emit('save', { status: 'APPROVED' })
  } catch (e) {
    errors.value.header = e.message || '审核失败'
  } finally {
    loading.value = false
  }
}

async function handleReverseAudit() {
  if (!confirm('确认反审核该报损单？将恢复库存。')) return
  loading.value = true
  try {
    const res = await post('/inventory/damage/reverse-audit', { bizId: props.editData.damageId })
    damageStatus.value = 'PENDING'
    errors.value = {}
    alert(res?.effect || '反审核成功')
    emit('save', { status: 'PENDING' })
  } catch (e) {
    errors.value.header = e.message || '反审核失败'
  } finally {
    loading.value = false
  }
}

async function handleCancel() {
  if (!confirm('确认作废该报损单？此操作不可逆，已审核的将自动回滚库存。')) return
  loading.value = true
  try {
    const res = await post('/inventory/damage/cancel', { bizId: props.editData.damageId })
    damageStatus.value = 'CANCELLED'
    errors.value = {}
    alert(res?.effect || '已作废')
    emit('save', { status: 'CANCELLED' })
  } catch (e) {
    errors.value.header = e.message || '作废失败'
  } finally {
    loading.value = false
  }
}

function handleClose() {
  emit('close')
}
</script>

<template>
  <div v-if="visible" class="damage-drawer-mask">
    <div class="damage-drawer-box">
      <!-- 头部 -->
      <div class="damage-drawer-head">
        <b>{{ isEdit ? '编辑报损单' : '新建报损单' }}</b>
        <span v-if="isEdit" class="status-tag" :class="'st-' + damageStatus.toLowerCase()">{{ statusText }}</span>
        <span style="flex:1"></span>
        <button v-if="canEdit" class="btn primary" @click="saveDamage()" :disabled="loading">保存</button>
        <button v-if="isEdit && damageStatus === 'PENDING'" class="btn primary" @click="handleAudit" :disabled="loading">审核</button>
        <button v-if="isEdit && damageStatus === 'APPROVED'" class="btn" @click="handleReverseAudit" :disabled="loading">反审核</button>
        <button v-if="isEdit && damageStatus !== 'CANCELLED'" class="btn danger" @click="handleCancel" :disabled="loading">作废</button>
        <button class="btn" @click="handleClose">关闭</button>
      </div>

      <!-- 主体 -->
      <div class="damage-drawer-body">
        <div v-if="!canEdit && isEdit" class="readonly-banner">{{ readonlyReason }}</div>

        <!-- 头部信息 -->
        <div class="card">
          <div class="card-title">单据信息</div>
          <div v-if="errors.header" class="err-line">{{ errors.header }}</div>
          <div class="grid3">
            <div class="field">
              <label>仓库 <span class="req">*</span></label>
              <input v-if="canEdit" list="wh-list" v-model="headerForm.warehouse" @change="onWarehouseChange" placeholder="请选择仓库" />
              <span v-else>{{ headerForm.warehouse }}</span>
              <datalist id="wh-list">
                <option v-for="w in warehouseOptions" :key="w" :value="w" />
              </datalist>
            </div>
            <div class="field">
              <label>报损日期</label>
              <input v-if="canEdit" type="date" v-model="headerForm.billDate" />
              <span v-else>{{ headerForm.billDate }}</span>
            </div>
            <div class="field">
              <label>备注</label>
              <input v-if="canEdit" v-model="headerForm.remark" placeholder="备注" />
              <span v-else>{{ headerForm.remark }}</span>
            </div>
          </div>
        </div>

        <!-- 明细 -->
        <div class="card detail-card">
          <div class="detail-toolbar">
            <span class="card-title">报损明细</span>
            <button v-if="canEdit" class="btn primary sm" @click="openGoodsPicker">+ 添加商品</button>
          </div>
          <div v-if="errors.details" class="err-line">{{ errors.details }}</div>
          <div class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th style="width:110px">商品编号</th>
                  <th>商品名称</th>
                  <th style="width:80px">规格</th>
                  <th style="width:60px">单位</th>
                  <th style="width:80px">数量</th>
                  <th style="width:90px">单价</th>
                  <th style="width:90px">金额</th>
                  <th style="width:90px">批次号</th>
                  <th style="width:100px">生产日期</th>
                  <th style="width:80px">可用库存</th>
                  <th style="width:90px">成本单价</th>
                  <th style="width:90px">成本金额</th>
                  <th v-if="canEdit" style="width:50px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="detailList.length === 0">
                  <td colspan="14" class="empty-detail">暂无报损商品，请点击"添加商品"</td>
                </tr>
                <tr v-for="(row, idx) in detailList" :key="idx">
                  <td>{{ idx + 1 }}</td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.spec }}</td>
                  <td>{{ row.unitName }}</td>
                  <td>
                    <input v-if="canEdit" type="number" min="0" step="any" v-model.number="row.qty"
                      @input="onQtyChange(idx)" style="width:70px" />
                    <span v-else>{{ row.qty }}</span>
                  </td>
                  <td>
                    <input v-if="canEdit" type="number" min="0" step="0.0001" v-model.number="row.price"
                      @input="onPriceChange(idx)" style="width:80px" />
                    <span v-else>{{ row.price }}</span>
                  </td>
                  <td class="num">{{ row.amount?.toFixed(2) }}</td>
                  <td style="position:relative">
                    <button v-if="canEdit" class="batch-btn" @click="openBatchPicker(idx)">
                      {{ row.batchNo || '选择批次' }}
                    </button>
                    <span v-else>{{ row.batchNo || '-' }}</span>
                    <div v-if="batchEditingIndex === idx && (batchCache[batchKey(row.goodsCode)] || []).length > 0"
                      class="batch-dropdown">
                      <div v-for="b in batchCache[batchKey(row.goodsCode)] || []" :key="b.batchNo"
                        class="batch-item" :class="{ taken: isBatchTaken(b, idx) }"
                        @click="pickBatch(idx, b)">
                        <div><b>{{ b.batchNo || '(空批次)' }}</b></div>
                        <div>生产日期: {{ b.productionDate || '-' }}</div>
                        <div>可用: {{ b.availableQty }} | 成本: {{ b.costPrice }}</div>
                      </div>
                    </div>
                  </td>
                  <td>{{ row.productionDate || '-' }}</td>
                  <td class="num">{{ row.availableStock }}</td>
                  <td class="num">{{ row.costPrice }}</td>
                  <td class="num">{{ row.costAmount?.toFixed(2) }}</td>
                  <td v-if="canEdit">
                    <button class="link danger-link" @click="removeRow(idx)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 汇总 -->
        <div class="summary-line">
          <span>合计数量：<b>{{ totalQty }}</b></span>
          <span>合计金额：<b>¥{{ totalAmount }}</b></span>
          <span>合计成本金额：<b>¥{{ totalCostAmount }}</b></span>
          <span>行数：<b>{{ detailList.length }}</b></span>
        </div>
      </div>
    </div>

    <!-- ============================================================ -->
    <!--  添加商品弹窗（参考采购退货申请添加商品窗口） -->
    <!-- ============================================================ -->
    <Teleport to="body">
      <div v-if="showGoodsPicker" class="gpk-mask" @click.self="closeGoodsPicker">
        <div class="gpk-box">
          <div class="gpk-head">
            <b>添加商品</b>
            <span class="gpk-wh-tag">仓库：{{ headerForm.warehouse }}</span>
            <div style="flex:1"></div>
            <button class="btn" @click="closeGoodsPicker">关闭</button>
            <button class="btn" @click="confirmGoodsSelection">
              勾选添加<span v-if="goodsPickerChecked.size">（{{ goodsPickerChecked.size }}）</span>
            </button>
            <button class="btn primary" @click="confirmGoodsAndClose">确定</button>
          </div>

          <!-- 搜索栏 -->
          <div class="gpk-search-bar">
            <input v-model="goodsPickerKeyword" placeholder="商品编号 / 名称 / 条码 / 批次号，回车查询"
              @keydown.enter="loadGoodsPickerList" />
            <button class="btn" @click="loadGoodsPickerList">查询</button>
            <span class="gpk-hint">仅展示当前仓库有可用库存的批次库存记录，同一批次不可重复添加</span>
          </div>

          <div v-if="goodsPickerError" class="gpk-err">{{ goodsPickerError }}</div>

          <!-- 表格：一行 = 一条批次库存记录 -->
          <div class="gpk-body">
            <div v-if="goodsPickerLoading" class="gpk-empty">加载中...</div>
            <div v-else-if="goodsPickerList.length === 0" class="gpk-empty">该仓库暂无有可用库存的批次记录</div>
            <table v-else>
              <thead>
                <tr>
                  <th style="width:36px">
                    <input type="checkbox" :checked="goodsPickerAllChecked" @change="toggleGoodsAll($event.target.checked)" />
                  </th>
                  <th style="min-width:110px">商品编号</th>
                  <th style="min-width:170px">商品名称</th>
                  <th style="min-width:100px">规格</th>
                  <th style="width:64px">单位</th>
                  <th style="min-width:120px">条码</th>
                  <th style="min-width:110px">批次号</th>
                  <th style="width:100px">生产日期</th>
                  <th style="width:90px">可用库存</th>
                  <th style="width:80px">保质期</th>
                  <th style="width:100px">到期日期</th>
                  <th style="width:80px">剩余天数</th>
                  <th style="min-width:90px">品牌</th>
                  <th style="min-width:100px">商品分类</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="r in goodsPickerList"
                  :key="r.goodsCode + '|' + r.batchNo"
                  :class="{
                    checked: goodsPickerChecked.has(r.goodsCode + '|' + r.batchNo),
                    'already-added': existingBatchKeys.has(r.goodsCode + '|' + r.batchNo),
                  }"
                >
                  <td>
                    <input
                      type="checkbox"
                      :checked="goodsPickerChecked.has(r.goodsCode + '|' + r.batchNo)"
                      :disabled="existingBatchKeys.has(r.goodsCode + '|' + r.batchNo)"
                      @change="toggleGoodsRow(r.goodsCode + '|' + r.batchNo, $event.target.checked)"
                    />
                  </td>
                  <td>{{ r.goodsCode }}</td>
                  <td>{{ r.goodsName }}</td>
                  <td>{{ r.spec || '-' }}</td>
                  <td>{{ r.baseUnit || '-' }}</td>
                  <td>{{ r.barcode || '-' }}</td>
                  <td>{{ r.batchNo || '-' }}</td>
                  <td>{{ r.productionDate || '-' }}</td>
                  <td class="num">{{ r.availableQty }}</td>
                  <td class="num">{{ r.shelfLifeDays > 0 ? r.shelfLifeDays + '天' : '-' }}</td>
                  <td>{{ r.expiryDate || '-' }}</td>
                  <td class="num" :class="{ 'days-warn': r.remainingDays !== null && r.remainingDays <= 30 }">
                    {{ r.remainingDays === null ? '-' : r.remainingDays }}
                  </td>
                  <td>{{ r.brandName || '-' }}</td>
                  <td>{{ r.categoryName || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="gpk-foot">
            <span>已勾选：<b>{{ goodsPickerChecked.size }}</b> 条</span>
            <span v-if="goodsPickerConfirmedCount > 0" class="gpk-ok">本次已添加：<b>{{ goodsPickerConfirmedCount }}</b> 条</span>
            <div style="flex:1"></div>
            <span class="gpk-tip">勾选批次后点击"勾选添加"可继续添加，"确定"添加后关闭窗口。已在明细中的批次不可重复添加。</span>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.damage-drawer-mask {
  position: fixed; top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900; display: flex; pointer-events: none;
}
.damage-drawer-box {
  flex: 1; display: flex; flex-direction: column;
  background: #fff; pointer-events: auto;
  box-shadow: -4px 0 20px rgba(0,0,0,.08);
}
.damage-drawer-head {
  height: 46px; display: flex; align-items: center; gap: 8px;
  padding: 0 16px; border-bottom: 1px solid #e8e8e8;
  background: #fafafa; flex-shrink: 0;
}
.damage-drawer-head b { font-size: 15px; }
.status-tag {
  font-size: 12px; padding: 1px 8px; border-radius: 3px;
}
.st-draft { background: #f0f0f0; color: #666; }
.st-pending { background: #fff7e6; color: #d46b08; }
.st-approved { background: #f6ffed; color: #389e0d; }
.st-cancelled { background: #fff2f0; color: #cf1322; }

.btn {
  padding: 4px 14px; border: 1px solid #d9d9d9; border-radius: 4px;
  background: #fff; cursor: pointer; font-size: 13px;
}
.btn:hover { border-color: #409eff; color: #409eff; }
.btn.primary { background: #409eff; color: #fff; border-color: #409eff; }
.btn.primary:hover { background: #3a8ee6; }
.btn.danger { color: #ff4d4f; border-color: #ff4d4f; }
.btn.danger:hover { background: #fff2f0; }
.btn.sm { padding: 2px 10px; font-size: 12px; }

.damage-drawer-body {
  flex: 1; overflow-y: auto; padding: 16px;
  background: #f5f7fa; display: flex; flex-direction: column; gap: 12px;
}
.readonly-banner {
  background: #fff7e6; border: 1px solid #ffd591; color: #d46b08;
  padding: 8px 12px; border-radius: 4px; font-size: 13px;
}
.card {
  background: #fff; border-radius: 6px; padding: 14px;
  border: 1px solid #e8e8e8;
}
.card-title { font-weight: 600; margin-bottom: 10px; font-size: 14px; }
.grid3 {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px;
}
.field { display: flex; flex-direction: column; gap: 4px; }
.field label { font-size: 12px; color: #888; }
.field .req { color: #ff4d4f; }
.field input, .field select {
  padding: 5px 8px; border: 1px solid #d9d9d9; border-radius: 4px; font-size: 13px;
}

.err-line { color: #ff4d4f; font-size: 12px; margin-bottom: 8px; }

.detail-card { flex: 1; min-height: 200px; display: flex; flex-direction: column; }
.detail-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 8px;
}
.detail-toolbar .card-title { margin-bottom: 0; }
.detail-scroll { flex: 1; overflow: auto; }
.detail-scroll table { width: 100%; border-collapse: collapse; font-size: 12px; }
.detail-scroll th {
  position: sticky; top: 0; background: #fafafa; padding: 6px 4px;
  border-bottom: 2px solid #e8e8e8; text-align: left; white-space: nowrap;
  z-index: 1;
}
.detail-scroll td { padding: 4px; border-bottom: 1px solid #f0f0f0; vertical-align: middle; }
.detail-scroll td input {
  padding: 3px 4px; border: 1px solid #d9d9d9; border-radius: 3px; font-size: 12px;
}
.num { text-align: right; }
.empty-detail {
  text-align: center; color: #bbb; padding: 40px 0;
}
.link { background: none; border: none; cursor: pointer; font-size: 12px; color: #409eff; padding: 0; }
.danger-link { color: #ff4d4f; }

.batch-btn {
  background: #f0f5ff; border: 1px solid #adc6ff; border-radius: 3px;
  padding: 2px 6px; font-size: 12px; cursor: pointer; color: #2f54eb;
  max-width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.batch-btn:hover { background: #e6f0ff; }
.batch-dropdown {
  position: absolute; top: 100%; left: 0; z-index: 10;
  background: #fff; border: 1px solid #d9d9d9; border-radius: 4px;
  width: 240px; max-height: 200px; overflow-y: auto;
  box-shadow: 0 4px 12px rgba(0,0,0,.1); padding: 4px 0;
}
.batch-item {
  padding: 6px 10px; cursor: pointer; font-size: 12px; border-bottom: 1px solid #f0f0f0;
}
.batch-item:hover { background: #f0f5ff; }
.batch-item.taken { opacity: 0.4; pointer-events: none; }
.batch-item div { line-height: 1.5; }

.summary-line {
  display: flex; gap: 24px; padding: 10px 16px;
  background: #fff; border-radius: 6px; border: 1px solid #e8e8e8;
  font-size: 13px; flex-shrink: 0;
}
.summary-line b { color: #409eff; }

/* ============================================================ */
/*  添加商品弹窗（参考采购退货申请添加商品窗口风格） */
/* ============================================================ */
.gpk-mask {
  position: fixed; inset: 0;
  background: rgba(15, 35, 60, 0.35);
  z-index: 1300;
  display: flex; justify-content: center; align-items: center;
}
.gpk-box {
  width: 1360px; max-width: 96vw;
  height: 620px; max-height: 90vh;
  background: #fff; border-radius: 10px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  display: flex; flex-direction: column;
}
.gpk-head {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}
.gpk-head b { font-size: 14px; }
.gpk-wh-tag {
  padding: 3px 10px;
  background: #ecf5ff;
  border: 1px solid #d9ecff;
  border-radius: 12px;
  color: #409eff;
  font-size: 12px; font-weight: 700;
  white-space: nowrap;
}

/* 搜索栏 */
.gpk-search-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}
.gpk-search-bar input {
  height: 30px; width: 260px; padding: 0 10px;
  border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px;
}
.gpk-hint {
  font-size: 12px; color: #909399; margin-left: 12px;
}

.gpk-err {
  margin: 8px 14px 0;
  padding: 6px 10px;
  background: #fef0f0; border: 1px solid #fde2e2; border-radius: 4px;
  color: #f56c6c; font-size: 12px;
  flex-shrink: 0;
}

/* 表格区 */
.gpk-body {
  flex: 1; min-height: 0; overflow: auto;
  margin: 10px 14px;
  border: 1px solid #e5e7eb; border-radius: 6px;
}
.gpk-body table { width: 100%; border-collapse: collapse; font-size: 12px; }
.gpk-body th {
  position: sticky; top: 0; z-index: 1;
  background: #f5f7fa; color: #303133; font-weight: 700;
  padding: 7px 8px; text-align: left; white-space: nowrap;
  border-bottom: 1px solid #e5e7eb;
}
.gpk-body td { padding: 6px 8px; border-bottom: 1px solid #f0f2f5; white-space: nowrap; }
.gpk-body td.num { text-align: right; font-variant-numeric: tabular-nums; }
.gpk-body td.days-warn { color: #e6a23c; font-weight: 700; }
.gpk-body tr.checked { background: #f0f9eb; }
.gpk-body tr.already-added { opacity: 0.45; }
.gpk-body tr.already-added td { color: #c0c4cc; }
.gpk-empty { padding: 50px; text-align: center; color: #909399; font-size: 12px; }

/* 底部状态栏 */
.gpk-foot {
  display: flex; align-items: center; gap: 14px;
  padding: 8px 14px;
  border-top: 1px solid #e5e7eb;
  font-size: 12px; color: #606266;
  flex-shrink: 0;
}
.gpk-foot .gpk-ok { color: #67c23a; }
.gpk-foot .gpk-tip { color: #909399; }

@media (max-width: 900px) {
  .damage-drawer-mask { left: 0; }
}
</style>
