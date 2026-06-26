<script setup>
import { onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth.js'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// 监听认证状态：token 被清除时自动跳转登录页
watch(() => auth.token, (val) => {
  if (!val && !route.meta?.public) {
    router.push('/login')
  }
})

// 监听后端 401 认证过期事件
function onAuthExpired() {
  auth.clearToken()
  router.push('/login')
}

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
