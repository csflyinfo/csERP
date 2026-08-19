<script setup>
// 采购订单：新增商品明细专用弹窗
// 功能：搜索商品 / 单位切换 / 数量-单价-金额互算 / 供应商与系统最近采购价 / 库存展示
// 键盘：↑↓ 切商品；Enter 选中 → 下一编辑框；Ctrl/Cmd+Enter = 添加并继续
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  // role: 'purchase' → 采购场景（供应商最近采购价 / 系统最近采购价 / 大→中→小 单位默认）
  //        'sale'     → 销售场景（客户最近成交价 / 系统最近售价 / 小→中→大 单位默认）
  role: { type: String, default: 'purchase' },
  // 采购透传：影响供应商最近采购价 / 采购单位默认策略
  supplierCode: { type: String, default: '' },
  supplierName: { type: String, default: '' },
  // 销售透传：影响客户最近成交价 / 销售单位默认
  customerCode: { type: String, default: '' },
  customerName: { type: String, default: '' },
  // 单据所选仓库名：销售场景下「可用库存」按此仓库取，与明细表格的可用库存列同口径
  warehouse: { type: String, default: '' },
})
const emit = defineEmits(['close', 'confirm'])

const isPurchase = computed(() => props.role !== 'sale')
const dialogTitle = computed(() => isPurchase.value ? '添加采购商品' : '添加销售商品')
const priceLabel1 = computed(() => isPurchase.value ? '供应商最近采购价' : '客户最近成交价')
const priceLabel2 = computed(() => isPurchase.value ? '系统最近采购价' : '系统最近售价')
const unitLabel = computed(() => isPurchase.value ? '采购单位' : '销售单位')
const qtyLabel = computed(() => isPurchase.value ? '采购数量' : '销售数量')

// ============ 数据加载 ============
const allGoods = ref([])
const unitsList = ref([])
let dataLoaded = false
async function ensureData() {
  if (dataLoaded) return
  const [g, u] = await Promise.all([
    post('/base/goods/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => ({ records: [] })),
    post('/base/unit/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => ({ records: [] })),
  ])
  allGoods.value = (g.records || []).filter(x => x.status !== 'STOPPED')
  unitsList.value = u.records || []
  dataLoaded = true
  // 排序由调用方在每次打开时执行（applySaleRanking），此处只负责取数
}

/**
 * 拉取销量排名，把 allGoods 按「有交易优先 → 销量降序」重排。
 * 排名接口只返回前 N 条，未上榜的商品保持原顺序接在后面。
 */
async function applySaleRanking() {
  try {
    const ranked = await post('/base/goods/sale-ranking', {
      customerCode: props.customerCode || '',
      keyword: '',
      limit: 200,
    })
    if (!Array.isArray(ranked) || ranked.length === 0) return
    // 建立 goodsCode → 排名序号，并把销量挂到商品上供下拉展示
    const order = new Map()
    const qtyMap = new Map()
    ranked.forEach((r, i) => {
      order.set(r.goodsCode, i)
      qtyMap.set(r.goodsCode, Number(r.saleQty) || 0)
    })
    allGoods.value = allGoods.value
      .map(g => ({ ...g, saleQty: qtyMap.get(g.goodsCode) ?? 0 }))
      .sort((a, b) => {
        const ia = order.has(a.goodsCode) ? order.get(a.goodsCode) : Number.MAX_SAFE_INTEGER
        const ib = order.has(b.goodsCode) ? order.get(b.goodsCode) : Number.MAX_SAFE_INTEGER
        if (ia !== ib) return ia - ib
        return String(a.goodsCode).localeCompare(String(b.goodsCode))
      })
  } catch (_) { /* 排名失败不影响录单，保持原顺序 */ }
}

// ============ 表单状态 ============
const goodsSearch = ref('')
const showGoodsList = ref(false)
const highlightIndex = ref(0)
const form = ref(emptyForm())

// 销售取价来源（仅 role='sale'）：由 /base/goods/sale-price 返回
//   priceSource: CUSTOMER | PRICE_GROUP | GOODS_STANDARD | NONE
const salePriceInfo = ref(null)
// 单价是否为「系统自动带出」。用户手改过就置 false，之后切单位不再覆盖他的输入。
const priceAutoFilled = ref(false)

function emptyForm() {
  return {
    goods: null,
    barcode: '',
    spec: '',
    smallUnitName: '',
    purchaseUnitName: '',
    purchaseUnitLevel: 1, // 1=小 2=中 3=大
    convertQty: 1,        // 采购单位到小单位的换算率
    qty: null,
    smallQty: 0,
    price: null,
    amount: null,
    supplierLatestPrice: 0,
    systemLatestPrice: 0,
    salesAttribute: '正常', // 销售场景专用：正常/赠品/样品/兑换/陈列
    remark: '',
    availableStock: 0,
  }
}

// ============ 搜索过滤 ============
const filteredGoods = computed(() => {
  const q = goodsSearch.value.trim().toLowerCase()
  if (!q) return allGoods.value.slice(0, 30)
  return allGoods.value.filter(g => {
    const code = String(g.goodsCode || '').toLowerCase()
    const name = String(g.goodsName || '').toLowerCase()
    const bar = String(g.barcode || '').toLowerCase()
    // 简拼（simpleCode）是录单常用检索方式，与后端 keyword 口径保持一致
    const simple = String(g.simpleCode || '').toLowerCase()
    return code.includes(q) || name.includes(q) || bar.includes(q) || simple.includes(q)
  }).slice(0, 30)
})

// 只有用户主动输入才生效；程序设置 goodsSearch 不触发
let suppressSearchWatch = false
watch(goodsSearch, () => {
  if (suppressSearchWatch) return
  // 用户主动修改搜索关键字 → 视为放弃已选商品，并实时展开下拉展示匹配结果
  if (form.value.goods) form.value.goods = null
  highlightIndex.value = 0
  showGoodsList.value = true
})

// ============ 单位下拉：根据商品 unit_config 派生 ============
const unitOptions = computed(() => {
  const g = form.value.goods
  if (!g) return []
  const cfg = parseUnitConfig(g.unitConfig)
  const opts = []
  // 索引 0=小 1=中 2=大；只保留 enabled
  const labels = ['小单位', '中单位', '大单位']
  for (let i = 0; i < 3; i++) {
    const u = cfg[i]
    if (u && u.enabled && u.unitName) {
      opts.push({
        level: i + 1,
        label: labels[i],
        name: u.unitName,
        barcode: u.barcode || '',
        convertQty: Number(u.convertQty) || 1,
        standardPrice: Number(u.standardPrice) || 0,
      })
    }
  }
  return opts
})
function parseUnitConfig(raw) {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try { return JSON.parse(String(raw)) } catch (_) { return [] }
}

// ============ 选中商品 ============
async function pickGoods(g) {
  form.value.goods = g
  // 换商品 → 清掉上一个商品的取价来源与手改标记，避免串味
  resetPriceState()
  form.value.price = null
  // 程序设置搜索框内容，不打开下拉
  suppressSearchWatch = true
  goodsSearch.value = `${g.goodsCode} ${g.goodsName}`
  showGoodsList.value = false
  await nextTick()
  suppressSearchWatch = false

  // 单位默认值：
  //   采购场景 —— 若商品设置了「默认采购单位」(default_purchase_unit) 且该单位已启用，优先取它；
  //               否则沿用「大 → 中 → 小」优先级。
  //   销售场景 —— 沿用「小 → 中 → 大」优先级。
  const opts = unitOptions.value
  let defaultUnit
  if (isPurchase.value) {
    const preferName = String(g.defaultPurchaseUnit || '').trim()
    const preferred = preferName ? opts.find(o => o.name === preferName) : null
    defaultUnit = preferred
        || opts.find(o => o.level === 3)
        || opts.find(o => o.level === 2)
        || opts[0]
        || null
  } else {
    defaultUnit = opts.find(o => o.level === 1) || opts.find(o => o.level === 2) || opts[opts.length - 1] || null
  }
  applyUnit(defaultUnit)

  // 小单位（level=1）—— 名称+条码
  const smallUnit = opts.find(o => o.level === 1)
  form.value.smallUnitName = smallUnit?.name || g.baseUnit || ''
  form.value.barcode = smallUnit?.barcode || g.barcode || ''
  form.value.spec = g.spec || ''

  // 供应商 & 系统最近采购价 / 客户最近成交价 & 系统最近售价
  try {
    if (isPurchase.value) {
      const p = await get(`/base/goods/latest-purchase-price?goodsCode=${encodeURIComponent(g.goodsCode)}&supplierCode=${encodeURIComponent(props.supplierCode || '')}`)
      form.value.supplierLatestPrice = Number(p?.supplierLatestPrice) || 0
      form.value.systemLatestPrice = Number(p?.systemLatestPrice) || 0
    } else {
      // 销售场景：后端查 sales_order_detail 最近非 0 售价（客户级 + 系统级），无历史兜底商品建议零售价
      const p = await get(`/base/goods/latest-sales-price?goodsCode=${encodeURIComponent(g.goodsCode)}&customerCode=${encodeURIComponent(props.customerCode || '')}`)
      form.value.supplierLatestPrice = Number(p?.customerLatestPrice) || 0
      form.value.systemLatestPrice = Number(p?.systemLatestPrice) || 0
    }
  } catch (_) { /* 忽略 */ }

  // 库存
  try {
    if (!isPurchase.value && props.warehouse) {
      // 销售：按单据仓库取，口径与明细表「可用库存」列一致（否则两处数字会对不上）
      const rows = await post('/inventory/available-stock', {
        warehouse: props.warehouse,
        goodsCodes: [g.goodsCode],
      })
      const hit = (Array.isArray(rows) ? rows : []).find(r => r.goodsCode === g.goodsCode)
      form.value.availableStock = Number(hit?.availableQty) || 0
    } else {
      // 采购或未选仓库：退回全仓合计
      const s = await get(`/base/goods/stock-summary?goodsCode=${encodeURIComponent(g.goodsCode)}`)
      form.value.availableStock = Number(s?.availableStock) || 0
    }
  } catch (_) { /* 忽略 */ }

  // 光标切到数量
  await nextTick()
  refs.qty?.focus?.()
  refs.qty?.select?.()
}

/**
 * 销售取价：按「客户商品价 > 客户价格组价 > 商品标价」优先级取该单位应售单价。
 * 后端 /base/goods/sale-price 负责判定优先级与降级，前端只负责回显。
 *
 * 仅在单价为「自动带出」状态时才覆盖 —— 用户手改过的价不动。
 */
async function resolveSalePrice(goodsCode, level) {
  if (isPurchase.value || !goodsCode) return
  try {
    const r = await get(`/base/goods/sale-price?goodsCode=${encodeURIComponent(goodsCode)}`
      + `&customerCode=${encodeURIComponent(props.customerCode || '')}`
      + `&unitLevel=${level}`)
    salePriceInfo.value = r || null
    const p = Number(r?.price) || 0
    // 空价 或 仍是自动带出的价 → 用新单位的取价覆盖
    if (p > 0 && (priceAutoFilled.value || !form.value.price)) {
      form.value.price = p
      priceAutoFilled.value = true
      recalcAmount()
    }
  } catch (_) {
    salePriceInfo.value = null
  }
}

function applyUnit(u) {
  if (!u) {
    form.value.purchaseUnitName = ''
    form.value.purchaseUnitLevel = 1
    form.value.convertQty = 1
    return
  }
  form.value.purchaseUnitName = u.name
  form.value.purchaseUnitLevel = u.level
  form.value.convertQty = u.convertQty || 1
  if (isPurchase.value) {
    // 采购场景：沿用商品档案该单位的 standardPrice 作参考进价（逻辑不变）
    if (u.standardPrice > 0 && !form.value.price) {
      form.value.price = u.standardPrice
    }
  } else {
    // 销售场景：按单位重新取价（客户价 > 价格组价 > 商品标价）
    // 不能沿用采购的 `!form.value.price` 判断 —— 切单位必须换成对应单位的价
    resolveSalePrice(form.value.goods?.goodsCode, u.level)
  }
  recalcAmount()
  recalcSmallQty()
}

function onUnitChange(level) {
  const u = unitOptions.value.find(o => o.level === Number(level))
  applyUnit(u)
}

// ============ 数量 / 单价 / 金额 互算 ============
function isWeighted() { return !!form.value.goods?.isWeighted }

function onQtyInput(e) {
  let v = String(e?.target?.value ?? '')
  // 非称重品：只允许整数、不允许负号
  if (!isWeighted()) v = v.replace(/[^\d]/g, '')
  else v = v.replace(/[^\d.]/g, '')
  form.value.qty = v === '' ? null : Number(v)
  if (e?.target) e.target.value = v
  recalcAmount()
  recalcSmallQty()
}

function onPriceInput(e) {
  let v = String(e?.target?.value ?? '').replace(/[^\d.]/g, '')
  // 最多 4 位小数
  const dot = v.indexOf('.')
  if (dot >= 0) v = v.slice(0, dot + 1) + v.slice(dot + 1).replace(/\./g, '').slice(0, 4)
  form.value.price = v === '' ? null : Number(v)
  if (e?.target) e.target.value = v
  // 用户手动改价 → 之后切单位不再用系统取价覆盖他的输入
  priceAutoFilled.value = false
  recalcAmount()
}

// 输入金额 → 反算单价（保留 4 位）
function onAmountInput(e) {
  let v = String(e?.target?.value ?? '').replace(/[^\d.]/g, '')
  const dot = v.indexOf('.')
  if (dot >= 0) v = v.slice(0, dot + 1) + v.slice(dot + 1).replace(/\./g, '').slice(0, 2)
  form.value.amount = v === '' ? null : Number(v)
  if (e?.target) e.target.value = v
  const q = Number(form.value.qty)
  if (q > 0 && form.value.amount != null) {
    const price = form.value.amount / q
    form.value.price = Math.round(price * 10000) / 10000
    // 改金额反算出的单价同样视为手改
    priceAutoFilled.value = false
  }
}

// 单价来源标签的配色：客户专属价最优（绿），价格组次之（蓝），商品标价为兜底（灰）
const priceSourceClass = computed(() => {
  if (!priceAutoFilled.value) return 'src-manual'
  return {
    CUSTOMER: 'src-customer',
    PRICE_GROUP: 'src-group',
    GOODS_STANDARD: 'src-standard',
  }[salePriceInfo.value?.priceSource] || 'src-none'
})

function recalcAmount() {
  const q = Number(form.value.qty) || 0
  const p = Number(form.value.price) || 0
  form.value.amount = Math.round(q * p * 100) / 100
}

function recalcSmallQty() {
  const q = Number(form.value.qty) || 0
  const c = Number(form.value.convertQty) || 1
  form.value.smallQty = Math.round(q * c * 10000) / 10000
}

// ============ 键盘导航（商品下拉） ============
function onSearchFocus() {
  // 聚焦即展开下拉：默认列出候选商品（历史销售靠前），不必先输关键字
  if (!form.value.goods) showGoodsList.value = true
}
function onSearchKeyDown(e) {
  // 未展开下拉时：按 Enter 触发查询 → 打开下拉；其他键不处理
  if (!showGoodsList.value) {
    if (e.key === 'Enter') {
      e.preventDefault()
      // 若已选中商品，此次 Enter 视为「换商品」：清空选中 + 展开下拉
      if (form.value.goods) form.value.goods = null
      highlightIndex.value = 0
      showGoodsList.value = true
    }
    return
  }
  // 下拉已展开：↑↓ 高亮 / Enter 选中 / Esc 关闭
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    highlightIndex.value = Math.min(highlightIndex.value + 1, filteredGoods.value.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    highlightIndex.value = Math.max(highlightIndex.value - 1, 0)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const g = filteredGoods.value[highlightIndex.value]
    if (g) pickGoods(g)
  } else if (e.key === 'Escape') {
    showGoodsList.value = false
  }
}

// 采购单位下拉键盘循环切换
function onUnitKeyDown(e) {
  if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
    const opts = unitOptions.value
    if (opts.length <= 1) return
    e.preventDefault()
    const cur = opts.findIndex(o => o.level === form.value.purchaseUnitLevel)
    const step = e.key === 'ArrowDown' ? 1 : -1
    const next = (cur + step + opts.length) % opts.length
    applyUnit(opts[next])
  }
}

// 按 Enter 切下一编辑框
const refs = {}
const fieldOrder = ['unit', 'qty', 'price', 'amount', 'remark']
function focusNext(cur) {
  const i = fieldOrder.indexOf(cur)
  if (i >= 0 && i < fieldOrder.length - 1) {
    const el = refs[fieldOrder[i + 1]]
    if (el && el.focus) { el.focus(); el.select?.() }
  }
}
function bindRef(key) { return el => { refs[key] = el } }

function onEnterField(cur, e) {
  if (e.shiftKey || e.ctrlKey || e.metaKey) return
  e.preventDefault()
  focusNext(cur)
}

// ============ 添加 / 添加并继续 / 取消 ============
function buildRow() {
  const g = form.value.goods
  if (!g) return null
  if (!(Number(form.value.qty) > 0)) return null
  return {
    goodsCode: g.goodsCode,
    goodsName: g.goodsName,
    spec: g.spec || '',
    barcode: form.value.barcode,
    smallUnitName: form.value.smallUnitName,
    purchaseUnitName: form.value.purchaseUnitName,
    purchaseUnitLevel: form.value.purchaseUnitLevel,
    convertQty: form.value.convertQty,
    qty: Number(form.value.qty),
    smallQty: form.value.smallQty,
    price: Number(form.value.price) || 0,
    amount: Number(form.value.amount) || 0,
    supplierLatestPrice: form.value.supplierLatestPrice,
    systemLatestPrice: form.value.systemLatestPrice,
    salesAttribute: form.value.salesAttribute || '正常',
    remark: form.value.remark,
  }
}

function validate() {
  const g = form.value.goods
  if (!g) { alert('请选择商品'); return false }
  if (!(Number(form.value.qty) > 0)) { alert('请输入采购数量'); return false }
  if (Number(form.value.price) < 0) { alert('单价不能为负'); return false }
  return true
}

function submitConfirm() {
  if (!validate()) return
  const row = buildRow()
  if (!row) return
  emit('confirm', row)
  emit('close')
}

async function submitAddContinue() {
  if (!validate()) return
  const row = buildRow()
  if (!row) return
  emit('confirm', row)
  // 保留窗口，清空表单，光标回到搜索框
  goodsSearch.value = ''
  form.value = emptyForm()
  resetPriceState()
  await nextTick()
  refs.search?.focus?.()
}

/** 清空销售取价状态（换商品 / 清表单时都要调，否则来源标签会残留上一条的） */
function resetPriceState() {
  salePriceInfo.value = null
  priceAutoFilled.value = false
}

function cancel() {
  emit('close')
}

// ============ 打开/关闭 ============
watch(() => props.visible, async (v) => {
  if (v) {
    await ensureData()
    // 每次打开都按当前客户重排（销量按客户统计，换客户排名就不同）
    if (!isPurchase.value) await applySaleRanking()
    goodsSearch.value = ''
    form.value = emptyForm()
    resetPriceState()
    highlightIndex.value = 0
    await nextTick()
    // 不自动聚焦：用户点击输入框才展开下拉，避免打开窗口就弹出一大排商品
  }
})

// 快捷键：Ctrl/Cmd + S = 添加并继续（仅窗口可见时激活）
function onKeyDown(e) {
  if (!props.visible) return
  const ctrl = e.ctrlKey || e.metaKey
  if (!ctrl) return
  const k = (e.key || '').toLowerCase()
  if (k === 's') {
    e.preventDefault()
    submitAddContinue()
  }
}
onMounted(() => window.addEventListener('keydown', onKeyDown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeyDown))
</script>

<template>
  <div v-if="visible" class="gad-mask" @click.self="cancel">
    <div class="gad-box">
      <div class="gad-head">
        <b>{{ dialogTitle }}</b>
        <span class="gad-sup" v-if="supplierName">供应商：{{ supplierName }}</span>
        <div style="flex:1"></div>
        <button class="btn" @click="cancel">取消 (Esc)</button>
        <button class="btn" @click="submitAddContinue" title="Ctrl+S">添加并继续 (Ctrl+S)</button>
        <button class="btn primary" @click="submitConfirm">确定</button>
      </div>

      <div class="gad-body">
        <!-- 商品搜索：独占一行 -->
        <div class="gad-row gad-row-full">
          <label>商品 <span class="req">*</span></label>
          <div class="gad-search-wrap">
            <input
              :ref="bindRef('search')"
              v-model="goodsSearch"
              placeholder="输入 编号/名称/简拼/条码 检索，↑↓ 选择，回车确认"
              @focus="onSearchFocus"
              @keydown="onSearchKeyDown"
            />
            <div v-if="showGoodsList && filteredGoods.length" class="gad-dropdown">
              <div
                v-for="(g, i) in filteredGoods"
                :key="g.goodsCode"
                class="gad-opt"
                :class="{ active: i === highlightIndex }"
                @mouseenter="highlightIndex = i"
                @click="pickGoods(g)"
              >
                <span class="c">{{ g.goodsCode }}</span>
                <span class="n">{{ g.goodsName }}</span>
                <span class="s">{{ g.spec }}</span>
                <span class="b">{{ g.barcode }}</span>
              </div>
            </div>
            <div v-else-if="showGoodsList && goodsSearch" class="gad-dropdown">
              <div class="gad-empty">未找到匹配商品</div>
            </div>
          </div>
        </div>

        <!-- 双列字段区：12 个字段两两排列 -->
        <div class="gad-grid-2">
          <div class="gad-row">
            <label>条码</label>
            <input :value="form.barcode" readonly />
          </div>
          <div class="gad-row">
            <label>规格</label>
            <input :value="form.spec" readonly />
          </div>
          <div class="gad-row">
            <label>小单位</label>
            <input :value="form.smallUnitName" readonly />
          </div>
          <div v-if="!isPurchase" class="gad-row">
            <label>销售属性</label>
            <select v-model="form.salesAttribute">
              <option>正常</option>
              <option>赠品</option>
              <option>样品</option>
              <option>兑换</option>
              <option>陈列</option>
            </select>
          </div>
          <div class="gad-row">
            <label>{{ unitLabel }}</label>
            <select
              :ref="bindRef('unit')"
              :value="form.purchaseUnitLevel"
              :disabled="!form.goods || unitOptions.length === 0"
              @change="onUnitChange($event.target.value)"
              @keydown="onUnitKeyDown"
              @keydown.enter.prevent="focusNext('unit')"
            >
              <option v-for="u in unitOptions" :key="u.level" :value="u.level">
                {{ u.label }}：{{ u.name }} ({{ u.convertQty }})
              </option>
            </select>
          </div>
          <div class="gad-row">
            <label>{{ qtyLabel }} <span class="req">*</span></label>
            <input
              :ref="bindRef('qty')"
              :value="form.qty"
              :placeholder="isWeighted() ? '支持小数' : '整数'"
              @input="onQtyInput"
              @keydown.enter="onEnterField('qty', $event)"
            />
          </div>
          <div class="gad-row">
            <label>小单位数量</label>
            <input :value="form.smallQty" readonly />
          </div>
          <div class="gad-row">
            <label>单价 <span class="req">*</span></label>
            <input
              :ref="bindRef('price')"
              :value="form.price"
              placeholder="≤4 位小数"
              @input="onPriceInput"
              @keydown.enter="onEnterField('price', $event)"
            />
            <!-- 销售场景标出单价来源，业务员一眼知道价格从哪来 -->
            <span v-if="!isPurchase && salePriceInfo" class="gad-price-src" :class="priceSourceClass">
              {{ priceAutoFilled ? salePriceInfo.priceSourceText : '手工改价' }}
            </span>
          </div>
          <div class="gad-row">
            <label>金额</label>
            <input
              :ref="bindRef('amount')"
              :value="form.amount"
              placeholder="改金额自动反算单价"
              @input="onAmountInput"
              @keydown.enter="onEnterField('amount', $event)"
            />
          </div>
          <div class="gad-row">
            <label>{{ priceLabel1 }}</label>
            <input :value="form.supplierLatestPrice || 0" readonly />
          </div>
          <div class="gad-row">
            <label>{{ priceLabel2 }}</label>
            <input :value="form.systemLatestPrice || 0" readonly />
          </div>
          <div class="gad-row">
            <label>可用库存</label>
            <input :value="form.availableStock || 0" readonly
                   :title="!isPurchase && warehouse ? `仓库：${warehouse}（实物−锁定−冻结）` : '全部仓库合计'" />
          </div>
        </div>

        <!-- 备注：独占一行 -->
        <div class="gad-row gad-row-full">
          <label>备注</label>
          <input
            :ref="bindRef('remark')"
            v-model="form.remark"
            placeholder="选填"
            @keydown.enter="submitConfirm"
          />
        </div>

        <div class="gad-tip">
          <span>提示：Enter 移到下一字段；在备注栏 Enter = 确定；点「添加并继续」保留窗口继续添加。</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gad-mask {
  position: fixed; inset: 0;
  background: rgba(15, 35, 60, 0.35);
  z-index: 1200;
  display: flex; justify-content: center; align-items: center;
}
.gad-box {
  width: 780px; max-width: 96vw;
  background: #fff; border-radius: 10px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  display: flex; flex-direction: column;
}
.gad-head {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid #e5e7eb;
}
.gad-head b { font-size: 14px; }
.gad-sup { color: #666; font-size: 12px; }
.gad-body { padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.gad-row { display: flex; align-items: center; gap: 8px; position: relative; }
.gad-row label { width: 110px; text-align: right; color: #444; font-size: 13px; flex-shrink: 0; }
.gad-row input, .gad-row select {
  flex: 1;
  height: 30px; padding: 0 8px;
  border: 1px solid #dcdfe6; border-radius: 4px;
  font-size: 13px;
  width: 100%; min-width: 0;
}
.gad-row input:focus, .gad-row select:focus { outline: none; border-color: #409eff; box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.15); }
.gad-row input[readonly] { background: #f7f8fa; color: #666; }
.gad-row .req { color: #f56c6c; }

/* 布局：商品/备注独占一行；其他字段一行两列 */
.gad-row-full { width: 100%; }
.gad-grid-2 {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px 20px;
}
.gad-grid-2 .gad-row { min-width: 0; }
.gad-grid-2 .gad-row label { width: 110px; }

/* 保留兼容（暂未使用） */
.gad-row-group { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px 8px; }
.gad-row-group .gad-row label { width: 96px; }
.gad-row-group .gad-row { display: flex; }

/* 搜索下拉 */
.gad-search-wrap { flex: 1; position: relative; }
.gad-search-wrap input { width: 100%; }
.gad-dropdown {
  position: absolute; top: 100%; left: 0; right: 0;
  background: #fff; border: 1px solid #dcdfe6; border-radius: 4px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.1);
  max-height: 300px; overflow-y: auto;
  z-index: 20;
}
.gad-opt {
  display: grid; grid-template-columns: 100px 1fr 100px 160px;
  gap: 6px;
  padding: 6px 10px; cursor: pointer; font-size: 12px;
  border-bottom: 1px solid #f0f2f5;
}
.gad-opt:last-child { border-bottom: none; }
.gad-opt.active, .gad-opt:hover { background: #ecf5ff; }
.gad-opt .c { color: #409eff; font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.gad-opt .n { color: #303133; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gad-opt .s { color: #666; }
.gad-opt .b { color: #999; font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.gad-empty { padding: 12px; text-align: center; color: #909399; font-size: 12px; }

.gad-tip { color: #909399; font-size: 12px; padding-top: 4px; border-top: 1px dashed #e5e7eb; }

/* 单价来源标签（销售场景） */
.gad-price-src {
  margin-left: 6px; padding: 1px 6px; border-radius: 3px;
  font-size: 11px; white-space: nowrap; flex-shrink: 0;
}
.gad-price-src.src-customer { background: #f0f9eb; color: #529b2e; }   /* 客户专属价 */
.gad-price-src.src-group    { background: #ecf5ff; color: #337ecc; }   /* 价格组价 */
.gad-price-src.src-standard { background: #f4f4f5; color: #909399; }   /* 商品标价 */
.gad-price-src.src-none     { background: #fef0f0; color: #c45656; }   /* 未设价 */
.gad-price-src.src-manual   { background: #fdf6ec; color: #b88230; }   /* 手工改价 */
</style>
