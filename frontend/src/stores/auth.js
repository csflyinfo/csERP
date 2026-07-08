import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { post } from '../api/client.js'

const TOKEN_KEY = 'erp-token'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref(null)

  const isLoggedIn = computed(() => !!token.value)

  function setToken(t) {
    token.value = t
    localStorage.setItem(TOKEN_KEY, t)
  }

  function clearToken() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  async function login(username, password) {
    // client.js 已处理 code!=='0' 抛错、baseURL、超时、Bearer 头（此处无 token 时不注入）
    const data = await post('/auth/login', { username, password })
    if (data?.token) {
      setToken(data.token)
      user.value = data.user
      return true
    }
    throw new Error('登录失败')
  }

  function logout() {
    clearToken()
  }

  return { token, user, isLoggedIn, login, logout, setToken, clearToken }
})
