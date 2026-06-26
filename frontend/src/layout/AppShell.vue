<script setup>
import { computed, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useAppStore } from '../stores/app.js'
import { fallbackMenus } from '../fallback-menus.js'
import { moduleConfigs } from '../module-config.js'
import { post } from '../api/client.js'

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
      <div class="nav-module">
        <div class="title">{{ currentName }}</div>
        <div class="desc">{{ currentDesc }}</div>
      </div>
      <div class="spacer"></div>
      <div class="actions">
        <button class="btn" @click="navigate('dashboard')">返回首页</button>
        <button class="btn primary" @click="showCreate">新建</button>
      </div>
      <button class="topbtn" @click="navigate('exportCenter')">导出中心</button>
      <button class="topbtn" @click="navigate('todo')" style="position:relative">
        待办<span v-if="todoCount > 0" style="position:absolute;top:-4px;right:-4px;background:#f5222d;color:#fff;border-radius:50%;padding:0 5px;font-size:11px">{{ todoCount }}</span>
      </button>
      <button class="topbtn" @click="navigate('notification')" style="position:relative">
        消息<span v-if="notifyCount > 0" style="position:absolute;top:-4px;right:-4px;background:#f5222d;color:#fff;border-radius:50%;padding:0 5px;font-size:11px">{{ notifyCount }}</span>
      </button>
      <button class="topbtn" @click="doLogout">退出</button>
      <div class="user">
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
          @click="activeTop = items; navigate(menus[items][0].code)"
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
      <div class="tab-bar" v-if="app.openTabs.length > 0">
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
  </div>
</template>

<style scoped>
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
