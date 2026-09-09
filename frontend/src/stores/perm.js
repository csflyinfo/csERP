import { defineStore } from 'pinia'
import { ref } from 'vue'
import { get } from '../api/client.js'

/**
 * 当前登录用户的功能点 / 敏感字段权限集（PRD-28，卡片6）。
 *
 * 数据来源 GET /system/perm/mine：{ superAdmin, roleCodes, funcs:[funcCode], fields:[fieldCode] }。
 * 超管后端返回空集 + superAdmin=true，hasFunc/canViewField 一律短路放行。
 * 路由守卫在进入业务页面前调 {@link ensure}，因此 v-permission 挂载时权限集已就绪。
 */
export const usePermStore = defineStore('perm', () => {
  const loaded = ref(false)
  const superAdmin = ref(false)
  const roleCodes = ref([])
  const funcs = ref(new Set())
  const fields = ref(new Set())
  let promise = null

  function applyMine(d) {
    superAdmin.value = !!(d && d.superAdmin)
    roleCodes.value = Array.isArray(d && d.roleCodes) ? d.roleCodes : []
    funcs.value = new Set(Array.isArray(d && d.funcs) ? d.funcs : [])
    fields.value = new Set(Array.isArray(d && d.fields) ? d.fields : [])
    loaded.value = true
  }

  /** 退出登录 / 切换账号后清空，避免沿用上一个人的权限。 */
  function reset() {
    loaded.value = false
    superAdmin.value = false
    roleCodes.value = []
    funcs.value = new Set()
    fields.value = new Set()
    promise = null
  }

  /** 拉取权限集；并发复用同一 Promise。force=true 用于角色配置变更后重拉。 */
  function ensure(force = false) {
    if (!force && loaded) return Promise.resolve()
    if (!force && promise) return promise
    promise = get('/system/perm/mine')
      .then((d) => applyMine(d))
      .catch((e) => {
        // 拉取失败 fail-closed：按无权限处理，避免越权显示；不影响登录页等公开路由
        applyMine(null)
        throw e
      })
      .finally(() => { promise = null })
    return promise
  }

  /** 是否拥有功能点；空 code 视为不校验。 */
  function hasFunc(code) {
    if (!code) return true
    return superAdmin.value || funcs.value.has(code)
  }

  /** 多码任一命中即放行；空数组视为不校验。 */
  function hasAnyFunc(codes) {
    if (!codes || codes.length === 0) return true
    if (superAdmin.value) return true
    return codes.some((c) => funcs.value.has(c))
  }

  /** 是否可见某敏感字段；空 code 视为不校验。 */
  function canViewField(code) {
    if (!code) return true
    return superAdmin.value || fields.value.has(code)
  }

  return {
    loaded, superAdmin, roleCodes, funcs, fields,
    ensure, reset, hasFunc, hasAnyFunc, canViewField,
  }
})
