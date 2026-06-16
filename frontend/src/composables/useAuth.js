import { ref } from 'vue'
import { get, post } from '../api/client.js'

export function useAuth() {
  const loginForm = ref({ username: 'admin', password: 'admin123' })
  const loginError = ref('')
  const loginLoading = ref(false)
  const currentUser = ref(null)
  const authToken = ref(localStorage.getItem('erp-demo-token') || '')

  async function loadCurrentUser() {
    try {
      currentUser.value = await get('/auth/current-user')
    } catch (error) {
      currentUser.value = { displayName: '系统管理员', username: 'admin', roles: ['ADMIN'] }
    }
  }

  async function login(afterLogin, toast) {
    loginLoading.value = true
    loginError.value = ''
    try {
      const result = await post('/auth/login', loginForm.value)
      authToken.value = result.token
      localStorage.setItem('erp-demo-token', result.token)
      await afterLogin()
      toast('登录成功')
    } catch (error) {
      authToken.value = ''
      localStorage.removeItem('erp-demo-token')
      loginError.value = error.message || '登录失败'
    } finally {
      loginLoading.value = false
    }
  }

  async function logout(resetNavigation) {
    try { await post('/auth/logout') } catch (error) {}
    authToken.value = ''
    currentUser.value = null
    localStorage.removeItem('erp-demo-token')
    resetNavigation()
  }

  return { loginForm, loginError, loginLoading, currentUser, authToken, loadCurrentUser, login, logout }
}
