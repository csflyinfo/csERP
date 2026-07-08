<script setup>
/**
 * 客户对账单 新建/编辑抽屉
 */
import { ref, watch, computed } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' },
  moduleCode: { type: String, default: 'customerStatement' },
  editData: { type: Object, default: null },
})
const emit = defineEmits(['close', 'save'])
const loading = ref(false); const formErrors = ref({})

const isSupplier = computed(() => props.moduleCode === 'supplierStatement')
const partnerList = ref([]); const employeeList = ref([])

// ===== 主单 =====
const header = ref({
  statementId: '', statementNo: '',
  customerCode: '', customerName: '', salesman: '',
  statementDate: new Date().toISOString().slice(0, 10),
  expectedPayDate: new Date(Date.now() + 7*86400000).toISOString().slice(0, 10),
  contactName: '', contactPhone: '',
  remark: '', status: 'PENDING',
})

function onPartnerChange(code) {
  header.value.customerCode = code
  const hit = partnerList.value.find(c => (c.customerCode || c.supplierCode) === code)
  header.value.customerName = hit ? (hit.customerName || hit.supplierName || '') : ''
  header.value.salesman = hit ? (hit.salesman || hit.buyer || '') : ''
  header.value.contactName = hit?.contactName || ''
  header.value.contactPhone = hit?.mobile || hit?.phone || ''
}

// ===== 明细 =====
const details = ref([])
const totalAmount = computed(() => details.value.reduce((s, d) => s + (Number(d.reconcileAmount) || 0), 0).toFixed(2))

function removeDetail(i) { details.value.splice(i, 1) }

// ===== 添加单据弹窗 =====
const showBillPicker = ref(false)
const billPickerLoading = ref(false)
const availableBills = ref([])
const pickedBillKeys = ref(new Set())
const billPickerDateFrom = ref('')
const billPickerDateTo = ref('')

async function openBillPicker() {
  if (!header.value.customerName) { alert(isSupplier.value ? '请先选择供应商' : '请先选择客户'); return }
  pickedBillKeys.value = new Set()
  availableBills.value = []
  showBillPicker.value = true
  billPickerLoading.value = true
  const prefix = isSupplier.value ? 'supplier' : 'customer'
  const reqBody = {
    customerName: header.value.customerName,
    supplierName: header.value.customerName,
    dateFrom: billPickerDateFrom.value, dateTo: billPickerDateTo.value,
  }
  try {
    const data = await post(`/finance/${prefix}-statement/available-bills`, reqBody)
    availableBills.value = (data || []).map(b => ({ ...b, _key: b.sourceBill || b.arNo || b.apNo }))
  } catch (e) { availableBills.value = []; alert('加载待结单据失败：' + (e.message || '未知错误') + '\n客户：' + header.value.customerName) }
  finally { billPickerLoading.value = false }
}

const pickedTotal = computed(() => {
  return availableBills.value
    .filter(b => pickedBillKeys.value.has(b._key))
    .reduce((s, b) => s + (Number(b.unsettledAmount || b.unreceivedAmount || b.unpaidAmount) || 0), 0)
})

function toggleBill(key) {
  if (pickedBillKeys.value.has(key)) pickedBillKeys.value.delete(key)
  else pickedBillKeys.value.add(key)
}

/** 将勾选单据写入明细，并从可选列表移除；返回写入条数 */
function addPickedBills() {
  const picked = availableBills.value.filter(b => pickedBillKeys.value.has(b._key))
  if (picked.length === 0) return 0
  const existing = new Set(details.value.map(d => d.sourceBillNo))
  let added = 0
  picked.forEach(b => {
    const key = b.sourceBill || b.arNo || b.apNo
    if (existing.has(key)) return
    details.value.push({
      sourceBillNo: key,
      sourceBillDate: (b.dueDate || b.billDate || '').toString().slice(0, 10),
      sourceBillType: b.billType || '销售发货',
      billAmount: Number(b.billAmount || b.arAmount || b.apAmount || 0),
      reconcileAmount: Number(b.unsettledAmount || b.unreceivedAmount || b.unpaidAmount || 0),
      paidAmount: 0,
      unpaidAmount: Number(b.unsettledAmount || b.unreceivedAmount || b.unpaidAmount || 0),
      billRemark: '',
    })
    existing.add(key)
    added++
  })
  // 从可选列表中移除已添加的
  availableBills.value = availableBills.value.filter(b => !pickedBillKeys.value.has(b._key))
  pickedBillKeys.value = new Set()
  return added
}

/** 选择添加：写入明细，不关闭窗口 */
function selectAddBills() { addPickedBills() }

/** 确定：写入明细，关闭窗口 */
function confirmAddBills() { addPickedBills(); showBillPicker.value = false }

// ===== 数据加载 =====
async function loadBase() {
  const api = isSupplier.value ? '/base/supplier/page' : '/base/customer/page'
  try { const c = await post(api, { pageNo:1, pageSize:500, filters:{} }); partnerList.value = (c.records||[]).filter(x=>x.customerCode||x.supplierCode) } catch(_){partnerList.value=[]}
  try { const e = await post('/base/master/employee/page', { pageNo:1, pageSize:200, filters:{} }); employeeList.value = (e.records||[]).filter(x=>x.employeeName||x.name) } catch(_){employeeList.value=[]}
}

// ===== 校验+保存 =====
function validate() {
  const err = {}
  if (!header.value.customerCode) err.customer = '请选择客户'
  if (details.value.length === 0) err.details = '请添加对账明细'
  formErrors.value = err; return Object.keys(err).length === 0
}

async function save() {
  if (!validate()) { alert(Object.values(formErrors.value)[0]); return }
  loading.value = true
  try {
    const isEdit = props.mode === 'edit' && header.value.statementId
    const isSupplier = props.moduleCode === 'supplierStatement'
    const prefix = isSupplier ? 'supplier' : 'customer'
    const api = isEdit ? `/finance/${prefix}-statement/update` : `/finance/${prefix}-statement/create`
    await post(api, {
      statementId: header.value.statementId || undefined,
      customerCode: header.value.customerCode, customerName: header.value.customerName,
      salesman: header.value.salesman,
      statementDate: header.value.statementDate, expectedPayDate: header.value.expectedPayDate,
      contactName: header.value.contactName, contactPhone: header.value.contactPhone,
      remark: header.value.remark,
      details: details.value,
    })
    emit('save'); close()
  } catch (e) { alert('保存失败：'+ (e.message||'未知错误')) } finally { loading.value = false }
}

function close() { emit('close') }

watch(() => props.visible, async (v) => {
  if (!v) return
  header.value = { statementId:'', statementNo:'', customerCode:'', customerName:'', salesman:'', statementDate: new Date().toISOString().slice(0,10), expectedPayDate: new Date(Date.now()+7*86400000).toISOString().slice(0,10), contactName:'', contactPhone:'', remark:'', status:'PENDING' }
  details.value = []; formErrors.value = {}
  await loadBase()
  if (props.mode === 'edit' && props.editData) {
    const raw = props.editData._raw || props.editData
    try {
      const prefix = isSupplier.value ? 'supplier' : 'customer'
      const data = await post(`/finance/${prefix}-statement/detail`, { statementId: raw.statementId || raw.c0 })
      if (data) {
        header.value = {
          statementId: data.statementId||'', statementNo: data.statementNo||'',
          customerCode: data.customerCode||'', customerName: data.customerName||'',
          salesman: data.salesman||'', statementDate: (data.statementDate||'').toString().slice(0,10),
          expectedPayDate: (data.expectedPayDate||'').toString().slice(0,10),
          contactName: data.contactName||'', contactPhone: data.contactPhone||'',
          remark: data.remark||'', status: data.status||'PENDING',
        }
        details.value = (data.details||[]).map(d => ({
          sourceBillNo: d.sourceBillNo||'', sourceBillDate: (d.sourceBillDate||'').toString().slice(0,10),
          sourceBillType: d.sourceBillType||'', billAmount: Number(d.billAmount||0),
          reconcileAmount: Number(d.reconcileAmount||0), paidAmount: Number(d.paidAmount||0),
          unpaidAmount: Number(d.unpaidAmount||0), billRemark: d.billRemark||'',
        }))
      }
    } catch (_) {}
  }
})
</script>

<template>
  <div v-if="visible" class="bill-drawer-mask" @click.self="emit('close')">
    <div class="bill-drawer-box" style="width:min(860px,96vw)">
      <div class="bill-drawer-head">
        <b>{{ props.mode === 'edit' ? '编辑' : '新建' }}{{ props.moduleCode === 'supplierStatement' ? '供应商' : '客户' }}对账单</b>
        <span v-if="header.statementNo" style="margin-left:10px;color:#606266;font-size:12px">{{ header.statementNo }}</span>
        <span v-if="header.status" class="badge" :class="header.status==='APPROVED'?'ok':'wait'" style="margin-left:8px">{{ {PENDING:'待审核',APPROVED:'已审核'}[header.status]||header.status }}</span>
        <div style="flex:1"></div>
        <button class="btn" @click="close">关闭</button>
        <button v-if="header.status==='PENDING'" class="btn primary" @click="save" :disabled="loading">保存</button>
      </div>
      <div class="bill-drawer-body">
        <div class="card" style="padding:10px 14px">
          <div class="form-grid">
            <div class="field field-full">
              <label>{{ isSupplier ? '供应商' : '客户' }} <span class="req">*</span></label>
              <select :value="header.customerCode" @change="onPartnerChange($event.target.value)" :disabled="header.status!=='PENDING'">
                <option value="">请选择</option>
                <option v-for="c in partnerList" :key="c.customerCode || c.supplierCode" :value="c.customerCode || c.supplierCode">{{ c.customerCode || c.supplierCode }} {{ c.customerName || c.supplierName }}</option>
              </select>
            </div>
            <div class="field"><label>对账日期 <span class="req">*</span></label><input type="date" v-model="header.statementDate" :disabled="header.status!=='PENDING'" /></div>
            <div class="field"><label>预计回款日</label><input type="date" v-model="header.expectedPayDate" :disabled="header.status!=='PENDING'" /></div>
            <div class="field"><label>联系人</label><input v-model="header.contactName" :disabled="header.status!=='PENDING'" /></div>
            <div class="field"><label>联系电话</label><input v-model="header.contactPhone" :disabled="header.status!=='PENDING'" /></div>
            <div class="field field-full"><label>备注</label><input v-model="header.remark" :disabled="header.status!=='PENDING'" placeholder="最多200字" /></div>
          </div>
        </div>
        <div class="card" style="padding:10px 14px;flex:1;display:flex;flex-direction:column;min-height:180px">
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
            <b style="font-size:13px">对账明细</b><span v-if="formErrors.details" style="color:#f56c6c;font-size:12px">{{ formErrors.details }}</span>
            <div style="flex:1"></div>
            <span style="font-size:12px;color:#606266">合计 ￥{{ totalAmount }}</span>
            <button v-if="header.status==='PENDING'" class="btn primary" style="height:24px;font-size:11px;padding:0 8px" @click="openBillPicker">添加单据</button>
          </div>
          <div class="detail-scroll">
            <table><thead><tr><th style="width:36px">#</th><th>单据号</th><th>单据日期</th><th>单据类型</th><th style="text-align:right">对账金额</th><th style="text-align:right">已收款</th><th style="text-align:right">未收款</th><th>单据备注</th><th v-if="header.status==='PENDING'" style="width:40px">操作</th></tr></thead>
            <tbody>
              <tr v-if="details.length===0"><td :colspan="header.status==='PENDING'?9:8" style="text-align:center;color:#909399;padding:20px">暂无明细，点击右上角「添加单据」</td></tr>
              <tr v-for="(d,i) in details" :key="i">
                <td>{{ i+1 }}</td><td>{{ d.sourceBillNo }}</td><td>{{ d.sourceBillDate }}</td><td>{{ d.sourceBillType }}</td>
                <td style="text-align:right">{{ Number(d.reconcileAmount||0).toFixed(2) }}</td>
                <td style="text-align:right">{{ Number(d.paidAmount||0).toFixed(2) }}</td>
                <td style="text-align:right">{{ Number(d.unpaidAmount||0).toFixed(2) }}</td>
                <td>{{ d.billRemark }}</td>
                <td v-if="header.status==='PENDING'"><button class="link link-btn danger-link" @click="removeDetail(i)">删除</button></td>
              </tr>
            </tbody></table>
          </div>
        </div>
      </div>

      <!-- 添加单据弹窗（Teleport 到 body 避免被抽屉 z-index 压制） -->
      <Teleport to="body">
      <div v-if="showBillPicker" class="modal-lite" style="z-index:950" @click.self="showBillPicker=false">
        <div class="modal-lite-box" style="width:min(750px,95vw);max-height:80vh">
          <div class="modal-lite-head"><b>添加单据 — {{ header.customerName }}</b><div class="actions"><button class="btn" @click="showBillPicker=false">取消</button><button class="btn" @click="selectAddBills" :disabled="pickedBillKeys.size===0">选择添加</button><button class="btn primary" @click="confirmAddBills" :disabled="pickedBillKeys.size===0">确定</button></div></div>
          <div class="modal-lite-body">
            <div style="display:flex;gap:8px;margin-bottom:8px"><input type="date" v-model="billPickerDateFrom" /> ~ <input type="date" v-model="billPickerDateTo" /><button class="btn" style="height:28px;font-size:12px" @click="openBillPicker">查询</button><span style="margin-left:auto;font-size:13px">选择对账金额：<b style="color:#409eff">{{ pickedTotal.toFixed(2) }}</b></span></div>
            <div v-if="billPickerLoading" style="text-align:center;padding:30px;color:#909399">加载中...</div>
            <div v-else>
              <table><thead><tr><th style="width:30px"></th><th>{{ isSupplier ? '应付单据' : '应收单据' }}</th><th>业务单据</th><th>单据日期</th><th>单据类型</th><th style="text-align:right">{{ isSupplier ? '应付金额' : '应收金额' }}</th><th style="text-align:right">{{ isSupplier ? '已付金额' : '已收金额' }}</th><th style="text-align:right">{{ isSupplier ? '未付金额' : '未收金额' }}</th></tr></thead>
            <tbody><tr v-if="availableBills.length===0"><td colspan="8" style="text-align:center;color:#909399;padding:20px">该往来单位暂无未对账单据</td></tr>
            <tr v-for="b in availableBills" :key="b._key" @click="toggleBill(b._key)" style="cursor:pointer">
              <td><input type="checkbox" :checked="pickedBillKeys.has(b._key)" @click.stop="toggleBill(b._key)" /></td>
              <td>{{ isSupplier ? (b.apNo || '') : (b.arNo || '') }}</td>
              <td>{{ b.sourceBill || b.arNo || b.apNo }}</td>
              <td>{{ (b.dueDate||'').toString().slice(0,10) }}</td>
              <td>{{ b.billType }}</td>
              <td style="text-align:right">{{ Number(b.billAmount||b.arAmount||b.apAmount||0).toFixed(2) }}</td>
              <td style="text-align:right">{{ Number(isSupplier ? (b.paidAmount||0) : (b.receivedAmount||0)).toFixed(2) }}</td>
              <td style="text-align:right;color:#409eff;font-weight:600">{{ Number(b.unsettledAmount||b.unreceivedAmount||b.unpaidAmount||0).toFixed(2) }}</td>
            </tr></tbody></table>
            </div>
          </div>
        </div>
      </div>
      </Teleport>
    </div>
  </div>
</template>

<style scoped>
.card { background:#fff; border:1px solid #e5e7eb; border-radius:6px; }
.form-grid { display:grid; grid-template-columns:1fr 1fr; gap:8px 16px; }
.form-grid .field { display:grid; grid-template-columns:78px 1fr; align-items:center; gap:4px; }
.form-grid .field label { font-size:12px; color:#606266; text-align:right; font-weight:600; white-space:nowrap; }
.form-grid .field input,.form-grid .field select { height:28px; padding:0 6px; border:1px solid #dcdfe6; border-radius:4px; font-size:12px; }
.field-full { grid-column:1/-1; }
.req { color:#f56c6c; }
.detail-scroll { flex:1; overflow:auto; min-height:0; }
.detail-scroll table { width:100%; border-collapse:collapse; font-size:12px; }
.detail-scroll th { background:#f5f7fa; padding:6px 8px; text-align:left; font-weight:600; border-bottom:1px solid #e5e7eb; }
.detail-scroll td { padding:4px 8px; border-bottom:1px solid #f0f0f0; }
.bill-drawer-mask { position:fixed; top:48px; right:0; bottom:0; left:299px; z-index:900; display:flex; pointer-events:none; }
.bill-drawer-box { flex:1; background:#fff; display:flex; flex-direction:column; min-width:0; border-left:1px solid #e5e7eb; box-shadow:-6px 0 24px rgba(15,46,88,.12); pointer-events:auto; }
.bill-drawer-head { display:flex; align-items:center; gap:10px; height:46px; padding:0 16px; border-bottom:1px solid #e5e7eb; flex-shrink:0; }
.bill-drawer-head b { font-size:15px; }
.bill-drawer-body { flex:1; overflow:auto; padding:12px 16px; display:flex; flex-direction:column; gap:12px; background:#f5f7fa; }
</style>
