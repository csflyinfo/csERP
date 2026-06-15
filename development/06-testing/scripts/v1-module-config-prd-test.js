import { moduleConfigs } from '../../../frontend/src/module-config.js'

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function hasAll(moduleCode, key, values) {
  const config = moduleConfigs[moduleCode]
  assert(config, `${moduleCode} config should exist`)
  values.forEach(value => assert(config[key]?.includes(value), `${moduleCode}.${key} should include ${value}`))
}

function main() {
  ;['user', 'role', 'param', 'billNo', 'log', 'customerPrice', 'customerPriceQuery'].forEach(code => {
    assert(moduleConfigs[code], `${code} should have module config`)
  })

  hasAll('customerPrice', 'columns', ['调整单号', '客户编号/名称', '生效方式', '有效期', '商品数', '审核信息', '状态', '操作'])
  hasAll('customerPriceQuery', 'columns', ['客户编号/名称', '商品编码', '商品名称', '原价', '现价', '最新进价', '成本价', '生效状态'])

  hasAll('user', 'columns', ['账号', '姓名', '手机', '权限组', '数据范围', '状态', '操作'])
  hasAll('role', 'columns', ['权限组编码', '权限组名称', '用户数', '菜单权限', '字段权限', '状态', '操作'])
  hasAll('param', 'columns', ['参数键', '参数名称', '当前值', '默认值', '分组', '备注', '操作'])
  hasAll('billNo', 'columns', ['单据类型', '前缀', '日期格式', '流水位数', '重置周期', '示例', '状态', '操作'])
  hasAll('log', 'columns', ['操作时间', '操作人', '模块', '动作', '业务号', '结果', '详情', '操作'])

  hasAll('transfer', 'columns', ['调拨单号', '调出仓库', '调入仓库', '调拨类型', '调拨模式', '在途数量', '成本金额'])
  hasAll('purchaseExpense', 'columns', ['费用单号', '费用日期', '费用类型', '收款对象类型', '税额', '含税金额', '分摊方式', '分摊状态', '是否生成应付'])
  hasAll('purchaseInvoice', 'columns', ['采购发票单号', '发票号码', '发票代码', '发票类型', '开票日期', '不含税金额', '已勾稽金额', '未勾稽金额', '认证状态'])
  hasAll('financeExpense', 'columns', ['费用单号', '费用日期', '费用方向', '费用类型', '往来对象类型', '税额', '是否生成往来', '是否直接收付款'])

  hasAll('ar', 'actions', ['应收结算', '预收抵扣', '查看核销记录'])
  hasAll('ap', 'actions', ['应付结算', '预付抵扣', '查看核销记录'])
  hasAll('purchaseOrder', 'actions', ['新建采购订单', '导入', '导出主单', '导出明细', '打印'])
  hasAll('salesOrder', 'actions', ['新建销售订单', '导入', '导出主单', '导出明细', '打印'])

  console.log('V1 module config PRD test passed')
}

try {
  main()
} catch (error) {
  console.error(error)
  process.exit(1)
}
