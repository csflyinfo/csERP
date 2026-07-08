<script setup>
/**
 * 收款单 / 付款单 新建 / 编辑抽屉。
 *
 * 替换原 FundBillDrawer：支持往来单位类型三选一、明细行、制单审核人、关联单号等完整字段。
 */
import { ref, watch, computed, onMounted } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' },
  moduleCode: { type: String, required: true },  // receiptPayment / paymentModule
  editData: { type: Object, default: null },
})
const emit = defineEmits(['close', 'save'])

const isReceipt = computed(() => props.moduleCode === 'receiptPayment')
const title = computed(() => {
  const prefix = isReceipt.value ? '收款单' : '付款单'
  return props.mode === 'edit' ? `编辑${prefix}` : `新建${prefix}`
})

// ==================== 下拉选项 ====================
const fundAccounts = ref([])          // 资金账户（从 base_fund_account 加载）
const customerList = ref([])          // 客户
const supplierList = ref([])          // 供应商
const counterpartyList = ref([])      // 往来单位
const employeeList = ref([])          // 人员信息（经手人下拉）
const loading = ref(false)
const formErrors = ref({})

// ==================== 主单 ====================
const header = ref({
  receiptId: '',
  receiptNo: '',
  receiptDate: new Date().toISOString().slice(0, 10),
  counterpartyType: isReceipt.value ? 'CUSTOMER' : 'SUPPLIER',     // 收款默认客户，付款默认供应商
  counterpartyCode: '',
  counterpartyName: '',
  handler: '',
  relatedBillNo: '',
  summary: '',
  status: 'PENDING',
  creatorName: '',
  createTime: '',
})

// ==================== 明细行 ====================
const details = ref([])
const supplierAccounts = ref([])   // 付款时供应商收款账户列表

function makeEmptyDetail() {
  return { fundAccount: '', amount: null, remark: '' }
}

/** 明细合计 */
const totalAmount = computed(() =>
  details.value.reduce((s, d) => s + (Number(d.amount) || 0), 0).toFixed(2))

// ==================== 往来单位类型切换 → 重新加载对应列表 ====================
const partnerOptions = computed(() => {
  if (header.value.counterpartyType === 'SUPPLIER') return supplierList.value
  if (header.value.counterpartyType === 'COUNTERPARTY') return counterpartyList.value
  return customerList.value  // 默认客户
})

function onTypeChange() {
  header.value.counterpartyCode = ''
  header.value.counterpartyName = ''
}

function onPartnerChange(code) {
  header.value.counterpartyCode = code
  const hit = partnerOptions.value.find(p =>
    (p.customerCode || p.supplierCode || p.counterpartyCode || p.code) === code)
  header.value.counterpartyName = hit
    ? (hit.customerName || hit.supplierName || hit.counterpartyName || hit.name || '')
    : ''
  // 付款单选供应商 → 加载供应商收款账户
  if (!isReceipt.value && code && header.value.counterpartyType === 'SUPPLIER') {
    loadSupplierAccounts(code)
  } else {
    supplierAccounts.value = []
  }
}

// ==================== 数据加载 ====================
async function loadBaseData() {
  try {
    const r = await post('/base/master/fund-account/page', { pageNo: 1, pageSize: 200, filters: {} })
    fundAccounts.value = (r.records || []).filter(x => String(x.status || '').toUpperCase() !== 'STOPPED')
  } catch (_) { fundAccounts.value = [] }
  try {
    const c = await post('/base/customer/page', { pageNo: 1, pageSize: 500, filters: {} })
    customerList.value = (c.records || []).filter(x => x.customerCode)
  } catch (_) { customerList.value = [] }
  try {
    const s = await post('/base/supplier/page', { pageNo: 1, pageSize: 500, filters: {} })
    supplierList.value = (s.records || []).filter(x => x.supplierCode)
  } catch (_) { supplierList.value = [] }
  try {
    const cp = await post('/base/master/counterparty/page', { pageNo: 1, pageSize: 500, filters: {} })
    counterpartyList.value = (cp.records || []).filter(x => x.counterpartyCode || x.code)
  } catch (_) { counterpartyList.value = [] }
  try {
    const emp = await post('/base/master/employee/page', { pageNo: 1, pageSize: 500, filters: {} })
    employeeList.value = (emp.records || []).filter(x => x.employeeName || x.name)
  } catch (_) { employeeList.value = [] }
}

/** 加载供应商收款账户（付款时用） */
async function loadSupplierAccounts(supplierCode) {
  try {
    const resp = await get('/base/supplier/detail?code=' + encodeURIComponent(supplierCode))
    supplierAccounts.value = (resp?.bankAccounts || []).filter(a => a.accountName)
  } catch (_) { supplierAccounts.value = [] }
}

// ==================== 编辑模式回填 ====================
async function loadExisting(adjustId) {
  try {
    const data = await post('/finance/receipt/detail', { receiptId: adjustId })
    if (!data) { alert('收款单不存在'); return }
    header.value = {
      receiptId: data.receiptId || '',
      receiptNo: data.receiptNo || '',
      receiptDate: (data.receiptDate || '').toString().slice(0, 10),
      counterpartyType: data.counterpartyType || 'CUSTOMER',
      counterpartyCode: data.counterpartyCode || '',
      counterpartyName: data.counterpartyName || '',
      handler: data.handler || '',
      relatedBillNo: data.relatedBillNo || '',
      summary: data.summary || '',
      status: data.status || 'PENDING',
      creatorName: data.creatorName || '',
      createTime: data.createTime || '',
    }
    details.value = (data.details || []).map(d => ({
      fundAccount: d.fundAccount || '',
      amount: d.amount || null,
      remark: d.remark || '',
    }))
    if (details.value.length === 0) details.value.push(makeEmptyDetail())
  } catch (e) {
    alert('加载收款单失败：' + (e.message || '未知错误'))
  }
}

// ==================== 明细行操作 ====================
function addDetailRow() { details.value.push(makeEmptyDetail()) }
function removeDetailRow(i) { details.value.splice(i, 1) }

// ==================== 校验 ====================
function validate() {
  const err = {}
  if (!header.value.counterpartyCode) err.partner = '请选择往来单位'
  const filled = details.value.filter(d => Number(d.amount) > 0)
  if (filled.length === 0) err.details = '请至少填写一条收款明细'
  else {
    const bad = filled.find(d => !d.fundAccount)
    if (bad) err.details = '请选择资金账户'
  }
  formErrors.value = err
  return Object.keys(err).length === 0
}

// ==================== 保存 ====================
async function save() {
  if (!validate()) { alert(Object.values(formErrors.value)[0]); return }
  loading.value = true
  try {
    const isEdit = props.mode === 'edit' && header.value.receiptId
    const api = isReceipt.value
      ? (isEdit ? '/finance/receipt/update' : '/finance/receipt/create')
      : (isEdit ? '/finance/payment/update' : '/finance/payment/create')
    const payload = {
      receiptId: header.value.receiptId || undefined,
      receiptDate: header.value.receiptDate,
      counterpartyType: header.value.counterpartyType,
      counterpartyCode: header.value.counterpartyCode,
      counterpartyName: header.value.counterpartyName,
      handler: header.value.handler,
      relatedBillNo: header.value.relatedBillNo,
      summary: header.value.summary,
      details: details.value.filter(d => Number(d.amount) > 0),
    }
    await post(api, payload)
    emit('save')
    close()
  } catch (e) {
    alert('保存失败：' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

function close() { emit('close') }

watch(() => props.visible, async (v) => {
  if (!v) return
  header.value = {
    receiptId: '', receiptNo: '', receiptDate: new Date().toISOString().slice(0, 10),
    counterpartyType: isReceipt.value ? 'CUSTOMER' : 'SUPPLIER', counterpartyCode: '', counterpartyName: '',
    handler: '', relatedBillNo: '', summary: '',
    status: 'PENDING', creatorName: '', createTime: '',
  }
  details.value = [makeEmptyDetail()]
  formErrors.value = {}
  await loadBaseData()
  if (props.mode === 'edit' && props.editData) {
    const raw = props.editData._raw || props.editData
    await loadExisting(raw.receiptId || raw.c0)
  }
})
</script>

<template>
  <div v-if="visible" class="bill-drawer-mask" @click.self="emit('close')">
    <div class="bill-drawer-box" style="width:min(860px,96vw)">
      <div class="bill-drawer-head">
        <b>{{ title }}</b>
        <span v-if="header.receiptNo" style="margin-left:10px;color:#606266;font-size:12px">{{ header.receiptNo }}</span>
        <span v-if="header.status" class="badge" :class="header.status === 'APPROVED' ? 'ok' : (header.status === 'PENDING' ? 'wait' : '')" style="margin-left:8px">
          {{ { PENDING: '待审核', APPROVED: '已审核', CANCELLED: '已作废' }[header.status] || header.status }}
        </span>
        <div style="flex:1"></div>
        <button class="btn" @click="close" :disabled="loading">关闭</button>
        <button v-if="header.status === 'PENDING'" class="btn primary" @click="save" :disabled="loading">保存</button>
      </div>

      <div class="bill-drawer-body" style="gap:8px">
        <!-- 主单信息 -->
        <div class="card" style="padding:10px 14px">
          <div class="form-grid">
            <!-- 往来单位类型：独占一行，单选组用小号 -->
            <div class="field field-full">
              <label>往来单位类型 <span class="req">*</span></label>
              <div class="radio-row-sm">
                <label class="radio-label-sm"><input type="radio" value="CUSTOMER" v-model="header.counterpartyType" @change="onTypeChange" />客户</label>
                <label class="radio-label-sm"><input type="radio" value="SUPPLIER" v-model="header.counterpartyType" @change="onTypeChange" />供应商</label>
                <label class="radio-label-sm"><input type="radio" value="COUNTERPARTY" v-model="header.counterpartyType" @change="onTypeChange" />往来单位</label>
              </div>
            </div>
            <!-- 往来单位：独占一行，紧跟类型下方 -->
            <div class="field field-full">
              <label>往来单位 <span class="req">*</span></label>
              <select :value="header.counterpartyCode" @change="onPartnerChange($event.target.value)">
                <option value="">请选择</option>
                <option v-for="p in partnerOptions" :key="p.customerCode || p.supplierCode || p.counterpartyCode || p.code"
                  :value="p.customerCode || p.supplierCode || p.counterpartyCode || p.code">
                  {{ p.customerCode || p.supplierCode || p.counterpartyCode || p.code }}
                  {{ p.customerName || p.supplierName || p.counterpartyName || p.name || '' }}
                </option>
              </select>
            </div>
            <div class="field">
              <label>收款日期 <span class="req">*</span></label>
              <input type="date" v-model="header.receiptDate" />
            </div>
            <div class="field">
              <label>经手人</label>
              <select v-model="header.handler">
                <option value="">请选择</option>
                <option v-for="e in employeeList" :key="e.employeeCode || e.code"
                  :value="e.employeeName || e.name">{{ e.employeeName || e.name }}</option>
              </select>
            </div>
            <div class="field">
              <label>关联单号</label>
              <input v-model="header.relatedBillNo" placeholder="选填" />
            </div>
            <div class="field field-full">
              <label>摘要</label>
              <input v-model="header.summary" placeholder="选填" />
            </div>
          </div>
        </div>

        <!-- 收款明细 -->
        <div class="card" style="padding:10px 14px;flex:1;display:flex;flex-direction:column;min-height:180px">
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
            <b style="font-size:13px">收款明细</b>
            <span v-if="formErrors.details" style="color:#f56c6c;font-size:12px">{{ formErrors.details }}</span>
            <div style="flex:1"></div>
            <span style="font-size:12px;color:#606266">合计 ￥{{ totalAmount }}</span>
            <button class="btn" style="height:24px;font-size:11px;padding:0 8px" @click="addDetailRow">+ 添加行</button>
          </div>
          <div class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th>资金账户 <span class="req">*</span></th>
                  <th style="width:110px">{{ isReceipt ? '收款金额' : '付款金额' }}</th>
                  <th v-if="!isReceipt" style="min-width:120px">供应商账户</th>
                  <th>备注</th>
                  <th style="width:50px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="details.length === 0">
                  <td :colspan="isReceipt ? 5 : 6" style="text-align:center;color:#909399;padding:20px">暂无明细，点击右上角「+ 添加行」</td>
                </tr>
                <tr v-for="(d, i) in details" :key="i">
                  <td>{{ i + 1 }}</td>
                  <td>
                    <select v-model="d.fundAccount" style="width:100%;height:24px;font-size:12px">
                      <option value="">请选择</option>
                      <option v-for="fa in fundAccounts" :key="fa.fundAccountCode || fa.code"
                        :value="fa.fundAccountName || fa.name">
                        {{ fa.fundAccountCode || fa.code }} {{ fa.fundAccountName || fa.name }}
                      </option>
                    </select>
                  </td>
                  <td><input type="number" min="0" step="0.01" v-model.number="d.amount" style="width:100%;height:24px;text-align:right;font-size:12px" /></td>
                  <td v-if="!isReceipt">
                    <select v-model="d.supplierAccount" style="width:100%;height:24px;font-size:12px">
                      <option value="">请选择</option>
                      <option v-for="sa in supplierAccounts" :key="sa.id" :value="sa.accountName + ' ' + (sa.bankName||'') + ' ' + (sa.bankAccount||'')">
                        {{ sa.accountName }} {{ sa.bankName }} {{ sa.bankAccount }}
                      </option>
                    </select>
                  </td>
                  <td><input v-model="d.remark" style="width:100%;height:24px;font-size:12px" placeholder="选填" /></td>
                  <td><button class="link link-btn danger-link" @click="removeDetailRow(i)">删除</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ===== 抽屉布局（与 BillDrawer 同款：无遮罩 + 右侧定位 box） ===== */
.bill-drawer-mask {
  position: fixed;
  top: 48px;
  right: 0; bottom: 0;
  left: 299px;
  z-index: 900;
  display: flex;
  pointer-events: none;
}
.bill-drawer-box {
  flex: 1;
  background: #fff;
  display: flex; flex-direction: column;
  min-width: 0;
  border-left: 1px solid #e5e7eb;
  box-shadow: -6px 0 24px rgba(15,46,88,.12);
  pointer-events: auto;
}
.bill-drawer-head {
  display: flex; align-items: center; gap: 10px;
  height: 46px; padding: 0 16px;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}
.bill-drawer-head b { font-size: 15px; }
.bill-drawer-body {
  flex: 1; overflow: auto;
  padding: 12px 16px;
  display: flex; flex-direction: column; gap: 12px;
  background: #f5f7fa;
}

/* ===== 卡片 ===== */
.card {
  background: #fff; border: 1px solid #e5e7eb; border-radius: 6px;
}

/* ===== 主单表单 (2 列网格) ===== */
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 20px;
}
.form-grid .field {
  display: grid;
  grid-template-columns: 88px 1fr;
  align-items: center; gap: 6px;
}
.form-grid .field label { font-size: 12px; color: #606266; text-align: right; font-weight: 600; white-space: nowrap; }
.form-grid .field input,
.form-grid .field select {
  height: 30px; padding: 0 8px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px;
  background: #fff;
}
.field-full { grid-column: 1 / -1; }

/* ===== 往来单位类型单选（小号舒适型） ===== */
.radio-row-sm { display: flex; gap: 18px; align-items: center; height: 30px; }
.radio-label-sm { display: inline-flex; align-items: center; gap: 3px; font-size: 12px; color: #606266; cursor: pointer; }
.radio-label-sm input[type="radio"] { margin: 0; width: 13px; height: 13px; cursor: pointer; accent-color: #409eff; }

/* ===== 明细表 ===== */
.detail-scroll { flex: 1; overflow: auto; min-height: 0; }
.detail-scroll table { width: 100%; border-collapse: collapse; font-size: 12px; }
.detail-scroll th { background: #f5f7fa; padding: 7px 8px; text-align: left; font-weight: 600; color: #303133; border-bottom: 1px solid #e5e7eb; position: sticky; top: 0; z-index: 1; }
.detail-scroll td { padding: 5px 8px; border-bottom: 1px solid #f0f0f0; }
.detail-scroll td input,
.detail-scroll td select { height: 26px; border: 1px solid #dcdfe6; border-radius: 3px; font-size: 12px; }
.detail-scroll td input:focus,
.detail-scroll td select:focus { border-color: #409eff; outline: none; }

/* ===== 通用 ===== */
.req { color: #f56c6c; margin-left: 1px; }
</style>
