<script setup>
import { ref, watch, computed, onMounted } from 'vue'
import { post } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' },
  moduleCode: { type: String, required: true }, // customer | supplier | warehouse | unit | brand | category
  editData: { type: Object, default: null },
})

// 上级分类下拉数据（分类模块用）
const categoryParents = ref([])
async function loadCategoryParents() {
  try {
    const data = await post('/base/category/page', { pageNo: 1, pageSize: 500, filters: {} })
    categoryParents.value = (data.records || []).map(r => ({ code: r.categoryCode, name: r.categoryName }))
  } catch (e) {
    categoryParents.value = []
  }
}
onMounted(() => {
  if (props.moduleCode === 'category') loadCategoryParents()
})
watch(() => props.visible, (v) => {
  if (v && props.moduleCode === 'category') loadCategoryParents()
})

const emit = defineEmits(['close', 'save'])

const titleMap = {
  customer: '客户资料',
  supplier: '供应商资料',
  warehouse: '仓库资料',
  unit: '单位',
  brand: '品牌',
  category: '商品分类',
}

const title = computed(() => {
  const t = titleMap[props.moduleCode] || '基础资料'
  return props.mode === 'edit' ? `编辑${t}` : `新建${t}`
})

const form = ref({})
const formErrors = ref({})

function getDefaultForm() {
  switch (props.moduleCode) {
    case 'customer':
      return {
        customerCode: '', customerName: '', channelType: '零售商超',
        contactName: '', mobile: '', territory: '', routeLine: '',
        salesman: '', customerLevel: '普通', accountPeriodType: '现结',
        creditLimit: 0, invoiceTitle: '', taxNo: '',
      }
    case 'supplier':
      return {
        supplierCode: '', supplierName: '', shortName: '',
        supplierType: '普通供应商', contactName: '', phone: '',
        deliveryDays: 0, settlementMethod: '现结', accountPeriodDays: 0,
        defaultBuyer: '', defaultReceiptAccount: '',
        invoiceTitle: '', taxNo: '', address: '', remark: '',
      }
    case 'warehouse':
      return {
        warehouseCode: '', warehouseName: '', warehouseType: '正常仓',
        inventoryType: '平台主仓', costGroup: 'CG01', managerName: '',
      }
    case 'unit':
      return { unitCode: '', unitName: '', canBaseUnit: true }
    case 'brand':
      return { brandCode: '', brandName: '', simpleCode: '' }
    case 'category':
      return { parentId: '', parentCode: '', categoryCode: '', categoryName: '', taxRate: '13%' }
    default:
      return {}
  }
}

function resetForm() {
  form.value = getDefaultForm()
  formErrors.value = {}
}

watch(() => props.visible, (val) => {
  if (val) {
    resetForm()
    if (props.mode === 'edit' && props.editData) {
      // 从行数据反解
      const row = props.editData
      const code = row.c0 || row.c1 || ''
      const name = row.c1 || row.c2 || ''
      form.value = { ...getDefaultForm(), customerCode: code, customerName: name, supplierCode: code, supplierName: name, warehouseCode: code, warehouseName: name, unitCode: code, unitName: name, brandCode: code, brandName: name, categoryCode: code, categoryName: name }
    }
  }
})

const apiMap = {
  customer: { save: '/base/customer/create', update: '/base/customer/update' },
  supplier: { save: '/base/supplier/create', update: '/base/supplier/update' },
  warehouse: { save: '/base/warehouse/create', update: '/base/warehouse/create' },
  unit: { save: '/base/unit/create', update: '/base/unit/create' },
  brand: { save: '/base/brand/create', update: '/base/brand/create' },
  category: { save: '/base/category/create', update: '/base/category/create' },
}

function validate() {
  const errors = {}
  const nameField = props.moduleCode + 'Name'
  const codeField = props.moduleCode + 'Code'
  if (!form.value[nameField]) errors.name = '名称不能为空'
  formErrors.value = errors
  return Object.keys(errors).length === 0
}

async function save() {
  if (!validate()) return
  const api = apiMap[props.moduleCode]
  if (!api) { alert('未知模块'); return }
  const endpoint = props.mode === 'edit' && api.update ? api.update : api.save
  try {
    const result = await post(endpoint, form.value)
    emit('save', result)
    closeDrawer()
  } catch (error) {
    alert('保存失败：' + (error.message || '未知错误'))
  }
}

function closeDrawer() {
  emit('close')
}

const fields = computed(() => {
  switch (props.moduleCode) {
    case 'customer':
      return [
        { key: 'customerCode', label: '客户编码' },
        { key: 'customerName', label: '客户名称', required: true },
        { key: 'channelType', label: '渠道类型', type: 'select', options: ['零售商超', '便利店', '餐饮店', '批发商', '电商平台'] },
        { key: 'contactName', label: '联系人' },
        { key: 'mobile', label: '手机号' },
        { key: 'territory', label: '片区' },
        { key: 'routeLine', label: '线路' },
        { key: 'salesman', label: '业务员' },
        { key: 'customerLevel', label: '客户等级', type: 'select', options: ['金牌', '银牌', '铜牌', '普通'] },
        { key: 'accountPeriodType', label: '账期类型', type: 'select', options: ['现结', '周结', '月结', '季结'] },
        { key: 'creditLimit', label: '信用额度', type: 'number' },
        { key: 'invoiceTitle', label: '发票抬头' },
        { key: 'taxNo', label: '税号' },
      ]
    case 'supplier':
      return [
        { key: 'supplierCode', label: '供应商编码' },
        { key: 'supplierName', label: '供应商名称', required: true },
        { key: 'shortName', label: '简称' },
        { key: 'supplierType', label: '供应商类型', type: 'select', options: ['普通供应商', '核心供应商', '临时供应商'] },
        { key: 'contactName', label: '联系人' },
        { key: 'phone', label: '电话' },
        { key: 'deliveryDays', label: '到货天数', type: 'number' },
        { key: 'settlementMethod', label: '结算方式', type: 'select', options: ['现结', '月结30天', '月结60天', '货到付款'] },
        { key: 'accountPeriodDays', label: '账期天数', type: 'number' },
        { key: 'defaultBuyer', label: '默认采购员' },
        { key: 'defaultReceiptAccount', label: '默认收款账户' },
        { key: 'invoiceTitle', label: '发票抬头' },
        { key: 'taxNo', label: '税号' },
        { key: 'address', label: '地址', full: true },
        { key: 'remark', label: '备注', type: 'textarea', full: true },
      ]
    case 'warehouse':
      return [
        { key: 'warehouseCode', label: '仓库编码' },
        { key: 'warehouseName', label: '仓库名称', required: true },
        { key: 'warehouseType', label: '仓库类型', type: 'select', options: ['正常仓', '退货仓', '虚拟仓', '冷藏仓'] },
        { key: 'inventoryType', label: '存货类型', type: 'select', options: ['平台主仓', '门店仓', '供应商仓'] },
        { key: 'managerName', label: '负责人' },
      ]
    case 'unit':
      return [
        { key: 'unitCode', label: '单位编码' },
        { key: 'unitName', label: '单位名称', required: true },
      ]
    case 'brand':
      return [
        { key: 'brandCode', label: '品牌编码' },
        { key: 'brandName', label: '品牌名称', required: true },
        { key: 'simpleCode', label: '简码' },
      ]
    case 'category':
      return [
        { key: 'parentCode', label: '上级分类', type: 'select', options: [{ value: '', label: '（无 - 顶级分类）' }, ...categoryParents.value.map(c => ({ value: c.code, label: `${c.code}  ${c.name}` }))] },
        { key: 'categoryCode', label: '分类编号', required: true },
        { key: 'categoryName', label: '分类名称', required: true },
        { key: 'taxRate', label: '税率', type: 'select', options: ['13%', '9%', '6%', '0%'] },
      ]
    default:
      return []
  }
})
</script>

<template>
  <div v-show="visible" class="drawer-overlay drawer-lite" @click.self="closeDrawer">
    <div class="modal-lite-box">
      <div class="modal-lite-head">
        <b>{{ title }}</b>
        <div class="actions">
          <button class="btn" @click="closeDrawer">取消</button>
          <button class="btn primary" @click="save">保存</button>
        </div>
      </div>

      <div class="modal-lite-body" style="padding:14px">
        <div class="grid4">
          <div v-for="f in fields" :key="f.key" class="field" :class="{ 'field-full': f.full }">
            <label>
              {{ f.label }}
              <span v-if="f.required" style="color:var(--danger)">*</span>
            </label>
            <select v-if="f.type === 'select'" v-model="form[f.key]">
              <option
                v-for="opt in f.options"
                :key="typeof opt === 'object' ? opt.value : opt"
                :value="typeof opt === 'object' ? opt.value : opt"
              >{{ typeof opt === 'object' ? opt.label : opt }}</option>
            </select>
            <input v-else-if="f.type === 'number'" v-model.number="form[f.key]" type="number" />
            <textarea v-else-if="f.type === 'textarea'" v-model="form[f.key]" :placeholder="f.label" rows="2"></textarea>
            <input v-else v-model="form[f.key]" :placeholder="f.label" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.field-full {
  grid-column: 1 / -1;
}
</style>
