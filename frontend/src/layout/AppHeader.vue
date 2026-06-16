<script setup>
defineProps({
  current: { type: String, required: true },
  currentName: { type: String, required: true },
  currentDesc: { type: String, required: true },
  currentUser: { type: Object, default: null },
})

const emit = defineEmits(['toast', 'route', 'create', 'logout'])
</script>

<template>
  <header class="top">
    <div class="brand"><div class="mark"></div>商贸云 ERP V1.0</div>
    <button class="hamb" @click="emit('toast', '菜单折叠/展开')">☰</button>
    <div class="nav-module">
      <div class="title">{{ currentName }}</div>
      <div class="desc">{{ currentDesc }}</div>
    </div>
    <div class="spacer"></div>
    <div class="actions">
      <button class="btn" @click="emit('route', current === 'customerPriceEdit' ? 'customerPrice' : current)">返回列表</button>
      <button class="btn primary" @click="emit('create')">新建</button>
    </div>
    <button class="topbtn" @click="emit('route', 'exportCenter')">导出中心</button>
    <button class="topbtn" @click="emit('route', 'log')">消息</button>
    <button class="topbtn" @click="emit('logout')">退出</button>
    <div class="user"><div class="avatar">{{ currentUser?.displayName?.slice(0, 1) || '管' }}</div><span>{{ currentUser?.displayName || '管理员' }}</span></div>
  </header>
</template>
