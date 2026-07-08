// 统一导入配置注册表 —— 所有模块的导入调用同一个 ImportDialog
// 每个 preset 描述：
//   title / templateName / templateHeaders / fieldMap / requiredKey
//   endpoint: '/api/xxx' — 后端接口路径（不含 /api 前缀，由 post 补齐）
//   extra: (moduleCode) => 额外 payload（发给后端，与 rows 合并）
//   afterImport: 导入成功后是否刷新列表（默认 true）

const M = (o) => o

// 主档通用：调 /base/master/import + moduleCode
const masterPreset = (moduleCode, opts) => ({
  endpoint: '/base/master/import',
  extra: () => ({ moduleCode }),
  ...opts,
})

// customer/supplier 的详细字段较多，先给出核心必填 + 常用；用户可按模板扩展
export const IMPORT_PRESETS = {
  // ============ 基础资料 ============
  category: masterPreset('category', {
    title: '导入商品分类',
    templateName: '商品分类导入模板',
    templateHeaders: ['分类编码', '分类名称', '上级分类编码', '默认税率', '外部编码', '备注'],
    fieldMap: {
      '分类编码': 'categoryCode',
      '分类名称': 'categoryName',
      '上级分类编码': 'parentCode',
      '默认税率': 'defaultTaxRate',
      '外部编码': 'externalCode',
      '备注': 'remark',
    },
    requiredKey: 'categoryName',
    endpoint: '/base/category/import',  // 分类有独立表，走独立端点（后端可复用逻辑）
    extra: () => ({}),
  }),
  brand: masterPreset('brand', {
    title: '导入品牌',
    templateName: '品牌导入模板',
    templateHeaders: ['品牌编码', '品牌名称', '简码', '备注'],
    fieldMap: { '品牌编码': 'brandCode', '品牌名称': 'brandName', '简码': 'simpleCode', '备注': 'remark' },
    requiredKey: 'brandName',
    endpoint: '/base/brand/import',
    extra: () => ({}),
  }),
  unit: masterPreset('unit', {
    title: '导入单位',
    templateName: '单位导入模板',
    templateHeaders: ['单位编码', '单位名称', '是否中单位', '是否大单位', '备注'],
    fieldMap: { '单位编码': 'unitCode', '单位名称': 'unitName', '是否中单位': 'canMiddleUnit', '是否大单位': 'canLargeUnit', '备注': 'remark' },
    requiredKey: 'unitName',
    endpoint: '/base/unit/import',
    extra: () => ({}),
  }),
  warehouse: masterPreset('warehouse', {
    title: '导入仓库',
    templateName: '仓库导入模板',
    templateHeaders: ['仓库编码', '仓库名称', '仓库类型', '存货类型', '成本分组', '负责人'],
    fieldMap: {
      '仓库编码': 'warehouseCode', '仓库名称': 'warehouseName',
      '仓库类型': 'warehouseType', '存货类型': 'inventoryType',
      '成本分组': 'costGroup', '负责人': 'managerName',
    },
    requiredKey: 'warehouseName',
    endpoint: '/base/warehouse/import',
    extra: () => ({}),
  }),
  territory: masterPreset('territory', {
    title: '导入片区',
    templateName: '片区导入模板',
    templateHeaders: ['片区编码', '片区名称', '备注'],
    fieldMap: { '片区编码': 'territoryCode', '片区名称': 'territoryName', '备注': 'remark' },
    requiredKey: 'territoryName',
  }),
  routeLine: masterPreset('routeLine', {
    title: '导入线路',
    templateName: '线路导入模板',
    templateHeaders: ['线路编码', '线路名称', '司机', '覆盖范围', '备注'],
    fieldMap: {
      '线路编码': 'routeLineCode', '线路名称': 'routeLineName',
      '司机': 'driver', '覆盖范围': 'coverage', '备注': 'remark',
    },
    requiredKey: 'routeLineName',
  }),
  department: masterPreset('department', {
    title: '导入部门',
    templateName: '部门导入模板',
    templateHeaders: ['部门编码', '部门名称', '上级部门编码', '人数', '备注'],
    fieldMap: {
      '部门编码': 'departmentCode', '部门名称': 'departmentName',
      '上级部门编码': 'parentCode', '人数': 'headCount', '备注': 'remark',
    },
    requiredKey: 'departmentName',
  }),
  employee: masterPreset('employee', {
    title: '导入人员',
    templateName: '人员导入模板',
    templateHeaders: ['人员编码', '姓名', '性别', '所属货主', '手机号', '身份证号', '学历', '联系地址', '部门', '是否业务员', '是否采购员', '是否库管员', '是否配送员', '备注'],
    fieldMap: {
      '人员编码': 'employeeCode', '姓名': 'employeeName', '性别': 'gender',
      '所属货主': 'ownerName', '手机号': 'mobile', '身份证号': 'idCard',
      '学历': 'education', '联系地址': 'address', '部门': 'department',
      '是否业务员': 'isSalesman', '是否采购员': 'isBuyer',
      '是否库管员': 'isWarehouseKeeper', '是否配送员': 'isDeliveryman', '备注': 'remark',
    },
    requiredKey: 'employeeName',
  }),
  owner: masterPreset('owner', {
    title: '导入货主',
    templateName: '货主导入模板',
    templateHeaders: ['货主编码', '货主名称', '货主类型', '所属平台', '备注'],
    fieldMap: { '货主编码': 'ownerCode', '货主名称': 'ownerName', '货主类型': 'ownerType', '所属平台': 'platform', '备注': 'remark' },
    requiredKey: 'ownerName',
  }),
  expenseType: masterPreset('expenseType', {
    title: '导入费用类型',
    templateName: '费用类型导入模板',
    templateHeaders: ['费用类型编码', '费用类型名称', '上级费用类型编码', '费用方向', '成本参与', '备注'],
    fieldMap: {
      '费用类型编码': 'expenseTypeCode', '费用类型名称': 'expenseTypeName',
      '上级费用类型编码': 'parentCode', '费用方向': 'direction',
      '成本参与': 'costParticipation', '备注': 'remark',
    },
    requiredKey: 'expenseTypeName',
  }),
  fundAccount: masterPreset('fundAccount', {
    title: '导入资金账户',
    templateName: '资金账户导入模板',
    templateHeaders: ['账户编码', '账户名称', '上级账户编码', '账户类型', '期初余额', '备注'],
    fieldMap: {
      '账户编码': 'fundAccountCode', '账户名称': 'fundAccountName',
      '上级账户编码': 'parentCode', '账户类型': 'accountType',
      '期初余额': 'balance', '备注': 'remark',
    },
    requiredKey: 'fundAccountName',
  }),
  priceGroup: masterPreset('priceGroup', {
    title: '导入价格组',
    templateName: '价格组导入模板',
    templateHeaders: ['价格组编码', '价格组名称', '是否启用', '排序', '备注'],
    fieldMap: {
      '价格组编码': 'priceGroupCode', '价格组名称': 'priceGroupName',
      '是否启用': 'enabled', '排序': 'sortOrder', '备注': 'remark',
    },
    requiredKey: 'priceGroupName',
  }),
  counterpartyType: masterPreset('counterpartyType', {
    title: '导入往来单位类型',
    templateName: '往来单位类型导入模板',
    templateHeaders: ['编号', '类型名称'],
    fieldMap: { '编号': 'typeCode', '类型名称': 'typeName' },
    requiredKey: 'typeName',
  }),

  // ============ 往来单位（三合一下拉） ============
  counterparty: M({
    title: '导入往来单位',
    templateName: '往来单位导入模板',
    templateHeaders: ['往来单位编码', '往来单位名称', '单位类型编码', '联系人', '电话', '备注'],
    fieldMap: {
      '往来单位编码': 'counterpartyCode',
      '往来单位名称': 'counterpartyName',
      '单位类型编码': 'typeCode',
      '联系人': 'contactName',
      '电话': 'phone',
      '备注': 'remark',
    },
    requiredKey: 'counterpartyName',
    endpoint: '/base/master/counterparty/import',
    extra: () => ({ kind: 'counterparty' }),
  }),
  counterpartyBank: M({
    title: '导入银行账号',
    templateName: '往来单位银行账号导入模板',
    templateHeaders: ['往来单位编码', '户名', '开户银行', '银行账号', '支行', '是否默认', '备注'],
    fieldMap: {
      '往来单位编码': 'counterpartyCode',
      '户名': 'accountName',
      '开户银行': 'bankName',
      '银行账号': 'bankAccountNo',
      '支行': 'branchName',
      '是否默认': 'isDefault',
      '备注': 'remark',
    },
    requiredKey: 'bankAccountNo',
    endpoint: '/base/master/counterparty/import',
    extra: () => ({ kind: 'bank' }),
    // 子表导入不需要刷新主档列表
    afterImport: 'none',
  }),
  counterpartyInvoice: M({
    title: '导入发票信息',
    templateName: '往来单位发票信息导入模板',
    templateHeaders: ['往来单位编码', '发票抬头', '税号', '开户行', '银行账号', '地址', '电话', '是否默认'],
    fieldMap: {
      '往来单位编码': 'counterpartyCode',
      '发票抬头': 'invoiceTitle',
      '税号': 'taxNo',
      '开户行': 'bankName',
      '银行账号': 'bankAccountNo',
      '地址': 'address',
      '电话': 'phone',
      '是否默认': 'isDefault',
    },
    requiredKey: 'invoiceTitle',
    endpoint: '/base/master/counterparty/import',
    extra: () => ({ kind: 'invoice' }),
    afterImport: 'none',
  }),

  // ============ 导入查询（filter 模式）：本地读 Excel → 拼查询条件，不发后端 upsert ============
  priceGroupItemQuery: {
    mode: 'filter',
    title: '导入查询 - 价格组商品',
    templateName: '价格组商品查询导入模板',
    templateHeaders: ['商品编号'],
    fieldMap: { '商品编号': 'goodsCode' },
    requiredKey: 'goodsCode',
  },
  priceLogQuery: {
    mode: 'filter',
    title: '导入查询 - 价格变价',
    templateName: '价格变价查询导入模板',
    templateHeaders: ['商品编号'],
    fieldMap: { '商品编号': 'goodsCode' },
    requiredKey: 'goodsCode',
  },
  // 报损单导入：按仓库分组生成报损单
  damage: {
    title: '导入报损单',
    templateName: '报损单导入模板',
    templateHeaders: ['仓库', '商品编号', '批次号', '生产日期', '数量'],
    fieldMap: {
      '仓库': 'warehouse',
      '商品编号': 'goodsCode',
      '批次号': 'batchNo',
      '生产日期': 'productionDate',
      '数量': 'qty',
    },
    requiredKey: 'warehouse',
    endpoint: '/inventory/damage/import',
    extra: () => ({}),
  },
  // 其他入库单导入：按「仓库 + 单据日期 + 客户/供应商」分组生成其他入库单
  otherInbound: {
    title: '导入其他入库单',
    templateName: '其他入库单导入模板',
    templateHeaders: ['单据日期', '客户', '供应商', '仓库', '商品编号', '批次号', '生产日期', '数量'],
    fieldMap: {
      '单据日期': 'billDate',
      '客户': 'customer',
      '供应商': 'supplier',
      '仓库': 'warehouse',
      '商品编号': 'goodsCode',
      '批次号': 'batchNo',
      '生产日期': 'productionDate',
      '数量': 'qty',
    },
    requiredKey: 'warehouse',
    endpoint: '/inventory/other-inbound/import',
    extra: () => ({}),
  },
  // 其他出库单导入：按「仓库 + 单据日期 + 客户/供应商」分组生成其他出库单
  otherOutbound: {
    title: '导入其他出库单',
    templateName: '其他出库单导入模板',
    templateHeaders: ['单据日期', '客户', '供应商', '仓库', '商品编号', '批次号', '生产日期', '数量'],
    fieldMap: {
      '单据日期': 'billDate',
      '客户': 'customer',
      '供应商': 'supplier',
      '仓库': 'warehouse',
      '商品编号': 'goodsCode',
      '批次号': 'batchNo',
      '生产日期': 'productionDate',
      '数量': 'qty',
    },
    requiredKey: 'warehouse',
    endpoint: '/inventory/other-outbound/import',
    extra: () => ({}),
  },
}

// 模块级默认 preset key（`action 导入` 点击时使用）
export const MODULE_DEFAULT_IMPORT = {
  category: 'category',
  brand: 'brand',
  unit: 'unit',
  warehouse: 'warehouse',
  territory: 'territory',
  routeLine: 'routeLine',
  department: 'department',
  employee: 'employee',
  owner: 'owner',
  expenseType: 'expenseType',
  fundAccount: 'fundAccount',
  priceGroup: 'priceGroup',
  counterpartyType: 'counterpartyType',
  counterparty: 'counterparty',
  damage: 'damage',
  otherInbound: 'otherInbound',
  otherOutbound: 'otherOutbound',
}

// counterparty 特殊：需要下拉三选一
export const MODULE_IMPORT_MENU = {
  counterparty: [
    { key: 'counterparty', label: '导入往来单位' },
    { key: 'counterpartyBank', label: '导入银行账号' },
    { key: 'counterpartyInvoice', label: '导入发票信息' },
  ],
}
