<script setup>
/**
 * 费用单新建/编辑抽屉（与收款单同款布局：表单在上、明细表在下）
 */
import { ref, watch, computed } from 'vue'
import { post } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' },
  editData: { type: Object, default: null },
})
const emit = defineEmits(['close', 'save'])

const loading = ref(false)
const formErrors = ref({})

// ==================== 下拉选项 ====================
const customerList = ref([])
const supplierList = ref([])
const counterpartyList = ref([])
const employeeList = ref([])
const expenseTypes = ref([])
const goodsList = ref([])
const departmentList = ref([])   // 部门列表
const fundAccountList = ref([])  // 资金账户列表

// ==================== 主单 ====================
const header = ref({
  expenseId: '', expenseNo: '',
  expenseDate: new Date().toISOString().slice(0, 10),
  direction: 'OUT',                        // IN=收入 OUT=支出，默认支出
  counterpartyType: 'CUSTOMER',
  counterpartyCode: '', counterpartyName: '',
  handler: '', department: '',
  relatedBillNo: '', externalVoucherNo: '', fundAccount: '',
  remark: '', status: 'PENDING',
})

const partnerOptions = computed(() => {
  if (header.value.counterpartyType === 'SUPPLIER') return supplierList.value
  if (header.value.counterpartyType === 'COUNTERPARTY') return counterpartyList.value
  return customerList.value
})

function onTypeChange() { header.value.counterpartyCode = ''; header.value.counterpartyName = '' }

function onPartnerChange(code) {
  header.value.counterpartyCode = code
  const hit = partnerOptions.value.find(p =>
    (p.customerCode || p.supplierCode || p.counterpartyCode || p.code) === code)
  header.value.counterpartyName = hit ? (hit.customerName || hit.supplierName || hit.counterpartyName || hit.name || '') : ''
}

// ==================== 费用明细 ====================
const details = ref([])
function makeEmptyDetail() { return { expenseType: '', goodsCode: '', goodsName: '', brandName: '', qty: 1, price: null, amount: 0, taxRate: 13, taxAmount: 0, excludingTaxAmount: 0, remark: '' } }

/** 数量或单价变化 → 自动算金额；金额变化 → 有数量则反算单价 */
function recalcDetail(d) {
  const qty = Number(d.qty) || 0
  const price = Number(d.price) || 0
  d.amount = parseFloat((qty * price).toFixed(2))
  const rate = (Number(d.taxRate) || 0) / 100
  d.taxAmount = parseFloat((d.amount * rate).toFixed(2))
  d.excludingTaxAmount = parseFloat((d.amount - d.taxAmount).toFixed(2))
}
function onDetailQtyChange(d) { recalcDetail(d) }
function onDetailPriceChange(d, rawVal) {
  let v = String(rawVal || '').replace(/[^\d.]/g, '')
  const dot = v.indexOf('.')
  if (dot >= 0) v = v.slice(0, dot + 1) + v.slice(dot + 1).replace(/\./g, '').slice(0, 4)
  d.price = v === '' || v === '.' ? null : Number(v)
  recalcDetail(d)
}
function onDetailAmountChange(d, rawVal) {
  let v = String(rawVal || '').replace(/[^\d.]/g, '')
  const dot = v.indexOf('.')
  if (dot >= 0) v = v.slice(0, dot + 1) + v.slice(dot + 1).replace(/\./g, '').slice(0, 2)
  d.amount = v === '' || v === '.' ? 0 : Number(v)
  const qty = Number(d.qty) || 0
  if (qty > 0 && d.amount > 0) d.price = parseFloat((d.amount / qty).toFixed(4))
  const rate = (Number(d.taxRate) || 0) / 100
  d.taxAmount = parseFloat((d.amount * rate).toFixed(2))
  d.excludingTaxAmount = parseFloat((d.amount - d.taxAmount).toFixed(2))
}
function onDetailTaxRateChange(d) {
  recalcDetail(d)
}

const totalAmount = computed(() =>
  details.value.reduce((s, d) => s + (Number(d.amount) || 0), 0).toFixed(2))
const totalTax = computed(() =>
  details.value.reduce((s, d) => s + (Number(d.taxAmount) || 0), 0).toFixed(2))

function addDetailRow() { details.value.push(makeEmptyDetail()) }
function removeDetailRow(i) { details.value.splice(i, 1) }

/** 选中商品 → 自动带出品牌 */
function onDetailGoodsChange(detail, code) {
  detail.goodsCode = code
  const g = goodsList.value.find(x => x.goodsCode === code)
  detail.goodsName = g?.goodsName || ''
  detail.brandName = g?.brandName || ''
}

// ==================== 数据加载 ====================
async function loadBaseData() {
  try { const c = await post('/base/customer/page', { pageNo: 1, pageSize: 500, filters: {} }); customerList.value = (c.records || []).filter(x => x.customerCode) } catch (_) { customerList.value = [] }
  try { const s = await post('/base/supplier/page', { pageNo: 1, pageSize: 500, filters: {} }); supplierList.value = (s.records || []).filter(x => x.supplierCode) } catch (_) { supplierList.value = [] }
  try { const cp = await post('/base/master/counterparty/page', { pageNo: 1, pageSize: 500, filters: {} }); counterpartyList.value = (cp.records || []).filter(x => x.counterpartyCode || x.code) } catch (_) { counterpartyList.value = [] }
  try { const emp = await post('/base/master/employee/page', { pageNo: 1, pageSize: 500, filters: {} }); employeeList.value = (emp.records || []).filter(x => x.employeeName || x.name) } catch (_) { employeeList.value = [] }
  try {
    const et = await post('/base/master/expense-type/page', { pageNo: 1, pageSize: 500, filters: {} })
    const all = (et.records || []).filter(x => x.expenseTypeName || x.name)
    // 只保留末级：其 code 不能是其他类型的 parent_code
    const parentCodes = new Set(all.map(x => x.parentCode || x.parent_code).filter(Boolean))
    expenseTypes.value = all.filter(x => !parentCodes.has(x.expenseTypeCode || x.code))
  } catch (_) { expenseTypes.value = [] }
  try { const g = await post('/base/goods/page', { pageNo: 1, pageSize: 2000, filters: {} }); goodsList.value = (g.records || []).filter(x => String(x.status || '').toUpperCase() !== 'STOPPED') } catch (_) { goodsList.value = [] }
  try { const d = await post('/base/master/department/page', { pageNo: 1, pageSize: 200, filters: {} }); departmentList.value = (d.records || []).filter(x => x.departmentName || x.name) } catch (_) { departmentList.value = [] }
  try { const fa = await post('/base/master/fund-account/page', { pageNo: 1, pageSize: 200, filters: {} }); fundAccountList.value = (fa.records || []).filter(x => x.fundAccountName || x.name) } catch (_) { fundAccountList.value = [] }
}

// ==================== 校验 + 保存 ====================
function validate() {
  const err = {}
  if (!header.value.counterpartyCode) err.partner = '请选择往来单位'
  if (!header.value.expenseDate) err.date = '请选择费用日期'
  if (!header.value.handler) err.handler = '请选择经手人'
  if (details.value.filter(d => Number(d.amount) > 0).length === 0) err.details = '请至少填写一条费用明细'
  else { const bad = details.value.find(d => Number(d.amount) > 0 && !d.expenseType); if (bad) err.details = '请选择费用类型' }
  formErrors.value = err; return Object.keys(err).length === 0
}

async function save() {
  if (!validate()) { alert(Object.values(formErrors.value)[0]); return }
  loading.value = true
  try {
    const isEdit = props.mode === 'edit' && header.value.expenseId
    const api = isEdit ? '/finance/expense/update' : '/finance/expense/create'
    await post(api, {
      expenseId: header.value.expenseId || undefined,
      expenseDate: header.value.expenseDate,
      direction: header.value.direction,
      counterpartyType: header.value.counterpartyType,
      counterpartyCode: header.value.counterpartyCode,
      counterpartyName: header.value.counterpartyName,
      handler: header.value.handler, department: header.value.department,
      relatedBillNo: header.value.relatedBillNo,
      externalVoucherNo: header.value.externalVoucherNo,
      fundAccount: header.value.fundAccount,
      remark: header.value.remark,
      details: details.value.filter(d => Number(d.amount) > 0),
    })
    emit('save'); close()
  } catch (e) { alert('保存失败：' + (e.message || '未知错误')) } finally { loading.value = false }
}

function close() { emit('close') }

// ==================== 编辑回填 ====================
async function loadExisting(expenseId) {
  try {
    const data = await post('/finance/expense/detail', { expenseId })
    if (!data) { alert('费用单不存在'); return }
    header.value = {
      expenseId: data.expenseId || '', expenseNo: data.expenseNo || '',
      expenseDate: (data.expenseDate || '').toString().slice(0, 10),
      direction: data.direction || 'OUT',
      counterpartyType: data.counterpartyType || 'CUSTOMER',
      counterpartyCode: data.counterpartyCode || '', counterpartyName: data.counterpartyName || '',
      handler: data.handler || '', department: data.department || '',
      relatedBillNo: data.relatedBillNo || '', externalVoucherNo: data.externalVoucherNo || '',
      fundAccount: data.fundAccount || '', remark: data.remark || '', status: data.status || 'PENDING',
    }
    details.value = (data.details || []).map(d => ({
      expenseType: d.expenseType || '', goodsCode: d.goodsCode || '', goodsName: d.goodsName || '',
      brandName: d.brandName || '', qty: d.qty || 1, price: d.price || null, amount: d.amount || 0, remark: d.remark || '',
    }))
    if (details.value.length === 0) details.value.push(makeEmptyDetail())
  } catch (e) { alert('加载失败：' + (e.message || '未知错误')) }
}

watch(() => props.visible, async (v) => {
  if (!v) return
  header.value = { expenseId: '', expenseNo: '', expenseDate: new Date().toISOString().slice(0, 10), direction: 'OUT', counterpartyType: 'CUSTOMER', counterpartyCode: '', counterpartyName: '', handler: '', department: '', relatedBillNo: '', externalVoucherNo: '', fundAccount: '', remark: '', status: 'PENDING' }
  details.value = [makeEmptyDetail()]; formErrors.value = {}
  await loadBaseData()
  if (props.mode === 'edit' && props.editData) {
    const raw = props.editData._raw || props.editData
    await loadExisting(raw.expenseId || raw.c0)
  }
})
</script>

<template>
  <div v-if="visible" class="bill-drawer-mask" @click.self="emit('close')">
    <div class="bill-drawer-box" style="width:min(900px,96vw)">
      <div class="bill-drawer-head">
        <b>{{ props.mode === 'edit' ? '编辑' : '新建' }}费用单</b>
        <span v-if="header.expenseNo" style="margin-left:10px;color:#606266;font-size:12px">{{ header.expenseNo }}</span>
        <span v-if="header.status" class="badge" :class="header.status === 'APPROVED' ? 'ok' : 'wait'" style="margin-left:8px">
          {{ { PENDING: '待审核', APPROVED: '已审核' }[header.status] || header.status }}
        </span>
        <div style="flex:1"></div>
        <button class="btn" @click="close" :disabled="loading">关闭</button>
        <button v-if="header.status === 'PENDING'" class="btn primary" @click="save" :disabled="loading">保存</button>
      </div>

      <div class="bill-drawer-body">
        <!-- 主单信息 -->
        <div class="card" style="padding:10px 14px">
          <div class="form-grid">
            <div class="field field-full">
              <label>往来单位类型 <span class="req">*</span></label>
              <div class="radio-row-sm">
                <label class="radio-label-sm"><input type="radio" value="CUSTOMER" v-model="header.counterpartyType" @change="onTypeChange" />客户</label>
                <label class="radio-label-sm"><input type="radio" value="SUPPLIER" v-model="header.counterpartyType" @change="onTypeChange" />供应商</label>
                <label class="radio-label-sm"><input type="radio" value="COUNTERPARTY" v-model="header.counterpartyType" @change="onTypeChange" />往来单位</label>
              </div>
            </div>
            <div class="field field-full">
              <label>往来单位 <span class="req">*</span></label>
              <select :value="header.counterpartyCode" @change="onPartnerChange($event.target.value)">
                <option value="">请选择</option>
                <option v-for="p in partnerOptions" :key="p.customerCode || p.supplierCode || p.counterpartyCode || p.code" :value="p.customerCode || p.supplierCode || p.counterpartyCode || p.code">
                  {{ p.customerCode || p.supplierCode || p.counterpartyCode || p.code }} {{ p.customerName || p.supplierName || p.counterpartyName || p.name || '' }}
                </option>
              </select>
            </div>
            <div class="field">
              <label>收支类型 <span class="req">*</span></label>
              <select v-model="header.direction">
                <option value="OUT">支出</option>
                <option value="IN">收入</option>
              </select>
            </div>
            <div class="field">
              <label>费用日期 <span class="req">*</span></label>
              <input type="date" v-model="header.expenseDate" />
            </div>
            <div class="field">
              <label>经手人 <span class="req">*</span></label>
              <select v-model="header.handler"><option value="">请选择</option><option v-for="e in employeeList" :key="e.employeeCode || e.code" :value="e.employeeName || e.name">{{ e.employeeName || e.name }}</option></select>
            </div>
            <div class="field">
              <label>部门</label>
              <select v-model="header.department"><option value="">请选择</option><option v-for="d in departmentList" :key="d.departmentCode || d.code" :value="d.departmentName || d.name">{{ d.departmentName || d.name }}</option></select>
            </div>
            <div class="field">
              <label>收/付账户</label>
              <select v-model="header.fundAccount"><option value="">请选择</option><option v-for="fa in fundAccountList" :key="fa.fundAccountCode || fa.code" :value="fa.fundAccountName || fa.name">{{ fa.fundAccountCode || fa.code }} {{ fa.fundAccountName || fa.name }}</option></select>
            </div>
            <div class="field">
              <label>关联单号</label>
              <input v-model="header.relatedBillNo" placeholder="选填" />
            </div>
            <div class="field">
              <label>外部凭证号</label>
              <input v-model="header.externalVoucherNo" placeholder="选填" />
            </div>
            <div class="field field-full">
              <label>备注</label>
              <input v-model="header.remark" placeholder="选填" />
            </div>
          </div>
        </div>

        <!-- 费用明细 -->
        <div class="card" style="padding:10px 14px;flex:1;display:flex;flex-direction:column;min-height:180px">
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
            <b style="font-size:13px">费用明细</b>
            <span v-if="formErrors.details" style="color:#f56c6c;font-size:12px">{{ formErrors.details }}</span>
            <div style="flex:1"></div>
            <span style="font-size:12px;color:#606266">合计 ￥{{ totalAmount }}（税额 ￥{{ totalTax }}）</span>
            <button class="btn" style="height:24px;font-size:11px;padding:0 8px" @click="addDetailRow">+ 添加行</button>
          </div>
          <div class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:36px">#</th>
                  <th style="min-width:90px">费用类型 <span class="req">*</span></th>
                  <th style="min-width:100px">商品</th>
                  <th style="width:56px">品牌</th>
                  <th style="width:52px;text-align:right">数量</th>
                  <th style="width:72px;text-align:right">单价</th>
                  <th style="width:76px;text-align:right">金额</th>
                  <th style="width:50px;text-align:right">税率%</th>
                  <th style="width:70px;text-align:right">税额</th>
                  <th style="width:76px;text-align:right">不含税</th>
                  <th style="min-width:60px">备注</th>
                  <th style="width:36px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="details.length === 0">
                  <td colspan="12" style="text-align:center;color:#909399;padding:20px">暂无明细，点击右上角「+ 添加行」</td>
                </tr>
                <tr v-for="(d, i) in details" :key="i">
                  <td>{{ i + 1 }}</td>
                  <td>
                    <select v-model="d.expenseType" style="width:100%;height:24px;font-size:12px">
                      <option value="">请选择</option>
                      <option v-for="et in expenseTypes" :key="et.expenseTypeCode || et.code" :value="et.expenseTypeName || et.name">{{ et.expenseTypeName || et.name }}</option>
                    </select>
                  </td>
                  <td>
                    <select :value="d.goodsCode" @change="onDetailGoodsChange(d, $event.target.value)" style="width:100%;height:24px;font-size:12px">
                      <option value="">请选择</option>
                      <option v-for="g in goodsList" :key="g.goodsCode" :value="g.goodsCode">{{ g.goodsCode }} {{ g.goodsName }}</option>
                    </select>
                  </td>
                  <td><input v-model="d.brandName" style="width:100%;height:24px;font-size:11px" placeholder="自动" /></td>
                  <td><input type="number" min="0" step="1" :value="d.qty" @input="d.qty=Number($event.target.value)||0; recalcDetail(d)" style="width:100%;height:24px;text-align:right;font-size:12px" /></td>
                  <td><input type="text" :value="d.price == null ? '' : d.price" @input="onDetailPriceChange(d, $event.target.value)" style="width:100%;height:24px;text-align:right;font-size:12px" /></td>
                  <td><input type="text" :value="d.amount" @input="onDetailAmountChange(d, $event.target.value)" style="width:100%;height:24px;text-align:right;font-size:12px;font-weight:600" /></td>
                  <td><input type="number" min="0" max="100" :value="d.taxRate" @input="d.taxRate=Number($event.target.value)||0; recalcDetail(d)" style="width:100%;height:24px;text-align:right;font-size:12px" /></td>
                  <td style="text-align:right;font-size:12px;color:#e6a23c">{{ Number(d.taxAmount || 0).toFixed(2) }}</td>
                  <td style="text-align:right;font-size:12px;color:#909399">{{ Number(d.excludingTaxAmount || 0).toFixed(2) }}</td>
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
/* ===== 抽屉布局（与 BillDrawer / ReceiptDrawer 同款） ===== */
.bill-drawer-mask {
  position: fixed; top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900; display: flex; pointer-events: none;
}
.bill-drawer-box {
  flex: 1; background: #fff; display: flex; flex-direction: column; min-width: 0;
  border-left: 1px solid #e5e7eb; box-shadow: -6px 0 24px rgba(15,46,88,.12); pointer-events: auto;
}
.bill-drawer-head {
  display: flex; align-items: center; gap: 10px; height: 46px; padding: 0 16px;
  border-bottom: 1px solid #e5e7eb; flex-shrink: 0;
}
.bill-drawer-head b { font-size: 15px; }
.bill-drawer-body {
  flex: 1; overflow: auto; padding: 12px 16px;
  display: flex; flex-direction: column; gap: 12px; background: #f5f7fa;
}

.card { background: #fff; border: 1px solid #e5e7eb; border-radius: 6px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; }
.form-grid .field { display: grid; grid-template-columns: 78px 1fr; align-items: center; gap: 4px; }
.form-grid .field label { font-size: 12px; color: #606266; text-align: right; font-weight: 600; white-space: nowrap; }
.form-grid .field input, .form-grid .field select { height: 28px; padding: 0 6px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; }
.field-full { grid-column: 1 / -1; }
.radio-row-sm { display: flex; gap: 8px; align-items: center; height: 28px; }
.radio-label-sm { display: inline-flex; align-items: center; gap: 3px; font-size: 12px; color: #606266; cursor: pointer; }
.radio-label-sm input[type="radio"] { width: 13px; height: 13px; cursor: pointer; accent-color: #409eff; margin: 0; }
.req { color: #f56c6c; }
.detail-scroll { flex: 1; overflow: auto; min-height: 0; }
.detail-scroll table { width: 100%; border-collapse: collapse; font-size: 12px; }
.detail-scroll th { background: #f5f7fa; padding: 6px 6px; text-align: left; font-weight: 600; color: #303133; border-bottom: 1px solid #e5e7eb; position: sticky; top: 0; z-index: 1; }
.detail-scroll td { padding: 3px 6px; border-bottom: 1px solid #f0f0f0; }
</style>
