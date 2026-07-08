<script setup>
/**
 * 其他入库单抽屉 —— 新建 / 编辑 / 查看其他入库单。
 *
 * 用于非采购/调拨类入库：期初库存、样品入库、赠品入库、盘外发现、内部加工等。
 *
 * 流程：新建（DRAFT/PENDING）→ 编辑 → 审核（增加库存）→ 反审核（扣回库存）。
 * 支持作废和删除（草稿与待审核）。
 *
 * 与报损单抽屉的关键差异：
 *   - 头部多出「客户 / 供应商」（二选一，选一个自动清空另一个）与「其他入库类型」（取字典）
 *   - 明细单价默认取商品标准售价（报损取成本单价）
 *   - 入库不校验可用库存（不存在超额问题），批次可沿用已有批次或新建
 */
import { ref, computed, watch, onBeforeUnmount, nextTick } from 'vue'
import { post, get } from '../api/client.js'
import { getDict } from '../utils/dictionary.js'
import { formatDate } from '../utils/dateTime.js'
import InlineGoodsPicker from './InlineGoodsPicker.vue'
import OtherInboundGoodsDialog from './OtherInboundGoodsDialog.vue'

/** 其他入库类型字典编码 */
const DICT_OTHER_INBOUND_TYPE = 'other_inbound_type'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 编辑/查看已有单据时传入 { inboundId, inboundNo } */
  editData: { type: Object, default: null },
  /** 外部强制只读（列表点「查看」时传 true） */
  readonly: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'save'])

const headerForm = ref(emptyHeader())
const detailList = ref([])
const errors = ref({})
const loading = ref(false)
const inboundStatus = ref('')

const warehouseOptions = ref([])
const customerOptions = ref([])
const supplierOptions = ref([])
const inboundTypeOptions = ref([])
const allGoods = ref([])

// 商品选择弹窗
const showGoodsPicker = ref(false)

/** 单元格 input 的 ref 表，键为 `${rowIndex}:${colKey}`（模式参照 BillDrawer） */
const cellRefs = ref({})

/** 明细行可编辑列顺序 —— 回车按此顺序在同行内向右推进 */
const EDITABLE_COLS = ['goods', 'qty', 'price', 'batchNo', 'productionDate']

function bindCell(rowIndex, colKey) {
  return el => {
    const k = `${rowIndex}:${colKey}`
    if (el) cellRefs.value[k] = el
    else delete cellRefs.value[k]
  }
}

/**
 * 当前仓库下每个商品的成本单价与库存：{ goodsCode: { costPrice, availableStock } }。
 * 换仓库时整体重取一次，弹窗与行内选择共用，避免每加一个商品都发一次请求。
 */
const warehouseCostMap = ref({})

/** 弹窗用的商品列表：商品档案 + 当前仓库的成本/库存 */
const enrichedGoods = computed(() => allGoods.value.map(g => {
  const ext = warehouseCostMap.value[g.goodsCode]
  return {
    ...g,
    costPrice: ext ? ext.costPrice : 0,
    availableStock: ext ? ext.availableStock : 0,
  }
}))

// 批次下拉
const batchCache = ref({})
const batchEditingIndex = ref(-1)

const isEdit = computed(() => !!props.editData?.inboundId)

const canEdit = computed(() => {
  if (props.readonly) return false
  if (!isEdit.value) return true
  return inboundStatus.value === 'DRAFT' || inboundStatus.value === 'PENDING'
})

const readonlyReason = computed(() => {
  if (canEdit.value) return ''
  switch (inboundStatus.value) {
    case 'APPROVED': return '该其他入库单已审核，只可查看。如需修改请先反审核。'
    case 'CANCELLED': return '该其他入库单已作废，只可查看。'
    default: return '该其他入库单当前状态不可编辑，只可查看。'
  }
})

const statusText = computed(() => ({
  DRAFT: '草稿',
  PENDING: '未审核',
  APPROVED: '已审核',
  CANCELLED: '已作废',
}[inboundStatus.value] || inboundStatus.value))

function emptyHeader() {
  return {
    billDate: new Date().toISOString().slice(0, 10),
    customer: '',
    supplier: '',
    inboundType: '',
    warehouse: '',
    remark: '',
  }
}

/** 新建一个空明细行（末尾常驻空行，供直接在列表里录入） */
function makeEmptyRow() {
  return {
    goodsCode: '', goodsName: '', spec: '', unitName: '',
    qty: null, price: null, amount: 0,
    batchNo: '', productionDate: '', batchNoTouched: false,
    costPrice: 0, costAmount: 0, currentStock: 0,
    // 仅前端使用：该行商品搜索框关键字
    goodsSearch: '',
  }
}

/** 已选商品的行 —— 校验、保存、合计一律只认这些行，末尾空行必须排除 */
const filledRows = computed(() => detailList.value.filter(r => r.goodsCode))

/** 保证末尾始终有且仅有一个空行 */
function ensureTrailingBlankRow() {
  const list = detailList.value
  if (list.length === 0 || list[list.length - 1].goodsCode) {
    list.push(makeEmptyRow())
  }
}

const totalQty = computed(() =>
  filledRows.value.reduce((s, r) => s + Number(r.qty || 0), 0)
)
const totalAmount = computed(() =>
  filledRows.value.reduce((s, r) => s + Number(r.amount || 0), 0).toFixed(2)
)
const totalCostAmount = computed(() =>
  filledRows.value.reduce((s, r) => s + Number(r.costAmount || 0), 0).toFixed(2)
)

watch(() => props.visible, async (val) => {
  if (!val) return
  errors.value = {}
  detailList.value = []
  batchCache.value = {}
  batchEditingIndex.value = -1
  warehouseCostMap.value = {}
  cellRefs.value = {}
  showGoodsPicker.value = false
  inboundStatus.value = ''
  headerForm.value = emptyHeader()
  await loadBaseOptions()
  if (props.editData?.inboundId) {
    await loadExisting(props.editData.inboundId)
  }
  // 头部仓库就绪后（新建时可能只有一个仓库被自动选中）取一次成本/库存
  if (headerForm.value.warehouse) {
    await loadWarehouseCostMap()
    // 编辑已有单据：把当前库存回填到已加载的明细行（成本沿用单据上记录的值，不覆盖）
    detailList.value.forEach(r => {
      const ext = warehouseCostMap.value[r.goodsCode]
      if (ext) r.currentStock = ext.availableStock
    })
  }
  // 进入即有一条空行可直接录入
  if (canEdit.value) ensureTrailingBlankRow()
})

/**
 * 取当前仓库下全部商品的成本单价与可用库存，缓存到 warehouseCostMap。
 * 后端 goods-options 不传 keyword 时返回全部商品，一次调用即可覆盖弹窗与快速添加。
 */
async function loadWarehouseCostMap() {
  const wh = headerForm.value.warehouse
  if (!wh) { warehouseCostMap.value = {}; return }
  try {
    const opts = await get(`/inventory/other-inbound/goods-options?warehouse=${encodeURIComponent(wh)}`)
    const map = {}
    for (const o of (Array.isArray(opts) ? opts : [])) {
      map[o.goodsCode] = {
        costPrice: Number(o.costPrice || 0),
        availableStock: Number(o.availableStock || 0),
      }
    }
    warehouseCostMap.value = map
  } catch {
    warehouseCostMap.value = {}   // 取不到不阻塞录单，成本回落到标准售价
  }
}

async function loadBaseOptions() {
  const emptyPage = { records: [] }
  try {
    const [wh, cust, supp, goods, dict] = await Promise.all([
      post('/base/warehouse/page', { pageNo: 1, pageSize: 200, filters: {} }).catch(() => emptyPage),
      post('/base/customer/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => emptyPage),
      post('/base/supplier/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => emptyPage),
      post('/base/goods/page', { pageNo: 1, pageSize: 9999, filters: {} }).catch(() => emptyPage),
      getDict(DICT_OTHER_INBOUND_TYPE).catch(() => []),
    ])
    warehouseOptions.value = (wh.records || []).map(r => r.warehouseName).filter(Boolean)
    if (!headerForm.value.warehouse && warehouseOptions.value.length === 1) {
      headerForm.value.warehouse = warehouseOptions.value[0]
    }
    customerOptions.value = (cust.records || []).map(r => r.customerName).filter(Boolean)
    supplierOptions.value = (supp.records || []).map(r => r.supplierName).filter(Boolean)
    inboundTypeOptions.value = (dict || []).map(d => d.name).filter(Boolean)
    allGoods.value = (goods.records || []).map(g => ({
      goodsCode: g.goodsCode,
      goodsName: g.goodsName,
      spec: g.spec || '',
      baseUnit: g.baseUnit || '',
      barcode: g.barcode || '',
      simpleCode: g.simpleCode || '',
      brandName: g.brandName || '',
      categoryName: g.categoryName || '',
      status: g.status || 'NORMAL',
      standardPrice: Number(g.standardPrice || 0),
    }))
  } catch {
    warehouseOptions.value = []
    customerOptions.value = []
    supplierOptions.value = []
    inboundTypeOptions.value = []
    allGoods.value = []
  }
}

async function loadExisting(inboundId) {
  loading.value = true
  try {
    const data = await get(`/inventory/other-inbound/detail?inboundId=${encodeURIComponent(inboundId)}`)
    inboundStatus.value = data.status || ''
    headerForm.value = {
      billDate: String(data.billDate || '').slice(0, 10) || new Date().toISOString().slice(0, 10),
      customer: data.customer || '',
      supplier: data.supplier || '',
      inboundType: data.inboundType || '',
      warehouse: data.warehouse || '',
      remark: data.remark || '',
    }
    detailList.value = (data.details || []).map(d => ({
      detailId: d.detailId,
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      spec: d.spec || '',
      unitName: d.unitName || '',
      goodsSearch: d.goodsCode || '',
      qty: Number(d.qty || 0),
      price: Number(d.price || 0),
      amount: Number(d.amount || 0),
      batchNo: d.batchNo || '',
      productionDate: String(d.productionDate || '').slice(0, 10),
      batchNoTouched: !!d.batchNo,
      costPrice: Number(d.costPrice || 0),
      costAmount: Number(d.costAmount || 0),
      currentStock: 0,
    }))
  } catch (e) {
    errors.value.header = e.message || '加载其他入库单失败'
  } finally {
    loading.value = false
  }
}

// ============ 客户 / 供应商二选一 ============
/** 填了客户就清空供应商，反之亦然——后端也会二次校验 */
function onCustomerChange() {
  if (headerForm.value.customer) headerForm.value.supplier = ''
  errors.value.header = ''
}
function onSupplierChange() {
  if (headerForm.value.supplier) headerForm.value.customer = ''
  errors.value.header = ''
}

// ============ 仓库切换 ============
/** 换仓库后原批次与成本都不再适用，清空并重取当前仓库的成本/库存 */
async function onWarehouseChange() {
  batchCache.value = {}
  batchEditingIndex.value = -1
  await loadWarehouseCostMap()
  detailList.value.forEach((r, i) => {
    r.batchNo = ''
    r.productionDate = ''
    r.batchNoTouched = false
    const ext = warehouseCostMap.value[r.goodsCode]
    r.currentStock = ext ? ext.availableStock : 0
    r.costPrice = ext && ext.costPrice > 0 ? ext.costPrice : Number(r.price || 0)
    recalcAmount(i)
  })
}

// ============ 添加商品 ============
function openGoodsPicker() {
  if (!canEdit.value) { errors.value.header = readonlyReason.value; return }
  if (!headerForm.value.warehouse) {
    errors.value.header = '请先选择仓库'
    return
  }
  errors.value = {}
  showGoodsPicker.value = true
}

/**
 * 按商品档案填充一行明细（就地修改传入的 row，保留其在列表中的位置）：
 *   单价 = 标准售价；成本单价 = 当前仓库库存成本，无成本时回落到标准售价（审核时还会按审核时点成本刷新）
 */
function fillRowWithGoods(row, goods) {
  const ext = warehouseCostMap.value[goods.goodsCode]
  const price = Number(goods.standardPrice || 0)
  row.goodsCode = goods.goodsCode
  row.goodsName = goods.goodsName
  row.spec = goods.spec || ''
  row.unitName = goods.baseUnit || ''
  row.goodsSearch = goods.goodsCode
  row.price = price
  row.costPrice = ext && ext.costPrice > 0 ? ext.costPrice : price
  row.currentStock = ext ? ext.availableStock : 0
  if (row.qty == null) row.qty = 1     // 直接录入场景给个默认量，回车即可继续
  recalcRow(row)
  return row
}

/** 生成一行已填好商品的明细 */
function makeRow(goods) {
  return fillRowWithGoods(makeEmptyRow(), goods)
}

/**
 * 行内选中商品：就地填充该行（不新增行），随后保证末尾仍有空行。
 * 同商品允许重复（其他入库常需同商品分多个批次），但不允许出现两行「同商品且都未指定批次」。
 */
async function onRowGoodsSelect(row, rowIndex, goods) {
  if (!goods?.goodsCode) return
  const dup = detailList.value.some((r, i) =>
    i !== rowIndex && r.goodsCode === goods.goodsCode && !r.batchNo && !row.batchNo)
  if (dup) {
    errors.value.details = `${goods.goodsName || goods.goodsCode} 已有未指定批次的明细行，请先填写其批次号再录同商品`
    row.goodsSearch = ''
    return
  }
  errors.value.details = ''
  fillRowWithGoods(row, goods)
  ensureTrailingBlankRow()
  await nextTick()
  focusCell(rowIndex, 'qty')   // 选完商品光标进数量，符合连续录单习惯
}

/** 行内多选：当前行填第一个，其余追加 */
function onRowGoodsSelectMulti(row, rowIndex, list) {
  const rows = Array.isArray(list) ? list : [list]
  if (rows.length === 0) return
  onRowGoodsSelect(row, rowIndex, rows[0])
  addGoodsRows(rows.slice(1))
}

/**
 * 批量写入明细（弹窗来源）。
 * 优先填入末尾空行，避免空行夹在中间；同商品未指定批次的重复行会被跳过并提示。
 */
function addGoodsRows(list) {
  if (!canEdit.value) return
  const rows = Array.isArray(list) ? list : [list]
  const skipped = []
  for (const g of rows) {
    if (!g?.goodsCode) continue
    const blankDup = detailList.value.find(r => r.goodsCode === g.goodsCode && !r.batchNo)
    if (blankDup) { skipped.push(g.goodsName || g.goodsCode); continue }
    const blankIdx = detailList.value.findIndex(r => !r.goodsCode)
    if (blankIdx >= 0) fillRowWithGoods(detailList.value[blankIdx], g)
    else detailList.value.push(makeRow(g))
  }
  ensureTrailingBlankRow()
  errors.value.details = skipped.length
    ? `${skipped.join('、')} 已有未指定批次的明细行，请先填写其批次号再添加同商品`
    : ''
}

/** 弹窗确定 / 行内「添加」回调 */
function onPickerConfirm(rows) {
  addGoodsRows(rows)
}

// ============ 单元格键盘导航（模式参照 BillDrawer） ============

function focusCell(rowIndex, colKey) {
  const el = cellRefs.value[`${rowIndex}:${colKey}`]
  if (!el) return
  // InlineGoodsPicker 暴露的是组件实例，用它 expose 的 focus()
  if (typeof el.focus === 'function') el.focus()
  if (typeof el.select === 'function') el.select()
}

/** ↑↓ 同列换行 */
function moveRow(rowIndex, colKey, dir) {
  const target = dir === 'up' ? rowIndex - 1 : rowIndex + 1
  if (target < 0 || target >= detailList.value.length) return
  focusCell(target, colKey)
}

/**
 * 回车：同行推进到下一个可编辑字段；
 * 已在行末字段 → 跳下一行首字段（商品列）；最后一行则先补空行再跳。
 */
async function nextField(rowIndex, colKey) {
  const i = EDITABLE_COLS.indexOf(colKey)
  if (i < 0) return
  if (i < EDITABLE_COLS.length - 1) {
    focusCell(rowIndex, EDITABLE_COLS[i + 1])
    return
  }
  if (rowIndex === detailList.value.length - 1) {
    ensureTrailingBlankRow()
    await nextTick()
  }
  focusCell(rowIndex + 1, EDITABLE_COLS[0])
}

/** 单元格通用键盘处理（商品列除外，它的导航由 InlineGoodsPicker 转发） */
function onCellKeyDown(e, rowIndex, colKey) {
  const k = e.key
  if (k === 'ArrowUp') { moveRow(rowIndex, colKey, 'up'); e.preventDefault() }
  else if (k === 'ArrowDown') { moveRow(rowIndex, colKey, 'down'); e.preventDefault() }
  else if (k === 'Enter') { nextField(rowIndex, colKey); e.preventDefault() }
}

/** 商品列的导航请求（下拉未展开时由 InlineGoodsPicker 转发上来） */
function onGoodsNav(rowIndex, action) {
  if (action === 'up') moveRow(rowIndex, 'goods', 'up')
  else if (action === 'down') moveRow(rowIndex, 'goods', 'down')
  else if (action === 'enter') nextField(rowIndex, 'goods')
}

function removeRow(index) {
  detailList.value.splice(index, 1)
  if (batchEditingIndex.value === index) batchEditingIndex.value = -1
  // 删空了或末尾不是空行 → 补一空行，保证始终可继续录入
  if (canEdit.value) ensureTrailingBlankRow()
}

// ============ 批次选择 ============
function batchKey(goodsCode) {
  return `${goodsCode}|${headerForm.value.warehouse}`
}

function closeBatchPicker() {
  batchEditingIndex.value = -1
}

/**
 * 点击批次单元格以外的任何地方都关闭批次下拉。
 * 用 mousedown 捕获阶段：早于批次项的 click，但因为命中 .batch-cell 会提前返回，
 * 所以不会把「点下拉项选批次」这个操作误关掉。
 */
function onDocMouseDown(e) {
  if (batchEditingIndex.value < 0) return
  if (e.target?.closest?.('.batch-cell')) return
  closeBatchPicker()
}

/** Esc 关闭批次下拉 */
function onDocKeyDown(e) {
  if (batchEditingIndex.value >= 0 && e.key === 'Escape') closeBatchPicker()
}

// 仅在下拉展开时挂全局监听，避免常驻
watch(batchEditingIndex, (v) => {
  if (v >= 0) {
    document.addEventListener('mousedown', onDocMouseDown, true)
    document.addEventListener('keydown', onDocKeyDown)
  } else {
    document.removeEventListener('mousedown', onDocMouseDown, true)
    document.removeEventListener('keydown', onDocKeyDown)
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocMouseDown, true)
  document.removeEventListener('keydown', onDocKeyDown)
})

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
      `/inventory/other-inbound/batch-options?goodsCode=${encodeURIComponent(row.goodsCode)}&warehouse=${encodeURIComponent(headerForm.value.warehouse)}`
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
  } catch { /* 静默：批次仅为便捷沿用，取不到可手工输入 */ }
}

function isBatchTaken(batch, index) {
  if (!batch.batchNo) return false
  return detailList.value.some((r, i) =>
    i !== index && r.goodsCode === detailList.value[index].goodsCode && r.batchNo === batch.batchNo
  )
}

/** 选中已有批次：沿用其生产日期与成本单价 */
function pickBatch(index, batch) {
  if (isBatchTaken(batch, index)) {
    errors.value.details = '该批次已被其他行使用'
    return
  }
  const row = detailList.value[index]
  row.batchNo = batch.batchNo
  row.batchNoTouched = true
  row.productionDate = batch.productionDate
  if (batch.costPrice > 0) row.costPrice = Number(batch.costPrice)
  row.currentStock = Number(batch.availableQty || 0)
  recalcAmount(index)
  batchEditingIndex.value = -1
}

/**
 * 生产日期变更 → 若用户没手动改过批次号，同步刷成 yyyyMMdd。
 * 批次号生成规则见 CLAUDE.md「商品批次号生成规则（全局统一）」：无前缀。
 */
function onProductionDateChange(row) {
  if (row.batchNoTouched) return
  const pd = String(row.productionDate || '').replace(/-/g, '')
  if (pd) row.batchNo = pd
}

/**
 * 生产日期按 YYYY-MM-DD 规范化（CLAUDE.md 全局日期约定）。
 * 宽容接受 20260813 / 2026/8/13 / 2026.8.13 等写法，失焦时统一成 YYYY-MM-DD；
 * 实在解析不出来就清空，避免把非法字符串提交到后端。
 */
function onProductionDateBlur(row) {
  const raw = String(row.productionDate || '').trim()
  if (!raw) { row.productionDate = ''; onProductionDateChange(row); return }
  const compact = raw.replace(/[^\d]/g, '')
  let normalized = ''
  if (compact.length === 8) {
    normalized = `${compact.slice(0, 4)}-${compact.slice(4, 6)}-${compact.slice(6, 8)}`
  } else {
    normalized = formatDate(raw.replace(/[./]/g, '-'))
  }
  // formatDate 对非法值返回空串；再用 Date 兜一层，挡住 2026-13-45 这类
  const d = normalized ? new Date(normalized) : null
  row.productionDate = (d && !Number.isNaN(d.getTime())) ? formatDate(d) : ''
  if (!row.productionDate && raw) {
    errors.value.details = `生产日期「${raw}」格式不正确，请按 YYYY-MM-DD 填写`
  }
  onProductionDateChange(row)
}

function onBatchNoInput(row) {
  row.batchNoTouched = true
}

// ============ 金额计算 ============
/** 按行重算金额与成本金额（单价 4 位、金额 2 位，见 CLAUDE.md 精度约定） */
function recalcRow(row) {
  const q = Number(row.qty || 0)
  row.amount = parseFloat((q * Number(row.price || 0)).toFixed(2))
  row.costAmount = parseFloat((q * Number(row.costPrice || 0)).toFixed(2))
}

function recalcAmount(index) {
  const row = detailList.value[index]
  if (row) recalcRow(row)
}

function onQtyChange(index) {
  errors.value.details = ''
  recalcAmount(index)
}

function onPriceChange(index) {
  recalcAmount(index)
}

// ============ 保存 ============
function validate() {
  errors.value = {}
  const f = headerForm.value
  if (!f.customer && !f.supplier) { errors.value.header = '请选择客户或供应商'; return false }
  if (f.customer && f.supplier) { errors.value.header = '客户与供应商只能选择一个'; return false }
  if (!f.inboundType) { errors.value.header = '请选择其他入库类型'; return false }
  if (!f.warehouse) { errors.value.header = '请选择仓库'; return false }

  // 只校验已选商品的行；末尾常驻空行不参与
  const rows = filledRows.value
  if (rows.length === 0) { errors.value.details = '请添加入库商品'; return false }

  for (const r of rows) {
    if (!r.qty || Number(r.qty) <= 0) {
      errors.value.details = `商品 ${r.goodsName} 的入库数量必须大于 0`
      return false
    }
    if (r.productionDate && !/^\d{4}-\d{2}-\d{2}$/.test(r.productionDate)) {
      errors.value.details = `商品 ${r.goodsName} 的生产日期需为 YYYY-MM-DD 格式`
      return false
    }
  }
  // 同商品 + 同批次不可重复
  const seen = new Map()
  for (const r of rows) {
    const key = `${r.goodsCode}|${r.batchNo || ''}`
    if (seen.has(key)) {
      errors.value.details = `商品 ${r.goodsName}（批次 ${r.batchNo || '无'}）重复`
      return false
    }
    seen.set(key, true)
  }
  return true
}

function buildPayload(status) {
  return {
    billDate: headerForm.value.billDate,
    customer: headerForm.value.customer,
    supplier: headerForm.value.supplier,
    inboundType: headerForm.value.inboundType,
    warehouse: headerForm.value.warehouse,
    status,
    remark: headerForm.value.remark,
    details: filledRows.value.map(r => ({
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
    })),
  }
}

/** 保存：无草稿态，一律保存为「未审核」（PENDING），等待审核 */
async function saveInbound() {
  if (!validate()) return
  loading.value = true
  try {
    const payload = buildPayload('PENDING')
    if (isEdit.value) {
      payload.inboundId = props.editData.inboundId
      await post('/inventory/other-inbound/update', payload)
    } else {
      await post('/inventory/other-inbound/create', payload)
    }
    emit('save', { status: 'PENDING' })
  } catch (e) {
    errors.value.header = e.message || '保存失败'
  } finally {
    loading.value = false
  }
}

async function handleAudit() {
  if (!confirm('确认审核该其他入库单？审核后将增加库存并记入成本。')) return
  loading.value = true
  try {
    const res = await post('/inventory/other-inbound/audit', { bizId: props.editData.inboundId })
    inboundStatus.value = 'APPROVED'
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
  if (!confirm('确认反审核该其他入库单？将扣回此前入库的库存。')) return
  loading.value = true
  try {
    const res = await post('/inventory/other-inbound/reverse-audit', { bizId: props.editData.inboundId })
    inboundStatus.value = 'PENDING'
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
  if (!confirm('确认作废该其他入库单？此操作不可逆，已审核的将自动扣回库存。')) return
  loading.value = true
  try {
    const res = await post('/inventory/other-inbound/cancel', { bizId: props.editData.inboundId })
    inboundStatus.value = 'CANCELLED'
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
  <div v-if="visible" class="oib-drawer-mask">
    <div class="oib-drawer-box">
      <!-- 头部 -->
      <div class="oib-drawer-head">
        <b>{{ isEdit ? '编辑其他入库单' : '新建其他入库单' }}</b>
        <span v-if="isEdit && props.editData?.inboundNo" class="bill-no">{{ props.editData.inboundNo }}</span>
        <span v-if="isEdit" class="status-tag" :class="'st-' + inboundStatus.toLowerCase()">{{ statusText }}</span>
        <span style="flex:1"></span>
        <button v-if="canEdit" class="btn primary" @click="saveInbound" :disabled="loading">保存</button>
        <button v-if="isEdit && inboundStatus === 'PENDING'" class="btn primary" @click="handleAudit" :disabled="loading">审核</button>
        <button v-if="isEdit && inboundStatus === 'APPROVED'" class="btn" @click="handleReverseAudit" :disabled="loading">取消审核</button>
        <button v-if="isEdit && inboundStatus !== 'CANCELLED'" class="btn danger" @click="handleCancel" :disabled="loading">作废</button>
        <button class="btn" @click="handleClose">关闭</button>
      </div>

      <!-- 主体 -->
      <div class="oib-drawer-body">
        <div v-if="!canEdit && isEdit" class="readonly-banner">{{ readonlyReason }}</div>

        <!-- 头部信息 -->
        <div class="card">
          <div class="card-title">单据信息</div>
          <div v-if="errors.header" class="err-line">{{ errors.header }}</div>
          <div class="grid3">
            <div class="field">
              <label>单据日期 <span class="req">*</span></label>
              <input v-if="canEdit" type="date" v-model="headerForm.billDate" />
              <span v-else>{{ headerForm.billDate }}</span>
            </div>
            <div class="field">
              <label>客户 <span class="hint">（与供应商二选一）</span></label>
              <input v-if="canEdit" list="oib-cust-list" v-model="headerForm.customer"
                :disabled="!!headerForm.supplier" @change="onCustomerChange" placeholder="请选择客户" />
              <span v-else>{{ headerForm.customer || '-' }}</span>
              <datalist id="oib-cust-list">
                <option v-for="c in customerOptions" :key="c" :value="c" />
              </datalist>
            </div>
            <div class="field">
              <label>供应商 <span class="hint">（与客户二选一）</span></label>
              <input v-if="canEdit" list="oib-supp-list" v-model="headerForm.supplier"
                :disabled="!!headerForm.customer" @change="onSupplierChange" placeholder="请选择供应商" />
              <span v-else>{{ headerForm.supplier || '-' }}</span>
              <datalist id="oib-supp-list">
                <option v-for="s in supplierOptions" :key="s" :value="s" />
              </datalist>
            </div>
            <div class="field">
              <label>其他入库类型 <span class="req">*</span></label>
              <select v-if="canEdit" v-model="headerForm.inboundType">
                <option value="">请选择</option>
                <option v-for="t in inboundTypeOptions" :key="t" :value="t">{{ t }}</option>
              </select>
              <span v-else>{{ headerForm.inboundType || '-' }}</span>
            </div>
            <div class="field">
              <label>仓库 <span class="req">*</span></label>
              <input v-if="canEdit" list="oib-wh-list" v-model="headerForm.warehouse"
                @change="onWarehouseChange" placeholder="请选择仓库" />
              <span v-else>{{ headerForm.warehouse }}</span>
              <datalist id="oib-wh-list">
                <option v-for="w in warehouseOptions" :key="w" :value="w" />
              </datalist>
            </div>
            <div class="field">
              <label>备注</label>
              <input v-if="canEdit" v-model="headerForm.remark" placeholder="备注" />
              <span v-else>{{ headerForm.remark }}</span>
            </div>
          </div>
        </div>

        <!-- 明细：可直接在列表里录入（商品列内联搜索 + 末尾常驻空行） -->
        <div class="card detail-card">
          <div class="detail-toolbar">
            <span class="card-title">入库明细</span>
            <span class="toolbar-hint">
              商品列直接输入 编号/名称/简拼/条码 即可添加 ·
              ↑↓ 上下切换 · 回车向右切换，行末回车跳下一行
            </span>
            <button v-if="canEdit" class="btn primary sm" @click="openGoodsPicker">+ 添加商品</button>
          </div>
          <div v-if="errors.details" class="err-line">{{ errors.details }}</div>
          <div class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:36px">#</th>
                  <th style="width:150px">商品 <span v-if="canEdit" class="req">*</span></th>
                  <th style="min-width:150px">商品名称</th>
                  <th style="width:80px">规格</th>
                  <th style="width:52px">单位</th>
                  <th style="width:62px">数量 <span v-if="canEdit" class="req">*</span></th>
                  <th style="width:72px">单价</th>
                  <th style="width:88px">金额</th>
                  <th style="width:150px">批次号</th>
                  <th style="width:112px">生产日期</th>
                  <th style="width:76px">当前库存</th>
                  <th style="width:96px" title="审核时按当前库存成本计价并回写；审核不重算库存成本，故不可手工编辑">成本单价 ⓘ</th>
                  <th style="width:88px">成本金额</th>
                  <th v-if="canEdit" style="width:46px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="loading && detailList.length === 0">
                  <td :colspan="canEdit ? 14 : 13" class="empty-detail">加载中...</td>
                </tr>
                <tr v-else-if="detailList.length === 0">
                  <td :colspan="canEdit ? 14 : 13" class="empty-detail">无入库明细</td>
                </tr>
                <tr v-for="(row, idx) in detailList" :key="idx" :class="{ 'blank-row': !row.goodsCode }">
                  <td>{{ row.goodsCode ? idx + 1 : '+' }}</td>

                  <!-- 商品：内联搜索选择器，选中即就地填充本行 -->
                  <td class="cell-pad">
                    <InlineGoodsPicker
                      v-if="canEdit"
                      :ref="bindCell(idx, 'goods')"
                      v-model="row.goodsSearch"
                      :goods-list="allGoods"
                      :existing-codes="filledRows.map(r => r.goodsCode)"
                      :disabled="!headerForm.warehouse"
                      @select="g => onRowGoodsSelect(row, idx, g)"
                      @select-multi="list => onRowGoodsSelectMulti(row, idx, list)"
                      @nav="action => onGoodsNav(idx, action)" />
                    <span v-else>{{ row.goodsCode }}</span>
                  </td>

                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.spec }}</td>
                  <td>{{ row.unitName }}</td>
                  <td>
                    <input v-if="canEdit" :ref="bindCell(idx, 'qty')"
                      type="number" min="0" step="any" v-model.number="row.qty"
                      :disabled="!row.goodsCode"
                      @input="onQtyChange(idx)"
                      @keydown="onCellKeyDown($event, idx, 'qty')"
                      class="cell-input num-input" />
                    <span v-else>{{ row.qty }}</span>
                  </td>
                  <td>
                    <input v-if="canEdit" :ref="bindCell(idx, 'price')"
                      type="number" min="0" step="0.0001" v-model.number="row.price"
                      :disabled="!row.goodsCode"
                      @input="onPriceChange(idx)"
                      @keydown="onCellKeyDown($event, idx, 'price')"
                      class="cell-input num-input" />
                    <span v-else>{{ Number(row.price).toFixed(4) }}</span>
                  </td>
                  <td class="num">{{ Number(row.amount || 0).toFixed(2) }}</td>
                  <td class="batch-cell" style="position:relative">
                    <template v-if="canEdit">
                      <input :ref="bindCell(idx, 'batchNo')"
                        v-model="row.batchNo"
                        :disabled="!row.goodsCode"
                        placeholder="批次号"
                        class="cell-input batch-input"
                        @input="onBatchNoInput(row)"
                        @keydown="onCellKeyDown($event, idx, 'batchNo')" />
                      <button class="batch-btn" :disabled="!row.goodsCode"
                        @click="openBatchPicker(idx)" title="选择已有批次">…</button>
                    </template>
                    <span v-else>{{ row.batchNo || '-' }}</span>
                    <!-- 已有批次下拉 -->
                    <div v-if="batchEditingIndex === idx && (batchCache[batchKey(row.goodsCode)] || []).length > 0"
                      class="batch-dropdown">
                      <div v-for="b in batchCache[batchKey(row.goodsCode)] || []" :key="b.batchNo"
                        class="batch-item" :class="{ taken: isBatchTaken(b, idx) }"
                        @click="pickBatch(idx, b)">
                        <div><b>{{ b.batchNo || '(空批次)' }}</b></div>
                        <div>生产日期: {{ b.productionDate || '-' }}</div>
                        <div>现有: {{ b.availableQty }} | 成本: {{ b.costPrice }}</div>
                      </div>
                    </div>
                  </td>
                  <td>
                    <!-- 文本输入而非 type=date：原生日期框的显示格式随系统语言变化，无法固定成 YYYY-MM-DD -->
                    <input v-if="canEdit" :ref="bindCell(idx, 'productionDate')"
                      v-model="row.productionDate"
                      :disabled="!row.goodsCode"
                      placeholder="YYYY-MM-DD" maxlength="10"
                      class="cell-input date-input"
                      @blur="onProductionDateBlur(row)"
                      @keydown="onCellKeyDown($event, idx, 'productionDate')" />
                    <span v-else>{{ row.productionDate || '-' }}</span>
                  </td>
                  <td class="num">{{ row.currentStock }}</td>
                  <!-- 成本单价只读：以审核时点成本为准，手改无意义 -->
                  <td class="num readonly-cell">{{ Number(row.costPrice || 0).toFixed(6) }}</td>
                  <td class="num">{{ Number(row.costAmount || 0).toFixed(2) }}</td>
                  <td v-if="canEdit">
                    <button v-if="row.goodsCode" class="link danger-link" @click="removeRow(idx)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 汇总 -->
        <div class="summary-line">
          <span>合计数量：<b>{{ totalQty }}</b></span>
          <span>出库金额：<b>¥{{ totalAmount }}</b></span>
          <span>合计成本金额：<b>¥{{ totalCostAmount }}</b></span>
          <span>行数：<b>{{ filledRows.length }}</b></span>
        </div>
      </div>

      <!-- 商品选择弹窗：表格多选，形态参照客户价格调整的商品选择器 -->
      <Teleport to="body">
        <OtherInboundGoodsDialog
          :visible="showGoodsPicker"
          :goods-list="enrichedGoods"
          :existing-codes="detailList.map(r => r.goodsCode)"
          :warehouse="headerForm.warehouse"
          @confirm="onPickerConfirm"
          @close="showGoodsPicker = false"
        />
      </Teleport>
    </div>
  </div>
</template>

<style scoped>
.oib-drawer-mask {
  position: fixed; top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900; display: flex; pointer-events: none;
}
.oib-drawer-box {
  flex: 1; display: flex; flex-direction: column;
  background: #fff; pointer-events: auto;
  box-shadow: -4px 0 20px rgba(0,0,0,.08);
}
.oib-drawer-head {
  height: 46px; display: flex; align-items: center; gap: 8px;
  padding: 0 16px; border-bottom: 1px solid #e8e8e8;
  background: #fafafa; flex-shrink: 0;
}
.oib-drawer-head b { font-size: 15px; }
.bill-no { font-size: 12px; color: #888; }
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

.oib-drawer-body {
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
.field .hint { color: #bbb; font-size: 11px; }
.field input, .field select {
  padding: 5px 8px; border: 1px solid #d9d9d9; border-radius: 4px; font-size: 13px;
}
.field input:disabled { background: #f5f5f5; color: #bbb; cursor: not-allowed; }

.err-line { color: #ff4d4f; font-size: 12px; margin-bottom: 8px; }

.detail-card { flex: 1; min-height: 200px; display: flex; flex-direction: column; }
.detail-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 8px;
}
.detail-toolbar .card-title { margin-bottom: 0; }
.toolbar-hint { flex: 1; margin-left: 10px; font-size: 12px; color: #a8b3c0; }
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
  padding: 2px 5px; font-size: 12px; cursor: pointer; color: #2f54eb;
  margin-left: 2px;
}
.batch-btn:hover:not(:disabled) { background: #e6f0ff; }
.batch-btn:disabled { opacity: .4; cursor: not-allowed; }
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

/* 明细单元格输入框：统一高度，宽度撑满单元格 */
.cell-pad { padding: 3px 4px !important; }
.cell-input {
  width: 100%; height: 24px; padding: 0 4px;
  border: 1px solid #dcdfe6; border-radius: 3px; font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.cell-input:focus { outline: none; border-color: #409eff; box-shadow: 0 0 0 2px rgba(64,158,255,.15); }
.cell-input:disabled { background: #f5f7fa; color: #c0c4cc; cursor: not-allowed; }
.num-input { text-align: right; }
.date-input { font-family: var(--font-mono); }
/* 批次号：输入框与「…」按钮共处一格，给按钮留出宽度 */
.batch-input { width: calc(100% - 26px); }
/* 只读列（成本单价）用浅底标出不可编辑 */
.readonly-cell { background: #fafbfc; color: #606266; }
/* 末尾空行：弱化行号与背景，提示这是「新增行」 */
.blank-row td { background: #fcfcfd; }
.blank-row td:first-child { color: #409eff; font-weight: 700; text-align: center; }

@media (max-width: 900px) {
  .oib-drawer-mask { left: 0; }
}
</style>
