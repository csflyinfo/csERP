<script setup>
/**
 * 采购发票抽屉（PRD-30）—— 供应商进项发票登记、勾稽、取消勾稽。
 *
 * 流程：录入发票（必须填发票金额，可暂不勾稽）→ 点「勾稽商品」弹窗选择该供应商
 *       未开票商品行 → 选中商品回填到「勾稽明细」表（本次开票数量/金额默认=未开票余量）
 *       → 数量/金额/税率均可改，税额按金额×税率自动算 → 勾错点「取消勾稽」移除。
 *       明细表只保留 税率/本次开票数量/本次开票金额/本次税额 四列可操作列，
 *       数量、金额输入框下方小字显示该商品行未开票余量（数量/金额上限）供对照校验。
 *       草稿点「保存/审核」；**已审核发票仍可继续勾稽**，勾稽明细区点「保存勾稽」
 *       即增量回写收货单/应付来票状态（无需反审核）。
 * 校验：本次开票数量必须 > 0，且不得超过源单据（收货单）数量、不得超过未开票数量；
 *       本次金额不可为负；勾稽合计不得超过发票金额。保存/审核时点后端均会再次校验。
 * 票账分离：发票审核只回写收货单/应付的来票状态，不生成应付、不动成本。
 * 审核后：发票头信息锁定（仅可认证、追加备注），勾稽明细可继续维护。
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'
import { useRbac } from '../composables/useRbac.js'

const { canView, actionHidden, guard, permOf } = useRbac('purchaseInvoice')

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 发票 ID 或单号；空串 = 新建 */
  invoiceId: { type: String, default: '' },
  readonly: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'save'])

const TOLERANCE = 1
const QTY_EPS = 0.0001
const INVOICE_TYPES = ['增值税专用发票', '增值税普通发票', '电子发票', '全电发票']

const head = ref({})
/** 勾稽明细：每行 = 本发票勾稽某收货单上某商品的本次开票数量/金额/税率 */
const matchLines = ref([])
const suppliers = ref([])
const errors = ref({})
const loading = ref(false)
const savingMatches = ref(false)

// 勾稽商品弹窗
const pickerOpen = ref(false)
const pickerLoading = ref(false)
const pickerRows = ref([])
const pickerSelected = ref([])
const pickerKeyword = ref('')

// 审核后追加备注
const remarkAppend = ref('')
const remarkSaving = ref(false)

const isEdit = computed(() => !!props.invoiceId)
const status = computed(() => head.value.status || '草稿')
const isApproved = computed(() => status.value === '已审核')
const isVoid = computed(() => status.value === '已作废')
/** 发票头可编辑：仅草稿 */
const canEditHead = computed(() => !props.readonly && status.value === '草稿')
/** 勾稽明细可编辑：草稿与已审核均可（已审核走「保存勾稽」增量回写） */
const canEditMatches = computed(() => !props.readonly && (status.value === '草稿' || status.value === '已审核'))

// ============ 金额 / 税额 ============
function round2(v) { return Math.round((Number(v) || 0) * 100) / 100 }
function parseTaxRate(raw) {
  const s = String(raw ?? '').trim()
  if (!s) return 0.13
  const isPercent = s.endsWith('%')
  const v = Number(isPercent ? s.slice(0, -1) : s)
  if (!Number.isFinite(v)) return 0.13
  return (isPercent || v > 1) ? v / 100 : v
}
function lineKey(r) { return r.receiptNo + '|' + r.goodsCode }
function matchThisAmount(m) { return round2(m.thisAmount || (Number(m.thisQty || 0) * Number(m.price || 0))) }
/** 价内税倒算：税额 = 含税金额 × 税率 ÷ (1+税率) */
function lineTax(m) {
  const amt = matchThisAmount(m)
  const rate = parseTaxRate(m.taxRate)
  return round2(amt && rate ? amt * rate / (1 + rate) : 0)
}

const invoiceAmount = computed(() => round2(head.value.totalAmount || 0))
const matchedSum = computed(() => matchLines.value.reduce((s, m) => s + matchThisAmount(m), 0))
const unmatchedAmount = computed(() => round2(invoiceAmount.value - matchedSum.value))
const estTax = computed(() => matchLines.value.reduce((s, m) => s + lineTax(m), 0))
const taxDisplay = computed(() => Number(head.value.taxAmount || 0) > 0 ? round2(head.value.taxAmount) : estTax.value)
const matchStatusText = computed(() => {
  if (matchedSum.value <= QTY_EPS) return '未勾稽'
  if (unmatchedAmount.value <= TOLERANCE) return '已勾稽'
  return '部分勾稽'
})

// ============ 打开 / 加载 ============
watch(() => props.visible, async (val) => {
  if (!val) return
  resetState()
  await loadSuppliers()
  if (props.invoiceId) {
    await loadInvoice(props.invoiceId)
  }
})

function resetState() {
  errors.value = {}
  head.value = {
    invoiceType: INVOICE_TYPES[0],
    direction: '蓝字',
    issueDate: new Date().toISOString().slice(0, 10),
    receiveDate: new Date().toISOString().slice(0, 10),
    certStatus: '未认证',
    status: '草稿',
    totalAmount: '',
  }
  matchLines.value = []
  pickerOpen.value = false
  pickerRows.value = []
  pickerSelected.value = []
  pickerKeyword.value = ''
  remarkAppend.value = ''
  savingMatches.value = false
}

async function loadSuppliers() {
  try {
    const data = await post('/base/supplier/page', { pageNo: 1, pageSize: 500, filters: {} })
    suppliers.value = (data.records || []).filter(r => r.status !== '停用' && r.status !== 'STOPPED')
  } catch (e) { suppliers.value = [] }
}

async function loadInvoice(id) {
  loading.value = true
  try {
    const data = await get(`/purchase/invoice/detail?id=${encodeURIComponent(id)}`)
    head.value = data
    if (head.value.totalAmount == null) head.value.totalAmount = data.invoiceAmount || ''
    matchLines.value = (data.matchLines || []).map(d => {
      const thisQty = Number(d.thisQty || 0)
      const price = Number(d.price || 0)
      return {
        receiptNo: d.receiptNo,
        inboundNo: d.inboundNo || '',
        orderNo: d.orderNo || '',
        inboundDate: d.createTime || '',
        goodsCode: d.goodsCode,
        goodsName: d.goodsName,
        spec: d.spec || '',
        unitName: d.unitName || '',
        qty: Number(d.qty || 0),               // 源单据（收货单）数量
        price,
        amount: Number(d.amount || 0),
        taxRate: d.taxRate || '13%',
        invoicedQty: 0,
        uninvoicedQty: thisQty,
        invoicedAmount: 0,
        uninvoicedAmount: Number(d.thisAmount || 0) || round2(thisQty * price),
        thisQty,
        thisAmount: Number(d.thisAmount || 0) || round2(thisQty * price),
        amountTouched: Number(d.thisAmount || 0) > 0,
      }
    })
    // 拉取实时已开票/未开票数量与金额回填到勾稽明细（编辑时排除本发票）
    if (head.value.supplierCode) {
      try {
        const avail = await post('/purchase/invoice/available-lines', {
          supplierCode: head.value.supplierCode,
          excludeInvoiceId: head.value.invoiceId,
        })
        const idx = new Map(avail.map(r => [lineKey(r), r]))
        for (const m of matchLines.value) {
          const live = idx.get(lineKey(m))
          if (live) {
            m.invoicedQty = Number(live.invoicedQty || 0)
            m.uninvoicedQty = Number(live.uninvoicedQty || 0)
            m.invoicedAmount = Number(live.invoicedAmount || 0)
            m.uninvoicedAmount = Math.max(0, Number(live.uninvoicedAmount || 0))
            m.inboundDate = live.inboundDate
            if (!m.inboundNo) m.inboundNo = live.inboundNo || ''
            if (!m.orderNo) m.orderNo = live.orderNo || ''
          }
        }
      } catch (e) { /* 实时数据取不到不阻塞回显 */ }
    }
  } catch (e) {
    errors.value.header = e.message || '加载发票失败'
  } finally {
    loading.value = false
  }
}

function onSupplierPick() {
  const s = suppliers.value.find(x => x.supplierCode === head.value.supplierCode)
  if (s) head.value.supplierName = s.supplierName
  // 切换供应商：勾稽明细全部失效，清空
  matchLines.value = []
  closePicker()
}

// ============ 勾稽商品弹窗 ============
async function openPicker() {
  if (!head.value.supplierCode) {
    errors.value.header = '请先选择供应商'
    return
  }
  pickerOpen.value = true
  pickerSelected.value = []
  await loadPickerRows()
}

function closePicker() {
  pickerOpen.value = false
  pickerSelected.value = []
}

async function loadPickerRows() {
  pickerLoading.value = true
  try {
    const rows = await post('/purchase/invoice/available-lines', {
      supplierCode: head.value.supplierCode,
      excludeInvoiceId: head.value.invoiceId || undefined,
      keyword: pickerKeyword.value.trim(),
    })
    // 已加入勾稽明细的行不再出现在弹窗中
    const added = new Set(matchLines.value.map(lineKey))
    pickerRows.value = rows.filter(r => !added.has(lineKey(r)))
  } catch (e) {
    errors.value.header = '加载未开票商品失败：' + (e.message || '')
    pickerRows.value = []
  } finally {
    pickerLoading.value = false
  }
}

/** 弹窗确认：选中商品添加到勾稽明细，随后关闭弹窗 */
function addPickerLines() {
  const selected = pickerRows.value.filter(r => pickerSelected.value.includes(lineKey(r)))
  for (const r of selected) {
    const freeQty = Number(r.uninvoicedQty || 0)
    const freeAmt = Math.max(0, Number(r.uninvoicedAmount || 0))
    const price = Number(r.price || 0)
    matchLines.value.push({
      receiptNo: r.receiptNo,
      inboundNo: r.inboundNo || r.receiptNo,
      orderNo: r.orderNo || '',
      inboundDate: r.inboundDate || '',
      goodsCode: r.goodsCode,
      goodsName: r.goodsName,
      spec: r.spec || '',
      unitName: r.unitName || '',
      qty: Number(r.qty || 0),            // 源单据数量
      price,
      amount: Number(r.amount || 0),
      taxRate: r.taxRate || '13%',        // 税率默认从源单据带出
      invoicedQty: Number(r.invoicedQty || 0),
      uninvoicedQty: freeQty,
      invoicedAmount: Number(r.invoicedAmount || 0),
      uninvoicedAmount: freeAmt,
      thisQty: freeQty,                   // 默认按未开票数量全额勾稽
      // 默认金额 = 剩余未开票金额（源单据实时回写）；取不到时退回 数量×含税单价
      thisAmount: freeAmt > 0 ? freeAmt : round2(freeQty * price),
      amountTouched: false,
    })
  }
  closePicker()
}

/** 取消勾稽：从勾稽明细移除该行 */
function removeMatch(idx) {
  matchLines.value.splice(idx, 1)
}

/** 改数量：未手工改过金额时，金额随 数量×含税单价 联动 */
function onQtyInput(m) {
  if (!m.amountTouched) m.thisAmount = round2(Number(m.thisQty || 0) * Number(m.price || 0))
}
function onAmountInput(m) { m.amountTouched = true }

function matchWarn(m) {
  const qty = Number(m.thisQty || 0)
  if (qty <= QTY_EPS) return '本次开票数量必须大于 0'
  if (qty > Number(m.qty || 0) + QTY_EPS) {
    return `本次开票数量不能超过源单据数量 ${m.qty}`
  }
  if (qty > Number(m.uninvoicedQty || 0) + QTY_EPS) {
    return `超过未开票数量 ${m.uninvoicedQty}（已被其他发票勾稽）`
  }
  if (Number(m.thisAmount || 0) < 0) return '本次金额不能为负'
  return ''
}

// ============ 校验 / 提交 ============
function validate() {
  errors.value = {}
  const h = head.value
  if (!h.invoiceNumber || !String(h.invoiceNumber).trim()) { errors.value.header = '发票号码必填'; return false }
  if (!h.supplierCode) { errors.value.header = '供应商必填'; return false }
  if (!h.invoiceType) { errors.value.header = '发票类型必填'; return false }
  if (!h.issueDate) { errors.value.header = '开票日期必填'; return false }
  if (!(Number(h.totalAmount) > 0)) { errors.value.header = '请填写发票金额（含税价税合计）'; return false }
  for (const m of matchLines.value) {
    const w = matchWarn(m)
    if (w) { errors.value.header = `${m.receiptNo} / ${m.goodsCode}：${w}`; return false }
  }
  if (matchedSum.value > invoiceAmount.value + TOLERANCE) {
    errors.value.header = `勾稽合计 ¥${matchedSum.value.toFixed(2)} 超过发票金额 ¥${invoiceAmount.value.toFixed(2)}`
    return false
  }
  return true
}

function matchLinesPayload() {
  return matchLines.value.map(m => ({
    receiptNo: m.receiptNo,
    goodsCode: m.goodsCode,
    thisQty: Number(m.thisQty || 0),
    thisAmount: matchThisAmount(m),
    taxRate: m.taxRate || '13%',
  }))
}

function buildPayload() {
  return {
    invoiceId: head.value.invoiceId || undefined,
    invoiceNumber: head.value.invoiceNumber,
    invoiceCode: head.value.invoiceCode || '',
    invoiceType: head.value.invoiceType,
    direction: head.value.direction || '蓝字',
    supplierCode: head.value.supplierCode,
    supplierName: head.value.supplierName,
    buyerTitle: head.value.buyerTitle || '',
    buyerTaxNo: head.value.buyerTaxNo || '',
    issueDate: head.value.issueDate,
    receiveDate: head.value.receiveDate || '',
    certStatus: head.value.certStatus || '未认证',
    remark: head.value.remark || '',
    totalAmount: Number(head.value.totalAmount || 0),
    matchLines: matchLinesPayload(),
  }
}

async function saveDraft() {
  if (!guard(isEdit.value ? '编辑' : '新建')) { errors.value.header = '无权限执行该操作'; return }
  if (!canEditHead.value) return
  if (!validate()) return
  try {
    const payload = buildPayload()
    const result = isEdit.value
      ? await post('/purchase/invoice/update', payload)
      : await post('/purchase/invoice/create', payload)
    emit('save', result)
    if (!isEdit.value && result.invoiceId) {
      head.value.invoiceId = result.invoiceId
      head.value.invoiceNo = result.invoiceNo
    }
    errors.value.header = ''
    alert('发票已保存' + (result.invoiceNo ? `（单号 ${result.invoiceNo}）` : '') + '，可后续继续勾稽后审核。')
  } catch (e) {
    errors.value.header = '保存失败：' + (e.message || '未知错误')
  }
}

async function auditInvoice() {
  if (!guard('审核')) { errors.value.header = '无权限执行该操作'; return }
  if (!validate()) return
  if (!confirm(`确认审核发票【${head.value.invoiceNumber || ''}】？\n\n`
    + `· 发票金额 ¥${invoiceAmount.value.toFixed(2)}，已勾稽 ¥${matchedSum.value.toFixed(2)}（${matchStatusText.value}）\n`
    + `· 审核后回写收货单与应付账款的来票状态；发票头信息锁定，勾稽明细仍可继续维护\n`
    + (unmatchedAmount.value > TOLERANCE ? `· 尚有 ¥${unmatchedAmount.value.toFixed(2)} 未勾稽余额，审核后可继续勾稽\n` : '')
    + '\n如需修改发票头，请审核后反审核。')) return
  try {
    const payload = buildPayload()
    if (isEdit.value) {
      await post('/purchase/invoice/update', payload)
    } else {
      const created = await post('/purchase/invoice/create', payload)
      head.value.invoiceId = created.invoiceId
      payload.invoiceId = created.invoiceId
    }
    const result = await post('/purchase/invoice/audit', { bizId: head.value.invoiceId })
    emit('save', result)
    emit('close')
  } catch (e) {
    errors.value.header = '审核失败：' + (e.message || '未知错误')
  }
}

/** 已审核发票继续勾稽：保存勾稽明细，后端增量回写来票状态 */
async function saveMatches() {
  if (!guard('编辑')) { errors.value.header = '无权限执行该操作'; return }
  if (!canEditMatches.value || !isApproved.value) return
  if (!validate()) return
  savingMatches.value = true
  try {
    const result = await post('/purchase/invoice/update-matches', {
      invoiceId: head.value.invoiceId,
      matchLines: matchLinesPayload(),
    })
    emit('save', result)
    errors.value.header = ''
    await loadInvoice(head.value.invoiceId)
    alert(`勾稽已保存并回写收货单来票状态（勾稽状态：${result.matchStatus || ''}）。`)
  } catch (e) {
    errors.value.header = '保存勾稽失败：' + (e.message || '未知错误')
  } finally {
    savingMatches.value = false
  }
}

async function reverseAudit() {
  if (!guard('反审核')) { errors.value.header = '无权限执行该操作'; return }
  if (!confirm(`确认反审核发票【${head.value.invoiceNo || ''}】？\n\n将回退该发票对收货单/应付的来票状态勾稽（不影响其他发票的勾稽），发票恢复草稿可修改发票头。已认证发票不可反审核。`)) return
  try {
    const result = await post('/purchase/invoice/reverse-audit', { bizId: head.value.invoiceId })
    emit('save', result)
    await loadInvoice(head.value.invoiceId)
  } catch (e) {
    errors.value.header = '反审核失败：' + (e.message || '未知错误')
  }
}

async function voidInvoice() {
  if (!guard('作废')) { errors.value.header = '无权限执行该操作'; return }
  const reason = prompt(`作废发票【${head.value.invoiceNo || ''}】，请填写作废原因：\n（已认证发票不可作废，须走红字发票流程）`)
  if (reason === null) return
  if (!reason.trim()) { alert('作废原因必填'); return }
  try {
    const result = await post('/purchase/invoice/void', { bizId: head.value.invoiceId, remark: reason.trim() })
    emit('save', result)
    emit('close')
  } catch (e) {
    errors.value.header = '作废失败：' + (e.message || '未知错误')
  }
}

async function certify(target) {
  if (!guard('认证')) { errors.value.header = '无权限执行该操作'; return }
  try {
    const result = await post('/purchase/invoice/certify', { invoiceId: head.value.invoiceId, certStatus: target })
    emit('save', result)
    await loadInvoice(head.value.invoiceId)
  } catch (e) {
    errors.value.header = '认证操作失败：' + (e.message || '未知错误')
  }
}

async function saveRemark() {
  if (!guard('编辑')) { errors.value.header = '无权限执行该操作'; return }
  const append = remarkAppend.value.trim()
  if (!append) { alert('请填写要追加的备注内容'); return }
  remarkSaving.value = true
  try {
    const old = head.value.remark ? String(head.value.remark).trim() : ''
    const remark = old ? `${old}；${append}` : append
    const result = await post('/purchase/invoice/update-remark', { invoiceId: head.value.invoiceId, remark })
    emit('save', result)
    remarkAppend.value = ''
    await loadInvoice(head.value.invoiceId)
  } catch (e) {
    errors.value.header = '备注保存失败：' + (e.message || '未知错误')
  } finally {
    remarkSaving.value = false
  }
}

async function removeDraft() {
  if (!guard('删除')) { errors.value.header = '无权限执行该操作'; return }
  if (!confirm(`确认删除发票草稿【${head.value.invoiceNo || head.value.invoiceNumber || ''}】？删除后不可恢复。`)) return
  try {
    const result = await post('/purchase/invoice/delete', { bizId: head.value.invoiceId })
    emit('save', result)
    emit('close')
  } catch (e) {
    errors.value.header = '删除失败：' + (e.message || '未知错误')
  }
}

function closeDrawer() { emit('close') }
function fmtDate(v) { return v ? String(v).slice(0, 10) : '' }
</script>

<template>
  <div v-show="visible" class="inv-drawer-mask">
    <div class="inv-drawer-box">
      <div class="inv-drawer-head">
        <b>{{ isEdit ? (canEditHead ? '编辑采购发票' : '查看采购发票') : '录入采购发票' }} {{ head.invoiceNo || '' }}</b>
        <span v-if="head.status" class="status-tag" :class="{
          'st-draft': head.status === '草稿',
          'st-approved': head.status === '已审核',
          'st-void': head.status === '已作废',
        }">{{ head.status }}</span>
        <span v-if="head.status" class="status-tag st-match">{{ matchStatusText }}</span>
        <span v-if="head.certStatus" class="status-tag" :class="head.certStatus === '已认证' ? 'st-cert' : ''">{{ head.certStatus }}</span>
        <div style="flex:1"></div>
        <div class="actions" v-action-perms="actionHidden">
          <button class="btn" @click="closeDrawer">{{ canEditHead ? '取消' : '关闭' }}</button>
          <template v-if="canEditHead">
            <button class="btn danger" v-if="isEdit" @click="removeDraft">删除草稿</button>
            <button class="btn" v-permission="isEdit ? permOf('purchaseInvoice', '编辑') : permOf('purchaseInvoice', '新建')" @click="saveDraft">保存</button>
            <button class="btn primary" @click="auditInvoice">审核</button>
          </template>
          <template v-else-if="isApproved && !readonly">
            <button class="btn" v-if="head.certStatus !== '已认证'" @click="certify('已认证')">认证</button>
            <button class="btn" v-else @click="certify('未认证')">撤销认证</button>
            <button class="btn" @click="reverseAudit">反审核</button>
            <button class="btn danger" @click="voidInvoice">作废</button>
          </template>
        </div>
      </div>

      <div class="inv-drawer-body">
        <div v-if="isVoid" class="readonly-banner">🔒 该发票已作废{{ head.voidReason ? '：' + head.voidReason : '' }}</div>
        <div v-else-if="isApproved" class="readonly-banner">
          🔒 该发票已审核，发票头信息已锁定（可认证、追加备注；改头信息请反审核）。
          <b style="color:#409eff">勾稽明细可继续维护，改完点「保存勾稽」即回写来票状态。</b>
        </div>
        <div v-if="errors.header" class="err-line">{{ errors.header }}</div>

        <!-- 发票信息 -->
        <div class="card" style="padding:12px;flex:none">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">发票信息</div>
          <div class="grid4">
            <div class="field">
              <label>采购发票单号</label>
              <input readonly :value="head.invoiceNo || '保存后生成'" />
            </div>
            <div class="field">
              <label><span style="color:var(--danger)">*</span> 发票号码</label>
              <input :readonly="!canEditHead" v-model="head.invoiceNumber" placeholder="税票号码" />
            </div>
            <div class="field">
              <label>发票代码</label>
              <input :readonly="!canEditHead" v-model="head.invoiceCode" placeholder="全电发票可空" />
            </div>
            <div class="field">
              <label><span style="color:var(--danger)">*</span> 发票类型</label>
              <select :disabled="!canEditHead" v-model="head.invoiceType">
                <option v-for="t in INVOICE_TYPES" :key="t" :value="t">{{ t }}</option>
              </select>
            </div>
            <div class="field">
              <label><span style="color:var(--danger)">*</span> 供应商</label>
              <select :disabled="!canEditHead || isEdit" v-model="head.supplierCode" @change="onSupplierPick">
                <option value="" disabled>请选择供应商</option>
                <option v-for="s in suppliers" :key="s.supplierCode" :value="s.supplierCode">{{ s.supplierName }}</option>
              </select>
            </div>
            <div class="field">
              <label><span style="color:var(--danger)">*</span> 开票日期</label>
              <input type="date" :readonly="!canEditHead" v-model="head.issueDate" />
            </div>
            <div class="field">
              <label>收票日期</label>
              <input type="date" :readonly="!canEditHead" v-model="head.receiveDate" />
            </div>
            <div class="field" v-if="canView('发票金额（含税价税合计）')">
              <label><span style="color:var(--danger)">*</span> 发票金额（含税价税合计）</label>
              <input type="number" step="0.01" min="0" :readonly="!canEditHead"
                     v-model.number="head.totalAmount" placeholder="按发票票面金额填写"
                     :style="canEditHead ? 'border-color:var(--primary);font-weight:700' : ''" />
            </div>
            <div class="field">
              <label>认证状态</label>
              <select :disabled="!isApproved" v-model="head.certStatus">
                <option value="未认证">未认证</option>
                <option value="已认证">已认证</option>
                <option value="无需认证">无需认证</option>
              </select>
            </div>
            <div class="field">
              <label>购买方抬头</label>
              <input :readonly="!canEditHead" v-model="head.buyerTitle" placeholder="我方抬头" />
            </div>
            <div class="field">
              <label>购买方税号</label>
              <input :readonly="!canEditHead" v-model="head.buyerTaxNo" />
            </div>
            <div class="field" v-if="canEditHead">
              <label>备注</label>
              <input :readonly="!canEditHead" v-model="head.remark" placeholder="选填" />
            </div>
          </div>
        </div>

        <!-- 勾稽明细（唯一表格，铺满剩余空间） -->
        <div class="card detail-card">
          <div class="detail-toolbar">
            <div style="font-weight:900;color:var(--primary)">勾稽明细</div>
            <div style="display:flex;gap:8px;align-items:center">
              <span v-if="canView('发票金额')" style="font-size:12px;color:#5d7896">
                已勾稽 ¥{{ matchedSum.toFixed(2) }} ／ 发票金额 ¥{{ invoiceAmount.toFixed(2) }}
              </span>
              <button v-if="canEditMatches" class="btn small" @click="openPicker">+ 勾稽商品</button>
              <button v-if="isApproved && canEditMatches" class="btn small primary"
                      :disabled="savingMatches" @click="saveMatches">
                {{ savingMatches ? '保存中…' : '保存勾稽' }}
              </button>
            </div>
          </div>

          <div v-if="matchLines.length === 0" class="empty-detail">
            尚未勾稽。<template v-if="canEditMatches">点「+ 勾稽商品」选择该供应商的未开票商品；草稿也可先保存发票，后续再勾稽。</template>
          </div>
          <div v-else class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th style="min-width:110px">进货单号</th>
                  <th style="min-width:120px">入库单号</th>
                  <th style="width:96px">入库日期</th>
                  <th style="min-width:120px">商品编号</th>
                  <th style="min-width:140px">商品名称</th>
                  <th style="width:80px">规格</th>
                  <th style="width:56px">单位</th>
                  <th class="num" style="width:86px">税率</th>
                  <th class="num" style="width:130px">本次开票数量</th>
                  <th class="num" style="width:150px" v-if="canView('本次开票金额')">本次开票金额</th>
                  <th class="num" style="width:100px" v-if="canView('本次税额')">本次税额</th>
                  <th v-if="canEditMatches" style="width:70px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(m, idx) in matchLines" :key="lineKey(m)">
                  <td>{{ idx + 1 }}</td>
                  <td>{{ m.orderNo || '—' }}</td>
                  <td>{{ m.inboundNo || m.receiptNo }}</td>
                  <td>{{ fmtDate(m.inboundDate) }}</td>
                  <td>{{ m.goodsCode }}</td>
                  <td>{{ m.goodsName }}</td>
                  <td>{{ m.spec || '' }}</td>
                  <td>{{ m.unitName || '' }}</td>
                  <td>
                    <input v-if="canEditMatches" class="cell-input" style="width:72px;text-align:right"
                           v-model="m.taxRate" placeholder="13%" />
                    <span v-else class="num-cell">{{ m.taxRate }}</span>
                  </td>
                  <td>
                    <input v-if="canEditMatches" type="number" step="any" min="0"
                           class="cell-input num" v-model.number="m.thisQty" @input="onQtyInput(m)"
                           :style="matchWarn(m) ? 'border-color:var(--danger)' : ''" />
                    <span v-else class="num-cell">{{ m.thisQty }}</span>
                    <div v-if="canEditMatches" class="cell-hint">未开票 {{ m.uninvoicedQty }}</div>
                  </td>
                  <td v-if="canView('本次开票金额')">
                    <input v-if="canEditMatches" type="number" step="0.01" min="0"
                           class="cell-input num" v-model.number="m.thisAmount" @input="onAmountInput(m)" />
                    <span v-else class="num-cell">{{ matchThisAmount(m).toFixed(2) }}</span>
                    <div v-if="canEditMatches" class="cell-hint">未开票 ¥{{ Number(m.uninvoicedAmount || 0).toFixed(2) }}</div>
                  </td>
                  <td class="num-cell" v-if="canView('本次税额')">{{ lineTax(m).toFixed(2) }}</td>
                  <td v-if="canEditMatches">
                    <button class="btn-link danger" @click="removeMatch(idx)">取消勾稽</button>
                  </td>
                </tr>
              </tbody>
            </table>
            <div v-for="(m, idx) in matchLines" :key="'warn' + idx">
              <div v-if="canEditMatches && matchWarn(m)" class="err-line" style="margin-top:4px">
                {{ m.receiptNo }} / {{ m.goodsCode }}：{{ matchWarn(m) }}
              </div>
            </div>
          </div>
        </div>

        <!-- 审核后：追加备注 -->
        <div v-if="isApproved && !readonly" class="card" style="padding:12px;flex:none">
          <div style="font-weight:900;margin-bottom:8px;color:var(--primary)">追加备注</div>
          <div v-if="head.remark" style="font-size:12px;color:#606266;margin-bottom:8px">
            当前备注：{{ head.remark }}
          </div>
          <div style="display:flex;gap:8px;align-items:center">
            <input class="cell-input" style="flex:1;max-width:520px;height:30px"
                   v-model="remarkAppend" placeholder="填写要追加的备注内容，保存后追加到原备注"
                   @keyup.enter="saveRemark" />
            <button class="btn small primary" :disabled="remarkSaving" @click="saveRemark">保存备注</button>
          </div>
        </div>

        <div class="summary">
          <span v-if="canView('发票金额')">发票金额：<b style="color:var(--danger)">¥ {{ invoiceAmount.toFixed(2) }}</b></span>
          <span v-if="canView('税额')">税额：<b>¥ {{ taxDisplay.toFixed(2) }}</b></span>
          <span v-if="canView('已勾稽金额')">已勾稽金额：<b>¥ {{ matchedSum.toFixed(2) }}</b></span>
          <span v-if="canView('未勾稽金额')">未勾稽金额：<b :style="{ color: unmatchedAmount > TOLERANCE ? 'var(--danger)' : '' }">¥ {{ unmatchedAmount.toFixed(2) }}</b></span>
          <span>勾稽状态：<b>{{ matchStatusText }}</b></span>
        </div>
      </div>
    </div>

    <!-- 勾稽商品弹窗 -->
    <div v-if="pickerOpen" class="picker-mask" @click.self="closePicker">
      <div class="picker-box">
        <div class="picker-head">
          <b>选择未开票商品（{{ head.supplierName || head.supplierCode }}）</b>
          <button class="btn-link" @click="closePicker">✕ 关闭</button>
        </div>
        <div class="picker-toolbar">
          <input v-model="pickerKeyword" class="cell-input" style="max-width:260px;height:30px"
                 placeholder="商品编号 / 名称模糊搜索" @keyup.enter="loadPickerRows" />
          <button class="btn small" @click="loadPickerRows">搜索</button>
          <span style="font-size:12px;color:#909399">
            勾选商品后点「添加选中」，本次开票数量/金额默认按未开票余量全额带出；勾稽明细输入框下方显示未开票余量供对照，数量、金额、税率均可调
          </span>
        </div>
        <div class="picker-body">
          <div v-if="pickerLoading" class="empty-detail" style="padding:24px">加载中…</div>
          <div v-else-if="pickerRows.length === 0" class="empty-detail" style="padding:24px">
            该供应商没有未开票的已入库商品行
          </div>
          <table v-else>
            <thead>
              <tr>
                <th style="width:36px"><input type="checkbox"
                  :checked="pickerSelected.length > 0 && pickerSelected.length === pickerRows.length"
                  @change="(e) => pickerSelected = e.target.checked ? pickerRows.map(lineKey) : []" /></th>
                <th>进货单号</th>
                <th>入库单号</th>
                <th>入库日期</th>
                <th>商品编号</th>
                <th>商品名称</th>
                <th>规格</th>
                <th>单位</th>
                <th class="num">数量</th>
                <th class="num" v-if="canView('单价')">单价</th>
                <th class="num" v-if="canView('金额')">金额</th>
                <th class="num">税率</th>
                <th class="num">已开票数量</th>
                <th class="num">未开票数量</th>
                <th class="num" v-if="canView('已开票金额')">已开票金额</th>
                <th class="num" v-if="canView('未开票金额')">未开票金额</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in pickerRows" :key="lineKey(r)">
                <td><input type="checkbox" :value="lineKey(r)" v-model="pickerSelected" /></td>
                <td>{{ r.orderNo || '—' }}</td>
                <td>{{ r.inboundNo || r.receiptNo }}</td>
                <td>{{ fmtDate(r.inboundDate) }}</td>
                <td>{{ r.goodsCode }}</td>
                <td>{{ r.goodsName }}</td>
                <td>{{ r.spec || '' }}</td>
                <td>{{ r.unitName || '' }}</td>
                <td class="num-cell">{{ r.qty }}</td>
                <td class="num-cell" v-if="canView('单价')">{{ Number(r.price || 0).toFixed(4) }}</td>
                <td class="num-cell" v-if="canView('金额')">{{ Number(r.amount || 0).toFixed(2) }}</td>
                <td class="num-cell">{{ r.taxRate || '13%' }}</td>
                <td class="num-cell">{{ Number(r.invoicedQty || 0) }}</td>
                <td class="num-cell">{{ Number(r.uninvoicedQty || 0) }}</td>
                <td class="num-cell" v-if="canView('已开票金额')">{{ Number(r.invoicedAmount || 0).toFixed(2) }}</td>
                <td class="num-cell" v-if="canView('未开票金额')" style="color:var(--danger);font-weight:700">{{ Number(r.uninvoicedAmount || 0).toFixed(2) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="picker-foot">
          <button class="btn" @click="closePicker">取消</button>
          <button class="btn primary" :disabled="pickerSelected.length === 0" @click="addPickerLines">
            添加选中（{{ pickerSelected.length }}）
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.inv-drawer-mask {
  position: fixed;
  top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900;
  display: flex;
  pointer-events: none;
  animation: fadeIn 0.2s ease;
}
.inv-drawer-box {
  flex: 1;
  background: #fff;
  display: flex; flex-direction: column;
  min-width: 0;
  border-left: 1px solid var(--line);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.12);
  pointer-events: auto;
  animation: slideIn 0.25s ease;
}
.inv-drawer-head {
  display: flex; align-items: center; gap: 10px;
  height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft);
  flex: none;
}
.inv-drawer-head b { font-size: 15px; }
.inv-drawer-body {
  flex: 1; overflow: hidden;
  padding: 12px 16px;
  display: flex; flex-direction: column; gap: 12px;
  background: #f5f7fa;
  min-height: 0;
}
/* 勾稽明细卡片：铺满窗口下部，不随记录条数收缩 */
.detail-card {
  flex: 1 1 auto;
  min-height: 260px;
  display: flex; flex-direction: column;
  padding: 12px;
}
.detail-toolbar {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 8px; flex: none;
}
.detail-scroll {
  flex: 1; overflow: auto; min-height: 0;
  border: 1px solid #f0f2f5; border-radius: 6px;
}
.detail-scroll table { width: 100%; border-collapse: collapse; font-size: 12px; }
.detail-scroll th {
  background: #f5f7fa; padding: 7px 6px; text-align: left;
  white-space: nowrap; border-bottom: 1px solid var(--line);
  position: sticky; top: 0; z-index: 1;
}
.detail-scroll td { padding: 4px 6px; border-bottom: 1px solid #f0f2f5; white-space: nowrap; }
.num-cell, th.num { text-align: right; font-variant-numeric: tabular-nums; }
.cell-input {
  width: 100%; height: 26px; padding: 0 4px;
  border: 1px solid #dcdfe6; border-radius: 3px;
  font-size: 12px; min-width: 0; background: #fff;
}
.cell-input.num { text-align: right; font-variant-numeric: tabular-nums; }
/* 输入框下方的可开票余量提示（数量/金额上限，供保存时对照校验） */
.cell-hint {
  font-size: 11px; line-height: 1.5; color: #8a9bb0;
  text-align: right; margin-top: 2px; white-space: nowrap;
}
.cell-input::-webkit-outer-spin-button,
.cell-input::-webkit-inner-spin-button { -webkit-appearance: none; margin: 0; }
.field select, .field input {
  width: 100%; height: 30px; padding: 0 8px;
  border: 1px solid #dcdfe6; border-radius: 4px; font-size: 13px;
  background: #fff;
}
.field input[readonly], .field select:disabled { background: #f7f8fa; color: #606266; }
.empty-detail {
  flex: 1;
  display: flex; align-items: center; justify-content: center;
  padding: 30px; text-align: center; color: #909399; font-size: 13px;
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
  flex: none;
}
.status-tag {
  padding: 2px 10px; border-radius: 10px;
  font-size: 12px; font-weight: 700; white-space: nowrap;
  background: #f4f4f5; border: 1px solid #e9e9eb; color: #909399;
}
.status-tag.st-draft { background: #fdf6ec; border-color: #faecd8; color: #e6a23c; }
.status-tag.st-approved { background: #f0f9eb; border-color: #e1f3d8; color: #67c23a; }
.status-tag.st-void { background: #fef0f0; border-color: #fde2e2; color: #f56c6c; }
.status-tag.st-match { background: #ecf5ff; border-color: #d9ecff; color: #409eff; }
.status-tag.st-cert { background: #f0f9eb; border-color: #e1f3d8; color: #67c23a; }
.btn.small { height: 26px; padding: 0 10px; font-size: 12px; }
.btn-link {
  border: none; background: none; cursor: pointer;
  font-size: 12px; padding: 2px 4px; color: #409eff;
}
.btn-link.danger { color: #f56c6c; }
.summary {
  display: flex; flex-wrap: wrap; gap: 18px;
  padding: 10px 14px;
  background: #fff; border: 1px solid var(--line-soft); border-radius: 8px;
  font-size: 13px;
  flex: none;
}
/* 勾稽商品弹窗 */
.picker-mask {
  position: fixed; inset: 0; z-index: 1100;
  background: rgba(15, 46, 88, 0.35);
  display: flex; align-items: center; justify-content: center;
  pointer-events: auto;
}
.picker-box {
  width: 92%; max-width: 1320px; max-height: 82vh;
  background: #fff; border-radius: 10px;
  box-shadow: 0 12px 40px rgba(15, 46, 88, 0.25);
  display: flex; flex-direction: column; overflow: hidden;
  animation: popIn 0.18s ease;
}
.picker-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px; border-bottom: 1px solid var(--line-soft);
}
.picker-head b { font-size: 14px; }
.picker-toolbar {
  display: flex; gap: 8px; align-items: center;
  padding: 10px 16px; border-bottom: 1px solid #f0f2f5; background: #fafbfc;
}
.picker-body { flex: 1; overflow: auto; padding: 8px 16px; }
.picker-body table { width: 100%; border-collapse: collapse; font-size: 12px; }
.picker-body th {
  background: #f5f7fa; padding: 7px 6px; text-align: left;
  white-space: nowrap; border-bottom: 1px solid var(--line);
  position: sticky; top: 0;
}
.picker-body td { padding: 4px 6px; border-bottom: 1px solid #f0f2f5; white-space: nowrap; }
.picker-foot {
  display: flex; justify-content: flex-end; gap: 10px;
  padding: 10px 16px; border-top: 1px solid var(--line-soft); background: #fafbfc;
}
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6 } to { transform: translateX(0); opacity: 1 } }
@keyframes popIn { from { transform: scale(0.96); opacity: 0 } to { transform: scale(1); opacity: 1 } }
@media (max-width: 900px) {
  .inv-drawer-mask { left: 0; }
}
</style>
