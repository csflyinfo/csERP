<script setup>
import { ref, watch, computed, onMounted } from 'vue'
import { post } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' },
  moduleCode: { type: String, required: true },
  editData: { type: Object, default: null },
})

const emit = defineEmits(['close', 'save'])

const isPurchase = computed(() => props.moduleCode === 'purchaseOrder')
const title = computed(() => {
  const t = isPurchase.value ? '采购订单' : '销售订单'
  return props.mode === 'edit' ? `编辑${t}` : `新建${t}`
})

const goodsList = ref([])
const unitList = ref([])
const warehouseList = ref([])
const partnerList = ref([])

const headerForm = ref({})
const detailList = ref([])
const formErrors = ref({})

function resetForm() {
  if (isPurchase.value) {
    headerForm.value = {
      supplierId: '', buyer: '', warehouseId: '',
      billDate: new Date().toISOString().slice(0, 10),
      settlementMethod: '月结30天', ownerName: '平台货主',
    }
  } else {
    headerForm.value = {
      customerId: '', salesman: '', warehouseId: '',
      billDate: new Date().toISOString().slice(0, 10),
      lineType: '正常',
    }
  }
  detailList.value = [createEmptyDetail()]
  formErrors.value = {}
}

function createEmptyDetail() {
  return {
    goodsId: '', goodsName: '', unitId: '箱', qty: 1,
    price: 0, taxRate: '13%', amount: '0.00',
    lineType: '正常', discountRate: '100%',
  }
}

function calcRowAmount(row) {
  const qty = Number(row.qty) || 0
  const price = Number(row.price) || 0
  row.amount = (qty * price).toFixed(2)
}

function onGoodsChange(row, goodsCode) {
  const g = goodsList.value.find(x => x.goodsCode === goodsCode || x.goodsId === goodsCode)
  if (g) {
    row.goodsId = g.goodsCode || g.goodsId
    row.goodsName = g.goodsName
    row.price = g.standardPrice || g.latestPurchasePrice || 0
    calcRowAmount(row)
  }
}

const totalAmount = computed(() => {
  return detailList.value.reduce((sum, r) => sum + (Number(r.amount) || 0), 0).toFixed(2)
})

watch(() => props.visible, (val) => {
  if (val) {
    resetForm()
    loadBaseData()
    if (props.mode === 'edit' && props.editData) {
      // 编辑模式：从行数据反解
      // 简化处理：编辑时只填充头部，明细需要后端查询
    }
  }
})

async function loadBaseData() {
  try {
    const [g, u, w] = await Promise.all([
      post('/base/goods/page', { pageNo: 1, pageSize: 200, filters: {} }),
      post('/base/unit/page', { pageNo: 1, pageSize: 50, filters: {} }),
      post('/base/warehouse/page', { pageNo: 1, pageSize: 50, filters: {} }),
    ])
    goodsList.value = g.records || []
    unitList.value = u.records || []
    warehouseList.value = w.records || []
  } catch (e) {}

  try {
    const partnerApi = isPurchase.value ? '/base/supplier/page' : '/base/customer/page'
    const p = await post(partnerApi, { pageNo: 1, pageSize: 200, filters: {} })
    partnerList.value = p.records || []
  } catch (e) {}
}

function addRow() {
  detailList.value.push(createEmptyDetail())
}

function removeRow(index) {
  if (detailList.value.length > 1) {
    detailList.value.splice(index, 1)
  }
}

function validate() {
  const errors = {}
  const h = headerForm.value
  if (isPurchase.value) {
    if (!h.supplierId) errors.supplierId = '请选择供应商'
  } else {
    if (!h.customerId) errors.customerId = '请选择客户'
  }
  if (!h.warehouseId) errors.warehouseId = '请选择仓库'

  const validDetails = detailList.value.filter(r => r.goodsId && Number(r.qty) > 0 && Number(r.price) > 0)
  if (validDetails.length === 0) errors.details = '请至少填写一条有效商品明细'

  formErrors.value = errors
  return Object.keys(errors).length === 0
}

async function saveBill() {
  if (!validate()) return

  const apiPath = isPurchase.value ? '/purchase/order/create' : '/sales/order/create'
  const payload = {
    ...headerForm.value,
    details: detailList.value
      .filter(r => r.goodsId && Number(r.qty) > 0)
      .map(r => ({
        goodsId: r.goodsId,
        goodsName: r.goodsName,
        unitId: r.unitId,
        lineType: r.lineType,
        taxRate: r.taxRate,
        qty: Number(r.qty),
        price: Number(r.price),
      })),
  }

  try {
    const result = await post(apiPath, payload)
    emit('save', result)
    closeDrawer()
  } catch (error) {
    alert('保存失败：' + (error.message || '未知错误'))
  }
}

function closeDrawer() {
  emit('close')
}
</script>

<template>
  <div v-show="visible" class="drawer-overlay drawer-lite" @click.self="closeDrawer">
    <div class="modal-lite-box">
      <div class="modal-lite-head">
        <b>{{ title }}</b>
        <div class="actions">
          <button class="btn" @click="closeDrawer">取消</button>
          <button class="btn primary" @click="saveBill">保存</button>
        </div>
      </div>

      <div class="modal-lite-body" style="padding:14px;display:flex;flex-direction:column;gap:12px">
        <!-- 头部信息 -->
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">单据信息</div>
          <div class="grid4">
            <div class="field">
              <label>{{ isPurchase ? '供应商' : '客户' }} <span v-if="formErrors.supplierId || formErrors.customerId" style="color:var(--danger)">*</span></label>
              <select v-model="headerForm[isPurchase ? 'supplierId' : 'customerId']">
                <option value="">请选择</option>
                <option v-for="p in partnerList" :key="p.supplierId || p.customerId" :value="p.supplierName || p.customerName">
                  {{ p.supplierName || p.customerName }}
                </option>
              </select>
            </div>
            <div class="field">
              <label>{{ isPurchase ? '采购员' : '业务员' }}</label>
              <input v-model="headerForm[isPurchase ? 'buyer' : 'salesman']" placeholder="请输入" />
            </div>
            <div class="field">
              <label>仓库 <span v-if="formErrors.warehouseId" style="color:var(--danger)">*</span></label>
              <select v-model="headerForm.warehouseId">
                <option value="">请选择</option>
                <option v-for="w in warehouseList" :key="w.warehouseId" :value="w.warehouseName">
                  {{ w.warehouseName }}
                </option>
              </select>
            </div>
            <div class="field">
              <label>单据日期</label>
              <input type="date" v-model="headerForm.billDate" />
            </div>
            <div class="field" v-if="isPurchase">
              <label>结算方式</label>
              <select v-model="headerForm.settlementMethod">
                <option>现结</option>
                <option>月结30天</option>
                <option>月结60天</option>
                <option>货到付款</option>
              </select>
            </div>
            <div class="field" v-if="isPurchase">
              <label>货主</label>
              <input v-model="headerForm.ownerName" placeholder="平台货主" />
            </div>
            <div class="field" v-if="!isPurchase">
              <label>行类型</label>
              <select v-model="headerForm.lineType">
                <option>正常</option>
                <option>赠品</option>
                <option>样品</option>
              </select>
            </div>
          </div>
        </div>

        <!-- 商品明细 -->
        <div class="card" style="padding:12px;flex:1;display:flex;flex-direction:column;min-height:0">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
            <div style="font-weight:900;color:var(--primary)">商品明细</div>
            <button class="btn primary" style="height:26px;padding:0 10px;font-size:12px" @click="addRow">+ 添加商品</button>
          </div>
          <div v-if="formErrors.details" style="color:var(--danger);font-size:12px;margin-bottom:6px">{{ formErrors.details }}</div>
          <div style="overflow:auto;flex:1;min-height:0">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th>商品</th>
                  <th style="width:80px">单位</th>
                  <th style="width:90px">数量</th>
                  <th style="width:100px">单价</th>
                  <th style="width:80px">税率</th>
                  <th style="width:110px">金额</th>
                  <th style="width:50px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in detailList" :key="index">
                  <td>{{ index + 1 }}</td>
                  <td>
                    <select v-model="row.goodsId" @change="onGoodsChange(row, row.goodsId)" style="min-width:180px">
                      <option value="">选择商品</option>
                      <option v-for="g in goodsList" :key="g.goodsCode || g.goodsId" :value="g.goodsCode || g.goodsId">
                        {{ g.goodsName }} ({{ g.goodsCode || g.goodsId }})
                      </option>
                    </select>
                  </td>
                  <td>
                    <select v-model="row.unitId" style="min-width:60px">
                      <option v-for="u in unitList" :key="u.unitId" :value="u.unitName">{{ u.unitName }}</option>
                    </select>
                  </td>
                  <td><input v-model.number="row.qty" type="number" min="1" style="text-align:right" @input="calcRowAmount(row)" /></td>
                  <td><input v-model.number="row.price" type="number" min="0" step="0.01" style="text-align:right" @input="calcRowAmount(row)" /></td>
                  <td>
                    <select v-model="row.taxRate" style="min-width:60px">
                      <option>13%</option>
                      <option>9%</option>
                      <option>6%</option>
                      <option>0%</option>
                    </select>
                  </td>
                  <td style="text-align:right;font-weight:800">{{ row.amount }}</td>
                  <td><button class="btn danger" style="height:24px;padding:0 6px;font-size:12px" @click="removeRow(index)">删除</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 金额汇总 -->
        <div class="summary">
          <span>合计金额：<b>¥ {{ totalAmount }}</b></span>
          <span>商品行数：<b>{{ detailList.filter(r => r.goodsId).length }}</b></span>
        </div>
      </div>
    </div>
  </div>
</template>
