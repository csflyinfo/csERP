<script setup>
/**
 * 厂家费用单 新建/编辑/查看 抽屉（PRD-36 M3）
 * 左：主单（供应商/日期/性质/协议号/经手人/部门/税额/补录标志/备注）；
 * 右：费用行（费用类型/垫付客户/关联客户费用单行/贷方科目/数量单价金额/备注）。
 * 代垫行可通过选择器关联 FE 明细行（一张 FE 整单被本 JF 占用，可同单多行），也可只录 FE 单号；
 * 红字单金额必须为负、不能关联 FE。保存只建 PENDING；「保存并审核」即刻立费用债权。
 */
import { ref, computed } from 'vue'
import { post, get } from '../api/client.js'
import { todayStr } from '../utils/dateTime.js'

const emit = defineEmits(['saved'])

const visible = ref(false)
const mode = ref('create')
const saving = ref(false)
const feedback = ref(null)

const suppliers = ref([])
const employees = ref([])
const customers = ref([])
const expenseTypes = ref([])
const backfillEnabled = ref(false)

const head = ref(emptyHead())
function emptyHead() {
  return {
    factoryExpenseId: '',
    factoryExpenseNo: '',
    supplierCode: '',
    supplierName: '',
    expenseDate: todayStr(),
    claimType: 'ADVANCE',
    externalVoucherNo: '',
    handler: '',
    department: '',
    totalTaxAmount: '',
    totalExcludingTaxAmount: '',
    remark: '',
    status: '',
    sourceMode: 'MANUAL',
    businessSource: 'BACKOFFICE',
    isRed: 'N',
    redSourceNo: '',
    openingBackfill: false,
  }
}

const lines = ref([])
const settles = ref([])
let lineSeq = 0

const readonly = computed(() => mode.value === 'view')
const isEdit = computed(() => mode.value === 'edit')
const isRed = computed(() => head.value.isRed === 'Y')
const isAdvance = computed(() => head.value.claimType === 'ADVANCE')
const totalAmount = computed(() => round2(lines.value.reduce((s, r) => s + (Number(r.amount) || 0), 0)))

// FE 行选择器
const pickerVisible = ref(false)
const pickingKey = ref(null)
const pickerLoading = ref(false)
const pickerRows = ref([])
const pickerFilters = ref({ customer: '', keyword: '', dateFrom: '', dateTo: '' })

function round2(v) {
  return Math.round((Number(v) || 0) * 100) / 100
}

async function loadOptions() {
  try {
    const s = await post('/base/supplier/page', { pageNo: 1, pageSize: 500, filters: {} })
    suppliers.value = (s.records || []).filter(x => x.supplierCode && x.status !== 'DELETED')
  } catch (_) { suppliers.value = [] }
  try {
    const emp = await post('/base/master/employee/page', { pageNo: 1, pageSize: 200, filters: {} })
    employees.value = (emp.records || []).filter(x => x.employeeName || x.name)
  } catch (_) { employees.value = [] }
  try {
    const c = await post('/base/customer/page', { pageNo: 1, pageSize: 500, filters: {} })
    customers.value = (c.records || []).filter(x => x.customerCode && x.status !== 'DELETED')
  } catch (_) { customers.value = [] }
  try {
    // 末级费用类型：过滤掉存在子级的编码（与服务端口径一致）
    const et = await post('/base/master/expense-type/page', { pageNo: 1, pageSize: 500, filters: {} })
    const all = (et.records || []).filter(x => x.expenseTypeName || x.name)
    const parentSet = new Set()
    for (const x of all) {
      const code = x.parentCode
      if (code) parentSet.add(code)
    }
    expenseTypes.value = all.filter(x => !parentSet.has(x.expenseTypeCode || x.code)
      && (x.status || 'NORMAL') === 'NORMAL')
  } catch (_) { expenseTypes.value = [] }
  try {
    const flag = await get('/finance/factory-expense/backfill-flag')
    backfillEnabled.value = !!flag?.enabled
  } catch (_) { backfillEnabled.value = false }
}

async function open(modeName, row) {
  feedback.value = null
  mode.value = modeName || 'create'
  head.value = emptyHead()
  lines.value = []
  settles.value = []
  pickerVisible.value = false
  await loadOptions()
  if (mode.value === 'create') {
    visible.value = true
    addLine()
    return
  }
  try {
    const d = await post('/finance/factory-expense/detail', { factoryExpenseId: row.factoryExpenseId })
    head.value = {
      factoryExpenseId: d.factoryExpenseId,
      factoryExpenseNo: d.factoryExpenseNo || '',
      supplierCode: d.supplierCode || '',
      supplierName: d.supplierName || '',
      expenseDate: d.expenseDate ? String(d.expenseDate).substring(0, 10) : todayStr(),
      claimType: d.claimType || 'ADVANCE',
      externalVoucherNo: d.externalVoucherNo || '',
      handler: d.handler || '',
      department: d.department || '',
      totalTaxAmount: d.totalTaxAmount != null ? d.totalTaxAmount : '',
      totalExcludingTaxAmount: d.totalExcludingTaxAmount != null ? d.totalExcludingTaxAmount : '',
      remark: d.remark || '',
      status: d.status || '',
      sourceMode: d.sourceMode || 'MANUAL',
      businessSource: d.businessSource || 'BACKOFFICE',
      isRed: d.isRed || 'N',
      redSourceNo: d.redSourceNo || '',
      openingBackfill: d.businessSource === 'OPENING_BACKFILL',
    }
    lines.value = (d.details || []).map(x => ({
      key: ++lineSeq,
      expenseTypeName: x.expenseTypeName || '',
      customerCode: x.customerCode || '',
      customerName: x.customerName || '',
      customerExpenseNo: x.customerExpenseNo || '',
      customerExpenseDetailId: x.customerExpenseDetailId || '',
      glCreditSubject: x.glCreditSubject || '',
      qty: x.qty != null ? Number(x.qty) : 0,
      price: x.price != null ? Number(x.price) : null,
      amount: Number(x.amount) || 0,
      remark: x.remark || '',
    }))
    settles.value = d.settles || []
    if (!lines.value.length) addLine()
    visible.value = true
  } catch (e) {
    feedback.value = e?.message || '单据加载失败'
    visible.value = true
  }
}

function close() { visible.value = false }

function onSupplierChange() {
  const s = suppliers.value.find(x => x.supplierCode === head.value.supplierCode)
  head.value.supplierName = s ? s.supplierName : ''
}

function onClaimTypeChange() {
  // 其他性质不能关联 FE：切走代垫时清空关联（保留客户与金额）
  if (!isAdvance.value) {
    for (const l of lines.value) {
      l.customerExpenseNo = ''
      l.customerExpenseDetailId = ''
    }
  }
}

function makeLine() {
  return {
    key: ++lineSeq,
    expenseTypeName: '',
    customerCode: '',
    customerName: '',
    customerExpenseNo: '',
    customerExpenseDetailId: '',
    glCreditSubject: '',
    qty: 0,
    price: null,
    amount: null,
    remark: '',
  }
}
function addLine() {
  lines.value.push(makeLine())
}
function removeLine(l) {
  if (lines.value.length <= 1) return
  lines.value = lines.value.filter(x => x.key !== l.key)
}
function onCustomerChange(l) {
  const c = customers.value.find(x => x.customerCode === l.customerCode)
  l.customerName = c ? c.customerName : ''
}
function onQtyPrice(l) {
  if (Number(l.qty) && Number(l.price)) {
    l.amount = round2(Number(l.qty) * Number(l.price))
  }
}
function clearFeLink(l) {
  l.customerExpenseNo = ''
  l.customerExpenseDetailId = ''
}

// ==================== FE 行选择器 ====================

function openPicker(l) {
  pickingKey.value = l.key
  pickerRows.value = []
  pickerFilters.value = { customer: '', keyword: '', dateFrom: '', dateTo: '' }
  pickerVisible.value = true
  loadPicker()
}

async function loadPicker() {
  pickerLoading.value = true
  try {
    pickerRows.value = await post('/finance/factory-expense/customer-expense-candidates', {
      currentJfNo: head.value.factoryExpenseNo || '',
      customer: pickerFilters.value.customer,
      keyword: pickerFilters.value.keyword,
      dateFrom: pickerFilters.value.dateFrom,
      dateTo: pickerFilters.value.dateTo,
    })
  } catch (e) {
    feedback.value = e?.message || '客户费用候选加载失败'
    pickerRows.value = []
  } finally {
    pickerLoading.value = false
  }
}

function pickCandidate(c) {
  const l = lines.value.find(x => x.key === pickingKey.value)
  if (!l) { pickerVisible.value = false; return }
  l.customerExpenseNo = c.expenseNo
  l.customerExpenseDetailId = c.detailId
  l.customerCode = c.customerCode || l.customerCode
  if (c.customerCode) {
    const cust = customers.value.find(x => x.customerCode === c.customerCode)
    l.customerName = cust ? cust.customerName : (c.customerName || l.customerName)
  }
  if (!l.expenseTypeName) l.expenseTypeName = c.expenseType || ''
  // 默认带出 FE 行金额（允许小于该行金额，差额我方自担），用户可改小
  if (!Number(l.amount)) l.amount = Number(c.detailAmount) || 0
  pickerVisible.value = false
}

function pickerRowDisabled(c) {
  // 一张 FE 整单占用：本单其他行已选过该 FE 的其他明细行时，仍允许继续选（同一 JF 可占同一张 FE 的多行）
  return false
}

// ==================== 保存 ====================

function buildPayload() {
  return {
    factoryExpenseId: head.value.factoryExpenseId || undefined,
    supplierCode: head.value.supplierCode,
    supplierName: head.value.supplierName,
    expenseDate: head.value.expenseDate,
    claimType: head.value.claimType,
    businessSource: head.value.openingBackfill ? 'OPENING_BACKFILL' : 'BACKOFFICE',
    externalVoucherNo: head.value.externalVoucherNo,
    handler: head.value.handler,
    department: head.value.department,
    totalTaxAmount: head.value.totalTaxAmount === '' ? undefined : head.value.totalTaxAmount,
    totalExcludingTaxAmount: head.value.totalExcludingTaxAmount === '' ? undefined : head.value.totalExcludingTaxAmount,
    remark: head.value.remark,
    details: lines.value.map(l => ({
      expenseTypeName: l.expenseTypeName,
      customerCode: l.customerCode,
      customerName: l.customerName,
      customerExpenseNo: isAdvance.value ? l.customerExpenseNo : '',
      customerExpenseDetailId: isAdvance.value ? l.customerExpenseDetailId : '',
      glCreditSubject: l.glCreditSubject,
      qty: Number(l.qty) || 0,
      price: l.price === null || l.price === '' ? undefined : l.price,
      amount: round2(l.amount),
      remark: l.remark,
    })),
  }
}

function validate() {
  if (!head.value.supplierCode) return '请选择供应商'
  if (!head.value.expenseDate) return '请填写费用日期'
  if (!lines.value.length) return '请至少录入一行费用明细'
  const seenManualFe = new Set()
  for (const l of lines.value) {
    if (!l.expenseTypeName) return '存在未选择费用类型的明细行'
    const amt = Number(l.amount)
    if (!amt || isNaN(amt)) return '存在金额为空或为 0 的明细行'
    if (isRed.value) {
      if (amt > 0) return '红字单明细金额必须为负'
    } else if (amt < 0) {
      return '普通单明细金额必须为正，负金额请开红字单'
    }
    if (!isAdvance.value && l.customerExpenseNo) return '费用性质为「其他」的明细不能关联客户费用单'
    if (isAdvance.value && l.customerExpenseNo && !l.customerExpenseDetailId) {
      if (seenManualFe.has(l.customerExpenseNo)) {
        return `客户费用单 ${l.customerExpenseNo} 只录单号关联时只能出现一行，请合并或通过选择器选择明细行`
      }
      seenManualFe.add(l.customerExpenseNo)
    }
  }
  if (isRed.value && totalAmount.value >= 0) return '红字单合计金额必须为负'
  return ''
}

async function save(thenAudit) {
  const err = validate()
  if (err) { feedback.value = err; return }
  const payload = buildPayload()
  const verb = isEdit.value ? '修改' : '新建'
  const redTip = isRed.value ? '（红字单）' : ''
  if (!window.confirm(
    `确认${verb}厂家费用单${redTip}？供应商：${head.value.supplierName}，合计 ￥${totalAmount.value.toFixed(2)}`
    + (thenAudit ? '\n保存后将立即审核并立费用债权。' : ''))) return
  saving.value = true
  feedback.value = null
  try {
    let res
    if (isEdit.value) res = await post('/finance/factory-expense/update', payload)
    else res = await post('/finance/factory-expense/create', payload)
    if (thenAudit) {
      await post('/finance/factory-expense/audit', { factoryExpenseId: res.factoryExpenseId })
    }
    emit('saved')
    close()
  } catch (e) {
    feedback.value = e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

function fmt(v) {
  return (Number(v || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtDate(v) {
  return v ? String(v).substring(0, 10) : ''
}

defineExpose({ open })
</script>

<template>
  <div v-if="visible" class="xw-mask" @click.self="close">
    <div class="xw-modal">
      <div class="xw-h">
        {{ readonly ? '查看厂家费用单' : (isEdit ? '编辑厂家费用单' : '新建厂家费用单') }}
        <span v-if="head.factoryExpenseNo" class="no-tag">{{ head.factoryExpenseNo }}</span>
        <span v-if="isRed" class="red-tag">红字单（原单 {{ head.redSourceNo }}）</span>
        <span v-if="head.businessSource === 'OPENING_BACKFILL'" class="backfill-tag">上线历史补录</span>
        <span class="x" @click="close">×</span>
      </div>

      <div class="xw-b">
        <div v-if="feedback" class="toast-inline error">{{ feedback }}</div>
        <div class="xw-grid">
          <!-- 左：主单 -->
          <div class="master">
            <div class="fi">
              <label>供应商 <span class="req">*</span></label>
              <select v-model="head.supplierCode" :disabled="readonly || isRed" @change="onSupplierChange">
                <option value="">请选择供应商</option>
                <option v-for="s in suppliers" :key="s.supplierCode" :value="s.supplierCode">
                  {{ s.supplierCode }} {{ s.supplierName }}
                </option>
              </select>
            </div>
            <div class="fi">
              <label>费用日期 <span class="req">*</span></label>
              <input type="date" v-model="head.expenseDate" :disabled="readonly" />
            </div>
            <div class="fi">
              <label>费用性质 <span class="req">*</span></label>
              <select v-model="head.claimType" :disabled="readonly || isRed" @change="onClaimTypeChange">
                <option value="ADVANCE">代垫（厂家承担，先垫付客户）</option>
                <option value="OTHER">其他（厂家给予的费用额度）</option>
              </select>
            </div>
            <div class="fi">
              <label>厂家协议号 / 票据号</label>
              <input v-model="head.externalVoucherNo" :disabled="readonly" maxlength="50" placeholder="选填" />
            </div>
            <div class="fi">
              <label>经手人</label>
              <select v-model="head.handler" :disabled="readonly">
                <option value="">请选择</option>
                <option v-for="e in employees" :key="e.employeeCode || e.code" :value="e.employeeName || e.name">
                  {{ e.employeeName || e.name }}
                </option>
              </select>
            </div>
            <div class="fi">
              <label>部门</label>
              <input v-model="head.department" :disabled="readonly" maxlength="50" placeholder="选填" />
            </div>
            <div class="fi two">
              <div>
                <label>税额合计</label>
                <input type="number" step="0.01" v-model="head.totalTaxAmount" :disabled="readonly" placeholder="选填" />
              </div>
              <div>
                <label>不含税金额</label>
                <input type="number" step="0.01" v-model="head.totalExcludingTaxAmount" :disabled="readonly" placeholder="选填" />
              </div>
            </div>
            <div v-if="backfillEnabled && !readonly" class="fi check">
              <label><input type="checkbox" v-model="head.openingBackfill" :disabled="isEdit" />
                上线历史补录单（只立费用账户，不生成总账凭证）</label>
            </div>
            <div class="balance-panel">
              <div class="bp-line">明细行数：<b>{{ lines.length }}</b> 行</div>
              <div class="bp-line">费用合计：<b class="money" :class="{ neg: totalAmount < 0 }">￥{{ fmt(totalAmount) }}</b></div>
              <div v-if="isRed" class="bp-warn">红字单：明细金额必须为负，不可关联客户费用单、不可被兑现</div>
            </div>
            <div class="fi">
              <label>备注</label>
              <textarea v-model="head.remark" rows="3" :disabled="readonly" maxlength="200" placeholder="选填，最多 200 字"></textarea>
            </div>
            <div v-if="readonly" class="fi">
              <label>单据状态 / 来源</label>
              <div class="ro-text">
                <span class="tag" :class="head.status">{{ head.status === 'APPROVED' ? '已审核' : '待审核' }}</span>
                {{ { MANUAL: '手工录入', CUSTOMER_EXPENSE: '客户费用单关联', IMPORT: 'Excel 导入' }[head.sourceMode] || '手工录入' }}
              </div>
            </div>
          </div>

          <!-- 右：费用行 -->
          <div class="detail-side">
            <div class="detail-head">
              <span>费用明细{{ isAdvance && !isRed ? '（代垫行可关联客户费用单行）' : '' }}</span>
              <button v-if="!readonly" class="btn sm" @click="addLine">新增行</button>
            </div>
            <div class="detail-scroll">
              <table class="line-table">
                <thead>
                  <tr>
                    <th style="width:44px">#</th>
                    <th style="width:150px">费用类型 <span class="req">*</span></th>
                    <th style="width:140px">垫付客户</th>
                    <th v-if="isAdvance && !isRed" style="width:230px">关联客户费用单（整单占用）</th>
                    <th style="width:100px">贷方科目</th>
                    <th style="width:70px">数量</th>
                    <th style="width:90px">单价</th>
                    <th style="width:100px">金额 <span class="req">*</span></th>
                    <th style="min-width:120px">备注</th>
                    <th v-if="!readonly" style="width:50px"></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(l, i) in lines" :key="l.key">
                    <td class="c">{{ i + 1 }}</td>
                    <td>
                      <select v-model="l.expenseTypeName" :disabled="readonly" style="width:100%">
                        <option value="">请选择</option>
                        <option v-for="et in expenseTypes" :key="et.expenseTypeCode || et.code"
                                :value="et.expenseTypeName || et.name">
                          {{ et.expenseTypeName || et.name }}
                        </option>
                      </select>
                    </td>
                    <td>
                      <select v-model="l.customerCode" :disabled="readonly" @change="onCustomerChange(l)" style="width:100%">
                        <option value="">—</option>
                        <option v-for="c in customers" :key="c.customerCode" :value="c.customerCode">
                          {{ c.customerCode }} {{ c.customerName }}
                        </option>
                      </select>
                    </td>
                    <td v-if="isAdvance && !isRed">
                      <div class="fe-cell">
                        <input v-model="l.customerExpenseNo" :disabled="readonly"
                               class="fe-no" placeholder="FE 单号，可手输" />
                        <button v-if="!readonly" class="btn xs" @click="openPicker(l)" title="从已审核客户支出费用单中选择明细行">选择</button>
                        <button v-if="!readonly && l.customerExpenseNo" class="btn xs ghost" @click="clearFeLink(l)">清</button>
                      </div>
                      <div v-if="l.customerExpenseDetailId" class="fe-line-id">行 {{ l.customerExpenseDetailId.substring(0, 10) }}…</div>
                    </td>
                    <td><input v-model="l.glCreditSubject" :disabled="readonly" class="cell-inp"
                               placeholder="留空自动" title="留空时按费用类型配置自动解析；手工指定须为末级启用科目" /></td>
                    <td><input type="number" step="1" v-model.number="l.qty" :disabled="readonly"
                               class="cell-inp" @change="onQtyPrice(l)" /></td>
                    <td><input type="number" step="0.0001" v-model.number="l.price" :disabled="readonly"
                               class="cell-inp" @change="onQtyPrice(l)" /></td>
                    <td><input type="number" step="0.01" v-model.number="l.amount" :disabled="readonly"
                               class="cell-inp amount" :class="{ neg: Number(l.amount) < 0 }" /></td>
                    <td><input v-model="l.remark" :disabled="readonly" class="cell-inp" maxlength="100" /></td>
                    <td v-if="!readonly" class="c">
                      <a class="lk danger" v-if="lines.length > 1" @click="removeLine(l)">删</a>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <!-- 查看：兑现记录 -->
            <div v-if="readonly" class="settle-box">
              <div class="detail-head"><span>兑现记录（含待审核计划数）</span></div>
              <table class="ap-table">
                <thead>
                  <tr>
                    <th>兑现单号</th><th style="width:90px">兑现日期</th><th style="width:90px">方式</th>
                    <th style="width:100px;text-align:right">本次兑现</th><th style="width:70px">状态</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!settles.length"><td colspan="5" class="empty">暂无兑现记录</td></tr>
                  <tr v-for="(s, i) in settles" :key="i">
                    <td>{{ s.settleNo }}</td>
                    <td>{{ fmtDate(s.settleDate) }}</td>
                    <td>{{ s.settleTypeText }}</td>
                    <td class="num">{{ fmt(s.settleAmount) }}</td>
                    <td class="c">{{ s.statusText }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      <div class="xw-f">
        <button class="btn" @click="close">{{ readonly ? '关闭' : '取消' }}</button>
        <template v-if="!readonly">
          <button class="btn" :disabled="saving" @click="save(false)">{{ saving ? '保存中…' : '保存' }}</button>
          <button class="btn primary" :disabled="saving" @click="save(true)">保存并审核</button>
        </template>
      </div>
    </div>

    <!-- FE 行选择器 -->
    <div v-if="pickerVisible" class="pk-mask" @click.self="pickerVisible = false">
      <div class="pk-modal">
        <div class="xw-h">
          选择客户费用单明细行（仅已审核客户支出费用单，整单占用）
          <span class="x" @click="pickerVisible = false">×</span>
        </div>
        <div class="pk-filter">
          <label>客户 <input v-model="pickerFilters.customer" class="inp" placeholder="编号或名称"
                             @keyup.enter="loadPicker" /></label>
          <label>关键字 <input v-model="pickerFilters.keyword" class="inp" placeholder="FE 单号 / 费用类型"
                               @keyup.enter="loadPicker" /></label>
          <label>费用日期
            <input type="date" v-model="pickerFilters.dateFrom" class="inp" />
            <span class="dash">至</span>
            <input type="date" v-model="pickerFilters.dateTo" class="inp" />
          </label>
          <button class="btn primary sm" @click="loadPicker">查询</button>
        </div>
        <div class="pk-scroll">
          <table class="ap-table">
            <thead>
              <tr>
                <th style="width:140px">客户费用单号</th>
                <th style="width:90px">日期</th>
                <th>客户</th>
                <th>费用类型</th>
                <th style="width:100px;text-align:right">FE 行金额</th>
                <th>备注</th>
                <th style="width:60px"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="pickerLoading"><td colspan="7" class="empty">加载中…</td></tr>
              <tr v-else-if="!pickerRows.length"><td colspan="7" class="empty">没有可选的客户费用单行（已被其他厂家费用单整单占用的不会出现）</td></tr>
              <tr v-for="c in pickerRows" :key="c.detailId">
                <td>{{ c.expenseNo }}</td>
                <td>{{ fmtDate(c.expenseDate) }}</td>
                <td>{{ c.customerCode }} {{ c.customerName }}</td>
                <td>{{ c.expenseType }}</td>
                <td class="num strong">{{ fmt(c.detailAmount) }}</td>
                <td>{{ c.detailRemark || c.billRemark || '' }}</td>
                <td class="c"><button class="btn xs primary" :disabled="pickerRowDisabled(c)"
                                     @click="pickCandidate(c)">选择</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.xw-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); z-index: 2000;
  display: flex; align-items: center; justify-content: center; }
.xw-modal { width: 96vw; max-width: 1360px; height: 88vh; background: #fff; border-radius: 6px;
  display: flex; flex-direction: column; box-shadow: 0 8px 30px rgba(0,0,0,.2); }
.xw-h { padding: 12px 18px; font-size: 15px; font-weight: 600; border-bottom: 1px solid #ebeef5;
  display: flex; align-items: center; gap: 10px; }
.xw-h .no-tag { font-size: 12px; color: #1f6fdd; font-weight: 400; }
.xw-h .x { margin-left: auto; cursor: pointer; font-size: 20px; color: #909399; }
.red-tag { font-size: 12px; color: #fff; background: #c45656; border-radius: 3px; padding: 1px 8px; font-weight: 400; }
.backfill-tag { font-size: 12px; color: #b88230; background: #fdf6ec; border: 1px solid #faecd8;
  border-radius: 3px; padding: 0 8px; font-weight: 400; }
.xw-b { padding: 12px 18px; display: flex; flex-direction: column; flex: 1; min-height: 0; }
.toast-inline { font-size: 13px; margin-bottom: 8px; padding: 6px 10px; border-radius: 4px; }
.toast-inline.error { color: #c45656; background: #fef0f0; }
.xw-grid { display: grid; grid-template-columns: 320px 1fr; gap: 14px; flex: 1; min-height: 0; }
.master { display: flex; flex-direction: column; gap: 12px; border-right: 1px solid #ebeef5; padding-right: 14px; overflow: auto; }
.fi { display: flex; flex-direction: column; gap: 4px; }
.fi label { font-size: 12px; color: #606266; font-weight: 600; }
.fi select, .fi input, .fi textarea { border: 1px solid #dcdfe6; border-radius: 4px; padding: 5px 8px;
  font-size: 13px; font-family: inherit; }
.fi select, .fi input { height: 30px; }
.fi.two { flex-direction: row; gap: 8px; }
.fi.two > div { flex: 1; display: flex; flex-direction: column; gap: 4px; }
.fi.check label { display: flex; align-items: flex-start; gap: 6px; font-weight: 400; color: #b88230; font-size: 12.5px; }
.fi.check input { height: auto; }
.req { color: #f56c6c; }
.balance-panel { background: #f5f7fa; border-radius: 4px; padding: 10px 12px;
  display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: #303133; }
.bp-line b { font-variant-numeric: tabular-nums; }
.bp-line .money { color: #1f6fdd; font-size: 15px; }
.bp-line .neg { color: #c45656; }
.bp-warn { color: #c45656; font-size: 12px; }
.ro-text { font-size: 13px; display: flex; align-items: center; gap: 8px; }
.tag { padding: 1px 8px; border-radius: 3px; font-size: 12px; }
.tag.PENDING { background: #f4f4f5; color: #909399; }
.tag.APPROVED { background: #f0f9eb; color: #2f8f46; }
.detail-side { display: flex; flex-direction: column; min-height: 0; }
.detail-head { font-size: 13px; font-weight: 600; color: #303133; margin-bottom: 8px;
  display: flex; justify-content: space-between; align-items: center; }
.detail-scroll { flex: 1; overflow: auto; border: 1px solid #ebeef5; border-radius: 4px; }
.line-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.line-table th, .line-table td { border-bottom: 1px solid #f0f2f5; padding: 5px 6px; vertical-align: middle; }
.line-table th { background: #f5f7fa; font-weight: 600; text-align: left; position: sticky; top: 0; z-index: 1;
  white-space: nowrap; }
.line-table td.c { text-align: center; }
.cell-inp { width: 100%; height: 26px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 6px; font-size: 12.5px; }
.amount { text-align: right; font-variant-numeric: tabular-nums; }
.neg { color: #c45656; }
.fe-cell { display: flex; gap: 4px; align-items: center; }
.fe-no { flex: 1; height: 26px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 6px; font-size: 12.5px; min-width: 0; }
.fe-line-id { font-size: 11px; color: #909399; margin-top: 2px; }
.settle-box { margin-top: 12px; border-top: 1px dashed #ebeef5; padding-top: 8px; max-height: 200px; overflow: auto; }
.ap-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.ap-table th, .ap-table td { border-bottom: 1px solid #f0f2f5; padding: 6px 8px; white-space: nowrap; }
.ap-table th { background: #f5f7fa; font-weight: 600; text-align: left; }
.ap-table td.num { text-align: right; font-variant-numeric: tabular-nums; }
.ap-table td.c { text-align: center; }
.ap-table .strong { font-weight: 600; color: #1f6fdd; }
.ap-table .empty { text-align: center; color: #909399; padding: 20px 0; }
.lk { color: #1f6fdd; cursor: pointer; font-size: 12.5px; }
.lk.danger { color: #c45656; }
.xs { padding: 2px 8px; font-size: 12px; }
.xs.ghost { background: #f4f4f5; color: #606266; }
.xw-f { padding: 10px 18px; border-top: 1px solid #ebeef5; display: flex; justify-content: flex-end; gap: 8px; }

.pk-mask { position: fixed; inset: 0; background: rgba(0,0,0,.35); z-index: 2200;
  display: flex; align-items: center; justify-content: center; }
.pk-modal { width: 900px; max-width: 95vw; max-height: 80vh; background: #fff; border-radius: 6px;
  display: flex; flex-direction: column; box-shadow: 0 8px 30px rgba(0,0,0,.2); }
.pk-filter { display: flex; flex-wrap: wrap; gap: 10px; padding: 10px 16px; align-items: center;
  border-bottom: 1px solid #ebeef5; font-size: 12.5px; color: #606266; }
.pk-filter label { display: flex; align-items: center; gap: 6px; }
.pk-filter .inp { height: 26px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 6px; font-size: 12.5px; }
.dash { color: #909399; }
.pk-scroll { flex: 1; overflow: auto; padding: 6px 16px 14px; }
</style>
