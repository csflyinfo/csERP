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

      // 采购管理
      { path: 'purchase-order', name: 'PurchaseOrder', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购订单', module: 'purchaseOrder' } },
      { path: 'purchase-inbound', name: 'PurchaseInbound', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购入库', module: 'purchaseInbound' } },
      { path: 'purchase-receipt', name: 'PurchaseReceipt', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购收货单', module: 'purchaseReceipt' } },

      // 销售管理
      { path: 'quick-order', name: 'QuickOrder', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '快速开单', module: 'quickOrder' } },
      { path: 'sales-order', name: 'SalesOrder', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '销售订单', module: 'salesOrder' } },
      { path: 'sales-outbound', name: 'SalesOutbound', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '销售出库', module: 'salesOutbound' } },
      { path: 'sales-receipt', name: 'SalesReceipt', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '销售收货单', module: 'salesReceipt' } },

      // 库存管理
      { path: 'stock-balance', name: 'StockBalance', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '库存余额', module: 'stockBalance' } },
      { path: 'stock-ledger', name: 'StockLedger', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '库存流水', module: 'stockLedger' } },

      // 财务管理
      { path: 'ar', name: 'AR', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '应收账款', module: 'ar' } },
      { path: 'ap', name: 'AP', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '应付账款', module: 'ap' } },
      { path: 'receipt-payment', name: 'ReceiptPayment', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '收付款单', module: 'receiptPayment' } },

      // 系统管理
      { path: 'user', name: 'User', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '用户管理', module: 'user' } },
      { path: 'role', name: 'Role', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '权限组管理', module: 'role' } },
      { path: 'param', name: 'Param', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '系统参数', module: 'param' } },
      { path: 'log', name: 'Log', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '操作日志', module: 'log' } },

      // 客户价格
      { path: 'customer-price', name: 'CustomerPrice', component: () => import('@/views/CustomerPriceAdjust.vue'), meta: { title: '客户价格调整' } },
      { path: 'customer-price-query', name: 'CustomerPriceQuery', component: () => import('@/views/CustomerPriceQuery.vue'), meta: { title: '客户价格查询' } },

      // 报表中心
      { path: 'sales-report', name: 'SalesReport', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '销售报表', module: 'salesReport' } },
      { path: 'purchase-report', name: 'PurchaseReport', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '采购报表', module: 'purchaseReport' } },
      { path: 'stock-report', name: 'StockReport', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '库存报表', module: 'stockReport' } },
      { path: 'finance-report', name: 'FinanceReport', component: () => import('@/views/GenericBusinessList.vue'), meta: { title: '财务报表', module: 'financeReport' } },
      { path: 'chart-report', name: 'ChartReport', component: () => import('@/views/ReportChartPage.vue'), meta: { title: '图表报表' } },
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
