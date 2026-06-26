import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'

const TOKEN_KEY = 'erp-token'
const API_BASE = import.meta.env.VITE_API_BASE || '/api'

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
    const res = await axios.post(`${API_BASE}/auth/login`, { username, password })
    if (res.data?.code === '0' && res.data.data?.token) {
      setToken(res.data.data.token)
      user.value = res.data.data.user
      return true
    }
    throw new Error(res.data?.message || '登录失败')
  }

  function logout() {
    clearToken()
  }

  return { token, user, isLoggedIn, login, logout, setToken, clearToken }
})
