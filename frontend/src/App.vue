<script setup>
import { computed, onMounted } from 'vue'
import AppShell from './layout/AppShell.vue'
import LoginPage from './views/LoginPage.vue'
import DashboardPage from './views/DashboardPage.vue'
import CustomerPriceAdjust from './views/CustomerPriceAdjust.vue'
import CustomerPriceEdit from './views/CustomerPriceEdit.vue'
import CustomerPriceQuery from './views/CustomerPriceQuery.vue'
import GenericBusinessList from './views/GenericBusinessList.vue'
import FallbackBusinessList from './views/FallbackBusinessList.vue'
import { moduleConfigs } from './module-config.js'
import { useAuth } from './composables/useAuth.js'
import { useDashboard } from './composables/useDashboard.js'
import { useNavigation } from './composables/useNavigation.js'
import { useToast } from './composables/useToast.js'

const { toastText, toast } = useToast()
const { loginForm, loginError, loginLoading, currentUser, authToken, loadCurrentUser, login, logout } = useAuth()
const { menus, activeTop, current, openTabs, menuLoading, route, closeTab, quickLocate, loadUserMenus, resetNavigation } = useNavigation(toast)
const { flowResult, dashboardLoading, dashboardError, recentLogs, dashboardCards, loadRecentLogs, loadDashboardSummary, runCoreFlow } = useDashboard(toast)

const currentConfig = computed(() => moduleConfigs[current.value])
const currentName = computed(() => moduleConfigs[current.value]?.title || Object.values(menus.value).flat().find(x => x.code === current.value)?.name || (current.value === 'customerPriceEdit' ? '客户价格调整单' : '经营概览'))
const currentDesc = computed(() => {
  if (moduleConfigs[current.value]?.desc) return moduleConfigs[current.value].desc
  const map = {
    dashboard: '企业经销数据、排行与待办',
    customerPriceEdit: '先选客户与生效规则，再添加调价商品；审核后才确认',
  }
  return map[current.value] || '紧凑查询、列表管理、新建编辑、导入导出'
})
const tabItems = computed(() => openTabs.value.map(code => ({ code, name: moduleConfigs[code]?.title || Object.values(menus.value).flat().find(x => x.code === code)?.name || (code === 'customerPriceEdit' ? '客户价格调整单' : '经营概览') })))

async function loadSessionData() {
  await loadCurrentUser()
  await Promise.all([loadUserMenus(currentUser.value?.roleCode), loadDashboardSummary(), loadRecentLogs()])
}

function doLogin() {
  login(loadSessionData, toast)
}

function doLogout() {
  logout(resetNavigation)
}

function showCreate() {
  if (current.value === 'category') toast('打开商品分类新建弹窗')
  else if (current.value === 'customerPrice') route('customerPriceEdit')
  else toast('打开新建页面')
}

async function bootstrap() {
  window.addEventListener('erp-auth-expired', doLogout)
  if (!authToken.value) return
  await loadSessionData()
}

onMounted(bootstrap)
</script>

<template>
  <LoginPage v-if="!authToken" :login-form="loginForm" :login-error="loginError" :login-loading="loginLoading" @login="doLogin" />

  <AppShell
    v-else
    :current="current"
    :current-name="currentName"
    :current-desc="currentDesc"
    :current-user="currentUser"
    :menus="menus"
    :active-top="activeTop"
    :menu-loading="menuLoading"
    :tabs="tabItems"
    :toast-text="toastText"
    @toast="toast"
    @route="route"
    @create="showCreate"
    @logout="doLogout"
    @set-active-top="activeTop = $event"
    @quick-locate="quickLocate"
    @close-tab="closeTab"
  >
    <DashboardPage
      v-if="current === 'dashboard'"
      :dashboard-cards="dashboardCards"
      :dashboard-loading="dashboardLoading"
      :dashboard-error="dashboardError"
      :recent-logs="recentLogs"
      :flow-result="flowResult"
      @refresh-summary="loadDashboardSummary"
      @refresh-logs="loadRecentLogs"
      @run-core-flow="runCoreFlow"
    />
    <CustomerPriceAdjust v-else-if="current === 'customerPrice'" @create="route('customerPriceEdit')" />
    <CustomerPriceEdit v-else-if="current === 'customerPriceEdit'" />
    <CustomerPriceQuery v-else-if="current === 'customerPriceQuery'" />
    <GenericBusinessList v-else-if="currentConfig" :config="currentConfig" :module-code="current" :role-code="currentUser?.roleCode || 'ADMIN'" />
    <FallbackBusinessList v-else :current="current" :current-name="currentName" @create="showCreate" />
  </AppShell>
</template>
