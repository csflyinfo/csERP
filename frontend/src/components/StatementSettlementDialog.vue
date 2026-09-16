<script setup>
/**
 * 对账单收款/付款结算弹窗（PRD-35 M3：客户收款支持使用预收）
 * 预收部分自动生成并审核 XH 预收核销单（来源 STATEMENT_SETTLE），现金部分生 SK 收款单
 */
import { ref, watch, computed } from 'vue'
import { post } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  moduleCode: { type: String, default: 'customerStatement' },
  selectedRows: { type: Array, default: () => [] },
})
const emit = defineEmits(['close', 'saved'])

const loading = ref(false)
const employeeList = ref([])
const fundAccounts = ref([])
const expenseTypes = ref([])  // 抹零费用类型（末级）

const isReceipt = computed(() => props.moduleCode === 'customerStatement')

// ===== 头部 =====
const header = ref({
  handler: '',
  settleDate: new Date().toISOString().slice(0, 10),
  remark: '',
  writeOff: 0,
  writeOffExpenseType: '',
})

// ===== 预收（仅客户收款，PRD-35 M3） =====
const advanceBalance = ref(0)
const useAdvance = ref(false)
const advanceAmount = ref(0)
// props 在 <script setup> 里只能通过 props.xxx 访问（数组本身，无 .value）
const customerCode = computed(() => props.selectedRows[0]?._raw?.customerCode || '')
const customerName = computed(() => props.selectedRows[0]?._raw?.customerName || '')

// ===== 资金账户 =====
const accounts = ref([{ fundAccount: '', amount: '' }])
function addAccount() { accounts.value.push({ fundAccount: '', amount: '' }) }
function removeAccount(i) { accounts.value.splice(i, 1) }

// ===== 计算 =====
const totalAmount = computed(() =>
  props.selectedRows.reduce((s, r) => s + (Number(r._raw?.totalAmount || 0) - Number(r._raw?.paidAmount || 0)), 0))

const netSettle = computed(() => {
  const wo = Number(header.value.writeOff) || 0
  return Math.round(Math.max(0, totalAmount.value - wo) * 100) / 100
})
const pendingAmount = netSettle  // 兼容模板旧名：净额

const advanceCap = computed(() =>
  Math.round(Math.min(netSettle.value, Number(advanceBalance.value) || 0) * 100) / 100)
const cashNeeded = computed(() =>
  Math.round(Math.max(0, netSettle.value - (isReceipt.value && useAdvance.value ? Number(advanceAmount.value) || 0 : 0)) * 100) / 100)

const acctTotal = computed(() => accounts.value.reduce((s, a) => s + (Number(a.amount) || 0), 0))

watch(useAdvance, (v) => {
  if (v) advanceAmount.value = advanceCap.value
})
watch(advanceCap, (v) => {
  if (useAdvance.value && Number(advanceAmount.value) > v) advanceAmount.value = v
})

async function loadAdvanceBalance() {
  advanceBalance.value = 0
  useAdvance.value = false
  advanceAmount.value = 0
  if (!isReceipt.value) return
  try {
    const body = customerCode.value ? { customerCode: customerCode.value } : { customerName: customerName.value }
    const r = await post('/finance/customer-account/advance-balance', body)
    advanceBalance.value = Number(r.advanceBalance) || 0
  } catch (_) { advanceBalance.value = 0 }
}

watch(() => props.visible, async (v) => {
  if (!v) return
  header.value = { handler: '', settleDate: new Date().toISOString().slice(0, 10), remark: '', writeOff: 0, writeOffExpenseType: '' }
  accounts.value = [{ fundAccount: '', amount: '' }]
  await loadAdvanceBalance()
  try { const emp = await post('/base/master/employee/page', { pageNo:1,pageSize:200,filters:{} }); employeeList.value = (emp.records||[]).filter(x=>x.employeeName||x.name) } catch(_) { employeeList.value=[] }
  try { const fa = await post('/base/master/fund-account/page', { pageNo:1,pageSize:200,filters:{} }); fundAccounts.value = (fa.records||[]).filter(x=>x.fundAccountName||x.name) } catch(_) { fundAccounts.value=[] }
  try {
    const et = await post('/base/master/expense-type/page', { pageNo:1,pageSize:500,filters:{} })
    const all = (et.records||[]).filter(x=>x.expenseTypeName||x.name)
    const parentCodes = new Set(all.map(x=>x.parentCode||x.parent_code).filter(Boolean))
    expenseTypes.value = all.filter(x=>!parentCodes.has(x.expenseTypeCode||x.code))
  } catch(_) { expenseTypes.value=[] }
})

async function confirmSettle() {
  if (!header.value.handler) { alert('请选择经手人'); return }
  const adv = isReceipt.value && useAdvance.value ? Math.round((Number(advanceAmount.value) || 0) * 100) / 100 : 0
  if (isReceipt.value && useAdvance.value && adv <= 0) {
    alert('请填写预收结算金额，或取消勾选「使用预收款」'); return
  }
  const filled = accounts.value.filter(a => a.fundAccount && Number(a.amount) > 0)
  if (cashNeeded.value > 0 && filled.length === 0) { alert('请至少填写一个资金账户'); return }
  const tot = Math.round(filled.reduce((s,a) => s + Number(a.amount), 0) * 100) / 100
  if (Math.abs(tot - cashNeeded.value) > 0.01) {
    alert(`账户合计 ￥${tot.toFixed(2)} 须等于净额扣减预收后的金额 ￥${cashNeeded.value.toFixed(2)}`); return
  }
  if (!confirm(`确认${isReceipt.value?'收':'付'}款结算 ${props.selectedRows.length} 张对账单？\n本次结算净额 ￥${netSettle.value.toFixed(2)}`
    + (adv > 0 ? `\n其中预收结算 ￥${adv.toFixed(2)}，现金 ￥${cashNeeded.value.toFixed(2)}` : ''))) return
  loading.value = true
  try {
    const prefix = isReceipt.value ? 'customer' : 'supplier'
    const payload = {
      statementIds: props.selectedRows.map(r => r._raw?.statementId).filter(Boolean),
      handler: header.value.handler, settleDate: header.value.settleDate,
      remark: header.value.remark, writeOff: header.value.writeOff,
      writeOffExpenseType: header.value.writeOffExpenseType,
      accounts: filled,
    }
    if (isReceipt.value) payload.useAdvanceAmount = adv
    const res = await post(`/finance/${prefix}-statement/settle`, payload)
    if (adv > 0 && res?.writeoffNos?.length) {
      alert(`结算成功，预收已生成核销单：${res.writeoffNos.join('，')}`)
    }
    emit('saved')
  } catch (e) { alert('结算失败：'+ (e.message||'未知错误')) } finally { loading.value = false }
}
</script>

<template>
  <div v-if="visible" class="modal-lite" @click.self="emit('close')">
    <div class="modal-lite-box" style="width:min(760px,95vw);max-height:88vh">
      <div class="modal-lite-head">
        <b>{{ isReceipt ? '收款' : '付款' }}结算（{{ selectedRows.length }} 张对账单）</b>
        <div class="actions"><button class="btn" @click="emit('close')">关闭</button><button class="btn primary" @click="confirmSettle" :disabled="loading">确认{{ isReceipt?'收款':'付款' }}</button></div>
      </div>
      <div class="modal-lite-body">
        <div v-if="isReceipt" class="settle-subtitle">
          已选 {{ selectedRows.length }} 张单据，本次结算净额 ￥{{ netSettle.toFixed(2) }}，退货负单参与净额抵扣
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px 14px;margin-bottom:10px">
          <div class="fi"><label>经手人 <span class="req">*</span></label><select v-model="header.handler"><option value="">请选择</option><option v-for="e in employeeList" :key="e.employeeCode||e.code" :value="e.employeeName||e.name">{{ e.employeeName||e.name }}</option></select></div>
          <div class="fi"><label>记账日期</label><input type="date" v-model="header.settleDate" /></div>
          <div class="fi" style="grid-column:1/-1"><label>备注</label><input v-model="header.remark" /></div>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px 14px;margin-bottom:10px;padding:8px;background:#f5f7fa;border-radius:4px">
          <div class="fi"><label>应收金额</label><b style="font-size:14px;color:#303133">{{ totalAmount.toFixed(2) }}</b></div>
          <div class="fi"><label>抹零金额</label><input type="number" step="0.01" v-model.number="header.writeOff" /></div>
          <div class="fi"><label>抹零费用类型</label><select v-model="header.writeOffExpenseType"><option value="">请选择</option><option v-for="et in expenseTypes" :key="et.expenseTypeCode||et.code" :value="et.expenseTypeName||et.name">{{ et.expenseTypeName||et.name }}</option></select></div>
          <div class="fi"><label>结算净额</label><b style="font-size:16px;color:#409eff">{{ netSettle.toFixed(2) }}</b></div>
          <div class="fi" v-if="isReceipt"><label>其中预收</label><b style="font-size:14px" :style="{color:useAdvance?'#e6a23c':'#909399'}">{{ (useAdvance ? Number(advanceAmount)||0 : 0).toFixed(2) }}</b></div>
          <div class="fi"><label>剩余应收</label><b style="font-size:14px" :style="{color:(cashNeeded-acctTotal)>0?'#e6a23c':'#67c23a'}">{{ (cashNeeded - acctTotal).toFixed(2) }}</b></div>
        </div>
        <!-- 使用预收款（仅客户收款，PRD-35 M3） -->
        <fieldset v-if="isReceipt" style="margin-bottom:10px">
          <legend>预收结算</legend>
          <div class="advance-panel">
            <div class="advance-balance">客户 ERP 预收余额：￥{{ (Number(advanceBalance) || 0).toFixed(2) }}</div>
            <label class="advance-check"><input type="checkbox" v-model="useAdvance" /><b>使用预收款</b></label>
            <div v-if="useAdvance" class="advance-input-row">
              <div class="fi"><label>预收结算金额</label>
                <input type="number" min="0" step="0.01" v-model.number="advanceAmount" />
              </div>
              <span class="advance-hint">最多 ￥{{ advanceCap.toFixed(2) }}，其余走资金账户</span>
            </div>
          </div>
        </fieldset>
        <fieldset><legend>{{ isReceipt?'收款':'付款' }}账户（账户合计须 = ￥{{ cashNeeded.toFixed(2) }}）</legend>
          <table><thead><tr><th>账户</th><th style="width:110px;text-align:right">{{ isReceipt?'收款':'付款' }}金额</th><th style="width:40px">操作</th></tr></thead>
          <tbody><tr v-for="(a,i) in accounts" :key="i">
            <td><select v-model="a.fundAccount" style="width:100%;height:26px;font-size:12px"><option value="">请选择</option><option v-for="fa in fundAccounts" :key="fa.fundAccountCode||fa.code" :value="fa.fundAccountName||fa.name">{{ fa.fundAccountCode||fa.code }} {{ fa.fundAccountName||fa.name }}</option></select></td>
            <td><input type="number" min="0" step="0.01" v-model.number="a.amount" style="width:100%;height:26px;text-align:right;font-size:12px" /></td>
            <td><button class="link link-btn danger-link" @click="removeAccount(i)" :disabled="accounts.length===1">删除</button></td>
          </tr></tbody></table>
          <div style="margin-top:4px"><button class="btn" style="font-size:11px;height:22px;padding:0 8px" @click="addAccount">+ 加账户</button>
            <span class="account-sum" :class="{ mismatch: Math.abs(acctTotal - cashNeeded) > 0.01 }">账户合计 ￥{{ acctTotal.toFixed(2) }}</span>
          </div>
        </fieldset>
      </div>
    </div>
  </div>
</template>

<style scoped>
.req { color:#f56c6c; }
.fi { display:flex; flex-direction:column; gap:2px; }
.fi label { font-size:12px; color:#606266; font-weight:600; }
.fi input, .fi select { height:28px; padding:0 6px; border:1px solid #dcdfe6; border-radius:4px; font-size:12px; }
fieldset { border:1px solid #e5e7eb; border-radius:4px; padding:8px 12px; }
legend { font-size:12px; color:#303133; font-weight:600; }
table { width:100%; border-collapse:collapse; font-size:12px; }
th { background:#f5f7fa; padding:5px 6px; text-align:left; font-weight:600; border-bottom:1px solid #e5e7eb; }
td { padding:3px 6px; border-bottom:1px solid #f0f0f0; }
.settle-subtitle { font-size:12px; color:#909399; margin-bottom:8px; }
.advance-panel { background:#f5f7fa; border-radius:4px; padding:10px 12px; display:flex; flex-wrap:wrap; align-items:center; gap:12px; }
.advance-balance { font-size:13px; color:#303133; }
.advance-check { display:flex; align-items:center; gap:4px; font-size:13px; cursor:pointer; }
.advance-input-row { display:flex; align-items:flex-end; gap:10px; width:100%; }
.advance-input-row .fi input { width:160px; }
.advance-hint { font-size:12px; color:#909399; }
.account-sum { margin-left:12px; font-size:12px; color:#606266; }
.account-sum.mismatch { color:#f56c6c; font-weight:600; }
</style>
