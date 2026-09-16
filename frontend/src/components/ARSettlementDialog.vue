<script setup>
/**
 * 客户应收收款结算弹窗（PRD-35 M3）
 * 勾选 AR 行 → 按客户分组 → 可使用预收（FIFO 冲最早到期行）→ 资金账户分摊 → 生成收款单并审核
 * 现金部分生成 SK 收款单（AR_SETTLE_CASH），预收部分自动生成并审核 XH 预收核销单
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

// ==================== AR 明细行 ====================
// 注意：arLines/totalSettle 必须在 netSettle 等 computed 之前声明，
// 否则 <script setup> 常量暂时性死区会让弹窗组件 setup 直接抛
// ReferenceError: Cannot access 'totalSettle' before initialization，
// 连带把引用本组件的应收列表页整页渲染打挂（白屏）。
const arLines = ref([])  // [{ arNo, customer, sourceBill, arAmount, receivedAmount, unreceivedAmount, writeOff: 0, settleAmount }]
const totalSettle = computed(() => arLines.value.reduce((s, l) => s + (Number(l.settleAmount) || 0), 0))

// ==================== 预收（PRD-35 M3） ====================
const advanceBalance = ref(0)
const useAdvance = ref(false)
const advanceAmount = ref(0)

const uniqueCustomers = computed(() => [...new Set((props.arRows || []).map(r => r.customer).filter(Boolean))])
const singleCustomer = computed(() => uniqueCustomers.value.length === 1 ? uniqueCustomers.value[0] : '')
const netSettle = computed(() => {
  const wo = Number(header.value.writeOff) || 0
  return Math.round(Math.max(0, totalSettle.value - wo) * 100) / 100
})
const advanceCap = computed(() =>
  Math.round(Math.min(netSettle.value, Number(advanceBalance.value) || 0) * 100) / 100)
const cashNeeded = computed(() =>
  Math.round(Math.max(0, netSettle.value - (useAdvance.value ? Number(advanceAmount.value) || 0 : 0)) * 100) / 100)

watch(useAdvance, (v) => {
  if (v) {
    // 勾选即给默认值：最多可用预收（净额与余额取小），用户可改小
    advanceAmount.value = advanceCap.value
  }
})
// 净额/余额变化时夹紧预收录入，避免超界
watch([advanceCap], () => {
  if (useAdvance.value && Number(advanceAmount.value) > advanceCap.value) {
    advanceAmount.value = advanceCap.value
  }
})

// ==================== 资金账户 ====================
const accounts = ref([{ fundAccount: '', amount: '' }])
function addAccount() { accounts.value.push({ fundAccount: '', amount: '' }) }
function removeAccount(i) { accounts.value.splice(i, 1) }
/** 选完账户后若只有一行且未填金额，自动带入需现金结算额 */
function autoDistributeAccounts(i) {
  if (accounts.value.length === 1 && (!Number(accounts.value[0].amount))) {
    accounts.value[0].amount = cashNeeded.value
  }
}
function autoFillCash() {
  accounts.value = [{ fundAccount: accounts.value[0]?.fundAccount || '', amount: cashNeeded.value }]
}

async function loadAdvanceBalance() {
  advanceBalance.value = 0
  useAdvance.value = false
  advanceAmount.value = 0
  if (!singleCustomer.value) return
  try {
    const r = await post('/finance/customer-account/advance-balance', { customerName: singleCustomer.value })
    advanceBalance.value = Number(r.advanceBalance) || 0
  } catch (_) { advanceBalance.value = 0 }
}

watch(() => props.visible, async (v) => {
  if (!v) return
  header.value = { receiptDate: new Date().toISOString().slice(0, 10), summary: '', handler: '', writeOff: 0, writeOffExpenseType: '' }
  accounts.value = [{ fundAccount: '', amount: '' }]
  arLines.value = (props.arRows || []).map(r => {
    // unreceived_amount 已是「应收-已收」的未收净额，不能再减 receivedAmount（曾误减导致部分已收行只能收一半）
    const unpaidRaw = r.unreceivedAmount != null
      ? Number(r.unreceivedAmount)
      : Number(r.arAmount || 0) - Number(r.receivedAmount || 0)
    const unpaid = Math.round(unpaidRaw * 100) / 100
    return {
      arNo: r.arNo || '', customer: r.customer || '',
      sourceBill: r.sourceBill || '', arAmount: Number(r.arAmount || 0),
      receivedAmount: Number(r.receivedAmount || 0),
      unreceivedAmount: unpaid > 0 ? unpaid : 0,
      writeOff: 0, settleAmount: unpaid > 0 ? unpaid : 0,
    }
  })
  await loadAdvanceBalance()
  // 加载下拉
  try { const emp = await post('/base/master/employee/page', { pageNo: 1, pageSize: 200, filters: {} }); employeeList.value = (emp.records || []).filter(x => x.employeeName || x.name) } catch (_) { employeeList.value = [] }
  try { const fa = await post('/base/master/fund-account/page', { pageNo: 1, pageSize: 200, filters: {} }); fundAccounts.value = (fa.records || []).filter(x => x.fundAccountName || x.name) } catch (_) { fundAccounts.value = [] }
  try { const et = await post('/base/master/expense-type/page', { pageNo:1,pageSize:500,filters:{} }); const all = (et.records||[]).filter(x=>x.expenseTypeName||x.name); const parentCodes = new Set(all.map(x=>x.parentCode||x.parent_code).filter(Boolean)); expenseTypes.value = all.filter(x=>!parentCodes.has(x.expenseTypeCode||x.code)) } catch(_) { expenseTypes.value=[] }
})

// ==================== 金额计算 ====================
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
  const adv = useAdvance.value ? Math.round((Number(advanceAmount.value) || 0) * 100) / 100 : 0
  if (useAdvance.value) {
    if (uniqueCustomers.value.length > 1) { show('跨客户结算不能使用预收，请按客户分别结算'); return }
    if (adv <= 0) { show('请填写预收结算金额，或取消勾选「使用预收款」'); return }
    if (adv > advanceCap.value + 0.001) { show(`预收结算金额最多 ￥${advanceCap.value.toFixed(2)}`); return }
  }
  const filledAccts = accounts.value.filter(a => a.fundAccount && Number(a.amount) > 0)
  if (cashNeeded.value > 0 && filledAccts.length === 0) { show('请至少填写一个资金账户'); return }
  const acctTotal = Math.round(filledAccts.reduce((s, a) => s + Number(a.amount), 0) * 100) / 100
  if (Math.abs(acctTotal - cashNeeded.value) > 0.01) {
    show(`账户合计 ￥${acctTotal.toFixed(2)} 须等于净额扣减预收后的金额 ￥${cashNeeded.value.toFixed(2)}`)
    return
  }
  if (!confirm(`确认结算 ${arLines.value.length} 笔应收？\n本次结算净额 ￥${netSettle.value.toFixed(2)}`
    + (adv > 0 ? `\n其中预收结算 ￥${adv.toFixed(2)}，现金 ￥${cashNeeded.value.toFixed(2)}` : '')
    + (wo !== 0 ? `\n抹零 ￥${wo.toFixed(2)}` : ''))) return
  loading.value = true
  try {
    const res = await post('/finance/ar/settle', {
      receiptDate: header.value.receiptDate,
      summary: header.value.summary,
      handler: header.value.handler,
      accounts: filledAccts,
      writeOff: wo,
      writeOffExpenseType: header.value.writeOffExpenseType,
      useAdvanceAmount: adv,
      arList: arLines.value.filter(l => Number(l.settleAmount) > 0).map(l => ({
        arNo: l.arNo, settleAmount: l.settleAmount,
      })),
    })
    if (adv > 0 && res?.writeoffNos?.length) {
      show(`结算成功，预收已生成核销单：${res.writeoffNos.join('，')}`)
    }
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
        <div class="settle-subtitle">
          已选 {{ arLines.length }} 张单据，本次结算净额 ￥{{ netSettle.toFixed(2) }}，按客户分别生成收款单，退货负单参与净额抵扣
        </div>
        <!-- 头部 -->
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px 14px;margin-bottom:10px">
          <div class="fi"><label>收款日期</label><input type="date" v-model="header.receiptDate" /></div>
          <div class="fi"><label>经手人 <span class="req">*</span></label><select v-model="header.handler"><option value="">请选择</option><option v-for="e in employeeList" :key="e.employeeCode||e.code" :value="e.employeeName||e.name">{{ e.employeeName||e.name }}</option></select></div>
          <div class="fi"><label>收款摘要</label><input v-model="header.summary" placeholder="选填" /></div>
        </div>
        <div style="display:flex;gap:20px;align-items:center;padding:8px 12px;background:#f5f7fa;border-radius:4px;margin-bottom:10px;font-size:13px">
          <span>本次应收：<b style="color:#303133">{{ totalSettle.toFixed(2) }}</b></span>
          <span>抹零金额：<b :style="{color: (header.writeOff||0)!==0?'#e6a23c':'#909399'}">{{ (header.writeOff || 0).toFixed(2) }}</b></span>
          <span>本次实收：<b style="color:#409eff;font-size:15px">{{ actualAmount.toFixed(2) }}</b></span>
          <div class="fi" v-if="(header.writeOff||0) !== 0" style="flex:1"><label>抹零费用类型 <span class="req">*</span></label><select v-model="header.writeOffExpenseType"><option value="">请选择</option><option v-for="et in expenseTypes" :key="et.expenseTypeCode||et.code" :value="et.expenseTypeName||et.name">{{ et.expenseTypeName||et.name }}</option></select></div>
        </div>
        <div class="fi" style="margin-bottom:8px;max-width:240px"><label>抹零金额</label><input type="number" step="0.01" v-model.number="header.writeOff" /></div>

        <!-- 使用预收款（PRD-35 M3） -->
        <fieldset style="margin-bottom:10px">
          <legend>预收结算</legend>
          <div class="advance-panel">
            <div class="advance-balance">客户 ERP 预收余额：￥{{ (Number(advanceBalance) || 0).toFixed(2) }}</div>
            <label class="advance-check" :class="{ disabled: !singleCustomer }">
              <input type="checkbox" v-model="useAdvance" :disabled="!singleCustomer" />
              <b>使用预收款</b>
            </label>
            <span v-if="!singleCustomer" class="advance-hint warn">跨客户结算不能使用预收，请按客户分别结算</span>
            <div v-if="useAdvance" class="advance-input-row">
              <div class="fi"><label>预收结算金额</label>
                <input type="number" min="0" step="0.01" v-model.number="advanceAmount" />
              </div>
              <span class="advance-hint">最多 ￥{{ advanceCap.toFixed(2) }}，其余走资金账户</span>
            </div>
          </div>
        </fieldset>

        <!-- 资金账户 -->
        <fieldset style="margin-bottom:10px"><legend>收款账户（账户合计须 = ￥{{ cashNeeded.toFixed(2) }}）
          <button type="button" class="link link-btn" style="margin-left:8px;font-size:11px" @click="autoFillCash">自动填入</button>
          </legend>
          <table><thead><tr><th>账户</th><th style="width:110px;text-align:right">收款金额</th><th style="width:40px">操作</th></tr></thead>
            <tbody>
              <tr v-for="(a, i) in accounts" :key="i">
                <td><select v-model="a.fundAccount" style="width:100%;height:26px;font-size:12px" @change="autoDistributeAccounts(i)"><option value="">请选择</option><option v-for="fa in fundAccounts" :key="fa.fundAccountCode||fa.code" :value="fa.fundAccountName||fa.name">{{ fa.fundAccountCode||fa.code }} {{ fa.fundAccountName||fa.name }}</option></select></td>
                <td><input type="number" min="0" step="0.01" v-model.number="a.amount" style="width:100%;height:26px;text-align:right;font-size:12px" /></td>
                <td><button class="link link-btn danger-link" @click="removeAccount(i)" :disabled="accounts.length===1">删除</button></td>
              </tr>
            </tbody></table>
          <div style="margin-top:4px"><button class="btn" style="font-size:11px;height:22px;padding:0 8px" @click="addAccount">+ 加账户</button>
            <span class="account-sum" :class="{ mismatch: Math.abs(totalAccounts - cashNeeded) > 0.01 }">账户合计 ￥{{ totalAccounts.toFixed(2) }}</span>
          </div>
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
.settle-subtitle { font-size: 12px; color: #909399; margin-bottom: 8px; }
.advance-panel { background: #f5f7fa; border-radius: 4px; padding: 10px 12px; display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.advance-balance { font-size: 13px; color: #303133; }
.advance-check { display: flex; align-items: center; gap: 4px; font-size: 13px; cursor: pointer; }
.advance-check.disabled { cursor: not-allowed; color: #c0c4cc; }
.advance-input-row { display: flex; align-items: flex-end; gap: 10px; width: 100%; }
.advance-input-row .fi input { width: 160px; }
.advance-hint { font-size: 12px; color: #909399; }
.advance-hint.warn { color: #e6a23c; }
.account-sum { margin-left: 12px; font-size: 12px; color: #606266; }
.account-sum.mismatch { color: #f56c6c; font-weight: 600; }
</style>
