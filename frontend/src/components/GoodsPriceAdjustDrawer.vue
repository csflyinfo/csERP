<script setup>
/**
 * 商品调价单抽屉 —— 按商品维度同时调多种价格。
 *
 * 与原价格组调价单独立的模块，用于：
 *   1. 列表新建：可添加多个商品
 *   2. 快速调价：商品档案跳转，单商品锁定不可增删
 *
 * 明细矩阵：行 = 商品 × 单位级别（仅启用），列 = 价格类型
 *   标准售价 / 参考进价 / 最低价 / 建议零售价 + 每启用价格组一列
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 调价单 ID（编辑/查看时传入；新增为空） */
  orderId: { type: String, default: '' },
  /** add | edit | view */
  mode: { type: String, default: 'add' },
})
const emit = defineEmits(['close', 'saved'])

// ========== 头部 ==========
const header = ref({
  orderId: '', orderNo: '', goodsCode: '', goodsName: '',
  goodsLocked: false, status: 'DRAFT', remark: '',
  createUser: '', createTime: '', auditUser: '', auditTime: '',
})

const isDraft = computed(() => header.value.status === 'DRAFT')
const isPending = computed(() => header.value.status === 'PENDING')
const isApproved = computed(() => header.value.status === 'APPROVED')
const canEdit = computed(() => props.mode !== 'view' && isDraft.value)

// ========== 明细 ==========
/** 每行 { goodsCode, goodsName, unitLevel, standardPriceOld/New, purchasePriceOld/New, minPriceOld/New, suggestRetailPriceOld/New, priceGroupPrices: [{pgCode, pgName, oldPrice, newPrice}] } */
const items = ref([])
const errors = ref({})
const loading = ref(false)

// 价格组列表（全局，用于动态列）
const priceGroups = ref([])
async function loadPriceGroups() {
  try {
    const rows = await get('/base/goods/price-groups')
    priceGroups.value = (Array.isArray(rows) ? rows : []).map(pg => ({
      priceGroupCode: pg.priceGroupCode,
      priceGroupName: pg.priceGroupName,
    }))
  } catch (e) { priceGroups.value = [] }
}

// 商品列表（添加商品弹窗用）
const goodsPickerOpen = ref(false)
const goodsList = ref([])
const goodsSearch = ref('')
const selectedGoods = ref(new Set())
const goodsLoading = ref(false)

// ========== 生命周期 ==========
watch(() => [props.visible, props.orderId], async ([v]) => {
  if (!v) return
  errors.value = {}
  await loadPriceGroups()
  if (props.orderId) {
    await loadOrder(props.orderId)
  } else {
    // 新增
    header.value = {
      orderId: '', orderNo: '', goodsCode: '', goodsName: '',
      goodsLocked: false, status: 'DRAFT', remark: '',
      createUser: '', createTime: '', auditUser: '', auditTime: '',
    }
    items.value = []
  }
})

async function loadOrder(orderId) {
  loading.value = true
  try {
    const data = await post('/base/goods-price-adjust/detail', { orderId })
    header.value = {
      orderId: data.orderId, orderNo: data.orderNo,
      goodsCode: data.goodsCode || '', goodsName: data.goodsName || '',
      goodsLocked: !!data.goodsLocked, status: data.status,
      remark: data.remark || '',
      createUser: data.createUser, createTime: data.createTime,
      auditUser: data.auditUser, auditTime: data.auditTime,
    }
    items.value = (data.items || []).map(ensureItem)
    // 快速调价单且明细为空时，按商品档案自动预填
    if (data.goodsLocked && data.goodsCode && items.value.length === 0 && canEdit.value) {
      await autoFillItems(data.goodsCode, data.goodsName)
      // 自动保存预填明细到后端
      if (header.value.orderId) {
        await doSaveSilent()
      }
    }
  } catch (e) {
    errors.value.header = e.message || '加载调价单失败'
  } finally { loading.value = false }
}

/** 按商品档案预填调价明细（仅快速调价入口调用） */
async function autoFillItems(goodsCode, goodsName) {
  try {
    // 拉取商品档案获取 unit_config
    const goodsData = await post('/base/goods/page', { pageNo: 1, pageSize: 1, filters: { goodsCode } })
    const g = (goodsData.records || [])[0]
    if (!g) return
    const profile = parseUnitConfig(g)
    const rows = []
    for (const u of profile) {
      if (!u.enabled) continue
      const pgPrices = await loadPriceGroupPrices(goodsCode, u.level)
      rows.push({
        goodsCode: g.goodsCode, goodsName: g.goodsName || goodsName,
        unitLevel: u.level,
        standardPriceOld: u.standardPrice, standardPriceNew: u.standardPrice,
        purchasePriceOld: u.purchasePrice, purchasePriceNew: u.purchasePrice,
        minPriceOld: u.minPrice, minPriceNew: u.minPrice,
        suggestRetailPriceOld: u.suggestRetailPrice, suggestRetailPriceNew: u.suggestRetailPrice,
        priceGroupPrices: pgPrices,
      })
    }
    items.value = rows
  } catch (e) {
    errors.value.header = '自动预填调价明细失败：' + (e.message || '未知错误')
  }
}

/** 静默保存（不弹 toast，用于自动预填后落库） */
async function doSaveSilent() {
  try {
    await post('/base/goods-price-adjust/save', {
      orderId: header.value.orderId,
      goodsCode: header.value.goodsCode,
      goodsName: header.value.goodsName,
      goodsLocked: header.value.goodsLocked,
      remark: header.value.remark,
      items: detailPayload(),
    })
  } catch (e) { /* 自动保存失败不阻塞 */ }
}

/** 预填一条明细行，确保所有字段有默认值 */
function ensureItem(raw) {
  const pgPrices = Array.isArray(raw.priceGroupPrices) ? raw.priceGroupPrices.map(p => ({
    pgCode: p.pgCode, pgName: p.pgName,
    oldPrice: Number(p.oldPrice || 0), newPrice: Number(p.newPrice || 0),
  })) : priceGroups.value.map(pg => ({
    pgCode: pg.priceGroupCode, pgName: pg.priceGroupName, oldPrice: 0, newPrice: 0,
  }))
  return {
    goodsCode: raw.goodsCode || '',
    goodsName: raw.goodsName || '',
    unitLevel: Number(raw.unitLevel || 1),
    standardPriceOld: Number(raw.standardPriceOld || 0), standardPriceNew: Number(raw.standardPriceNew || 0),
    purchasePriceOld: Number(raw.purchasePriceOld || 0), purchasePriceNew: Number(raw.purchasePriceNew || 0),
    minPriceOld: Number(raw.minPriceOld || 0), minPriceNew: Number(raw.minPriceNew || 0),
    suggestRetailPriceOld: Number(raw.suggestRetailPriceOld || 0), suggestRetailPriceNew: Number(raw.suggestRetailPriceNew || 0),
    priceGroupPrices: pgPrices,
  }
}

// ========== 添加商品（高级选择窗） ==========
const goodsPickerBrand = ref('')
const goodsPickerCategory = ref('')
const goodsPickerStorage = ref('')
const brandOptions = ref([])
const categoryOptions = ref([])
const storageOptions = ref(['常温','冷藏','冷冻','恒温'])

async function openGoodsPicker() {
  goodsPickerOpen.value = true
  selectedGoods.value = new Set()
  goodsSearch.value = ''
  goodsPickerBrand.value = ''
  goodsPickerCategory.value = ''
  goodsPickerStorage.value = ''
  if (goodsList.value.length === 0) {
    goodsLoading.value = true
    try {
      const [gData, bData, cData] = await Promise.all([
        post('/base/goods/page', { pageNo: 1, pageSize: 500, filters: {} }),
        post('/base/brand/page', { pageNo: 1, pageSize: 200, filters: {} }),
        post('/base/category/page', { pageNo: 1, pageSize: 200, filters: {} }),
      ])
      goodsList.value = (gData.records || []).filter(g => g.status !== 'STOPPED')
      brandOptions.value = (bData.records || []).map(r => r.brandName).filter(Boolean).sort()
      categoryOptions.value = (cData.records || []).map(r => r.categoryName).filter(Boolean).sort()
    } catch (e) { goodsList.value = [] }
    finally { goodsLoading.value = false }
  }
}

/** 解析单位配置取小单位名和条码 */
function parseSmallUnit(g) {
  let cfg = []
  try { cfg = typeof g.unitConfig === 'string' ? JSON.parse(g.unitConfig) : (Array.isArray(g.unitConfig) ? g.unitConfig : []) } catch (_) { }
  const small = (cfg[0] && cfg[0].enabled !== false) ? cfg[0] : null
  return {
    unitName: small?.unitName || g.baseUnit || '',
    barcode: small?.barcode || g.barcode || '',
  }
}

const filteredGoods = computed(() => {
  let list = goodsList.value
  const q = goodsSearch.value.trim().toLowerCase()
  if (q) {
    list = list.filter(g =>
      (g.goodsCode || '').toLowerCase().includes(q) ||
      (g.goodsName || '').toLowerCase().includes(q) ||
      (g.barcode || '').toLowerCase().includes(q)
    )
  }
  if (goodsPickerBrand.value) {
    list = list.filter(g => (g.brandName || '') === goodsPickerBrand.value)
  }
  if (goodsPickerCategory.value) {
    list = list.filter(g => (g.categoryName || '') === goodsPickerCategory.value)
  }
  if (goodsPickerStorage.value) {
    list = list.filter(g => (g.storageProperty || '常温') === goodsPickerStorage.value)
  }
  return list.slice(0, 100)
})

function toggleGoods(code) {
  const s = new Set(selectedGoods.value)
  if (s.has(code)) s.delete(code); else s.add(code)
  selectedGoods.value = s
}

const UNIT_LEVELS = [
  { level: 1, label: '小单位', idx: 0 },
  { level: 2, label: '中单位', idx: 1 },
  { level: 3, label: '大单位', idx: 2 },
]

/** 解析商品 unitConfig，返回哪些级别启用了 + 对应价格 */
function parseUnitConfig(g) {
  let cfg = []
  try { cfg = typeof g.unitConfig === 'string' ? JSON.parse(g.unitConfig) : (Array.isArray(g.unitConfig) ? g.unitConfig : []) } catch (_) { }
  return UNIT_LEVELS.map(ul => {
    const u = cfg[ul.idx]
    const enabled = u ? (u.enabled !== false && ul.idx === 0 ? true : u.enabled) : (ul.idx === 0)
    return {
      level: ul.level, label: ul.label, enabled,
      unitName: (u && u.unitName) || '',
      standardPrice: (u && Number(u.standardPrice || 0)) || 0,
      purchasePrice: (u && Number(u.purchasePrice || 0)) || 0,
      minPrice: (u && Number(u.minPrice || 0)) || 0,
      suggestRetailPrice: (u && Number(u.suggestRetailPrice || 0)) || 0,
    }
  })
}

/** 查询价格组当前价格 */
async function loadPriceGroupPrices(goodsCode, unitLevel) {
  const levelMap = { 1: 'smallPrice', 2: 'middlePrice', 3: 'largePrice' }
  const key = levelMap[unitLevel] || 'smallPrice'
  try {
    const rows = await get(`/base/goods/price-groups?goodsCode=${encodeURIComponent(goodsCode)}`)
    return (Array.isArray(rows) ? rows : []).map(pg => ({
      pgCode: pg.priceGroupCode, pgName: pg.priceGroupName,
      oldPrice: Number(pg[key] || 0), newPrice: Number(pg[key] || 0),
    }))
  } catch (e) { return [] }
}

async function confirmGoodsPick() {
  const existing = new Set(items.value.map(i => `${i.goodsCode}|${i.unitLevel}`))
  for (const g of goodsList.value) {
    if (!selectedGoods.value.has(g.goodsCode)) continue
    const profile = parseUnitConfig(g)
    for (const u of profile) {
      if (!u.enabled) continue
      const key = `${g.goodsCode}|${u.level}`
      if (existing.has(key)) continue
      const pgPrices = await loadPriceGroupPrices(g.goodsCode, u.level)
      items.value.push({
        goodsCode: g.goodsCode, goodsName: g.goodsName,
        unitLevel: u.level,
        standardPriceOld: u.standardPrice, standardPriceNew: u.standardPrice,
        purchasePriceOld: u.purchasePrice, purchasePriceNew: u.purchasePrice,
        minPriceOld: u.minPrice, minPriceNew: u.minPrice,
        suggestRetailPriceOld: u.suggestRetailPrice, suggestRetailPriceNew: u.suggestRetailPrice,
        priceGroupPrices: pgPrices,
      })
    }
  }
  goodsPickerOpen.value = false
}

function removeItem(index) {
  if (header.value.goodsLocked && items.value.length <= 1) {
    errors.value.header = '快速调价不可删除商品'
    return
  }
  items.value.splice(index, 1)
}

// ========== 显示辅助 ==========
const UNIT_LABELS = { 1: '小单位', 2: '中单位', 3: '大单位' }

function isPriceChanged(row, field) {
  return Number(row[field + 'Old'] || 0) !== Number(row[field + 'New'] || 0)
}

function priceInputClass(row, field) {
  return isPriceChanged(row, field) ? 'price-changed' : ''
}

// ========== 保存 / 提交 / 审核 ==========
function detailPayload() {
  return items.value.map(r => ({
    goodsCode: r.goodsCode, goodsName: r.goodsName, unitLevel: r.unitLevel,
    standardPriceOld: r.standardPriceOld, standardPriceNew: r.standardPriceNew,
    purchasePriceOld: r.purchasePriceOld, purchasePriceNew: r.purchasePriceNew,
    minPriceOld: r.minPriceOld, minPriceNew: r.minPriceNew,
    suggestRetailPriceOld: r.suggestRetailPriceOld, suggestRetailPriceNew: r.suggestRetailPriceNew,
    priceGroupPrices: r.priceGroupPrices.map(p => ({
      pgCode: p.pgCode, pgName: p.pgName, oldPrice: p.oldPrice, newPrice: p.newPrice,
    })),
  }))
}

async function doSave() {
  if (items.value.length === 0) { errors.value.header = '请至少添加一个商品'; return }
  try {
    const payload = {
      orderId: header.value.orderId,
      goodsCode: header.value.goodsCode,
      goodsName: header.value.goodsName,
      goodsLocked: header.value.goodsLocked,
      remark: header.value.remark,
      items: detailPayload(),
    }
    const result = await post('/base/goods-price-adjust/save', payload)
    header.value.orderId = result.orderId
    header.value.orderNo = result.orderNo
    emit('saved', result)
    errors.value = {}
  } catch (e) {
    errors.value.header = '保存失败：' + (e.message || '未知错误')
  }
}

async function doSubmit() {
  if (!header.value.orderId) { await doSave(); if (!header.value.orderId) return }
  if (!confirm('提交后将无法修改，确认提交？')) return
  try {
    await post('/base/goods-price-adjust/submit', { orderId: header.value.orderId })
    await loadOrder(header.value.orderId)
    emit('saved')
  } catch (e) { errors.value.header = '提交失败：' + (e.message || '未知错误') }
}

async function doApprove() {
  if (!confirm(`确认审核调价单【${header.value.orderNo}】？\n\n审核后将立即更新商品价格，此操作不可撤销。`)) return
  try {
    const result = await post('/base/goods-price-adjust/approve', { orderId: header.value.orderId })
    emit('saved', result)
    await loadOrder(header.value.orderId)
  } catch (e) { errors.value.header = '审核失败：' + (e.message || '未知错误') }
}

/** 快速调价确定：确认 → 自动保存 + 审核 */
async function doConfirm() {
  if (items.value.length === 0) { errors.value.header = '明细为空，无法确认'; return }
  if (!confirm('确认后将立即更新对应价格（标准售价/参考进价/最低价/建议零售价及价格组价格），是否确认？')) return
  try {
    const payload = {
      orderId: header.value.orderId,
      goodsCode: header.value.goodsCode,
      goodsName: header.value.goodsName,
      goodsLocked: header.value.goodsLocked,
      remark: header.value.remark,
      items: detailPayload(),
    }
    const result = await post('/base/goods-price-adjust/confirm', payload)
    emit('saved', result)
    await loadOrder(header.value.orderId)
  } catch (e) {
    errors.value.header = '确认失败：' + (e.message || '未知错误')
  }
}

async function doReject() {
  const reason = prompt('驳回原因：', '')
  if (reason === null) return
  try {
    await post('/base/goods-price-adjust/reject', { orderId: header.value.orderId, rejectReason: reason })
    await loadOrder(header.value.orderId)
    emit('saved')
  } catch (e) { errors.value.header = '驳回失败：' + (e.message || '未知错误') }
}

async function doDelete() {
  if (!confirm('确认删除该草稿？')) return
  try {
    await post('/base/goods-price-adjust/delete', { orderId: header.value.orderId })
    emit('saved')
    emit('close')
  } catch (e) { errors.value.header = '删除失败：' + (e.message || '未知错误') }
}

function closeDrawer() { emit('close') }
</script>

<template>
  <div v-show="visible" class="gpad-mask">
    <div class="gpad-box">
      <div class="gpad-head">
        <b>商品综合调价单 {{ header.orderNo }}</b>
        <span v-if="header.goodsLocked" class="tag locked">快速调价</span>
        <span class="stag" :class="'s-' + header.status">{{
          {DRAFT:'草稿',PENDING:'待审核',APPROVED:'已审核',REJECTED:'已驳回'}[header.status] || header.status
        }}</span>
        <div style="flex:1"/>
        <button class="btn" @click="closeDrawer">{{ header.goodsLocked ? '关闭' : '关闭' }}</button>
        <!-- 快速调价：仅关闭 + 确定 -->
        <template v-if="header.goodsLocked && isDraft">
          <button class="btn primary" @click="doConfirm">确定</button>
        </template>
        <!-- 普通模式：完整状态流转按钮 -->
        <template v-else>
          <button v-if="canEdit && !header.goodsLocked" class="btn" @click="openGoodsPicker">添加商品</button>
          <button v-if="canEdit" class="btn" @click="doSave">保存</button>
          <button v-if="isDraft" class="btn primary" @click="doSubmit">提交审核</button>
          <button v-if="isPending" class="btn primary" @click="doApprove">审核通过</button>
          <button v-if="isPending" class="btn" @click="doReject">驳回</button>
          <button v-if="isDraft" class="btn danger" @click="doDelete">删除</button>
        </template>
      </div>

      <div class="gpad-body">
        <div v-if="errors.header" class="err">{{ errors.header }}</div>

        <!-- 头部 -->
        <div class="card">
          <div class="grid4">
            <div class="f"><label>调价单号</label><input readonly :value="header.orderNo"/></div>
            <div class="f"><label>商品</label><input readonly :value="header.goodsLocked ? `${header.goodsCode} ${header.goodsName}` : '多商品'"/></div>
            <div class="f"><label>备注</label><input v-model="header.remark" :disabled="!canEdit" placeholder="备注"/></div>
            <div class="f"><label>审核</label><input readonly :value="header.auditUser ? `${header.auditUser} ${String(header.auditTime||'').slice(0,16)}` : '未审核'"/></div>
          </div>
        </div>

        <!-- 明细矩阵 -->
        <div class="card detail-card">
          <div class="dt">调价明细（行=商品×单位级别，列=价格类型）</div>
          <div v-if="loading" class="empty">加载中...</div>
          <div v-else-if="items.length === 0" class="empty">请点击「添加商品」选择要调价的商品</div>
          <div v-else class="scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:36px">#</th>
                  <th>商品编号</th>
                  <th>商品名称</th>
                  <th style="width:70px">单位级别</th>
                  <th v-for="pf in [
                    {k:'standardPrice',l:'标准售价'},{k:'purchasePrice',l:'参考进价'},
                    {k:'minPrice',l:'最低价'},{k:'suggestRetailPrice',l:'建议零售价'}
                  ]" :key="pf.k" style="min-width:150px">
                    {{ pf.l }}<br><span style="font-weight:400;font-size:10px">原价 → 新价</span>
                  </th>
                  <th v-for="pg in priceGroups" :key="pg.priceGroupCode" style="min-width:130px">
                    {{ pg.priceGroupName }}<br><span style="font-weight:400;font-size:10px">原价 → 新价</span>
                  </th>
                  <th v-if="canEdit" style="width:50px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, idx) in items" :key="idx">
                  <td>{{ idx + 1 }}</td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ UNIT_LABELS[row.unitLevel] || row.unitLevel }}</td>
                  <!-- 标准售价 / 参考进价 / 最低价 / 建议零售价 -->
                  <td v-for="pf in ['standardPrice','purchasePrice','minPrice','suggestRetailPrice']" :key="pf">
                    <span class="old">{{ Number(row[pf + 'Old'] || 0).toFixed(4) }}</span>
                    <span class="arrow">→</span>
                    <input v-if="canEdit" type="number" v-model.number="row[pf + 'New']" step="0.0001"
                           class="cell-input" :class="priceInputClass(row, pf)" />
                    <span v-else class="val" :class="priceInputClass(row, pf)">
                      {{ Number(row[pf + 'New'] || 0).toFixed(4) }}
                    </span>
                  </td>
                  <!-- 价格组价格 -->
                  <td v-for="pg in priceGroups" :key="'pg-'+pg.priceGroupCode">
                    <template v-for="pgp in row.priceGroupPrices" :key="pgp.pgCode">
                      <template v-if="pgp.pgCode === pg.priceGroupCode">
                        <span class="old">{{ Number(pgp.oldPrice || 0).toFixed(4) }}</span>
                        <span class="arrow">→</span>
                        <input v-if="canEdit" type="number" v-model.number="pgp.newPrice" step="0.0001"
                               class="cell-input" :class="{ 'price-changed': Number(pgp.oldPrice) !== Number(pgp.newPrice) }" />
                        <span v-else class="val" :class="{ 'price-changed': Number(pgp.oldPrice) !== Number(pgp.newPrice) }">
                          {{ Number(pgp.newPrice || 0).toFixed(4) }}
                        </span>
                      </template>
                    </template>
                  </td>
                  <td v-if="canEdit"><button class="link danger" @click="removeItem(idx)">删除</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="sum">商品行数：{{ items.length }}</div>
      </div>
    </div>
  </div>

  <!--
    添加商品弹窗必须放在 .gpad-mask 外面：
    .gpad-mask 为让抽屉左侧可点击设了 pointer-events: none，
    只有 .gpad-box 重新开启了 auto。弹窗嵌在 mask 内会继承 none 无法操作。
  -->
  <div v-if="goodsPickerOpen" class="picker-mask" @click.self="goodsPickerOpen=false">
    <div class="picker-box picker-box-wide">
      <div class="picker-head">
        <b>选择商品</b>
        <div style="flex:1"/>
        <button class="btn" @click="goodsPickerOpen=false">×</button>
      </div>
      <!-- 搜索 + 筛选栏 -->
      <div class="picker-filters">
        <input v-model="goodsSearch" placeholder="商品编号/名称/条码" class="search" style="flex:1.5" />
        <select v-model="goodsPickerBrand" class="search">
          <option value="">全部品牌</option>
          <option v-for="b in brandOptions" :key="b" :value="b">{{ b }}</option>
        </select>
        <select v-model="goodsPickerCategory" class="search">
          <option value="">全部分类</option>
          <option v-for="c in categoryOptions" :key="c" :value="c">{{ c }}</option>
        </select>
        <select v-model="goodsPickerStorage" class="search">
          <option value="">全部存储属性</option>
          <option v-for="s in storageOptions" :key="s" :value="s">{{ s }}</option>
        </select>
      </div>
      <!-- 表格 -->
      <div class="picker-body">
        <div v-if="goodsLoading" class="p-empty">加载中...</div>
        <div v-else-if="filteredGoods.length===0" class="p-empty">无匹配商品</div>
        <table v-else class="pg-table">
          <thead>
            <tr>
              <th style="width:34px"><input type="checkbox" :checked="filteredGoods.length>0 && filteredGoods.every(g=>selectedGoods.has(g.goodsCode))" @change="filteredGoods.forEach(g=>toggleGoods(g.goodsCode))" /></th>
              <th>商品编号</th><th>商品名称</th><th style="width:90px">规格</th>
              <th style="width:70px">小单位</th><th style="width:110px">小条码</th>
              <th style="width:80px">标准售价</th><th style="width:80px">品牌</th>
              <th style="width:90px">商品分类</th><th style="width:70px">存储属性</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="g in filteredGoods" :key="g.goodsCode" :class="{sel:selectedGoods.has(g.goodsCode)}" @click="toggleGoods(g.goodsCode)">
              <td><input type="checkbox" :checked="selectedGoods.has(g.goodsCode)" /></td>
              <td class="c">{{ g.goodsCode }}</td>
              <td class="n">{{ g.goodsName }}</td>
              <td>{{ g.spec || '-' }}</td>
              <td>{{ parseSmallUnit(g).unitName }}</td>
              <td class="bar">{{ parseSmallUnit(g).barcode }}</td>
              <td class="num">{{ Number(g.standardPrice||0).toFixed(2) }}</td>
              <td>{{ g.brandName || '-' }}</td>
              <td>{{ g.categoryName || '-' }}</td>
              <td>{{ g.storageProperty || '常温' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="picker-foot">
        <span>已选 {{ selectedGoods.size }} 个</span>
        <button class="btn primary" @click="confirmGoodsPick">确定</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gpad-mask { position:fixed; top:48px; right:0; bottom:0; left:299px; z-index:900; display:flex; pointer-events:none; animation:fadeIn .2s; }
.gpad-box { flex:1; background:#fff; display:flex; flex-direction:column; border-left:1px solid var(--line); box-shadow:-6px 0 24px rgba(15,46,88,.12); pointer-events:auto; animation:slideIn .25s; }
.gpad-head { display:flex; align-items:center; gap:10px; height:46px; padding:0 16px; border-bottom:1px solid var(--line-soft); }
.gpad-head b { font-size:15px; }
.gpad-body { flex:1; overflow:auto; padding:12px 16px; display:flex; flex-direction:column; gap:12px; background:#f5f7fa; }
.card { background:#fff; border-radius:6px; padding:12px; }
.grid4 { display:grid; grid-template-columns:repeat(4,1fr); gap:8px 16px; }
.f { display:flex; align-items:center; gap:6px; }
.f label { font-size:12px; font-weight:600; width:72px; text-align:right; flex-shrink:0; }
.f input { flex:1; height:30px; padding:0 8px; border:1px solid #dcdfe6; border-radius:4px; font-size:12px; }
.f input[readonly] { background:#f5f7fa; }
.detail-card { flex:1; display:flex; flex-direction:column; min-height:300px; }
.dt { font-weight:900; color:var(--primary); margin-bottom:8px; font-size:13px; }
.scroll { flex:1; overflow:auto; }
.scroll table { width:100%; border-collapse:collapse; font-size:11px; }
.scroll th { position:sticky; top:0; background:#f5f7fa; padding:6px 4px; text-align:center; white-space:nowrap; border-bottom:1px solid var(--line); }
.scroll td { padding:4px; border-bottom:1px solid #f0f2f5; white-space:nowrap; text-align:center; }
.cell-input { width:80px; height:24px; padding:0 4px; border:1px solid #dcdfe6; border-radius:3px; font-size:11px; text-align:right; }
.price-changed { border-color:#e6a23c !important; background:#fdf6ec !important; }
.old { color:#909399; font-size:11px; }
.arrow { color:#c0c4cc; margin:0 2px; font-size:10px; }
.val { font-weight:700; }
.val.price-changed { color:#e6a23c; }
.empty { padding:40px; text-align:center; color:#909399; font-size:13px; }
.err { padding:6px 10px; background:#fef0f0; border:1px solid #fde2e2; border-radius:4px; color:#f56c6c; font-size:12px; }
.sum { font-size:12px; color:#606266; }
.tag { padding:2px 8px; border-radius:4px; font-size:11px; font-weight:700; }
.tag.locked { background:#ecf5ff; color:#409eff; }
.stag { padding:2px 8px; border-radius:10px; font-size:11px; font-weight:700; }
.s-DRAFT { background:#f4f4f5; color:#909399; }
.s-PENDING { background:#fdf6ec; color:#e6a23c; }
.s-APPROVED { background:#f0f9eb; color:#67c23a; }
.s-REJECTED { background:#fef0f0; color:#f56c6c; }
.link { background:none; border:none; cursor:pointer; font-size:11px; color:#409eff; }
.link.danger { color:#f56c6c; }
.btn.danger { border-color:#f56c6c; color:#f56c6c; }

.picker-mask { position:fixed; inset:0; background:rgba(0,0,0,.4); z-index:2000; display:flex; justify-content:center; align-items:center; }
.picker-box { width:600px; max-height:70vh; background:#fff; border-radius:8px; display:flex; flex-direction:column; }
.picker-box-wide { width:1000px; max-width:95vw; }
.picker-head { display:flex; justify-content:space-between; align-items:center; padding:10px 14px; border-bottom:1px solid #e5e7eb; }
.picker-filters { display:flex; gap:8px; padding:8px 14px; border-bottom:1px solid #f0f2f5; flex-wrap:wrap; }
.picker-filters .search { flex:1; min-width:100px; height:30px; padding:0 8px; border:1px solid #dcdfe6; border-radius:4px; font-size:11px; }
.picker-body { flex:1; overflow:auto; padding:0; max-height:420px; }
.pg-table { width:100%; border-collapse:collapse; font-size:11px; }
.pg-table th { position:sticky; top:0; background:#f5f7fa; padding:6px 4px; text-align:left; white-space:nowrap; border-bottom:1px solid var(--line); z-index:1; }
.pg-table td { padding:4px; border-bottom:1px solid #f5f7fa; white-space:nowrap; }
.pg-table tr { cursor:pointer; }
.pg-table tr:hover { background:#f5f7fa; }
.pg-table tr.sel { background:#ecf5ff; }
.pg-table .c { color:#409eff; font-family:monospace; }
.pg-table .bar { font-family:monospace; font-size:10px; color:#909399; }
.pg-table .num { text-align:right; font-variant-numeric:tabular-nums; }
.picker-foot { display:flex; justify-content:space-between; align-items:center; padding:10px 14px; border-top:1px solid #e5e7eb; font-size:12px; }
.p-empty { padding:30px; text-align:center; color:#909399; }

@keyframes fadeIn { from{opacity:0} to{opacity:1} }
@keyframes slideIn { from{transform:translateX(60px);opacity:.6} to{transform:translateX(0);opacity:1} }
@media(max-width:900px){ .gpad-mask{left:0} }
</style>