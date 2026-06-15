<script setup>
import { computed, onMounted, ref } from 'vue'
import QueryBar from './components/QueryBar.vue'
import ProTable from './components/ProTable.vue'
import CustomerPriceAdjust from './views/CustomerPriceAdjust.vue'
import CustomerPriceEdit from './views/CustomerPriceEdit.vue'
import CustomerPriceQuery from './views/CustomerPriceQuery.vue'
import GenericBusinessList from './views/GenericBusinessList.vue'
import { get, post } from './api/client.js'
import { moduleConfigs } from './module-config.js'

const fallbackMenus = {
  首页: [{ code: 'dashboard', name: '经营概览' }],
  基础资料: [
    { code: 'goods', name: '商品档案' },
    { code: 'category', name: '商品分类' },
    { code: 'brand', name: '品牌管理' },
    { code: 'unit', name: '单位管理' },
    { code: 'customer', name: '门店/客户资料' },
    { code: 'supplier', name: '供应商资料' },
    { code: 'warehouse', name: '仓库资料' },
    { code: 'priceGroup', name: '价格组设置' },
    { code: 'customerPrice', name: '客户价格调整单' },
    { code: 'customerPriceQuery', name: '客户价格查询' },
    { code: 'territory', name: '片区管理' },
    { code: 'routeLine', name: '线路管理' },
    { code: 'employee', name: '人员信息' },
    { code: 'department', name: '部门管理' },
    { code: 'owner', name: '货主信息' },
    { code: 'expenseType', name: '费用类型' },
    { code: 'counterparty', name: '往来单位' },
    { code: 'fundAccount', name: '资金账户' },
  ],
  采购管理: [
    { code: 'purchaseOrder', name: '采购订单' },
    { code: 'purchaseInbound', name: '采购入库' },
    { code: 'purchaseReceipt', name: '采购收货单' },
    { code: 'purchaseReturn', name: '采购退货单' },
    { code: 'purchaseExpense', name: '采购费用单' },
    { code: 'purchaseInvoice', name: '采购发票' },
  ],
  销售管理: [
    { code: 'quickOrder', name: '销售快速开单' },
    { code: 'salesOrder', name: '销售订单' },
    { code: 'salesOutbound', name: '销售出库' },
    { code: 'salesReceipt', name: '销售收货单' },
    { code: 'salesReturn', name: '销售退货单' },
    { code: 'salesInvoice', name: '销售发票' },
    { code: 'flyOrder', name: '飞单' },
    { code: 'emptyAdjust', name: '客户空退空出' },
  ],
  库存管理: [
    { code: 'stockBalance', name: '库存余额' },
    { code: 'stockLedger', name: '库存流水' },
    { code: 'stockLock', name: '库存锁定' },
    { code: 'batchStock', name: '批次库存' },
    { code: 'stockWarning', name: '库存预警' },
    { code: 'transfer', name: '调拨单' },
    { code: 'damage', name: '报损单' },
    { code: 'costAdjust', name: '成本调整单' },
    { code: 'stockAdjust', name: '库存调整单' },
    { code: 'otherInbound', name: '其他入库' },
    { code: 'otherOutbound', name: '其他出库' },
    { code: 'stockTake', name: '库存盘点' },
  ],
  财务管理: [
    { code: 'ar', name: '应收账款' },
    { code: 'ap', name: '应付账款' },
    { code: 'receiptPayment', name: '收付款单' },
    { code: 'arSettlement', name: '应收结算' },
    { code: 'apSettlement', name: '应付结算' },
    { code: 'financeExpense', name: '费用单' },
    { code: 'fundLedger', name: '资金流水' },
    { code: 'counterpartyAr', name: '往来单位应收' },
    { code: 'counterpartyAp', name: '往来单位应付' },
    { code: 'receiptVerify', name: '收款核销' },
    { code: 'paymentVerify', name: '付款核销' },
    { code: 'customerStatement', name: '客户对账' },
    { code: 'supplierStatement', name: '供应商对账' },
  ],
  报表中心: [
    { code: 'salesReport', name: '销售报表' },
    { code: 'purchaseReport', name: '采购报表' },
    { code: 'stockReport', name: '库存报表' },
    { code: 'financeReport', name: '财务报表' },
  ],
  系统管理: [
    { code: 'user', name: '用户管理' },
    { code: 'role', name: '权限组管理' },
    { code: 'param', name: '系统参数' },
    { code: 'billNo', name: '单据编号规则' },
    { code: 'precision', name: '显示精度设置' },
    { code: 'dictionary', name: '用户数据字典' },
    { code: 'workflow', name: '审批流配置' },
    { code: 'printTemplate', name: '打印模板设置' },
    { code: 'importList', name: '导入列表' },
    { code: 'exportCenter', name: '导出中心' },
    { code: 'log', name: '操作日志' },
  ],
}

const menus = ref(fallbackMenus)
const activeTop = ref('首页')
const current = ref('dashboard')
const openTabs = ref(['dashboard'])
const toastText = ref('')
const flowResult = ref(null)
const dashboardSummary = ref(null)
const dashboardLoading = ref(false)
const dashboardError = ref('')
const recentLogs = ref([])
const loginForm = ref({ username: 'admin', password: 'admin123' })
const loginError = ref('')
const loginLoading = ref(false)
const currentUser = ref(null)
const authToken = ref(localStorage.getItem('erp-demo-token') || '')
const menuLoading = ref(false)

const currentName = computed(() => moduleConfigs[current.value]?.title || Object.values(menus.value).flat().find(x => x.code === current.value)?.name || (current.value === 'customerPriceEdit' ? '客户价格调整单' : '经营概览'))
const currentDesc = computed(() => {
  if (moduleConfigs[current.value]?.desc) return moduleConfigs[current.value].desc
  const map = {
    dashboard: '企业经销数据、排行与待办',
    customerPriceEdit: '先选客户与生效规则，再添加调价商品；审核后才确认',
  }
  return map[current.value] || '紧凑查询、列表管理、新建编辑、导入导出'
})

const genericColumns = [
  { key: 'code', title: '编码' },
  { key: 'name', title: '名称' },
  { key: 'group', title: '类型/分组' },
  { key: 'status', title: '状态' },
  { key: 'action', title: '操作' },
]
const genericRows = computed(() => [{ code: `${current.value.toUpperCase()}001`, name: `${currentName.value}示例`, group: '默认', status: '正常', action: '编辑' }])
const currentConfig = computed(() => moduleConfigs[current.value])
const tabItems = computed(() => openTabs.value.map(code => ({ code, name: moduleConfigs[code]?.title || Object.values(menus.value).flat().find(x => x.code === code)?.name || (code === 'customerPriceEdit' ? '客户价格调整单' : '经营概览') })))
const dashboardCards = computed(() => {
  const summary = dashboardSummary.value || {}
  return [
    { label: '销售金额', value: money(summary.salesAmount ?? 82450) },
    { label: '采购金额', value: money(summary.purchaseAmount ?? 3500) },
    { label: '库存金额', value: money(summary.stockAmount ?? 1420000) },
    { label: '可用库存', value: numberText(summary.availableQty ?? 1020) },
    { label: '应收余额', value: money(summary.arBalance ?? 350) },
    { label: '应付余额', value: money(summary.apBalance ?? 3955) },
    { label: '销售单数', value: numberText(summary.salesOrderCount ?? 0) },
    { label: '采购单数', value: numberText(summary.purchaseOrderCount ?? 0) },
    { label: '未核销应收', value: numberText(summary.arCount ?? 0) },
    { label: '未核销应付', value: numberText(summary.apCount ?? 0) },
    { label: '导入完成', value: numberText(summary.importFinishedCount ?? 0) },
    { label: '导出完成', value: numberText(summary.exportFinishedCount ?? 0) },
  ]
})

function route(code) {
  current.value = code
  if (!openTabs.value.includes(code)) openTabs.value.push(code)
  Object.entries(menus.value).forEach(([top, items]) => {
    if (items.some(item => item.code === code)) activeTop.value = top
  })
}

function closeTab(code) {
  if (code === 'dashboard') return
  const index = openTabs.value.indexOf(code)
  openTabs.value = openTabs.value.filter(item => item !== code)
  if (current.value === code) {
    current.value = openTabs.value[Math.max(0, index - 1)] || 'dashboard'
    Object.entries(menus.value).forEach(([top, items]) => {
      if (items.some(item => item.code === current.value)) activeTop.value = top
    })
  }
}

function quickLocate(value) {
  const hit = Object.values(menus.value).flat().find(item => item.name.includes(value))
  if (hit) route(hit.code)
  else toast('未找到匹配模块')
}

function toast(message) {
  toastText.value = message
  setTimeout(() => (toastText.value = ''), 1800)
}

function normalizeMenus(tree = []) {
  const result = {}
  tree.forEach(item => {
    const children = item.children?.length ? item.children : [item]
    result[item.name] = children.map(child => ({ code: child.code, name: child.name }))
  })
  return Object.keys(result).length ? result : fallbackMenus
}

async function loadCurrentUser() {
  try {
    currentUser.value = await get('/auth/current-user')
  } catch (error) {
    currentUser.value = { displayName: '系统管理员', username: 'admin', roles: ['ADMIN'] }
  }
}

async function loadUserMenus() {
  menuLoading.value = true
  try {
    menus.value = normalizeMenus(await get('/system/menu/user-tree'))
    if (!menus.value[activeTop.value]) activeTop.value = Object.keys(menus.value)[0] || '首页'
  } catch (error) {
    menus.value = fallbackMenus
    toast('菜单接口加载失败，已使用本地菜单')
  } finally {
    menuLoading.value = false
  }
}

async function login() {
  loginLoading.value = true
  loginError.value = ''
  try {
    const result = await post('/auth/login', loginForm.value)
    authToken.value = result.token
    localStorage.setItem('erp-demo-token', result.token)
    await Promise.all([loadCurrentUser(), loadUserMenus(), loadDashboardSummary(), loadRecentLogs()])
    toast('登录成功')
  } catch (error) {
    authToken.value = ''
    localStorage.removeItem('erp-demo-token')
    loginError.value = error.message || '登录失败'
  } finally {
    loginLoading.value = false
  }
}

async function logout() {
  try { await post('/auth/logout') } catch (error) {}
  authToken.value = ''
  currentUser.value = null
  localStorage.removeItem('erp-demo-token')
  menus.value = fallbackMenus
  current.value = 'dashboard'
  activeTop.value = '首页'
  openTabs.value = ['dashboard']
}

async function bootstrap() {
  window.addEventListener('erp-auth-expired', logout)
  if (!authToken.value) return
  await Promise.all([loadCurrentUser(), loadUserMenus(), loadDashboardSummary(), loadRecentLogs()])
}

async function loadRecentLogs() {
  try {
    const data = await post('/system/operation-log/page', { pageNo: 1, pageSize: 6, sortField: 'operateAt', sortOrder: 'desc', filters: {} })
    recentLogs.value = data.records || []
  } catch (error) {
    recentLogs.value = []
  }
}

function money(value) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function numberText(value) {
  return Number(value || 0).toLocaleString('zh-CN')
}

async function loadDashboardSummary() {
  dashboardLoading.value = true
  dashboardError.value = ''
  try {
    dashboardSummary.value = await get('/report/dashboard/summary')
  } catch (error) {
    dashboardError.value = '经营概览接口加载失败，已显示演示指标'
  } finally {
    dashboardLoading.value = false
  }
}

async function runCoreFlow() {
  try {
    flowResult.value = await post('/flow/v1-core/self-test', {})
    toast('V1.0核心闭环自测通过')
  } catch (error) {
    toast(`核心闭环自测失败：${error.message}`)
  }
}

function showCreate() {
  if (current.value === 'category') toast('打开商品分类新建弹窗')
  else if (current.value === 'customerPrice') route('customerPriceEdit')
  else toast('打开新建页面')
}

onMounted(bootstrap)
</script>

<template>
  <div v-if="!authToken" class="login-page">
    <div class="login-card">
      <div class="brand login-brand"><div class="mark"></div>商贸云 ERP V1.0</div>
      <p>开发演示账号：admin / admin123</p>
      <div class="field"><label>账号</label><input v-model="loginForm.username" placeholder="请输入账号" @keydown.enter="login" /></div>
      <div class="field"><label>密码</label><input v-model="loginForm.password" type="password" placeholder="请输入密码" @keydown.enter="login" /></div>
      <div v-if="loginError" class="login-error">{{ loginError }}</div>
      <button class="btn primary login-submit" :disabled="loginLoading" @click="login">{{ loginLoading ? '登录中...' : '登录系统' }}</button>
    </div>
  </div>

  <div v-else class="shell">
    <header class="top">
      <div class="brand"><div class="mark"></div>商贸云 ERP V1.0</div>
      <button class="hamb" @click="toast('菜单折叠/展开')">☰</button>
      <div class="nav-module">
        <div class="title">{{ currentName }}</div>
        <div class="desc">{{ currentDesc }}</div>
      </div>
      <div class="spacer"></div>
      <div class="actions">
        <button class="btn" @click="route(current === 'customerPriceEdit' ? 'customerPrice' : current)">返回列表</button>
        <button class="btn primary" @click="showCreate">新建</button>
      </div>
      <button class="topbtn" @click="route('exportCenter')">导出中心</button>
      <button class="topbtn" @click="route('log')">消息</button>
      <button class="topbtn" @click="logout">退出</button>
      <div class="user"><div class="avatar">{{ currentUser?.displayName?.slice(0, 1) || '管' }}</div><span>{{ currentUser?.displayName || '管理员' }}</span></div>
    </header>

    <aside class="side">
      <div class="side-search">
        <input class="quick-search" placeholder="模块快捷搜索：客户、商品、供应商、单据号" @keydown.enter="quickLocate($event.target.value)" />
        <div v-if="menuLoading" class="menu-loading">正在加载权限菜单...</div>
      </div>
      <template v-for="(items, top) in menus" :key="top">
        <div class="lvl1" :class="{ on: activeTop === top }" @click="activeTop = top; route(items[0].code)">
          <span class="dot"></span>{{ top }}
        </div>
        <div v-if="activeTop === top" class="submenu">
          <div v-for="item in items" :key="item.code" class="lvl2" :class="{ on: current === item.code }" @click.stop="route(item.code)">
            {{ item.name }}
          </div>
        </div>
      </template>
    </aside>

    <main class="main">
      <div class="tabbar">
        <div v-for="tab in tabItems" :key="tab.code" class="top-tab" :class="{ active: current === tab.code }" @click="route(tab.code)">
          <span>{{ tab.name }}</span>
          <button v-if="tab.code !== 'dashboard'" class="tab-close" @click.stop="closeTab(tab.code)">×</button>
        </div>
      </div>
      <div class="content">
        <section v-if="current === 'dashboard'">
          <div class="page-ops">
            <button class="btn" @click="loadDashboardSummary">刷新经营指标</button>
            <button class="btn primary" @click="runCoreFlow">核心闭环自测</button>
            <span v-if="dashboardLoading" class="muted">正在加载经营概览...</span>
            <span v-else-if="dashboardError" class="muted">{{ dashboardError }}</span>
          </div>
          <div class="cards">
            <div v-for="item in dashboardCards" :key="item.label" class="card">
              <div>{{ item.label }}</div>
              <div class="value">{{ item.value }}</div>
            </div>
          </div>
          <div class="tablebox" style="margin-bottom:8px">
            <div class="toolbar"><b>最近操作动态</b><div class="spacer"></div><button class="btn" @click="loadRecentLogs">刷新动态</button></div>
            <div style="padding:12px;display:grid;grid-template-columns:repeat(3,1fr);gap:8px">
              <div v-for="log in recentLogs" :key="log.bizNo + log.action + log.operateAt" class="card">
                <b>{{ log.action }} · {{ log.moduleCode }}</b>
                <p>{{ log.detail || log.bizNo }}</p>
                <span class="muted">{{ log.operatorName }} {{ log.operateAt }}</span>
              </div>
              <div v-if="!recentLogs.length" class="card"><b>暂无操作动态</b><p>导入、导出、系统配置等操作会显示在这里。</p></div>
            </div>
          </div>
          <div v-if="flowResult" class="tablebox">
            <div class="toolbar"><b>V1.0核心闭环自测结果</b></div>
            <div style="padding:12px;display:grid;grid-template-columns:repeat(5,1fr);gap:8px">
              <div class="card"><b>采购闭环</b><p>{{ flowResult.purchaseCycle.purchaseInbound.effect }}</p></div>
              <div class="card"><b>销售闭环</b><p>{{ flowResult.salesCycle.salesOutbound.effect }}</p></div>
              <div class="card"><b>应收核销</b><p>{{ flowResult.arReceipt.ar.status }}</p></div>
              <div class="card"><b>应付核销</b><p>{{ flowResult.apPayment.ap.status }}</p></div>
              <div class="card"><b>客户价格</b><p>{{ flowResult.customerPrice.effect }}</p></div>
            </div>
          </div>
        </section>

        <CustomerPriceAdjust v-else-if="current === 'customerPrice'" @create="route('customerPriceEdit')" />
        <CustomerPriceEdit v-else-if="current === 'customerPriceEdit'" />
        <CustomerPriceQuery v-else-if="current === 'customerPriceQuery'" />
        <GenericBusinessList v-else-if="currentConfig" :config="currentConfig" :module-code="current" />

        <section v-else>
          <QueryBar :fields="['关键字', '状态']" />
          <ProTable :title="currentName + '列表'" :columns="genericColumns" :rows="genericRows">
            <template #status="{ row }"><span class="badge ok">{{ row.status }}</span></template>
            <template #action="{ row }"><span class="link" @click="showCreate">{{ row.action }}</span></template>
          </ProTable>
        </section>
      </div>
    </main>

    <div v-if="toastText" style="position:fixed;right:18px;bottom:18px;background:#12385f;color:#fff;border-radius:10px;padding:12px 16px;z-index:99">{{ toastText }}</div>
  </div>
</template>
