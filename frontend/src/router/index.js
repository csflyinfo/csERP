import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginPage.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    name: 'Layout',
    component: () => import('@/layout/AppShell.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/DashboardPage.vue'), meta: { title: '经营概览' } },

      // 基础数据
      { path: 'goods', name: 'Goods', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '商品档案', module: 'goods' } },
      { path: 'category', name: 'Category', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '商品分类', module: 'category' } },
      { path: 'brand', name: 'Brand', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '品牌管理', module: 'brand' } },
      { path: 'unit', name: 'Unit', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '单位管理', module: 'unit' } },
      { path: 'warehouse', name: 'Warehouse', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '仓库资料', module: 'warehouse' } },
      { path: 'customer', name: 'Customer', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '客户资料', module: 'customer' } },
      { path: 'supplier', name: 'Supplier', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '供应商资料', module: 'supplier' } },
      { path: 'price-group', name: 'PriceGroup', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '价格组设置', module: 'priceGroup' } },
      { path: 'price-group-item', name: 'PriceGroupItem', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '价格组商品查询', module: 'priceGroupItem' } },
      { path: 'price-adjust-order', name: 'PriceAdjustOrder', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '价格组调价单', module: 'priceAdjustOrder' } },
      { path: 'goods-price-adjust', name: 'GoodsPriceAdjust', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '商品综合调价单', module: 'goodsPriceAdjust' } },
      { path: 'price-change-log', name: 'PriceChangeLog', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '价格组变价查询', module: 'priceChangeLog' } },
      { path: 'territory', name: 'Territory', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '片区管理', module: 'territory' } },
      { path: 'route-line', name: 'RouteLine', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '线路管理', module: 'routeLine' } },
      { path: 'employee', name: 'Employee', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '人员信息', module: 'employee' } },
      { path: 'department', name: 'Department', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '部门管理', module: 'department' } },
      { path: 'owner', name: 'Owner', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '货主信息', module: 'owner' } },
      { path: 'expense-type', name: 'ExpenseType', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '费用类型', module: 'expenseType' } },
      { path: 'counterparty', name: 'Counterparty', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '往来单位', module: 'counterparty' } },
      { path: 'counterparty-type', name: 'CounterpartyType', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '单位类型管理', module: 'counterpartyType', public: false } },
      { path: 'fund-account', name: 'FundAccount', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '资金账户', module: 'fundAccount' } },

      // 采购管理
      { path: 'purchase-order', name: 'PurchaseOrder', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购订单', module: 'purchaseOrder' } },
      { path: 'purchase-inbound', name: 'PurchaseInbound', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购入库', module: 'purchaseInbound' } },
      { path: 'purchase-receipt', name: 'PurchaseReceipt', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购收货单', module: 'purchaseReceipt' } },
      { path: 'purchase-return-apply', name: 'PurchaseReturnApply', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购退货申请', module: 'purchaseReturnApply' } },
      { path: 'purchase-return-outbound', name: 'PurchaseReturnOutbound', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购退货出库', module: 'purchaseReturnOutbound' } },
      { path: 'purchase-return', name: 'PurchaseReturn', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购退货单', module: 'purchaseReturn' } },
      { path: 'purchase-expense', name: 'PurchaseExpense', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购费用单', module: 'purchaseExpense' } },
      { path: 'purchase-invoice', name: 'PurchaseInvoice', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购发票', module: 'purchaseInvoice' } },

      // 销售管理
      { path: 'quick-order', name: 'QuickOrder', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '快速开单', module: 'quickOrder' } },
      { path: 'sales-order', name: 'SalesOrder', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '销售订单', module: 'salesOrder' } },
      { path: 'sales-outbound', name: 'SalesOutbound', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '销售出库', module: 'salesOutbound' } },
      { path: 'sales-receipt', name: 'SalesReceipt', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '销售发货单', module: 'salesReceipt' } },
      { path: 'reject-inbound', name: 'RejectInbound', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '拒收入库单', module: 'rejectInbound' } },
      { path: 'sales-return', name: 'SalesReturn', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '销售退货单', module: 'salesReturn' } },
      { path: 'sales-return-inbound', name: 'SalesReturnInbound', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '销售退货入库', module: 'salesReturnInbound' } },
      { path: 'sales-invoice', name: 'SalesInvoice', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '销售发票', module: 'salesInvoice' } },
      { path: 'fly-order', name: 'FlyOrder', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '飞单', module: 'flyOrder' } },
      { path: 'empty-adjust', name: 'EmptyAdjust', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '客户空退空出', module: 'emptyAdjust' } },

      // 库存管理
      { path: 'stock-balance', name: 'StockBalance', component: () => import('@/views/StockQuery.vue'), meta: { title: '库存查询', module: 'stockBalance' } },
      { path: 'stock-ledger', name: 'StockLedger', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '库存流水', module: 'stockLedger' } },
      { path: 'stock-warning', name: 'StockWarning', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '库存预警', module: 'stockWarning' } },
      { path: 'transfer-apply', name: 'TransferApply', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '调拨申请单', module: 'transferApply' } },
      { path: 'transfer-outbound', name: 'TransferOutbound', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '调拨出库单', module: 'transferOutbound' } },
      { path: 'transfer-inbound', name: 'TransferInbound', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '调拨入库单', module: 'transferInbound' } },
      { path: 'damage', name: 'Damage', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '报损单', module: 'damage' } },
      { path: 'cost-adjust', name: 'CostAdjust', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '成本调整单', module: 'costAdjust' } },
      { path: 'stock-adjust', name: 'StockAdjust', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '库存调整单', module: 'stockAdjust' } },
      { path: 'other-inbound', name: 'OtherInbound', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '其他入库', module: 'otherInbound' } },
      { path: 'other-outbound', name: 'OtherOutbound', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '其他出库', module: 'otherOutbound' } },
      { path: 'stock-take', name: 'StockTake', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '库存盘点', module: 'stockTake' } },

      // 财务管理
      { path: 'ar', name: 'AR', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '客户应收明细表', module: 'ar' } },
      { path: 'ap', name: 'AP', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '应付账款', module: 'ap' } },
      { path: 'receipt-payment', name: 'ReceiptPayment', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '收款单', module: 'receiptPayment' } },
      { path: 'payment-module', name: 'PaymentModule', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '付款单', module: 'paymentModule' } },
      { path: 'reconcile-record', name: 'ReconcileRecord', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '收款核销流水表', module: 'reconcileRecord' } },
      { path: 'ar-settlement', name: 'ARSettlement', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '应收结算', module: 'arSettlement' } },
      { path: 'ap-settlement', name: 'APSettlement', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '应付结算', module: 'apSettlement' } },
      { path: 'finance-expense', name: 'FinanceExpense', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '费用单', module: 'financeExpense' } },
      { path: 'fund-ledger', name: 'FundLedger', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '资金流水', module: 'fundLedger' } },
      { path: 'counterparty-ar', name: 'CounterpartyAR', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '往来单位应收', module: 'counterpartyAr' } },
      { path: 'counterparty-ap', name: 'CounterpartyAP', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '往来单位应付', module: 'counterpartyAp' } },
      { path: 'receipt-verify', name: 'ReceiptVerify', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '收款核销', module: 'receiptVerify' } },
      { path: 'payment-verify', name: 'PaymentVerify', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '付款核销', module: 'paymentVerify' } },
      { path: 'customer-statement', name: 'CustomerStatement', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '客户对账', module: 'customerStatement' } },
      { path: 'supplier-statement', name: 'SupplierStatement', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '供应商对账', module: 'supplierStatement' } },

      // 系统管理
      { path: 'user', name: 'User', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '用户管理', module: 'user' } },
      { path: 'role', name: 'Role', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '权限组管理', module: 'role' } },
      { path: 'param', name: 'Param', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '系统参数', module: 'param' } },
      { path: 'param-setting', name: 'ParamSetting', component: () => import('@/views/system/ParamSetting.vue'), meta: { title: '参数设置', module: 'paramSetting' } },
      { path: 'log', name: 'Log', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '操作日志', module: 'log' } },
      { path: 'dictionary', name: 'Dictionary', component: () => import('@/views/DictionaryPage.vue'), meta: { title: '用户数据字典', module: 'dictionary' } },
      { path: 'bill-no', name: 'BillNo', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '单据编号规则', module: 'billNo' } },
      { path: 'precision', name: 'Precision', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '显示精度设置', module: 'precision' } },
      { path: 'workflow', name: 'Workflow', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '审批流配置', module: 'workflow' } },
      { path: 'print-template', name: 'PrintTemplate', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '打印模板设置', module: 'printTemplate' } },
      { path: 'import-list', name: 'ImportList', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '导入列表', module: 'importList' } },
      { path: 'export-center', name: 'ExportCenter', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '导出中心', module: 'exportCenter' } },

      // 客户价格
      { path: 'customer-price', name: 'CustomerPrice', component: () => import('@/views/CustomerPriceAdjust.vue'), meta: { title: '客户价格调整' } },
      { path: 'customer-price/new', name: 'CustomerPriceNew', component: () => import('@/views/CustomerPriceEdit.vue'), meta: { title: '新建客户价格调整' } },
      { path: 'customer-price/edit/:id', name: 'CustomerPriceEdit', component: () => import('@/views/CustomerPriceEdit.vue'), meta: { title: '编辑客户价格调整' } },
      { path: 'customer-price-query', name: 'CustomerPriceQuery', component: () => import('@/views/CustomerPriceQuery.vue'), meta: { title: '客户价格查询' } },
      { path: 'customer-price-change', name: 'CustomerPriceChange', component: () => import('@/views/CustomerPriceChangeQuery.vue'), meta: { title: '客户商品变价查询' } },

      // 报表中心
      { path: 'sales-report', name: 'SalesReport', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '销售报表', module: 'salesReport' } },
      { path: 'purchase-report', name: 'PurchaseReport', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购报表', module: 'purchaseReport' } },
      { path: 'stock-report', name: 'StockReport', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '库存报表', module: 'stockReport' } },
      { path: 'finance-report', name: 'FinanceReport', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '财务报表', module: 'financeReport' } },
      { path: 'chart-report', name: 'ChartReport', component: () => import('@/views/ReportChartPage.vue'), meta: { title: '图表报表' } },
      // 运输管理（TMS）
      { path: 'tms-dispatch-pool', name: 'TmsDispatchPool', component: () => import('@/views/tms/DispatchPool.vue'), meta: { title: '配送任务池', module: 'tms-dispatch-pool' } },
      { path: 'tms-dispatch-list', name: 'TmsDispatchList', component: () => import('@/views/tms/DispatchList.vue'), meta: { title: '调度单管理', module: 'tms-dispatch-list' } },
      { path: 'tms-return-dispatch', name: 'TmsReturnDispatch', component: () => import('@/views/tms/SalesReturnDispatch.vue'), meta: { title: '退货单调度', module: 'tms-return-dispatch' } },
      { path: 'tms-delivery-monitor', name: 'TmsDeliveryMonitor', component: () => import('@/views/tms/DeliveryMonitor.vue'), meta: { title: '在途监控', module: 'tms-delivery-monitor' } },
      { path: 'tms-sign-verify', name: 'TmsSignVerify', component: () => import('@/views/tms/SignVerify.vue'), meta: { title: '签收核销', module: 'tms-sign-verify' } },
      { path: 'tms-driver-return', name: 'TmsDriverReturn', component: () => import('@/views/tms/DriverReturnList.vue'), meta: { title: '司机退货单', module: 'tms-driver-return' } },
      { path: 'tms-reschedule-return', name: 'TmsRescheduleReturn', component: () => import('@/views/tms/RescheduleReturnList.vue'), meta: { title: '改派返仓单', module: 'tms-reschedule-return' } },
      { path: 'tms-customer-reject', name: 'TmsCustomerReject', component: () => import('@/views/tms/CustomerRejectList.vue'), meta: { title: '客户拒收单', module: 'tms-customer-reject' } },
      { path: 'tms-exception-report', name: 'TmsExceptionReport', component: () => import('@/views/tms/ExceptionReportList.vue'), meta: { title: '异常上报处理', module: 'tms-exception-report' } },
      { path: 'tms-settlement', name: 'TmsSettlement', component: () => import('@/views/tms/SettlementList.vue'), meta: { title: '交账单管理', module: 'tms-settlement' } },
      { path: 'tms-store-location', name: 'TmsStoreLocation', component: () => import('@/views/tms/StoreLocationList.vue'), meta: { title: '门店定位审核', module: 'tms-store-location' } },
      { path: 'tms-dashboard', name: 'TmsDashboard', component: () => import('@/views/tms/DispatchDashboard.vue'), meta: { title: '调度看板', module: 'tms-dashboard' } },

      { path: 'notification', name: 'Notification', component: () => import('@/views/NotificationPage.vue'), meta: { title: '消息通知' } },
      { path: 'todo', name: 'Todo', component: () => import('@/views/TodoPage.vue'), meta: { title: '待办中心' } },
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.token) {
    next('/login')
  } else if (to.meta.public && auth.token) {
    next('/')
  } else {
    next()
  }
})

export default router
