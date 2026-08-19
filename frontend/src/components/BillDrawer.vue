<script setup>
import { ref, watch, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { post, get } from '../api/client.js'
import GoodsAddDialog from './GoodsAddDialog.vue'
import InlineGoodsPicker from './InlineGoodsPicker.vue'
import { clampDecimalInput, clampQtyInput, roundTo, PRICE_DECIMALS, AMOUNT_DECIMALS } from '../utils/decimal.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' },
  moduleCode: { type: String, required: true },
  editData: { type: Object, default: null },
})

const emit = defineEmits(['close', 'save'])

const isPurchase = computed(() => props.moduleCode === 'purchaseOrder')
const title = computed(() => {
  const t = isPurchase.value ? '采购订单' : '销售订单'
  return props.mode === 'edit' ? `编辑${t}` : `新建${t}`
})

const unitList = ref([])
const warehouseList = ref([])
const partnerList = ref([])       // 供应商 / 客户
const buyerList = ref([])         // 采购员（人员信息里 is_buyer=true）
const salesmanList = ref([])      // 业务员（人员信息里 is_salesman=true）

const headerForm = ref({})
const detailList = ref([])
const formErrors = ref({})

// 商品添加窗口（与表格内联录入共存，习惯弹窗的用户不受影响）
const goodsAddOpen = ref(false)

// ==================== 表格内联录入 ====================
/** 全量商品：编辑模式回显时补 isWeighted/unitConfig 用（已存明细的商品可能不在候选 top20 里） */
const allGoods = ref([])

/**
 * 商品下拉候选（仅销售订单）：
 * 优先展示最近一年有交易的商品，按销量高→低，默认前 20 条；
 * 输入关键字则在全部正常商品里模糊查询，命中结果同样按此规则排序。
 * 销量按「当前客户」统计，未选客户时回退全店。
 */
const rankedGoods = ref([])
const goodsLoading = ref(false)
const RANK_LIMIT = 20

/** 单元格 input 的 ref 表，键为 `${rowIndex}:${colKey}` */
const cellRefs = ref({})

/** 明细行的可编辑列顺序 —— 回车按此顺序在同行内推进 */
const editableCols = computed(() =>
  isPurchase.value
    ? ['goods', 'unit', 'qty', 'price', 'amount', 'remark']
    : ['goods', 'unit', 'qty', 'price', 'amount', 'salesAttribute', 'remark'])

/** 新建一个空明细行 */
function makeEmptyRow() {
  return {
    goodsCode: '', goodsName: '', spec: '', barcode: '',
    smallUnitName: '', purchaseUnitName: '', purchaseUnitLevel: 1, convertQty: 1,
    qty: null, smallQty: 0, price: null, amount: null,
    supplierLatestPrice: 0, systemLatestPrice: 0,
    salesAttribute: '正常', remark: '',
    // 仅前端使用，不提交后端
    availableStock: null,   // 当前仓库可用库存（销售订单专用，null=未知/未取到）
    isWeighted: false,      // 称重品 → 数量允许 3 位小数
    unitConfig: null,       // 原始多单位配置，派生单位下拉与取价
    goodsSearch: '',        // 该行商品搜索框关键字
    priceAutoFilled: false, // 单价是否系统带出（手改后为 false，切单位不再覆盖）
  }
}

/** 已填商品的行 —— 校验、保存、合计一律只认这些行，自动空行必须排除 */
const filledRows = computed(() => detailList.value.filter(r => r.goodsCode))

// ==================== 可用库存（仅销售订单） ====================
/**
 * 批量取「当前仓库」的可用库存并回填到对应明细行。
 * 口径与后端一致：可用 = 实物 − 锁定 − 冻结；查不到库存行按 0 处理。
 * 采购订单不需要（入库是加库存），仓库未选时也无从查询。
 */
async function loadAvailableStock(codes) {
  if (isPurchase.value) return
  const warehouse = headerForm.value.warehouseId
  const list = [...new Set((codes || []).filter(Boolean))]
  if (!warehouse || list.length === 0) return
  try {
    const rows = await post('/inventory/available-stock', { warehouse, goodsCodes: list })
    const byCode = new Map((Array.isArray(rows) ? rows : []).map(r => [r.goodsCode, Number(r.availableQty) || 0]))
    detailList.value.forEach(r => {
      if (list.includes(r.goodsCode)) r.availableStock = byCode.get(r.goodsCode) ?? 0
    })
  } catch (_) { /* 取库存失败不阻断录单，保存时后端仍会拦 */ }
}

/** 仓库变了 → 所有已填行的可用库存都要重取（同一商品不同仓库库存不同） */
async function onWarehouseChange(warehouseName) {
  headerForm.value.warehouseId = warehouseName
  if (isPurchase.value) return
  if (!warehouseName) {
    detailList.value.forEach(r => { r.availableStock = null })
    return
  }
  await loadAvailableStock(filledRows.value.map(r => r.goodsCode))
}

/** 某行是否超卖（用于标红提示，不阻断输入） */
function isOverStock(row) {
  if (isPurchase.value || !row.goodsCode || row.availableStock == null) return false
  return stockNeedOf(row.goodsCode) > Number(row.availableStock)
}

/** 同一商品可能录在多行（不同销售属性），需求量按商品汇总 —— 赠品/样品等一律计入 */
function stockNeedOf(goodsCode) {
  return filledRows.value
    .filter(r => r.goodsCode === goodsCode)
    .reduce((sum, r) => sum + (Number(r.qty) || 0), 0)
}

function bindCell(rowIndex, colKey) {
  return el => {
    const k = `${rowIndex}:${colKey}`
    if (el) cellRefs.value[k] = el
    else delete cellRefs.value[k]
  }
}

/**
 * 加载候选商品（最近一年有交易优先 + 销量降序，限 20 条）。
 * 采购订单不走此逻辑（没有「销售量」概念），仍用全量商品列表。
 */
async function loadRankedGoods(keyword = '') {
  if (isPurchase.value) { rankedGoods.value = allGoods.value; return }
  goodsLoading.value = true
  try {
    const rows = await post('/base/goods/sale-ranking', {
      customerCode: headerForm.value.customerCode || '',
      keyword,
      limit: RANK_LIMIT,
    })
    rankedGoods.value = Array.isArray(rows) ? rows : []
  } catch (_) {
    rankedGoods.value = []
  } finally {
    goodsLoading.value = false
  }
}

function resetForm() {
  if (isPurchase.value) {
    headerForm.value = {
      supplierCode: '', supplierName: '',
      buyer: '', warehouseId: '',
      billDate: new Date().toISOString().slice(0, 10),
    }
  } else {
    headerForm.value = {
      customerCode: '', customerName: '',
      salesman: '', priceGroupCode: '', warehouseId: '',
      billDate: new Date().toISOString().slice(0, 10),
      expectedDeliveryDate: '',
    }
  }
  // 进入即给一空行，用户可直接在表格里录入
  detailList.value = [makeEmptyRow()]
  formErrors.value = {}
  rankedGoods.value = []
}

const totalAmount = computed(() => {
  // 只累加已填商品的行，空行不参与
  return filledRows.value.reduce((sum, r) => sum + (Number(r.amount) || 0), 0).toFixed(2)
})

watch(() => props.visible, async (val) => {
  if (val) {
    resetForm()
    await loadBaseData()
    if (props.mode === 'edit' && props.editData) {
      await loadEditData(props.editData)
    }
    // 候选商品要在客户带出后再取（销量按客户统计）
    await loadRankedGoods()
  }
})

/** 编辑模式：调 detail 端点回显头部 + 明细 */
async function loadEditData(row) {
  const raw = row?._raw || row || {}
  const key = raw.orderId || raw.orderNo || raw.orderno || raw.orderid
  if (!key) return
  try {
    const path = isPurchase.value ? '/purchase/order/detail' : '/sales/order/detail'
    const detail = await get(`${path}?orderId=${encodeURIComponent(key)}`)
    if (!detail) return
    // 头部回显（后端 camelize 已经小驼峰）
    if (isPurchase.value) {
      headerForm.value = {
        orderId: detail.orderId || key,
        orderNo: detail.orderNo || '',
        supplierCode: detail.supplierCode || '',
        supplierName: detail.supplierName || '',
        buyer: detail.buyer || '',
        warehouseId: detail.warehouse || '',
        billDate: (detail.billDate || '').slice(0, 10) || new Date().toISOString().slice(0, 10),
        remark: detail.remark || '',
      }
    } else {
      headerForm.value = {
        orderId: detail.orderId || key,
        orderNo: detail.orderNo || '',
        customerCode: detail.customerCode || '',
        customerName: detail.customer || detail.customerName || '',
        salesman: detail.salesman || '',
        priceGroupCode: detail.priceGroupCode || '',
        warehouseId: detail.warehouse || '',
        billDate: (detail.billDate || '').slice(0, 10) || new Date().toISOString().slice(0, 10),
        expectedDeliveryDate: (detail.expectedDeliveryDate || '').slice(0, 10),
        remark: detail.remark || '',
      }
    }
    // 明细回显：把后端字段映射回前端行结构，并补齐内联编辑要用的字段
    detailList.value = (detail.details || []).map(d => {
      // 从商品档案补 称重标记 / 多单位配置（后端明细不存这两项）
      const g = allGoods.value.find(x => x.goodsCode === d.goodsCode)
      return {
        ...makeEmptyRow(),
        goodsCode: d.goodsCode || '',
        goodsName: d.goodsName || '',
        spec: d.spec || '',
        barcode: g?.barcode || '',
        purchaseUnitName: d.unitName || '',
        purchaseUnitLevel: Number(d.unitLevel || 1),
        convertQty: Number(d.convertQty || 1),
        qty: Number(d.qty || 0),
        smallQty: Number(d.baseQty || 0),
        price: Number(d.price || 0),
        amount: Number(d.amount || 0),
        salesAttribute: d.salesAttribute || d.lineType || '正常',
        remark: d.remark || '',
        isWeighted: !!g?.isWeighted,
        unitConfig: g?.unitConfig ?? null,
        goodsSearch: d.goodsCode || '',
        priceAutoFilled: false,   // 已存盘的价视为手工价，切单位不擅自覆盖
      }
    })
    // 末尾补空行，编辑时也能继续加商品
    ensureTrailingBlankRow()
    // 回显后按单据仓库补齐可用库存
    await loadAvailableStock(detailList.value.map(r => r.goodsCode))
  } catch (e) {
    alert('加载订单详情失败：' + (e.message || '未知错误'))
  }
}

async function loadBaseData() {
  const [u, w, g] = await Promise.all([
    post('/base/unit/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => ({ records: [] })),
    post('/base/warehouse/page', { pageNo: 1, pageSize: 100, filters: {} }).catch(() => ({ records: [] })),
    // 商品列表供内联选择器共用（简拼搜索在前端过滤，后端 keyword 也已支持）
    post('/base/goods/page', { pageNo: 1, pageSize: 2000, filters: {} }).catch(() => ({ records: [] })),
  ])
  unitList.value = u.records || []
  warehouseList.value = w.records || []
  allGoods.value = (g.records || []).filter(x => String(x.status || '').toUpperCase() !== 'STOPPED')

  try {
    const partnerApi = isPurchase.value ? '/base/supplier/page' : '/base/customer/page'
    const p = await post(partnerApi, { pageNo: 1, pageSize: 500, filters: {} })
    partnerList.value = p.records || []
  } catch (e) { partnerList.value = [] }

  if (isPurchase.value) {
    try {
      const b = await post('/base/employee/buyers', {})
      const list = Array.isArray(b) ? b : (b?.records || [])
      // 兼容 H2 大写返回：CODE/NAME
      buyerList.value = list.map(item => ({
        code: item.code || item.CODE || item.employeeCode || '',
        name: item.name || item.NAME || item.employeeName || '',
      })).filter(item => item.name)
    } catch (e) { buyerList.value = [] }
  } else {
    try {
      const s = await post('/base/employee/salesmen', {})
      const list = Array.isArray(s) ? s : (s?.records || [])
      salesmanList.value = list.map(item => ({
        code: item.code || item.CODE || item.employeeCode || '',
        name: item.name || item.NAME || item.employeeName || '',
      })).filter(item => item.name)
    } catch (e) { salesmanList.value = [] }
  }
}

// 选择供应商 → 自动带出默认采购员
async function onSupplierChange(code) {
  headerForm.value.supplierCode = code
  const hit = partnerList.value.find(p => (p.supplierCode || p.supplierId) === code || p.supplierName === code)
  headerForm.value.supplierName = hit?.supplierName || ''

  // 查详情拿 defaultBuyer
  try {
    const detail = await get(`/base/supplier/detail?code=${encodeURIComponent(code)}`)
    const buyer = detail?.defaultBuyer
    if (buyer) headerForm.value.buyer = buyer
  } catch (_) { /* 忽略 */ }
}

// 选择客户 → 自动带出业务员和价格组
function onCustomerChange(code) {
  headerForm.value.customerCode = code
  const hit = partnerList.value.find(p => (p.customerCode || p.customerId) === code)
  if (hit) {
    headerForm.value.customerName = hit.customerName || ''
    if (hit.salesman) headerForm.value.salesman = hit.salesman
    if (hit.priceGroupCode) headerForm.value.priceGroupCode = hit.priceGroupCode
  }
  // 换客户 → 销量排名随之变化，重取候选商品
  loadRankedGoods()
}

function openGoodsAdd() {
  if (isPurchase.value && !headerForm.value.supplierCode) {
    alert('请先选择供应商')
    return
  }
  if (!isPurchase.value && !headerForm.value.customerCode) {
    alert('请先选择客户')
    return
  }
  goodsAddOpen.value = true
}

function onGoodsAdded(row) {
  // 弹窗加进来的行需补齐内联编辑要用的字段，否则表格里改数量拿不到称重标记
  const g = allGoods.value.find(x => x.goodsCode === row.goodsCode)
  const full = {
    ...makeEmptyRow(),
    ...row,
    isWeighted: !!g?.isWeighted,
    unitConfig: g?.unitConfig ?? null,
    goodsSearch: row.goodsCode || '',
    priceAutoFilled: false,   // 弹窗里的价已由用户确认过，视为手工价
  }
  // 优先填入末尾空行，避免空行夹在中间
  const blankIdx = detailList.value.findIndex(r => !r.goodsCode)
  if (blankIdx >= 0) detailList.value.splice(blankIdx, 1, full)
  else detailList.value.push(full)
  ensureTrailingBlankRow()
  loadAvailableStock([full.goodsCode])
}

function removeRow(index) {
  detailList.value.splice(index, 1)
  // 删到空了或末尾不是空行 → 补一空行，保证始终可继续录入
  ensureTrailingBlankRow()
}

/** 保证末尾始终有且仅有一个空行 */
function ensureTrailingBlankRow() {
  const list = detailList.value
  if (list.length === 0 || list[list.length - 1].goodsCode) {
    list.push(makeEmptyRow())
  }
}

// ==================== 单位 / 取价 / 计算 ====================

/** 解析商品 unit_config（索引 0=小 1=中 2=大），只保留启用且有名称的单位 */
function parseUnitConfig(raw) {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try { return JSON.parse(String(raw)) } catch (_) { return [] }
}

/** 某行可选的单位列表 */
function unitOptionsOf(row) {
  const cfg = parseUnitConfig(row.unitConfig)
  const labels = ['小单位', '中单位', '大单位']
  const opts = []
  for (let i = 0; i < 3; i++) {
    const u = cfg[i]
    // 小单位恒可用；中/大单位需 enabled
    const enabled = i === 0 ? true : (u && u.enabled !== false)
    if (u && enabled && u.unitName) {
      opts.push({
        level: i + 1, label: labels[i], name: u.unitName,
        convertQty: Number(u.convertQty) || 1,
        standardPrice: Number(u.standardPrice) || 0,
      })
    }
  }
  return opts
}

/**
 * 按取价逻辑给某行带出单价。
 * 销售：调 /base/goods/sale-price（客户商品价 > 客户价格组价 > 商品标价）
 * 采购：沿用商品档案该单位的 standardPrice
 * 手改过价（priceAutoFilled=false 且已有价）则不覆盖。
 */
async function applyRowPrice(row) {
  if (!row.goodsCode) return
  const hasManualPrice = !row.priceAutoFilled && Number(row.price) > 0
  if (hasManualPrice) return

  if (isPurchase.value) {
    const u = unitOptionsOf(row).find(o => o.level === row.purchaseUnitLevel)
    if (u && u.standardPrice > 0) {
      row.price = u.standardPrice
      row.priceAutoFilled = true
    }
  } else {
    try {
      const r = await get(`/base/goods/sale-price?goodsCode=${encodeURIComponent(row.goodsCode)}`
        + `&customerCode=${encodeURIComponent(headerForm.value.customerCode || '')}`
        + `&unitLevel=${row.purchaseUnitLevel}`)
      const p = Number(r?.price) || 0
      if (p > 0) {
        row.price = p
        row.priceAutoFilled = true
        row.priceSourceText = r?.priceSourceText || ''
      }
    } catch (_) { /* 取价失败不阻断录单 */ }
  }
  recalcRow(row)
}

/** 内联选择器选中单个商品 → 写入该行 */
async function onRowGoodsSelect(row, index, g) {
  row.goodsCode = g.goodsCode
  row.goodsName = g.goodsName || ''
  row.spec = g.spec || ''
  row.barcode = g.barcode || ''
  row.isWeighted = !!g.isWeighted
  row.unitConfig = g.unitConfig ?? null
  row.goodsSearch = g.goodsCode
  row.priceAutoFilled = false
  row.price = null

  // 销售默认小→中→大，采购默认大→中→小（与 GoodsAddDialog 口径一致）
  const opts = unitOptionsOf(row)
  const def = isPurchase.value
    ? (opts.find(o => o.level === 3) || opts.find(o => o.level === 2) || opts[0])
    : (opts.find(o => o.level === 1) || opts[0])
  if (def) {
    row.purchaseUnitName = def.name
    row.purchaseUnitLevel = def.level
    row.convertQty = def.convertQty
    row.smallUnitName = opts.find(o => o.level === 1)?.name || g.baseUnit || ''
  } else {
    // 没配多单位 → 退回商品基本单位
    row.purchaseUnitName = g.baseUnit || ''
    row.purchaseUnitLevel = 1
    row.convertQty = 1
    row.smallUnitName = g.baseUnit || ''
  }
  if (row.qty == null) row.qty = 1

  await applyRowPrice(row)
  ensureTrailingBlankRow()
  loadAvailableStock([g.goodsCode])
  // 选完商品把光标推到数量，符合录单习惯
  await nextTick()
  focusCell(index, 'qty')
}

/** 内联选择器批量添加（鼠标多选） */
async function onRowGoodsSelectMulti(row, index, list) {
  if (!list?.length) return
  // 第一个填入当前行，其余插到后面
  await onRowGoodsSelect(row, index, list[0])
  for (let i = 1; i < list.length; i++) {
    const nr = makeEmptyRow()
    detailList.value.splice(index + i, 0, nr)
    await onRowGoodsSelect(nr, index + i, list[i])
  }
  ensureTrailingBlankRow()
}

/** 改单位 → 换算率与价格都要跟着换（需求：按单位对应取价） */
async function onRowUnitChange(row, level) {
  const u = unitOptionsOf(row).find(o => o.level === Number(level))
  if (!u) return
  row.purchaseUnitName = u.name
  row.purchaseUnitLevel = u.level
  row.convertQty = u.convertQty
  // 切单位后原价格不再适用：允许被新单位取价覆盖
  row.priceAutoFilled = true
  await applyRowPrice(row)
  recalcRow(row)
}

/** 数量：称重品 ≤3 位小数，非称重品仅整数 */
function onRowQtyInput(row, e) {
  const { text, value } = clampQtyInput(e.target.value, row.isWeighted)
  e.target.value = text
  row.qty = value
  recalcRow(row)
}

/** 单价：≤4 位小数 */
function onRowPriceInput(row, e) {
  const { text, value } = clampDecimalInput(e.target.value, PRICE_DECIMALS)
  e.target.value = text
  row.price = value
  row.priceAutoFilled = false   // 手改价，之后切单位不覆盖
  recalcRow(row)
}

/** 金额：2 位小数；改金额反算单价 */
function onRowAmountInput(row, e) {
  const { text, value } = clampDecimalInput(e.target.value, AMOUNT_DECIMALS)
  e.target.value = text
  row.amount = value
  const q = Number(row.qty) || 0
  if (q > 0 && value != null) {
    row.price = roundTo(value / q, PRICE_DECIMALS)
    row.priceAutoFilled = false
  }
}

/** 金额 = 数量 × 单价；小单位数量 = 数量 × 换算率 */
function recalcRow(row) {
  const q = Number(row.qty) || 0
  const p = Number(row.price) || 0
  row.amount = roundTo(q * p, AMOUNT_DECIMALS)
  row.smallQty = roundTo(q * (Number(row.convertQty) || 1), 4)
}

// ==================== 单元格键盘导航 ====================

function focusCell(rowIndex, colKey) {
  const el = cellRefs.value[`${rowIndex}:${colKey}`]
  if (!el) return
  // InlineGoodsPicker 暴露的是组件实例，用它的 focus()
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
 * 已在行末字段 → 跳下一行首字段；最后一行则先补空行再跳。
 */
async function nextField(rowIndex, colKey) {
  const cols = editableCols.value
  const i = cols.indexOf(colKey)
  if (i < 0) return
  if (i < cols.length - 1) {
    focusCell(rowIndex, cols[i + 1])
    return
  }
  // 行末：跳下一行
  if (rowIndex === detailList.value.length - 1) {
    ensureTrailingBlankRow()
    await nextTick()
  }
  focusCell(rowIndex + 1, cols[0])
}

/** 单元格通用键盘处理（商品列除外，它有自己的下拉导航） */
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

function validate() {
  const errors = {}
  const h = headerForm.value
  if (isPurchase.value) {
    if (!h.supplierCode) errors.supplier = '请选择供应商'
    if (!h.buyer) errors.buyer = '请选择采购员'
  } else {
    if (!h.customerCode) errors.customer = '请选择客户'
    if (!h.salesman) errors.salesman = '请选择业务员'
  }
  if (!h.warehouseId) errors.warehouse = '请选择仓库'
  // 只认已填商品的行 —— 末尾自动空行不算明细
  const rows = filledRows.value
  if (rows.length === 0) errors.details = '请至少添加一条商品明细'
  else {
    // 逐行校验：数量必须为正，单价不能为负
    const bad = rows.find(r => !(Number(r.qty) > 0))
    if (bad) errors.details = `商品【${bad.goodsCode}】请填写数量`
    else {
      const badPrice = rows.find(r => Number(r.price) < 0 || r.price == null)
      if (badPrice) errors.details = `商品【${badPrice.goodsCode}】请填写单价`
      else {
        // 库存校验（仅销售）：同商品多行合并后与可用库存比对，超出则不允许保存
        const short = firstShortage()
        if (short) errors.details = short
      }
    }
  }
  formErrors.value = errors
  return Object.keys(errors).length === 0
}

/**
 * 返回第一条库存不足的提示，全部够则返回空。
 * 只在已取到可用库存（availableStock 非 null）时判断；取不到时交由后端拦截，
 * 避免网络抖动导致明明有货却存不了单。
 */
function firstShortage() {
  if (isPurchase.value) return ''
  const seen = new Set()
  for (const r of filledRows.value) {
    if (seen.has(r.goodsCode) || r.availableStock == null) continue
    seen.add(r.goodsCode)
    const need = stockNeedOf(r.goodsCode)
    const available = Number(r.availableStock)
    if (need > available) {
      return `商品【${r.goodsCode} ${r.goodsName}】库存不足：本单需 ${need}，`
        + `${headerForm.value.warehouseId || '当前仓库'} 可用 ${available}`
    }
  }
  return ''
}

async function saveBill() {
  if (!validate()) {
    const first = Object.values(formErrors.value)[0]
    if (first) alert(first)
    return
  }
  // 编辑模式走 /update，需带 orderId；新建走 /create
  const isEdit = props.mode === 'edit'
  const apiPath = isPurchase.value
      ? (isEdit ? '/purchase/order/update' : '/purchase/order/create')
      : (isEdit ? '/sales/order/update' : '/sales/order/create')
  const payload = {
    ...headerForm.value,
    supplierId: headerForm.value.supplierCode, // 兼容采购后端字段
    customerId: headerForm.value.customerCode, // 兼容销售后端字段
    // 空行不能提交给后端，只映射已填商品的行
    details: filledRows.value.map(r => ({
      goodsCode: r.goodsCode,
      goodsId: r.goodsCode,
      goodsName: r.goodsName,
      spec: r.spec,
      unitId: r.purchaseUnitName,
      unitLevel: r.purchaseUnitLevel,
      convertQty: r.convertQty,
      qty: r.qty,
      baseQty: r.smallQty,
      price: r.price,
      amount: r.amount,
      remark: r.remark,
      // 销售场景取商品行 salesAttribute（默认「正常」），采购场景固定「正常」
      lineType: r.salesAttribute || '正常',
      salesAttribute: r.salesAttribute || '正常',
      taxRate: '13%',
    })),
  }
  try {
    const result = await post(apiPath, payload)
    emit('save', result)
    closeDrawer()
  } catch (error) {
    alert('保存失败：' + (error.message || '未知错误'))
  }
}

function closeDrawer() {
  emit('close')
}

// ============ 快捷键：抽屉可见时激活 ============
// Ctrl/Cmd + S → 保存；Ctrl/Cmd + A → 添加商品
function onKeyDown(e) {
  if (!props.visible) return
  // 商品添加窗口已打开时不响应，让子窗口处理自己的 Ctrl+S
  if (goodsAddOpen.value) return
  const ctrl = e.ctrlKey || e.metaKey
  if (!ctrl) return
  const k = (e.key || '').toLowerCase()
  if (k === 's') {
    e.preventDefault()
    saveBill()
  } else if (k === 'a') {
    // 只在没聚焦到输入框时触发（避免抢用户「全选文本」）
    const tag = (document.activeElement?.tagName || '').toUpperCase()
    const isEditable = ['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) || document.activeElement?.isContentEditable
    if (isEditable) return
    e.preventDefault()
    openGoodsAdd()
  }
}
onMounted(() => window.addEventListener('keydown', onKeyDown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeyDown))
</script>

<template>
  <div v-show="visible" class="bill-drawer-mask">
    <div class="bill-drawer-box">
      <div class="bill-drawer-head">
        <b>{{ title }}</b>
        <div style="flex:1"></div>
        <div class="actions">
          <button class="btn" @click="closeDrawer">取消</button>
          <button class="btn primary" title="Ctrl+S" @click="saveBill">保存 (Ctrl+S)</button>
        </div>
      </div>

      <div class="bill-drawer-body">
        <!-- 头部信息 -->
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">单据信息</div>
          <div class="grid4">
            <div class="field">
              <label>{{ isPurchase ? '供应商' : '客户' }} <span class="req">*</span></label>
              <select v-if="isPurchase" :value="headerForm.supplierCode" @change="onSupplierChange($event.target.value)">
                <option value="">请选择</option>
                <option v-for="p in partnerList" :key="p.supplierCode || p.supplierId" :value="p.supplierCode || p.supplierId">
                  {{ p.supplierCode }} - {{ p.supplierName }}
                </option>
              </select>
              <select v-else :value="headerForm.customerCode" @change="onCustomerChange($event.target.value)">
                <option value="">请选择</option>
                <option v-for="p in partnerList" :key="p.customerCode || p.customerId" :value="p.customerCode || p.customerId">
                  {{ p.customerCode }} - {{ p.customerName }}
                </option>
              </select>
            </div>
            <div class="field" v-if="isPurchase">
              <label>采购员 <span class="req">*</span></label>
              <select v-model="headerForm.buyer">
                <option value="">请选择</option>
                <option v-for="b in buyerList" :key="b.code" :value="b.name">{{ b.name }}</option>
              </select>
            </div>
            <div class="field" v-else>
              <label>业务员 <span class="req">*</span></label>
              <select v-model="headerForm.salesman">
                <option value="">请选择</option>
                <option v-for="s in salesmanList" :key="s.code" :value="s.name">{{ s.name }}</option>
              </select>
            </div>
            <div class="field">
              <label>仓库 <span class="req">*</span></label>
              <select :value="headerForm.warehouseId" @change="onWarehouseChange($event.target.value)">
                <option value="">请选择</option>
                <option v-for="w in warehouseList" :key="w.warehouseId" :value="w.warehouseName">
                  {{ w.warehouseName }}
                </option>
              </select>
            </div>
            <div class="field">
              <label>单据日期</label>
              <input type="date" v-model="headerForm.billDate" />
            </div>
            <div class="field" v-if="!isPurchase">
              <label>指定送货日期</label>
              <input type="date" v-model="headerForm.expectedDeliveryDate" />
            </div>
          </div>
        </div>

        <!-- 商品明细 -->
        <div class="card detail-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
            <div style="font-weight:900;color:var(--primary)">商品明细</div>
            <button class="btn primary" style="height:26px;padding:0 10px;font-size:12px" title="Ctrl+A" @click="openGoodsAdd">+ 添加商品 (Ctrl+A)</button>
          </div>
          <div v-if="formErrors.details" style="color:var(--danger);font-size:12px;margin-bottom:6px">{{ formErrors.details }}</div>
          <div class="detail-tip">
            <template v-if="!isPurchase">商品下拉优先展示最近一年有交易的商品，按销量高→低取前 {{ RANK_LIMIT }} 条 · 「可用库存」按所选仓库带出（实物−锁定−冻结），数量超出不允许保存 · </template>
            在「商品编号」列输入 编号/名称/简拼/条码 检索 · ↑↓ 同列换行 · Enter 同行换字段 · 行末 Enter 自动加行
          </div>
          <div class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th style="min-width:150px">商品编号</th>
                  <th style="min-width:160px">商品名称</th>
                  <th style="min-width:90px">规格</th>
                  <th style="width:84px">{{ isPurchase ? '采购单位' : '销售单位' }}</th>
                  <th style="width:82px">{{ isPurchase ? '采购数量' : '销售数量' }}</th>
                  <th style="width:80px">小单位数量</th>
                  <th v-if="!isPurchase" style="width:80px">可用库存</th>
                  <th style="width:88px">单价</th>
                  <th style="width:92px">金额</th>
                  <th v-if="!isPurchase" style="width:88px">销售属性</th>
                  <th style="min-width:110px">备注</th>
                  <th style="width:50px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in detailList" :key="index" :class="{ 'row-blank': !row.goodsCode }">
                  <td>{{ index + 1 }}</td>

                  <!-- 商品编号：内联搜索选择器 -->
                  <td class="cell-pad">
                    <InlineGoodsPicker
                      :ref="bindCell(index, 'goods')"
                      v-model="row.goodsSearch"
                      :goods-list="isPurchase ? allGoods : rankedGoods"
                      :remote="!isPurchase"
                      :loading="goodsLoading"
                      :show-sale-qty="!isPurchase"
                      @search="kw => loadRankedGoods(kw)"
                      @select="g => onRowGoodsSelect(row, index, g)"
                      @select-multi="list => onRowGoodsSelectMulti(row, index, list)"
                      @nav="action => onGoodsNav(index, action)" />
                  </td>

                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.spec }}</td>

                  <!-- 单位：多单位可切，切换后按取价逻辑重新带价 -->
                  <td class="cell-pad">
                    <select
                      :ref="bindCell(index, 'unit')"
                      class="cell-input"
                      :value="row.purchaseUnitLevel"
                      :disabled="!row.goodsCode"
                      @change="onRowUnitChange(row, $event.target.value)"
                      @keydown="e => onCellKeyDown(e, index, 'unit')">
                      <option v-for="u in unitOptionsOf(row)" :key="u.level" :value="u.level">{{ u.name }}</option>
                    </select>
                  </td>

                  <!-- 数量：非称重整数，称重≤3位小数 -->
                  <td class="cell-pad">
                    <input
                      :ref="bindCell(index, 'qty')"
                      class="cell-input num"
                      :value="row.qty"
                      :disabled="!row.goodsCode"
                      :placeholder="row.isWeighted ? '0.000' : '0'"
                      @input="e => onRowQtyInput(row, e)"
                      @keydown="e => onCellKeyDown(e, index, 'qty')" />
                  </td>

                  <td style="text-align:right">{{ row.smallQty }}</td>

                  <!-- 可用库存：选商品时按当前仓库带出；超卖标红但不阻断输入，保存时才拦 -->
                  <td v-if="!isPurchase" style="text-align:right"
                      :class="{ 'stock-short': isOverStock(row) }"
                      :title="isOverStock(row) ? '本单该商品合计数量已超过可用库存，保存会被拦截' : ''">
                    {{ row.goodsCode ? (row.availableStock == null ? '-' : row.availableStock) : '' }}
                  </td>

                  <!-- 单价：≤4 位小数 -->
                  <td class="cell-pad">
                    <input
                      :ref="bindCell(index, 'price')"
                      class="cell-input num"
                      :value="row.price"
                      :disabled="!row.goodsCode"
                      placeholder="0.0000"
                      :title="row.priceSourceText || ''"
                      @input="e => onRowPriceInput(row, e)"
                      @keydown="e => onCellKeyDown(e, index, 'price')" />
                  </td>

                  <!-- 金额：2 位小数，改金额反算单价 -->
                  <td class="cell-pad">
                    <input
                      :ref="bindCell(index, 'amount')"
                      class="cell-input num strong"
                      :value="row.amount"
                      :disabled="!row.goodsCode"
                      placeholder="0.00"
                      @input="e => onRowAmountInput(row, e)"
                      @keydown="e => onCellKeyDown(e, index, 'amount')" />
                  </td>

                  <td v-if="!isPurchase" class="cell-pad">
                    <select
                      :ref="bindCell(index, 'salesAttribute')"
                      v-model="row.salesAttribute"
                      class="cell-input"
                      :disabled="!row.goodsCode"
                      @keydown="e => onCellKeyDown(e, index, 'salesAttribute')">
                      <option>正常</option>
                      <option>赠品</option>
                      <option>样品</option>
                      <option>兑换</option>
                      <option>陈列</option>
                    </select>
                  </td>

                  <td class="cell-pad">
                    <input
                      :ref="bindCell(index, 'remark')"
                      v-model="row.remark"
                      class="cell-input"
                      :disabled="!row.goodsCode"
                      @keydown="e => onCellKeyDown(e, index, 'remark')" />
                  </td>

                  <td>
                    <button class="link link-btn danger-link" @click="removeRow(index)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="summary">
          <span>合计金额：<b>¥ {{ totalAmount }}</b></span>
          <span>商品行数：<b>{{ detailList.length }}</b></span>
        </div>
      </div>
    </div>
  </div>

  <!-- 商品添加窗口 -->
  <GoodsAddDialog
    :visible="goodsAddOpen"
    :role="isPurchase ? 'purchase' : 'sale'"
    :supplier-code="headerForm.supplierCode"
    :supplier-name="headerForm.supplierName"
    :customer-code="headerForm.customerCode"
    :customer-name="headerForm.customerName"
    :warehouse="headerForm.warehouseId"
    @confirm="onGoodsAdded"
    @close="goodsAddOpen = false"
  />
</template>

<style scoped>
/* 采用「无遮罩 + 定位 box」：抽屉打开时用户仍可点击左侧菜单切换其他模块 */
.bill-drawer-mask {
  position: fixed;
  top: 48px; /* 让开顶栏 */
  right: 0; bottom: 0;
  left: 299px; /* 菜单栏 224px + 向右缩进 75px */
  z-index: 900;
  display: flex;
  pointer-events: none; /* 遮罩本身不吸事件；只 box 内部有事件 */
  animation: fadeIn 0.2s ease;
}
.bill-drawer-box {
  flex: 1;
  background: #fff;
  display: flex; flex-direction: column;
  min-width: 0;
  border-left: 1px solid var(--line);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.12);
  pointer-events: auto; /* box 内部正常交互 */
  animation: slideIn 0.25s ease;
}
.bill-drawer-head {
  display: flex; align-items: center; gap: 10px;
  height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft);
}
.bill-drawer-head b { font-size: 15px; }
.bill-drawer-body {
  flex: 1; overflow: auto;
  padding: 12px 16px;
  display: flex; flex-direction: column; gap: 12px;
  background: #f5f7fa;
}
.detail-card { flex: 1; display: flex; flex-direction: column; min-height: 260px; padding: 12px; }
.detail-scroll { flex: 1; overflow: auto; min-height: 0; }
.empty-detail { padding: 40px; text-align: center; color: #909399; font-size: 13px; background: #fafbfc; border: 1px dashed #e5e7eb; border-radius: 8px; }
.field .req { color: #f56c6c; }

/* ===== 明细表内联录入 ===== */
.detail-tip {
  font-size: 11px; color: #909399; margin-bottom: 6px;
  padding: 3px 8px; background: #f8fafc; border-radius: 3px;
}
/* 单元格留窄边距，让 input 几乎占满格子 */
.detail-scroll td.cell-pad { padding: 2px 3px; }
.cell-input {
  width: 100%; height: 24px; padding: 0 4px;
  border: 1px solid #dcdfe6; border-radius: 3px;
  font-size: 12px; background: #fff;
}
.cell-input:focus {
  border-color: #409eff; outline: none;
  box-shadow: 0 0 0 2px rgba(64,158,255,.15);
}
.cell-input:disabled { background: #f5f7fa; color: #c0c4cc; cursor: not-allowed; }
.cell-input.num { text-align: right; font-variant-numeric: tabular-nums; }
.cell-input.strong { font-weight: 700; }
/* 可用库存不足：整格标红加粗，录单时一眼能看出哪行超卖 */
.detail-scroll td.stock-short { color: var(--danger, #f56c6c); font-weight: 700; }
/* 未填商品的空行整体淡化，视觉上区分「待录入」 */
.detail-scroll tr.row-blank { background: #fcfcfd; }
.detail-scroll tr.row-blank td { color: #c0c4cc; }
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6 } to { transform: translateX(0); opacity: 1 } }

/* 菜单收起状态下抽屉贴左 */
@media (max-width: 900px) {
  .bill-drawer-mask { left: 0; }
}
</style>
