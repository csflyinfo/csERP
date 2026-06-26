<script setup>
import { ref, watch, computed } from 'vue'
import { post } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' }, // add / edit
  billType: { type: String, required: true }, // receipt / payment / receiveVerify / payVerify
  editData: { type: Object, default: null },
})

const emit = defineEmits(['close', 'save'])

const isReceipt = computed(() => props.billType === 'receipt' || props.billType === 'receiveVerify')
const title = computed(() => {
  const map = {
    receipt: '新建收款单',
    payment: '新建付款单',
    receiveVerify: '收款核销',
    payVerify: '付款核销',
  }
  return map[props.billType] || '资金单据'
})

const partnerList = ref([])
const arApList = ref([])
const fundAccounts = ref(['工行基本户', '建行一般户', '支付宝', '微信'])

const form = ref({
  objectId: '',
  fundAccountId: '工行基本户',
  amount: '',
  remark: '',
})
const formErrors = ref({})
const loading = ref(false)

function resetForm() {
  form.value = {
    objectId: '',
    fundAccountId: '工行基本户',
    amount: '',
    remark: '',
  }
  formErrors.value = {}
  arApList.value = []
}

watch(() => props.visible, (val) => {
  if (val) {
    resetForm()
    loadPartners()
    if (props.editData) {
      form.value.objectId = props.editData.c1 || ''
      form.value.amount = props.editData.c4 || ''
    }
  }
})

async function loadPartners() {
  try {
    const api = isReceipt.value ? '/base/customer/page' : '/base/supplier/page'
    const data = await post(api, { pageNo: 1, pageSize: 200, filters: {} })
    partnerList.value = data.records || []
  } catch (e) {}

  // 核销模式：加载待核销的应收/应付列表
  if (props.billType === 'receiveVerify' || props.billType === 'payVerify') {
    try {
      const api = isReceipt.value ? '/finance/ar/page' : '/finance/ap/page'
      const data = await post(api, { pageNo: 1, pageSize: 50, filters: {} })
      arApList.value = (data.records || []).filter(r => r.status !== '已核销')
    } catch (e) {}
  }
}

function validate() {
  const errors = {}
  if (!form.value.objectId) {
    errors.objectId = isReceipt.value ? '请选择客户' : '请选择供应商'
  }
  if (!form.value.amount || Number(form.value.amount) <= 0) {
    errors.amount = '请输入有效金额'
  }
  formErrors.value = errors
  return Object.keys(errors).length === 0
}

async function save() {
  if (!validate()) return

  const apiMap = {
    receipt: '/finance/receipt/create',
    payment: '/finance/payment/create',
    receiveVerify: '/finance/reconcile/receive',
    payVerify: '/finance/reconcile/pay',
  }
  const api = apiMap[props.billType]

  try {
    const result = await post(api, {
      objectId: form.value.objectId,
      fundAccountId: form.value.fundAccountId,
      amount: Number(form.value.amount),
      remark: form.value.remark,
    })
    emit('save', result)
    closeDrawer()
  } catch (error) {
    alert('保存失败：' + (error.message || '未知错误'))
  }
}

function closeDrawer() {
  emit('close')
}

function selectArAp(row) {
  form.value.objectId = row.arNo || row.apNo || ''
  form.value.amount = row.unreceivedAmount || row.unpaidAmount || ''
}
</script>

<template>
  <div v-show="visible" class="drawer-overlay drawer-lite" @click.self="closeDrawer">
    <div class="modal-lite-box">
      <div class="modal-lite-head">
        <b>{{ title }}</b>
        <div class="actions">
          <button class="btn" @click="closeDrawer">取消</button>
          <button class="btn primary" @click="save">确认</button>
        </div>
      </div>

      <div class="modal-lite-body" style="padding:14px;display:flex;flex-direction:column;gap:12px">
        <!-- 核销模式：显示待核销列表 -->
        <div v-if="(billType === 'receiveVerify' || billType === 'payVerify') && arApList.length > 0" class="card" style="padding:10px;max-height:200px;overflow:auto">
          <div style="font-weight:900;margin-bottom:8px;color:var(--primary)">待核销{{ isReceipt ? '应收' : '应付' }}列表</div>
          <table>
            <thead>
              <tr>
                <th>单号</th>
                <th>{{ isReceipt ? '客户' : '供应商' }}</th>
                <th>金额</th>
                <th>未核销</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in arApList" :key="row.arNo || row.apNo">
                <td>{{ row.arNo || row.apNo }}</td>
                <td>{{ row.customer || row.supplier }}</td>
                <td style="text-align:right">{{ row.arAmount || row.apAmount }}</td>
                <td style="text-align:right">{{ row.unreceivedAmount || row.unpaidAmount }}</td>
                <td><button class="btn primary" style="height:24px;padding:0 8px;font-size:12px" @click="selectArAp(row)">选择</button></td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">单据信息</div>
          <div class="grid4">
            <div class="field">
              <label>{{ isReceipt ? '客户' : '供应商' }} <span v-if="formErrors.objectId" style="color:var(--danger)">*</span></label>
              <select v-model="form.objectId">
                <option value="">请选择</option>
                <option v-for="p in partnerList" :key="p.customerId || p.supplierId" :value="p.customerName || p.supplierName">
                  {{ p.customerName || p.supplierName }}
                </option>
              </select>
            </div>
            <div class="field">
              <label>资金账户</label>
              <select v-model="form.fundAccountId">
                <option v-for="a in fundAccounts" :key="a" :value="a">{{ a }}</option>
              </select>
            </div>
            <div class="field">
              <label>金额 <span v-if="formErrors.amount" style="color:var(--danger)">*</span></label>
              <input v-model="form.amount" type="number" min="0" step="0.01" placeholder="0.00" />
            </div>
            <div class="field">
              <label>备注</label>
              <input v-model="form.remark" placeholder="选填" />
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
