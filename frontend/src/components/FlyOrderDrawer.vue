<script setup>
import { ref, watch, computed, onMounted, nextTick } from 'vue'
import { post, get } from '../api/client.js'
import InlineGoodsPicker from './InlineGoodsPicker.vue'
import { clampDecimalInput, clampQtyInput, roundTo, PRICE_DECIMALS, AMOUNT_DECIMALS } from '../utils/decimal.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' },   // 'add' | 'edit'
  editData: { type: Object, default: null },
})

const emit = defineEmits(['close', 'saved'])

const title = computed(() => props.mode === 'edit' ? '编辑飞单' : '新建飞单')

// ==================== 基础数据 ====================
const allGoods = ref([])
const supplierList = ref([])
const customerList = ref([])
const salesmanList = ref([])

const headerForm = ref({})
const detailList = ref([])
const saving = ref(false)

// ==================== 表格内联录入 ====================
const cellRefs = ref({})

const editableCols = ['goods', 'unit', 'qty', 'purchasePrice', 'salesPrice', 'taxRate', 'remark']

function makeEmptyRow() {
  return {
    goodsCode: '', goodsName: '', spec: '',
    unitName: '', unitLevel: 1, convertQty: 1,
    unitOptions: [],
    qty: null,
    purchasePrice: null, salesPrice: null,
    purchaseAmount: null, salesAmount: null,
    taxRate: '',
    remark: '',
    unitConfig: null,
    goodsSearch: '',
    purchasePriceAuto: false,
    salesPriceAuto: false,
  }
}

const filledRows = computed(() => detailList.value.filter(r => r.goodsCode))

function bindCell(rowIndex, colKey) {
  return el => {
    const k = `${rowIndex}:${colKey}`
    if (el) cellRefs.value[k] = el
    else delete cellRefs.value[k]
  }
}

function focusCell(rowIndex, colKey) {
  nextTick(() => {
    const el = cellRefs.value[`${rowIndex}:${colKey}`]
    if (el?.focus) el.focus()
    else if (el?.$el?.focus) el.$el.focus()
  })
}

// ==================== 初始化 ====================

function resetForm() {
  headerForm.value = {
    supplierCode: '', supplierName: '',
    customerCode: '', customerName: '',
    salesman: '',
    billDate: new Date().toISOString().slice(0, 10),
    remark: '',
  }
  detailList.value = [makeEmptyRow()]
}

watch(() => props.visible, async (val) => {
  if (val) {
    resetForm()
    await loadBaseData()
    if (props.mode === 'edit' && props.editData) {
      await loadEditData(props.editData)
    }
  }
})

async function loadBaseData() {
  const [g, s, c] = await Promise.all([
    post('/base/goods/page', { pageNo: 1, pageSize: 2000, filters: {} }).catch(() => ({ records: [] })),
    post('/base/supplier/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => ({ records: [] })),
    post('/base/customer/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => ({ records: [] })),
  ])
  allGoods.value = (g.records || []).filter(x => String(x.status || '').toUpperCase() !== 'STOPPED')
  supplierList.value = s.records || []
  customerList.value = c.records || []

  try {
    const sm = await post('/base/employee/salesmen', {})
    const list = Array.isArray(sm) ? sm : (sm?.records || [])
    salesmanList.value = list.map(item => ({
      code: item.code || item.CODE || item.employeeCode || '',
      name: item.name || item.NAME || item.employeeName || '',
    })).filter(item => item.name)
  } catch (e) { salesmanList.value = [] }
}

// ==================== 供应商/客户选择 ====================

function onSupplierChange(code) {
  headerForm.value.supplierCode = code
  const hit = supplierList.value.find(p => (p.supplierCode || p.supplierId) === code)
  headerForm.value.supplierName = hit?.supplierName || ''
  // 换供应商 → 重新带出采购价
  detailList.value.forEach(r => { if (r.goodsCode) applyPurchasePrice(r) })
}

function onCustomerChange(code) {
  headerForm.value.customerCode = code
  const hit = customerList.value.find(p => (p.customerCode || p.customerId) === code)
  headerForm.value.customerName = hit?.customerName || ''
  if (hit?.salesman) headerForm.value.salesman = hit.salesman
  // 换客户 → 重新带出销售价
  detailList.value.forEach(r => { if (r.goodsCode) applySalesPrice(r) })
}

// ==================== 商品选择 ====================

function onRowGoodsSelect(row, index, g) {
  row.goodsCode = g.goodsCode
  row.goodsName = g.goodsName || ''
  row.spec = g.spec || ''
  row.unitConfig = g.unitConfig ?? null
  row.goodsSearch = g.goodsCode
  row.taxRate = g.taxRate || ''
  row.purchasePriceAuto = false
  row.salesPriceAuto = false
  row.purchasePrice = null
  row.salesPrice = null

  // 解析多单位，默认小单位
  const opts = parseUnitOptions(g.unitConfig)
  row.unitOptions = opts
  const def = opts.find(o => o.level === 1) || opts[0]
  if (def) {
    row.unitName = def.name
    row.unitLevel = def.level
    row.convertQty = def.convertQty
  } else {
    row.unitName = g.baseUnit || ''
    row.unitLevel = 1
    row.convertQty = 1
  }
  if (row.qty == null) row.qty = 1

  // 带出双价格
  applyPurchasePrice(row)
  applySalesPrice(row)
  recalcRow(row)
  ensureTrailingBlankRow()
  nextTick(() => focusCell(index, 'qty'))
}

// ==================== 单位切换 ====================

function onUnitChange(row) {
  const opt = row.unitOptions.find(o => o.level === row.unitLevel)
  if (opt) {
    row.unitName = opt.name
    row.convertQty = opt.convertQty
  }
  // 切单位 → 重新带出价格
  applyPurchasePrice(row)
  applySalesPrice(row)
  recalcRow(row)
}

// ==================== 取价逻辑 ====================

function parseUnitConfig(raw) {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try { return JSON.parse(String(raw)) } catch (_) { return [] }
}

function parseUnitOptions(raw) {
  const cfg = parseUnitConfig(raw)
  const labels = ['小单位', '中单位', '大单位']
  const opts = []
  for (let i = 0; i < 3; i++) {
    const u = cfg[i]
    const enabled = i === 0 ? true : (u && u.enabled !== false)
    if (u && enabled && u.unitName) {
      opts.push({
        level: i + 1, label: labels[i], name: u.unitName,
        convertQty: Number(u.convertQty) || 1,
        purchasePrice: Number(u.purchasePrice) || 0,
        standardPrice: Number(u.standardPrice) || 0,
      })
    }
  }
  return opts
}

/** 采购价：unit_config.purchasePrice → 兜底 latest_purchase_price × convertQty */
async function applyPurchasePrice(row) {
  if (!row.goodsCode) return
  if (!row.purchasePriceAuto && Number(row.purchasePrice) > 0) return

  try {
    const r = await get(`/sales/fly-order/goods-price?goodsCode=${encodeURIComponent(row.goodsCode)}`
      + `&unitLevel=${row.unitLevel}`)
    const p = Number(r?.purchasePrice) || 0
    if (p > 0 || row.purchasePriceAuto) {
      row.purchasePrice = p
      row.purchasePriceAuto = true
    }
  } catch (_) {}
  recalcRow(row)
}

/** 销售价：按销售订单取价逻辑（/base/goods/sale-price） */
async function applySalesPrice(row) {
  if (!row.goodsCode) return
  if (!row.salesPriceAuto && Number(row.salesPrice) > 0) return

  try {
    const r = await get(`/base/goods/sale-price?goodsCode=${encodeURIComponent(row.goodsCode)}`
      + `&customerCode=${encodeURIComponent(headerForm.value.customerCode || '')}`
      + `&unitLevel=${row.unitLevel}`)
    const p = Number(r?.price) || 0
    if (p > 0 || row.salesPriceAuto) {
      row.salesPrice = p
      row.salesPriceAuto = true
      row.priceSourceText = r?.priceSourceText || ''
    }
  } catch (_) {}
  recalcRow(row)
}

// ==================== 计算 ====================

function recalcRow(row) {
  const qty = Number(row.qty) || 0
  const pp = Number(row.purchasePrice) || 0
  const sp = Number(row.salesPrice) || 0
  row.purchaseAmount = roundTo(qty * pp, AMOUNT_DECIMALS)
  row.salesAmount = roundTo(qty * sp, AMOUNT_DECIMALS)
}

function onPriceInput(row, field) {
  if (field === 'purchasePrice') row.purchasePriceAuto = false
  if (field === 'salesPrice') row.salesPriceAuto = false
  recalcRow(row)
}

function onQtyInput(row) {
  recalcRow(row)
}

/** 修改金额 → 反算单价 */
function onAmountInput(row, field) {
  const amt = Number(row[field]) || 0
  const qty = Number(row.qty) || 0
  if (qty > 0) {
    const price = amt / qty
    if (field === 'purchaseAmount') {
      row.purchasePrice = roundTo(price, PRICE_DECIMALS)
      row.purchasePriceAuto = false
    } else {
      row.salesPrice = roundTo(price, PRICE_DECIMALS)
      row.salesPriceAuto = false
    }
  }
}

// ==================== 合计 ====================

const purchaseTotal = computed(() =>
  filledRows.value.reduce((s, r) => s + (Number(r.purchaseAmount) || 0), 0).toFixed(2))
const salesTotal = computed(() =>
  filledRows.value.reduce((s, r) => s + (Number(r.salesAmount) || 0), 0).toFixed(2))
const profitTotal = computed(() =>
  (Number(salesTotal.value) - Number(purchaseTotal.value)).toFixed(2))

// ==================== 行操作 ====================

function removeRow(index) {
  detailList.value.splice(index, 1)
  ensureTrailingBlankRow()
}

function ensureTrailingBlankRow() {
  const list = detailList.value
  if (list.length === 0 || list[list.length - 1].goodsCode) {
    list.push(makeEmptyRow())
  }
}

/** 手动添加空白行 */
function addBlankRow() {
  const list = detailList.value
  // 如果末尾已经是空行，直接聚焦它的商品搜索框
  if (list.length > 0 && !list[list.length - 1].goodsCode) {
    nextTick(() => focusCell(list.length - 1, 'goods'))
    return
  }
  list.push(makeEmptyRow())
  nextTick(() => focusCell(list.length - 1, 'goods'))
}

// ==================== 保存 ====================

async function doSave(andAudit = false) {
  if (!headerForm.value.supplierCode) { alert('请选择供应商'); return }
  if (!headerForm.value.customerCode) { alert('请选择客户'); return }
  if (filledRows.value.length === 0) { alert('请至少添加一行商品'); return }
  for (const r of filledRows.value) {
    if (!r.qty || Number(r.qty) <= 0) { alert(`商品 ${r.goodsName} 数量必须大于 0`); return }
  }

  saving.value = true
  try {
    const payload = {
      supplierCode: headerForm.value.supplierCode,
      supplierName: headerForm.value.supplierName,
      customerCode: headerForm.value.customerCode,
      customerName: headerForm.value.customerName,
      salesman: headerForm.value.salesman,
      billDate: headerForm.value.billDate,
      remark: headerForm.value.remark,
      details: filledRows.value.map(r => ({
        goodsCode: r.goodsCode,
        goodsName: r.goodsName,
        spec: r.spec,
        unitName: r.unitName,
        unitLevel: r.unitLevel,
        convertQty: r.convertQty,
        qty: r.qty,
        purchasePrice: r.purchasePrice,
        salesPrice: r.salesPrice,
        purchaseAmount: r.purchaseAmount,
        salesAmount: r.salesAmount,
        taxRate: r.taxRate,
        remark: r.remark,
      })),
    }

    const isEdit = props.mode === 'edit' && headerForm.value.flyId
    const endpoint = isEdit ? '/sales/fly-order/update' : '/sales/fly-order/create'
    if (isEdit) payload.flyId = headerForm.value.flyId
    const res = await post(endpoint, payload)
    if (!res?.flyId && !res?.success) { alert('保存失败'); return }

    const savedFlyId = res.flyId || headerForm.value.flyId
    if (andAudit) {
      const auditRes = await post('/sales/fly-order/audit', { flyId: savedFlyId })
      if (auditRes?.success) {
        alert(auditRes.effect || '审核成功')
      } else {
        alert('保存成功，但审核失败：' + (auditRes?.message || '未知错误'))
      }
    } else {
      alert(isEdit ? '飞单已更新' : '飞单已保存')
    }
    emit('saved')
    emit('close')
  } catch (e) {
    alert('保存失败：' + (e?.message || e))
  } finally {
    saving.value = false
  }
}

// ==================== 编辑模式 ====================

async function loadEditData(row) {
  const key = row?.flyId || row?.flyNo
  if (!key) return
  try {
    const detail = await get(`/sales/fly-order/detail?flyId=${encodeURIComponent(key)}`)
    if (!detail) return
    headerForm.value = {
      flyId: detail.flyId,
      flyNo: detail.flyNo,
      supplierCode: detail.supplierCode || '',
      supplierName: detail.supplierName || '',
      customerCode: detail.customerCode || '',
      customerName: detail.customerName || '',
      salesman: detail.salesman || '',
      billDate: detail.billDate || new Date().toISOString().slice(0, 10),
      remark: detail.remark || '',
    }
    const items = detail.details || []
    detailList.value = items.map(d => {
      const g = allGoods.value.find(x => x.goodsCode === d.goodsCode)
      const opts = parseUnitOptions(g?.unitConfig)
      return {
        goodsCode: d.goodsCode,
        goodsName: d.goodsName,
        spec: d.spec,
        unitName: d.unitName,
        unitLevel: d.unitLevel || 1,
        convertQty: d.convertQty || 1,
        unitOptions: opts,
        qty: d.qty,
        purchasePrice: d.purchasePrice,
        salesPrice: d.salesPrice,
        purchaseAmount: d.purchaseAmount,
        salesAmount: d.salesAmount,
        taxRate: d.taxRate || '',
        remark: d.remark || '',
        unitConfig: g?.unitConfig ?? null,
        goodsSearch: d.goodsCode,
        purchasePriceAuto: false,
        salesPriceAuto: false,
      }
    })
    ensureTrailingBlankRow()
  } catch (e) { console.error('loadEditData', e) }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="fly-drawer-overlay" @click.self="emit('close')">
      <div class="fly-drawer">
        <!-- 头部 -->
        <div class="fly-drawer-header">
          <span class="fly-drawer-title">{{ title }}</span>
          <button class="fly-drawer-close" @click="emit('close')">×</button>
        </div>

        <!-- 内容区 -->
        <div class="fly-drawer-body">
          <!-- 基本信息 -->
          <div class="fly-section">
            <div class="fly-section-title">基本信息</div>
            <div class="fly-form-grid">
              <div class="fly-form-item">
                <label>飞单号</label>
                <input class="fly-input" :value="headerForm.flyNo || '保存后自动生成'" disabled>
              </div>
              <div class="fly-form-item">
                <label>单据日期 <span class="req">*</span></label>
                <input type="date" class="fly-input" v-model="headerForm.billDate">
              </div>
              <div class="fly-form-item">
                <label>供应商 <span class="req">*</span></label>
                <select class="fly-input" v-model="headerForm.supplierCode" @change="onSupplierChange($event.target.value)">
                  <option value="">请选择</option>
                  <option v-for="s in supplierList" :key="s.supplierCode || s.supplierId"
                          :value="s.supplierCode || s.supplierId">{{ s.supplierName }}</option>
                </select>
              </div>
              <div class="fly-form-item">
                <label>客户 <span class="req">*</span></label>
                <select class="fly-input" v-model="headerForm.customerCode" @change="onCustomerChange($event.target.value)">
                  <option value="">请选择</option>
                  <option v-for="c in customerList" :key="c.customerCode || c.customerId"
                          :value="c.customerCode || c.customerId">{{ c.customerName }}</option>
                </select>
              </div>
              <div class="fly-form-item">
                <label>业务员</label>
                <select class="fly-input" v-model="headerForm.salesman">
                  <option value="">请选择</option>
                  <option v-for="s in salesmanList" :key="s.code" :value="s.name">{{ s.name }}</option>
                </select>
              </div>
              <div class="fly-form-item fly-form-item-wide">
                <label>备注</label>
                <input class="fly-input" v-model="headerForm.remark" placeholder="可选">
              </div>
            </div>
          </div>

          <!-- 商品明细 -->
          <div class="fly-section">
            <div class="fly-section-title">
              商品明细
              <button class="fly-add-row-btn" @click="addBlankRow">+ 添加商品</button>
            </div>
            <div class="fly-table-wrap">
              <table class="fly-table">
                <thead>
                  <tr>
                    <th style="width:120px">商品编号</th>
                    <th style="width:160px">商品名称</th>
                    <th style="width:80px">规格</th>
                    <th style="width:70px">单位</th>
                    <th style="width:60px">数量</th>
                    <th style="width:70px">采购价</th>
                    <th style="width:70px">销售价</th>
                    <th style="width:45px">税率</th>
                    <th style="width:75px">采购金额</th>
                    <th style="width:75px">销售金额</th>
                    <th style="width:90px">备注</th>
                    <th style="width:34px"></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, idx) in detailList" :key="idx" :class="{ 'fly-row-filled': row.goodsCode }">
                    <!-- 商品编号 -->
                    <td>
                      <InlineGoodsPicker
                        v-if="!row.goodsCode"
                        :ref="bindCell(idx, 'goods')"
                        v-model="row.goodsSearch"
                        :goods-list="allGoods"
                        @select="g => onRowGoodsSelect(row, idx, g)"
                      />
                      <span v-else class="fly-cell-code">{{ row.goodsCode }}</span>
                    </td>
                    <!-- 商品名称 -->
                    <td>
                      <span class="fly-cell-text">{{ row.goodsName }}</span>
                    </td>
                    <!-- 规格 -->
                    <td><span class="fly-cell-text">{{ row.spec }}</span></td>
                    <!-- 单位 -->
                    <td>
                      <select v-if="row.unitOptions.length > 1"
                              class="fly-input fly-input-sm"
                              v-model="row.unitLevel"
                              @change="onUnitChange(row)">
                        <option v-for="o in row.unitOptions" :key="o.level" :value="o.level">{{ o.name }}</option>
                      </select>
                      <span v-else class="fly-cell-text">{{ row.unitName }}</span>
                    </td>
                    <!-- 数量 -->
                    <td>
                      <input class="fly-input fly-input-sm fly-input-right"
                             type="number" step="0.001"
                             v-model="row.qty"
                             @input="onQtyInput(row)"
                             @keydown.enter.prevent="focusCell(idx + 1, 'goods')">
                    </td>
                    <!-- 采购价 -->
                    <td>
                      <input class="fly-input fly-input-sm fly-input-right"
                             type="number" step="0.01"
                             v-model="row.purchasePrice"
                             @input="onPriceInput(row, 'purchasePrice')"
                             :placeholder="row.purchasePriceAuto ? '自动' : ''">
                    </td>
                    <!-- 销售价 -->
                    <td>
                      <input class="fly-input fly-input-sm fly-input-right"
                             type="number" step="0.01"
                             v-model="row.salesPrice"
                             @input="onPriceInput(row, 'salesPrice')"
                             :placeholder="row.salesPriceAuto ? '自动' : ''">
                    </td>
                    <!-- 税率 -->
                    <td>
                      <input class="fly-input fly-input-sm" v-model="row.taxRate" placeholder="13%">
                    </td>
                    <!-- 采购金额 -->
                    <td>
                      <input class="fly-input fly-input-sm fly-input-right"
                             type="number" step="0.01"
                             v-model="row.purchaseAmount"
                             @input="onAmountInput(row, 'purchaseAmount')">
                    </td>
                    <!-- 销售金额 -->
                    <td>
                      <input class="fly-input fly-input-sm fly-input-right"
                             type="number" step="0.01"
                             v-model="row.salesAmount"
                             @input="onAmountInput(row, 'salesAmount')">
                    </td>
                    <!-- 备注 -->
                    <td>
                      <input class="fly-input fly-input-sm" v-model="row.remark">
                    </td>
                    <!-- 删除 -->
                    <td>
                      <button v-if="row.goodsCode" class="fly-btn-icon" @click="removeRow(idx)" title="删除">×</button>
                    </td>
                  </tr>
                </tbody>
                <tfoot>
                  <tr class="fly-tfoot">
                    <td colspan="2"></td>
                    <td></td>
                    <td></td>
                    <td class="fly-cell-right">合计：</td>
                    <td></td>
                    <td></td>
                    <td></td>
                    <td class="fly-cell-right fly-total">¥{{ purchaseTotal }}</td>
                    <td class="fly-cell-right fly-total">¥{{ salesTotal }}</td>
                    <td class="fly-cell-right fly-profit">毛利: ¥{{ profitTotal }}</td>
                    <td></td>
                  </tr>
                </tfoot>
              </table>
            </div>
          </div>
        </div>

        <!-- 底部 -->
        <div class="fly-drawer-footer">
          <button class="fly-btn fly-btn-default" @click="emit('close')">取消</button>
          <button class="fly-btn fly-btn-default" @click="doSave(false)" :disabled="saving">保存草稿</button>
          <button class="fly-btn fly-btn-primary" @click="doSave(true)" :disabled="saving">保存并审核</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.fly-drawer-overlay {
  position: fixed; inset: 0; z-index: 2000;
  background: rgba(0,0,0,0.4);
  display: flex; justify-content: flex-end;
}
.fly-drawer {
  width: 1200px; max-width: 98vw; height: 100vh;
  background: #fff; display: flex; flex-direction: column;
  box-shadow: -4px 0 16px rgba(0,0,0,0.12);
}
.fly-drawer-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px; border-bottom: 1px solid #e8e8e8;
  background: #fafafa;
}
.fly-drawer-title { font-size: 16px; font-weight: 600; color: #333; }
.fly-drawer-close {
  border: none; background: none; font-size: 22px; cursor: pointer;
  color: #999; line-height: 1;
}
.fly-drawer-body { flex: 1; overflow-y: auto; padding: 16px 20px; }

.fly-section { margin-bottom: 20px; }
.fly-section-title {
  font-size: 13px; font-weight: 600; color: #666;
  margin-bottom: 10px; padding-left: 8px;
  border-left: 3px solid #409eff;
  display: flex; align-items: center; justify-content: space-between;
}
.fly-add-row-btn {
  font-size: 12px; height: 26px; padding: 0 10px;
  border: 1px dashed #409eff; border-radius: 4px;
  background: #ecf5ff; color: #409eff; cursor: pointer;
  transition: all 0.2s;
}
.fly-add-row-btn:hover { background: #d9ecff; border-style: solid; }

.fly-form-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px 16px; }
.fly-form-item { display: flex; flex-direction: column; gap: 4px; }
.fly-form-item-wide { grid-column: span 3; }
.fly-form-item label { font-size: 12px; color: #666; }
.req { color: #f56c6c; }

.fly-input {
  height: 32px; padding: 0 8px;
  border: 1px solid #d9d9d9; border-radius: 4px;
  font-size: 13px; color: #333;
  outline: none; transition: border-color 0.2s;
}
.fly-input:focus { border-color: #409eff; }
.fly-input:disabled { background: #f5f5f5; color: #999; }
.fly-input-sm { height: 28px; font-size: 12px; padding: 0 4px; }
.fly-input-right { text-align: right; }

.fly-table-wrap { }
.fly-table {
  width: 100%; border-collapse: collapse; font-size: 12px;
  table-layout: fixed;
}
.fly-table th {
  background: #fafafa; color: #666; font-weight: 500;
  padding: 8px 4px; border-bottom: 1px solid #e8e8e8;
  text-align: center; white-space: nowrap;
}
.fly-table td {
  padding: 4px; border-bottom: 1px solid #f0f0f0;
  vertical-align: middle;
}
.fly-table td .fly-input { width: 100%; box-sizing: border-box; }
.fly-row-filled { background: #fff; }
.fly-row-filled:hover { background: #f5f7fa; }

.fly-cell-text { display: block; padding: 0 4px; color: #333; }
.fly-cell-right { text-align: right; padding: 0 6px; color: #333; }
.fly-cell-code { display: block; padding: 0 4px; color: #666; font-size: 12px; }

.fly-tfoot td {
  border-top: 2px solid #e8e8e8; padding: 8px 6px;
  font-weight: 600; background: #fafafa;
}
.fly-total { color: #333; }
.fly-profit { color: #e6a23c; font-size: 12px; }

.fly-btn-icon {
  border: none; background: #ff4d4f; color: #fff;
  width: 20px; height: 20px; border-radius: 50%;
  font-size: 12px; cursor: pointer; line-height: 1;
  display: flex; align-items: center; justify-content: center;
}

.fly-drawer-footer {
  display: flex; justify-content: flex-end; gap: 8px;
  padding: 12px 20px; border-top: 1px solid #e8e8e8;
  background: #fafafa;
}
.fly-btn {
  height: 34px; padding: 0 16px; border-radius: 4px;
  font-size: 13px; cursor: pointer; border: 1px solid #d9d9d9;
  transition: all 0.2s;
}
.fly-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.fly-btn-default { background: #fff; color: #333; }
.fly-btn-default:hover:not(:disabled) { border-color: #409eff; color: #409eff; }
.fly-btn-primary { background: #409eff; border-color: #409eff; color: #fff; }
.fly-btn-primary:hover:not(:disabled) { background: #66b1ff; }
</style>
