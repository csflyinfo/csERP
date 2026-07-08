<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import QueryBar from '../components/QueryBar.vue'
import ProTable from '../components/ProTable.vue'
import GoodsDrawer from './goods/GoodsDrawer.vue'
import BatchEditDrawer from './goods/components/BatchEditDrawer.vue'
import BillDrawer from '../components/BillDrawer.vue'
import FundBillDrawer from '../components/FundBillDrawer.vue'
import BaseInfoDrawer from '../components/BaseInfoDrawer.vue'
import { get, post, upload, downloadBlob, saveBlobFile, saveTextFile } from '../api/client.js'
import { usePermission } from '../composables/usePermission.js'
import { mapRecordToRow, moduleApis, excelModules } from '../module-api.js'
import { moduleConfigs } from '../module-config.js'

const route = useRoute()
const moduleCode = computed(() => route.meta?.module || '')
const config = computed(() => moduleConfigs[moduleCode.value] || {})
const roleCode = 'ADMIN'

// 基础资料模块（用于列表操作列显示"编辑 删除"）
const BASE_MODULES = ['goods', 'customer', 'supplier', 'warehouse', 'unit', 'brand', 'category', 'priceGroup', 'territory', 'routeLine', 'employee', 'department', 'owner', 'expenseType', 'counterparty', 'fundAccount']
const isBaseModule = computed(() => BASE_MODULES.includes(moduleCode.value))

// 商品编辑抽屉
const showGoodsDrawer = ref(false)
const drawerMode = ref('add') // add/edit
const editGoodsData = ref(null)

// 业务单据抽屉（采购订单/销售订单）
const showBillDrawer = ref(false)
const billDrawerMode = ref('add')
const billDrawerCode = ref('')
const billEditData = ref(null)

function openBillDrawer(mode, code, rowData = null) {
  billDrawerMode.value = mode
  billDrawerCode.value = code
  billEditData.value = rowData
  showBillDrawer.value = true
}
function closeBillDrawer() {
  showBillDrawer.value = false
}
function onBillSave(result) {
  showBillDrawer.value = false
  loadRows()
  show(`${moduleConfigs[billDrawerCode.value]?.title || '单据'}保存成功`)
}

// 资金单据抽屉（收付款/核销）
const showFundDrawer = ref(false)
const fundBillType = ref('receipt')
const fundEditData = ref(null)

function openFundDrawer(type, rowData = null) {
  fundBillType.value = type
  fundEditData.value = rowData
  showFundDrawer.value = true
}
function closeFundDrawer() {
  showFundDrawer.value = false
}
function onFundSave(result) {
  showFundDrawer.value = false
  loadRows()
  show('资金操作成功')
}

// 基础资料抽屉
const showBaseDrawer = ref(false)
const baseDrawerCode = ref('')
const baseDrawerMode = ref('add')
const baseEditData = ref(null)

function openBaseDrawer(mode, code, rowData = null) {
  baseDrawerMode.value = mode
  baseDrawerCode.value = code
  baseEditData.value = rowData
  showBaseDrawer.value = true
}
function closeBaseDrawer() {
  showBaseDrawer.value = false
}
async function onBaseSave(result) {
  showBaseDrawer.value = false
  show(`${moduleConfigs[baseDrawerCode.value]?.title || '资料'}保存成功`)
  await loadRows()
}

// 批量编辑
const showBatchEditDrawer = ref(false)
const selectedRows = ref([])
const selectedRowKeys = ref(new Set())

// 暴露给父组件：打开新增抽屉
function openAddDrawer() {
  drawerMode.value = 'add'
  editGoodsData.value = null
  showGoodsDrawer.value = true
}

// 暴露给父组件：打开编辑抽屉
function openEditDrawer(rowData) {
  drawerMode.value = 'edit'
  editGoodsData.value = rowData || null
  showGoodsDrawer.value = true
}

defineExpose({ openAddDrawer, openEditDrawer })

const { loadFieldScope, canViewField } = usePermission()
const feedback = ref('')
const dialog = ref(null)
const selectedRow = ref(null)
const detailData = ref(null)
const formModel = ref({})
const tableRows = ref([])
const loading = ref(false)
const selectedTreeNode = ref('全部')
const dynamicTree = ref([])   // 动态加载的树节点 [{ code, name, level, hasChildren }]
const columnSettings = ref({})
const pageNo = ref(1)
const pageSize = ref(20)
const total = ref(0)
const queryFilters = ref({})
const sortField = ref('')
const sortOrder = ref('')

const columns = computed(() => config.value.columns.map((title, index) => ({
  key: `c${index}`,
  title,
  num: /金额|数量|库存|单价|成本|余额|已收|未收|已付|未付|原价|现价|进价|税额|毛利|额度/.test(title),
})))
const visibleColumns = computed(() => columns.value.filter(col => (columnSettings.value[col.key] !== false || /操作/.test(col.title)) && canViewField(moduleCode.value, col.title)))
const fieldSettingKey = computed(() => `erp-field-setting:${moduleCode.value}`)

const actionColumnIndex = computed(() => config.value.columns.findIndex(title => /操作/.test(title)))
const statusColumnIndex = computed(() => config.value.columns.findIndex(title => /状态|核销状态|应付生成状态|应收生成|开票状态|勾稽状态/.test(title)))

function buildRow(values = config.value.row || []) {
  return Object.fromEntries((values || []).map((value, index) => [`c${index}`, value]))
}

async function loadRows() {
  const api = moduleApis[moduleCode.value]
  if (!api?.page) {
    tableRows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const data = await post(api.page, { pageNo: pageNo.value, pageSize: pageSize.value, sortField: sortField.value, sortOrder: sortOrder.value, filters: { ...queryFilters.value, roleCode: roleCode } })
    tableRows.value = data.records?.length ? data.records.map(record => mapRecordToRow(record, config.value)) : []
    total.value = data.total || 0
<<<<<<< HEAD
    // 分类模块同步刷新树
    if (moduleCode.value === 'category') {
      buildCategoryTree(data.records || [])
    }
=======
>>>>>>> 883115edf03d9c00556132be1a3b8459bb99e146
  } catch (error) {
    tableRows.value = []
    total.value = 0
    show(`${config.value.title}加载失败：${error.message}`)
  } finally {
    loading.value = false
  }
}

// 从扁平分类列表构建树形展示（父->子）
function buildCategoryTree(records) {
  if (!records || records.length === 0) {
    dynamicTree.value = []
    return
  }
  // 用 categoryCode 做映射，parentCode 关联父节点
  const map = {}
  records.forEach(r => {
    map[r.categoryCode] = { code: r.categoryCode, name: r.categoryName, parentCode: r.parentCode || '', children: [] }
  })
  const roots = []
  Object.values(map).forEach(node => {
    if (node.parentCode && map[node.parentCode]) {
      map[node.parentCode].children.push(node)
    } else {
      roots.push(node)
    }
  })
  // 按 code 排序
  const sortRec = (list) => {
    list.sort((a, b) => (a.code || '').localeCompare(b.code || ''))
    list.forEach(n => sortRec(n.children))
  }
  sortRec(roots)
  // 展平为带缩进的显示节点
  const flat = [{ code: '', name: '全部分类', level: 0 }]
  const walk = (nodes, level) => {
    nodes.forEach(n => {
      flat.push({ code: n.code, name: n.name, level })
      if (n.children.length) walk(n.children, level + 1)
    })
  }
  walk(roots, 1)
  dynamicTree.value = flat
}

function resetRows() {
  loadRows()
}

function loadColumnSettings() {
  const saved = localStorage.getItem(fieldSettingKey.value)
  const parsed = saved ? JSON.parse(saved) : {}
  columnSettings.value = Object.fromEntries(columns.value.map(col => [col.key, parsed[col.key] !== false]))
}

function saveColumnSettings() {
  localStorage.setItem(fieldSettingKey.value, JSON.stringify(columnSettings.value))
  show(`${config.value.title}字段设置已保存`)
  closeDialog()
}

function resetColumnSettings() {
  localStorage.removeItem(fieldSettingKey.value)
  loadColumnSettings()
  show(`${config.value.title}字段设置已恢复默认`)
}

watch(() => [config.value, roleCode], () => { loadColumnSettings(); loadFieldScope(moduleCode.value, roleCode); resetRows() }, { immediate: true })

const formFields = computed(() => {
  if (config.value.formFields?.length) return config.value.formFields
  const ignored = /操作|商品数|当前库存|库存金额|应收余额|应付余额|逾期金额|已入库|已收|未收|已付|未付|创建|审核|状态|付款状态|到货状态|签收状态|开票状态|勾稽状态|核销状态/
  const fromColumns = config.value.columns.filter(title => !ignored.test(title)).slice(0, 12)
  return fromColumns.length ? fromColumns : (config.value.sections || ['基础信息'])
})

const detailColumns = computed(() => {
  if (config.value.detailColumns?.length) return config.value.detailColumns
  if (config.value.mode === 'bill' || config.value.type !== 'base') {
    return config.value.columns.filter(title => !/操作|状态|创建|审核/.test(title)).slice(0, 10)
  }
  return []
})

function show(message) {
  feedback.value = message
  setTimeout(() => (feedback.value = ''), 2000)
}

// 商品保存后处理：从数据库重新加载列表，保证显示真实持久化的数据
async function handleGoodsSave(goodsData) {
  console.log('handleGoodsSave:', goodsData)
  show('商品保存成功')
  showGoodsDrawer.value = false
  await loadRows()
}

// 将商品对象转换为列表行格式
function buildGoodsRow(data) {
  const u = data.units?.[0] || {}
  const saleFlag = data.canSale ? '是' : '否'
  const purchaseFlag = data.canPurchase ? '是' : '否'
  const returnFlag = data.canReturn ? '是' : '否'
  return {
    c0: '-',
    c1: data.goodsCode || `GD${String(tableRows.value.length + 1).padStart(3, '0')}`,
    c2: data.goodsName || '',
    c3: data.goodsType || '正常商品',
    c4: data.spec || '',
    c5: data.categoryName || '',
    c6: data.brandName || '',
    c7: u.unitName || '瓶',
    c8: u.barcode || '',
    c9: data.shelfLifeDays ? `${data.shelfLifeDays}天` : '',
    c10: data.storageProperty || '常温',
    c11: data.standardPrice || '0.00',
    c12: data.suggestedRetailPrice || '0.00',
    c13: data.referencePurchasePrice || '0.00',
    c14: data.minSalePrice || '0.00',
    c15: data.stockUpperLimit || '0',
    c16: data.stockLowerLimit || '0',
    c17: data.defaultSupplier || '',
    c18: data.defaultWarehouse || '总仓',
    c19: `${saleFlag}/${purchaseFlag}/${returnFlag}`,
    c20: '0',
    c21: data.status || '正常',
    c22: '编辑',
  }
}

// ========== 批量编辑相关 ==========
// 打开批量编辑弹窗
function openBatchEdit() {
  if (selectedRowKeys.value.size === 0) {
    show('请先选择商品')
    return
  }
  selectedRows.value = tableRows.value.filter((row, index) => selectedRowKeys.value.has(index))
  showBatchEditDrawer.value = true
}

// 切换单行勾选
function toggleRowSelection(index, checked) {
  if (checked) selectedRowKeys.value.add(index)
  else selectedRowKeys.value.delete(index)
}

// 全选/取消全选
function toggleSelectAll(checked) {
  if (checked) tableRows.value.forEach((_, index) => selectedRowKeys.value.add(index))
  else selectedRowKeys.value.clear()
}

// 批量保存
function handleBatchSave(updateData) {
  selectedRows.value.forEach(row => Object.assign(row, updateData))
  show(`批量更新完成，共更新 ${selectedRows.value.length} 个商品`)
  showBatchEditDrawer.value = false
  selectedRowKeys.value.clear()
}

function openDialog(type, title, message, row = null) {
  selectedRow.value = row
  formModel.value = type === 'form' ? initFormModel(row) : {}
  dialog.value = { type, title, message }
}

function closeDialog() {
  dialog.value = null
  selectedRow.value = null
  detailData.value = null
  formModel.value = {}
}

function initFormModel(row) {
  return Object.fromEntries(formFields.value.map(field => [field, valueByTitle(field, row)]))
}

function valueByTitle(title, row = selectedRow.value) {
  const index = columns.value.findIndex(col => col.title === title)
  return index >= 0 ? row?.[`c${index}`] || '' : ''
}

async function saveForm() {
  const api = moduleApis[moduleCode.value]
  const endpoint = dialog.value?.title?.includes('编辑') && api?.update ? api.update : api?.save
  if (endpoint) {
    try {
      await post(endpoint, buildPayload())
      await loadRows()
      show(`${dialog.value.title}保存成功`)
      closeDialog()
      return
    } catch (error) {
      show(`${dialog.value.title}保存失败：${error.message}`)
      return
    }
  }
  show('该模块暂不支持保存')
  closeDialog()
}

async function confirmAction() {
  const action = dialog.value?.title || '操作'
  const api = moduleApis[moduleCode.value]
  const endpoint = resolveEndpoint(api, action)
  if (endpoint) {
    try {
      await post(endpoint, buildPayload())
      await loadRows()
      show(`${config.value.title}${action}成功`)
      closeDialog()
      return
    } catch (error) {
      show(`${action}失败：${error.message}`)
      return
    }
  }
  // 无后端接口时前端状态变更
  if (selectedRow.value && statusColumnIndex.value >= 0) {
    if (/反审核/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '待审核'
    else if (/审核|确认/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '已审核'
    if (/停用|作废|终止/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = action.replace('确认', '')
    if (/冻结/.test(action) && !/解冻/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '冻结'
    if (/解冻/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '正常'
    if (/关闭/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '已关闭'
    if (/删除/.test(action)) tableRows.value = tableRows.value.filter(row => row !== selectedRow.value)
    if (/核销/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '已核销'
  }
  show(`${config.value.title}${action}成功`)
  closeDialog()
}

function resolveEndpoint(api, action) {
  if (/反审核/.test(action)) return api?.reverseAudit
  if (/关闭/.test(action)) return api?.close
  if (/删除/.test(action)) return api?.delete
  if (/解冻/.test(action)) return api?.unfreeze
  if (/冻结/.test(action)) return api?.freeze
  if (/核销/.test(action)) return api?.reconcile
  if (/停用/.test(action)) return api?.stop
  if (/作废/.test(action)) return api?.cancel
  return api?.audit
}

async function openDetail(action, row) {
  const api = moduleApis[moduleCode.value]
  detailData.value = null
  if (api?.detail && row?.c0) {
    try {
      const separator = api.detail.includes('?') ? '&' : '?'
      detailData.value = await get(`${api.detail}${separator}orderId=${encodeURIComponent(row.c0)}`)
    } catch (error) {
      show(`${config.value.title}详情接口暂不可用，已显示列表字段`)
    }
  }
  openDialog('view', action, detailData.value ? `${config.value.title}详情已加载` : `${config.value.title}详情`, row)
}

const importFile = ref(null)

async function uploadImport() {
  const excelApi = excelModules[moduleCode.value]
  if (excelApi?.import && importFile.value) {
    try {
      const formData = new FormData()
      formData.append('file', importFile.value)
      formData.append('taskName', `${config.value.title}导入任务`)
      const result = await upload(excelApi.import, formData)
      show(result?.message || `导入完成：成功${result?.successRows ?? 0}行，失败${result?.failedRows ?? 0}行`)
      if (moduleCode.value === 'importList') await loadRows()
      loadRows()
    } catch (error) {
      show(`${config.value.title}导入失败：${error.message}`)
    }
  } else {
    const api = moduleApis[moduleCode.value]
    if (api?.import) {
      try {
        const result = await post(api.import, { ...buildPayload(), taskName: `${config.value.title}导入任务`, fileName: `${config.value.title}导入模板.xlsx` })
        show(result?.message || `导入完成：成功${result?.successRows ?? 0}行，失败${result?.failedRows ?? 0}行`)
        if (moduleCode.value === 'importList') await loadRows()
      } catch (error) {
        show(`${config.value.title}导入接口暂不可用`)
      }
    } else {
      show('导入校验通过')
    }
  }
  importFile.value = null
  closeDialog()
}

async function downloadTemplate() {
  const excelApi = excelModules[moduleCode.value]
  if (excelApi?.template) {
    try {
      const response = await fetch(`${import.meta.env.VITE_API_BASE || '/api'}${excelApi.template}`, {
        headers: { Authorization: `Bearer ${localStorage.getItem('erp-token') || ''}` }
      })
      if (!response.ok) throw new Error('下载失败')
      const blob = await response.blob()
      saveBlobFile(`${config.value.title}_导入模板.xlsx`, blob)
      show('模板下载成功')
    } catch (error) {
      show(`模板下载失败：${error.message}`)
    }
  } else {
    show('该模块暂无导入模板')
  }
}

async function handleAction(action, row = null) {
  const actionStr = String(action || '')

  // 商品模块：新增/编辑走大抽屉，批量编辑走批量弹窗
  if (moduleCode.value === 'goods') {
    if (actionStr === '新建商品' || actionStr === '新增商品') { openAddDrawer(); return }
    if (actionStr === '编辑') { openEditDrawer(row); return }
    if (actionStr === '批量编辑') { openBatchEdit(); return }
  }

  // 采购订单/销售订单：走专用单据抽屉
  if (moduleCode.value === 'purchaseOrder' || moduleCode.value === 'salesOrder') {
    if (/新建|新增/.test(actionStr)) { openBillDrawer('add', moduleCode.value); return }
    if (actionStr === '编辑') { openBillDrawer('edit', moduleCode.value, row); return }
  }

  // 收付款单/核销：走资金单据抽屉
  if (moduleCode.value === 'receiptPayment') {
    if (/新建收款|收款/.test(actionStr)) { openFundDrawer('receipt', row); return }
    if (/新建付款|付款/.test(actionStr)) { openFundDrawer('payment', row); return }
  }
  if (moduleCode.value === 'receiptVerify' || moduleCode.value === 'arSettlement' || moduleCode.value === 'counterpartyAr') {
    if (/核销|收款/.test(actionStr)) { openFundDrawer('receiveVerify', row); return }
  }
  if (moduleCode.value === 'paymentVerify' || moduleCode.value === 'apSettlement' || moduleCode.value === 'counterpartyAp') {
    if (/核销|付款/.test(actionStr)) { openFundDrawer('payVerify', row); return }
  }

  // 基础资料：走专用抽屉
  const baseModules = ['customer', 'supplier', 'warehouse', 'unit', 'brand', 'category', 'priceGroup', 'territory', 'routeLine', 'employee', 'department', 'owner', 'expenseType', 'counterparty', 'fundAccount']
  if (baseModules.includes(moduleCode.value)) {
    if (/新建|新增/.test(actionStr)) { openBaseDrawer('add', moduleCode.value); return }
    if (actionStr === '编辑') { openBaseDrawer('edit', moduleCode.value, row); return }
  }

  if (/刷新/.test(action)) {
    pageNo.value = 1
    await loadRows()
    show(`${config.value.title}已刷新`)
  } else if (/查看|详情|历史|库存|日志|来源/.test(action)) {
    await openDetail(action, row)
  } else if (/新建|编辑|复制|引入/.test(action)) {
    openDialog('form', action, `${config.value.title}：按PRD打开${config.value.mode === 'modal' ? '小弹窗' : config.value.mode === 'drawer' ? '右侧抽屉' : '独立页面'}。`, row)
  } else if (/审核|确认签收|停用|作废|终止|核销|反审核|冻结|解冻|关闭|删除/.test(action)) {
    openDialog('confirm', action, `${action}会按业务规则校验状态、权限和上下游引用，并写入操作日志。`, row)
  } else if (/导入/.test(action)) {
    openDialog('import', action, `${config.value.title}导入：先下载模板，上传后预校验，失败行可下载原因。`, row)
  } else if (/下载|失败原因/.test(action)) {
    const api = moduleApis[moduleCode.value]
    if (api?.download) {
      try {
        const result = await post(api.download, buildPayload())
        if (result?.fileContent) saveTextFile(result.fileName, result.fileContent, result.mimeType)
        show(result?.message ? `${result.message}：${result.fileName}` : `${config.value.title}下载已准备好`)
      } catch (error) {
        show(`${config.value.title}下载失败：${error.message}`)
      }
    } else {
      show(`${config.value.title}文件下载已开始`)
    }
  } else if (/导出/.test(action)) {
    const excelApi = excelModules[moduleCode.value]
    if (excelApi?.export) {
      try {
        const blob = await downloadBlob(excelApi.export, { moduleCode: moduleCode.value, filters: queryFilters.value })
        const fileName = `${config.value.title}_导出_${Date.now()}.xlsx`
        saveBlobFile(fileName, blob)
        show(`${config.value.title}导出成功：${fileName}`)
      } catch (error) {
        show(`${config.value.title}导出失败：${error.message}`)
      }
    } else {
      const api = moduleApis[moduleCode.value]
      if (api?.export) {
        try {
          const result = await post(api.export, { moduleCode: moduleCode.value, reportName: config.value.title, filters: queryFilters.value })
          show(result?.message || `${config.value.title}已创建导出任务`)
          if (moduleCode.value === 'exportCenter') loadRows()
        } catch (error) {
          show(`${config.value.title}导出接口暂不可用`)
        }
      } else {
        show(`${config.value.title}已创建导出任务`)
      }
    }
  } else if (/打印/.test(action)) {
    show(`${config.value.title}打印预览已打开`)
  } else if (/字段设置/.test(action)) {
    openDialog('field', '字段设置', '支持列显示/隐藏、顺序、宽度、固定列，并按用户保存。', row)
  } else {
    show(`${config.value.title}：${action}`)
  }
}

function buildPayload() {
  const base = {
    moduleCode: moduleCode.value,
    bizId: selectedRow.value?.c0 || `${moduleCode.value}-demo`,
    orderId: selectedRow.value?.c0,
    taskNo: selectedRow.value?.c0,
    remark: `${config.value.title}操作`,
    customerId: 'CUS001',
    supplierId: 'SUP001',
    warehouseId: 'WH001',
    objectId: 'OBJ001',
    fundAccountId: 'A001',
    amount: 100,
    parentId: 'ROOT',
    parentCode: '01',
    categoryCode: '01',
    categoryName: `${config.value.title}新增`,
    effectiveMode: 'IMMEDIATE',
    validType: 'LONG_TERM',
    details: [{ goodsId: 'G001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', discountRate: '100%', taxRate: '13%', qty: 1, price: 1, currentPrice: 1 }],
    priceIds: ['PRICE001'],
    reason: '页面操作停用',
  }
  return { ...base, ...modulePayload() }
}

function modulePayload() {
  if (moduleCode.value === 'goods') {
    const code = text('商品编码') || selectedRow.value?.c1 || `GD${Date.now()}`
    return {
      goodsCode: code,
      goodsId: selectedRow.value?.c1 || code,
      goodsName: text('商品名称') || '新商品',
      goodsType: text('商品类型') || '正常商品',
      spec: text('规格'),
      categoryName: text('分类') || '默认分类',
      brandName: text('品牌'),
      baseUnit: text('基本单位') || '箱',
      barcode: text('条码'),
      shelfLifeDays: numberValue('保质期', 0),
      storageProperty: text('存储属性') || '常温',
      standardPrice: numberValue('标准售价', 0),
      suggestedRetailPrice: numberValue('建议零售价', 0),
      latestPurchasePrice: numberValue('参考进价', 0),
      minSalePrice: numberValue('最低售价', 0),
      stockUpperLimit: numberValue('库存上限', 0),
      stockLowerLimit: numberValue('库存下限', 0),
      defaultSupplier: text('默认供应商'),
      defaultWarehouse: text('默认仓库'),
      canReturn: !/否/.test(text('可售/可采购/可退')),
    }
  }
  if (moduleCode.value === 'customer') {
    const code = text('客户编码') || selectedRow.value?.c0 || `CT${Date.now()}`
    return {
      customerId: selectedRow.value?.c0 || code,
      customerCode: code,
      customerName: text('客户名称') || '新客户',
      channelType: text('渠道类型') || '零售商超',
      contactName: text('联系人'),
      mobile: text('手机号'),
      territory: text('片区'),
      routeLine: text('线路'),
      salesman: text('业务员'),
      customerLevel: text('客户等级') || '普通',
      accountPeriodType: text('账期类型') || text('结算方式') || '现结',
      cutoffDay: text('截账日'),
      paymentDay: text('付款日'),
      creditLimit: numberValue('信用额度', 0),
      invoiceTitle: text('发票抬头'),
      taxNo: text('税号'),
    }
  }
  if (moduleCode.value === 'supplier') {
    const code = text('供应商编码') || selectedRow.value?.c0 || `SP${Date.now()}`
    return {
      supplierId: selectedRow.value?.c0 || code,
      supplierCode: code,
      supplierName: text('供应商名称') || '新供应商',
      shortName: text('供应商简称'),
      supplierType: text('供应商类型') || text('类型') || '普通供应商',
      contactName: text('联系人'),
      phone: text('电话'),
      deliveryDays: numberValue('到货天数', 0),
      settlementMethod: text('结算方式') || '现结',
      accountPeriodDays: numberValue('账期天数', 0),
      defaultBuyer: text('默认采购员'),
      defaultReceiptAccount: text('默认收款账户'),
      invoiceTitle: text('发票抬头'),
      taxNo: text('税号'),
    }
  }
  if (moduleCode.value === 'purchaseOrder') {
    return {
      orderId: selectedRow.value?.c0,
      supplierId: text('供应商') || '农夫山泉杭州经销',
      warehouseId: text('收货仓库') || text('仓库') || '总仓',
      buyer: text('采购员') || '李四',
      ownerName: text('货主') || '平台货主',
      settlementMethod: text('结算方式') || '月结30天',
      details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', taxRate: '13%', qty: 1, price: 35 }],
    }
  }
  if (moduleCode.value === 'salesOrder') {
    return {
      orderId: selectedRow.value?.c0,
      customerId: text('客户') || '华联超市',
      warehouseId: text('仓库') || '总仓',
      salesman: text('业务员') || '张三',
      lineType: text('行类型') || '正常',
      details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: text('行类型') || '正常', discountRate: '100%', taxRate: '13%', qty: 1, price: 35 }],
    }
  }
  if (moduleCode.value === 'purchaseInbound') {
    return { sourceOrder: text('采购单号') || selectedRow.value?.c2 || selectedRow.value?.c0 || 'PO202606140001' }
  }
  if (moduleCode.value === 'salesOutbound') {
    return { sourceOrder: text('销售单号') || selectedRow.value?.c1 || selectedRow.value?.c0 || 'SO202606140001' }
  }
  return {}
}

function text(field) {
  return String(formModel.value[field] ?? '').trim()
}

function numberValue(field, fallback = 0) {
  const value = Number(String(formModel.value[field] ?? '').replace(/[^0-9.-]/g, ''))
  return Number.isFinite(value) ? value : fallback
}

function selectTreeNode(nodeOrCode) {
  const value = typeof nodeOrCode === 'string' ? nodeOrCode.trim() : ''
  selectedTreeNode.value = value
  if (moduleCode.value === 'category') {
    // 分类模块：点击树节点=视觉高亮，不做后台过滤（列表显示所有分类）
    show(value ? `已高亮：${value}` : '已显示全部')
    return
  }
  queryFilters.value = { ...queryFilters.value, treeNode: value }
  pageNo.value = 1
  loadRows()
  show(value ? `已切换到：${value}` : '已显示全部')
}
async function handleQuery(filters = {}) { queryFilters.value = filters; pageNo.value = 1; await loadRows(); show(`${config.value.title}查询完成`) }
function handleReset() { queryFilters.value = {}; pageNo.value = 1; loadRows(); show(`${config.value.title}查询条件已重置`) }
function handleMore(fields) { openDialog('more', '更多查询条件', fields.join('、')) }
function handleRowAction(action, row) { handleAction(action, row) }
function handlePageChange(nextPageNo) { pageNo.value = Math.max(1, nextPageNo); loadRows() }
function handlePageSizeChange(nextPageSize) { pageSize.value = nextPageSize; pageNo.value = 1; loadRows() }
function handleSortChange(field) {
  if (sortField.value !== field) {
    sortField.value = field
    sortOrder.value = 'asc'
  } else if (sortOrder.value === 'asc') {
    sortOrder.value = 'desc'
  } else {
    sortField.value = ''
    sortOrder.value = ''
  }
  pageNo.value = 1
  loadRows()
}
</script>

<template>
  <div class="module-body" :class="{ 'with-tree': config.tree || moduleCode === 'category' }">
    <aside v-if="config.tree || moduleCode === 'category'" class="module-tree">
      <!-- 分类模块：动态树 -->
      <template v-if="moduleCode === 'category'">
        <div v-if="dynamicTree.length === 0" class="tree-empty">暂无分类，请点击右侧【新建分类】</div>
        <div
          v-for="node in dynamicTree"
          :key="node.code || 'root'"
          class="tree-node"
          :class="{ active: selectedTreeNode === node.code }"
          :style="{ paddingLeft: (12 + node.level * 16) + 'px' }"
          @click="selectTreeNode(node.code)"
        >
          <span v-if="node.level > 0" class="tree-indent">└</span>{{ node.name }}
        </div>
      </template>
      <!-- 其他模块：静态 treeNodes -->
      <template v-else>
        <div v-for="node in config.treeNodes" :key="node" class="tree-node" :class="{ active: selectedTreeNode === node.trim() }" @click="selectTreeNode(node)">{{ node }}</div>
      </template>
    </aside>

    <section class="module-list">
      <div class="page-ops">
        <button v-for="action in config.actions" :key="action" class="btn" :class="{ primary: /^新建|新增商品|审核并打印/.test(action) }" @click="handleAction(action)">
          {{ action }}
          <span v-if="action === '批量编辑' && moduleCode === 'goods' && selectedRowKeys.size > 0">({{ selectedRowKeys.size }})</span>
        </button>
      </div>

      <QueryBar :fields="config.filters" @query="handleQuery" @reset="handleReset" @more="handleMore" />

      <div v-if="config.tips?.length" class="tips-inline">
        <span v-for="tip in config.tips" :key="tip">⚠ {{ tip }}</span>
      </div>

      <div v-if="loading" class="tips-inline"><span>正在加载 {{ config.title }} 数据...</span></div>
      <ProTable :title="config.title + '列表'" :columns="visibleColumns" :rows="tableRows" :page-no="pageNo" :page-size="pageSize" :total="total" :sort-field="sortField" :sort-order="sortOrder" @field-setting="handleAction('字段设置')" @export="handleAction('导出')" @row-action="handleRowAction" @page-change="handlePageChange" @page-size-change="handlePageSizeChange" @sort-change="handleSortChange">
        <!-- 商品列表勾选列 -->
        <template v-if="moduleCode === 'goods'" #checkbox-header>
          <input type="checkbox" :checked="tableRows.length > 0 && selectedRowKeys.size === tableRows.length" @change="toggleSelectAll($event.target.checked)" />
        </template>
        <template v-if="moduleCode === 'goods'" #checkbox-cell="{ rowIndex }">
          <input type="checkbox" :checked="selectedRowKeys.has(rowIndex)" @change="toggleRowSelection(rowIndex, $event.target.checked)" />
        </template>

        <template v-for="col in visibleColumns" #[col.key]="{ row }" :key="col.key">
          <span v-if="/状态/.test(col.title)" class="badge wait">{{ row[col.key] }}</span>
          <span v-else-if="/操作/.test(col.title)">
            <template v-if="isBaseModule">
              <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
              <button class="link link-btn danger-link" @click="handleAction('删除', row)">删除</button>
            </template>
            <template v-else>
              <button v-for="action in String(row[col.key]).split(' ')" :key="action" class="link link-btn" @click="handleAction(action, row)">{{ action }}</button>
            </template>
          </span>
          <span v-else>{{ row[col.key] }}</span>
        </template>
      </ProTable>
    </section>
  </div>

  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>

  <div v-if="dialog" class="modal-lite" :class="{ 'drawer-lite': config.mode === 'drawer' && dialog.type === 'form', 'page-lite': config.mode === 'bill' && dialog.type === 'form' }">
    <div class="modal-lite-box">
      <div class="modal-lite-head"><b>{{ dialog.title }} - {{ config.title }}</b><button class="btn" @click="closeDialog">×</button></div>
      <div class="modal-lite-body">
        <p>{{ dialog.message }}</p>

        <div v-if="dialog.type === 'form'" class="form-edit-area">
          <div :class="config.mode === 'modal' ? 'form-vertical' : 'grid4'">
            <div v-for="field in formFields" :key="field" class="field">
              <label>{{ field }}<span v-if="/编码|名称|单号|客户|供应商|仓库|日期|数量|单价|金额|状态/.test(field)" style="color:#ef4444"> *</span></label>
              <select v-if="/状态|类型|方式|仓库|客户|供应商|单位|分类|品牌|业务员|采购员/.test(field)" v-model="formModel[field]"><option>{{ formModel[field] || field }}</option></select>
              <textarea v-else-if="/备注|说明|原因/.test(field)" v-model="formModel[field]" :placeholder="field"></textarea>
              <input v-else v-model="formModel[field]" :placeholder="field" />
            </div>
          </div>

          <div v-if="detailColumns.length" class="section-block">
            <b>明细信息</b>
            <div class="scroll mini-scroll">
              <table>
                <tr><th v-for="field in detailColumns" :key="field">{{ field }}</th><th>操作</th></tr>
                <tr><td v-for="field in detailColumns" :key="field" contenteditable>{{ field.includes('数量') ? '10' : field.includes('金额') ? '350.00' : field }}</td><td><button class="link link-btn">删除</button></td></tr>
              </table>
            </div>
            <button class="btn" style="margin-top:8px" @click="show('已新增明细行')">新增明细行</button>
          </div>
        </div>

        <div v-if="dialog.type === 'view'" class="section-block">
          <div class="grid4">
            <div v-for="col in columns.filter(c => !/操作/.test(c.title))" :key="col.key" class="field"><label>{{ col.title }}</label><input readonly :value="selectedRow?.[col.key] || ''" /></div>
          </div>
          <div v-if="detailData?.details?.length" class="section-block">
            <b>后端明细</b>
            <div class="scroll mini-scroll">
              <table>
                <tr><th>商品编码</th><th>商品名称</th><th>单位</th><th>数量</th><th>单价</th><th>金额</th><th>成本金额</th></tr>
                <tr v-for="detail in detailData.details" :key="detail.goodsCode + detail.goodsName + detail.qty">
                  <td>{{ detail.goodsCode }}</td><td>{{ detail.goodsName }}</td><td>{{ detail.unit }}</td><td>{{ detail.qty }}</td><td>{{ detail.price }}</td><td>{{ detail.amount }}</td><td>{{ detail.costAmount }}</td>
                </tr>
              </table>
            </div>
          </div>
        </div>

        <div v-if="dialog.type === 'confirm'" class="field"><label>原因/备注</label><textarea placeholder="请输入原因或备注"></textarea></div>

        <div v-if="dialog.type === 'import'" class="section-block">
          <button class="btn" @click="downloadTemplate">下载模板</button>
          <input style="margin-left:8px" type="file" accept=".xlsx,.xls" @change="importFile = $event.target.files?.[0] || null" />
          <p v-if="importFile" style="color:var(--primary);font-size:12px">已选择：{{ importFile.name }}</p>
          <p style="color:#5d7896">上传后先校验，不直接入库；失败行可下载失败原因。</p>
        </div>

        <div v-if="dialog.type === 'field'" class="field-setting-grid">
          <label v-for="col in columns" :key="col.key" class="field-check" :class="{ disabled: /操作/.test(col.title) }">
            <input v-model="columnSettings[col.key]" type="checkbox" :disabled="/操作/.test(col.title)" />
            <span>{{ col.title }}</span>
          </label>
        </div>
      </div>
      <div class="modal-lite-foot">
        <button class="btn" @click="closeDialog">取消</button>
        <button v-if="dialog.type === 'form'" class="btn primary" @click="saveForm">保存</button>
        <button v-else-if="dialog.type === 'confirm'" class="btn primary" @click="confirmAction">确认</button>
        <button v-else-if="dialog.type === 'import'" class="btn primary" @click="uploadImport">上传并校验</button>
        <button v-else-if="dialog.type === 'field'" class="btn" @click="resetColumnSettings">恢复默认</button>
        <button v-if="dialog.type === 'field'" class="btn primary" @click="saveColumnSettings">保存字段设置</button>
        <button v-else class="btn primary" @click="closeDialog">确定</button>
      </div>
    </div>
  </div>

  <!-- 商品编辑大抽屉 -->
  <GoodsDrawer
    :visible="showGoodsDrawer && moduleCode === 'goods'"
    :mode="drawerMode"
    :goods-data="editGoodsData"
    @close="showGoodsDrawer = false"
    @save="handleGoodsSave"
  />

  <!-- 业务单据抽屉（采购订单/销售订单） -->
  <BillDrawer
    :visible="showBillDrawer"
    :mode="billDrawerMode"
    :module-code="billDrawerCode"
    :edit-data="billEditData"
    @close="closeBillDrawer"
    @save="onBillSave"
  />

  <!-- 资金单据抽屉（收付款/核销） -->
  <FundBillDrawer
    :visible="showFundDrawer"
    :bill-type="fundBillType"
    :edit-data="fundEditData"
    @close="closeFundDrawer"
    @save="onFundSave"
  />

  <!-- 基础资料抽屉 -->
  <BaseInfoDrawer
    :visible="showBaseDrawer"
    :mode="baseDrawerMode"
    :module-code="baseDrawerCode"
    :edit-data="baseEditData"
    @close="closeBaseDrawer"
    @save="onBaseSave"
  />

  <!-- 批量编辑弹窗 -->
  <BatchEditDrawer
    :visible="showBatchEditDrawer && moduleCode === 'goods'"
    :selected-rows="selectedRows"
    @close="showBatchEditDrawer = false"
    @save="handleBatchSave"
  />
</template>
