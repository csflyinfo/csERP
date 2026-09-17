<script setup>
/**
 * 厂家费用兑现单 新建/编辑/查看 抽屉（PRD-36 M3）
 * 上半区：该供应商已审核、未全兑现的厂家费用单（费用日期升序 FIFO）+ 本次兑现金额；
 * 下半区按方式切换：
 *  - CASH 现金结算：多资金账户到账行，合计必须等于上半区；
 *  - OFFSET 冲应付：未结应付勾选 + 本次冲销金额，合计必须等于上半区；
 *  - OTHER 其他核销：一单一个对方科目（1123 转预付 / 1405 货补 / 5601、5602 减免 / 5711 坏账）。
 * 保存只建 PENDING；「保存并审核」即刻生效（资金入账/应付核销/费用账户冲减）。
 */
import { ref, computed } from 'vue'
import { post } from '../api/client.js'
import { todayStr } from '../utils/dateTime.js'

const emit = defineEmits(['saved'])

const visible = ref(false)
const mode = ref('create')
const saving = ref(false)
const feedback = ref(null)

const suppliers = ref([])
const employees = ref([])
const fundAccounts = ref([])
// 末级科目（OTHER 科目选择用）；/leaf-options 后端已过滤「末级 + 启用」，返回体不含 status，前端不要再按 status 筛
const leafAccounts = ref([])
const feeAccounts = computed(() =>
  leafAccounts.value.filter(a => /^(5601|5602)/.test(a.accountCode)))

const head = ref(emptyHead())
function emptyHead() {
  return {
    settleId: '',
    settleNo: '',
    supplierCode: '',
    supplierName: '',
    settleDate: todayStr(),
    settleType: 'CASH',
    handler: '',
    remark: '',
    relatedBillNo: '',
    contraSubjectCode: '',
    status: '',
  }
}

const jfRows = ref([])
const fundRows = ref([])
const apRows = ref([])
let seq = 0

const readonly = computed(() => mode.value === 'view')
const isEdit = computed(() => mode.value === 'edit')
const jfTotal = computed(() => round2(jfRows.value.reduce((s, r) => s + (Number(r.settleAmount) || 0), 0)))
const fundTotal = computed(() => round2(fundRows.value.reduce((s, r) => s + (Number(r.amount) || 0), 0)))
const apTotal = computed(() => round2(apRows.value.reduce((s, r) => s + (Number(r.offsetAmount) || 0), 0)))
const lowerTotal = computed(() =>
  head.value.settleType === 'CASH' ? fundTotal.value
    : head.value.settleType === 'OFFSET' ? apTotal.value : jfTotal.value)
const balanced = computed(() => jfTotal.value > 0 && Math.abs(jfTotal.value - lowerTotal.value) < 0.005)
const contraMode = computed({
  get() {
    const c = head.value.contraSubjectCode
    if (!c) return ''
    if (['1123', '1405', '5711'].includes(c)) return c
    return 'FEE'
  },
  set(v) {
    if (v === 'FEE') {
      const first = feeAccounts.value[0]
      head.value.contraSubjectCode = first ? first.accountCode : ''
    } else {
      head.value.contraSubjectCode = v
    }
  },
})
function subjectName(code) {
  return leafAccounts.value.find(a => a.accountCode === code)?.accountName || ''
}

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
    const fa = await post('/base/master/fund-account/page', { pageNo: 1, pageSize: 200, filters: {} })
    fundAccounts.value = (fa.records || []).filter(x => (x.fundAccountName || x.name)
      && String(x.status || '').toUpperCase() !== 'STOPPED')
  } catch (_) { fundAccounts.value = [] }
  try {
    leafAccounts.value = (await post('/finance/gl/account/leaf-options', {})) || []
  } catch (_) { leafAccounts.value = [] }
}

async function open(modeName, row) {
  feedback.value = null
  mode.value = modeName || 'create'
  head.value = emptyHead()
  jfRows.value = []
  fundRows.value = [emptyFundRow()]
  apRows.value = []
  await loadOptions()
  if (mode.value === 'create') {
    visible.value = true
    return
  }
  try {
    const d = await post('/finance/factory-settle/detail', { settleId: row.settleId })
    head.value = {
      settleId: d.settleId,
      settleNo: d.settleNo || '',
      supplierCode: d.supplierCode || '',
      supplierName: d.supplierName || '',
      settleDate: d.settleDate ? String(d.settleDate).substring(0, 10) : todayStr(),
      settleType: d.settleType || 'CASH',
      handler: d.handler || '',
      remark: d.remark || '',
      relatedBillNo: d.relatedBillNo || '',
      contraSubjectCode: d.contraSubjectCode || '',
      status: d.status || '',
    }
    visible.value = true
    await loadJfCandidates(d.jfDetails || [])
    if (head.value.settleType === 'CASH') {
      fundRows.value = (d.fundDetails || []).map(x => ({
        key: ++seq, fundAccount: x.fundAccount, amount: Number(x.amount) || 0, remark: x.remark || '',
      }))
      if (!fundRows.value.length) fundRows.value = [emptyFundRow()]
    } else if (head.value.settleType === 'OFFSET') {
      await loadApCandidates(d.apDetails || [])
    }
  } catch (e) {
    feedback.value = e?.message || '单据加载失败'
    visible.value = true
  }
}

function close() { visible.value = false }

function onSupplierChange() {
  const s = suppliers.value.find(x => x.supplierCode === head.value.supplierCode)
  head.value.supplierName = s ? s.supplierName : ''
  jfRows.value = []
  apRows.value = []
  if (head.value.supplierCode) {
    loadJfCandidates([])
    if (head.value.settleType === 'OFFSET') loadApCandidates([])
  }
}

function onTypeChange() {
  // 切换方式只在新建时允许；清掉另一方式的行
  fundRows.value = head.value.settleType === 'CASH' ? [emptyFundRow()] : []
  apRows.value = []
  if (head.value.settleType === 'OFFSET' && head.value.supplierCode) loadApCandidates([])
  if (head.value.settleType !== 'OTHER') head.value.contraSubjectCode = ''
}

// ==================== JF 候选（上半区） ====================

async function loadJfCandidates(snapshot) {
  if (!head.value.supplierCode) return
  try {
    const list = await post('/finance/factory-settle/jf-candidates', {
      supplierCode: head.value.supplierCode,
    })
    const byNo = new Map(list.map(x => [x.factoryExpenseNo, x]))
    const merged = list.map(x => ({
      key: ++seq,
      factoryExpenseNo: x.factoryExpenseNo,
      expenseDate: x.expenseDate ? String(x.expenseDate).substring(0, 10) : '',
      claimTypeText: x.claimTypeText,
      expenseAmount: Number(x.totalAmount) || 0,
      settledAmount: Number(x.settledAmount) || 0,
      unsettledAmount: Number(x.unsettledAmount) || 0,
      settleAmount: 0,
      missing: false,
    }))
    // 快照行：候选已不存在（如编辑期间被别的兑现单占满）时补红行，服务端会按真值再校验
    for (const d of snapshot) {
      const exist = merged.find(r => r.factoryExpenseNo === d.factoryExpenseNo)
      if (exist) {
        exist.settleAmount = Number(d.settleAmount) || 0
      } else {
        merged.push({
          key: ++seq,
          factoryExpenseNo: d.factoryExpenseNo,
          expenseDate: d.expenseDate ? String(d.expenseDate).substring(0, 10) : '',
          claimTypeText: d.claimType === 'OTHER' ? '其他' : '代垫',
          expenseAmount: Number(d.expenseAmount) || 0,
          settledAmount: Number(d.settledBefore) || 0,
          unsettledAmount: Number(d.expenseAmount) - Number(d.settledBefore || 0),
          settleAmount: Number(d.settleAmount) || 0,
          missing: true,
        })
      }
    }
    jfRows.value = merged
  } catch (e) {
    feedback.value = e?.message || '厂家费用候选加载失败'
    jfRows.value = []
  }
}

function onCheckJf(r, checked) {
  r.settleAmount = checked ? Math.min(Number(r.unsettledAmount) || 0, jfRemainingCap(r)) : 0
}
function clampJf(r) {
  let v = Number(r.settleAmount)
  if (isNaN(v) || v < 0) v = 0
  if (v > Number(r.unsettledAmount)) v = Number(r.unsettledAmount)
  r.settleAmount = round2(v)
}
/** 同一张 JF 在单内只能出现一行（候选按单号天然唯一），占位防超用辅助。 */
function jfRemainingCap(r) {
  return Number(r.unsettledAmount) || 0
}

// ==================== CASH 资金行 ====================

function emptyFundRow() {
  return { key: ++seq, fundAccount: '', amount: null, remark: '' }
}
function addFundRow() { fundRows.value.push(emptyFundRow()) }
function removeFundRow(r) {
  fundRows.value = fundRows.value.filter(x => x.key !== r.key)
  if (!fundRows.value.length) fundRows.value = [emptyFundRow()]
}

// ==================== OFFSET AP 候选 ====================

async function loadApCandidates(snapshot) {
  if (!head.value.supplierCode) return
  try {
    const list = await post('/finance/factory-settle/ap-candidates', {
      supplierCode: head.value.supplierCode,
      keyword: '',
    })
    const byNo = new Map(list.map(x => [x.apNo, x]))
    const merged = list.map(x => ({
      key: ++seq,
      apNo: x.apNo,
      sourceBill: x.sourceBill || '',
      dueDate: x.dueDate ? String(x.dueDate).substring(0, 10) : '',
      apAmount: Number(x.apAmount) || 0,
      paidAmount: Number(x.paidAmount) || 0,
      unpaidAmount: Number(x.unpaidAmount) || 0,
      offsetAmount: 0,
      missing: false,
    }))
    for (const d of snapshot) {
      const exist = merged.find(r => r.apNo === d.apNo)
      if (exist) {
        exist.offsetAmount = Number(d.offsetAmount) || 0
      } else {
        merged.push({
          key: ++seq,
          apNo: d.apNo,
          sourceBill: d.sourceBill || '',
          dueDate: d.dueDate ? String(d.dueDate).substring(0, 10) : '',
          apAmount: Number(d.apAmount) || 0,
          paidAmount: Math.max(0, (Number(d.apAmount) || 0) - (Number(d.unpaidBefore) || 0)),
          unpaidAmount: Number(d.unpaidBefore) || 0,
          offsetAmount: Number(d.offsetAmount) || 0,
          missing: true,
        })
      }
    }
    merged.sort((a, b) => {
      if (!a.dueDate) return 1
      if (!b.dueDate) return -1
      return a.dueDate < b.dueDate ? -1 : a.dueDate > b.dueDate ? 1 : 0
    })
    apRows.value = merged
  } catch (e) {
    feedback.value = e?.message || '未结应付加载失败'
    apRows.value = []
  }
}

function onCheckAp(r, checked) {
  r.offsetAmount = checked ? Number(r.unpaidAmount) || 0 : 0
}
function clampAp(r) {
  let v = Number(r.offsetAmount)
  if (isNaN(v) || v < 0) v = 0
  if (v > Number(r.unpaidAmount)) v = Number(r.unpaidAmount)
  r.offsetAmount = round2(v)
}

// ==================== 保存 ====================

function buildPayload() {
  const jfDetails = jfRows.value
    .filter(r => Number(r.settleAmount) > 0)
    .map(r => ({ factoryExpenseNo: r.factoryExpenseNo, settleAmount: round2(r.settleAmount) }))
  const payload = {
    settleId: head.value.settleId || undefined,
    supplierCode: head.value.supplierCode,
    supplierName: head.value.supplierName,
    settleDate: head.value.settleDate,
    settleType: head.value.settleType,
    handler: head.value.handler,
    remark: head.value.remark,
    jfDetails,
  }
  if (head.value.settleType === 'CASH') {
    payload.fundDetails = fundRows.value
      .filter(r => r.fundAccount && Number(r.amount) > 0)
      .map(r => ({ fundAccount: r.fundAccount, amount: round2(r.amount), remark: r.remark }))
  } else if (head.value.settleType === 'OFFSET') {
    payload.apDetails = apRows.value
      .filter(r => Number(r.offsetAmount) > 0)
      .map(r => ({ apNo: r.apNo, offsetAmount: round2(r.offsetAmount) }))
  } else {
    payload.contraSubjectCode = head.value.contraSubjectCode
    payload.relatedBillNo = head.value.relatedBillNo
  }
  return payload
}

function validate() {
  if (!head.value.supplierCode) return '请选择供应商'
  if (!head.value.settleDate) return '请填写兑现日期'
  if (!head.value.handler) return '请选择经手人'
  if (!jfRows.value.some(r => Number(r.settleAmount) > 0)) return '请至少选择一行厂家费用单并填写本次兑现金额'
  if (jfTotal.value <= 0) return '兑现合计必须大于 0'
  if (head.value.settleType === 'CASH') {
    if (!fundRows.value.some(r => r.fundAccount && Number(r.amount) > 0)) return '请填写资金账户到账明细'
    if (!balanced.value) return `资金到账合计 ${fundTotal.value.toFixed(2)} 与费用兑现合计 ${jfTotal.value.toFixed(2)} 不一致`
  } else if (head.value.settleType === 'OFFSET') {
    if (!apRows.value.some(r => Number(r.offsetAmount) > 0)) return '请至少选择一行未结应付并填写冲销金额'
    if (!balanced.value) return `冲应付合计 ${apTotal.value.toFixed(2)} 与费用兑现合计 ${jfTotal.value.toFixed(2)} 不一致`
  } else {
    if (!head.value.contraSubjectCode) return '请选择对方科目'
    if (head.value.contraSubjectCode === '5711' && !head.value.remark.trim()) {
      return '坏账核销（对方科目 5711）必须在备注中填写原因'
    }
  }
  return ''
}

async function save(thenAudit) {
  const err = validate()
  if (err) { feedback.value = err; return }
  const typeText = { CASH: '现金结算', OFFSET: '冲应付', OTHER: '其他核销' }[head.value.settleType]
  if (!window.confirm(
    `确认${isEdit.value ? '修改' : '新建'}厂家费用兑现单（${typeText}）？供应商：${head.value.supplierName}，兑现 ￥${jfTotal.value.toFixed(2)}`
    + (thenAudit ? '\n保存后将立即审核并生效。' : ''))) return
  saving.value = true
  feedback.value = null
  try {
    let res
    if (isEdit.value) res = await post('/finance/factory-settle/update', buildPayload())
    else res = await post('/finance/factory-settle/create', buildPayload())
    if (thenAudit) {
      await post('/finance/factory-settle/audit', { settleId: res.settleId })
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

defineExpose({ open })
</script>

<template>
  <div v-if="visible" class="xw-mask" @click.self="close">
    <div class="xw-modal">
      <div class="xw-h">
        {{ readonly ? '查看厂家费用兑现单' : (isEdit ? '编辑厂家费用兑现单' : '新建厂家费用兑现单') }}
        <span v-if="head.settleNo" class="no-tag">{{ head.settleNo }}</span>
        <span class="x" @click="close">×</span>
      </div>

      <div class="xw-b">
        <div v-if="feedback" class="toast-inline error">{{ feedback }}</div>

        <!-- 主单区 -->
        <div class="head-form">
          <label>供应商 <span class="req">*</span>
            <select v-model="head.supplierCode" :disabled="readonly" @change="onSupplierChange">
              <option value="">请选择供应商</option>
              <option v-for="s in suppliers" :key="s.supplierCode" :value="s.supplierCode">
                {{ s.supplierCode }} {{ s.supplierName }}
              </option>
            </select>
          </label>
          <label>兑现日期 <span class="req">*</span>
            <input type="date" v-model="head.settleDate" :disabled="readonly" />
          </label>
          <label>兑现方式 <span class="req">*</span>
            <select v-model="head.settleType" :disabled="readonly || isEdit" @change="onTypeChange">
              <option value="CASH">现金结算</option>
              <option value="OFFSET">冲应付（账扣）</option>
              <option value="OTHER">其他核销</option>
            </select>
          </label>
          <label>经手人 <span class="req">*</span>
            <select v-model="head.handler" :disabled="readonly">
              <option value="">请选择</option>
              <option v-for="e in employees" :key="e.employeeCode || e.code" :value="e.employeeName || e.name">
                {{ e.employeeName || e.name }}
              </option>
            </select>
          </label>
          <label v-if="head.settleType === 'OTHER'" class="grow2">关联单据
            <input v-model="head.relatedBillNo" :disabled="readonly" maxlength="50"
                   placeholder="预付付款单号/报损单等，选填" />
          </label>
          <label class="grow2">备注
            <input v-model="head.remark" :disabled="readonly" maxlength="200" placeholder="选填；5711 坏账必须填原因" />
          </label>
        </div>

        <!-- OTHER 对方科目 -->
        <div v-if="head.settleType === 'OTHER'" class="contra-box">
          <div class="cb-title">对方科目（一单一个，须末级启用）</div>
          <div class="cb-opts">
            <label><input type="radio" value="1123" v-model="contraMode" :disabled="readonly" />
              1123 预付账款（转预付）</label>
            <label><input type="radio" value="1405" v-model="contraMode" :disabled="readonly" />
              1405 库存商品（厂家货补）</label>
            <label>
              <input type="radio" value="FEE" v-model="contraMode" :disabled="readonly" />
              5601/5602 费用减免
              <select v-model="head.contraSubjectCode" :disabled="readonly || contraMode !== 'FEE'" class="fee-sel">
                <option value="">请选择末级费用科目</option>
                <option v-for="a in feeAccounts" :key="a.accountCode" :value="a.accountCode">
                  {{ a.accountCode }} {{ a.accountName }}
                </option>
              </select>
            </label>
            <label><input type="radio" value="5711" v-model="contraMode" :disabled="readonly" />
              5711 营业外支出（坏账，备注必填原因）</label>
            <span v-if="head.contraSubjectCode" class="cb-cur">
              当前：{{ head.contraSubjectCode }} {{ subjectName(head.contraSubjectCode) }}
            </span>
          </div>
        </div>

        <div class="two-pane">
          <!-- 上半区：JF 候选 -->
          <div class="pane">
            <div class="pane-head">
              <span>厂家费用单（按费用日期升序，FIFO）</span>
              <span class="sum">本次兑现合计：<b :class="{ neg: !balanced }">￥{{ fmt(jfTotal) }}</b></span>
            </div>
            <div class="pane-scroll">
              <table class="ln">
                <thead>
                  <tr>
                    <th v-if="!readonly" style="width:34px"></th>
                    <th style="width:140px">费用单号</th>
                    <th style="width:80px">日期</th>
                    <th style="width:50px">性质</th>
                    <th style="width:90px;text-align:right">费用金额</th>
                    <th style="width:80px;text-align:right">已兑现</th>
                    <th style="width:90px;text-align:right">未兑现</th>
                    <th style="width:110px;text-align:right">本次兑现 <span class="req">*</span></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!head.supplierCode"><td :colspan="readonly ? 7 : 8" class="empty">请先选择供应商</td></tr>
                  <tr v-else-if="!jfRows.length"><td :colspan="readonly ? 7 : 8" class="empty">该供应商没有可兑现的已审核厂家费用单</td></tr>
                  <tr v-for="r in jfRows" :key="r.key" :class="{ missing: r.missing }">
                    <td v-if="!readonly" class="c">
                      <input type="checkbox" :checked="Number(r.settleAmount) > 0"
                             @change="onCheckJf(r, $event.target.checked)" />
                    </td>
                    <td>{{ r.factoryExpenseNo }}</td>
                    <td>{{ r.expenseDate }}</td>
                    <td class="c">{{ r.claimTypeText }}</td>
                    <td class="num">{{ fmt(r.expenseAmount) }}</td>
                    <td class="num">{{ fmt(r.settledAmount) }}</td>
                    <td class="num strong">{{ fmt(r.unsettledAmount) }}</td>
                    <td><input type="number" step="0.01" min="0" class="amt"
                               v-model.number="r.settleAmount" :disabled="readonly" @change="clampJf(r)" /></td>
                  </tr>
                </tbody>
              </table>
              <div v-if="jfRows.some(r => r.missing)" class="missing-tip">
                标红行是制单时快照：该费用单当前已不在候选列表，保存时服务端会按真值重新校验。
              </div>
            </div>
          </div>

          <!-- 下半区：方式明细 -->
          <div class="pane">
            <!-- CASH -->
            <template v-if="head.settleType === 'CASH'">
              <div class="pane-head">
                <span>资金到账（可多行，合计须等于费用兑现合计）</span>
                <span class="sum">资金合计：<b :class="{ neg: !balanced }">￥{{ fmt(fundTotal) }}</b>
                  <button v-if="!readonly" class="btn xs" @click="addFundRow">新增行</button>
                </span>
              </div>
              <div class="pane-scroll">
                <table class="ln">
                  <thead>
                    <tr>
                      <th style="width:220px">资金账户</th>
                      <th style="width:120px;text-align:right">到账金额</th>
                      <th>备注</th>
                      <th v-if="!readonly" style="width:50px"></th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="r in fundRows" :key="r.key">
                      <td>
                        <select v-model="r.fundAccount" :disabled="readonly" style="width:100%">
                          <option value="">请选择资金账户</option>
                          <option v-for="fa in fundAccounts" :key="fa.fundAccountCode || fa.code"
                                  :value="fa.fundAccountName || fa.name">
                            {{ fa.fundAccountCode || fa.code }} {{ fa.fundAccountName || fa.name }}
                          </option>
                        </select>
                      </td>
                      <td><input type="number" step="0.01" min="0" class="amt"
                                 v-model.number="r.amount" :disabled="readonly" /></td>
                      <td><input v-model="r.remark" :disabled="readonly" class="cell-inp" maxlength="100" /></td>
                      <td v-if="!readonly" class="c">
                        <a v-if="fundRows.length > 1" class="lk danger" @click="removeFundRow(r)">删</a>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>

            <!-- OFFSET -->
            <template v-else-if="head.settleType === 'OFFSET'">
              <div class="pane-head">
                <span>未结应付（按到期日升序，勾选后填写本次冲销）</span>
                <span class="sum">冲应付合计：<b :class="{ neg: !balanced }">￥{{ fmt(apTotal) }}</b></span>
              </div>
              <div class="pane-scroll">
                <table class="ln">
                  <thead>
                    <tr>
                      <th v-if="!readonly" style="width:34px"></th>
                      <th style="width:130px">应付单号</th>
                      <th style="width:130px">来源单据</th>
                      <th style="width:85px">到期日</th>
                      <th style="width:90px;text-align:right">应付金额</th>
                      <th style="width:80px;text-align:right">已付</th>
                      <th style="width:90px;text-align:right">未付</th>
                      <th style="width:110px;text-align:right">本次冲销</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-if="!head.supplierCode"><td :colspan="readonly ? 7 : 8" class="empty">请先选择供应商</td></tr>
                    <tr v-else-if="!apRows.length"><td :colspan="readonly ? 7 : 8" class="empty">该供应商没有未结清应付</td></tr>
                    <tr v-for="r in apRows" :key="r.key" :class="{ missing: r.missing }">
                      <td v-if="!readonly" class="c">
                        <input type="checkbox" :checked="Number(r.offsetAmount) > 0"
                               @change="onCheckAp(r, $event.target.checked)" />
                      </td>
                      <td>{{ r.apNo }}</td>
                      <td>{{ r.sourceBill || '—' }}</td>
                      <td>{{ r.dueDate || '—' }}</td>
                      <td class="num">{{ fmt(r.apAmount) }}</td>
                      <td class="num">{{ fmt(r.paidAmount) }}</td>
                      <td class="num strong">{{ fmt(r.unpaidAmount) }}</td>
                      <td><input type="number" step="0.01" min="0" class="amt"
                                 v-model.number="r.offsetAmount" :disabled="readonly" @change="clampAp(r)" /></td>
                    </tr>
                  </tbody>
                </table>
                <div v-if="apRows.some(r => r.missing)" class="missing-tip">
                  标红行是制单时快照：该应付当前已不在未结列表，保存时服务端会按真值重新校验。
                </div>
              </div>
            </template>

            <!-- OTHER -->
            <template v-else>
              <div class="pane-head"><span>其他核销说明</span></div>
              <div class="other-info">
                <p>· 审核后厂家费用账户按合计冲减，供应商往来台账登记非现金结转（OUT）。</p>
                <p>· 1123 转预付：同时增加供应商预付余额；若该供应商预付已被核销占用导致余额不足，审核将被拒绝，请先反审核相关预付核销单或退款单。</p>
                <p>· 5601/5602 费用减免：计入对应末级费用科目的借方。</p>
                <p>· 5711 坏账：备注必须填写坏账原因。</p>
                <p class="big">本次兑现合计：￥{{ fmt(jfTotal) }}</p>
              </div>
            </template>
          </div>
        </div>
      </div>

      <div class="xw-f">
        <button class="btn" @click="close">{{ readonly ? '关闭' : '取消' }}</button>
        <template v-if="!readonly">
          <button class="btn" :disabled="saving || !balanced" :title="!balanced ? '上下两区合计不一致' : ''"
                  @click="save(false)">{{ saving ? '保存中…' : '保存' }}</button>
          <button class="btn primary" :disabled="saving || !balanced" :title="!balanced ? '上下两区合计不一致' : ''"
                  @click="save(true)">保存并审核</button>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.xw-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); z-index: 2000;
  display: flex; align-items: center; justify-content: center; }
.xw-modal { width: 97vw; max-width: 1400px; height: 90vh; background: #fff; border-radius: 6px;
  display: flex; flex-direction: column; box-shadow: 0 8px 30px rgba(0,0,0,.2); }
.xw-h { padding: 12px 18px; font-size: 15px; font-weight: 600; border-bottom: 1px solid #ebeef5;
  display: flex; align-items: center; gap: 10px; }
.xw-h .no-tag { font-size: 12px; color: #1f6fdd; font-weight: 400; }
.xw-h .x { margin-left: auto; cursor: pointer; font-size: 20px; color: #909399; }
.xw-b { padding: 12px 18px; display: flex; flex-direction: column; flex: 1; min-height: 0; gap: 10px; }
.toast-inline { font-size: 13px; padding: 6px 10px; border-radius: 4px; }
.toast-inline.error { color: #c45656; background: #fef0f0; }
.req { color: #f56c6c; }

.head-form { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.head-form label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #606266;
  font-weight: 600; }
.head-form select, .head-form input { height: 30px; border: 1px solid #dcdfe6; border-radius: 4px;
  padding: 0 8px; font-size: 13px; font-family: inherit; min-width: 170px; }
.head-form .grow2 { flex: 1.4 1 220px; }
.head-form .grow2 input { width: 100%; }

.contra-box { border: 1px solid #faecd8; background: #fdf6ec; border-radius: 4px; padding: 8px 12px; }
.cb-title { font-size: 12.5px; font-weight: 600; color: #b88230; margin-bottom: 6px; }
.cb-opts { display: flex; flex-wrap: wrap; gap: 14px; align-items: center; font-size: 13px; color: #303133; }
.cb-opts label { display: flex; align-items: center; gap: 5px; font-weight: 400; white-space: nowrap; }
.fee-sel { height: 28px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 6px; font-size: 12.5px; }
.cb-cur { color: #1f6fdd; font-size: 12.5px; }

.two-pane { display: grid; grid-template-rows: 1fr 1fr; gap: 10px; flex: 1; min-height: 0; }
.pane { display: flex; flex-direction: column; min-height: 0; border: 1px solid #ebeef5; border-radius: 4px; }
.pane-head { padding: 8px 12px; background: #f5f7fa; font-size: 13px; font-weight: 600;
  display: flex; justify-content: space-between; align-items: center; border-radius: 4px 4px 0 0; }
.pane-head .sum { font-weight: 400; color: #606266; display: flex; align-items: center; gap: 10px; }
.pane-head b { color: #1f6fdd; font-variant-numeric: tabular-nums; }
.pane-head b.neg { color: #c45656; }
.pane-scroll { flex: 1; overflow: auto; }
.ln { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.ln th, .ln td { border-bottom: 1px solid #f0f2f5; padding: 5px 8px; white-space: nowrap; }
.ln th { background: #fafafa; font-weight: 600; text-align: left; position: sticky; top: 0; z-index: 1; }
.ln td.c { text-align: center; }
.ln td.num { text-align: right; font-variant-numeric: tabular-nums; }
.ln .strong { font-weight: 600; color: #1f6fdd; }
.ln .empty { text-align: center; color: #909399; padding: 24px 0; }
.ln tr.missing td { background: #fff7f6; }
.amt { width: 104px; height: 26px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 6px;
  text-align: right; font-size: 12.5px; }
.cell-inp { width: 100%; height: 26px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 6px; font-size: 12.5px; }
.neg { color: #c45656; }
.missing-tip { color: #b88230; font-size: 12px; padding: 6px 10px; }
.lk { color: #1f6fdd; cursor: pointer; font-size: 12.5px; }
.lk.danger { color: #c45656; }
.xs { padding: 2px 8px; font-size: 12px; }
.other-info { padding: 14px 18px; font-size: 13px; color: #606266; line-height: 1.9; }
.other-info .big { font-size: 15px; color: #1f6fdd; font-weight: 600; }
.xw-f { padding: 10px 18px; border-top: 1px solid #ebeef5; display: flex; justify-content: flex-end; gap: 8px; }
</style>
