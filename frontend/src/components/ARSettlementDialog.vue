<script setup>
/**
 * 客户应收收款结算弹窗
 * 勾选 AR 行 → 按客户分组 → 资金账户分摊 → 生成收款单并审核
 */
import { ref, watch, computed } from 'vue'
import { post } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  arRows: { type: Array, default: () => [] },   // 勾选的 AR 原始记录 [{arNo, customer, arAmount, receivedAmount, unreceivedAmount, sourceBill, ...}]
})
const emit = defineEmits(['close', 'saved'])

const loading = ref(false)
const employeeList = ref([])
const fundAccounts = ref([])  // 从 base_fund_account 加载

// ==================== 头部 ====================
const header = ref({
  receiptDate: new Date().toISOString().slice(0, 10),
  summary: '',
  handler: '',
  writeOff: 0,
  writeOffExpenseType: '',
})
const expenseTypes = ref([])  // 抹零费用类型（末级）

// ==================== 资金账户 ====================
const accounts = ref([{ fundAccount: '', amount: '' }])
function addAccount() { accounts.value.push({ fundAccount: '', amount: '' }) }
function removeAccount(i) { accounts.value.splice(i, 1) }

// ==================== AR 明细行 ====================
const arLines = ref([])  // [{ arNo, customer, sourceBill, arAmount, receivedAmount, unreceivedAmount, writeOff: 0, settleAmount }]

watch(() => props.visible, async (v) => {
  if (!v) return
  header.value = { receiptDate: new Date().toISOString().slice(0, 10), summary: '', handler: '', writeOff: 0, writeOffExpenseType: '' }
  accounts.value = [{ fundAccount: '', amount: '' }]
  arLines.value = (props.arRows || []).map(r => {
    const unpaid = Math.round((Number(r.unreceivedAmount || 0) - Number(r.receivedAmount || 0)) * 100) / 100
    return {
      arNo: r.arNo || '', customer: r.customer || '',
      sourceBill: r.sourceBill || '', arAmount: Number(r.arAmount || 0),
      receivedAmount: Number(r.receivedAmount || 0),
      unreceivedAmount: unpaid > 0 ? unpaid : 0,
      writeOff: 0, settleAmount: unpaid > 0 ? unpaid : 0,
    }
  })
  // 加载下拉
  try { const emp = await post('/base/master/employee/page', { pageNo: 1, pageSize: 200, filters: {} }); employeeList.value = (emp.records || []).filter(x => x.employeeName || x.name) } catch (_) { employeeList.value = [] }
  try { const fa = await post('/base/master/fund-account/page', { pageNo: 1, pageSize: 200, filters: {} }); fundAccounts.value = (fa.records || []).filter(x => x.fundAccountName || x.name) } catch (_) { fundAccounts.value = [] }
  try { const et = await post('/base/master/expense-type/page', { pageNo:1,pageSize:500,filters:{} }); const all = (et.records||[]).filter(x=>x.expenseTypeName||x.name); const parentCodes = new Set(all.map(x=>x.parentCode||x.parent_code).filter(Boolean)); expenseTypes.value = all.filter(x=>!parentCodes.has(x.expenseTypeCode||x.code)) } catch(_) { expenseTypes.value=[] }
})

// ==================== 金额计算 ====================
const totalSettle = computed(() => arLines.value.reduce((s, l) => s + (Number(l.settleAmount) || 0), 0))
const totalAccounts = computed(() => accounts.value.reduce((s, a) => s + (Number(a.amount) || 0), 0))
const actualAmount = computed(() => {
  const wo = Number(header.value.writeOff) || 0
  return Math.max(0, totalSettle.value - wo)
})

// ==================== 确认结算 ====================
async function confirmSettle() {
  if (!header.value.handler) { show('请选择经手人'); return }
  const wo = Number(header.value.writeOff) || 0
  if (wo !== 0 && !header.value.writeOffExpenseType) { show('请选择抹零费用类型'); return }
  const filledAccts = accounts.value.filter(a => a.fundAccount && Number(a.amount) > 0)
  if (filledAccts.length === 0) { show('请至少填写一个资金账户'); return }
  const acctTotal = filledAccts.reduce((s, a) => s + Number(a.amount), 0)
  if (Math.abs(acctTotal - actualAmount.value) > 0.01) { show(`账户合计 ${acctTotal.toFixed(2)} ≠ 本次实收 ${actualAmount.value.toFixed(2)}`); return }
  if (!confirm(`确认结算 ${arLines.value.length} 笔应收？\n本次实收 ￥${actualAmount.value.toFixed(2)}` + (wo !== 0 ? `\n抹零 ￥${wo.toFixed(2)}` : ''))) return
  loading.value = true
  try {
    await post('/finance/ar/settle', {
      receiptDate: header.value.receiptDate,
      summary: header.value.summary,
      handler: header.value.handler,
      accounts: filledAccts,
      writeOff: wo,
      writeOffExpenseType: header.value.writeOffExpenseType,
      arList: arLines.value.filter(l => Number(l.settleAmount) > 0).map(l => ({
        arNo: l.arNo, settleAmount: l.settleAmount,
      })),
    })
    emit('saved')
  } catch (e) { show('结算失败：' + (e.message || '未知错误')) }
  finally { loading.value = false }
}

function show(msg) { alert(msg) }
</script>

<template>
  <div v-if="visible" class="modal-lite" @click.self="emit('close')">
    <div class="modal-lite-box" style="width:min(900px,97vw);max-height:90vh">
      <div class="modal-lite-head">
        <b>收款结算</b>
        <div class="actions"><button class="btn" @click="emit('close')">关闭</button><button class="btn primary" @click="confirmSettle" :disabled="loading">确认结算</button></div>
      </div>
      <div class="modal-lite-body">
        <!-- 头部 + 金额汇总 -->
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px 14px;margin-bottom:10px">
          <div class="fi"><label>收款日期</label><input type="date" v-model="header.receiptDate" /></div>
          <div class="fi"><label>经手人 <span class="req">*</span></label><select v-model="header.handler"><option value="">请选择</option><option v-for="e in employeeList" :key="e.employeeCode||e.code" :value="e.employeeName||e.name">{{ e.employeeName||e.name }}</option></select></div>
          <div class="fi"><label>收款摘要</label><input v-model="header.summary" placeholder="选填" /></div>
        </div>
        <div style="display:flex;gap:20px;padding:8px 12px;background:#f5f7fa;border-radius:4px;margin-bottom:10px;font-size:13px">
          <span>本次应收：<b style="color:#303133">{{ totalSettle.toFixed(2) }}</b></span>
          <span>抹零金额：<b :style="{color: (header.writeOff||0)!==0?'#e6a23c':'#909399'}">{{ (header.writeOff || 0).toFixed(2) }}</b></span>
          <span>本次实收：<b style="color:#409eff;font-size:15px">{{ actualAmount.toFixed(2) }}</b></span>
          <div class="fi" v-if="(header.writeOff||0) !== 0" style="flex:1"><label>抹零费用类型 <span class="req">*</span></label><select v-model="header.writeOffExpenseType"><option value="">请选择</option><option v-for="et in expenseTypes" :key="et.expenseTypeCode||et.code" :value="et.expenseTypeName||et.name">{{ et.expenseTypeName||et.name }}</option></select></div>
        </div>
        <div class="fi" style="margin-bottom:8px"><label>抹零金额</label><input type="number" step="0.01" v-model.number="header.writeOff" /></div>
        <!-- 资金账户 -->
        <fieldset style="margin-bottom:10px"><legend>资金账户（合计须 = ￥{{ actualAmount.toFixed(2) }}）</legend>
          <table><thead><tr><th>账户</th><th style="width:110px;text-align:right">收款金额</th><th style="width:40px">操作</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in accounts" :key="i">
              <td><select v-model="a.fundAccount" style="width:100%;height:26px;font-size:12px" @change="autoDistributeAccounts"><option value="">请选择</option><option v-for="fa in fundAccounts" :key="fa.fundAccountCode||fa.code" :value="fa.fundAccountName||fa.name">{{ fa.fundAccountCode||fa.code }} {{ fa.fundAccountName||fa.name }}</option></select></td>
              <td><input type="number" min="0" step="0.01" v-model.number="a.amount" style="width:100%;height:26px;text-align:right;font-size:12px" /></td>
              <td><button class="link link-btn danger-link" @click="removeAccount(i)" :disabled="accounts.length===1">删除</button></td>
            </tr>
          </tbody></table>
          <div style="margin-top:4px"><button class="btn" style="font-size:11px;height:22px;padding:0 8px" @click="addAccount">+ 添加账户</button><span style="margin-left:12px;font-size:12px;color:#606266">应收合计 ￥{{ totalSettle.toFixed(2) }}</span></div>
        </fieldset>
        <!-- AR 明细 -->
        <fieldset><legend>应收明细（{{ arLines.length }} 笔）</legend>
          <div class="detail-scroll" style="max-height:300px">
            <table><thead><tr>
              <th>发货单号</th><th>客户</th><th style="text-align:right">应收金额</th><th style="text-align:right">已收金额</th><th style="text-align:right">未收金额</th><th style="text-align:right;width:90px">本次收款</th>
            </tr></thead>
            <tbody>
              <tr v-for="l in arLines" :key="l.arNo">
                <td>{{ l.sourceBill || l.arNo }}</td>
                <td>{{ l.customer }}</td>
                <td style="text-align:right">{{ l.arAmount.toFixed(2) }}</td>
                <td style="text-align:right">{{ l.receivedAmount.toFixed(2) }}</td>
                <td style="text-align:right;color:#409eff">{{ l.unreceivedAmount.toFixed(2) }}</td>
                <td><input type="number" min="0" step="0.01" v-model.number="l.settleAmount" style="width:100%;height:24px;text-align:right;font-size:12px;font-weight:600" /></td>
              </tr>
            </tbody></table>
          </div>
        </fieldset>
      </div>
    </div>
  </div>
</template>

<style scoped>
.req { color: #f56c6c; }
.fi { display: flex; flex-direction: column; gap: 2px; }
.fi label { font-size: 12px; color: #606266; font-weight: 600; }
.fi input, .fi select { height: 28px; padding: 0 6px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; }
fieldset { border: 1px solid #e5e7eb; border-radius: 4px; padding: 8px 12px; }
legend { font-size: 12px; color: #303133; font-weight: 600; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th { background: #f5f7fa; padding: 5px 6px; text-align: left; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
td { padding: 3px 6px; border-bottom: 1px solid #f0f0f0; }
.detail-scroll { overflow: auto; }
</style>
