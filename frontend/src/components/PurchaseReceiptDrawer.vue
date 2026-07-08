<script setup>
/**
 * 采购收货单抽屉 —— 未审核可改单价，已审核只可查看。
 *
 * 金额口径（与采购退货单一致）：
 *   · 单价为**含税单价** → 商品金额为含税金额
 *   · 税额 = 含税金额 × 税率 ÷ (1 + 税率)（价内税倒算）
 *   · 不含税金额 = 含税金额 − 税额
 *   · 应付结算按**含税商品金额**
 *
 * 改价链路：
 *   未审核期间改价只落在收货单上（可反复改）；
 *   **审核时**后端才把单价回写到来源采购入库单，并按差额重算库存成本单价与库存金额，
 *   同时写一条「成本调整」库存流水留痕。
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 收货单 ID 或单号 */
  receiptId: { type: String, default: '' },
  /** 外部强制只读（列表点「查看」时传 true） */
  readonly: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'save'])

const head = ref({})
const detailList = ref([])
const errors = ref({})
const loading = ref(false)

const isApproved = computed(() => head.value.status === 'APPROVED')
const canEdit = computed(() => !props.readonly && !isApproved.value)

/** 只读原因 */
const readonlyReason = computed(() => {
  if (canEdit.value) return ''
  if (isApproved.value) return '该收货单已审核，只可查看。如需改价请先反审核。'
  return '该收货单当前状态不可编辑，只可查看。'
})

const statusText = computed(() => ({
  PENDING: '待审核', APPROVED: '已审核', CANCELLED: '已作废',
}[head.value.status] || head.value.status || ''))

// 含税商品金额 / 税额 / 不含税金额：随明细实时算，改价即时反映
const totalGoodsAmount = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.qty || 0) * Number(r.price || 0), 0)
)
const totalTaxAmount = computed(() =>
  detailList.value.reduce((s, r) => s + rowTax(r), 0)
)
const totalUntaxedAmount = computed(() => totalGoodsAmount.value - totalTaxAmount.value)

watch(() => [props.visible, props.receiptId], async ([val]) => {
  if (!val || !props.receiptId) return
  errors.value = {}
  await loadReceipt(props.receiptId)
})

async function loadReceipt(id) {
  loading.value = true
  try {
    const data = await get(`/purchase/receipt/detail?id=${encodeURIComponent(id)}`)
    head.value = data
    detailList.value = (data.details || []).map(d => ({
      detailId: d.detailId,
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      unitName: d.unitName,
      qty: Number(d.qty || 0),
      price: Number(d.price || 0),
      taxRate: d.taxRate || '13%',
      originalPrice: Number(d.price || 0),   // 记录原价，用于高亮改动
    }))
  } catch (e) {
    errors.value.header = e.message || '加载收货单失败'
    head.value = {}
    detailList.value = []
  } finally {
    loading.value = false
  }
}

/** 解析税率字符串 "13%" / "13" / "0.13" → 小数 */
function parseTaxRate(raw) {
  const s = String(raw ?? '').trim()
  if (!s) return 0.13
  const isPercent = s.endsWith('%')
  const v = Number(isPercent ? s.slice(0, -1) : s)
  if (!Number.isFinite(v)) return 0.13
  return (isPercent || v > 1) ? v / 100 : v
}

/** 行税额（价内税倒算，2位小数） */
function rowTax(row) {
  const amount = Number(row.qty || 0) * Number(row.price || 0)
  const rate = parseTaxRate(row.taxRate)
  if (!amount || !rate) return 0
  return Math.round((amount * rate / (1 + rate)) * 100) / 100
}

function rowAmount(row) {
  return (Number(row.qty || 0) * Number(row.price || 0)).toFixed(2)
}

/** 行金额（不补零，供输入框显示） */
function rowAmountRaw(row) {
  const v = Number(row.qty || 0) * Number(row.price || 0)
  if (v === 0) return ''
  return String(Math.round(v * 100) / 100)
}

function isPriceChanged(row) {
  return Number(row.price || 0) !== Number(row.originalPrice || 0)
}

// ============ 数字录入：纯手动输入，无加减控件 ============
/** 只保留数字与一个小数点，限制小数位；保留输入中的原始形态（如 "26."） */
function sanitizeDecimal(raw, maxDecimals) {
  let s = String(raw ?? '').replace(/[^\d.]/g, '')
  const dot = s.indexOf('.')
  if (dot >= 0) {
    s = s.slice(0, dot) + '.' + s.slice(dot + 1).replace(/\./g, '').slice(0, maxDecimals)
  }
  return s
}
function textToNumber(s) {
  const v = Number(s)
  return Number.isFinite(v) ? v : 0
}

function priceDisplay(row) {
  if (row.priceText !== undefined) return row.priceText
  const p = Number(row.price || 0)
  return p === 0 ? '' : p
}
function amountDisplay(row) {
  if (row.amountText !== undefined) return row.amountText
  return rowAmountRaw(row)
}

/** 单价录入：最多 4 位小数 */
function onPriceInput(row, raw) {
  const s = sanitizeDecimal(raw, 4)
  row.priceText = s
  row.price = textToNumber(s)
}
function onPriceBlur(row) {
  delete row.priceText
  row.price = Math.round(Number(row.price || 0) * 10000) / 10000
}

/** 金额录入：最多 2 位小数，按数量反算单价（4 位小数） */
function onAmountInput(row, raw) {
  const s = sanitizeDecimal(raw, 2)
  row.amountText = s
  const q = Number(row.qty) || 0
  if (q <= 0) {
    errors.value.details = '该行数量为 0，无法按金额反算单价'
    return
  }
  row.price = Math.round((textToNumber(s) / q) * 10000) / 10000
  errors.value.details = ''
}
function onAmountBlur(row) {
  delete row.amountText
}

function validate() {
  errors.value = {}
  if (detailList.value.length === 0) {
    errors.value.details = '收货明细为空'
    return false
  }
  for (const row of detailList.value) {
    if (Number(row.price || 0) < 0) {
      errors.value.details = `商品 ${row.goodsName || row.goodsCode} 的单价不能为负`
      return false
    }
  }
  return true
}

function detailPayload() {
  return detailList.value.map(r => ({
    detailId: r.detailId,
    price: Number(r.price) || 0,
  }))
}

/** 保存改价（不审核） */
async function saveReceipt() {
  if (!canEdit.value) { errors.value.header = readonlyReason.value; return }
  if (!validate()) return
  try {
    const result = await post('/purchase/receipt/update', {
      receiptId: head.value.receiptId,
      details: detailPayload(),
    })
    emit('save', result)
    await loadReceipt(head.value.receiptId)
  } catch (e) {
    errors.value.header = '保存失败：' + (e.message || '未知错误')
  }
}

/** 审核 → 生成应付 + 回写入库单单价 + 重算库存成本 */
async function auditReceipt() {
  if (!validate()) return
  const changed = detailList.value.filter(isPriceChanged)
  const amount = totalGoodsAmount.value.toFixed(2)
  let msg = `确认审核收货单【${head.value.receiptNo}】？\n\n`
    + `· 按含税商品金额 ¥${amount} 生成应付账款\n`
  if (changed.length > 0) {
    msg += `· 有 ${changed.length} 条明细单价被修改，将同步回写来源入库单 `
      + `${head.value.sourceInboundNo || ''} 并重算库存成本\n`
  }
  msg += '\n此操作不可直接撤销。'
  if (!confirm(msg)) return
  try {
    // 先保存改价，再审核，避免改了没保存就审核
    if (canEdit.value && changed.length > 0) {
      await post('/purchase/receipt/update', {
        receiptId: head.value.receiptId,
        details: detailPayload(),
      })
    }
    const result = await post('/purchase/receipt/audit', { bizId: head.value.receiptId })
    emit('save', result)
    emit('close')
  } catch (e) {
    errors.value.header = '审核失败：' + (e.message || '未知错误')
  }
}

/** 反审核 */
async function reverseAudit() {
  if (!confirm(`确认反审核收货单【${head.value.receiptNo}】？\n\n将撤销该收货单生成的应付账款。若已发生付款核销，反审核会被拒绝。`)) return
  try {
    const result = await post('/purchase/receipt/reverse-audit', { bizId: head.value.receiptId })
    emit('save', result)
    await loadReceipt(head.value.receiptId)
  } catch (e) {
    errors.value.header = '反审核失败：' + (e.message || '未知错误')
  }
}

function closeDrawer() { emit('close') }
</script>

<template>
  <div v-show="visible" class="receipt-drawer-mask">
    <div class="receipt-drawer-box">
      <div class="receipt-drawer-head">
        <b>{{ canEdit ? '采购收货单（可改价）' : '查看采购收货单' }} {{ head.receiptNo || '' }}</b>
        <span v-if="head.status" class="status-tag" :class="'st-' + String(head.status).toLowerCase()">
          {{ statusText }}
        </span>
        <div style="flex:1"></div>
        <div class="actions">
          <button class="btn" @click="closeDrawer">{{ canEdit ? '取消' : '关闭' }}</button>
          <template v-if="canEdit">
            <button class="btn" @click="saveReceipt">保存改价</button>
            <button class="btn primary" @click="auditReceipt">审核</button>
          </template>
          <button v-else-if="isApproved" class="btn" @click="reverseAudit">反审核</button>
        </div>
      </div>

      <div class="receipt-drawer-body">
        <div v-if="!canEdit" class="readonly-banner">🔒 {{ readonlyReason }}</div>
        <div v-if="errors.header" class="err-line">{{ errors.header }}</div>

        <!-- 头部 -->
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">单据信息</div>
          <div class="grid4">
            <div class="field">
              <label>收货单号</label>
              <input readonly :value="head.receiptNo || ''" />
            </div>
            <div class="field">
              <label>来源入库单</label>
              <input readonly :value="head.sourceInboundNo || ''" />
            </div>
            <div class="field">
              <label>采购单号</label>
              <input readonly :value="head.sourceOrderNo || ''" />
            </div>
            <div class="field">
              <label>供应商</label>
              <input readonly :value="head.supplierName || ''" />
            </div>
            <div class="field">
              <label>仓库</label>
              <input readonly :value="head.warehouse || ''" />
            </div>
            <div class="field">
              <label>收货日期</label>
              <input readonly :value="String(head.receiptDate || '').slice(0, 10)" />
            </div>
            <div class="field">
              <label>应付生成状态</label>
              <input readonly :value="head.apStatus || ''" />
            </div>
            <div class="field">
              <label>审核信息</label>
              <input readonly :value="head.auditUser ? `${head.auditUser} ${String(head.auditTime || '').slice(0, 19)}` : '未审核'" />
            </div>
          </div>
        </div>

        <!-- 金额汇总 -->
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">金额汇总</div>
          <div class="grid4">
            <div class="field">
              <label>商品金额（含税）</label>
              <input readonly class="highlight" :value="'¥ ' + totalGoodsAmount.toFixed(2)" />
            </div>
            <div class="field">
              <label>税额</label>
              <input readonly :value="'¥ ' + totalTaxAmount.toFixed(2)" />
            </div>
            <div class="field">
              <label>不含税金额</label>
              <input readonly :value="'¥ ' + totalUntaxedAmount.toFixed(2)" />
            </div>
            <div class="field">
              <label>费用分摊</label>
              <input readonly :value="'¥ ' + Number(head.expenseAmount || 0).toFixed(2)" />
            </div>
          </div>
          <div class="amount-tip">
            应付结算按<b>商品金额（含税）</b>进行；税额按价内税倒算（含税金额 × 税率 ÷ (1+税率)），不含税金额 = 含税金额 − 税额
          </div>
        </div>

        <!-- 明细 -->
        <div class="card detail-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
            <div style="font-weight:900;color:var(--primary)">收货明细（按商品聚合）</div>
            <div style="font-size:12px;color:#5d7896">
              {{ canEdit
                ? '只可修改单价（数量由仓库实收决定）；审核时单价将回写来源入库单并重算库存成本'
                : '已审核单据，明细只读' }}
            </div>
          </div>
          <div v-if="errors.details" class="err-line">{{ errors.details }}</div>
          <div v-if="loading" class="empty-detail">加载中...</div>
          <div v-else-if="detailList.length === 0" class="empty-detail">暂无收货明细</div>
          <div v-else class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th style="min-width:110px">商品编号</th>
                  <th style="min-width:170px">商品名称</th>
                  <th style="width:70px">单位</th>
                  <th style="width:90px">收货数量</th>
                  <th style="width:130px">单价（含税）</th>
                  <th style="width:130px">金额（含税）</th>
                  <th style="width:70px">税率</th>
                  <th style="width:100px">税额</th>
                  <th style="width:110px">不含税金额</th>
                  <th v-if="canEdit" style="width:90px">原单价</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in detailList" :key="row.detailId || index"
                    :class="{ 'row-changed': canEdit && isPriceChanged(row) }">
                  <td>{{ index + 1 }}</td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.unitName }}</td>
                  <td class="num-cell">{{ row.qty }}</td>
                  <td>
                    <input v-if="canEdit" type="text" inputmode="decimal"
                           :value="priceDisplay(row)"
                           class="cell-input num wide"
                           @input="onPriceInput(row, $event.target.value)"
                           @blur="onPriceBlur(row)" />
                    <span v-else class="num-cell">{{ Number(row.price).toFixed(4) }}</span>
                  </td>
                  <td>
                    <input v-if="canEdit" type="text" inputmode="decimal"
                           :value="amountDisplay(row)"
                           class="cell-input num wide"
                           title="修改金额将按收货数量反算单价（保留4位小数）"
                           @input="onAmountInput(row, $event.target.value)"
                           @blur="onAmountBlur(row)" />
                    <span v-else class="num-cell">{{ rowAmount(row) }}</span>
                  </td>
                  <td class="num-cell">{{ row.taxRate }}</td>
                  <td class="num-cell">{{ rowTax(row).toFixed(2) }}</td>
                  <td class="num-cell">
                    {{ (Number(row.qty || 0) * Number(row.price || 0) - rowTax(row)).toFixed(2) }}
                  </td>
                  <td v-if="canEdit" class="num-cell orig">
                    <span v-if="isPriceChanged(row)">{{ Number(row.originalPrice).toFixed(4) }}</span>
                    <span v-else>-</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="summary">
          <span>商品金额（含税）：<b style="color:var(--danger)">¥ {{ totalGoodsAmount.toFixed(2) }}</b></span>
          <span>税额：<b>¥ {{ totalTaxAmount.toFixed(2) }}</b></span>
          <span>不含税金额：<b>¥ {{ totalUntaxedAmount.toFixed(2) }}</b></span>
          <span>行数：<b>{{ detailList.length }}</b></span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.receipt-drawer-mask {
  position: fixed;
  top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900;
  display: flex;
  pointer-events: none;
  animation: fadeIn 0.2s ease;
}
.receipt-drawer-box {
  flex: 1;
  background: #fff;
  display: flex; flex-direction: column;
  min-width: 0;
  border-left: 1px solid var(--line);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.12);
  pointer-events: auto;
  animation: slideIn 0.25s ease;
}
.receipt-drawer-head {
  display: flex; align-items: center; gap: 10px;
  height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft);
}
.receipt-drawer-head b { font-size: 15px; }
.receipt-drawer-body {
  flex: 1; overflow: auto;
  padding: 12px 16px;
  display: flex; flex-direction: column; gap: 12px;
  background: #f5f7fa;
}
.detail-card { flex: 1; display: flex; flex-direction: column; min-height: 260px; padding: 12px; }
.detail-scroll { flex: 1; overflow: auto; min-height: 0; }
.detail-scroll table { width: 100%; border-collapse: collapse; font-size: 12px; }
.detail-scroll th {
  position: sticky; top: 0; z-index: 2;
  background: #f5f7fa; padding: 7px 6px; text-align: left;
  white-space: nowrap; border-bottom: 1px solid var(--line);
}
.detail-scroll td { padding: 4px 6px; border-bottom: 1px solid #f0f2f5; white-space: nowrap; }
.detail-scroll td.num-cell { text-align: right; font-variant-numeric: tabular-nums; }
.detail-scroll td.orig { color: #909399; text-decoration: line-through; }
.detail-scroll tr.row-changed { background: #fdf6ec; }
.cell-input {
  width: 100%; height: 24px; padding: 0 4px;
  border: 1px solid #dcdfe6; border-radius: 3px;
  font-size: 12px; min-width: 0;
}
.cell-input.num { text-align: right; font-variant-numeric: tabular-nums; }
.cell-input.wide { min-width: 120px; }
.cell-input::-webkit-outer-spin-button,
.cell-input::-webkit-inner-spin-button { -webkit-appearance: none; margin: 0; }
.empty-detail {
  padding: 40px; text-align: center; color: #909399; font-size: 13px;
  background: #fafbfc; border: 1px dashed #e5e7eb; border-radius: 8px;
}
.err-line {
  color: var(--danger); font-size: 12px;
  padding: 6px 10px;
  background: #fef0f0; border: 1px solid #fde2e2; border-radius: 4px;
}
.readonly-banner {
  padding: 8px 12px;
  background: #fdf6ec; border: 1px solid #faecd8; border-radius: 6px;
  color: #e6a23c; font-size: 12px; font-weight: 700;
}
.status-tag {
  padding: 2px 10px; border-radius: 10px;
  font-size: 12px; font-weight: 700; white-space: nowrap;
  background: #f4f4f5; border: 1px solid #e9e9eb; color: #909399;
}
.status-tag.st-pending { background: #fdf6ec; border-color: #faecd8; color: #e6a23c; }
.status-tag.st-approved { background: #f0f9eb; border-color: #e1f3d8; color: #67c23a; }
.field input[readonly] { background: #f7f8fa; color: #606266; }
.field input.highlight { font-weight: 900; color: var(--danger); }
.amount-tip {
  margin-top: 8px; padding: 6px 10px;
  background: #f4f4f5; border-left: 3px solid #909399; border-radius: 3px;
  font-size: 12px; color: #606266;
}
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6 } to { transform: translateX(0); opacity: 1 } }
@media (max-width: 900px) {
  .receipt-drawer-mask { left: 0; }
}
</style>