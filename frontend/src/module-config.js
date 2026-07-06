export const moduleConfigs = {
  goods: {
    type: 'base', mode: 'page', title: '商品档案', desc: '复杂档案：多单位、条码、价格、库存上下限、供应商关系',
    filters: ['商品编码/名称/条码', '商品分类', '品牌', '供应商', '状态', '是否可售', '是否可采购', '有无库存'],
    columns: ['商品图片', '商品编码', '商品名称', '规格', '分类', '品牌', '基本单位', '条码', '标准售价', '参考进价', '最低售价', '可售/可采购', '当前库存', '状态', '操作'],
    row: [],
    actions: ['新增商品', '批量编辑', '导入商品', '导出'],
    sections: ['基础信息', '单位与条码', '价格信息', '采购与库存', '附件/日志'],
  },
  category: {
    type: 'base', mode: 'modal', tree: true, title: '商品分类', desc: '三级分类树：新建先选上级分类，分类编号两位，状态默认正常',
    treeNodes: [],
    filters: ['分类名称/编码', '状态'], columns: ['分类编码', '分类名称', '上级分类', '默认税率', '商品数', '状态', '操作'],
    row: [], actions: ['新建分类', '导入', '导出'], sections: ['上级分类', '分类编号', '分类名称', '默认税率', '状态'],
    formFields: ['上级分类', '分类编号', '分类名称', '默认税率', '状态'],
  },
  customer: {
    type: 'base', mode: 'drawer', title: '门店/客户资料', desc: '中等资料：地址、账期、信用额度、开票信息、线路片区',
    filters: ['客户编码/名称/手机号', '客户类型', '客户等级', '片区', '线路', '业务员', '状态', '是否超信用', '是否逾期'],
    columns: ['客户编码', '客户名称', '联系人', '手机号', '片区', '线路', '业务员', '客户等级', '结算方式', '信用额度', '应收余额', '逾期金额', '状态', '操作'],
    row: [],
    actions: ['新增客户', '批量修改', '导入修改', '导出'], sections: ['基本信息', '地址信息', '账期设置', '开票信息', '操作日志'],
  },
  supplier: {
    type: 'base', mode: 'drawer', title: '供应商资料', desc: '中等资料：到货天数、结算方式、收款账户、发票联系人',
    filters: ['供应商编码/名称/联系人', '供应商类型', '结算方式', '默认采购员', '状态'],
    columns: ['供应商编码', '供应商名称', '类型', '联系人', '电话', '到货天数', '结算方式', '默认采购员', '默认收款账户', '应付余额', '状态', '操作'],
    row: [],
    actions: ['新增供应商', '批量修改', '导入修改', '导出'], sections: ['基础信息', '结算设置', '收款账户', '发票信息', '联系人'],
  },
  warehouse: {
    type: 'base', mode: 'modal', title: '仓库资料', desc: '简单资料：仓库、成本分组、库存类型、负责人',
    filters: ['仓库编码/名称', '仓库类型', '成本分组', '状态'], columns: ['仓库编码', '仓库名称', '仓库类型', '库存类型', '成本分组', '负责人', '状态', '操作'],
    row: [], actions: ['新增仓库', '导出'], sections: ['仓库编码', '仓库名称', '仓库类型', '库存类型', '成本分组', '负责人', '状态'],
  },
  priceGroup: {
    type: 'base', mode: 'modal', title: '价格组设置', desc: '客户等级价与价格组启用维护',
    filters: ['价格组名称', '状态'], columns: ['价格组编码', '价格组名称', '是否启用', '排序', '备注', '操作'], row: [], actions: ['新增价格组', '导出'], sections: ['价格组编码', '价格组名称', '启用状态', '排序', '备注'],
  },
  purchaseOrder: {
    type: 'purchase', mode: 'bill', title: '采购订单', desc: '供应商订货起点，审核后形成采购在途',
    filters: ['单据日期', '采购单号', '供应商', '采购员', '仓库', '状态', '到货状态', '商品'],
    columns: ['采购单号', '单据日期', '供应商', '采购员', '收货仓库', '订单金额', '已入库金额', '付款状态', '状态', '到货状态', '创建人/时间', '操作'],
    row: [],
    actions: ['新建采购订单', '导入', '导出主单', '导出明细', '打印'],
    sections: ['头部信息：供应商/采购员/仓库/日期/预计到货/结算方式', '商品明细：商品/单位/数量/单价/税率/金额/已入库', '金额汇总：商品金额/税额/折扣/订单金额', '备注附件'],
    tips: ['审核后形成采购在途', '已入库订单不可直接反审核'],
  },
  purchaseInbound: {
    type: 'purchase', mode: 'bill', title: '采购入库', desc: '引入采购订单，审核后增加库存、生成流水、重算成本、生成采购收货单',
    filters: ['入库日期', '入库单号', '采购单号', '供应商', '仓库', '状态', '商品'],
    columns: ['入库单号', '入库日期', '采购单号', '供应商', '仓库', '入库数量', '入库金额', '状态', '是否更新库存', '是否生成收货单', '操作'],
    row: [],
    actions: ['新建采购入库', '引入采购订单', '导出'], sections: ['来源采购订单', '实收数量与批次', '成本计算', '生成采购收货单'], tips: ['实收数量不可超过订单剩余数量', '批次商品必须填写生产日期'],
  },
  purchaseReceipt: {
    type: 'purchase', mode: 'bill', title: '采购收货单', desc: '采购结算依据，审核后生成正式应付',
    filters: ['收货日期', '收货单号', '采购单号', '入库单号', '供应商', '状态', '应付生成状态'],
    columns: ['收货单号', '来源入库单', '采购单号', '供应商', '仓库', '商品金额', '税额', '最终金额', '状态', '应付生成状态', '操作'],
    row: [],
    actions: ['导出', '打印'], sections: ['来源入库明细', '费用分摊', '应付生成'], tips: ['审核后生成应付账款', '已核销应付不可反审核'],
  },
  quickOrder: {
    type: 'sales', mode: 'bill', title: '销售快速开单', desc: '最高频页面：客户、商品、收款、审核并打印一屏完成',
    filters: ['客户搜索F2', '仓库', '业务员', '结算方式'],
    columns: ['商品', '仓库', '业务单位', '开单数量', '换算率', '基本单位数量', '可用库存', '单价', '金额', '成本', '毛利', '赠品'],
    row: [],
    actions: ['挂单', '保存草稿', '审核并打印', '添加赠品', '常购商品'], sections: ['客户欠款/信用额度', '商品扫码F3', '组合收款', '金额汇总'], tips: ['支持 F2/F3/F4/F7/F9/Ctrl+Enter', '库存不足阻断审核'],
  },
  salesOrder: {
    type: 'sales', mode: 'bill', title: '销售订单', desc: '客户订货，审核后锁定库存',
    filters: ['订单日期', '订单号', '客户', '业务员', '仓库', '状态', '收款状态', '出库状态', '商品'],
    columns: ['订单号', '日期', '客户', '业务员', '仓库', '订单金额', '已收金额', '未收金额', '信用检查', '库存检查', '状态', '出库状态', '签收状态', '操作'],
    row: [],
    actions: ['新建销售订单', '导入', '导出主单', '导出明细', '打印'], sections: ['客户信用与欠款', '价格匹配', '库存校验', '明细与金额'], tips: ['审核校验客户状态、最低售价、信用额度、可用库存'],
  },
  salesOutbound: {
    type: 'sales', mode: 'bill', title: '销售出库', desc: '引入已审核销售订单，审核后扣库存、释放锁定、生成销售收货单',
    filters: ['出库日期', '出库单号', '销售单号', '客户', '仓库', '状态'], columns: ['出库单号', '销售单号', '客户', '仓库', '出库日期', '出库数量', '销售金额', '成本金额', '生成收货单', '状态', '操作'],
    row: [], actions: ['新建出库', '引入销售订单', '导出'], sections: ['来源销售订单', '本次出库数量', '批次与成本', '生成销售收货单'], tips: ['出库数量不可超过未出库数量', '出库不生成正式应收'],
  },
  salesReceipt: {
    type: 'sales', mode: 'bill', title: '销售收货单', desc: '客户签收确认，审核后生成最终应收',
    filters: ['签收日期', '收货单号', '销售单号', '客户', '签收状态', '应收生成', '状态'], columns: ['收货单号', '销售单号', '客户', '签收日期', '签收金额', '应收生成', '签收状态', '状态', '操作'], row: [], actions: ['确认签收', '导出', '打印'], sections: ['签收数量', '差异原因', '账期计算', '生成应收'], tips: ['签收数量不可大于出库数量', '已核销应收不可反审核'],
  },
  stockBalance: {
    type: 'inventory', mode: 'readonly', title: '库存余额', desc: '库存余额不可直接编辑，可查看流水、批次、锁定',
    filters: ['货主', '仓库', '商品', '分类', '品牌', '库存状态', '仅看有库存', '低于下限'], columns: ['商品', '仓库', '实物库存', '锁定库存', '冻结库存', '可用库存', '采购在途', '成本单价', '库存金额', '最近出入库时间', '操作'], row: [], actions: ['导出', '库存调整'], sections: ['库存口径', '成本权限', '低库存预警'], tips: ['可用库存=实物-锁定-冻结', '成本字段受权限控制'],
  },
  stockLedger: {
    type: 'inventory', mode: 'readonly', title: '库存流水', desc: '库存流水不可删除，反审核生成反向流水',
    filters: ['发生日期', '商品', '仓库', '来源单据类型', '来源单号', '出入方向', '操作人'], columns: ['流水号', '发生时间', '来源单据', '商品', '仓库', '批次', '方向', '数量', '成本单价', '金额', '变动后数量', '操作人'], row: [], actions: ['导出'], sections: ['来源追溯', '库存变动', '成本记录'], tips: ['点击来源单据可跳转'],
  },
  ar: {
    type: 'finance', mode: 'tabs', title: '应收账款', desc: '销售收货单审核后生成，应收支持收款核销',
    filters: ['应收日期', '应收单号', '客户/往来单位', '核销状态', '逾期状态'], columns: ['应收单号', '来源销售收货单', '客户', '业务员', '应收金额', '已收金额', '未收金额', '预计收款日', '逾期天数', '核销状态', '开票状态', '操作'], row: [], actions: ['发起收款', '核销', '导出'], sections: ['应收明细', '应收汇总', '账龄分析', '逾期预警'], tips: ['核销后更新已收/未收', '逾期状态按预计结款日计算'],
  },
  ap: {
    type: 'finance', mode: 'tabs', title: '应付账款', desc: '采购收货单审核后生成，应付支持付款核销',
    filters: ['应付日期', '应付单号', '供应商/往来单位', '核销状态', '到期状态'], columns: ['应付单号', '来源采购收货单', '供应商', '应付金额', '已付金额', '未付金额', '预计付款日', '核销状态', '操作'], row: [], actions: ['发起付款', '核销', '导出'], sections: ['应付明细', '应付汇总', '账龄分析', '付款排期'], tips: ['付款核销后更新应付余额'],
  },
}

const simpleBase = ['brand','unit','routeLine','department','owner','expenseType','counterparty','fundAccount','employee','territory']
simpleBase.forEach(code => {
  const names = { brand:'品牌管理', unit:'单位管理', routeLine:'线路管理', department:'部门管理', owner:'货主信息', expenseType:'费用类型', counterparty:'往来单位', fundAccount:'资金账户', employee:'人员信息', territory:'片区管理' }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'base', mode: 'modal', title: names[code], desc: '基础资料维护：查询、新建、编辑、停用、导入导出',
    filters: ['关键字', '状态'], columns: ['编码', '名称', '类型/分组', '状态', '备注', '操作'], row: [], actions: ['新建', '批量编辑', '导入', '导出'], sections: ['基础信息', '状态与备注'], formFields: ['编码', '名称', '类型/分组', '状态', '备注'],
  }
})

const inventoryExtra = ['stockLock','batchStock','stockWarning','transfer','damage','costAdjust','stockAdjust','otherInbound','otherOutbound','stockTake']
inventoryExtra.forEach(code => {
  const names = { stockLock:'库存锁定', batchStock:'批次库存', stockWarning:'库存预警', transfer:'调拨单', damage:'报损单', costAdjust:'成本调整单', stockAdjust:'库存调整单', otherInbound:'其他入库', otherOutbound:'其他出库', stockTake:'库存盘点' }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'inventory', mode: 'bill', title: names[code], desc: '库存增强模块：审核后才影响库存或成本，成本字段受权限控制',
    filters: ['日期', '单号', '仓库', '商品', '状态'], columns: ['单号/商品', '仓库', '业务类型', '数量', '成本金额', '状态', '操作'], row: [], actions: ['新建', '审核', '导出', '打印'], sections: ['基本信息', '商品明细', '成本汇总', '附件日志'], tips: ['审核后不可修改', '已结账期间不可反审核'],
  }
})

const purchaseExtra = ['purchaseReturn','purchaseExpense','purchaseInvoice']
purchaseExtra.forEach(code => {
  const names = { purchaseReturn:'采购退货单', purchaseExpense:'采购费用单', purchaseInvoice:'采购发票' }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'purchase', mode: 'bill', title: names[code], desc: '采购增强模块：费用、发票、退货与应付/成本联动',
    filters: ['日期', '单号', '供应商', '状态', '商品'], columns: ['单号', '供应商', '业务类型', '金额', '应付/勾稽状态', '状态', '操作'], row: [], actions: ['新建', '导入', '导出', '打印'], sections: ['基本信息', '关联入库/收货', '费用/发票明细', '应付影响'], tips: ['采购费用审核后可分摊重算成本', '发票勾稽关联应付'],
  }
})

const salesExtra = ['salesReturn','salesInvoice','flyOrder','emptyAdjust']
salesExtra.forEach(code => {
  const names = { salesReturn:'销售退货单', salesInvoice:'销售发票', flyOrder:'飞单', emptyAdjust:'客户空退空出' }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'sales', mode: 'bill', title: names[code], desc: '销售增强模块：退货、发票、飞单、账务调整',
    filters: ['日期', '单号', '客户', '状态', '商品'], columns: ['单号', '客户', '业务类型', '金额', '应收/开票状态', '状态', '操作'], row: [], actions: ['新建', '导入', '导出', '打印'], sections: ['基本信息', '商品/发票明细', '库存/应收影响', '日志'], tips: ['飞单不影响库存但生成销售与采购闭环', '空退空出不经过仓库，仅调整应收'],
  }
})

const financeExtra = ['receiptPayment','arSettlement','apSettlement','financeExpense','fundLedger','counterpartyAr','counterpartyAp','receiptVerify','paymentVerify','customerStatement','supplierStatement']
financeExtra.forEach(code => {
  const names = { receiptPayment:'收付款单', arSettlement:'应收结算', apSettlement:'应付结算', financeExpense:'费用单', fundLedger:'资金流水', counterpartyAr:'往来单位应收', counterpartyAp:'往来单位应付', receiptVerify:'收款核销', paymentVerify:'付款核销', customerStatement:'客户对账', supplierStatement:'供应商对账' }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'finance', mode: 'bill', title: names[code], desc: '财务模块：收付款、核销、费用、资金流水',
    filters: ['日期', '单号', '对象', '状态', '金额'], columns: ['单号', '对象', '来源单据', '金额', '已核销/余额', '状态', '操作'], row: [], actions: ['新建', '审核', '核销', '导出'], sections: ['对象信息', '收付款信息', '核销明细', '资金流水'], tips: ['收付款审核后生成资金流水', '结算只能选择同一对象的应收/应付'],
  }
})

const systemExtra = ['precision','dictionary','workflow','printTemplate','importList','exportCenter']
systemExtra.forEach(code => {
  const names = { precision:'显示精度设置', dictionary:'用户数据字典', workflow:'审批流配置', printTemplate:'打印模板设置', importList:'导入列表', exportCenter:'导出中心' }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'system', mode: 'modal', title: names[code], desc: '系统配置与运维管理：参数、模板、审批、导入导出任务',
    filters: ['关键字', '状态', '分组'], columns: ['编码', '名称', '类型/分组', '状态', '备注', '操作'], row: [], actions: ['新建', '保存配置', '导入', '导出'], sections: ['基础信息', '配置项', '状态与备注'], formFields: ['编码', '名称', '类型/分组', '状态', '备注'],
  }
})

moduleConfigs.importList = {
  ...moduleConfigs.importList,
  mode: 'readonly',
  desc: '导入列表：查看导入任务状态、成功/失败行数，失败任务可下载原因',
  columns: ['任务号', '任务名称', '模块编码', '状态', '文件名', '成功行数', '失败行数', '结果说明', '创建时间', '完成时间', '操作'],
  row: [],
  actions: ['刷新', '导入'],
}

moduleConfigs.exportCenter = {
  ...moduleConfigs.exportCenter,
  mode: 'readonly',
  desc: '导出中心：查看异步导出任务状态，已完成任务可下载文件',
  columns: ['任务号', '报表名称', '模块编码', '状态', '文件名', '筛选条件', '创建时间', '完成时间', '操作'],
  row: [],
  actions: ['刷新', '导出'],
}

Object.assign(moduleConfigs, {
  user: {
    type: 'system', mode: 'drawer', title: '用户管理', desc: '系统用户、角色、数据范围与启停维护',
    filters: ['账号/姓名/手机号', '权限组', '数据范围', '状态'],
    columns: ['账号', '姓名', '手机', '权限组', '数据范围', '状态', '操作'],
    row: [],
    actions: ['新建用户', '批量停用', '导入', '导出'], sections: ['账号信息', '角色权限', '数据范围', '状态日志'],
  },
  role: {
    type: 'system', mode: 'drawer', title: '权限组管理', desc: '权限组、菜单权限、字段权限与用户数维护',
    filters: ['权限组编码/名称', '状态'],
    columns: ['权限组编码', '权限组名称', '用户数', '菜单权限', '字段权限', '状态', '操作'],
    row: [],
    actions: ['新建权限组', '复制权限组', '导出'], sections: ['基础信息', '菜单权限', '字段权限', '数据权限'],
  },
  param: {
    type: 'system', mode: 'modal', title: '系统参数', desc: '全局业务参数、默认值和分组维护',
    filters: ['参数键/名称', '参数分组'],
    columns: ['参数键', '参数名称', '当前值', '默认值', '分组', '备注', '操作'],
    row: [],
    actions: ['保存配置', '恢复默认', '导出'], sections: ['参数信息', '变更说明'],
  },
  billNo: {
    type: 'system', mode: 'modal', title: '单据编号规则', desc: '单据前缀、日期格式、流水位数和重置周期维护',
    filters: ['单据类型', '状态'],
    columns: ['单据类型', '前缀', '日期格式', '流水位数', '重置周期', '示例', '状态', '操作'],
    row: [],
    actions: ['新建规则', '保存配置', '导出'], sections: ['编号规则', '流水控制', '示例预览'],
  },
  log: {
    type: 'system', mode: 'readonly', title: '操作日志', desc: '系统关键操作、结果和业务号追溯',
    filters: ['操作时间', '操作人', '模块', '动作', '结果', '业务号'],
    columns: ['操作时间', '操作人', '模块', '动作', '业务号', '结果', '详情', '操作'],
    row: [],
    actions: ['刷新', '导出'], sections: ['日志列表', '详情追溯'],
  },
})

Object.assign(moduleConfigs, {
  customerPrice: {
    type: 'base', mode: 'bill', title: '客户价格调整单', desc: '客户专属价格调整，审核后生效并自动停用历史有效价',
    filters: ['调整单号', '客户', '生效方式', '生效状态', '审核状态', '商品'],
    columns: ['调整单号', '客户编号/名称', '单据日期', '生效方式', '生效时间', '有效期', '商品数', '审核信息', '状态', '操作'],
    row: [],
    actions: ['新建调整单', '导入价格', '审核', '作废', '导出'], sections: ['客户与生效规则', '调价商品明细', '审核日志'],
  },
  customerPriceQuery: {
    type: 'base', mode: 'readonly', title: '客户价格查询', desc: '查询客户当前价、历史价与停用专属价',
    filters: ['客户', '商品', '生效状态', '有效期', '调整单号'],
    columns: ['客户编号/名称', '商品编码', '商品名称', '单位', '规格', '条码', '原价', '现价', '最新进价', '成本价', '生效方式', '有效期', '生效状态', '操作'],
    row: [],
    actions: ['刷新', '停用价格', '导出'], sections: ['价格列表', '来源追溯'],
  },
})

Object.assign(moduleConfigs, {
  transfer: {
    type: 'inventory', mode: 'bill', title: '调拨单', desc: '仓库间调拨，支持一步/两步调拨和调拨在途',
    filters: ['调拨日期', '调拨单号', '调出仓库', '调入仓库', '调拨类型', '调拨模式', '状态', '商品'],
    columns: ['调拨单号', '调拨日期', '调出仓库', '调入仓库', '调拨类型', '调拨模式', '调拨数量', '在途数量', '成本金额', '状态', '操作'],
    row: [],
    actions: ['新建调拨单', '审核', '调拨出库', '调拨入库', '关闭', '导出', '打印'], sections: ['调拨头信息', '商品明细', '调拨在途', '库存影响'],
  },
  purchaseExpense: {
    type: 'purchase', mode: 'bill', title: '采购费用单', desc: '采购费用登记、分摊、成本重算与应付生成',
    filters: ['费用日期', '费用单号', '费用类型', '收款对象', '分摊状态', '状态'],
    columns: ['费用单号', '费用日期', '费用类型', '收款对象类型', '收款对象', '费用金额', '税额', '含税金额', '已分摊金额', '未分摊金额', '分摊方式', '分摊状态', '是否生成应付', '状态', '操作'],
    row: [],
    actions: ['新建费用单', '费用分摊', '审核', '反审核', '导入', '导出', '打印'], sections: ['费用信息', '关联入库/收货', '分摊明细', '应付影响'],
  },
  purchaseInvoice: {
    type: 'purchase', mode: 'bill', title: '采购发票', desc: '采购发票登记、应付勾稽与认证状态维护',
    filters: ['发票日期', '发票号码', '供应商', '发票类型', '勾稽状态', '认证状态', '状态'],
    columns: ['采购发票单号', '发票号码', '发票代码', '发票类型', '供应商', '开票日期', '不含税金额', '税额', '含税金额', '已勾稽金额', '未勾稽金额', '勾稽状态', '认证状态', '状态', '操作'],
    row: [],
    actions: ['新建发票', '发票勾稽', '认证', '审核', '导入', '导出'], sections: ['发票信息', '关联应付', '勾稽明细', '认证信息'],
  },
  financeExpense: {
    type: 'finance', mode: 'bill', title: '费用单', desc: '费用收入/支出登记，可生成往来或直接收付款',
    filters: ['费用日期', '费用方向', '费用类型', '往来对象', '状态'],
    columns: ['费用单号', '费用日期', '费用方向', '费用类型', '往来对象类型', '往来对象', '费用金额', '税额', '是否生成往来', '往来单据', '是否直接收付款', '收付款单号', '状态', '操作'],
    row: [],
    actions: ['新建费用单', '审核', '反审核', '导入', '导出'], sections: ['费用信息', '往来生成', '收付款信息', '附件日志'],
  },
})

moduleConfigs.ar = {
  ...moduleConfigs.ar,
  actions: ['发起收款', '应收结算', '预收抵扣', '核销', '查看核销记录', '导出'],
  row: [],
}
moduleConfigs.ap = {
  ...moduleConfigs.ap,
  columns: ['应付单号', '来源采购收货单', '供应商', '应付金额', '已付金额', '未付金额', '预计付款日', '逾期天数', '核销状态', '操作'],
  row: [],
  actions: ['发起付款', '应付结算', '预付抵扣', '核销', '查看核销记录', '导出'],
}

Object.assign(moduleConfigs, {
  goods: {
    ...moduleConfigs.goods,
    filters: ['商品编码/名称/条码', '商品类型', '商品分类', '品牌', '供应商', '存储属性', '状态', '是否可售', '是否可采购', '低于库存下限'],
    columns: ['商品图片', '商品编码', '商品名称', '商品类型', '规格', '分类', '品牌', '基本单位', '条码', '保质期', '存储属性', '标准售价', '建议零售价', '参考进价', '最低售价', '库存上限', '库存下限', '默认供应商', '默认仓库', '可售/可采购/可退', '当前库存', '状态', '操作'],
    row: [],
    actions: ['新增商品', '批量编辑', '导入商品', '导出'],
    sections: ['基础信息', '单位与条码', '价格组1-10', '采购与库存', '保质期/存储属性', '附件/日志'],
  },
  customer: {
    ...moduleConfigs.customer,
    columns: ['客户编码', '客户名称', '渠道类型', '联系人', '手机号', '片区', '线路', '业务员', '客户等级', '账期类型', '截账日', '付款日', '信用额度', '应收余额', '逾期金额', '发票抬头', '税号', '状态', '操作'],
    row: [],
    actions: ['新增客户', '批量修改', '导入修改', '冻结', '解冻', '导出'],
  },
  supplier: {
    ...moduleConfigs.supplier,
    columns: ['供应商编码', '供应商名称', '供应商简称', '供应商类型', '联系人', '电话', '到货天数', '结算方式', '账期天数', '默认采购员', '默认收款账户', '发票抬头', '税号', '应付余额', '状态', '操作'],
    row: [],
    actions: ['新增供应商', '批量修改', '导入修改', '维护收款账户', '导出'],
  },
  warehouse: {
    ...moduleConfigs.warehouse,
    filters: ['仓库编码/名称', '货主', '仓库类型', '成本分组', '状态'],
    columns: ['仓库编码', '仓库名称', '货主', '仓库类型', '库存类型', '成本分组', '负责人', '地址', '是否默认', '状态', '操作'],
    row: [],
    sections: ['货主', '仓库编码', '仓库名称', '仓库类型', '库存类型', '成本分组', '地址', '是否默认', '状态'],
  },
  purchaseOrder: {
    ...moduleConfigs.purchaseOrder,
    columns: ['采购单号', '单据日期', '货主', '供应商', '采购员', '收货仓库', '预计到货日期', '结算方式', '订单金额', '已入库金额', '成本金额', '付款状态', '状态', '到货状态', '创建人/时间', '操作'],
    row: [],
    actions: ['新建采购订单', '保存草稿', '审核', '反审核', '终止', '删除', '导入', '导出主单', '导出明细', '打印'],
    sections: ['头部信息：货主/供应商/采购员/仓库/日期/预计到货/结算方式', '商品明细：行类型/商品/单位/数量/单价/税率/金额/成本', '金额汇总：商品金额/税额/折扣/订单金额', '备注附件'],
  },
  purchaseInbound: {
    ...moduleConfigs.purchaseInbound,
    columns: ['入库单号', '入库日期', '采购单号', '供应商', '仓库', '库位', '应入数量', '实收数量', '批次号', '生产日期', '到期日期', '入库前成本', '入库后成本', '分摊费用', '入库金额', '状态', '是否更新库存', '是否生成收货单', '操作'],
    row: [],
  },
  quickOrder: {
    ...moduleConfigs.quickOrder,
    columns: ['商品', '仓库', '业务单位', '开单数量', '换算率', '基本单位数量', '可用库存', '单价', '折扣', '金额', '成本', '毛利', '赠品'],
    row: [],
    actions: ['挂单', '保存草稿', '审核并打印', '添加赠品', '常购商品', '历史订单', '组合收款'],
    sections: ['客户欠款/信用额度', '商品扫码F3', '组合收款：现金/微信/支付宝/银行卡/赊账/预收款', '金额汇总'],
    tips: ['支持 F2/F3/F4/F5/F6/F7/F8/F9/F10/Ctrl+Enter', '库存不足阻断审核'],
  },
  salesOrder: {
    ...moduleConfigs.salesOrder,
    filters: ['订单日期', '订单号', '客户', '业务员', '仓库', '创建人', '状态', '收款状态', '出库状态', '商品'],
    columns: ['订单号', '日期', '客户', '业务员', '仓库', '行类型', '订单金额', '已收金额', '未收金额', '成本金额', '信用检查', '库存检查', '状态', '出库状态', '签收状态', '创建人', '操作'],
    row: [],
    actions: ['新建销售订单', '保存草稿', '审核', '反审核', '关闭', '删除', '导入', '导出主单', '导出明细', '打印'],
  },
  salesOutbound: {
    ...moduleConfigs.salesOutbound,
    columns: ['出库单号', '销售单号', '客户', '仓库', '出库日期', '出库数量', '批次号', '销售金额', '成本金额', '生成收货单', '状态', '操作'],
    row: [],
  },
  salesReceipt: {
    ...moduleConfigs.salesReceipt,
    actions: ['确认签收', '修改签收单价', '审核', '反审核', '查看应收', '导出', '打印'],
    row: [],
  },
})

const reportExtra = ['salesReport','purchaseReport','stockReport','financeReport']
reportExtra.forEach(code => {
  const names = { salesReport:'销售报表', purchaseReport:'采购报表', stockReport:'库存报表', financeReport:'财务报表' }
  const columns = {
    salesReport: ['单据日期', '客户', '业务员', '仓库', '销售金额', '已收金额', '未收金额', '毛利', '状态', '操作'],
    purchaseReport: ['单据日期', '供应商', '采购员', '仓库', '采购金额', '入库金额', '付款状态', '状态', '操作'],
    stockReport: ['商品编码', '商品名称', '仓库', '实物库存', '锁定库存', '可用库存', '成本单价', '库存金额', '最近出入库时间', '操作'],
    financeReport: ['报表类型', '对象名称', '金额', '已核销金额', '余额', '预计日期', '状态', '操作'],
  }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'report', mode: 'readonly', title: names[code], desc: '报表中心：按业务单据与余额汇总，支持查询、导出与来源追溯',
    filters: ['日期范围', '对象', '仓库', '商品', '状态'], columns: columns[code], row: [], actions: ['刷新', '导出'], sections: ['汇总指标', '明细列表', '来源追溯'], tips: ['报表数据来源于已审核业务单据和余额流水', '导出任务进入导出中心'],
  }
})
