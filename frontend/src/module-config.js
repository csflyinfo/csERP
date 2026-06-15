export const moduleConfigs = {
  goods: {
    type: 'base', mode: 'page', title: '商品档案', desc: '复杂档案：多单位、条码、价格、库存上下限、供应商关系',
    filters: ['商品编码/名称/条码', '商品分类', '品牌', '供应商', '状态', '是否可售', '是否可采购', '有无库存'],
    columns: ['商品图片', '商品编码', '商品名称', '规格', '分类', '品牌', '基本单位', '条码', '标准售价', '参考进价', '最低售价', '可售/可采购', '当前库存', '状态', '操作'],
    row: ['-', 'SP001', '农夫山泉500ml*24', '500ml*24', '瓶装水', '农夫山泉', '瓶', '6941410749551', '35.00', '31.20', '30.00', '是/是', '1200', '正常', '编辑 复制 停用 库存 历史'],
    actions: ['新增商品', '批量编辑', '导入商品', '导出'],
    sections: ['基础信息', '单位与条码', '价格信息', '采购与库存', '附件/日志'],
  },
  category: {
    type: 'base', mode: 'modal', tree: true, title: '商品分类', desc: '三级分类树：新建先选上级分类，分类编号两位，状态默认正常',
    treeNodes: ['全部分类', '食品饮料', '　饮料', '　　瓶装水', '　　茶饮料', '　方便食品', '日化百货'],
    filters: ['分类名称/编码', '状态'], columns: ['分类编码', '分类名称', '上级分类', '默认税率', '商品数', '状态', '操作'],
    row: ['010101', '瓶装水', '饮料', '13%', '32', '正常', '编辑 停用'], actions: ['新建分类', '导入', '导出'], sections: ['上级分类', '分类编号', '分类名称', '默认税率', '状态'],
    formFields: ['上级分类', '分类编号', '分类名称', '默认税率', '状态'],
  },
  customer: {
    type: 'base', mode: 'drawer', title: '门店/客户资料', desc: '中等资料：地址、账期、信用额度、开票信息、线路片区',
    filters: ['客户编码/名称/手机号', '客户类型', '客户等级', '片区', '线路', '业务员', '状态', '是否超信用', '是否逾期'],
    columns: ['客户编码', '客户名称', '联系人', '手机号', '片区', '线路', '业务员', '客户等级', '结算方式', '信用额度', '应收余额', '逾期金额', '状态', '操作'],
    row: ['C001', '华联超市', '王店长', '138****8888', '西湖区', '朝阳线', '张三', '金牌', '月结30天', '50000', '12000', '2000', '正常', '编辑 价格 应收'],
    actions: ['新增客户', '批量修改', '导入修改', '导出'], sections: ['基本信息', '地址信息', '账期设置', '开票信息', '操作日志'],
  },
  supplier: {
    type: 'base', mode: 'drawer', title: '供应商资料', desc: '中等资料：到货天数、结算方式、收款账户、发票联系人',
    filters: ['供应商编码/名称/联系人', '供应商类型', '结算方式', '默认采购员', '状态'],
    columns: ['供应商编码', '供应商名称', '类型', '联系人', '电话', '到货天数', '结算方式', '默认采购员', '默认收款账户', '应付余额', '状态', '操作'],
    row: ['G001', '农夫山泉杭州经销', '饮料供应商', '赵经理', '0571-8888', '5', '月结30天', '李四', '工商银行 6222****', '6600', '正常', '编辑 账户 应付'],
    actions: ['新增供应商', '批量修改', '导入修改', '导出'], sections: ['基础信息', '结算设置', '收款账户', '发票信息', '联系人'],
  },
  warehouse: {
    type: 'base', mode: 'modal', title: '仓库资料', desc: '简单资料：仓库、成本分组、库存类型、负责人',
    filters: ['仓库编码/名称', '仓库类型', '成本分组', '状态'], columns: ['仓库编码', '仓库名称', '仓库类型', '库存类型', '成本分组', '负责人', '状态', '操作'],
    row: ['W001', '总仓', '正常仓', '平台主仓', 'CG01', '王五', '正常', '编辑 停用'], actions: ['新增仓库', '导出'], sections: ['仓库编码', '仓库名称', '仓库类型', '库存类型', '成本分组', '负责人', '状态'],
  },
  priceGroup: {
    type: 'base', mode: 'modal', title: '价格组设置', desc: '客户等级价与价格组启用维护',
    filters: ['价格组名称', '状态'], columns: ['价格组编码', '价格组名称', '是否启用', '排序', '备注', '操作'], row: ['PRICE1', '批发价', '是', '1', '默认销售价', '编辑'], actions: ['新增价格组', '导出'], sections: ['价格组编码', '价格组名称', '启用状态', '排序', '备注'],
  },
  purchaseOrder: {
    type: 'purchase', mode: 'bill', title: '采购订单', desc: '供应商订货起点，审核后形成采购在途',
    filters: ['单据日期', '采购单号', '供应商', '采购员', '仓库', '状态', '到货状态', '商品'],
    columns: ['采购单号', '单据日期', '供应商', '采购员', '收货仓库', '订单金额', '已入库金额', '付款状态', '状态', '到货状态', '创建人/时间', '操作'],
    row: ['PO202606140001', '2026-06-14', '农夫山泉杭州经销', '李四', '总仓', '3500.00', '0.00', '未付款', '待审核', '未到货', '管理员 10:20', '查看 编辑 审核 终止 打印 日志'],
    actions: ['新建采购订单', '导入', '导出主单', '导出明细', '打印'],
    sections: ['头部信息：供应商/采购员/仓库/日期/预计到货/结算方式', '商品明细：商品/单位/数量/单价/税率/金额/已入库', '金额汇总：商品金额/税额/折扣/订单金额', '备注附件'],
    tips: ['审核后形成采购在途', '已入库订单不可直接反审核'],
  },
  purchaseInbound: {
    type: 'purchase', mode: 'bill', title: '采购入库', desc: '引入采购订单，审核后增加库存、生成流水、重算成本、生成采购收货单',
    filters: ['入库日期', '入库单号', '采购单号', '供应商', '仓库', '状态', '商品'],
    columns: ['入库单号', '入库日期', '采购单号', '供应商', '仓库', '入库数量', '入库金额', '状态', '是否更新库存', '是否生成收货单', '操作'],
    row: ['PI202606140001', '2026-06-14', 'PO202606140001', '农夫山泉杭州经销', '总仓', '100', '3500.00', '待审核', '否', '否', '查看 编辑 审核 打印'],
    actions: ['新建采购入库', '引入采购订单', '导出'], sections: ['来源采购订单', '实收数量与批次', '成本计算', '生成采购收货单'], tips: ['实收数量不可超过订单剩余数量', '批次商品必须填写生产日期'],
  },
  purchaseReceipt: {
    type: 'purchase', mode: 'bill', title: '采购收货单', desc: '采购结算依据，审核后生成正式应付',
    filters: ['收货日期', '收货单号', '采购单号', '入库单号', '供应商', '状态', '应付生成状态'],
    columns: ['收货单号', '来源入库单', '采购单号', '供应商', '仓库', '商品金额', '税额', '最终金额', '状态', '应付生成状态', '操作'],
    row: ['PR202606140001', 'PI202606140001', 'PO202606140001', '农夫山泉杭州经销', '总仓', '3500.00', '455.00', '3955.00', '待审核', '未生成', '查看 审核 打印 查看应付'],
    actions: ['导出', '打印'], sections: ['来源入库明细', '费用分摊', '应付生成'], tips: ['审核后生成应付账款', '已核销应付不可反审核'],
  },
  quickOrder: {
    type: 'sales', mode: 'bill', title: '销售快速开单', desc: '最高频页面：客户、商品、收款、审核并打印一屏完成',
    filters: ['客户搜索F2', '仓库', '业务员', '结算方式'],
    columns: ['商品', '仓库', '业务单位', '开单数量', '换算率', '基本单位数量', '可用库存', '单价', '金额', '成本', '毛利', '赠品'],
    row: ['农夫山泉500ml*24', '总仓', '箱', '10', '24', '240', '1200', '35.00', '350.00', '312.00', '38.00', '否'],
    actions: ['挂单', '保存草稿', '审核并打印', '添加赠品', '常购商品'], sections: ['客户欠款/信用额度', '商品扫码F3', '组合收款', '金额汇总'], tips: ['支持 F2/F3/F4/F7/F9/Ctrl+Enter', '库存不足阻断审核'],
  },
  salesOrder: {
    type: 'sales', mode: 'bill', title: '销售订单', desc: '客户订货，审核后锁定库存',
    filters: ['订单日期', '订单号', '客户', '业务员', '仓库', '状态', '收款状态', '出库状态', '商品'],
    columns: ['订单号', '日期', '客户', '业务员', '仓库', '订单金额', '已收金额', '未收金额', '信用检查', '库存检查', '状态', '出库状态', '签收状态', '操作'],
    row: ['SO202606140001', '2026-06-14', '华联超市', '张三', '总仓', '350.00', '0.00', '350.00', '通过', '通过', '待审核', '未出库', '未签收', '查看 编辑 审核 打印'],
    actions: ['新建销售订单', '导入', '导出主单', '导出明细', '打印'], sections: ['客户信用与欠款', '价格匹配', '库存校验', '明细与金额'], tips: ['审核校验客户状态、最低售价、信用额度、可用库存'],
  },
  salesOutbound: {
    type: 'sales', mode: 'bill', title: '销售出库', desc: '引入已审核销售订单，审核后扣库存、释放锁定、生成销售收货单',
    filters: ['出库日期', '出库单号', '销售单号', '客户', '仓库', '状态'], columns: ['出库单号', '销售单号', '客户', '仓库', '出库日期', '出库数量', '销售金额', '成本金额', '生成收货单', '状态', '操作'],
    row: ['SOU202606140001', 'SO202606140001', '华联超市', '总仓', '2026-06-14', '10', '350.00', '312.00', '未生成', '待审核', '查看 审核 打印'], actions: ['新建出库', '引入销售订单', '导出'], sections: ['来源销售订单', '本次出库数量', '批次与成本', '生成销售收货单'], tips: ['出库数量不可超过未出库数量', '出库不生成正式应收'],
  },
  salesReceipt: {
    type: 'sales', mode: 'bill', title: '销售收货单', desc: '客户签收确认，审核后生成最终应收',
    filters: ['签收日期', '收货单号', '销售单号', '客户', '签收状态', '应收生成', '状态'], columns: ['收货单号', '销售单号', '客户', '签收日期', '签收金额', '应收生成', '签收状态', '状态', '操作'], row: ['SR202606140001', 'SO202606140001', '华联超市', '2026-06-14', '350.00', '未生成', '全部签收', '待审核', '确认签收 审核 查看应收'], actions: ['确认签收', '导出', '打印'], sections: ['签收数量', '差异原因', '账期计算', '生成应收'], tips: ['签收数量不可大于出库数量', '已核销应收不可反审核'],
  },
  stockBalance: {
    type: 'inventory', mode: 'readonly', title: '库存余额', desc: '库存余额不可直接编辑，可查看流水、批次、锁定',
    filters: ['货主', '仓库', '商品', '分类', '品牌', '库存状态', '仅看有库存', '低于下限'], columns: ['商品', '仓库', '实物库存', '锁定库存', '冻结库存', '可用库存', '采购在途', '成本单价', '库存金额', '最近出入库时间', '操作'], row: ['农夫山泉500ml*24', '总仓', '1200', '180', '0', '1020', '100', '30.80', '36960.00', '2026-06-14 10:20', '流水 批次 锁定 调整'], actions: ['导出', '库存调整'], sections: ['库存口径', '成本权限', '低库存预警'], tips: ['可用库存=实物-锁定-冻结', '成本字段受权限控制'],
  },
  stockLedger: {
    type: 'inventory', mode: 'readonly', title: '库存流水', desc: '库存流水不可删除，反审核生成反向流水',
    filters: ['发生日期', '商品', '仓库', '来源单据类型', '来源单号', '出入方向', '操作人'], columns: ['流水号', '发生时间', '来源单据', '商品', '仓库', '批次', '方向', '数量', '成本单价', '金额', '变动后数量', '操作人'], row: ['INV202606140001', '2026-06-14 10:20', 'PI202606140001', '农夫山泉500ml*24', '总仓', 'B202606', '入库', '100', '30.80', '3080.00', '1200', '管理员'], actions: ['导出'], sections: ['来源追溯', '库存变动', '成本记录'], tips: ['点击来源单据可跳转'],
  },
  ar: {
    type: 'finance', mode: 'tabs', title: '应收账款', desc: '销售收货单审核后生成，应收支持收款核销',
    filters: ['应收日期', '应收单号', '客户/往来单位', '核销状态', '逾期状态'], columns: ['应收单号', '来源销售收货单', '客户', '业务员', '应收金额', '已收金额', '未收金额', '预计收款日', '逾期天数', '核销状态', '开票状态', '操作'], row: ['AR202606140001', 'SR202606140001', '华联超市', '张三', '350.00', '0.00', '350.00', '2026-07-14', '0', '未核销', '未开票', '查看来源 发起收款 核销'], actions: ['发起收款', '核销', '导出'], sections: ['应收明细', '应收汇总', '账龄分析', '逾期预警'], tips: ['核销后更新已收/未收', '逾期状态按预计结款日计算'],
  },
  ap: {
    type: 'finance', mode: 'tabs', title: '应付账款', desc: '采购收货单审核后生成，应付支持付款核销',
    filters: ['应付日期', '应付单号', '供应商/往来单位', '核销状态', '到期状态'], columns: ['应付单号', '来源采购收货单', '供应商', '应付金额', '已付金额', '未付金额', '预计付款日', '核销状态', '操作'], row: ['AP202606140001', 'PR202606140001', '农夫山泉杭州经销', '3955.00', '0.00', '3955.00', '2026-07-14', '未核销', '查看来源 发起付款 核销'], actions: ['发起付款', '核销', '导出'], sections: ['应付明细', '应付汇总', '账龄分析', '付款排期'], tips: ['付款核销后更新应付余额'],
  },
}

const simpleBase = ['brand','unit','routeLine','department','owner','expenseType','counterparty','fundAccount','employee','territory']
simpleBase.forEach(code => {
  const names = { brand:'品牌管理', unit:'单位管理', routeLine:'线路管理', department:'部门管理', owner:'货主信息', expenseType:'费用类型', counterparty:'往来单位', fundAccount:'资金账户', employee:'人员信息', territory:'片区管理' }
  const treeNodes = {
    expenseType: ['全部费用', '采购费用', '　运费', '　装卸费', '经营费用', '　房租', '收入类费用'],
    territory: ['全部片区', '浙江', '　杭州', '　　西湖区', '　　拱墅区'],
    department: ['公司', '销售部', '采购部', '仓储部', '财务部'],
    fundAccount: ['全部账户', '现金', '银行账户', '线上账户'],
  }
  const tree = Boolean(treeNodes[code])
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'base', mode: 'modal', tree, treeNodes: treeNodes[code] || [], title: names[code], desc: tree ? '树状基础资料：左侧树节点筛选，右侧列表维护，简单新增使用小弹窗' : '基础资料维护：查询、新建、编辑、停用、导入导出',
    filters: ['关键字', '状态'], columns: ['编码', '名称', '类型/分组', '状态', '备注', '操作'], row: [code.toUpperCase()+'001', names[code]+'示例', '默认', '正常', '示例数据', '编辑 停用'], actions: ['新建', '批量编辑', '导入', '导出'], sections: ['基础信息', '状态与备注'], formFields: ['编码', '名称', '类型/分组', '状态', '备注'],
  }
})

const inventoryExtra = ['stockLock','batchStock','stockWarning','transfer','damage','costAdjust','stockAdjust','otherInbound','otherOutbound','stockTake']
inventoryExtra.forEach(code => {
  const names = { stockLock:'库存锁定', batchStock:'批次库存', stockWarning:'库存预警', transfer:'调拨单', damage:'报损单', costAdjust:'成本调整单', stockAdjust:'库存调整单', otherInbound:'其他入库', otherOutbound:'其他出库', stockTake:'库存盘点' }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'inventory', mode: 'bill', title: names[code], desc: '库存增强模块：审核后才影响库存或成本，成本字段受权限控制',
    filters: ['日期', '单号', '仓库', '商品', '状态'], columns: ['单号/商品', '仓库', '业务类型', '数量', '成本金额', '状态', '操作'], row: [code.toUpperCase()+'001', '总仓', names[code], '100', '3080.00', '待审核', '查看 编辑 审核 日志'], actions: ['新建', '审核', '导出', '打印'], sections: ['基本信息', '商品明细', '成本汇总', '附件日志'], tips: ['审核后不可修改', '已结账期间不可反审核'],
  }
})

const purchaseExtra = ['purchaseReturn','purchaseExpense','purchaseInvoice']
purchaseExtra.forEach(code => {
  const names = { purchaseReturn:'采购退货单', purchaseExpense:'采购费用单', purchaseInvoice:'采购发票' }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'purchase', mode: 'bill', title: names[code], desc: '采购增强模块：费用、发票、退货与应付/成本联动',
    filters: ['日期', '单号', '供应商', '状态', '商品'], columns: ['单号', '供应商', '业务类型', '金额', '应付/勾稽状态', '状态', '操作'], row: [code.toUpperCase()+'001', '农夫山泉杭州经销', names[code], '1000.00', '未处理', '待审核', '查看 编辑 审核'], actions: ['新建', '导入', '导出', '打印'], sections: ['基本信息', '关联入库/收货', '费用/发票明细', '应付影响'], tips: ['采购费用审核后可分摊重算成本', '发票勾稽关联应付'],
  }
})

const salesExtra = ['salesReturn','salesInvoice','flyOrder','emptyAdjust']
salesExtra.forEach(code => {
  const names = { salesReturn:'销售退货单', salesInvoice:'销售发票', flyOrder:'飞单', emptyAdjust:'客户空退空出' }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'sales', mode: 'bill', title: names[code], desc: '销售增强模块：退货、发票、飞单、账务调整',
    filters: ['日期', '单号', '客户', '状态', '商品'], columns: ['单号', '客户', '业务类型', '金额', '应收/开票状态', '状态', '操作'], row: [code.toUpperCase()+'001', '华联超市', names[code], '350.00', '未处理', '待审核', '查看 编辑 审核'], actions: ['新建', '导入', '导出', '打印'], sections: ['基本信息', '商品/发票明细', '库存/应收影响', '日志'], tips: ['飞单不影响库存但生成销售与采购闭环', '空退空出不经过仓库，仅调整应收'],
  }
})

const financeExtra = ['receiptPayment','arSettlement','apSettlement','financeExpense','fundLedger','counterpartyAr','counterpartyAp','receiptVerify','paymentVerify','customerStatement','supplierStatement']
financeExtra.forEach(code => {
  const names = { receiptPayment:'收付款单', arSettlement:'应收结算', apSettlement:'应付结算', financeExpense:'费用单', fundLedger:'资金流水', counterpartyAr:'往来单位应收', counterpartyAp:'往来单位应付', receiptVerify:'收款核销', paymentVerify:'付款核销', customerStatement:'客户对账', supplierStatement:'供应商对账' }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'finance', mode: 'bill', title: names[code], desc: '财务模块：收付款、核销、费用、资金流水',
    filters: ['日期', '单号', '对象', '状态', '金额'], columns: ['单号', '对象', '来源单据', '金额', '已核销/余额', '状态', '操作'], row: [code.toUpperCase()+'001', '华联超市', 'SR202606140001', '350.00', '0.00', '待审核', '查看 审核 核销'], actions: ['新建', '审核', '核销', '导出'], sections: ['对象信息', '收付款信息', '核销明细', '资金流水'], tips: ['收付款审核后生成资金流水', '结算只能选择同一对象的应收/应付'],
  }
})

const systemExtra = ['precision','dictionary','workflow','printTemplate','importList','exportCenter']
systemExtra.forEach(code => {
  const names = { precision:'显示精度设置', dictionary:'用户数据字典', workflow:'审批流配置', printTemplate:'打印模板设置', importList:'导入列表', exportCenter:'导出中心' }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'system', mode: 'modal', title: names[code], desc: '系统配置与运维管理：参数、模板、审批、导入导出任务',
    filters: ['关键字', '状态', '分组'], columns: ['编码', '名称', '类型/分组', '状态', '备注', '操作'], row: [code.toUpperCase()+'001', names[code]+'示例', '系统', '正常', '示例数据', '编辑 停用'], actions: ['新建', '保存配置', '导入', '导出'], sections: ['基础信息', '配置项', '状态与备注'], formFields: ['编码', '名称', '类型/分组', '状态', '备注'],
  }
})

moduleConfigs.exportCenter = {
  ...moduleConfigs.exportCenter,
  mode: 'readonly',
  desc: '导出中心：查看异步导出任务状态，已完成任务可下载文件',
  columns: ['任务号', '报表名称', '模块编码', '状态', '文件名', '筛选条件', '创建时间', '完成时间', '操作'],
  row: ['EXP202606150001', '销售订单导出', 'salesOrder', '已完成', '销售订单导出_EXP202606150001.xlsx', '{}', '2026-06-15 09:10:00', '2026-06-15 09:10:05', '下载'],
  actions: ['刷新', '导出'],
}

const reportExtra = ['salesReport','purchaseReport','stockReport','financeReport']
reportExtra.forEach(code => {
  const names = { salesReport:'销售报表', purchaseReport:'采购报表', stockReport:'库存报表', financeReport:'财务报表' }
  const columns = {
    salesReport: ['单据日期', '客户', '业务员', '仓库', '销售金额', '已收金额', '未收金额', '毛利', '状态', '操作'],
    purchaseReport: ['单据日期', '供应商', '采购员', '仓库', '采购金额', '入库金额', '付款状态', '状态', '操作'],
    stockReport: ['商品编码', '商品名称', '仓库', '实物库存', '锁定库存', '可用库存', '成本单价', '库存金额', '最近出入库时间', '操作'],
    financeReport: ['报表类型', '对象名称', '金额', '已核销金额', '余额', '预计日期', '状态', '操作'],
  }
  const rows = {
    salesReport: ['2026-06-14', '华联超市', '张三', '总仓', '350.00', '0.00', '350.00', '42.00', '已审核', '查看 导出'],
    purchaseReport: ['2026-06-14', '农夫山泉杭州经销', '李四', '总仓', '3500.00', '3500.00', '未付款', '已审核', '查看 导出'],
    stockReport: ['SP001', '农夫山泉500ml*24', '总仓', '1200', '180', '1020', '30.80', '36960.00', '2026-06-14 10:20', '流水 导出'],
    financeReport: ['应收', '华联超市', '350.00', '0.00', '350.00', '2026-07-14', '未核销', '查看 导出'],
  }
  moduleConfigs[code] = moduleConfigs[code] || {
    type: 'report', mode: 'readonly', title: names[code], desc: '报表中心：按业务单据与余额汇总，支持查询、导出与来源追溯',
    filters: ['日期范围', '对象', '仓库', '商品', '状态'], columns: columns[code], row: rows[code], actions: ['刷新', '导出'], sections: ['汇总指标', '明细列表', '来源追溯'], tips: ['报表数据来源于已审核业务单据和余额流水', '导出任务进入导出中心'],
  }
})
