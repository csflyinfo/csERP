<script setup>
defineProps({
  loginForm: { type: Object, required: true },
  loginError: { type: String, default: '' },
  loginLoading: { type: Boolean, default: false },
})

const emit = defineEmits(['login'])
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand login-brand"><div class="mark"></div>商贸云 ERP V1.0</div>
      <p>开发演示账号：admin / admin123</p>
      <div class="field"><label>账号</label><input v-model="loginForm.username" placeholder="请输入账号" @keydown.enter="emit('login')" /></div>
      <div class="field"><label>密码</label><input v-model="loginForm.password" type="password" placeholder="请输入密码" @keydown.enter="emit('login')" /></div>
      <div v-if="loginError" class="login-error">{{ loginError }}</div>
      <button class="btn primary login-submit" :disabled="loginLoading" @click="emit('login')">{{ loginLoading ? '登录中...' : '登录系统' }}</button>
    </div>
  </div>
</template>
