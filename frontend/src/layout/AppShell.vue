<script setup>
import { computed, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useAppStore } from '../stores/app.js'
import { useMenuStore } from '../stores/menu.js'
import { MENU_PATH, PATH_MENU } from '../router/menu-map.js'
import { firstPageCode, findNode, moduleCodeToPath, rootOfCode } from '../stores/menu-tree.js'
import { moduleConfigs } from '../module-config.js'
import { post } from '../api/client.js'
import BillDrawer from '../components/BillDrawer.vue'
import PurchaseInboundDrawer from '../components/PurchaseInboundDrawer.vue'
import SalesOutboundDrawer from '../components/SalesOutboundDrawer.vue'
import PurchaseReturnApplyDrawer from '../components/PurchaseReturnApplyDrawer.vue'
import PurchaseReceiptDrawer from '../components/PurchaseReceiptDrawer.vue'
import PurchaseInvoiceDrawer from '../components/PurchaseInvoiceDrawer.vue'
import PurchaseReturnOutboundDrawer from '../components/PurchaseReturnOutboundDrawer.vue'
import PurchaseReturnDrawer from '../components/PurchaseReturnDrawer.vue'
import SalesReturnDrawer from '../components/SalesReturnDrawer.vue'
import SalesReturnInboundDrawer from '../components/SalesReturnInboundDrawer.vue'
import RejectInboundDrawer from '../components/RejectInboundDrawer.vue'
import ReceiptSignDialog from '../components/ReceiptSignDialog.vue'
import FlyOrderDrawer from '../components/FlyOrderDrawer.vue'
import GoodsPriceAdjustDrawer from '../components/GoodsPriceAdjustDrawer.vue'
import StockTakeDrawer from '../components/StockTakeDrawer.vue'
import DamageDrawer from '../components/DamageDrawer.vue'
import OtherInboundDrawer from '../components/OtherInboundDrawer.vue'
import OtherOutboundDrawer from '../components/OtherOutboundDrawer.vue'
import ProfileDialog from '../components/rbac/ProfileDialog.vue'
import ChangePasswordDialog from '../components/rbac/ChangePasswordDialog.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()
// PRD-28 卡片8：侧边栏改由 /system/menu/user-tree 授权菜单驱动（三级），本地菜单仅在接口失败时降级
const menuStore = useMenuStore()
// 验证环境可通过 VITE_APP_TITLE/VITE_APP_BADGE 注入标识（如总账开发版），默认标题不变
const appTitle = import.meta.env.VITE_APP_TITLE || '商贸云 ERP V1.0'
const envBadge = import.meta.env.VITE_APP_BADGE || ''
if (typeof document !== 'undefined' && import.meta.env.VITE_APP_TITLE) {
  document.title = import.meta.env.VITE_APP_TITLE
}
const menuCollapsed = ref(false)
const toastText = ref('')
const todoCount = ref(0)
const notifyCount = ref(0)

// PRD-28：头像下拉 / 个人中心 / 修改密码
const userMenuOpen = ref(false)
const profileVisible = ref(false)
const pwdDialogVisible = ref(false)

// 首登或管理员重置密码后强制改密（标记来自登录返回或 /auth/profile）
const forcePwdVisible = computed(() => auth.mustChangePwd)

async function loadCounts() {
  try {
    const [todo, notify] = await Promise.all([
      post('/system/todo/pending-count', {}),
      post('/system/notification/unread-count', {}),
    ])
    todoCount.value = todo?.count || 0
    notifyCount.value = notify?.count || 0
  } catch (e) {}
}

onMounted(() => {
  loadCounts()
  const timer = setInterval(loadCounts, 30000)
  // 刷新后 auth.user 为 null（只持久化 token），补拉资料以驱动超管菜单与强制改密
  if (auth.token && !auth.user) {
    auth.fetchProfile().catch(() => {})
  }
  // 菜单树通常已由路由守卫预拉；此处兜底（如从旧标签页恢复时）
  if (auth.token) menuStore.ensure().catch(() => {})
  return () => clearInterval(timer)
})

function onPwdSaved() {
  pwdDialogVisible.value = false
  auth.markPasswordChanged()
  toast('密码修改成功')
}

function onProfilePwdChanged() {
  auth.markPasswordChanged()
  toast('密码修改成功')
}

// 预计算可用路由集合，用于判断菜单是否可导航（后端配了菜单但前端尚未实现页面时给提示）
const availableRoutes = new Set(router.getRoutes().map(r => r.path))

/**
 * 菜单编码 → 前端路由路径：
 * 服务端菜单码（base.goods / finance.gl.voucher）走 MENU_PATH；
 * 降级菜单的 moduleCode（goodsPriceAdjust）走 camelCase→kebab 兜底。
 */
function menuCodeToPath(code) {
  return MENU_PATH[code] || moduleCodeToPath(code)
}

const currentModule = computed(() => route.meta?.module || '')
const currentName = computed(() => route.meta?.title || '经营概览')
// 当前路由对应的菜单编码（PATH_MENU 命中才是菜单页，子页面/独立页为空）
const currentMenuCode = computed(() => PATH_MENU[route.path] || '')

// 当前展开的一级根目录：优先按路由反查，未命中（子页面/降级异常）保持手动展开项
const manualRoot = ref('')
const activeRoot = computed(() => {
  if (currentMenuCode.value) {
    const root = rootOfCode(menuStore.tree, currentMenuCode.value)
    if (root) return root.code
  }
  return manualRoot.value || menuStore.tree[0]?.code || ''
})

// 二级目录（财务管理 > 总账）展开状态；进入三级页面时自动展开对应目录
const expandedDirs = ref(new Set())
watch(currentMenuCode, (code) => {
  if (!code) return
  const root = rootOfCode(menuStore.tree, code)
  const dir = (root?.children || []).find(n => n.menuType === 'DIR' && (n.children || []).some(c => c.code === code))
  if (dir) expandedDirs.value = new Set([...expandedDirs.value, dir.code])
}, { immediate: true })

// 抽屉只在其模块页面渲染；状态在 store 保留，切换到其它模块时抽屉隐藏，回来时恢复
const billDrawerVisibleInCurrentModule = computed(() => {
  return app.billDrawer.visible && currentModule.value === app.billDrawer.moduleCode
})
// 采购入库抽屉：purchaseInbound / purchaseOrder 两个页面都可见
// （订单页点「生成入库单」后即使停留在订单页也能看到抽屉，保存后可跳去入库列表）
const inboundDrawerVisibleInCurrentModule = computed(() => {
  return app.inboundDrawer.visible &&
    (currentModule.value === 'purchaseInbound' || currentModule.value === 'purchaseOrder')
})

// 销售出库抽屉：salesOutbound / salesOrder 两个页面都可见（对称）
const outboundDrawerVisibleInCurrentModule = computed(() => {
  return app.outboundDrawer.visible &&
    (currentModule.value === 'salesOutbound' || currentModule.value === 'salesOrder')
})

// 采购收货单抽屉：仅 purchaseReceipt 页面
const receiptDrawerVisibleInCurrentModule = computed(() => {
  return app.receiptDrawer.visible && currentModule.value === 'purchaseReceipt'
})

// 采购发票抽屉：purchaseInvoice 页面；报表中心「未勾稽发票」报表点「勾稽」也在此打开
const invoiceDrawerVisibleInCurrentModule = computed(() => {
  return app.invoiceDrawer.visible &&
    (currentModule.value === 'purchaseInvoice' || currentModule.value === 'invoiceUnmatchedReport')
})

// 采购退货申请抽屉：仅 purchaseReturnApply 页面（商品从抽屉内的两个选择窗口添加）
const returnApplyDrawerVisibleInCurrentModule = computed(() => {
  return app.returnApplyDrawer.visible && currentModule.value === 'purchaseReturnApply'
})

// 采购退货出库抽屉：purchaseReturnOutbound / purchaseReturnApply 页面可见
// （申请审核后自动生成出库单，用户可直接在申请页跳去处理）
const returnOutboundDrawerVisibleInCurrentModule = computed(() => {
  return app.returnOutboundDrawer.visible &&
    (currentModule.value === 'purchaseReturnOutbound' || currentModule.value === 'purchaseReturnApply')
})

// 采购退货单抽屉：purchaseReturn / purchaseReturnOutbound 页面可见
const returnDrawerVisibleInCurrentModule = computed(() => {
  return app.returnDrawer.visible &&
    (currentModule.value === 'purchaseReturn' || currentModule.value === 'purchaseReturnOutbound')
})

// 销售退货单抽屉：仅 salesReturn 页面
const salesReturnDrawerVisibleInCurrentModule = computed(() => {
  return app.salesReturnDrawer.visible && currentModule.value === 'salesReturn'
})

// 销售退货入库抽屉：仅 salesReturnInbound 页面
const salesReturnInboundDrawerVisibleInCurrentModule = computed(() => {
  return app.salesReturnInboundDrawer.visible && currentModule.value === 'salesReturnInbound'
})

// 拒收入库单抽屉：仅 rejectInbound 页面
const rejectInboundDrawerVisibleInCurrentModule = computed(() => {
  return app.rejectInboundDrawer.visible && currentModule.value === 'rejectInbound'
})

// 发货单确认签收弹窗：仅 salesReceipt 页面
const receiptSignDialogVisibleInCurrentModule = computed(() => {
  return app.receiptSignDialog.visible && currentModule.value === 'salesReceipt'
})

// 飞单抽屉：仅 flyOrder 页面
const flyOrderDrawerVisibleInCurrentModule = computed(() => {
  return app.flyOrderDrawer.visible && currentModule.value === 'flyOrder'
})

// 商品调价单抽屉：goodsPriceAdjust / goods 页面可见（商品档案快速调价跳转）
const goodsPriceAdjustDrawerVisibleInCurrentModule = computed(() => {
  return app.goodsPriceAdjustDrawer.visible &&
    (currentModule.value === 'goodsPriceAdjust' || currentModule.value === 'goods')
})

// 盘点单抽屉：仅 stockTake 页面
const stockTakeDrawerVisibleInCurrentModule = computed(() => {
  return app.stockTakeDrawer.visible && currentModule.value === 'stockTake'
})

// 报损单抽屉：仅 damage 页面
const damageDrawerVisibleInCurrentModule = computed(() => {
  return app.damageDrawer.visible && currentModule.value === 'damage'
})

// 其他入库单抽屉：仅 otherInbound 页面
const otherInboundDrawerVisibleInCurrentModule = computed(() => {
  return app.otherInboundDrawer.visible && currentModule.value === 'otherInbound'
})

// 其他出库单抽屉：仅 otherOutbound 页面
const otherOutboundDrawerVisibleInCurrentModule = computed(() => {
  return app.otherOutboundDrawer.visible && currentModule.value === 'otherOutbound'
})

/** 入库单保存后：递增 refreshSignal，让当前页刷新；然后关闭抽屉 */
function onInboundSaved() {
  app.refreshSignal++
  app.showToast('采购入库单已保存')
  app.closeInboundDrawer()
}

/** 出库单保存后 */
function onOutboundSaved() {
  app.refreshSignal++
  app.showToast('销售出库单已保存')
  app.closeOutboundDrawer()
}

/** 收货单保存/审核后 */
function onReceiptSaved(result) {
  app.refreshSignal++
  app.showToast(result?.apNo ? `收货单已审核，应付单号 ${result.apNo}` : '采购收货单已保存')
}

/** 采购发票保存/审核/作废/认证后 */
function onInvoiceSaved(result) {
  app.refreshSignal++
  app.showToast(result?.effect || result?.invoiceNo ? `采购发票 ${result.invoiceNo || ''} 已处理` : '采购发票已保存')
}

/** 退货申请保存后 */
function onReturnApplySaved(result) {
  app.refreshSignal++
  app.showToast(result?.applyNo ? `采购退货申请 ${result.applyNo} 已保存` : '采购退货申请已保存')
  app.closeReturnApplyDrawer()
}

/** 退货出库保存/审核后：审核会返回 returnNo（自动生成的退货单号） */
function onReturnOutboundSaved(result) {
  app.refreshSignal++
  app.showToast(result?.returnNo
    ? `退货出库已审核，已生成采购退货单 ${result.returnNo}`
    : '采购退货出库单已保存')
}

/** 退货单审核/反审核后 */
function onReturnSaved(result) {
  app.refreshSignal++
  app.showToast(result?.apNo ? `退货单已审核，应付冲减单号 ${result.apNo}` : '采购退货单已更新')
}

/** 销售退货单保存后 */
function onSalesReturnSaved(result) {
  app.refreshSignal++
  const msg = result?.status === 'REJECTED' ? `退货单已驳回`
    : result?.status === 'CONFIRMED' ? `退货单已确认`
    : result?.status === 'APPROVED' ? `退货单已审核，已生成入库单 ${result.inboundNo || ''}`
    : result?.applyNo ? `销售退货单 ${result.applyNo} 已保存` : '销售退货单已保存'
  app.showToast(msg)
  app.closeSalesReturnDrawer()
}

/** 销售退货入库保存/审核后 */
function onSalesReturnInboundSaved(result) {
  app.refreshSignal++
  app.showToast(result?.status === 'APPROVED'
    ? '退货入库已审核，库存已回库'
    : '销售退货入库单已保存')
}

/** 拒收入库保存/审核/反审核后 */
function onRejectInboundSaved(result) {
  app.refreshSignal++
  if (result?.status === 'APPROVED') {
    app.showToast('拒收入库已审核，已按原出库成本单价回库')
  } else if (result?.status === 'PENDING' && result?.effect?.includes('扣回')) {
    app.showToast('拒收入库已反审核，库存已扣回')
  } else {
    app.showToast('拒收入库单已保存')
  }
}

/** 发货单确认签收后 */
function onReceiptSigned(result) {
  app.refreshSignal++
  // 后端在签收时已按签收数量重算金额并自动审核生成应收，这里把两件事都告知用户
  const parts = [result?.rejectInboundNo ? `已生成拒收入库单 ${result.rejectInboundNo}` : '无拒收商品']
  if (result?.arNo) parts.push(`已自动审核，应收 ${result.arNo}（签收金额 ${result.signAmount}）`)
  else parts.push('全部拒收，签收金额为 0，未生成应收')
  app.showToast('签收完成：' + parts.join('；'))
}

/** 飞单保存/审核后 */
function onFlyOrderSaved() {
  app.refreshSignal++
  app.closeFlyOrderDrawer()
}

/** 商品调价单保存/审核后 */
function onGoodsPriceAdjustSaved() {
  app.refreshSignal++
  app.showToast('商品调价单已保存')
}
const currentDesc = computed(() => moduleConfigs[currentModule.value]?.desc || '企业经销数据、排行与待办')
const currentUser = computed(() => auth.user || { displayName: '系统管理员' })

// 路由变化时自动添加 tab
watch(() => route.path, () => {
  if (route.meta?.title) {
    app.addTab(route)
  }
}, { immediate: true })

function navigate(code) {
  if (!code) return
  const path = menuCodeToPath(code)
  if (!availableRoutes.has(path)) {
    toast('该功能正在开发中')
    return
  }
  router.push(path)
}

/** 点一级目录：展开并跳到其第一个页面；同时记录手动展开项（菜单页反查失败时也能高亮） */
function onRootClick(root) {
  manualRoot.value = root.code
  navigate(firstPageCode(root))
}

/** 点二级目录：仅展开/折叠三级页面 */
function toggleDir(dirCode) {
  const next = new Set(expandedDirs.value)
  if (next.has(dirCode)) next.delete(dirCode)
  else next.add(dirCode)
  expandedDirs.value = next
}

/** 叶子是否对当前用户可见：服务端树已按授权裁剪；降级树 adminOnly 项仅超管可见 */
function leafVisible(leaf) {
  return !leaf.adminOnly || auth.isSuperAdmin
}

function doLogout() {
  auth.logout()
  router.push('/login')
}

function closeTab(path) {
  const isActive = route.path === path
  app.removeTab(path)
  if (isActive && app.openTabs.length > 0) {
    router.push(app.openTabs[app.openTabs.length - 1].path)
  }
}

function showCreate() {
  toast(`新建 ${currentName.value}`)
}

function toast(msg) {
  toastText.value = msg
  setTimeout(() => { toastText.value = '' }, 2000)
}

function quickLocate(keyword) {
  const found = findNode(menuStore.tree, keyword)
  if (!found) {
    toast('未找到匹配菜单')
    return
  }
  manualRoot.value = rootOfCode(menuStore.tree, found.code)?.code || manualRoot.value
  navigate(firstPageCode(found))
}

const topKeys = computed(() => menuStore.tree.map(r => r.code))
</script>

<template>
  <div class="shell">
    <!-- Header -->
    <header class="top">
      <div class="brand">
        <div class="mark"></div>{{ appTitle }}
        <span v-if="envBadge" class="env-badge">{{ envBadge }}</span>
      </div>
      <button class="hamb" @click="menuCollapsed = !menuCollapsed">☰</button>
      <!-- 打开的模块 Tab（从主内容区提升到顶栏） -->
      <div class="tab-bar-top" v-if="app.openTabs.length > 0">
        <div
          v-for="tab in app.openTabs"
          :key="tab.path"
          class="tab"
          :class="{ active: route.path === tab.path }"
          @click="router.push(tab.path)"
        >
          {{ tab.name }}
          <span class="tab-close" @click.stop="closeTab(tab.path)">×</span>
        </div>
      </div>
      <!-- 没有 tab 时用 spacer 把右侧按钮推到边 -->
      <div v-else class="spacer"></div>
      <button v-if="menuStore.hasMenu('system.export_center')" class="topbtn" @click="navigate('system.export_center')">导出中心</button>
      <div class="user-menu-wrap">
        <div class="user" :class="{ on: userMenuOpen }" style="cursor:pointer" title="账户菜单"
             @click="userMenuOpen = !userMenuOpen">
          <div class="avatar">{{ currentUser?.displayName?.slice(0, 1) || '管' }}</div>
          <span>{{ currentUser?.displayName || '管理员' }}</span>
          <span class="caret">▾</span>
        </div>
        <div v-if="userMenuOpen" class="user-dropdown">
          <div class="dropdown-item" @click="userMenuOpen = false; profileVisible = true">个人中心</div>
          <div class="dropdown-item" @click="userMenuOpen = false; pwdDialogVisible = true">修改密码</div>
          <div class="dropdown-item danger" @click="doLogout">退出登录</div>
        </div>
      </div>
      <!-- 透明遮罩：点击页面任意处关闭账户下拉 -->
      <div v-if="userMenuOpen" class="dropdown-mask" @click="userMenuOpen = false"></div>
    </header>

    <!-- Sidebar：PRD-28 卡片8 起按用户授权菜单树渲染（支持三级：一级目录/二级页面或子目录/三级页面） -->
    <aside v-show="!menuCollapsed" class="side">
      <div class="side-search">
        <input
          class="quick-search"
          placeholder="模块快捷搜索：客户、商品、供应商、单据号"
          @keydown.enter="quickLocate($event.target.value)"
        />
        <div v-if="menuStore.source === 'fallback'" class="menu-fallback-tip">菜单服务不可用，已使用本地菜单</div>
      </div>
      <template v-for="rootCode in topKeys" :key="rootCode">
        <div
          class="lvl1"
          :class="{ on: activeRoot === rootCode }"
          @click="onRootClick(menuStore.tree.find(r => r.code === rootCode))"
        >
          <span class="dot"></span>{{ menuStore.tree.find(r => r.code === rootCode)?.name }}
        </div>
        <div v-if="activeRoot === rootCode" class="submenu">
          <template v-for="node in menuStore.tree.find(r => r.code === rootCode)?.children || []" :key="node.code">
            <!-- 二级目录（如 财务管理 > 总账）：点击展开三级页面 -->
            <template v-if="node.menuType === 'DIR' && (node.children || []).length">
              <div class="lvl2 lvl2-dir" :class="{ on: expandedDirs.has(node.code) }" @click.stop="toggleDir(node.code)">
                <span>{{ node.name }}</span>
                <span class="dir-arrow">{{ expandedDirs.has(node.code) ? '▾' : '▸' }}</span>
              </div>
              <div v-if="expandedDirs.has(node.code)" class="lvl3-wrap">
                <div
                  v-for="leaf in node.children"
                  :key="leaf.code"
                  v-show="leafVisible(leaf)"
                  class="lvl3"
                  :class="{ on: currentMenuCode === leaf.code }"
                  @click.stop="navigate(leaf.code)"
                >
                  {{ leaf.name }}
                </div>
              </div>
            </template>
            <!-- 二级页面（降级树 adminOnly 项仅超管可见，MENU-001；服务端树本身已裁剪） -->
            <div
              v-else-if="leafVisible(node)"
              class="lvl2"
              :class="{ on: currentMenuCode === node.code }"
              @click.stop="navigate(node.code)"
            >
              {{ node.name }}
            </div>
          </template>
        </div>
      </template>
    </aside>

    <!-- Main -->
    <main class="main">
      <div class="content">
        <router-view />
      </div>
    </main>

    <!-- Toast -->
    <div
      v-if="toastText"
      style="position:fixed;right:18px;bottom:18px;background:#12385f;color:#fff;border-radius:10px;padding:12px 16px;z-index:99"
    >
      {{ toastText }}
    </div>

    <!-- 全局 BillDrawer：状态跨模块保留，但只在对应模块页面渲染 -->
    <BillDrawer
      :visible="billDrawerVisibleInCurrentModule"
      :module-code="app.billDrawer.moduleCode"
      :mode="app.billDrawer.mode"
      :edit-data="app.billDrawer.editData"
      @close="app.closeBillDrawer"
      @save="app.closeBillDrawer"
    />

    <!-- 全局采购入库抽屉：purchaseInbound / purchaseOrder 页面可见 -->
    <PurchaseInboundDrawer
      :visible="inboundDrawerVisibleInCurrentModule"
      :source-order="app.inboundDrawer.sourceOrder"
      @close="app.closeInboundDrawer"
      @save="onInboundSaved"
    />

    <!-- 全局销售出库抽屉：salesOutbound / salesOrder 页面可见 -->
    <SalesOutboundDrawer
      :visible="outboundDrawerVisibleInCurrentModule"
      :source-order="app.outboundDrawer.sourceOrder"
      :edit-data="app.outboundDrawer.editData"
      @close="app.closeOutboundDrawer"
      @save="onOutboundSaved"
    />

    <!-- 全局采购收货单抽屉：未审核可改价，已审核只读 -->
    <PurchaseReceiptDrawer
      :visible="receiptDrawerVisibleInCurrentModule"
      :receipt-id="app.receiptDrawer.receiptId"
      :readonly="app.receiptDrawer.readonly"
      @close="app.closeReceiptDrawer"
      @save="onReceiptSaved"
    />

    <!-- 全局采购发票抽屉：草稿可编辑，已审核只读（PRD-30 来票登记与勾稽核销） -->
    <PurchaseInvoiceDrawer
      :visible="invoiceDrawerVisibleInCurrentModule"
      :invoice-id="app.invoiceDrawer.invoiceId"
      :readonly="app.invoiceDrawer.readonly"
      @close="app.closeInvoiceDrawer"
      @save="onInvoiceSaved"
    />

    <!-- 全局采购退货申请抽屉：仅 purchaseReturnApply 页面（商品由抽屉内两个选择窗口添加） -->
    <PurchaseReturnApplyDrawer
      :visible="returnApplyDrawerVisibleInCurrentModule"
      :edit-data="app.returnApplyDrawer.editData"
      :readonly="app.returnApplyDrawer.readonly"
      @close="app.closeReturnApplyDrawer"
      @save="onReturnApplySaved"
    />

    <!-- 全局采购退货出库抽屉：purchaseReturnOutbound / purchaseReturnApply 页面可见 -->
    <PurchaseReturnOutboundDrawer
      :visible="returnOutboundDrawerVisibleInCurrentModule"
      :outbound-id="app.returnOutboundDrawer.outboundId"
      :readonly="app.returnOutboundDrawer.readonly"
      @close="app.closeReturnOutboundDrawer"
      @save="onReturnOutboundSaved"
    />

    <!-- 全局采购退货单抽屉：purchaseReturn / purchaseReturnOutbound 页面可见 -->
    <PurchaseReturnDrawer
      :visible="returnDrawerVisibleInCurrentModule"
      :return-id="app.returnDrawer.returnId"
      @close="app.closeReturnDrawer"
      @save="onReturnSaved"
    />

    <!-- 全局销售退货单抽屉：仅 salesReturn 页面 -->
    <SalesReturnDrawer
      :visible="salesReturnDrawerVisibleInCurrentModule"
      :edit-data="app.salesReturnDrawer.editData"
      :readonly="app.salesReturnDrawer.readonly"
      @close="app.closeSalesReturnDrawer"
      @save="onSalesReturnSaved"
    />

    <!-- 全局销售退货入库抽屉：仅 salesReturnInbound 页面 -->
    <SalesReturnInboundDrawer
      :visible="salesReturnInboundDrawerVisibleInCurrentModule"
      :inbound-id="app.salesReturnInboundDrawer.inboundId"
      @close="app.closeSalesReturnInboundDrawer"
      @save="onSalesReturnInboundSaved"
    />

    <!-- 全局拒收入库单抽屉：仅 rejectInbound 页面 -->
    <RejectInboundDrawer
      :visible="rejectInboundDrawerVisibleInCurrentModule"
      :inbound-id="app.rejectInboundDrawer.inboundId"
      :readonly="app.rejectInboundDrawer.readonly"
      @close="app.closeRejectInboundDrawer"
      @save="onRejectInboundSaved"
    />

    <!-- 全局发货单确认签收弹窗：仅 salesReceipt 页面 -->
    <ReceiptSignDialog
      :visible="receiptSignDialogVisibleInCurrentModule"
      :receipt-id="app.receiptSignDialog.receiptId"
      @close="app.closeReceiptSignDialog"
      @saved="onReceiptSigned"
    />

    <!-- 全局飞单抽屉：仅 flyOrder 页面 -->
    <FlyOrderDrawer
      :visible="flyOrderDrawerVisibleInCurrentModule"
      :mode="app.flyOrderDrawer.mode"
      :edit-data="app.flyOrderDrawer.editData"
      @close="app.closeFlyOrderDrawer"
      @saved="onFlyOrderSaved"
    />

    <GoodsPriceAdjustDrawer
      :visible="goodsPriceAdjustDrawerVisibleInCurrentModule"
      :order-id="app.goodsPriceAdjustDrawer.orderId"
      :mode="app.goodsPriceAdjustDrawer.mode"
      @close="app.closeGoodsPriceAdjustDrawer"
      @saved="onGoodsPriceAdjustSaved"
    />

    <!-- 全局盘点单抽屉：仅 stockTake 页面 -->
    <StockTakeDrawer
      :visible="stockTakeDrawerVisibleInCurrentModule"
      :mode="app.stockTakeDrawer.mode"
      :edit-data="app.stockTakeDrawer.editData"
      @close="app.closeStockTakeDrawer"
      @save="app.refreshSignal++; app.closeStockTakeDrawer(); app.showToast('保存成功')"
    />
    <!-- 全局报损单抽屉：仅 damage 页面 -->
    <DamageDrawer
      :visible="damageDrawerVisibleInCurrentModule"
      :edit-data="app.damageDrawer.editData"
      :readonly="app.damageDrawer.readonly"
      @close="app.closeDamageDrawer"
      @save="app.refreshSignal++; app.closeDamageDrawer(); app.showToast('保存成功')"
    />
    <!-- 全局其他入库单抽屉：仅 otherInbound 页面 -->
    <OtherInboundDrawer
      :visible="otherInboundDrawerVisibleInCurrentModule"
      :edit-data="app.otherInboundDrawer.editData"
      :readonly="app.otherInboundDrawer.readonly"
      @close="app.closeOtherInboundDrawer"
      @save="app.refreshSignal++; app.closeOtherInboundDrawer(); app.showToast('保存成功')"
    />
    <!-- 全局其他出库单抽屉：仅 otherOutbound 页面 -->
    <OtherOutboundDrawer
      :visible="otherOutboundDrawerVisibleInCurrentModule"
      :edit-data="app.otherOutboundDrawer.editData"
      :readonly="app.otherOutboundDrawer.readonly"
      @close="app.closeOtherOutboundDrawer"
      @save="app.refreshSignal++; app.closeOtherOutboundDrawer(); app.showToast('保存成功')"
    />

    <!-- PRD-28 个人中心 / 修改密码（头像下拉入口） -->
    <ProfileDialog
      :visible="profileVisible"
      @close="profileVisible = false"
      @password-changed="onProfilePwdChanged"
    />
    <ChangePasswordDialog
      :visible="pwdDialogVisible"
      @close="pwdDialogVisible = false"
      @saved="onPwdSaved"
    />
    <!-- 首登/重置后强制改密：不可关闭，改密成功后随 mustChangePwd 清除自动消失 -->
    <ChangePasswordDialog
      :visible="forcePwdVisible"
      :forced="true"
      @saved="onPwdSaved"
    />
  </div>
</template>

<style scoped>
/* PRD-28 卡片8 三级菜单（财务管理 > 总账 > 页面）与降级提示 */
.menu-fallback-tip { font-size: 11px; color: #b8860b; padding: 4px 10px 0; }
.lvl2-dir { justify-content: space-between; font-weight: 700; }
.dir-arrow { font-size: 11px; color: #7c93ad; }
.lvl3-wrap { padding: 2px 0 2px 10px; }
.lvl3 {
  height: 27px; border-radius: 7px; display: flex; align-items: center;
  padding: 0 12px; color: #4a6480; font-size: 12.5px; cursor: pointer; margin: 2px 0;
}
.lvl3.on { background: #fff; border: 1px solid #93c5fd; color: var(--primary); font-weight: 700; }

/* PRD-28 头像账户下拉 */
.user-menu-wrap { position: relative; z-index: 60; }
.user .caret { font-size: 10px; color: #909ba7; margin-left: 2px; }
.user.on { background: #eef3fa; }
.user-dropdown {
  position: absolute; right: 0; top: calc(100% + 6px);
  background: #fff; border: 1px solid var(--line); border-radius: 8px;
  box-shadow: 0 10px 30px rgba(18, 56, 95, 0.15);
  min-width: 132px; padding: 4px; z-index: 61;
}
.dropdown-item {
  padding: 8px 14px; font-size: 13px; color: #303133;
  border-radius: 6px; cursor: pointer; white-space: nowrap;
}
.dropdown-item:hover { background: #f0f5fb; color: var(--primary); }
.dropdown-item.danger:hover { background: #fef0f0; color: #d93025; }
.dropdown-mask { position: fixed; inset: 0; z-index: 55; }

/* 顶栏内的 Tab 条 */
.tab-bar-top {
  display: flex;
  gap: 4px;
  overflow-x: auto;
  align-items: center;
  flex: 1;
  min-width: 0;
  margin: 0 12px;
}
.tab-bar-top::-webkit-scrollbar { height: 4px; }
.tab-bar-top .tab {
  padding: 4px 10px;
  border-radius: 6px;
  background: #f0f4fa;
  color: #5d7896;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid transparent;
}
.tab-bar-top .tab.active {
  background: #fff;
  color: #12385f;
  font-weight: 600;
  border-color: #cfd8e5;
}
.tab-bar-top .tab-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  font-size: 12px;
  line-height: 1;
  color: #909ba7;
}
.tab-bar-top .tab-close:hover {
  background: #dce3ec;
  color: #303133;
}

/* 主内容区之前的旧 Tab 样式保留但已不使用 */
.tab-bar {
  display: flex;
  gap: 4px;
  padding: 6px 12px;
  background: #f6f9fc;
  border-bottom: 1px solid #dce3ec;
  overflow-x: auto;
}
.tab {
  padding: 4px 12px;
  border-radius: 6px 6px 0 0;
  background: #e8eef4;
  color: #5d7896;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 6px;
}
.tab.active {
  background: #fff;
  color: #12385f;
  font-weight: 600;
}
.tab-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  font-size: 12px;
  line-height: 1;
}
.tab-close:hover {
  background: #dce3ec;
}
</style>
