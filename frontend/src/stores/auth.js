import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { get, post } from '../api/client.js'
import { usePermStore } from './perm.js'

const TOKEN_KEY = 'erp-token'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  // user 只保存在内存：刷新页面后 token 仍在、user 为 null，需要 fetchProfile() 补拉
  const user = ref(null)
  // /auth/profile 进行中的 Promise，路由守卫与 AppShell 并发共用一次请求
  let profilePromise = null

  const isLoggedIn = computed(() => !!token.value)
  const isSuperAdmin = computed(() => {
    const codes = user.value?.roleCodes || []
    // ADMIN 为 V102 前老令牌角色码，后端已归一，前端一并视为超管
    return codes.includes('SYS_ADMIN') || codes.includes('ADMIN')
  })
  const mustChangePwd = computed(() => !!user.value?.mustChangePwd)

  function setToken(t) {
    token.value = t
    localStorage.setItem(TOKEN_KEY, t)
  }

  function clearToken() {
    token.value = ''
    user.value = null
    profilePromise = null
    localStorage.removeItem(TOKEN_KEY)
    // 一并清空功能点/字段权限，避免登出后残留或下个账号沿用
    usePermStore().reset()
  }

  /** 直接替换 user（登录时用登录返回值，改密后用于清 mustChangePwd 标记） */
  function setUser(u) {
    user.value = u
  }

  /**
   * 刷新后补拉当前用户资料（PRD-28 §10.5）。
   * /auth/profile 不直接下发 roleCodes，这里由 roles 归一补齐，供超管判断使用。
   * 并发调用复用同一个 Promise。
   */
  async function fetchProfile() {
    if (!token.value) return null
    if (!profilePromise) {
      profilePromise = get('/auth/profile').then((p) => {
        if (p && !Array.isArray(p.roleCodes) && Array.isArray(p.roles)) {
          p.roleCodes = p.roles.map((r) => r.roleCode)
        }
        user.value = p
        return p
      }).finally(() => { profilePromise = null })
    }
    return profilePromise
  }

  /** 自助改密成功后清强制标记，避免再次弹窗（真实值下次登录/刷新由后端给） */
  function markPasswordChanged() {
    if (user.value) user.value = { ...user.value, mustChangePwd: false }
  }

  async function login(username, password) {
    // client.js 已处理 code!=='0' 抛错、baseURL、超时、Bearer 头（此处无 token 时不注入）
    const data = await post('/auth/login', { username, password })
    if (data?.token) {
      setToken(data.token)
      user.value = data.user
      // 新账号登录：丢弃旧权限缓存，由路由守卫按新用户重拉
      usePermStore().reset()
      return true
    }
    throw new Error('登录失败')
  }

  function logout() {
    clearToken()
  }

  return {
    token, user, isLoggedIn, isSuperAdmin, mustChangePwd,
    login, logout, setToken, clearToken, setUser, fetchProfile, markPasswordChanged,
  }
})
