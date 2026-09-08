<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'

const router = useRouter()
const auth = useAuthStore()

// 验证环境可通过 VITE_APP_TITLE 注入标识（如总账开发版），默认标题不变
const appTitle = import.meta.env.VITE_APP_TITLE || '商贸云 ERP V1.0'
const envBadge = import.meta.env.VITE_APP_BADGE || ''
if (import.meta.env.VITE_APP_TITLE) {
  document.title = import.meta.env.VITE_APP_TITLE
}

const username = ref('admin')
const password = ref('admin123')
const loginError = ref('')
const loginLoading = ref(false)

async function doLogin() {
  loginLoading.value = true
  loginError.value = ''
  try {
    await auth.login(username.value, password.value)
    router.push('/')
  } catch (error) {
    loginError.value = error.message || '账号或密码错误'
  } finally {
    loginLoading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand login-brand">
        <div class="mark"></div>{{ appTitle }}
        <span v-if="envBadge" class="env-badge">{{ envBadge }}</span>
      </div>
      <p style="color:var(--muted);margin:0 0 18px">真实应用开发版</p>
      <div class="field" style="margin-bottom:12px">
        <label>账号</label>
        <input v-model="username" placeholder="请输入账号" @keydown.enter="doLogin" />
      </div>
      <div class="field" style="margin-bottom:12px">
        <label>密码</label>
        <input v-model="password" type="password" placeholder="请输入密码" @keydown.enter="doLogin" />
      </div>
      <div v-if="loginError" class="login-error">{{ loginError }}</div>
      <button class="btn primary login-submit" :disabled="loginLoading" @click="doLogin">
        {{ loginLoading ? '登录中...' : '登录系统' }}
      </button>
    </div>
  </div>
</template>
