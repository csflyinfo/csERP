<script setup>
import { ref, watch, computed, onMounted } from 'vue'
import { post, get } from '../api/client.js'
import { getDict } from '../utils/dictionary.js'
import SettlementPanel from './SettlementPanel.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' }, // add | edit | view
  moduleCode: { type: String, required: true },
  editData: { type: Object, default: null },
})

const emit = defineEmits(['close', 'save'])

// 480×520 定尺模块（新增/编辑/详情统一形态）
const FIXED_SIZE_MODULES = [
  'category', 'brand', 'unit', 'warehouse',
  'territory', 'routeLine', 'department', 'expenseType',
  'employee', 'owner', 'counterpartyType', 'fundAccount', 'priceGroup',
]
const useFixedSize = computed(() => FIXED_SIZE_MODULES.includes(props.moduleCode))
// 走中心弹窗（否则走右侧抽屉）：定尺模块 + 供应商/客户外的普通场景
const useModal = computed(() => useFixedSize.value)
// 走右侧宽抽屉：counterparty（表单中含银行账户 / 开票信息网格）
const isSideDrawer = computed(() => ['counterparty', 'supplier', 'customer'].includes(props.moduleCode))
// 小弹窗模块：字段极少（如往来单位类型），采用更紧凑的宽度
const SMALL_MODAL_MODULES = ['counterpartyType']
const isSmallModal = computed(() => SMALL_MODAL_MODULES.includes(props.moduleCode))

const titleMap = {
  customer: '客户资料',
  supplier: '供应商资料',
  warehouse: '仓库资料',
  unit: '单位',
  brand: '品牌',
  category: '商品分类',
  territory: '片区',
  routeLine: '线路',
  department: '部门',
  expenseType: '费用类型',
  employee: '人员信息',
  owner: '货主',
  counterparty: '往来单位',
  counterpartyType: '单位类型',
  fundAccount: '资金账户',
  priceGroup: '价格组',
}

const title = computed(() => {
  const t = titleMap[props.moduleCode] || '基础资料'
  const prefix = props.mode === 'view' ? '' : (props.mode === 'edit' ? '编辑' : '新建')
  const suffix = props.mode === 'view' ? '详情' : ''
  return `${prefix}${t}${suffix}`
})

const isReadonly = computed(() => props.mode === 'view')

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
// 上级部门（部门模块用）
const departmentParents = ref([])
async function loadDepartmentParents() {
  try {
    const data = await post('/base/master/department/page', { pageNo: 1, pageSize: 500, filters: {} })
    departmentParents.value = (data.records || []).map(r => ({ code: r.departmentCode || r.code, name: r.departmentName || r.name }))
  } catch (e) {
    departmentParents.value = []
  }
}
// 人员列表（仓库负责人 / 人员上级业务员下拉用）
const employeeList = ref([])
async function loadEmployees() {
  try {
    const data = await post('/base/master/employee/page', { pageNo: 1, pageSize: 500, filters: {} })
    employeeList.value = (data.records || []).map(r => ({
      code: r.employeeCode || r.code,
      name: r.employeeName || r.name,
      isSalesman: !!r.isSalesman,
      isDeliveryman: !!r.isDeliveryman,
      status: r.status || 'NORMAL',
    }))
  } catch (e) {
    employeeList.value = []
  }
}
// 货主列表（人员/其他资料下拉用）
const ownerList = ref([])
async function loadOwners() {
  try {
    const data = await post('/base/master/owner/page', { pageNo: 1, pageSize: 500, filters: {} })
    ownerList.value = (data.records || []).map(r => ({ code: r.ownerCode || r.code, name: r.ownerName || r.name }))
  } catch (e) {
    ownerList.value = []
  }
}
// 部门列表（人员所属部门下拉用；与 departmentParents 分开保持职责清晰）
const departmentList = ref([])
async function loadDepartments() {
  try {
    const data = await post('/base/master/department/page', { pageNo: 1, pageSize: 500, filters: {} })
    departmentList.value = (data.records || []).map(r => ({ code: r.departmentCode || r.code, name: r.departmentName || r.name }))
  } catch (e) {
    departmentList.value = []
  }
}
// 资金账户父级（用于新建二级账户时选择上级）
const fundAccountParents = ref([])
async function loadFundAccountParents() {
  try {
    const data = await post('/base/master/fund-account/page', { pageNo: 1, pageSize: 500, filters: {} })
    // 上级下拉只列出「一级账户」，即 parentCode 为空的记录
    fundAccountParents.value = (data.records || [])
      .filter(r => !r.parentCode)
      .map(r => ({ code: r.fundAccountCode || r.code, name: r.fundAccountName || r.name }))
  } catch (e) {
    fundAccountParents.value = []
  }
}
// 往来单位类型列表（供往来单位新建/编辑时"单位类型"下拉使用）
const counterpartyTypeList = ref([])
async function loadCounterpartyTypes() {
  try {
    const data = await post('/base/master/counterparty-type/page', { pageNo: 1, pageSize: 500, filters: {} })
    counterpartyTypeList.value = (data.records || [])
      .filter(r => r.status == null || r.status === 'NORMAL' || r.status === '正常')
      .map(r => ({ code: r.typeCode || r.code, name: r.typeName || r.name }))
  } catch (e) {
    counterpartyTypeList.value = []
  }
}
// 费用类型父级（用于选择上级费用类型）—— 保留 parentCode 以判定层级
const expenseTypeParents = ref([])
async function loadExpenseTypeParents() {
  try {
    const data = await post('/base/master/expense-type/page', { pageNo: 1, pageSize: 500, filters: {} })
    expenseTypeParents.value = (data.records || [])
      .map(r => ({ code: r.expenseTypeCode || r.code, name: r.expenseTypeName || r.name, parentCode: r.parentCode || '' }))
  } catch (e) {
    expenseTypeParents.value = []
  }
}

// 片区父级
const territoryParents = ref([])
async function loadTerritoryParents() {
  try {
    const data = await post('/base/master/territory/page', { pageNo: 1, pageSize: 500, filters: {} })
    territoryParents.value = (data.records || [])
      .map(r => ({ code: r.territoryCode || r.code, name: r.territoryName || r.name, parentCode: r.parentCode || '' }))
  } catch (e) {
    territoryParents.value = []
  }
}

// 供应商模块：采购员下拉、交货方式字典、默认物流公司下拉
const buyerOptions = ref([])
async function loadBuyers() {
  try {
    const data = await post('/base/employee/buyers', {})
    const list = Array.isArray(data) ? data : (data?.records || [])
    // 兼容 H2 大写返回：CODE/NAME
    buyerOptions.value = list.map(b => ({
      code: b.code || b.CODE || b.employeeCode || '',
      name: b.name || b.NAME || b.employeeName || '',
    })).filter(b => b.name)
  } catch (e) {
    buyerOptions.value = []
  }
}
const deliveryMethodOptions = ref([])
async function loadDeliveryMethods() {
  deliveryMethodOptions.value = await getDict('delivery_method')
}
const logisticsOptions = ref([])
async function loadLogisticsCompanies() {
  logisticsOptions.value = await getDict('logistics_company')
}

// ============ 客户模块 loaders ============
const customerChannelOptions = ref([])         // 客户渠道字典
const customerTerritoryOptions = ref([])       // 末级片区
const customerRouteLineOptions = ref([])       // 线路
const customerSalesmanOptions = ref([])        // 业务员
async function loadCustomerChannels() {
  customerChannelOptions.value = await getDict('customer_channel')
}
async function loadCustomerTerritories() {
  try {
    const data = await post('/base/master/territory/page', { pageNo: 1, pageSize: 1000, filters: {} })
    const all = (data?.records || []).filter(r => (r.status || 'NORMAL') === 'NORMAL')
    // 末级：其它片区没把它作为 parent
    const parentSet = new Set(all.map(r => r.parentCode).filter(Boolean))
    customerTerritoryOptions.value = all
      .filter(r => !parentSet.has(r.territoryCode))
      .map(r => ({ code: r.territoryCode, name: r.territoryName }))
  } catch (e) {
    customerTerritoryOptions.value = []
  }
}
async function loadCustomerRouteLines() {
  try {
    const data = await post('/base/master/route-line/page', { pageNo: 1, pageSize: 500, filters: {} })
    customerRouteLineOptions.value = (data?.records || [])
      .filter(r => (r.status || 'NORMAL') === 'NORMAL')
      .map(r => ({ code: r.routeLineCode, name: r.routeLineName }))
  } catch (e) {
    customerRouteLineOptions.value = []
  }
}
async function loadCustomerSalesmen() {
  try {
    const data = await post('/base/employee/salesmen', {})
    const list = Array.isArray(data) ? data : (data?.records || [])
    customerSalesmanOptions.value = list.map(b => ({
      code: b.code || b.CODE || b.employeeCode || '',
      name: b.name || b.NAME || b.employeeName || '',
    })).filter(b => b.name)
  } catch (e) {
    customerSalesmanOptions.value = []
  }
}
// 客户模块：价格组下拉（仅启用的）
const customerPriceGroupOptions = ref([])
async function loadCustomerPriceGroups() {
  try {
    const data = await post('/base/master/price-group/page', { pageNo: 1, pageSize: 200, filters: {} })
    customerPriceGroupOptions.value = (data?.records || [])
      .filter(r => r.enabled && (r.status || 'NORMAL') === 'NORMAL')
      .map(r => ({ code: r.priceGroupCode, name: r.priceGroupName }))
  } catch (e) {
    customerPriceGroupOptions.value = []
  }
}

onMounted(() => {
  if (props.moduleCode === 'category') loadCategoryParents()
  if (props.moduleCode === 'department') loadDepartmentParents()
  if (props.moduleCode === 'warehouse') loadEmployees()
  if (props.moduleCode === 'routeLine') loadEmployees()
  if (props.moduleCode === 'employee') { loadOwners(); loadDepartments(); loadEmployees() }
  if (props.moduleCode === 'fundAccount') loadFundAccountParents()
  if (props.moduleCode === 'counterparty') loadCounterpartyTypes()
  if (props.moduleCode === 'expenseType') loadExpenseTypeParents()
  if (props.moduleCode === 'territory') loadTerritoryParents()
  if (props.moduleCode === 'supplier') { loadBuyers(); loadDeliveryMethods(); loadLogisticsCompanies() }
  if (props.moduleCode === 'customer') { loadCustomerChannels(); loadCustomerTerritories(); loadCustomerRouteLines(); loadCustomerSalesmen(); loadCustomerPriceGroups() }
})
watch(() => props.visible, (v) => {
  if (!v) return
  if (props.moduleCode === 'category') loadCategoryParents()
  if (props.moduleCode === 'department') loadDepartmentParents()
  if (props.moduleCode === 'warehouse') loadEmployees()
  if (props.moduleCode === 'routeLine') loadEmployees()
  if (props.moduleCode === 'employee') { loadOwners(); loadDepartments(); loadEmployees() }
  if (props.moduleCode === 'fundAccount') loadFundAccountParents()
  if (props.moduleCode === 'counterparty') loadCounterpartyTypes()
  if (props.moduleCode === 'expenseType') loadExpenseTypeParents()
  if (props.moduleCode === 'territory') loadTerritoryParents()
  if (props.moduleCode === 'supplier') { loadBuyers(); loadDeliveryMethods(); loadLogisticsCompanies() }
  if (props.moduleCode === 'customer') { loadCustomerChannels(); loadCustomerTerritories(); loadCustomerRouteLines(); loadCustomerSalesmen(); loadCustomerPriceGroups() }
})

const form = ref({})
const formErrors = ref({})

function getDefaultForm() {
  switch (props.moduleCode) {
    case 'customer':
      return {
        customerCode: '', customerName: '', channelType: '',
        contactName: '', mobile: '', territory: '', routeLine: '',
        salesman: '', customerLevel: '普通',
        creditLimit: 0, invoiceTitle: '', taxNo: '',
        shippingAddress: '',
        longitude: '', latitude: '',
        priceGroupCode: '',   // 空 = 商品默认售价
        addresses: [],        // V12 多地址子表
        // 账期设置：新建默认「预付」；编辑时被后端数据回填覆盖
        settlementType: 'PREPAY', termType: 'FIXED', termDays: 0,
        cutoffDay: '', paymentMode: '', termMonths: 0, paymentDay: '',
      }
    case 'supplier':
      return {
        supplierCode: '', supplierName: '', shortName: '',
        supplierType: '普通供应商', contactName: '', phone: '',
        deliveryDays: 0,
        defaultBuyer: '',
        deliveryMethod: '送货上门',
        defaultLogisticsCompany: '',
        address: '', remark: '',
        bankAccounts: [], // 多条收款账户子表
        // 账期设置
        settlementType: 'TERM', termType: 'FIXED', termDays: 0,
        cutoffDay: '', paymentMode: '', termMonths: 0, paymentDay: '',
      }
    case 'warehouse':
      return {
        warehouseCode: '', warehouseName: '', warehouseType: '实物仓',
        inventoryType: '平台仓库', costGroup: 'CG01', managerName: '',
      }
    case 'unit':
      return { unitCode: '', unitName: '', canMiddleUnit: false, canLargeUnit: false }
    case 'brand':
      return { brandCode: '', brandName: '', simpleCode: '' }
    case 'category':
      return { parentId: '', parentCode: '', categoryCode: '', categoryName: '', externalCode: '', taxRate: 13, status: 'NORMAL' }
    case 'territory':
      return { territoryCode: '', territoryName: '', parentCode: '', remark: '' }
    case 'routeLine':
      return { routeLineCode: '', routeLineName: '', driver: '', coverage: '', remark: '' }
    case 'department':
      return { departmentCode: '', departmentName: '', parentCode: '', headCount: 0, remark: '' }
    case 'expenseType':
      return { expenseTypeCode: '', expenseTypeName: '', parentCode: '', direction: '支出', costParticipation: '否', remark: '' }
    case 'employee':
      return {
        employeeCode: '', employeeName: '',
        gender: '男', ownerName: '', mobile: '', idCard: '',
        education: '', address: '', department: '',
        isSalesman: false, isSalesmanAdmin: false, parentSalesman: '',
        isBuyer: false, isWarehouseKeeper: false, isDeliveryman: false,
        remark: '',
      }
    case 'owner':
      return { ownerCode: '', ownerName: '', ownerType: '自营', platform: '', remark: '' }
    case 'counterparty':
      return { counterpartyCode: '', counterpartyName: '', counterpartyType: '', typeCode: '', contactName: '', phone: '', remark: '', status: 'NORMAL', bankAccounts: [], invoiceInfos: [] }
    case 'counterpartyType':
      return { typeCode: '', typeName: '' }
    case 'fundAccount':
      return { fundAccountCode: '', fundAccountName: '', parentCode: '', accountType: '银行账户', balance: 0, remark: '' }
    case 'priceGroup':
      return { priceGroupCode: '', priceGroupName: '', enabled: true, sortOrder: 0, remark: '' }
    default:
      return {}
  }
}

function resetForm() {
  form.value = getDefaultForm()
  formErrors.value = {}
}

// 用 row._raw（后端原始记录）反填，比只解析 c0/c1 稳
watch(() => props.visible, async (val) => {
  if (!val) return
  resetForm()
  if ((props.mode === 'edit' || props.mode === 'view') && props.editData) {
    const raw = props.editData._raw || {}
    // 合并已知字段：默认值 + 后端原始字段（同名键覆盖）
    form.value = { ...form.value, ...raw }
    // 商品分类：defaultTaxRate("13%") 反向映射到表单 taxRate(13)
    if (props.moduleCode === 'category' && raw.defaultTaxRate != null) {
      const n = parseInt(String(raw.defaultTaxRate).replace('%', ''), 10)
      form.value.taxRate = Number.isFinite(n) ? n : 13
    }
    // 往来单位：优先用 typeCode；如果历史数据只有 counterpartyType 名称，先按名称回查
    if (props.moduleCode === 'counterparty' && !form.value.typeCode && raw.counterpartyType) {
      const hit = counterpartyTypeList.value.find(t => t.name === raw.counterpartyType)
      if (hit) form.value.typeCode = hit.code
    }
    // 供应商：拉取详情以取 bankAccounts 子表
    if (props.moduleCode === 'supplier' && (raw.supplierCode || props.editData.c0)) {
      const code = raw.supplierCode || props.editData.c0
      try {
        const detail = await get(`/base/supplier/detail?code=${encodeURIComponent(code)}`)
        if (detail) {
          form.value = { ...form.value, ...detail, bankAccounts: Array.isArray(detail.bankAccounts) ? detail.bankAccounts : [] }
        }
      } catch (_) { /* 忽略 */ }
    }
    // 客户：拉取地址子表（V12）
    if (props.moduleCode === 'customer' && (raw.customerCode || props.editData.c0)) {
      const code = raw.customerCode || props.editData.c0
      try {
        const list = await post('/base/customer/addresses', { customerCode: code })
        form.value.addresses = Array.isArray(list) ? list : []
      } catch (_) { form.value.addresses = [] }
    }
  }
})

// 保存/更新端点
const apiMap = {
  customer: { save: '/base/customer/create', update: '/base/customer/update' },
  supplier: { save: '/base/supplier/create', update: '/base/supplier/update' },
  warehouse: { save: '/base/warehouse/create', update: '/base/warehouse/update' },
  unit: { save: '/base/unit/create', update: '/base/unit/update' },
  brand: { save: '/base/brand/create', update: '/base/brand/update' },
  category: { save: '/base/category/create', update: '/base/category/update' },
  // 通用主档：/base/master/save 通过 moduleCode 分派
  territory: { save: '/base/master/save', update: '/base/master/save' },
  routeLine: { save: '/base/master/save', update: '/base/master/save' },
  department: { save: '/base/master/save', update: '/base/master/save' },
  expenseType: { save: '/base/master/save', update: '/base/master/save' },
  employee: { save: '/base/master/save', update: '/base/master/save' },
  owner: { save: '/base/master/save', update: '/base/master/save' },
  counterparty: { save: '/base/master/save', update: '/base/master/save' },
  counterpartyType: { save: '/base/master/save', update: '/base/master/save' },
  fundAccount: { save: '/base/master/save', update: '/base/master/save' },
  priceGroup: { save: '/base/master/save', update: '/base/master/save' },
}

// 必填字段：名称必填
const NAME_FIELD_MAP = {
  customer: 'customerName', supplier: 'supplierName', warehouse: 'warehouseName',
  unit: 'unitName', brand: 'brandName', category: 'categoryName',
  territory: 'territoryName', routeLine: 'routeLineName', department: 'departmentName',
  expenseType: 'expenseTypeName', employee: 'employeeName', owner: 'ownerName',
  counterparty: 'counterpartyName', counterpartyType: 'typeName', fundAccount: 'fundAccountName', priceGroup: 'priceGroupName',
}

function validate() {
  const errors = {}
  const nameField = NAME_FIELD_MAP[props.moduleCode]
  if (nameField && !form.value[nameField]) errors.name = '名称不能为空'
  // 资金账户：新建时上级账户必填
  if (props.moduleCode === 'fundAccount' && props.mode === 'add' && !form.value.parentCode) {
    errors.parentCode = '上级账户不能为空'
  }
  // 线路：新建时编码必填
  if (props.moduleCode === 'routeLine' && props.mode === 'add' && !String(form.value.routeLineCode || '').trim()) {
    errors.routeLineCode = '线路编码不能为空'
  }
  // 客户：客户编号、客户名称、联系人、联系电话、收货地址、业务员必填
  if (props.moduleCode === 'customer') {
    const req = [
      ['customerCode', '客户编号不能为空'],
      ['customerName', '客户名称不能为空'],
      ['contactName', '联系人不能为空'],
      ['mobile', '联系电话不能为空'],
      ['shippingAddress', '收货地址不能为空'],
      ['salesman', '业务员不能为空'],
    ]
    for (const [key, msg] of req) {
      if (!String(form.value[key] || '').trim()) { errors[key] = msg; break }
    }
  }
  formErrors.value = errors
  return Object.keys(errors).length === 0
}

async function save() {
  if (isReadonly.value) { closeDrawer(); return }
  if (!validate()) {
    const first = Object.values(formErrors.value)[0]
    if (first) alert(first)
    return
  }
  const api = apiMap[props.moduleCode]
  if (!api) { alert('未知模块'); return }
  const endpoint = props.mode === 'edit' && api.update ? api.update : api.save
  try {
    // 主档统一端点需要 moduleCode + mode（用于后端区分新增/编辑，避免同码记录被静默覆盖）
    const payload = { ...form.value, moduleCode: props.moduleCode, mode: props.mode }
    // 商品分类：taxRate 数字 → defaultTaxRate 字符串（如 13 → "13%"）
    if (props.moduleCode === 'category') {
      let rate = Number(payload.taxRate)
      if (!Number.isFinite(rate)) rate = 0
      rate = Math.max(0, Math.min(99, Math.floor(rate)))
      payload.defaultTaxRate = `${rate}%`
      delete payload.taxRate
    }
    // 往来单位：typeCode → 同步写 counterpartyType（类型名称冗余列表显示用）
    if (props.moduleCode === 'counterparty' && payload.typeCode) {
      const hit = counterpartyTypeList.value.find(t => t.code === payload.typeCode)
      payload.counterpartyType = hit ? hit.name : payload.typeCode
    }
    const result = await post(endpoint, payload)
    emit('save', result)
    closeDrawer()
  } catch (error) {
    alert('保存失败：' + (error.message || '未知错误'))
  }
}

function closeDrawer() {
  emit('close')
}

// ============ 供应商收款账户子网格操作 ============
function addSupplierBank() {
  if (!Array.isArray(form.value.bankAccounts)) form.value.bankAccounts = []
  form.value.bankAccounts.push({ accountName: '', bankName: '', bankAccount: '', branch: '', isDefault: false, remark: '' })
}
function removeSupplierBank(i) {
  form.value.bankAccounts.splice(i, 1)
}
function setSupplierBankDefault(i, checked) {
  const list = form.value.bankAccounts || []
  if (checked) {
    list.forEach((b, idx) => { b.isDefault = idx === i })
  } else {
    if (list[i]) list[i].isDefault = false
  }
}

// V12：客户地址子表
function addCustomerAddress() {
  if (!Array.isArray(form.value.addresses)) form.value.addresses = []
  form.value.addresses.push({ addressName: '', contactName: '', contactMobile: '', detailAddress: '', longitude: '', latitude: '', isDefault: false, remark: '' })
}
function removeCustomerAddress(i) {
  form.value.addresses.splice(i, 1)
}
function setCustomerAddressDefault(i, checked) {
  const list = form.value.addresses || []
  if (checked) list.forEach((a, idx) => { a.isDefault = idx === i })
  else if (list[i]) list[i].isDefault = false
}

const fields = computed(() => {
  switch (props.moduleCode) {
    case 'customer': {
      const list = [
        { key: 'customerCode', label: '客户编码', required: true, readonly: props.mode === 'edit' },
        { key: 'customerName', label: '客户名称', required: true },
        { key: 'channelType', label: '渠道类型', type: 'select', options: [
          { value: '', label: '（请选择）' },
          ...customerChannelOptions.value.map(c => ({ value: c.name, label: c.name })),
        ] },
        { key: 'contactName', label: '联系人', required: true },
        { key: 'mobile', label: '手机号', required: true },
        { key: 'territory', label: '片区', type: 'select', options: [
          { value: '', label: '（未指定）' },
          ...customerTerritoryOptions.value.map(t => ({ value: t.name, label: t.code ? `${t.code}  ${t.name}` : t.name })),
        ] },
        { key: 'routeLine', label: '线路', type: 'select', options: [
          { value: '', label: '（未指定）' },
          ...customerRouteLineOptions.value.map(r => ({ value: r.name, label: r.code ? `${r.code}  ${r.name}` : r.name })),
        ] },
        { key: 'salesman', label: '业务员', required: true, type: 'select', options: [
          { value: '', label: '（请选择）' },
          ...customerSalesmanOptions.value.map(s => ({ value: s.name, label: s.code ? `${s.code}  ${s.name}` : s.name })),
        ] },
        { key: 'customerLevel', label: '客户等级', type: 'select', options: ['金牌', '银牌', '铜牌', '普通'] },
        { key: 'priceGroupCode', label: '价格组', type: 'select', options: [
          { value: '', label: '（空 - 取商品默认售价）' },
          ...customerPriceGroupOptions.value.map(p => ({ value: p.code, label: p.code ? `${p.code}  ${p.name}` : p.name })),
        ] },
        { key: 'creditLimit', label: '信用额度', type: 'number' },
        { key: 'invoiceTitle', label: '发票抬头' },
        { key: 'taxNo', label: '税号' },
        { key: 'longitude', label: '经度', type: 'number' },
        { key: 'latitude', label: '纬度', type: 'number' },
      ]
      if (props.mode === 'edit') {
        list.push({ key: 'status', label: '状态', type: 'select', options: [
          { value: 'NORMAL', label: '正常' },
          { value: 'STOPPED', label: '停用' },
        ] })
      }
      list.push({ key: 'shippingAddress', label: '收货地址（默认）', required: true, type: 'textarea', full: true })
      list.push({ key: 'addresses', label: '收货地址列表', type: 'addressesGrid', full: true })
      list.push({ key: 'settlement', label: '账期设置', type: 'settlement', full: true })
      return list
    }
    case 'supplier': {
      const list = [
        { key: 'supplierCode', label: '供应商编码', readonly: props.mode === 'edit' },
        { key: 'supplierName', label: '供应商名称', required: true },
        { key: 'shortName', label: '简称' },
        { key: 'supplierType', label: '供应商类型', type: 'select', options: ['普通供应商', '核心供应商', '临时供应商'] },
        { key: 'contactName', label: '联系人' },
        { key: 'phone', label: '电话' },
        { key: 'deliveryDays', label: '到货天数', type: 'number' },
        { key: 'defaultBuyer', label: '默认采购员', type: 'select', options: [{ value: '', label: '（未指定）' }, ...buyerOptions.value.map(b => ({ value: b.name, label: b.code ? `${b.code}  ${b.name}` : b.name }))] },
        { key: 'deliveryMethod', label: '交货方式', type: 'select', options: [
          ...(deliveryMethodOptions.value.length ? deliveryMethodOptions.value.map(d => ({ value: d.name, label: d.name })) : ['送货上门', '到厂自提', '物流站自提']),
        ] },
        { key: 'defaultLogisticsCompany', label: '默认物流公司', type: 'select', options: [{ value: '', label: '（未指定）' }, ...logisticsOptions.value.map(l => ({ value: l.name, label: l.name }))] },
      ]
      // 编辑模式追加状态字段（新建默认 NORMAL 不展示）
      if (props.mode === 'edit') {
        list.push({ key: 'status', label: '状态', type: 'select', options: [
          { value: 'NORMAL', label: '正常' },
          { value: 'STOPPED', label: '停用' },
        ] })
      }
      list.push({ key: 'settlement', label: '账期设置', type: 'settlement', full: true })
      list.push({ key: 'address', label: '地址', full: true })
      list.push({ key: 'remark', label: '备注', type: 'textarea', full: true })
      list.push({ key: 'bankAccounts', label: '收款账户', type: 'bankAccountsGrid', full: true })
      return list
    }
    case 'warehouse':
      return [
        { key: 'warehouseCode', label: '仓库编码' },
        { key: 'warehouseName', label: '仓库名称', required: true },
        { key: 'warehouseType', label: '仓库类型', type: 'select', options: ['实物仓', '虚拟仓'] },
        { key: 'inventoryType', label: '存货类型', type: 'select', options: ['平台仓库', '非平台仓'] },
        { key: 'costGroup', label: '成本分组' },
        { key: 'managerName', label: '负责人', type: 'select', options: [{ value: '', label: '（请选择）' }, ...employeeList.value.map(e => ({ value: e.name, label: e.code ? `${e.code}  ${e.name}` : e.name }))] },
      ]
    case 'unit':
      return [
        { key: 'unitCode', label: '单位编码' },
        { key: 'unitName', label: '单位名称', required: true },
        { key: 'canMiddleUnit', label: '是否中单位', type: 'checkbox' },
        { key: 'canLargeUnit', label: '是否大单位', type: 'checkbox' },
      ]
    case 'brand':
      return [
        { key: 'brandCode', label: '品牌编码' },
        { key: 'brandName', label: '品牌名称', required: true },
        { key: 'simpleCode', label: '简码' },
      ]
    case 'category': {
      const list = [
        { key: 'parentCode', label: '上级分类', type: 'select', options: [{ value: '', label: '（无 - 顶级分类）' }, ...categoryParents.value.map(c => ({ value: c.code, label: `${c.code}  ${c.name}` }))] },
        { key: 'categoryCode', label: '分类编号', required: true },
        { key: 'categoryName', label: '分类名称', required: true },
        { key: 'externalCode', label: '外部编码' },
        { key: 'taxRate', label: '税率', type: 'taxRate' },
      ]
      if (props.mode === 'edit') {
        list.push({ key: 'status', label: '状态', type: 'select', options: [
          { value: 'NORMAL', label: '正常' },
          { value: 'STOPPED', label: '停用' },
        ] })
      }
      return list
    }
    case 'territory':
      return [
        { key: 'parentCode', label: '上级片区', type: 'select', options: [
          { value: '', label: '（无 - 顶级片区）' },
          ...territoryParents.value
            .filter(t => t.code !== form.value.territoryCode)
            .map(t => ({ value: t.code, label: `${t.code}  ${t.name}` })),
        ] },
        { key: 'territoryCode', label: '片区编码' },
        { key: 'territoryName', label: '片区名称', required: true },
        { key: 'remark', label: '备注', type: 'textarea' },
      ]
    case 'routeLine': {
      const editing = props.mode === 'edit'
      const deliveryOptions = employeeList.value
        .filter(e => e.isDeliveryman && (e.status || 'NORMAL') === 'NORMAL')
        .map(e => ({ value: e.name, label: e.code ? `${e.code}  ${e.name}` : e.name }))
      return [
        { key: 'routeLineCode', label: '线路编码', required: !editing, readonly: editing, placeholder: editing ? '' : '请输入线路编码' },
        { key: 'routeLineName', label: '线路名称', required: true },
        { key: 'driver', label: '默认司机', type: 'select', options: [{ value: '', label: '（请选择配送员）' }, ...deliveryOptions] },
        { key: 'coverage', label: '覆盖范围' },
        { key: 'remark', label: '备注', type: 'textarea' },
      ]
    }
    case 'department':
      return [
        { key: 'departmentCode', label: '部门编码' },
        { key: 'departmentName', label: '部门名称', required: true },
        { key: 'parentCode', label: '上级部门', type: 'select', options: [{ value: '', label: '（无 - 顶级部门）' }, ...departmentParents.value.map(d => ({ value: d.code, label: `${d.code}  ${d.name}` }))] },
        { key: 'headCount', label: '人数', type: 'number' },
        { key: 'remark', label: '备注', type: 'textarea' },
      ]
    case 'expenseType': {
      // 上级下拉候选：
      // - 新建 或 原上级为空：全部（除自己）
      // - 编辑 且 原上级非空：仅"原上级的同级"（parentCode 相同的记录），保证不跨级
      const editing = props.mode === 'edit'
      const originalParent = editing ? (props.editData?._raw?.parentCode || '') : ''
      const selfCode = form.value.expenseTypeCode
      const isCrossLevelLocked = editing && originalParent !== ''
      // 找到「原上级」记录以取其 parentCode（= grandparent），同级=parentCode 相同
      const gp = isCrossLevelLocked
        ? (expenseTypeParents.value.find(t => t.code === originalParent)?.parentCode ?? '')
        : ''
      const parentOptions = isCrossLevelLocked
        ? expenseTypeParents.value.filter(t => t.code !== selfCode && (t.parentCode || '') === gp)
        : expenseTypeParents.value.filter(t => t.code !== selfCode)
      return [
        { key: 'parentCode', label: '上级费用类型', type: 'select',
          readonly: false,
          options: [
            { value: '', label: isCrossLevelLocked ? '（保持顶级不可切换）' : '（无 - 顶级费用类型）', disabled: isCrossLevelLocked },
            ...parentOptions.map(t => ({ value: t.code, label: `${t.code}  ${t.name}` })),
          ] },
        { key: 'expenseTypeCode', label: '费用类型编码', readonly: editing },
        { key: 'expenseTypeName', label: '费用类型名称', required: true },
        { key: 'direction', label: '费用方向', type: 'select', options: ['收入', '支出'] },
        { key: 'costParticipation', label: '成本参与', type: 'select', options: ['是', '否'] },
        { key: 'remark', label: '备注', type: 'textarea' },
      ]
    }
    case 'employee': {
      const list = [
        { key: 'employeeCode', label: '人员编码', placeholder: '留空自动生成 E+5位', readonly: props.mode === 'edit' },
        { key: 'employeeName', label: '姓名', required: true },
        { key: 'gender', label: '性别', type: 'select', options: ['男', '女'] },
        { key: 'ownerName', label: '所属货主', type: 'select', options: [{ value: '', label: '（请选择）' }, ...ownerList.value.map(o => ({ value: o.name, label: o.code ? `${o.code}  ${o.name}` : o.name }))] },
        { key: 'mobile', label: '手机号' },
        { key: 'idCard', label: '身份证号' },
        { key: 'education', label: '学历', type: 'select', options: ['', '初中', '高中', '中专', '大专', '本科', '硕士', '博士', '其他'] },
        { key: 'address', label: '联系地址' },
        { key: 'department', label: '部门', type: 'select', options: [{ value: '', label: '（请选择）' }, ...departmentList.value.map(d => ({ value: d.name, label: d.code ? `${d.code}  ${d.name}` : d.name }))] },
        { key: 'isSalesman', label: '是否业务员', type: 'checkbox' },
      ]
      // 是否业务员 = 是 → 展示业务员管理员 + 上级业务员
      if (form.value.isSalesman) {
        list.push({ key: 'isSalesmanAdmin', label: '业务员管理员', type: 'checkbox' })
        list.push({ key: 'parentSalesman', label: '上级业务员', type: 'select', options: [
          { value: '', label: '（无）' },
          ...employeeList.value.filter(e => e.isSalesman && e.name !== form.value.employeeName).map(e => ({ value: e.name, label: e.code ? `${e.code}  ${e.name}` : e.name })),
        ] })
      }
      list.push({ key: 'isBuyer', label: '是否采购员', type: 'checkbox' })
      list.push({ key: 'isWarehouseKeeper', label: '是否库管员', type: 'checkbox' })
      list.push({ key: 'isDeliveryman', label: '是否配送员', type: 'checkbox' })
      list.push({ key: 'remark', label: '备注', type: 'textarea' })
      return list
    }
    case 'owner':
      return [
        { key: 'ownerCode', label: '货主编码' },
        { key: 'ownerName', label: '货主名称', required: true },
        { key: 'ownerType', label: '货主类型', type: 'select', options: ['自营', '联营', '代销'] },
        { key: 'platform', label: '所属平台' },
        { key: 'remark', label: '备注', type: 'textarea' },
      ]
    case 'counterparty': {
      const list = [
        { key: 'counterpartyCode', label: '往来单位编码', readonly: props.mode === 'edit' },
        { key: 'counterpartyName', label: '往来单位名称', required: true },
        { key: 'typeCode', label: '单位类型', type: 'select', options: [
          { value: '', label: '（请选择）' },
          ...counterpartyTypeList.value.map(t => ({ value: t.code, label: t.code ? `${t.code}  ${t.name}` : t.name })),
        ] },
        { key: 'contactName', label: '联系人' },
        { key: 'phone', label: '电话' },
        { key: 'remark', label: '备注', type: 'textarea' },
      ]
      // 编辑时可修改状态；新建默认 NORMAL 不显示
      if (props.mode === 'edit') {
        list.push({ key: 'status', label: '状态', type: 'select', options: [
          { value: 'NORMAL', label: '正常' },
          { value: 'STOPPED', label: '停用' },
        ] })
      }
      return list
    }
    case 'counterpartyType':
      return [
        { key: 'typeCode', label: '编号', required: true, readonly: props.mode === 'edit' },
        { key: 'typeName', label: '类型名称', required: true },
      ]
    case 'fundAccount':
      return [
        { key: 'parentCode', label: '上级账户', required: true, type: 'select', options: [
          { value: '', label: '（请选择）' },
          ...fundAccountParents.value.map(p => ({ value: p.code, label: `${p.code}  ${p.name}` })),
        ] },
        { key: 'fundAccountCode', label: '账户编码', placeholder: '留空自动生成' },
        { key: 'fundAccountName', label: '账户名称', required: true },
        { key: 'accountType', label: '账户类型', type: 'select', options: ['现金', '银行账户', '在途', '第三方支付'] },
        { key: 'balance', label: '期初余额', type: 'number' },
        { key: 'remark', label: '备注', type: 'textarea' },
      ]
    case 'priceGroup':
      return [
        { key: 'priceGroupCode', label: '价格组编码', readonly: true },
        { key: 'priceGroupName', label: '价格组名称', required: true },
        { key: 'sortOrder', label: '排序', type: 'number' },
        { key: 'remark', label: '备注', type: 'textarea' },
      ]
    default:
      return []
  }
})

// 根据字段数量动态计算窗口高度：字段间距 32px
// height = header + 上下 padding + n × 字段行高 + (n-1) × 32 + textarea 额外高度
const boxHeight = computed(() => {
  if (!useFixedSize.value) return null
  const flds = fields.value
  const HEADER = 48       // 头部
  const PADDING_V = 40    // 20 top + 20 bottom
  const FIELD_H = 32      // 单行输入
  const TEXTAREA_EXTRA = 32 // textarea 相对普通输入多出的高度（min-height 64 vs 32）
  const GAP = 16          // 字段间距（原 32 过大，字段多时导致弹窗超屏）
  const n = flds.length
  const textareaCount = flds.filter(f => f.type === 'textarea').length
  const inner = n * FIELD_H + Math.max(0, n - 1) * GAP + textareaCount * TEXTAREA_EXTRA
  // 上下留 8px 视觉呼吸
  const desired = HEADER + PADDING_V + inner + 16
  // 视口高度上限：不超过 92vh；下限 320px
  const viewportCap = typeof window !== 'undefined' ? Math.floor(window.innerHeight * 0.92) : 900
  return Math.max(320, Math.min(desired, viewportCap))
})

const boxStyle = computed(() => useFixedSize.value ? { height: `${boxHeight.value}px`, maxHeight: `${boxHeight.value}px` } : null)

// 税率输入：只允许 0-99 的正整数
function onTaxRateInput(event, key) {
  const raw = String(event.target.value ?? '')
  // 剥掉非数字（含小数点/负号）
  let digits = raw.replace(/\D+/g, '')
  if (digits.length > 2) digits = digits.slice(0, 2)
  const num = digits === '' ? '' : parseInt(digits, 10)
  form.value[key] = num
  // 强制回写到 input 元素，避免用户输了 "9.5" 但 v-model.number 保留了值
  if (event.target.value !== String(num)) event.target.value = String(num ?? '')
}

// ==================== 往来单位子表操作 ====================
function ensureArr(key) {
  if (!Array.isArray(form.value[key])) form.value[key] = []
  return form.value[key]
}
function addBankAccount() {
  ensureArr('bankAccounts').push({ accountName: '', bankName: '', bankAccountNo: '', branchName: '', isDefault: false, remark: '' })
}
function removeBankAccount(i) { ensureArr('bankAccounts').splice(i, 1) }
function setBankDefault(i) {
  const list = ensureArr('bankAccounts')
  list.forEach((r, idx) => { r.isDefault = idx === i })
}
function addInvoiceInfo() {
  ensureArr('invoiceInfos').push({ invoiceTitle: '', taxNo: '', bankName: '', bankAccountNo: '', address: '', phone: '', isDefault: false })
}
function removeInvoiceInfo(i) { ensureArr('invoiceInfos').splice(i, 1) }
function setInvoiceDefault(i) {
  const list = ensureArr('invoiceInfos')
  list.forEach((r, idx) => { r.isDefault = idx === i })
}

// 打开时（编辑/查看模式）拉详情以填充子表
watch(() => props.visible, async (v) => {
  if (!v) return
  if (props.moduleCode === 'counterparty' && (props.mode === 'edit' || props.mode === 'view') && props.editData) {
    const code = props.editData._raw?.counterpartyCode || props.editData.c0 || ''
    if (!code) return
    try {
      const detail = await post('/base/master/counterparty/detail', { counterpartyCode: code })
      form.value.bankAccounts = detail.bankAccounts || []
      form.value.invoiceInfos = detail.invoiceInfos || []
    } catch (e) { /* 忽略：仍可编辑主档 */ }
  }
})
</script>

<template>
  <div v-show="visible" class="drawer-overlay" :class="isSideDrawer ? 'drawer-lite' : (useModal ? 'modal-lite' : 'drawer-lite')" @click.self="closeDrawer">
    <div class="modal-lite-box" :class="{ 'modal-fixed-480x720': useFixedSize, 'modal-small': isSmallModal, 'counterparty-drawer': isSideDrawer, 'supplier-drawer': props.moduleCode === 'supplier' || props.moduleCode === 'customer' }" :style="boxStyle">
      <div class="modal-lite-head">
        <b>{{ title }}</b>
        <div class="actions">
          <button class="btn" @click="closeDrawer">{{ isReadonly ? '关闭' : '取消' }}</button>
          <button v-if="!isReadonly" class="btn primary" @click="save">保存</button>
        </div>
      </div>

      <div class="modal-lite-body form-body-480">
        <div class="form-stack">
          <div v-for="f in fields" :key="f.key" class="field" :class="{ 'field-full': f.full }">
            <label>
              {{ f.label }}
              <span v-if="f.required" style="color:var(--danger)">*</span>
            </label>
            <select v-if="f.type === 'select'" v-model="form[f.key]" :disabled="isReadonly || f.readonly">
              <option
                v-for="opt in f.options"
                :key="typeof opt === 'object' ? opt.value : opt"
                :value="typeof opt === 'object' ? opt.value : opt"
                :disabled="typeof opt === 'object' && !!opt.disabled"
              >{{ typeof opt === 'object' ? opt.label : opt }}</option>
            </select>
            <div v-else-if="f.type === 'taxRate'" class="tax-rate-input">
              <input
                v-model.number="form[f.key]"
                type="number"
                inputmode="numeric"
                min="0"
                max="99"
                step="1"
                :readonly="isReadonly"
                @input="onTaxRateInput($event, f.key)"
              />
              <span class="suffix">%</span>
            </div>
            <label v-else-if="f.type === 'checkbox'" class="checkbox-cell">
              <input type="checkbox" v-model="form[f.key]" :disabled="isReadonly" />
              <span v-if="props.moduleCode === 'unit'">勾选后可作为{{ f.label.includes('中') ? '中' : '大' }}单位引用</span>
              <span v-else>{{ form[f.key] ? '是' : '否' }}</span>
            </label>
            <input v-else-if="f.type === 'number'" v-model.number="form[f.key]" type="number" :readonly="isReadonly || f.readonly" />
            <textarea v-else-if="f.type === 'textarea'" v-model="form[f.key]" :placeholder="f.label" rows="3" :readonly="isReadonly || f.readonly"></textarea>
            <!-- 账期设置面板（客户/供应商共用） -->
            <SettlementPanel
              v-else-if="f.type === 'settlement'"
              :model-value="{
                settlementType: form.settlementType || 'TERM',
                termType: form.termType,
                termDays: form.termDays,
                cutoffDay: form.cutoffDay,
                paymentMode: form.paymentMode,
                termMonths: form.termMonths,
                paymentDay: form.paymentDay,
              }"
              :readonly="isReadonly"
              :role="props.moduleCode === 'supplier' ? 'supplier' : 'customer'"
              @update:model-value="(v) => Object.assign(form, v)"
            />
            <!-- 供应商收款账户子网格 -->
            <div v-else-if="f.type === 'bankAccountsGrid'" class="bank-grid-wrap">
              <div class="bank-grid-head">
                <span>收款账户（多条）</span>
                <button v-if="!isReadonly" type="button" class="btn" @click="addSupplierBank">+ 添加账户</button>
              </div>
              <table class="bank-grid">
                <thead>
                  <tr>
                    <th style="width:36px">#</th>
                    <th style="min-width:110px">户名 <span class="req">*</span></th>
                    <th style="min-width:130px">开户银行</th>
                    <th style="min-width:150px">账号 <span class="req">*</span></th>
                    <th style="min-width:130px">支行</th>
                    <th style="width:60px">默认</th>
                    <th style="min-width:120px">备注</th>
                    <th v-if="!isReadonly" style="width:50px"></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(b, i) in (form[f.key] || [])" :key="i">
                    <td class="c">{{ i + 1 }}</td>
                    <td><input v-model="b.accountName" :readonly="isReadonly" /></td>
                    <td><input v-model="b.bankName" :readonly="isReadonly" /></td>
                    <td><input v-model="b.bankAccount" :readonly="isReadonly" /></td>
                    <td><input v-model="b.branch" :readonly="isReadonly" /></td>
                    <td class="c">
                      <input type="checkbox" :checked="!!b.isDefault" :disabled="isReadonly" @change="setSupplierBankDefault(i, $event.target.checked)" />
                    </td>
                    <td><input v-model="b.remark" :readonly="isReadonly" /></td>
                    <td v-if="!isReadonly" class="c">
                      <button type="button" class="btn danger" @click="removeSupplierBank(i)">×</button>
                    </td>
                  </tr>
                  <tr v-if="!(form[f.key] || []).length">
                    <td :colspan="isReadonly ? 7 : 8" class="empty">暂无收款账户，点击右上角【+ 添加账户】</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <!-- 客户地址子网格 -->
            <div v-else-if="f.type === 'addressesGrid'" class="bank-grid-wrap">
              <div class="bank-grid-head">
                <span>收货地址（多条）</span>
                <button v-if="!isReadonly" type="button" class="btn" @click="addCustomerAddress">+ 添加地址</button>
              </div>
              <table class="bank-grid">
                <thead>
                  <tr>
                    <th style="width:36px">#</th>
                    <th style="min-width:100px">地址别名</th>
                    <th style="min-width:80px">联系人</th>
                    <th style="min-width:110px">电话</th>
                    <th style="min-width:200px">详细地址 <span class="req">*</span></th>
                    <th style="width:80px">经度</th>
                    <th style="width:80px">纬度</th>
                    <th style="width:60px">默认</th>
                    <th style="min-width:100px">备注</th>
                    <th v-if="!isReadonly" style="width:50px"></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(a, i) in (form[f.key] || [])" :key="i">
                    <td class="c">{{ i + 1 }}</td>
                    <td><input v-model="a.addressName" :readonly="isReadonly" /></td>
                    <td><input v-model="a.contactName" :readonly="isReadonly" /></td>
                    <td><input v-model="a.contactMobile" :readonly="isReadonly" /></td>
                    <td><input v-model="a.detailAddress" :readonly="isReadonly" /></td>
                    <td><input v-model="a.longitude" type="number" step="0.0000001" :readonly="isReadonly" /></td>
                    <td><input v-model="a.latitude" type="number" step="0.0000001" :readonly="isReadonly" /></td>
                    <td class="c">
                      <input type="checkbox" :checked="!!a.isDefault" :disabled="isReadonly" @change="setCustomerAddressDefault(i, $event.target.checked)" />
                    </td>
                    <td><input v-model="a.remark" :readonly="isReadonly" /></td>
                    <td v-if="!isReadonly" class="c">
                      <button type="button" class="btn danger" @click="removeCustomerAddress(i)">×</button>
                    </td>
                  </tr>
                  <tr v-if="!(form[f.key] || []).length">
                    <td :colspan="isReadonly ? 9 : 10" class="empty">暂无地址，点击右上角【+ 添加地址】</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <input v-else v-model="form[f.key]" :placeholder="f.label" :readonly="isReadonly || f.readonly" />
          </div>
        </div>

        <!-- 往来单位子表：银行账户 + 开票信息 -->
        <template v-if="props.moduleCode === 'counterparty'">
          <div class="sub-block">
            <div class="sub-head">
              <b>银行账户</b>
              <div class="sub-actions">
                <button class="btn primary" @click="addBankAccount" :disabled="isReadonly" type="button">新增银行账户</button>
              </div>
            </div>
            <div class="sub-scroll">
              <table class="sub-grid" style="min-width: 780px">
                <colgroup>
                  <col style="width:36px" />
                  <col style="width:120px" />
                  <col style="width:140px" />
                  <col style="width:150px" />
                  <col style="width:120px" />
                  <col style="width:56px" />
                  <col />
                  <col style="width:56px" />
                </colgroup>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>户名</th>
                    <th>开户银行</th>
                    <th>银行账号</th>
                    <th>支行</th>
                    <th>默认</th>
                    <th>备注</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(r, i) in form.bankAccounts || []" :key="i">
                    <td class="c">{{ i + 1 }}</td>
                    <td><input v-model="r.accountName" placeholder="户名" :readonly="isReadonly" /></td>
                    <td><input v-model="r.bankName" placeholder="开户银行" :readonly="isReadonly" /></td>
                    <td><input v-model="r.bankAccountNo" placeholder="银行账号" :readonly="isReadonly" /></td>
                    <td><input v-model="r.branchName" placeholder="支行" :readonly="isReadonly" /></td>
                    <td class="c"><input type="radio" name="bank-default" :checked="!!r.isDefault" @change="setBankDefault(i)" :disabled="isReadonly" /></td>
                    <td><input v-model="r.remark" placeholder="备注" :readonly="isReadonly" /></td>
                    <td class="c"><button class="link link-btn danger-link" @click="removeBankAccount(i)" :disabled="isReadonly" type="button">删除</button></td>
                  </tr>
                  <tr v-if="!(form.bankAccounts && form.bankAccounts.length)"><td colspan="8" class="empty">暂无银行账户，点击「新增银行账户」或在列表页顶部「导入 → 导入银行账号」</td></tr>
                </tbody>
              </table>
            </div>
          </div>

          <div class="sub-block">
            <div class="sub-head">
              <b>开票信息</b>
              <div class="sub-actions">
                <button class="btn primary" @click="addInvoiceInfo" :disabled="isReadonly" type="button">新增开票信息</button>
              </div>
            </div>
            <div class="sub-scroll">
              <table class="sub-grid" style="min-width: 900px">
                <colgroup>
                  <col style="width:36px" />
                  <col style="width:150px" />
                  <col style="width:130px" />
                  <col style="width:140px" />
                  <col style="width:140px" />
                  <col />
                  <col style="width:110px" />
                  <col style="width:56px" />
                  <col style="width:56px" />
                </colgroup>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>发票抬头</th>
                    <th>税号</th>
                    <th>开户行</th>
                    <th>银行账号</th>
                    <th>地址</th>
                    <th>电话</th>
                    <th>默认</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(r, i) in form.invoiceInfos || []" :key="i">
                    <td class="c">{{ i + 1 }}</td>
                    <td><input v-model="r.invoiceTitle" placeholder="发票抬头" :readonly="isReadonly" /></td>
                    <td><input v-model="r.taxNo" placeholder="税号" :readonly="isReadonly" /></td>
                    <td><input v-model="r.bankName" placeholder="开户行" :readonly="isReadonly" /></td>
                    <td><input v-model="r.bankAccountNo" placeholder="银行账号" :readonly="isReadonly" /></td>
                    <td><input v-model="r.address" placeholder="地址" :readonly="isReadonly" /></td>
                    <td><input v-model="r.phone" placeholder="电话" :readonly="isReadonly" /></td>
                    <td class="c"><input type="radio" name="inv-default" :checked="!!r.isDefault" @change="setInvoiceDefault(i)" :disabled="isReadonly" /></td>
                    <td class="c"><button class="link link-btn danger-link" @click="removeInvoiceInfo(i)" :disabled="isReadonly" type="button">删除</button></td>
                  </tr>
                  <tr v-if="!(form.invoiceInfos && form.invoiceInfos.length)"><td colspan="9" class="empty">暂无开票信息，点击「新增开票信息」或在列表页顶部「导入 → 导入发票信息」</td></tr>
                </tbody>
              </table>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 480 宽定尺弹窗，高度按字段数动态设置（通过内联 style） */
.modal-fixed-480x720 {
  width: 480px !important;
  max-width: 480px !important;
  display: flex;
  flex-direction: column;
}
/* 小弹窗：宽度更紧凑，用于字段极少的场景（如往来单位类型） */
.modal-fixed-480x720.modal-small {
  width: 400px !important;
  max-width: 400px !important;
}
.modal-fixed-480x720.modal-small .form-body-480 {
  padding: 16px 24px;
}
.modal-fixed-480x720.modal-small .form-stack .field {
  grid-template-columns: 76px minmax(0, 1fr);
}
.modal-fixed-480x720 .modal-lite-head {
  flex: 0 0 auto;
  height: 48px;
}
.modal-fixed-480x720 .form-body-480 {
  flex: 1 1 auto;
  padding: 20px 32px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}
/* 字段竖向居中均匀排布，字段间距 32px */
.form-stack {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 16px;
  min-height: 100%;
}
.form-stack .field {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 4px 12px;
  align-items: center;
}
.form-stack .field label {
  text-align: right;
  font-size: 13px;
  color: #303133;
  font-weight: 600;
  margin: 0;
}
.form-stack .field input,
.form-stack .field select,
.form-stack .field textarea {
  height: 32px;
  padding: 0 10px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  font-size: 13px;
  outline: none;
  min-width: 0;
  width: 100%;
  background: #fff;
}
.form-stack .field input:focus,
.form-stack .field select:focus,
.form-stack .field textarea:focus {
  border-color: var(--primary);
}
.form-stack .field input[readonly],
.form-stack .field select:disabled,
.form-stack .field textarea[readonly] {
  background: #f5f7fa;
  color: #606266;
  cursor: not-allowed;
}
.form-stack .field textarea {
  height: auto;
  min-height: 64px;
  padding: 6px 10px;
  resize: vertical;
  line-height: 1.5;
  align-self: start;
}
.form-stack .field:has(textarea) label {
  align-self: start;
  margin-top: 6px;
}
.field-full {
  grid-column: 1 / -1;
}
/* 税率输入：数字 + % 后缀 */
.tax-rate-input {
  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
}
.tax-rate-input input {
  width: 100%;
  padding-right: 28px;
}
.tax-rate-input input::-webkit-outer-spin-button,
.tax-rate-input input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}
.tax-rate-input input[type=number] {
  -moz-appearance: textfield;
}
.tax-rate-input .suffix {
  position: absolute;
  right: 10px;
  color: #909399;
  font-size: 13px;
  pointer-events: none;
}
/* 勾选控件 */
.form-stack .field .checkbox-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  color: #606266;
  font-weight: 400;
  text-align: left;
  margin: 0;
}
.form-stack .field .checkbox-cell input[type=checkbox] {
  width: 16px;
  height: 16px;
  margin: 0;
  padding: 0;
  cursor: pointer;
  accent-color: var(--primary);
}
.form-stack .field .checkbox-cell input[type=checkbox]:disabled {
  cursor: not-allowed;
}

/* ============ 往来单位右侧抽屉专用样式 ============ */
.counterparty-drawer .modal-lite-body {
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
}
.counterparty-drawer .form-stack {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px 20px;
  flex: 0 0 auto;
  min-height: auto;
  justify-content: initial;
}
.counterparty-drawer .form-stack .field {
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 4px 12px;
}
.counterparty-drawer .form-stack .field:has(textarea) {
  grid-column: 1 / -1;
}

/* ============ 供应商右侧抽屉：比默认宽 200px，两列布局 ============ */
.drawer-overlay.drawer-lite .modal-lite-box.supplier-drawer {
  width: min(1020px, 92vw);
}
.supplier-drawer .modal-lite-body {
  padding: 16px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
}
.supplier-drawer .form-stack {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px 24px;
  flex: 0 0 auto;
  min-height: auto;
  justify-content: initial;
}
.supplier-drawer .form-stack .field {
  grid-template-columns: 108px minmax(0, 1fr);
  gap: 4px 12px;
}
/* 大字段（地址/备注/收款账户网格）占满整行 */
.supplier-drawer .form-stack .field:has(textarea),
.supplier-drawer .form-stack .field-full {
  grid-column: 1 / -1;
}
.sub-block {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  padding: 10px 12px;
  min-width: 0;
  overflow: hidden;
}
.sub-block .sub-scroll {
  overflow-x: auto;
  min-width: 0;
}
.sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.sub-head b { font-size: 13px; color: #303133; }
.sub-actions { display: flex; gap: 6px; }
.sub-actions .btn { padding: 4px 10px; font-size: 12px; cursor: pointer; }
.sub-grid {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  table-layout: fixed;
}
.sub-grid th, .sub-grid td {
  border: 1px solid #e5e7eb;
  padding: 4px 6px;
  vertical-align: middle;
}
.sub-grid th {
  background: #f8fafc;
  font-weight: 600;
  color: #303133;
  text-align: left;
}
.sub-grid td.c { text-align: center; }
.sub-grid td .empty { color: #909399; padding: 12px; text-align: center; }
.sub-grid td.empty { text-align: center; color: #909399; padding: 14px; }
.sub-grid input[type=text],
.sub-grid input:not([type]) {
  width: 100%;
  border: 0;
  outline: none;
  padding: 4px 4px;
  font-size: 12px;
  background: transparent;
}
.sub-grid input:focus { background: #eff6ff; }

/* 供应商收款账户子网格 */
.bank-grid-wrap {
  border: 1px solid var(--line);
  border-radius: 6px;
  overflow: hidden;
  background: #fff;
}
.bank-grid-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  background: #f5f8fb;
  font-size: 12px;
  color: #303133;
  font-weight: 600;
}
.bank-grid-head .btn {
  height: 26px;
  padding: 0 10px;
  font-size: 12px;
}
.bank-grid {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.bank-grid th,
.bank-grid td {
  border-top: 1px solid var(--line);
  padding: 3px 6px;
  vertical-align: middle;
}
.bank-grid th {
  background: #fafbfc;
  color: #606266;
  font-weight: 600;
  text-align: left;
}
.bank-grid td.c { text-align: center; color: #909399; }
.bank-grid input {
  width: 100%;
  border: 1px solid transparent;
  padding: 3px 6px;
  border-radius: 4px;
  background: transparent;
}
.bank-grid input:focus {
  background: #eff6ff;
  border-color: #d1e3fa;
}
.bank-grid .empty {
  text-align: center;
  color: #909399;
  padding: 14px;
  font-size: 12px;
}
.bank-grid .req { color: #f56c6c; }
.bank-grid .btn.danger {
  height: 22px;
  padding: 0 6px;
  font-size: 12px;
  line-height: 20px;
}
</style>
