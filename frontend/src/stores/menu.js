import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { get } from '../api/client.js'
import { buildFallbackTree, collectCodes } from './menu-tree.js'

/**
 * 当前登录用户的授权菜单树（PRD-28 卡片8）。
 *
 * 数据来源 GET /system/menu/user-tree（不带 roleCode——后端只返回本人授权菜单，
 * 超管返回全量）。侧边栏三级渲染、路由守卫页面级裁剪都以此为准。
 * source：
 *  - 'server'  服务端菜单，守卫按授权编码 fail-closed 拦截未授权页面；
 *  - 'fallback' 接口失败降级本地菜单，仅保侧边栏可用，不做前端页面拦截（后端仍 403）；
 *  - 'empty' 尚未加载。
 * 与 perm store 一致：登录/登出时 reset，并发 ensure 复用同一 Promise，可 force 重拉。
 */
export const useMenuStore = defineStore('menu', () => {
  const tree = ref([])
  const source = ref('empty')
  const loaded = ref(false)
  let promise = null

  const codes = computed(() => collectCodes(tree.value))

  function applyServer(nodes) {
    tree.value = Array.isArray(nodes) ? nodes : []
    source.value = 'server'
    loaded.value = true
  }

  function applyFallback() {
    tree.value = buildFallbackTree()
    source.value = 'fallback'
    loaded.value = true
  }

  /** 退出登录 / 切换账号后清空。 */
  function reset() {
    tree.value = []
    source.value = 'empty'
    loaded.value = false
    promise = null
  }

  /** 拉取用户菜单树；并发复用同一 Promise。force=true 用于授权变更后重拉。 */
  function ensure(force = false) {
    if (!force && loaded) return Promise.resolve()
    if (!force && promise) return promise
    promise = get('/system/menu/user-tree')
      .then((d) => applyServer(d))
      .catch((e) => {
        // 401 由 client.js 全局事件处理（跳登录），这里不降级误导
        if (String(e?.code) === '401') throw e
        applyFallback()
      })
      .finally(() => { promise = null })
    return promise
  }

  /** 是否拥有某菜单编码（服务端来源时供守卫/入口显隐使用）。无 code 视为不校验。 */
  function hasMenu(code) {
    if (!code) return true
    return source.value !== 'server' || codes.value.has(code)
  }

  return { tree, source, loaded, codes, ensure, reset, hasMenu, applyFallback }
})
