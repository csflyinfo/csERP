export const moduleApis = {
  goods: { page: '/base/goods/page', save: '/base/goods/create', update: '/base/goods/update', stop: '/base/goods/stop', delete: '/base/goods/delete' },
  category: { page: '/base/category/page', save: '/base/category/create', update: '/base/category/update', stop: '/base/category/stop', delete: '/base/category/delete' },
  brand: { page: '/base/brand/page', save: '/base/brand/create', update: '/base/brand/update', stop: '/base/brand/stop', delete: '/base/brand/delete' },
  unit: { page: '/base/unit/page', save: '/base/unit/create', update: '/base/unit/update', stop: '/base/unit/stop', delete: '/base/unit/delete' },
  warehouse: { page: '/base/warehouse/page', save: '/base/warehouse/create', update: '/base/warehouse/update', stop: '/base/warehouse/stop', delete: '/base/warehouse/delete' },
  customer: { page: '/base/customer/page', save: '/base/customer/create', update: '/base/customer/update', stop: '/base/customer/stop', delete: '/base/customer/delete' },
  supplier: { page: '/base/supplier/page', save: '/base/supplier/create', update: '/base/supplier/update', stop: '/base/supplier/stop', delete: '/base/supplier/delete' },
  priceGroup: { page: '/base/master/price-group/page', save: '/base/master/save', stop: '/base/master/stop', delete: '/base/master/delete' },
  counterparty: { page: '/base/master/counterparty/page', save: '/base/master/save', stop: '/base/master/stop', delete: '/base/master/delete' },
  fundAccount: { page: '/base/master/fund-account/page', save: '/base/master/save', stop: '/base/master/stop', delete: '/base/master/delete' },
  expenseType: { page: '/base/master/expense-type/page', save: '/base/master/save', stop: '/base/master/stop', delete: '/base/master/delete' },
  territory: { page: '/base/master/territory/page', save: '/base/master/save', stop: '/base/master/stop', delete: '/base/master/delete' },
  routeLine: { page: '/base/master/route-line/page', save: '/base/master/save', stop: '/base/master/stop', delete: '/base/master/delete' },
  employee: { page: '/base/master/employee/page', save: '/base/master/save', stop: '/base/master/stop', delete: '/base/master/delete' },
  department: { page: '/base/master/department/page', save: '/base/master/save', stop: '/base/master/stop', delete: '/base/master/delete' },
  owner: { page: '/base/master/owner/page', save: '/base/master/save', stop: '/base/master/stop', delete: '/base/master/delete' },

  customerPrice: { page: '/base/customer-price-adjust/page', save: '/base/customer-price-adjust/create', audit: '/base/customer-price-adjust/audit', cancel: '/base/customer-price-adjust/cancel', import: '/base/customer-price-adjust/import' },
  customerPriceQuery: { page: '/base/customer-price/query', stop: '/base/customer-price/stop' },

  stockBalance: { page: '/inventory/balance/page' },
  stockLedger: { page: '/inventory/ledger/page' },
  stockLock: { page: '/inventory/lock/page' },
  batchStock: { page: '/inventory/batch/page' },
  stockWarning: { page: '/inventory/warning/page' },
  transfer: { page: '/inventory/transfer/page', audit: '/inventory/transfer/audit' },
  damage: { page: '/inventory/damage/page', audit: '/inventory/damage/audit' },
  costAdjust: { page: '/inventory/cost-adjust/page', audit: '/inventory/cost-adjust/audit' },
  stockAdjust: { page: '/inventory/cost-adjust/page', audit: '/inventory/cost-adjust/audit' },
  otherInbound: { page: '/inventory/ledger/page', audit: '/purchase/inbound/audit' },
  otherOutbound: { page: '/inventory/ledger/page', audit: '/inventory/damage/audit' },
  stockTake: { page: '/inventory/balance/page', audit: '/inventory/cost-adjust/audit' },

  purchaseOrder: { page: '/purchase/order/page', save: '/purchase/order/create', update: '/purchase/order/update', detail: '/purchase/order/detail', audit: '/purchase/order/audit', reverseAudit: '/purchase/order/reverse-audit', close: '/purchase/order/close', delete: '/purchase/order/delete' },
  purchaseInbound: { page: '/purchase/inbound/page', save: '/purchase/inbound/create', detail: '/purchase/inbound/detail', audit: '/purchase/inbound/audit' },
  purchaseReceipt: { page: '/purchase/receipt/page', audit: '/purchase/receipt/audit' },
  purchaseReturn: { page: '/purchase/return/page', audit: '/purchase/return/audit' },
  purchaseExpense: { page: '/purchase/expense/page', audit: '/purchase/expense/audit' },
  purchaseInvoice: { page: '/purchase/invoice/page' },

  quickOrder: { page: '/sales/order/page', save: '/sales/quick-order/create-and-audit', audit: '/sales/quick-order/create-and-audit' },
  salesOrder: { page: '/sales/order/page', save: '/sales/order/create', update: '/sales/order/update', detail: '/sales/order/detail', audit: '/sales/order/audit', reverseAudit: '/sales/order/reverse-audit', close: '/sales/order/close', delete: '/sales/order/delete' },
  salesOutbound: { page: '/sales/outbound/page', save: '/sales/outbound/create', detail: '/sales/outbound/detail', audit: '/sales/outbound/audit' },
  salesReceipt: { page: '/sales/receipt/page', audit: '/sales/receipt/audit' },
  salesReturn: { page: '/sales/return/page', audit: '/sales/return/audit' },
  salesInvoice: { page: '/sales/invoice/page' },
  flyOrder: { page: '/sales/fly-order/page', audit: '/flow/v1-core/self-test' },
  emptyAdjust: { page: '/sales/empty-adjust/page', audit: '/flow/v1-core/self-test' },

  ar: { page: '/finance/ar/page', reconcile: '/finance/reconcile/receive' },
  ap: { page: '/finance/ap/page', reconcile: '/finance/reconcile/pay' },
  receiptPayment: { page: '/finance/receipt-payment/page', save: '/finance/receipt/create', audit: '/flow/v1-core/self-test' },
  arSettlement: { page: '/finance/ar-settlement/page', reconcile: '/finance/reconcile/receive' },
  apSettlement: { page: '/finance/ap-settlement/page', reconcile: '/finance/reconcile/pay' },
  financeExpense: { page: '/finance/expense/page', audit: '/finance/expense/audit' },
  fundLedger: { page: '/finance/fund-ledger/page' },
  counterpartyAr: { page: '/finance/ar/page', reconcile: '/finance/reconcile/receive' },
  counterpartyAp: { page: '/finance/ap/page', reconcile: '/finance/reconcile/pay' },
  receiptVerify: { page: '/finance/ar/page', reconcile: '/finance/reconcile/receive' },
  paymentVerify: { page: '/finance/ap/page', reconcile: '/finance/reconcile/pay' },
  customerStatement: { page: '/finance/ar/page' },
  supplierStatement: { page: '/finance/ap/page' },

  user: { page: '/system/user/page', save: '/system/user/save', stop: '/base/master/stop' },
  role: { page: '/system/role/page', save: '/system/role/save', stop: '/base/master/stop' },
  param: { page: '/system/param/page', save: '/system/param/update' },
  billNo: { page: '/system/bill-no-rule/page', save: '/system/bill-no-rule/update' },
  precision: { page: '/system/precision/page', save: '/system/precision/save' },
  dictionary: { page: '/system/dictionary/page', save: '/system/dictionary/save' },
  workflow: { page: '/system/workflow/page', save: '/system/workflow/save' },
  printTemplate: { page: '/system/print-template/page', save: '/system/print-template/save' },
  importList: { page: '/system/import-list/page', import: '/system/import-list/create', download: '/system/import-list/download-failures' },
  exportCenter: { page: '/system/export-center/page', download: '/system/export-center/download' },
  log: { page: '/system/operation-log/page' },

  salesReport: { page: '/report/sales/page', export: '/excel/export/salesOrder' },
  purchaseReport: { page: '/report/purchase/page', export: '/excel/export/purchaseOrder' },
  stockReport: { page: '/report/stock/page', export: '/excel/export/stockBalance' },
  financeReport: { page: '/report/finance/page', export: '/excel/export/finance' },
}

export const excelModules = {
  goods: { export: '/excel/export/goods', import: '/excel/import/goods', template: '/excel/template/goods' },
  customer: { export: '/excel/export/customer', import: '/excel/import/customer', template: '/excel/template/customer' },
  supplier: { export: '/excel/export/supplier', import: '/excel/import/supplier', template: '/excel/template/supplier' },
  warehouse: { export: '/excel/export/warehouse', import: '/excel/import/warehouse', template: '/excel/template/warehouse' },
  purchaseOrder: { export: '/excel/export/purchaseOrder', import: '/excel/import/purchaseOrder', template: '/excel/template/purchaseOrder' },
  salesOrder: { export: '/excel/export/salesOrder', import: '/excel/import/salesOrder', template: '/excel/template/salesOrder' },
}

export function mapRecordToRow(record, config) {
  const row = Object.fromEntries(config.columns.map((title, index) => [`c${index}`, valueForTitle(title, record)]))
  row._raw = record
  return row
}

// ============================================================
// 列标题 -> 后端字段名 精确映射表
// 顺序：先精确匹配（如"分类名称"），再宽泛匹配（如"名称"）
// ============================================================
const EXACT_TITLE_MAP = {
  // 商品
  '商品图片': ['image'],
  '商品编码': ['goodsCode', 'code'],
  '商品编号': ['goodsCode', 'code'],
  '商品名称': ['goodsName'],
  '商品类型': ['goodsType'],
  '商品等级': ['goodsLevel'],
  '商品简介': ['goodsIntro'],
  '规格': ['spec'],
  '规格型号': ['spec'],
  '简拼': ['simpleCode'],
  '简码': ['simpleCode'],
  '条码': ['barcode'],
  '基本单位': ['baseUnit'],
  '税率': ['taxRate'],
  '存储属性': ['storageProperty'],
  '产地': ['origin'],
  '保质期': ['shelfLifeDays'],
  '临期预警天数': ['warningDays'],
  '库存上限': ['stockUpperLimit'],
  '库存下限': ['stockLowerLimit'],
  '采购起订量': ['minOrderQty'],
  '标准售价': ['standardPrice'],
  '建议零售价': ['suggestedRetailPrice'],
  '参考进价': ['latestPurchasePrice', 'referencePurchasePrice'],
  '最新进价': ['latestPurchasePrice'],
  '最低售价': ['minSalePrice'],
  '默认供应商': ['defaultSupplier'],
  '默认仓库': ['defaultWarehouse'],
  '当前库存': ['currentStock', 'physicalQty'],
  '实物库存': ['physicalQty', 'currentStock'],
  '可用库存': ['availableQty'],
  '锁定库存': ['lockedQty'],
  '冻结库存': ['frozenQty'],
  '库存金额': ['stockAmount'],
  '成本单价': ['costPrice'],
  '成本价': ['costPrice'],
  '成本金额': ['costAmount'],
  '可售/可采购': ['salePurchaseFlag'],
  '可售/可采购/可退': ['salePurchaseReturnFlag'],
  '商品负责人': ['goodsManager'],

  // 商品分类
  '分类编码': ['categoryCode'],
  '分类名称': ['categoryName'],
  '分类': ['categoryName', 'category'],
  '上级分类': ['parentCode', 'parentId'],
  '默认税率': ['defaultTaxRate'],
  '商品数': ['goodsCount'],

  // 品牌
  '品牌编码': ['brandCode'],
  '品牌名称': ['brandName'],
  '品牌': ['brandName'],

  // 单位
  '单位编码': ['unitCode'],
  '单位名称': ['unitName'],
  '单位': ['unitName', 'baseUnit', 'unit'],
  '可作为基本单位': ['canBaseUnit'],

  // 仓库
  '仓库编码': ['warehouseCode'],
  '仓库名称': ['warehouseName'],
  '仓库类型': ['warehouseType'],
  '存货类型': ['inventoryType'],
  '库存类型': ['inventoryType'],
  '成本分组': ['costGroup'],
  '负责人': ['managerName'],
  '仓库': ['warehouse', 'warehouseName'],
  '收货仓库': ['warehouse'],
  '入库仓库': ['warehouse'],
  '出库仓库': ['warehouse'],
  '调出仓库': ['warehouseOut'],
  '调入仓库': ['warehouseIn'],
  '地址': ['address'],
  '货主': ['ownerName'],
  '是否默认': ['isDefault'],

  // 客户
  '客户编码': ['customerCode'],
  '客户名称': ['customerName'],
  '客户编号/名称': ['customerName'],
  '客户': ['customerName', 'customer'],
  '门店': ['customerName'],
  '渠道类型': ['channelType'],
  '客户等级': ['customerLevel'],
  '账期类型': ['accountPeriodType'],
  '截账日': ['cutoffDay'],
  '付款日': ['paymentDay'],
  '信用额度': ['creditLimit'],
  '应收余额': ['arBalance'],
  '逾期金额': ['overdueAmount'],
  '发票抬头': ['invoiceTitle'],
  '税号': ['taxNo'],

  // 供应商
  '供应商编码': ['supplierCode'],
  '供应商名称': ['supplierName'],
  '供应商简称': ['shortName'],
  '供应商类型': ['supplierType'],
  '供应商': ['supplierName', 'supplier'],
  '简称': ['shortName'],
  '类型': ['type', 'supplierType'],
  '电话': ['phone'],
  '到货天数': ['deliveryDays'],
  '结算方式': ['settlementMethod'],
  '账期天数': ['accountPeriodDays'],
  '默认采购员': ['defaultBuyer'],
  '默认收款账户': ['defaultReceiptAccount'],
  '应付余额': ['apBalance'],

  // 联系
  '联系人': ['contactName'],
  '手机': ['mobile'],
  '手机号': ['mobile'],

  // 片区/线路/人员/部门/货主
  '片区': ['territory'],
  '片区编码': ['territoryCode'],
  '片区名称': ['territoryName'],
  '所属城市': ['city'],
  '线路': ['routeLine'],
  '线路编码': ['routeLineCode'],
  '线路名称': ['routeLineName'],
  '司机': ['driver'],
  '覆盖范围': ['coverage'],
  '业务员': ['salesman'],
  '采购员': ['buyer'],
  '人员编码': ['employeeCode'],
  '人员姓名': ['employeeName'],
  '姓名': ['employeeName', 'displayName'],
  '所属部门': ['department'],
  '职位': ['position'],
  '部门编码': ['departmentCode'],
  '部门名称': ['departmentName'],
  '上级部门': ['parentName'],
  '人数': ['headCount'],
  '货主编码': ['ownerCode'],
  '货主名称': ['ownerName'],
  '货主类型': ['ownerType'],
  '所属平台': ['platform'],

  // 费用类型/往来单位/资金账户/价格组
  '费用类型编码': ['expenseTypeCode'],
  '费用类型名称': ['expenseTypeName'],
  '费用方向': ['direction'],
  '成本参与': ['costParticipation'],
  '往来单位编码': ['counterpartyCode'],
  '往来单位名称': ['counterpartyName'],
  '往来单位': ['counterpartyName'],
  '单位类型': ['counterpartyType'],
  '账户编码': ['fundAccountCode'],
  '账户名称': ['fundAccountName'],
  '账户类型': ['accountType'],
  '期初余额': ['balance'],
  '价格组编码': ['priceGroupCode'],
  '价格组名称': ['priceGroupName'],
  '是否启用': ['enabled'],
  '排序': ['sortOrder'],

  // 单据类
  '采购单号': ['orderNo'],
  '订单号': ['orderNo'],
  '入库单号': ['inboundNo'],
  '收货单号': ['receiptNo'],
  '出库单号': ['outboundNo'],
  '流水号': ['ledgerNo'],
  '应收单号': ['arNo'],
  '应付单号': ['apNo'],
  '调整单号': ['adjustNo'],
  '退货单号': ['returnNo'],
  '发票号码': ['invoiceNo'],
  '费用单号': ['expenseNo'],
  '调拨单号': ['transferNo'],
  '收付款单号': ['paymentNo'],
  '单号': ['orderNo', 'billNo'],
  '来源单据': ['sourceBill'],
  '来源销售收货单': ['sourceBill'],
  '来源采购收货单': ['sourceBill'],
  '来源入库单': ['sourceBill'],
  '来源销售单号': ['sourceBill'],
  '销售单号': ['sourceOrder', 'orderNo'],
  '来源采购单号': ['sourceOrder'],

  '单据日期': ['billDate'],
  '日期': ['billDate'],
  '入库日期': ['billDate'],
  '出库日期': ['billDate'],
  '签收日期': ['billDate'],
  '发生时间': ['occurredAt'],
  '发生日期': ['occurredAt'],
  '开票日期': ['billDate'],
  '预计到货日期': ['expectedArrivalDate'],
  '预计收款日': ['dueDate'],
  '预计付款日': ['dueDate'],
  '预计日期': ['dueDate'],

  '订单金额': ['amount'],
  '应收金额': ['arAmount', 'amount'],
  '应付金额': ['apAmount', 'amount'],
  '销售金额': ['amount'],
  '采购金额': ['amount'],
  '签收金额': ['signedAmount', 'amount'],
  '入库金额': ['inboundAmount', 'amount'],
  '费用金额': ['expenseAmount', 'amount'],
  '金额': ['amount'],
  '已收金额': ['receivedAmount', 'paidAmount'],
  '已收': ['receivedAmount'],
  '未收金额': ['unreceivedAmount', 'unpaidAmount'],
  '已付金额': ['paidAmount'],
  '未付金额': ['unpaidAmount'],
  '未结金额': ['unsettledAmount'],
  '毛利': ['grossProfit'],
  '成本': ['costAmount'],
  '折扣': ['discountRate'],
  '数量': ['qty'],
  '出库数量': ['qty'],
  '入库数量': ['qty'],
  '调拨数量': ['qty'],
  '在途数量': ['pendingQty'],
  '换算率': ['convertRate'],
  '基本单位数量': ['baseQty'],
  '单价': ['price'],
  '原价': ['originalPrice'],
  '现价': ['currentPrice', 'price'],
  '专属价格': ['currentPrice'],

  '状态': ['status'],
  '核销状态': ['status', 'apStatus', 'arStatus'],
  '生效状态': ['effectiveStatus', 'status'],
  '应付生成状态': ['apStatus'],
  '应收生成': ['arStatus'],
  '开票状态': ['issueStatus'],
  '勾稽状态': ['matchStatus'],
  '认证状态': ['authStatus'],
  '付款状态': ['paymentStatus'],
  '到货状态': ['arrivalStatus'],
  '出库状态': ['outboundStatus'],
  '签收状态': ['signStatus'],
  '库存检查': ['stockCheck'],
  '信用检查': ['creditCheck'],
  '行类型': ['lineType'],
  '业务单位': ['bizUnit'],
  '业务类型': ['bizType'],

  '批次号': ['batchNo'],
  '批次': ['batchNo'],
  '生产日期': ['productionDate'],
  '到期日期': ['expiryDate'],
  '库位': ['location'],
  '入库前成本': ['beforeCost'],
  '入库后成本': ['afterCost'],
  '分摊费用': ['allocatedExpense'],
  '备注': ['remark'],

  // 单托盘/堆码
  '单托盘大单位数量': ['palletQty'],
  '堆码层数': ['stackLayers'],

  // 用户/角色/系统
  '账号': ['username'],
  '用户名': ['username'],
  '权限组': ['role', 'roleName'],
  '角色': ['role', 'roleName'],
  '权限组编码': ['roleCode'],
  '权限组名称': ['roleName'],
  '角色编码': ['roleCode'],
  '角色名称': ['roleName'],
  '数据范围': ['dataScope'],
  '用户数': ['userCount'],
  '菜单权限': ['menuScope'],
  '字段权限': ['fieldScope'],
  '参数键': ['paramKey'],
  '参数名称': ['paramName'],
  '当前值': ['paramValue'],
  '参数值': ['paramValue'],
  '默认值': ['defaultValue'],
  '分组': ['paramGroup'],
  '参数分组': ['paramGroup'],
  '单据类型': ['billType'],
  '前缀': ['prefix'],
  '日期格式': ['dateFormat'],
  '流水位数': ['serialLength'],
  '重置周期': ['resetCycle'],
  '示例': ['exampleNo'],

  '任务号': ['taskNo'],
  '任务名称': ['taskName'],
  '报表名称': ['reportName'],
  '模块编码': ['moduleCode'],
  '文件名': ['fileName'],
  '成功行数': ['successRows'],
  '失败行数': ['failedRows'],
  '结果说明': ['resultText'],
  '筛选条件': ['filterText'],
  '报表类型': ['reportType'],
  '对象名称': ['objectName'],
  '对象': ['objectName'],
  '收款对象': ['objectName'],
  '往来对象': ['objectName'],
  '收款对象类型': ['objectType'],
  '往来对象类型': ['objectType'],
  '余额': ['balance'],
  '已核销金额': ['verifiedAmount'],
  '已核销/余额': ['verifiedAmount'],
  '预计结款日': ['dueDate'],
  '逾期天数': ['overdueDays'],

  '创建时间': ['createdAt'],
  '完成时间': ['finishedAt'],
  '操作时间': ['operateAt'],
  '操作人': ['operatorName'],
  '创建人': ['creatorName'],
  '创建人/时间': ['creatorInfo'],
  '审核信息': ['auditInfo'],
  '制单信息': ['creatorInfo'],
  '模块': ['moduleCode'],
  '动作': ['action'],
  '结果': ['result'],
  '详情': ['detail'],
  '业务号': ['bizNo'],

  // 客户价格
  '有效期': ['validRange'],
  '生效方式': ['effectiveMode'],
  '生效时间': ['effectiveTime'],

  // 单据类扩展
  '调拨类型': ['transferType'],
  '调拨模式': ['transferMode'],
  '费用日期': ['billDate'],
  '费用类型': ['expenseType'],
  '不含税金额': ['amount'],
  '含税金额': ['taxIncludeAmount'],
  '税额': ['taxAmount'],
  '已勾稽金额': ['matchedAmount'],
  '未勾稽金额': ['unmatchedAmount'],
  '已分摊金额': ['allocatedAmount'],
  '未分摊金额': ['unallocatedAmount'],
  '分摊方式': ['allocateMethod'],
  '分摊状态': ['allocateStatus'],
  '是否生成应付': ['apGenerated'],
  '是否生成往来': ['counterpartyGenerated'],
  '是否直接收付款': ['directPayment'],
  '往来单据': ['counterpartyBill'],
  '生成收货单': ['receiptGenerated'],
  '重量(kg)': ['weight'],
  '体积(m³)': ['volume'],
}

// 状态英文码 → 中文
const STATUS_MAP = {
  NORMAL: '正常',
  STOPPED: '停用',
  FROZEN: '冻结',
  DELETED: '已删除',
  PENDING: '待审核',
  AUDITED: '已审核',
  UNVERIFIED: '未核销',
  VERIFIED: '已核销',
  EFFECTIVE: '生效中',
  EXPIRED: '已过期',
  CLOSED: '已关闭',
  ACTIVE: '正常',
  INACTIVE: '停用',
  ON: '启用',
  OFF: '禁用',
  SCHEDULED: '定时生效',
  IMMEDIATE: '立即生效',
}

// 布尔值 → 中文
function toDisplayValue(val, title) {
  if (val === null || val === undefined) return ''
  // 状态字段翻译
  if (/状态/.test(title) && typeof val === 'string' && STATUS_MAP[val]) {
    return STATUS_MAP[val]
  }
  // 布尔 → 是/否
  if (typeof val === 'boolean') return val ? '是' : '否'
  return val
}

function valueForTitle(title, record) {
  if (!title || !record) return ''
  // 精确匹配
  const keys = EXACT_TITLE_MAP[title]
  if (keys) {
    for (const key of keys) {
      if (record[key] !== undefined && record[key] !== null && record[key] !== '') {
        return toDisplayValue(record[key], title)
      }
    }
  }
  // 兜底：单一"编码"匹配任意 XxxCode 字段
  if (title === '编码' || title === '编号') {
    const codeField = Object.keys(record).find(k => /Code$/.test(k) && typeof record[k] === 'string')
    if (codeField) return toDisplayValue(record[codeField], title)
    if (record.code) return toDisplayValue(record.code, title)
  }
  // 兜底：单一"名称"匹配任意 XxxName 字段（排除 parentName/managerName 等修饰字段）
  if (title === '名称') {
    const nameField = Object.keys(record).find(k => /Name$/.test(k) && !/^(parent|manager|contact|display|role|param|task|report|module|object|file)Name$/i.test(k) && typeof record[k] === 'string')
    if (nameField) return toDisplayValue(record[nameField], title)
    if (record.name) return toDisplayValue(record.name, title)
  }
  // 兜底："类型/分组"匹配 supplierType/counterpartyType/ownerType/accountType 等
  if (title === '类型/分组' || title === '类型') {
    const typeField = Object.keys(record).find(k => /Type$/.test(k) && typeof record[k] === 'string')
    if (typeField) return toDisplayValue(record[typeField], title)
    if (record.type) return toDisplayValue(record.type, title)
  }
  // 操作列返回默认按钮
  if (/操作/.test(title)) return '编辑'
  return ''
}
