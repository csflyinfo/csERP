<script setup>
import { computed, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useAppStore } from '../stores/app.js'
import { fallbackMenus } from '../fallback-menus.js'
import { moduleConfigs } from '../module-config.js'
import { post } from '../api/client.js'
import BillDrawer from '../components/BillDrawer.vue'
import PurchaseInboundDrawer from '../components/PurchaseInboundDrawer.vue'
import SalesOutboundDrawer from '../components/SalesOutboundDrawer.vue'
import PurchaseReturnApplyDrawer from '../components/PurchaseReturnApplyDrawer.vue'
import PurchaseReceiptDrawer from '../components/PurchaseReceiptDrawer.vue'
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

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()

const menus = fallbackMenus
const menuCollapsed = ref(false)
const toastText = ref('')
const todoCount = ref(0)
const notifyCount = ref(0)

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
  return () => clearInterval(timer)
})

// module code → 路由 path（camelCase → kebab-case）
function codeToPath(code) {
  return '/' + code.replace(/([a-z0-9]|(?=[A-Z]))([A-Z])/g, '$1-$2').toLowerCase()
}

// 预计算可用路由集合，用于判断菜单是否可导航
const availableRoutes = new Set(router.getRoutes().map(r => r.path))

// module code → 菜单分类
const moduleToCategory = {}
for (const [cat, items] of Object.entries(menus)) {
  for (const item of items) {
    moduleToCategory[item.code] = cat
  }
}

const currentModule = computed(() => route.meta?.module || '')
const currentName = computed(() => route.meta?.title || '经营概览')

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
const activeTop = computed(() => moduleToCategory[currentModule.value] || '首页')

// 路由变化时自动添加 tab
watch(() => route.path, () => {
  if (route.meta?.title) {
    app.addTab(route)
  }
}, { immediate: true })

function navigate(code) {
  const path = codeToPath(code)
  if (!availableRoutes.has(path)) {
    toast('该功能正在开发中')
    return
  }
  router.push(path)
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
  for (const [top, items] of Object.entries(menus)) {
    const found = items.find(i => i.name.includes(keyword) || i.code.includes(keyword))
    if (found) {
      navigate(found.code)
      return
    }
  }
  toast('未找到匹配菜单')
}

const topKeys = computed(() => Object.keys(menus))
</script>

<template>
  <div class="shell">
    <!-- Header -->
    <header class="top">
      <div class="brand">
        <div class="mark"></div>商贸云 ERP V1.0
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
      <button class="topbtn" @click="navigate('exportCenter')">导出中心</button>
      <div class="user" @click="doLogout" style="cursor:pointer" title="点击退出登录">
        <div class="avatar">{{ currentUser?.displayName?.slice(0, 1) || '管' }}</div>
        <span>{{ currentUser?.displayName || '管理员' }}</span>
      </div>
    </header>

    <!-- Sidebar -->
    <aside v-show="!menuCollapsed" class="side">
      <div class="side-search">
        <input
          class="quick-search"
          placeholder="模块快捷搜索：客户、商品、供应商、单据号"
          @keydown.enter="quickLocate($event.target.value)"
        />
      </div>
      <template v-for="items in topKeys" :key="items">
        <div
          class="lvl1"
          :class="{ on: activeTop === items }"
          @click="navigate(menus[items][0].code)"
        >
          <span class="dot"></span>{{ items }}
        </div>
        <div v-if="activeTop === items" class="submenu">
          <div
            v-for="item in menus[items]"
            :key="item.code"
            class="lvl2"
            :class="{ on: currentModule === item.code }"
            @click.stop="navigate(item.code)"
          >
            {{ item.name }}
          </div>
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
  </div>
</template>

<style scoped>
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
