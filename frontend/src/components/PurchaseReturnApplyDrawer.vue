<script setup>
/**
 * 采购退货申请抽屉 —— 双入口加商品。
 *
 * 明细来源两种，用 returnMode 区分可编辑性：
 *   · BY_BILL  按单退货：【按单添加商品】→ 采购入库单选择窗口带入
 *              只可改「退货数量」「单价」；数量硬约束 ≤ 可退数量
 *   · BY_GOODS 按品退货：【添加商品】→ 商品档案选择窗口带入
 *              数量 / 单位 / 单价 / 金额均可改；数量约束 ≤ 可用库存
 *
 * 两种退货方式的批次号都可改（选仓库内有库存的批次），
 * 选批次后带出生产日期与可用库存；同商品同单位允许多行选不同批次。
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'
import PurchaseInboundPickerDialog from './PurchaseInboundPickerDialog.vue'
import PurchaseReturnGoodsPickerDialog from './PurchaseReturnGoodsPickerDialog.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 编辑/查看已有单据时传入 { applyId, applyNo } */
  editData: { type: Object, default: null },
  /** 外部强制只读（列表点「查看」时传 true） */
  readonly: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'save'])

const headerForm = ref(emptyHeader())
const detailList = ref([])
const errors = ref({})
const loading = ref(false)
/** 单据状态（编辑已有单据时从后端带回） */
const applyStatus = ref('')

const supplierOptions = ref([])
const warehouseOptions = ref([])

// 两个选择窗口
const showInboundPicker = ref(false)
const showGoodsPicker = ref(false)

// 批次下拉缓存：key = `${goodsCode}|${warehouse}` → 批次数组
const batchCache = ref({})
/** 当前展开批次下拉的行索引；-1 表示无 */
const batchEditingIndex = ref(-1)

const isEdit = computed(() => !!props.editData?.applyId)

/**
 * 是否可编辑。
 * <p>已审核（及之后的已出库/已完成）单据只可查看 —— 此时申请数量已被下游
 * 出库单占用、可退额度已扣减，再改会导致出库单与申请单对不上。
 * 如需修改请先反审核。
 */
const canEdit = computed(() => {
  if (props.readonly) return false
  if (!isEdit.value) return true                       // 新建
  return applyStatus.value === 'DRAFT' || applyStatus.value === 'PENDING'
})

/** 只读原因（给用户明确提示，而不是按钮默默消失） */
const readonlyReason = computed(() => {
  if (canEdit.value) return ''
  switch (applyStatus.value) {
    case 'APPROVED': return '该申请已审核，只可查看。如需修改请先反审核。'
    case 'OUTBOUNDED': return '该申请已生成退货出库单并出库，只可查看。'
    case 'COMPLETED': return '该申请已完成，只可查看。'
    default: return '该申请当前状态不可编辑，只可查看。'
  }
})

/** 状态中文 */
const statusText = computed(() => ({
  DRAFT: '草稿',
  PENDING: '待审核',
  APPROVED: '已审核',
  OUTBOUNDED: '已出库',
  COMPLETED: '已完成',
}[applyStatus.value] || applyStatus.value))

function emptyHeader() {
  return {
    supplierCode: '',
    supplier: '',
    warehouse: '',
    billDate: new Date().toISOString().slice(0, 10),
    returnReason: '',
    remark: '',
  }
}

const totalQty = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.qty || 0), 0)
)
const totalAmount = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.qty || 0) * Number(r.price || 0), 0).toFixed(2)
)

watch(() => props.visible, async (val) => {
  if (!val) return
  errors.value = {}
  detailList.value = []
  batchCache.value = {}
  batchEditingIndex.value = -1
  applyStatus.value = ''
  headerForm.value = emptyHeader()
  loadBaseOptions()
  if (props.editData?.applyId) {
    await loadExistingApply(props.editData.applyId)
  }
})

/** 供应商 / 仓库下拉 */
async function loadBaseOptions() {
  try {
    const [sup, wh] = await Promise.all([
      post('/base/supplier/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => ({ records: [] })),
      post('/base/warehouse/page', { pageNo: 1, pageSize: 200, filters: {} }).catch(() => ({ records: [] })),
    ])
    supplierOptions.value = (sup.records || [])
      .map(r => ({ code: r.supplierCode, name: r.supplierName }))
      .filter(o => o.name)
    warehouseOptions.value = (wh.records || []).map(r => r.warehouseName).filter(Boolean)
    // 只有一个仓库时默认选中，省一步操作
    if (!headerForm.value.warehouse && warehouseOptions.value.length === 1) {
      headerForm.value.warehouse = warehouseOptions.value[0]
    }
  } catch (e) {
    supplierOptions.value = []
    warehouseOptions.value = []
  }
}

/** 编辑草稿：加载已有申请单 */
async function loadExistingApply(applyId) {
  loading.value = true
  try {
    const data = await get(`/purchase/return-apply/detail?id=${encodeURIComponent(applyId)}`)
    applyStatus.value = data.status || ''
    headerForm.value = {
      supplierCode: data.supplierCode || '',
      supplier: data.supplierName || '',
      warehouse: data.warehouse || '',
      billDate: String(data.billDate || '').slice(0, 10) || new Date().toISOString().slice(0, 10),
      returnReason: data.returnReason || '',
      remark: data.remark || '',
    }
    detailList.value = (data.details || []).map(d => ({
      returnMode: d.returnMode || 'BY_BILL',
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      spec: d.spec || '',
      unitName: d.unitName || '',
      unitConfig: '',
      qty: Number(d.qty || 0),
      price: Number(d.price || 0),
      batchNo: d.batchNo || '',
      productionDate: String(d.productionDate || '').slice(0, 10),
      taxRate: d.taxRate || '13%',
      sourceInboundNo: d.sourceInboundNo || '',
      sourceDetailId: d.sourceDetailId || '',
      returnableQty: Number(d.returnableQty || 0),
      costPrice: Number(d.costPrice || 0),
      availableStock: Number(d.availableStock || 0),
    }))
  } catch (e) {
    errors.value.header = e.message || '加载申请单失败'
  } finally {
    loading.value = false
  }
}

/** 供应商切换：已有明细时提示会清空（源单绑定的是特定供应商） */
function onSupplierChange(supplierName) {
  const found = supplierOptions.value.find(o => o.name === supplierName)
  headerForm.value.supplierCode = found?.code || ''
  if (detailList.value.length > 0) {
    if (confirm('切换供应商将清空已添加的退货明细，是否继续？')) {
      detailList.value = []
      batchCache.value = {}
    }
  }
}

/** 仓库切换：批次与可用库存都跟仓库绑定，需清缓存并提示 */
function onWarehouseChange() {
  batchCache.value = {}
  if (detailList.value.length > 0) {
    detailList.value.forEach(r => {
      r.batchNo = ''
      r.productionDate = ''
      r.availableStock = 0
    })
    errors.value.details = '仓库已切换，请重新选择各行批次'
  }
}

// ============ 打开选择窗口 ============
function openInboundPicker() {
  if (!canEdit.value) { errors.value.header = readonlyReason.value; return }
  if (!headerForm.value.supplier) {
    errors.value.header = '请先选择供应商，再按单添加商品'
    return
  }
  errors.value = {}
  showInboundPicker.value = true
}

function openGoodsPicker() {
  if (!canEdit.value) { errors.value.header = readonlyReason.value; return }
  if (!headerForm.value.supplier) {
    errors.value.header = '请先选择供应商，再添加商品'
    return
  }
  if (!headerForm.value.warehouse) {
    errors.value.header = '请先选择仓库，再添加商品（可用库存与批次按仓库计算）'
    return
  }
  errors.value = {}
  showGoodsPicker.value = true
}

/**
 * 按单添加回调：按 sourceDetailId 去重（同一源单行只能加一次）。
 * 若入库单所在仓库与主表仓库不一致，以入库单仓库为准回填主表（首次添加时）。
 */
function onInboundConfirm(rows) {
  const existing = new Set(detailList.value.map(r => r.sourceDetailId).filter(Boolean))
  let added = 0
  let skipped = 0
  rows.forEach(r => {
    if (existing.has(r.sourceDetailId)) { skipped++; return }
    // 首次添加且主表未选仓库 → 用源单仓库
    if (!headerForm.value.warehouse && r.warehouse) {
      headerForm.value.warehouse = r.warehouse
    }
    const { warehouse, ...line } = r
    detailList.value.push(line)
    existing.add(r.sourceDetailId)
    added++
  })
  errors.value = {}
  if (skipped > 0) {
    errors.value.details = `已添加 ${added} 条；${skipped} 条因该源单行已在明细中而跳过`
  }
}

/** 按品添加回调：按 goodsCode + batchNo 去重（新加的批次为空，故同商品只能加一次空批次行） */
function onGoodsConfirm(rows) {
  const existing = new Set(
    detailList.value.map(r => `${r.goodsCode}|${r.batchNo || ''}`)
  )
  let added = 0
  let skipped = 0
  rows.forEach(r => {
    const key = `${r.goodsCode}|${r.batchNo || ''}`
    if (existing.has(key)) { skipped++; return }
    detailList.value.push({ ...r, taxRate: '13%' })
    existing.add(key)
    added++
  })
  errors.value = {}
  if (skipped > 0) {
    errors.value.details = `已添加 ${added} 条；${skipped} 条因该商品已在明细中而跳过（如需退不同批次，请在明细里改批次后再添加）`
  }
}

function removeRow(index) {
  detailList.value.splice(index, 1)
  if (batchEditingIndex.value === index) batchEditingIndex.value = -1
}

// ============ 批次选择 ============
function batchKey(goodsCode) {
  return `${goodsCode}|${headerForm.value.warehouse}`
}

/** 展开某行批次下拉（首次展开时拉该商品在当前仓库的有库存批次） */
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
      `/purchase/return-apply/batch-options?goodsCode=${encodeURIComponent(row.goodsCode)}`
      + `&warehouse=${encodeURIComponent(headerForm.value.warehouse)}`
    )
    batchCache.value = {
      ...batchCache.value,
      [key]: (Array.isArray(rows) ? rows : []).map(b => ({
        batchNo: b.batchNo,
        productionDate: String(b.productionDate || '').slice(0, 10),
        availableQty: Number(b.availableQty || 0),
        costPrice: Number(b.costPrice || 0),
      })),
    }
  } catch (e) {
    batchCache.value = { ...batchCache.value, [key]: [] }
    errors.value.details = e.message || '加载批次失败'
  }
}

function batchOptionsFor(row) {
  return batchCache.value[batchKey(row.goodsCode)] || []
}

/**
 * 该批次是否已被本单其他行占用（同商品 + 同单位 + 同批次不允许重复）。
 * <p>业务含义：同商品同单位要退不同批次就各占一行，
 * 但两行不能选同一个批次 —— 否则同一批次的退货量被拆成两条，
 * 可退额度与批次库存校验都会算重。
 */
function isBatchTaken(index, batchNo) {
  const row = detailList.value[index]
  if (!row || !batchNo) return false
  return detailList.value.some((r, i) =>
    i !== index
    && r.goodsCode === row.goodsCode
    && (r.unitName || '') === (row.unitName || '')
    && (r.batchNo || '') === batchNo
  )
}

/** 选中批次 → 带出生产日期 + 可用库存 + 成本单价；已被其他行占用则拒绝 */
function pickBatch(index, batch) {
  const row = detailList.value[index]
  if (!row) return
  if (isBatchTaken(index, batch.batchNo)) {
    errors.value.details = `批次 ${batch.batchNo} 已被本单其他行使用（${row.goodsName || row.goodsCode} · ${row.unitName}），`
      + '同商品同单位同批次不可重复；请选其他批次，或直接修改那一行的数量'
    return
  }
  row.batchNo = batch.batchNo
  row.productionDate = batch.productionDate
  row.availableStock = batch.availableQty
  if (batch.costPrice > 0) row.costPrice = batch.costPrice
  batchEditingIndex.value = -1
  errors.value.details = ''
}

/**
 * 该行数量上限：
 *   · 按单退货 —— min(源单可退数量, 批次可用库存)
 *   · 按品退货 —— 批次可用库存
 * 批次可用库存是实物约束，突破会把批次库存扣成负数，因此两种方式都受它约束。
 *
 * 注意：availableStock 是「不含本单」的可用库存快照，
 * 展示时会减去本单同商品同批次已占用的数量（见 remainStockFor），但校验上限仍用原始快照。
 */
function maxQtyFor(row) {
  const stock = Number(row.availableStock || 0)
  if (!isByBill(row)) return stock
  const returnable = Number(row.returnableQty || 0)
  // 未选批次时 availableStock 是商品仓库维度的汇总值，同样作为上限
  return Math.min(returnable, stock)
}

/**
 * 展示用「可用库存」：原始可用库存 − 本单中同商品同批次<em>其他行</em>已填的退货数量。
 * <p>需求：可用库存不减当前添加商品的申请数量 —— 即本行自己填的数量不从可用库存里扣，
 * 但同商品同批次的其他行会占用额度，必须扣掉，否则多行叠加会超卖。
 */
function remainStockFor(row, index) {
  const base = Number(row.availableStock || 0)
  let usedByOthers = 0
  detailList.value.forEach((r, i) => {
    if (i === index) return                                  // 跳过本行：本行数量不扣
    if (r.goodsCode !== row.goodsCode) return
    if ((r.batchNo || '') !== (row.batchNo || '')) return     // 只有同批次才互相占用
    usedByOthers += Number(r.qty || 0)
  })
  const remain = base - usedByOthers
  return remain > 0 ? Math.round(remain * 10000) / 10000 : 0
}

// ============ 明细编辑 ============
/** 按单退货只能改数量与单价；按品退货数量/单位/单价/金额都能改 */
function isByBill(row) {
  return (row.returnMode || 'BY_BILL') === 'BY_BILL'
}

/** 该行的单位下拉选项（仅按品退货可切换，来自 unitConfig） */
function unitOptionsFor(row) {
  const raw = row.unitConfig
  if (!raw) return []
  let cfg
  try { cfg = typeof raw === 'string' ? JSON.parse(raw) : raw } catch (_) { return [] }
  if (!Array.isArray(cfg)) return []
  const labels = ['小单位', '中单位', '大单位']
  return cfg
    .map((u, i) => (u && u.enabled !== false && u.unitName)
      ? { name: u.unitName, label: labels[i] || '', convertQty: Number(u.convertQty) || 1 }
      : null)
    .filter(Boolean)
}

/** 按品退货切换单位：按换算率折算已填数量与单价，保持金额基本不变 */
function onUnitChange(row, newUnitName) {
  const opts = unitOptionsFor(row)
  const from = opts.find(o => o.name === row.unitName)
  const to = opts.find(o => o.name === newUnitName)
  row.unitName = newUnitName
  if (!from || !to || from.convertQty === to.convertQty) return
  const ratio = from.convertQty / to.convertQty   // 新单位数量 = 原数量 × (原换算率 / 新换算率)
  if (Number(row.qty) > 0) {
    row.qty = Math.round(Number(row.qty) * ratio * 10000) / 10000
  }
  if (Number(row.price) > 0) {
    row.price = Math.round(Number(row.price) / ratio * 10000) / 10000
  }
}

/**
 * 改金额 → 按数量反算单价，保留 4 位小数（两种退货方式都支持）。
 * 数量为 0 时不反算（除零），提示用户先填数量。
 * <p>实际由 {@code onAmountTextInput} 调用，见下方「数字录入」小节。
 */

// ============ 数字录入：纯手动输入，无加减控件 ============
/**
 * 数字文本清洗：只保留数字与**一个**小数点，并限制小数位数。
 * <p>保留用户正在输入的原始形态（如 "100."、"0."），不强制补零、不四舍五入，
 * 这样输入过程中光标不会跳、小数点不会被吞。
 */
function sanitizeDecimal(raw, maxDecimals) {
  let s = String(raw ?? '').replace(/[^\d.]/g, '')
  const firstDot = s.indexOf('.')
  if (firstDot >= 0) {
    const intPart = s.slice(0, firstDot)
    const decPart = s.slice(firstDot + 1).replace(/\./g, '').slice(0, maxDecimals)
    s = intPart + '.' + decPart
  }
  return s
}

/** 文本转数值："" / "." / "100." 都按已输入部分取值 */
function textToNumber(s) {
  const v = Number(s)
  return Number.isFinite(v) ? v : 0
}

/**
 * 录入中的「草稿文本」机制 —— 解决受控输入与派生值互相打架的问题。
 *
 * 数量 / 单价 / 金额三个字段在编辑时把原始文本存在 row.qtyText / priceText / amountText，
 * 输入框优先显示草稿文本；失焦时清掉草稿，回到规范化显示（单价 4 位、金额 2 位小数）。
 *
 * 金额尤其需要：它的显示值是 数量 × 单价 派生出来的，若直接绑派生值，
 * 每敲一个字符都会被重算覆盖（敲 "1" 变成 "1.00"，小数点永远敲不进去）。
 */
function qtyDisplay(row) {
  return row.qtyText !== undefined ? row.qtyText : (row.qty ?? '')
}
function priceDisplay(row) {
  if (row.priceText !== undefined) return row.priceText
  const p = Number(row.price || 0)
  return p === 0 ? '' : p
}
/**
 * 金额显示：**不补小数位的两个 0**，小数位完全由用户手动输入。
 * <p>用 rowAmountRaw 而非 rowAmount（后者 toFixed(2) 会把 100 显示成 100.00）。
 */
function amountDisplay(row) {
  if (row.amountText !== undefined) return row.amountText
  return rowAmountRaw(row)
}

function onQtyInput(row, raw) {
  const s = sanitizeDecimal(raw, 4)
  row.qtyText = s
  row.qty = textToNumber(s)
}
function onQtyBlur(row) {
  delete row.qtyText
}

function onPriceInput(row, raw) {
  const s = sanitizeDecimal(raw, 4)
  row.priceText = s
  row.price = textToNumber(s)
}
function onPriceBlur(row) {
  delete row.priceText
  // 兜底：单价最多 4 位小数（金额反算可能产生更长小数，如 100/3）
  row.price = Math.round(Number(row.price || 0) * 10000) / 10000
}

/** 金额录入：保留原始文本，同时按数量实时反算单价（4 位小数） */
function onAmountTextInput(row, raw) {
  const s = sanitizeDecimal(raw, 2)
  row.amountText = s
  const q = Number(row.qty) || 0
  if (q <= 0) {
    errors.value.details = '请先填写退货数量，再按金额反算单价'
    return
  }
  row.price = Math.round((textToNumber(s) / q) * 10000) / 10000
  errors.value.details = ''
}
function onAmountBlur(row) {
  delete row.amountText
}

/** 行金额（用于合计与提交）：2 位小数 */
function rowAmount(row) {
  return (Number(row.qty || 0) * Number(row.price || 0)).toFixed(2)
}

/**
 * 行金额（用于输入框显示）：**不补零** —— 100 显示 "100" 而非 "100.00"，
 * 小数位由用户手动输入。计算结果超过 2 位小数时才截到 2 位。
 */
function rowAmountRaw(row) {
  const v = Number(row.qty || 0) * Number(row.price || 0)
  if (v === 0) return ''
  const rounded = Math.round(v * 100) / 100
  return String(rounded)
}

/** 行数量是否超限（用于标红提示，保存时硬拦） */
function isQtyExceeded(row) {
  const q = Number(row.qty || 0)
  if (q <= 0) return false
  return q > maxQtyFor(row) + 1e-6
}

/** 超限原因提示文案 */
function qtyExceedTitle(row) {
  if (!isQtyExceeded(row)) return ''
  const returnable = Number(row.returnableQty || 0)
  const stock = Number(row.availableStock || 0)
  if (isByBill(row) && stock < returnable) {
    return `超过批次可用库存 ${stock}（源单可退 ${returnable}），请拆分到其他批次`
  }
  return isByBill(row) ? `超过可退数量 ${returnable}` : `超过可用库存 ${stock}`
}

// ============ 校验与保存 ============
function validate() {
  errors.value = {}
  if (!headerForm.value.supplier) {
    errors.value.header = '请选择供应商'
    return false
  }
  if (!headerForm.value.warehouse) {
    errors.value.header = '请选择仓库'
    return false
  }
  if (detailList.value.length === 0) {
    errors.value.details = '请通过【按单添加商品】或【添加商品】添加退货明细'
    return false
  }
  for (const row of detailList.value) {
    const label = row.goodsName || row.goodsCode
    if (Number(row.qty || 0) <= 0) {
      errors.value.details = `商品 ${label} 的退货数量必须大于 0`
      return false
    }
    if (isQtyExceeded(row)) {
      const returnable = Number(row.returnableQty || 0)
      const stock = Number(row.availableStock || 0)
      if (isByBill(row) && stock < returnable) {
        errors.value.details = `商品 ${label} 退货数量 ${row.qty} 超过批次可用库存 ${stock}（源单可退 ${returnable}），请拆分到其他批次退货`
      } else if (isByBill(row)) {
        errors.value.details = `商品 ${label} 退货数量 ${row.qty} 超过可退数量 ${returnable}`
      } else {
        errors.value.details = `商品 ${label} 退货数量 ${row.qty} 超过可用库存 ${stock}`
      }
      return false
    }
  }

  // 同商品 + 同单位 + 同批次 不允许重复（切换单位后可能出现，故保存前兜底校验）
  const seen = new Map()
  for (let i = 0; i < detailList.value.length; i++) {
    const r = detailList.value[i]
    if (!r.batchNo) continue
    const key = `${r.goodsCode}|${r.unitName || ''}|${r.batchNo}`
    if (seen.has(key)) {
      errors.value.details = `第 ${seen.get(key) + 1} 行与第 ${i + 1} 行重复：`
        + `${r.goodsName || r.goodsCode} · ${r.unitName} · 批次 ${r.batchNo}。`
        + '同商品同单位同批次只能有一行，请合并数量或改选其他批次'
      return false
    }
    seen.set(key, i)
  }
  return true
}

async function saveApply(status) {
  if (!canEdit.value) {
    errors.value.header = readonlyReason.value
    return
  }
  if (!validate()) return
  const payload = {
    supplierCode: headerForm.value.supplierCode,
    supplier: headerForm.value.supplier,
    warehouse: headerForm.value.warehouse,
    billDate: headerForm.value.billDate,
    returnReason: headerForm.value.returnReason,
    remark: headerForm.value.remark,
    status,
    details: detailList.value.map(r => ({
      returnMode: r.returnMode || 'BY_BILL',
      goodsCode: r.goodsCode,
      goodsName: r.goodsName,
      spec: r.spec || '',
      unitName: r.unitName,
      qty: Number(r.qty),
      price: Number(r.price) || 0,
      batchNo: r.batchNo || '',
      productionDate: r.productionDate || null,
      taxRate: r.taxRate || '13%',
      sourceInboundNo: r.sourceInboundNo || '',
      sourceDetailId: r.sourceDetailId || '',
      returnableQty: Number(r.returnableQty) || 0,
      costPrice: Number(r.costPrice) || 0,
      availableStock: Number(r.availableStock) || 0,
    })),
  }
  try {
    const result = isEdit.value
      ? await post('/purchase/return-apply/update', { ...payload, applyId: props.editData.applyId })
      : await post('/purchase/return-apply/create', payload)
    emit('save', result)
    emit('close')
  } catch (e) {
    errors.value.header = '保存失败：' + (e.message || '未知错误')
  }
}

function closeDrawer() { emit('close') }
</script>

<template>
  <div v-show="visible" class="return-drawer-mask">
    <div class="return-drawer-box">
      <div class="return-drawer-head">
        <b>{{ !isEdit ? '新建采购退货申请' : (canEdit ? '编辑采购退货申请' : '查看采购退货申请') }}</b>
        <span v-if="isEdit && applyStatus" class="status-tag" :class="'st-' + applyStatus.toLowerCase()">
          {{ statusText }}
        </span>
        <div style="flex:1"></div>
        <div class="actions">
          <button class="btn" @click="closeDrawer">{{ canEdit ? '取消' : '关闭' }}</button>
          <template v-if="canEdit">
            <button class="btn" @click="saveApply('DRAFT')">保存草稿</button>
            <button class="btn primary" @click="saveApply('PENDING')">保存并提交审核</button>
          </template>
        </div>
      </div>

      <div class="return-drawer-body">
        <!-- 已审核及之后状态：只读提示 -->
        <div v-if="!canEdit && isEdit" class="readonly-banner">🔒 {{ readonlyReason }}</div>

        <!-- 头部 -->
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">单据信息</div>
          <div v-if="errors.header" class="err-line">{{ errors.header }}</div>
          <div class="grid4">
            <div class="field">
              <label>供应商 <span v-if="canEdit" class="req">*</span></label>
              <select v-if="canEdit" v-model="headerForm.supplier" @change="onSupplierChange($event.target.value)">
                <option value="">请选择</option>
                <option v-for="o in supplierOptions" :key="o.code" :value="o.name">{{ o.name }}</option>
              </select>
              <input v-else readonly :value="headerForm.supplier" />
            </div>
            <div class="field">
              <label>仓库 <span v-if="canEdit" class="req">*</span></label>
              <select v-if="canEdit" v-model="headerForm.warehouse" @change="onWarehouseChange">
                <option value="">请选择</option>
                <option v-for="w in warehouseOptions" :key="w" :value="w">{{ w }}</option>
              </select>
              <input v-else readonly :value="headerForm.warehouse" />
            </div>
            <div class="field">
              <label>单据日期</label>
              <input type="date" v-model="headerForm.billDate" :readonly="!canEdit" :disabled="!canEdit" />
            </div>
            <div class="field">
              <label>退货原因</label>
              <input v-model="headerForm.returnReason" :readonly="!canEdit"
                     :placeholder="canEdit ? '如：质量问题 / 临期 / 滞销' : ''" />
            </div>
            <div class="field" style="grid-column:span 2">
              <label>备注</label>
              <input v-model="headerForm.remark" :readonly="!canEdit" :placeholder="canEdit ? '选填' : ''" />
            </div>
          </div>
        </div>

        <!-- 明细 -->
        <div class="card detail-card">
          <div class="detail-toolbar">
            <div style="font-weight:900;color:var(--primary)">退货明细</div>
            <div v-if="canEdit" class="toolbar-btns">
              <button class="btn primary" @click="openInboundPicker">按单添加商品</button>
              <button class="btn" @click="openGoodsPicker">添加商品</button>
            </div>
          </div>
          <div v-if="canEdit" class="mode-legend">
            <span><b class="tag by-bill">按单退货</b>源自采购入库单，只可改数量与单价，数量不超过可退数量</span>
            <span><b class="tag by-goods">按品退货</b>源自商品档案，数量/单位/单价/金额可改，数量不超过可用库存</span>
          </div>
          <div v-if="errors.details" class="err-line">{{ errors.details }}</div>
          <div v-if="loading" class="empty-detail">加载中...</div>
          <div v-else-if="detailList.length === 0" class="empty-detail">
            {{ canEdit ? '请点击【按单添加商品】从采购入库单选择，或点击【添加商品】从商品档案选择' : '该申请无退货明细' }}
          </div>
          <div v-else class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:36px">#</th>
                  <th style="width:78px">退货方式</th>
                  <th style="min-width:100px">商品编号</th>
                  <th style="min-width:150px">商品名称</th>
                  <th style="min-width:90px">规格</th>
                  <th style="width:92px">单位</th>
                  <th style="width:92px">退货数量 <span v-if="canEdit" class="req">*</span></th>
                  <th style="width:130px">单价</th>
                  <th style="width:130px">金额</th>
                  <th style="min-width:130px">批次号</th>
                  <th style="width:100px">生产日期</th>
                  <th style="width:78px">可退数量</th>
                  <th style="width:78px">可用库存</th>
                  <th style="width:96px">成本单价</th>
                  <th style="min-width:130px">源单号</th>
                  <th v-if="canEdit" style="width:56px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in detailList" :key="index">
                  <td>{{ index + 1 }}</td>
                  <td>
                    <span class="tag" :class="isByBill(row) ? 'by-bill' : 'by-goods'">
                      {{ isByBill(row) ? '按单退货' : '按品退货' }}
                    </span>
                  </td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.spec || '-' }}</td>
                  <!-- 单位：按单只读；按品可切换（只读模式一律纯文本） -->
                  <td>
                    <select
                      v-if="canEdit && !isByBill(row) && unitOptionsFor(row).length > 1"
                      :value="row.unitName"
                      class="cell-input"
                      @change="onUnitChange(row, $event.target.value)"
                    >
                      <option v-for="u in unitOptionsFor(row)" :key="u.name" :value="u.name">{{ u.name }}</option>
                    </select>
                    <span v-else>{{ row.unitName || '-' }}</span>
                  </td>
                  <!-- 数量：纯文本录入（无加减控件），最多4位小数 -->
                  <td>
                    <input
                      v-if="canEdit"
                      type="text"
                      inputmode="decimal"
                      :value="qtyDisplay(row)"
                      class="cell-input num"
                      :class="{ 'input-error': isQtyExceeded(row) }"
                      :title="qtyExceedTitle(row)"
                      @input="onQtyInput(row, $event.target.value)"
                      @blur="onQtyBlur(row)"
                    />
                    <span v-else class="num-cell">{{ row.qty }}</span>
                  </td>
                  <!-- 单价：纯文本录入，最多4位小数 -->
                  <td>
                    <input
                      v-if="canEdit"
                      type="text"
                      inputmode="decimal"
                      :value="priceDisplay(row)"
                      class="cell-input num wide"
                      @input="onPriceInput(row, $event.target.value)"
                      @blur="onPriceBlur(row)"
                    />
                    <span v-else class="num-cell">{{ Number(row.price || 0).toFixed(4) }}</span>
                  </td>
                  <!-- 金额：纯文本录入，最多2位小数；改金额按数量反算单价（4位小数） -->
                  <td>
                    <input
                      v-if="canEdit"
                      type="text"
                      inputmode="decimal"
                      :value="amountDisplay(row)"
                      class="cell-input num wide"
                      title="修改金额将按退货数量反算单价（保留4位小数）"
                      @input="onAmountTextInput(row, $event.target.value)"
                      @blur="onAmountBlur(row)"
                    />
                    <span v-else class="num-cell">{{ rowAmount(row) }}</span>
                  </td>
                  <!-- 批次号：可编辑时点击展开有库存批次；只读时纯文本 -->
                  <td class="batch-cell">
                    <template v-if="canEdit">
                      <button class="batch-btn" :class="{ empty: !row.batchNo }" @click="openBatchPicker(index)">
                        {{ row.batchNo || '选择批次' }}
                      </button>
                    <div v-if="batchEditingIndex === index" class="batch-dropdown">
                      <div v-if="batchOptionsFor(row).length === 0" class="batch-empty">
                        该商品在【{{ headerForm.warehouse || '未选仓库' }}】无可用批次库存
                      </div>
                      <div
                        v-for="b in batchOptionsFor(row)"
                        :key="b.batchNo"
                        class="batch-opt"
                        :class="{ taken: isBatchTaken(index, b.batchNo) }"
                        :title="isBatchTaken(index, b.batchNo) ? '该批次已被本单其他行使用（同商品同单位同批次不可重复）' : ''"
                        @click="pickBatch(index, b)"
                      >
                        <span class="bn">{{ b.batchNo }}</span>
                        <span class="pd">{{ b.productionDate || '-' }}</span>
                        <span class="aq">
                          <template v-if="isBatchTaken(index, b.batchNo)">已占用</template>
                          <template v-else>可用 {{ b.availableQty }}</template>
                        </span>
                      </div>
                    </div>
                    </template>
                    <span v-else>{{ row.batchNo || '-' }}</span>
                  </td>
                  <td>{{ row.productionDate || '-' }}</td>
                  <td class="num-cell">{{ isByBill(row) ? row.returnableQty : '-' }}</td>
                  <!-- 可用库存：不减本行数量；但同商品同批次的其他行会占用，需扣除 -->
                  <td class="num-cell" :title="remainStockFor(row, index) !== Number(row.availableStock || 0)
                        ? `批次可用 ${row.availableStock}，本单其他行已占用 ${Number(row.availableStock || 0) - remainStockFor(row, index)}` : ''">
                    {{ remainStockFor(row, index) }}
                  </td>
                  <td class="num-cell">{{ Number(row.costPrice || 0).toFixed(6) }}</td>
                  <td>{{ row.sourceInboundNo || '-' }}</td>
                  <td v-if="canEdit">
                    <button class="link link-btn danger-link" @click="removeRow(index)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="summary">
          <span>合计退货数量：<b>{{ totalQty }}</b></span>
          <span>合计退货金额：<b>¥ {{ totalAmount }}</b></span>
          <span>行数：<b>{{ detailList.length }}</b></span>
        </div>
      </div>
    </div>
  </div>

  <!--
    两个选择窗口必须放在 .return-drawer-mask **外面**：
    该 mask 为了让抽屉左侧区域可点击设了 pointer-events: none，
    只有 .return-drawer-box 重新开启了 auto。若把弹窗嵌在 mask 内，
    弹窗会继承 none 而变成「能看见但点不动」（窗口卡死）。
  -->
  <PurchaseInboundPickerDialog
    :visible="showInboundPicker"
    :supplier-name="headerForm.supplier"
    @close="showInboundPicker = false"
    @confirm="onInboundConfirm"
  />

  <PurchaseReturnGoodsPickerDialog
    :visible="showGoodsPicker"
    :supplier-name="headerForm.supplier"
    :warehouse="headerForm.warehouse"
    @close="showGoodsPicker = false"
    @confirm="onGoodsConfirm"
  />
</template>

<style scoped>
.return-drawer-mask {
  position: fixed;
  top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900;
  display: flex;
  pointer-events: none;
  animation: fadeIn 0.2s ease;
}
.return-drawer-box {
  flex: 1;
  background: #fff;
  display: flex; flex-direction: column;
  min-width: 0;
  border-left: 1px solid var(--line);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.12);
  pointer-events: auto;
  animation: slideIn 0.25s ease;
}
.return-drawer-head {
  display: flex; align-items: center; gap: 10px;
  height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft);
}
.return-drawer-head b { font-size: 15px; }
.return-drawer-body {
  flex: 1; overflow: auto;
  padding: 12px 16px;
  display: flex; flex-direction: column; gap: 12px;
  background: #f5f7fa;
}
.detail-card { flex: 1; display: flex; flex-direction: column; min-height: 300px; padding: 12px; }
.detail-toolbar {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 8px;
}
.toolbar-btns { display: flex; gap: 8px; }
.mode-legend {
  display: flex; flex-wrap: wrap; gap: 16px;
  font-size: 12px; color: #5d7896;
  padding-bottom: 8px;
}
.mode-legend .tag { margin-right: 6px; }
.detail-scroll { flex: 1; overflow: auto; min-height: 0; }
.detail-scroll table { width: 100%; border-collapse: collapse; font-size: 12px; }
.detail-scroll th {
  position: sticky; top: 0; z-index: 2;
  background: #f5f7fa; padding: 7px 6px; text-align: left;
  white-space: nowrap; border-bottom: 1px solid var(--line);
}
.detail-scroll td {
  padding: 4px 6px; border-bottom: 1px solid #f0f2f5;
  white-space: nowrap;
}
.detail-scroll td.num-cell { text-align: right; font-variant-numeric: tabular-nums; }
.cell-input {
  width: 100%; height: 24px; padding: 0 4px;
  border: 1px solid #dcdfe6; border-radius: 3px;
  font-size: 12px; min-width: 0;
}
.cell-input.num { text-align: right; font-variant-numeric: tabular-nums; }
/* 单价 / 金额：需容纳 10 位数字（如 1234567.8901），等宽数字下约 120px */
.cell-input.wide { min-width: 120px; }
.cell-input.input-error { border-color: #f56c6c; background: #fef0f0; }
/* 数量/单价/金额一律纯手动录入：去掉浏览器数字加减控件（保险起见两种内核都关） */
.cell-input::-webkit-outer-spin-button,
.cell-input::-webkit-inner-spin-button { -webkit-appearance: none; margin: 0; }
.cell-input[type="number"] { -moz-appearance: textfield; appearance: textfield; }
.empty-detail {
  padding: 40px; text-align: center; color: #909399; font-size: 13px;
  background: #fafbfc; border: 1px dashed #e5e7eb; border-radius: 8px;
}
.err-line {
  color: var(--danger); font-size: 12px;
  padding: 6px 10px; margin-bottom: 6px;
  background: #fef0f0; border: 1px solid #fde2e2; border-radius: 4px;
}
.field .req { color: #f56c6c; }

/* 只读提示条：已审核及之后状态的单据顶部醒目提示 */
.readonly-banner {
  padding: 8px 12px;
  background: #fdf6ec; border: 1px solid #faecd8; border-radius: 6px;
  color: #e6a23c; font-size: 12px; font-weight: 700;
}
/* 头部状态标签 */
.status-tag {
  padding: 2px 10px; border-radius: 10px;
  font-size: 12px; font-weight: 700; white-space: nowrap;
  background: #f4f4f5; border: 1px solid #e9e9eb; color: #909399;
}
.status-tag.st-draft { background: #f4f4f5; border-color: #e9e9eb; color: #909399; }
.status-tag.st-pending { background: #fdf6ec; border-color: #faecd8; color: #e6a23c; }
.status-tag.st-approved { background: #f0f9eb; border-color: #e1f3d8; color: #67c23a; }
.status-tag.st-outbounded { background: #ecf5ff; border-color: #d9ecff; color: #409eff; }
.status-tag.st-completed { background: #f0f9eb; border-color: #e1f3d8; color: #67c23a; }
/* 只读输入框：视觉上区分于可编辑 */
.field input[readonly] { background: #f7f8fa; color: #606266; }

/* 退货方式标签 */
.tag {
  display: inline-block; padding: 1px 6px;
  border-radius: 3px; font-size: 11px; font-weight: 700;
  white-space: nowrap;
}
.tag.by-bill { background: #ecf5ff; color: #409eff; border: 1px solid #d9ecff; }
.tag.by-goods { background: #fdf6ec; color: #e6a23c; border: 1px solid #faecd8; }

/* 批次下拉 */
.batch-cell { position: relative; }
.batch-btn {
  width: 100%; height: 24px; padding: 0 6px;
  border: 1px solid #dcdfe6; border-radius: 3px;
  background: #fff; cursor: pointer;
  font-size: 12px; text-align: left;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.batch-btn:hover { border-color: #409eff; }
.batch-btn.empty { color: #c0c4cc; }
.batch-dropdown {
  position: absolute; top: 100%; left: 0; z-index: 30;
  min-width: 240px;
  background: #fff; border: 1px solid #dcdfe6; border-radius: 4px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.12);
  max-height: 220px; overflow-y: auto;
}
.batch-opt {
  display: grid; grid-template-columns: 1fr 90px 76px;
  gap: 6px; padding: 6px 8px;
  cursor: pointer; font-size: 12px;
  border-bottom: 1px solid #f0f2f5;
}
.batch-opt:last-child { border-bottom: none; }
.batch-opt:hover { background: #ecf5ff; }
/* 已被本单其他行占用的批次：灰化 + 禁止光标（同商品同单位同批次不可重复） */
.batch-opt.taken {
  color: #c0c4cc;
  background: #fafafa;
  cursor: not-allowed;
}
.batch-opt.taken:hover { background: #fafafa; }
.batch-opt.taken .aq { color: #c0c4cc; }
.batch-opt .bn { font-family: var(--font-mono); }
.batch-opt .pd { color: #666; }
.batch-opt .aq { color: #67c23a; text-align: right; }
.batch-empty { padding: 12px; text-align: center; color: #909399; font-size: 12px; }

@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6 } to { transform: translateX(0); opacity: 1 } }
@media (max-width: 900px) {
  .return-drawer-mask { left: 0; }
}
</style>