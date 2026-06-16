import { ref, watch } from 'vue'
import { get } from '../api/client.js'
import { fallbackMenus } from '../fallback-menus.js'

const NAV_STATE_KEY = 'erp-nav-state'

export function useNavigation(toast) {
  const savedNavState = loadNavState()
  const menus = ref(fallbackMenus)
  const activeTop = ref(savedNavState.activeTop || '首页')
  const current = ref(savedNavState.current || 'dashboard')
  const openTabs = ref(savedNavState.openTabs?.length ? savedNavState.openTabs : ['dashboard'])
  const menuLoading = ref(false)

  function route(code) {
    current.value = code
    if (!openTabs.value.includes(code)) openTabs.value.push(code)
    Object.entries(menus.value).forEach(([top, items]) => {
      if (items.some(item => item.code === code)) activeTop.value = top
    })
    saveNavState()
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
    saveNavState()
  }

  function quickLocate(value) {
    const hit = Object.values(menus.value).flat().find(item => item.name.includes(value))
    if (hit) route(hit.code)
    else toast('未找到匹配模块')
  }

  function normalizeMenus(tree = []) {
    const result = {}
    tree.forEach(item => {
      const children = item.children?.length ? item.children : [item]
      result[item.name] = children.map(child => ({ code: child.code, name: child.name }))
    })
    return Object.keys(result).length ? result : fallbackMenus
  }

  async function loadUserMenus(roleCode = 'ADMIN') {
    menuLoading.value = true
    try {
      menus.value = normalizeMenus(await get(`/system/menu/user-tree?roleCode=${encodeURIComponent(roleCode || 'ADMIN')}`))
      if (!menus.value[activeTop.value]) activeTop.value = Object.keys(menus.value)[0] || '首页'
    } catch (error) {
      menus.value = fallbackMenus
      toast('菜单接口加载失败，已使用本地菜单')
    } finally {
      menuLoading.value = false
    }
  }

  function resetNavigation() {
    localStorage.removeItem(NAV_STATE_KEY)
    menus.value = fallbackMenus
    current.value = 'dashboard'
    activeTop.value = '首页'
    openTabs.value = ['dashboard']
  }

  function saveNavState() {
    localStorage.setItem(NAV_STATE_KEY, JSON.stringify({ current: current.value, activeTop: activeTop.value, openTabs: openTabs.value }))
  }

  watch([current, activeTop, openTabs], saveNavState, { deep: true })

  return { menus, activeTop, current, openTabs, menuLoading, route, closeTab, quickLocate, loadUserMenus, resetNavigation }
}

function loadNavState() {
  try {
    return JSON.parse(localStorage.getItem(NAV_STATE_KEY) || '{}')
  } catch (error) {
    return {}
  }
}
