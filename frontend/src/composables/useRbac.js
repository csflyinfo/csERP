import { unref } from 'vue'
import { moduleConfigs } from '../module-config.js'
import { usePermStore } from '../stores/perm.js'

/**
 * 销售/采购模块的 RBAC 适配层（PRD-28 卡片6）。
 *
 * 三个职责：
 *  1. 前端 moduleCode（camelCase）→ 后端菜单码（点号），用于拼 <menu>.<action> 功能码；
 *  2. 列表中文列标题 → 敏感字段码（VIEW_*），用于整列显隐（值脱敏后端已做，前端按权限连列头一并收走）；
 *  3. 按钮文案（新建/审核/导出…）→ 功能码，供 v-permission / v-action-perms / 函数闸门。
 *
 * 卡片6 覆盖 sales/purchase；卡片7 扩展 base/inventory/finance/report 模块。
 * WMS/TMS 仍返回「不校验」，留待卡片9、10 接入。
 * 命中不到功能码时一律「不拦截」（不隐藏按钮），最终安全由后端 403 兜底。
 */

// 前端 moduleCode → 后端菜单码（与 @RequirePerm 的前缀严格一致）
const MODULE_MENU = {
  purchaseOrder: 'purchase.order',
  purchaseInbound: 'purchase.inbound',
  purchaseReceipt: 'purchase.receipt',
  purchaseReturnApply: 'purchase.return_apply',
  purchaseReturnOutbound: 'purchase.return_outbound',
  purchaseReturn: 'purchase.return_bill',
  purchaseExpense: 'purchase.fee',
  purchaseInvoice: 'purchase.invoice',
  salesOrder: 'sales.order',
  salesOutbound: 'sales.outbound',
  salesReceipt: 'sales.receipt',
  salesReturn: 'sales.return',
  salesReturnInbound: 'sales.return_inbound',
  rejectInbound: 'sales.reject_inbound',
  flyOrder: 'sales.flying',
  quickOrder: 'sales.quick',
  // 报表中心（卡片6：销售/采购报表是业务数据旁路出口，菜单码与 MenuConfig/后端注解一致）
  salesReport: 'report.sales',
  purchaseReport: 'report.purchase',
  // 报表中心一期：采购五表（菜单码与 MenuConfig.reportMenus 严格一致）
  purchaseOrderDetailReport: 'report.purchase_order_detail',
  purchaseMoveReport: 'report.purchase_move_detail',
  purchaseGoodsSummaryReport: 'report.purchase_goods_summary',
  purchaseSupplierSummaryReport: 'report.purchase_supplier_summary',
  purchaseForecastReport: 'report.purchase_forecast',
  // 报表中心二期：库存两表 + 销售七表（菜单码与 MenuConfig P2 段严格一致）
  inventoryRollReport: 'report.inventory_roll',
  stockLedgerReport: 'report.stock_ledger',
  salesGoodsSummaryReport: 'report.sales_goods_summary',
  customerGoodsSummaryReport: 'report.customer_goods_summary',
  customerSummaryReport: 'report.customer_summary',
  salesmanSummaryReport: 'report.salesman_summary',
  salesmanGoodsSummaryReport: 'report.salesman_goods_summary',
  salesOrderDetailReport: 'report.sales_order_detail',
  salesMoveReport: 'report.sales_move_detail',
  // 报表中心三期：库存分析/综合分析/绩效/财务十表（菜单码与 MenuConfig P3 段严格一致）
  shortageAnalysisReport: 'report.shortage_analysis',
  goodsTurnoverReport: 'report.goods_turnover',
  goodsAnalysisReport: 'report.goods_analysis',
  wmsKeeperPerfReport: 'report.wms_keeper_perf',
  driverDeliveryPerfReport: 'report.driver_delivery_perf',
  arAgingReport: 'report.ar_aging',
  apAgingReport: 'report.ap_aging',
  customerArSummaryReport: 'report.customer_ar_summary',
  supplierApSummaryReport: 'report.supplier_ap_summary',
  fundJournalReport: 'report.fund_journal',

  // ===== 卡片7：基础档案 / 库存 / 财务 / 报表 =====
  // 基础档案
  goods: 'base.goods', category: 'base.category', brand: 'base.brand', unit: 'base.unit',
  warehouse: 'base.warehouse', customer: 'base.customer', supplier: 'base.supplier',
  priceGroup: 'base.price_group', priceGroupItem: 'base.price_group_goods',
  priceAdjustOrder: 'base.price_adjust', goodsPriceAdjust: 'base.goods_price_adjust',
  priceChangeLog: 'base.price_change_query',
  territory: 'base.region', routeLine: 'base.route', employee: 'base.employee',
  department: 'base.department', owner: 'base.owner', expenseType: 'base.fee_type',
  counterparty: 'base.other_unit', counterpartyType: 'base.other_unit',
  fundAccount: 'base.fund_account',
  customerPrice: 'base.customer_price', customerPriceQuery: 'base.customer_price_query',
  customerPriceChange: 'base.customer_price_change',
  customerPriceChangeLog: 'base.customer_price_change',
  // 库存
  stockBalance: 'inv.balance', stockLedger: 'inv.flow', stockWarning: 'inv.warning',
  transferApply: 'inv.transfer_apply', transferOutbound: 'inv.transfer_out',
  transferInbound: 'inv.transfer_in',
  damage: 'inv.damage', costAdjust: 'inv.cost_adjust', stockAdjust: 'inv.cost_adjust',
  otherInbound: 'inv.other_in', otherOutbound: 'inv.other_out', stockTake: 'inv.count',
  // 财务
  ar: 'fin.ar_detail', ap: 'fin.ap',
  receiptPayment: 'fin.receipt', paymentModule: 'fin.payment',
  reconcileRecord: 'fin.receipt_writeoff',
  arSettlement: 'fin.ar_settle', apSettlement: 'fin.ap_settle',
  financeExpense: 'fin.fee', fundLedger: 'fin.fund_flow',
  counterpartyAr: 'fin.ar_detail', counterpartyAp: 'fin.ap',
  receiptVerify: 'fin.receipt_verify', paymentVerify: 'fin.payment_verify',
  customerStatement: 'fin.customer_recon', supplierStatement: 'fin.supplier_recon',
  // 报表
  stockReport: 'report.inventory', financeReport: 'report.finance',
}

/**
 * 各菜单后端「真实存在」的功能点动作集合（权威来源：10 个 sales/purchase Controller 上
 * 的 @RequirePerm 注解）。文案映射出的码若不在此集合，按幽灵码丢弃（不拦截），
 * 避免把后端本就没有的动作（如 purchase.order.edit、purchase.fee.unaudit）对所有人隐藏。
 * global.* 全局点不经过此白名单。
 */
const MENU_ACTION_SET = {
  'purchase.order': ['add', 'view', 'audit', 'unaudit', 'close', 'delete'],
  'purchase.inbound': ['add', 'view', 'audit'],
  'purchase.receipt': ['view', 'audit', 'unaudit', 'edit'],
  'purchase.return_apply': ['view', 'add', 'edit', 'audit', 'unaudit', 'delete'],
  'purchase.return_outbound': ['view', 'edit', 'audit'],
  'purchase.return_bill': ['view', 'audit', 'unaudit'],
  'purchase.fee': ['view', 'audit'],
  'purchase.invoice': ['view', 'add', 'edit', 'audit', 'unaudit', 'close', 'delete', 'biz_certify', 'biz_report'],
  'sales.order': ['add', 'view', 'edit', 'audit', 'unaudit', 'close', 'delete'],
  'sales.outbound': ['view', 'add', 'edit', 'audit'],
  'sales.receipt': ['view', 'audit', 'unaudit', 'biz_sign', 'biz_unsign'],
  'sales.return': ['view', 'add', 'edit', 'biz_confirm', 'biz_reject', 'biz_push_warehouse', 'biz_cancel_push', 'biz_change_type', 'audit', 'unaudit', 'delete'],
  'sales.return_inbound': ['view', 'edit', 'audit', 'biz_sync_wms_tasks'],
  'sales.reject_inbound': ['view', 'edit', 'audit', 'unaudit'],
  'sales.flying': ['add', 'edit', 'view', 'audit', 'unaudit', 'close', 'delete', 'export'],
  'sales.quick': ['add'],
  // 报表只读：导出按钮走 global.export（codesForAction 特例），不落模块点
  'report.sales': ['view'],
  'report.purchase': ['view'],
  // 报表中心一期：前四表只读；采购预测可生成待审核采购订单（add）
  'report.purchase_order_detail': ['view'],
  'report.purchase_move_detail': ['view'],
  'report.purchase_goods_summary': ['view'],
  'report.purchase_supplier_summary': ['view'],
  'report.purchase_forecast': ['view', 'add'],
  // 报表中心二期九表：全部只读（导出走 global.export + 敏感列 global.data_export_sensitive）
  'report.inventory_roll': ['view'],
  'report.stock_ledger': ['view'],
  'report.sales_goods_summary': ['view'],
  'report.customer_goods_summary': ['view'],
  'report.customer_summary': ['view'],
  'report.salesman_summary': ['view'],
  'report.salesman_goods_summary': ['view'],
  'report.sales_order_detail': ['view'],
  'report.sales_move_detail': ['view'],
  // 报表中心三期十表：全部只读（导出走 global.export）
  'report.shortage_analysis': ['view'],
  'report.goods_turnover': ['view'],
  'report.goods_analysis': ['view'],
  'report.wms_keeper_perf': ['view'],
  'report.driver_delivery_perf': ['view'],
  'report.ar_aging': ['view'],
  'report.ap_aging': ['view'],
  'report.customer_ar_summary': ['view'],
  'report.supplier_ap_summary': ['view'],
  'report.fund_journal': ['view'],

  // ===== 卡片7（权威来源：后端各 Controller @RequirePerm 注解）=====
  // 基础档案
  'base.goods': ['view', 'add', 'edit', 'delete', 'biz_stop', 'biz_freeze', 'biz_sale_ranking', 'biz_selector'],
  'base.category': ['view', 'add', 'edit', 'delete', 'import', 'biz_stop'],
  'base.brand': ['view', 'add', 'edit', 'delete', 'import', 'biz_stop'],
  'base.unit': ['view', 'add', 'edit', 'delete', 'import', 'biz_stop'],
  'base.warehouse': ['view', 'add', 'edit', 'delete', 'import', 'biz_stop'],
  'base.customer': ['view', 'add', 'edit', 'delete', 'biz_stop'],
  'base.supplier': ['view', 'add', 'edit', 'delete', 'biz_stop'],
  'base.price_group': ['view', 'edit'],
  'base.price_group_goods': ['view', 'biz_toggle', 'biz_goods_count'],
  'base.price_adjust': ['view', 'edit', 'delete', 'audit', 'biz_submit', 'biz_reject'],
  'base.goods_price_adjust': ['view', 'edit', 'delete', 'audit', 'biz_submit', 'biz_reject', 'biz_confirm'],
  'base.price_change_query': ['view'],
  'base.customer_price': ['view', 'add', 'edit', 'audit', 'close', 'import'],
  'base.customer_price_query': ['view', 'biz_stop'],
  'base.customer_price_change': ['view'],
  // 通用主档（/base/master/save 编程式鉴权）：标准 8 动作 + 员工拉单
  'base.region': ['view', 'add', 'edit', 'delete', 'import'],
  'base.route': ['view', 'add', 'edit', 'delete', 'import'],
  'base.department': ['view', 'add', 'edit', 'delete', 'import'],
  'base.fee_type': ['view', 'add', 'edit', 'delete', 'import'],
  'base.owner': ['view', 'add', 'edit', 'delete', 'import'],
  'base.fund_account': ['view', 'add', 'edit', 'delete', 'import'],
  'base.other_unit': ['view', 'add', 'edit', 'delete', 'import'],
  'base.employee': ['view', 'add', 'edit', 'delete', 'import', 'biz_buyers', 'biz_salesmen'],
  // 库存
  'inv.balance': ['view', 'biz_batch_lock', 'biz_batch_unlock'],
  'inv.flow': ['view'],
  'inv.warning': ['view'],
  'inv.transfer_apply': ['view', 'add', 'edit', 'delete', 'audit', 'unaudit'],
  'inv.transfer_out': ['view', 'add', 'edit', 'audit', 'unaudit'],
  'inv.transfer_in': ['view', 'edit', 'audit', 'unaudit'],
  'inv.damage': ['view', 'add', 'edit', 'delete', 'audit', 'unaudit', 'close', 'import'],
  'inv.other_in': ['view', 'add', 'edit', 'delete', 'audit', 'unaudit', 'close', 'import'],
  'inv.other_out': ['view', 'add', 'edit', 'delete', 'audit', 'unaudit', 'close', 'import'],
  'inv.count': ['view', 'add', 'edit', 'delete', 'audit', 'unaudit', 'import', 'export', 'biz_parse_excel', 'biz_parse_items'],
  'inv.cost_adjust': ['view', 'audit'],
  // 财务
  'fin.ar_detail': ['view'],
  'fin.ap': ['view'],
  'fin.ar_settle': ['view', 'settle'],
  'fin.ap_settle': ['view'],
  'fin.receipt': ['view', 'add', 'edit', 'delete', 'audit', 'unaudit'],
  'fin.payment': ['view', 'add', 'edit', 'delete', 'audit', 'unaudit'],
  'fin.receipt_verify': ['view', 'writeoff'],
  'fin.payment_verify': ['view', 'writeoff'],
  'fin.receipt_writeoff': ['view'],
  'fin.fund_flow': ['view'],
  'fin.fee': ['view', 'add', 'edit', 'delete', 'audit'],
  'fin.customer_recon': ['view', 'add', 'edit', 'delete', 'audit', 'unaudit', 'settle'],
  'fin.supplier_recon': ['view', 'add', 'edit', 'delete', 'audit', 'settle'],
  // 报表只读
  'report.inventory': ['view'],
  'report.finance': ['view'],
}

// 模块级特殊业务动作（biz_*）：[按钮文案正则, action]，命中优先于标准动作
const BIZ_FUNCS = {
  'sales.receipt': [
    [/反签收|取消签收|撤销签收/, 'biz_unsign'],
    [/签收/, 'biz_sign'],
  ],
  'sales.return': [
    [/取消推送|撤销推送/, 'biz_cancel_push'],
    [/推送仓库|推送WMS|推送 WMS/, 'biz_push_warehouse'],
    [/变更.*方式|切换.*方式|改退货方式|改为(自提|司机)/, 'biz_change_type'],
    [/拒收|驳回/, 'biz_reject'],
    [/确认退货|^确认$/, 'biz_confirm'],
  ],
  'sales.return_inbound': [
    [/同步.*WMS|同步.*仓库|sync/i, 'biz_sync_wms_tasks'],
  ],
  'purchase.invoice': [
    [/认证/, 'biz_certify'],
  ],
  // ===== 卡片7 =====
  // 档案停用/冻结（独立控制器端点 → biz_* 功能点）
  'base.goods': [
    [/停用|启用|停售|在售/, 'biz_stop'],
    [/冻结|解冻/, 'biz_freeze'],
    [/销售排行/, 'biz_sale_ranking'],
  ],
  'base.customer': [[/停用|启用/, 'biz_stop']],
  'base.supplier': [[/停用|启用/, 'biz_stop']],
  'base.brand': [[/停用|启用/, 'biz_stop']],
  'base.category': [[/停用|启用/, 'biz_stop']],
  'base.unit': [[/停用|启用/, 'biz_stop']],
  'base.warehouse': [[/停用|启用/, 'biz_stop']],
  // 调价单工作流
  'base.goods_price_adjust': [
    [/提交/, 'biz_submit'],
    [/驳回|拒绝/, 'biz_reject'],
    [/^确认$/, 'biz_confirm'],
  ],
  'base.price_adjust': [
    [/提交/, 'biz_submit'],
    [/驳回|拒绝/, 'biz_reject'],
  ],
  'base.customer_price_query': [
    [/停用|终止/, 'biz_stop'],
  ],
}

/**
 * 跨模块/复合动作特判（先于 biz 与标准动作）：
 * 「生成出库单」在销售订单页露出但请求落到出库模块；「改价/审核」任一点满足即可见。
 */
const ACTION_OVERRIDES = {
  'sales.order': [
    [/生成出库单/, ['sales.outbound.add']],
  ],
  'purchase.order': [
    [/生成入库单/, ['purchase.inbound.add']],
  ],
  'purchase.receipt': [
    // 「改价/审核」打开抽屉改价（receipt.edit）并可直接审核（receipt.audit），任一即可见
    [/改价/, ['purchase.receipt.edit', 'purchase.receipt.audit']],
  ],
  'purchase.return_outbound': [
    // UI 唯一写入口「确认出库」实为编辑实发数量后保存（update）
    [/确认出库/, ['purchase.return_outbound.edit']],
  ],
  'purchase.return_apply': [
    // 「保存并提交审核」是制单提交（后端走 create/update，add/edit 点），不是审核动作本身；
    // 不能让 /审核/ 抢先映射成 audit，否则制单员提交入口被误隐藏
    [/提交审核/, ['purchase.return_apply.add', 'purchase.return_apply.edit']],
  ],

  // ===== 卡片7 =====
  // 通用主档（/base/master 编程式鉴权）：停用/冻结/解冻在后端统一归 <menu>.edit
  'base.region': [[/停用|启用|冻结|解冻/, ['base.region.edit']]],
  'base.route': [[/停用|启用|冻结|解冻/, ['base.route.edit']]],
  'base.department': [[/停用|启用|冻结|解冻/, ['base.department.edit']]],
  'base.fee_type': [[/停用|启用|冻结|解冻/, ['base.fee_type.edit']]],
  'base.owner': [[/停用|启用|冻结|解冻/, ['base.owner.edit']]],
  'base.fund_account': [[/停用|启用|冻结|解冻/, ['base.fund_account.edit']]],
  'base.other_unit': [[/停用|启用|冻结|解冻/, ['base.other_unit.edit']]],
  'base.employee': [[/停用|启用|冻结|解冻/, ['base.employee.edit']]],
  'base.price_group': [[/停用|启用/, ['base.price_group.edit']]],
  'base.price_group_goods': [[/停用|启用|启用\/停用/, ['base.price_group_goods.biz_toggle']]],
  // 注：各模块「导入/导出」按钮由 codesForAction 早退逻辑统一映射（模块点 + global 点）
  // 库存查询页「库存调整」跳成本调整单页面
  'inv.balance': [[/库存调整/, ['inv.cost_adjust.view']]],
  // 应收明细/往来应收：收款结算生成收款单
  'fin.ar_detail': [[/收款结算/, ['fin.ar_settle.settle']]],
  // 应付：发起付款=制付款单；核销/应付结算/预付抵扣走付款核销端点
  'fin.ap': [
    [/发起付款/, ['fin.payment.add']],
    // 「查看核销记录」文案也含「核销」，必须排在通用核销规则前
    [/查看核销记录/, ['fin.receipt_writeoff.view']],
    [/核销|应付结算|预付抵扣/, ['fin.payment_verify.writeoff']],
  ],
  // 收/付款单列表行内「核销」打开核销弹窗，端点是 receipt-verify/payment-verify 的 reconcile
  'fin.receipt': [[/核销/, ['fin.receipt_verify.writeoff']]],
  'fin.payment': [[/核销/, ['fin.payment_verify.writeoff']]],
  'fin.ar_settle': [[/核销|收款核销/, ['fin.receipt_verify.writeoff']]],
  'fin.ap_settle': [[/核销|付款核销|应付结算/, ['fin.payment_verify.writeoff']]],
  'fin.receipt_verify': [[/核销|收款核销/, ['fin.receipt_verify.writeoff']]],
  'fin.payment_verify': [[/核销|付款核销/, ['fin.payment_verify.writeoff']]],
  'fin.customer_recon': [[/收款结算/, ['fin.customer_recon.settle']]],
  'fin.supplier_recon': [[/付款结算/, ['fin.supplier_recon.settle']]],
}

// 标准动作（按文案关键词，顺序即优先级）
const STANDARD_ACTIONS = [
  [/反审核|取消审核|撤销审核/, 'unaudit'],
  [/审核并打印|审核/, 'audit'],
  [/作废|关闭|终止/, 'close'],
  [/删除/, 'delete'],
  [/编辑|修改/, 'edit'],
  [/新建|新增|挂单|保存草稿|添加|引入|开单|常购|生成.*单/, 'add'],
]

/** 模块是否纳入本卡 RBAC（销售/采购） */
export function isRbacModule(moduleCode) {
  return !!MODULE_MENU[moduleCode]
}

/**
 * 列标题 → 敏感字段码；非敏感列返回 null（始终展示）。
 * 返回值也可能是字段码数组：语义为「任一可见即保留该列」（用于财务报表这类
 * 一行 AR、一行 AP 的混合语义列，行内值仍由后端按行脱敏，列隐藏取并集避免误隐藏）。
 * 规则口径逐列对齐后端各 Controller 的 mask 调用点（注册表/覆盖表），不得凭标题想当然。
 */
export function fieldForColumn(moduleCode, title) {
  if (!title || !isRbacModule(moduleCode)) return null
  const t = String(title)
  const type = moduleConfigs[moduleCode]?.type

  // 状态类列（即使名字含应收/应付）不是金额，不脱敏
  if (/状态/.test(t)) return null

  // ========== 卡片7：库存模块（先于通用成本/金额规则，库存金额≠成本金额） ==========
  // 库存查询 + 库存报表：与 InventoryController.balancePage / ReportController.stock 同口径
  // 实物库存(physicalQty)/冻结(frozenQty)/采购在途后端不脱敏；库存金额(stockAmount)随库存数量视角
  if (moduleCode === 'stockBalance' || moduleCode === 'stockReport') {
    if (/成本单价/.test(t)) return 'VIEW_COST'
    if (/锁定/.test(t)) return 'VIEW_STOCK_LOCK'
    if (/可用|库存金额/.test(t)) return 'VIEW_STOCK_AMOUNT'
    if (/主供应商|默认供应商/.test(t)) return 'VIEW_SUPPLIER_OF_GOODS'
    return null
  }
  if (moduleCode === 'stockLedger') {
    if (/成本单价/.test(t)) return 'VIEW_COST'
    if (/金额/.test(t)) return 'VIEW_STOCK_COST' // 流水金额 amount → VIEW_STOCK_COST
    if (/变动后数量/.test(t)) return 'VIEW_STOCK_AMOUNT' // balanceQty 注册表绑定
    return null // 数量(qty) 后端不脱敏
  }
  if (moduleCode === 'stockWarning' && /当前库存|可用库存|库存/.test(t)) return 'VIEW_STOCK_AMOUNT'
  if (moduleCode === 'stockTake' && /盘前金额|实盘金额|差异金额/.test(t)) return 'VIEW_STOCK_COST'
  if (moduleCode === 'damage' && /报损金额|成本金额/.test(t)) return 'VIEW_COST_AMOUNT'
  if ((moduleCode === 'otherInbound' || moduleCode === 'otherOutbound')
      && /^金额$|成本金额/.test(t)) return 'VIEW_COST_AMOUNT'

  // ========== 卡片7：财务模块 ==========
  if (moduleCode === 'receiptPayment' && /收款金额|核销金额/.test(t)) return 'VIEW_FUND_FLOW'
  if (moduleCode === 'paymentModule' && /付款金额|核销金额/.test(t)) return 'VIEW_FUND_FLOW'
  if (moduleCode === 'reconcileRecord' && /核销金额/.test(t)) return 'VIEW_SETTLE_DETAIL'
  // ExpenseDrawer 明细列短标题为「金额/税额/不含税」，列表列为「费用金额」，统一资金视角
  // 费用单：后端 FUND_AMOUNT_KEYS 把明细 price/金额/税额/不含税统一绑 VIEW_FUND_FLOW（抽屉明细短标题：单价/金额/税额/不含税）
  if (moduleCode === 'financeExpense' && /费用金额|^金额$|^单价$|税额|不含税/.test(t)) return 'VIEW_FUND_FLOW'
  if (moduleCode === 'fundLedger') {
    if (/余额/.test(t)) return 'VIEW_FUND_ACCOUNT_BALANCE'
    if (/金额|收入|支出/.test(t)) return 'VIEW_FUND_FLOW'
  }
  if (moduleCode === 'arSettlement' && /结算金额|折扣金额/.test(t)) return 'VIEW_AR_BALANCE'
  if (moduleCode === 'apSettlement' && /结算金额|折扣金额/.test(t)) return 'VIEW_AP_BALANCE'
  if (moduleCode === 'receiptVerify' && /金额/.test(t)) return 'VIEW_FUND_FLOW'
  if (moduleCode === 'paymentVerify' && /金额/.test(t)) return 'VIEW_FUND_FLOW'
  if (moduleCode === 'customerStatement') {
    if (/对账金额/.test(t)) return 'VIEW_AR_BALANCE'
    if (/已收款金额/.test(t)) return 'VIEW_FUND_FLOW'
    if (/抹零/.test(t)) return 'VIEW_SETTLE_DETAIL'
  }
  if (moduleCode === 'supplierStatement') {
    if (/对账金额/.test(t)) return 'VIEW_AP_BALANCE'
    if (/已付款金额/.test(t)) return 'VIEW_FUND_FLOW'
    if (/抹零/.test(t)) return 'VIEW_SETTLE_DETAIL'
  }
  // 财务报表：金额/已核销/余额列 AR 行与 AP 行混合，任一视角可见即留列（值后端按行脱敏）
  if (moduleCode === 'financeReport' && /金额|余额/.test(t)) {
    return ['VIEW_AR_BALANCE', 'VIEW_AP_BALANCE']
  }

  // ========== 卡片7：基础档案 ==========
  if (moduleCode === 'goods') {
    if (/最低售价/.test(t)) return 'VIEW_MIN_PRICE'
    if (/建议零售价/.test(t)) return 'VIEW_SUGGEST_RETAIL_PRICE'
    if (/标准售价/.test(t)) return 'VIEW_SALE_PRICE'
    if (/参考进价/.test(t)) return 'VIEW_PURCHASE_PRICE'
    if (/主供应商|默认供应商/.test(t)) return 'VIEW_SUPPLIER_OF_GOODS'
    // 当前库存(currentStock)/库存上下限后端商品档案列表不脱敏，手机号类档案本身字段有菜单即可见
    return null
  }
  if (moduleCode === 'customer' && /信用额度/.test(t)) return 'VIEW_CREDIT_LIMIT'
  if (moduleCode === 'fundAccount' && /期初余额|余额/.test(t)) return 'VIEW_FUND_ACCOUNT_BALANCE'
  if (moduleCode === 'priceGroupItem') {
    // 标价=商品标准价(unit_config.standardPrice)，现价=价格组价(i.price)
    if (/标价/.test(t)) return 'VIEW_SALE_PRICE'
    if (/现价/.test(t)) return 'VIEW_PRICE_GROUP'
  }
  if ((moduleCode === 'customerPriceQuery') && /标价|现价/.test(t)) return 'VIEW_CUSTOMER_PRICE'
  if ((moduleCode === 'customerPriceChange' || moduleCode === 'customerPriceChangeLog')
      && /变价前|变价后/.test(t)) return 'VIEW_CUSTOMER_PRICE'
  // 商品变价查询：变价前/后按行可能是售价/进价/最低价/建议零售/价格组价，任一可见即留列
  if (moduleCode === 'priceChangeLog' && /变价前|变价后/.test(t)) {
    return ['VIEW_SALE_PRICE', 'VIEW_PURCHASE_PRICE', 'VIEW_MIN_PRICE',
      'VIEW_SUGGEST_RETAIL_PRICE', 'VIEW_PRICE_GROUP']
  }

  // ========== 报表中心一期：采购五表（脱敏点与后端 maskOverrides/controller 严格对齐） ==========
  if (moduleCode === 'purchaseOrderDetailReport'
      || moduleCode === 'purchaseMoveReport'
      || moduleCode === 'purchaseGoodsSummaryReport'
      || moduleCode === 'purchaseSupplierSummaryReport'
      || moduleCode === 'purchaseForecastReport') {
    if (/单价|箱价|采购价/.test(t)) return 'VIEW_PURCHASE_PRICE'
    if (/金额/.test(t)) return 'VIEW_PURCHASE_AMOUNT'
    return null
  }

  // ========== 报表中心二期·库存两表（成本口径，与后端 VIEW_STOCK_COST 脱敏严格对齐） ==========
  if (moduleCode === 'inventoryRollReport' || moduleCode === 'stockLedgerReport') {
    // #8 期初/收入/调整/发出/期末成本金额，#9 单价/金额/结存金额，全部归库存成本视角
    if (/单价|金额/.test(t)) return 'VIEW_STOCK_COST'
    return null
  }

  // ========== 报表中心二期·销售七表（脱敏点与后端 maskOverrides 严格对齐） ==========
  if (moduleCode === 'salesGoodsSummaryReport'
      || moduleCode === 'customerGoodsSummaryReport'
      || moduleCode === 'customerSummaryReport'
      || moduleCode === 'salesmanSummaryReport'
      || moduleCode === 'salesmanGoodsSummaryReport'
      || moduleCode === 'salesOrderDetailReport'
      || moduleCode === 'salesMoveReport') {
    // 往来款先判，避免被通用金额吞掉（回款额/回款率/应收余额/逾期）
    if (/回款|应收|逾期/.test(t)) return 'VIEW_AR_BALANCE'
    if (/单价|箱价/.test(t)) return 'VIEW_SALE_PRICE'
    if (/单位成本/.test(t)) return 'VIEW_COST'
    if (/成本金额/.test(t)) return 'VIEW_COST_AMOUNT'
    if (/毛利/.test(t)) return 'VIEW_PROFIT'
    if (/销售金额|退货金额|净销售额|签收金额|订单金额|客单价/.test(t)) return 'VIEW_SALE_AMOUNT'
    return null
  }

  // ========== 报表中心三期·库存分析/综合分析（脱敏点与后端 maskOverrides 严格对齐） ==========
  if (moduleCode === 'goodsTurnoverReport') {
    if (/本期销售成本/.test(t)) return 'VIEW_COST_AMOUNT'
    if (/毛利率/.test(t)) return 'VIEW_PROFIT'
    if (/期间销售额/.test(t)) return 'VIEW_SALE_AMOUNT'
    if (/金额/.test(t)) return 'VIEW_STOCK_COST' // 期初/入库/期末/平均库存金额
    return null
  }
  if (moduleCode === 'goodsAnalysisReport') {
    if (/采购金额/.test(t)) return 'VIEW_PURCHASE_AMOUNT'
    if (/配比成本|成本/.test(t)) return 'VIEW_COST_AMOUNT'
    if (/毛利/.test(t)) return 'VIEW_PROFIT'
    if (/签收金额/.test(t)) return 'VIEW_SALE_AMOUNT'
    if (/期末库存金额/.test(t)) return 'VIEW_STOCK_COST'
    return null
  }
  if (moduleCode === 'wmsKeeperPerfReport') {
    // 盘盈/盘亏金额按库存成本视角（后端按批次移动平均计价、VIEW_STOCK_COST 脱敏）
    if (/盘盈金额|盘亏金额/.test(t)) return 'VIEW_STOCK_COST'
    return null
  }
  if (moduleCode === 'driverDeliveryPerfReport') {
    if (/代收货款|实际缴款|缴款差异/.test(t)) return 'VIEW_AR_BALANCE'
    if (/配送货值/.test(t)) return 'VIEW_SALE_AMOUNT'
    return null
  }

  // ========== 报表中心三期·财务五表（应收 VIEW_AR_BALANCE / 应付 VIEW_AP_BALANCE） ==========
  if (moduleCode === 'arAgingReport' || moduleCode === 'customerArSummaryReport') {
    if (/单号/.test(t)) return null // 应收单号/来源单号是单据号不是金额
    if (/应收|已核销|核销|回款|余额|未到期|逾期|信用额度|减免|抹零/.test(t)) return 'VIEW_AR_BALANCE'
    return null
  }
  if (moduleCode === 'apAgingReport' || moduleCode === 'supplierApSummaryReport') {
    if (/单号/.test(t)) return null // 应付单号/来源单号是单据号不是金额
    if (/应付|已付|付款|余额|未到期|逾期|收票|折让/.test(t)) return 'VIEW_AP_BALANCE'
    return null
  }
  // #6 缺货分析、#22 现金日记账：无金额脱敏列（后端列定义 PERM 均为 null）
  if (moduleCode === 'shortageAnalysisReport' || moduleCode === 'fundJournalReport') return null

  // ========== 卡片7：报表（salesReport/purchaseReport 为 type=report，不进泛化分支） ==========
  if (moduleCode === 'salesReport') {
    if (/销售金额/.test(t)) return 'VIEW_SALE_AMOUNT'
    if (/已收金额|未收金额/.test(t)) return 'VIEW_AR_BALANCE'
  }
  if (moduleCode === 'purchaseReport' && /采购金额|入库金额/.test(t)) return 'VIEW_PURCHASE_AMOUNT'

  // 成本与毛利（跨模块语义固定）
  if (/成本单价|单位成本/.test(t)) return 'VIEW_COST'
  if (/成本金额/.test(t)) return 'VIEW_COST_AMOUNT'
  // 兜底：其余含「成本」的列（如快速开单行内「成本」）归成本单价视角
  if (/成本/.test(t)) return 'VIEW_COST'
  if (/毛利/.test(t)) return 'VIEW_PROFIT'

  // 往来款（先于通用金额判定，避免被销售/采购金额吞掉）
  if (/已收金额|未收金额|应收余额|应收金额/.test(t)) return 'VIEW_AR_BALANCE'
  if (/已付金额|未付金额|应付余额|应付金额/.test(t)) return 'VIEW_AP_BALANCE'

  // 具名采购价/销售价
  if (/采购价|参考进价|进价|采购单价/.test(t)) return 'VIEW_PURCHASE_PRICE'
  // 折扣本质是价格让利率，与销售单价同视角
  if (/销售价|售价|销售单价|标准售价|折扣/.test(t)) return 'VIEW_SALE_PRICE'
  if (/采购金额/.test(t)) return 'VIEW_PURCHASE_AMOUNT'
  if (/销售金额/.test(t)) return 'VIEW_SALE_AMOUNT'

  // 采购发票：金额/税额/来票属应付往来视角
  if (moduleCode === 'purchaseInvoice' && /金额|税额|不含税/.test(t)) return 'VIEW_AP_BALANCE'
  // 采购收货单：来票/开票金额是应付往来信息（与发票、后端 PURCHASE_BILL profile 同口径）
  if (moduleCode === 'purchaseReceipt' && /开票|来票/.test(t)) return 'VIEW_AP_BALANCE'

  // 泛化单价 / 金额按业务类型归属
  if (/单价|原价|现价/.test(t)) {
    return type === 'purchase' ? 'VIEW_PURCHASE_PRICE' : 'VIEW_SALE_PRICE'
  }
  if (/金额|税额|不含税/.test(t)) {
    if (type === 'purchase') return 'VIEW_PURCHASE_AMOUNT'
    if (type === 'sales') return 'VIEW_SALE_AMOUNT'
  }

  // 快速开单的可用库存属库存数量敏感列
  if (moduleCode === 'quickOrder' && /可用库存/.test(t)) return 'VIEW_STOCK_AMOUNT'
  return null
}

/**
 * 校验功能码真实存在（防幽灵码误隐藏）：
 *  - global.* 全局点恒合法；
 *  - 当前菜单的码按本菜单动作集合校验动作后缀；
 *  - 跨模块码（如销售订单页的 sales.outbound.add）按码自身菜单的动作集合校验。
 */
function resolveCodes(menu, rawCodes) {
  const codes = Array.isArray(rawCodes) ? rawCodes : [rawCodes]
  return codes.filter(c => {
    if (!c) return false
    if (c.startsWith('global.')) return true
    const dot = c.lastIndexOf('.')
    if (dot < 0) return false
    const codeMenu = c.slice(0, dot)
    const action = c.slice(dot + 1)
    const allowed = MENU_ACTION_SET[codeMenu]
    // 非本卡菜单（如 WMS/TMS/GL 的显式码）不做白名单校验
    if (!allowed) return codeMenu !== menu
    return allowed.includes(action)
  })
}

/**
 * 按钮文案 → 完整功能码数组（多码=任一满足即放行）；映射不到或幽灵码过滤后为空返回 null。
 * 导入/导出/打印走全局功能点（飞单导出是模块点，单独处理）。
 */
export function codesForAction(moduleCode, label) {
  if (!label || !isRbacModule(moduleCode)) return null
  const menu = MODULE_MENU[moduleCode]
  const text = String(label)

  // 模块自带 import/export 动作点时（后端注解是 <menu>.import/export），与全局点任一满足即可见
  const menuActions = MENU_ACTION_SET[menu]
  if (/导入/.test(text)) {
    return menuActions?.includes('import') ? ['global.import', `${menu}.import`] : ['global.import']
  }
  // 「审核并打印」主语义是审核（先过审核接口），打印判定让位于 audit
  if (/打印/.test(text) && !/审核/.test(text)) return ['global.print']
  if (/导出/.test(text)) {
    if (moduleCode === 'flyOrder') return ['sales.flying.export']
    return menuActions?.includes('export') ? ['global.export', `${menu}.export`] : ['global.export']
  }

  // 快速开单：创建即审核（/sales/quick-order/create-and-audit），后端无独立 audit 点，
  // 「审核并打印」等审核语义统一归 sales.quick.add
  if (moduleCode === 'quickOrder' && /审核/.test(text)) return ['sales.quick.add']

  // 跨模块/复合动作特判（码已经过人工核实，仍走白名单过滤防漂移）
  const override = ACTION_OVERRIDES[menu]
  if (override) {
    for (const [re, codes] of override) {
      if (re.test(text)) {
        const resolved = resolveCodes(menu, codes)
        if (resolved.length) return resolved
      }
    }
  }

  // biz_* 特殊动作优先
  const biz = BIZ_FUNCS[menu]
  if (biz) {
    for (const [re, action] of biz) {
      if (re.test(text)) {
        const resolved = resolveCodes(menu, `${menu}.${action}`)
        if (resolved.length) return resolved
      }
    }
  }
  // 标准动作
  for (const [re, action] of STANDARD_ACTIONS) {
    if (re.test(text)) {
      const resolved = resolveCodes(menu, `${menu}.${action}`)
      if (resolved.length) return resolved
      // 标准动作在该模块不存在（幽灵码）：不拦截，维持该入口既有可用性（后端无对应端点）
      return null
    }
  }
  return null
}

/** 单码便捷版（v-permission 单按钮）：取第一个码，无映射返回 undefined。 */
export function funcForAction(moduleCode, label) {
  return codesForAction(moduleCode, label)?.[0]
}

/**
 * 组件内使用：返回响应式判定函数（背后是 pinia perm store）。
 * @param {string|import('vue').Ref<string>|ComputedRef<string>} [moduleRef]
 *        可选绑定当前模块码；绑定后可用 canView/actionHidden/guard 便捷方法，
 *        不传则只用显式带 moduleCode 参数的方法（GenericBusinessList 两者都用）。
 */
export function useRbac(moduleRef) {
  const perm = usePermStore()
  const mod = () => unref(moduleRef) || ''
  return {
    isRbacModule,
    fieldForColumn,
    codesForAction,
    funcForAction,
    /**
     * 该列在当前用户下是否可见（非敏感列或非 RBAC 模块恒可见）。
     * fieldForColumn 返回数组时为混合语义列：任一视角可见即保留（值由后端按行脱敏）。
     */
    canViewColumn(moduleCode, title) {
      const field = fieldForColumn(moduleCode, title)
      if (!field) return true
      return Array.isArray(field) ? field.some(f => perm.canViewField(f)) : perm.canViewField(field)
    },
    /** 绑定模块版列判定：canView('单价') === canViewColumn(mod(), '单价')。 */
    canView(title) {
      const field = fieldForColumn(mod(), title)
      if (!field) return true
      return Array.isArray(field) ? field.some(f => perm.canViewField(f)) : perm.canViewField(field)
    },
    /** 按钮 v-permission 用：返回功能码或 undefined（指令对 undefined 不校验）。 */
    permOf(moduleCode, label) {
      return funcForAction(moduleCode, label) ?? undefined
    },
    /** 多码版：供 v-permission 数组绑定（任一满足即可见）。 */
    permCodesFor(label) {
      return codesForAction(mod(), label) ?? undefined
    },
    /**
     * v-action-perms 容器指令裁决（绑定模块）：label 映射不到功能点时不拦截。
     * 返回 true = 当前用户无权（多码任一不满足全无时隐藏）、按钮应隐藏。
     */
    actionHidden(label) {
      const codes = codesForAction(mod(), String(label || '').trim())
      return codes ? !perm.hasAnyFunc(codes) : false
    },
    /**
     * 写操作处理函数入口闸门（绑定模块）：无权返回 false（调用方应中止并提示），
     * 无功能点映射或多码任一满足时返回 true。最终安全仍由后端 403 兜底。
     */
    guard(label) {
      const codes = codesForAction(mod(), String(label || '').trim())
      return !codes || perm.hasAnyFunc(codes)
    },
    /** 显式功能点码闸门（按钮文案无法映射时直接用码）。 */
    guardCode(code) {
      return perm.hasFunc(code)
    },
  }
}
