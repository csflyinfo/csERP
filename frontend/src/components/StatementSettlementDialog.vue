<script setup>
/**
 * 对账单收款/付款结算弹窗
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

// ===== 资金账户 =====
const accounts = ref([{ fundAccount: '', amount: '' }])
function addAccount() { accounts.value.push({ fundAccount: '', amount: '' }) }
function removeAccount(i) { accounts.value.splice(i, 1) }

// ===== 计算 =====
const totalAmount = computed(() =>
  selectedRows.value.reduce((s, r) => s + (Number(r._raw?.totalAmount || 0) - Number(r._raw?.paidAmount || 0)), 0))

const pendingAmount = computed(() => {
  const wo = Number(header.value.writeOff) || 0
  return Math.max(0, totalAmount.value - wo)
})

const acctTotal = computed(() => accounts.value.reduce((s, a) => s + (Number(a.amount) || 0), 0))

watch(() => props.visible, async (v) => {
  if (!v) return
  header.value = { handler: '', settleDate: new Date().toISOString().slice(0, 10), remark: '', writeOff: 0, writeOffExpenseType: '' }
  accounts.value = [{ fundAccount: '', amount: '' }]
  try { const emp = await post('/base/master/employee/page', { pageNo:1,pageSize:200,filters:{} }); employeeList.value = (emp.records||[]).filter(x=>x.employeeName||x.name) } catch(_) { employeeList.value=[] }
  try { const fa = await post('/base/master/fund-account/page', { pageNo:1,pageSize:200,filters:{} }); fundAccounts.value = (fa.records||[]).filter(x=>x.fundAccountName||x.name) } catch(_) { fundAccounts.value=[] }
  try {
    const et = await post('/base/master/expense-type/page', { pageNo:1,pageSize:500,filters:{} })
    const all = (et.records||[]).filter(x=>x.expenseTypeName||x.name)
    const parentCodes = new Set(all.map(x=>x.parentCode||x.parent_code).filter(Boolean))
    expenseTypes.value = all.filter(x=>!parentCodes.has(x.expenseTypeCode||x.code))
  } catch(_) { expenseTypes.value=[] }
  // 自动分摊
  const total = pendingAmount.value
  if (total > 0 && accounts.value.length === 1) accounts.value[0].amount = parseFloat(total.toFixed(2))
})

async function confirmSettle() {
  if (!header.value.handler) { alert('请选择经手人'); return }
  const filled = accounts.value.filter(a => a.fundAccount && Number(a.amount) > 0)
  if (filled.length === 0) { alert('请至少填写一个资金账户'); return }
  const tot = filled.reduce((s,a) => s + Number(a.amount), 0)
  if (Math.abs(tot - pendingAmount.value) > 0.01) { alert(`账户合计 ${tot.toFixed(2)} ≠ 待${isReceipt.value?'收':'付'}金额 ${pendingAmount.value.toFixed(2)}`); return }
  if (!confirm(`确认结算 ${selectedRows.value.length} 张对账单，合计 ￥${pendingAmount.value.toFixed(2)}？`)) return
  loading.value = true
  try {
    const prefix = isReceipt.value ? 'customer' : 'supplier'
    await post(`/finance/${prefix}-statement/settle`, {
      statementIds: selectedRows.value.map(r => r._raw?.statementId).filter(Boolean),
      handler: header.value.handler, settleDate: header.value.settleDate,
      remark: header.value.remark, writeOff: header.value.writeOff,
      writeOffExpenseType: header.value.writeOffExpenseType,
      accounts: filled,
    })
    emit('saved')
  } catch (e) { alert('结算失败：'+ (e.message||'未知错误')) } finally { loading.value = false }
}
</script>

<template>
  <div v-if="visible" class="modal-lite" @click.self="emit('close')">
    <div class="modal-lite-box" style="width:min(700px,95vw);max-height:88vh">
      <div class="modal-lite-head">
        <b>{{ isReceipt ? '收款' : '付款' }}结算（{{ selectedRows.length }} 张对账单）</b>
        <div class="actions"><button class="btn" @click="emit('close')">关闭</button><button class="btn primary" @click="confirmSettle" :disabled="loading">确认{{ isReceipt?'收款':'付款' }}</button></div>
      </div>
      <div class="modal-lite-body">
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px 14px;margin-bottom:10px">
          <div class="fi"><label>经手人 <span class="req">*</span></label><select v-model="header.handler"><option value="">请选择</option><option v-for="e in employeeList" :key="e.employeeCode||e.code" :value="e.employeeName||e.name">{{ e.employeeName||e.name }}</option></select></div>
          <div class="fi"><label>记账日期</label><input type="date" v-model="header.settleDate" /></div>
          <div class="fi" style="grid-column:1/-1"><label>备注</label><input v-model="header.remark" /></div>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px 14px;margin-bottom:10px;padding:8px;background:#f5f7fa;border-radius:4px">
          <div class="fi"><label>应收金额</label><b style="font-size:14px;color:#303133">{{ totalAmount.toFixed(2) }}</b></div>
          <div class="fi"><label>抹零金额</label><input type="number" step="0.01" v-model.number="header.writeOff" /></div>
          <div class="fi"><label>抹零费用类型</label><select v-model="header.writeOffExpenseType"><option value="">请选择</option><option v-for="et in expenseTypes" :key="et.expenseTypeCode||et.code" :value="et.expenseTypeName||et.name">{{ et.expenseTypeName||et.name }}</option></select></div>
          <div class="fi"><label>待{{ isReceipt?'收':'付' }}金额</label><b style="font-size:16px;color:#409eff">{{ pendingAmount.toFixed(2) }}</b></div>
          <div class="fi"><label>剩余应收</label><b style="font-size:14px" :style="{color:(pendingAmount-acctTotal)>0?'#e6a23c':'#67c23a'}">{{ (pendingAmount - acctTotal).toFixed(2) }}</b></div>
        </div>
        <fieldset><legend>{{ isReceipt?'收款':'付款' }}账户</legend>
          <table><thead><tr><th>账户</th><th style="width:110px;text-align:right">{{ isReceipt?'收款':'付款' }}金额</th><th style="width:40px">操作</th></tr></thead>
          <tbody><tr v-for="(a,i) in accounts" :key="i">
            <td><select v-model="a.fundAccount" style="width:100%;height:26px;font-size:12px"><option value="">请选择</option><option v-for="fa in fundAccounts" :key="fa.fundAccountCode||fa.code" :value="fa.fundAccountName||fa.name">{{ fa.fundAccountCode||fa.code }} {{ fa.fundAccountName||fa.name }}</option></select></td>
            <td><input type="number" min="0" step="0.01" v-model.number="a.amount" style="width:100%;height:26px;text-align:right;font-size:12px" /></td>
            <td><button class="link link-btn danger-link" @click="removeAccount(i)" :disabled="accounts.length===1">删除</button></td>
          </tr></tbody></table>
          <div style="margin-top:4px"><button class="btn" style="font-size:11px;height:22px;padding:0 8px" @click="addAccount">+ 添加账户</button><span style="margin-left:12px;font-size:12px;color:#606266">合计 ￥{{ acctTotal.toFixed(2) }}</span></div>
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
</style>
