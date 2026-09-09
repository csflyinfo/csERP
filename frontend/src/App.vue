<script setup>
import { onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth.js'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// 监听认证状态：token 被清除时自动跳转登录页（已在登录页则不重复跳转）
watch(() => auth.token, (val) => {
  if (!val && !route.meta?.public && route.path !== '/login') {
    router.push('/login')
  }
})

// 监听后端 401 认证过期事件（PRD-28 卡片8：与 403 分流，403 不清会话、由调用方提示）
function onAuthExpired() {
  auth.clearToken()
  if (route.path !== '/login') router.push('/login')
}

// 注：403（erp-perm-denied 事件）不清会话、不全局 toast——ApiError 已抛给触发操作
// 的页面，由其 catch 提示「无操作权限：xxx」；后台静默请求的 403 本就不应打扰用户。

onMounted(() => {
  window.addEventListener('erp-auth-expired', onAuthExpired)
})

onUnmounted(() => {
  window.removeEventListener('erp-auth-expired', onAuthExpired)
})
</script>

<template>
  <router-view />
</template>
