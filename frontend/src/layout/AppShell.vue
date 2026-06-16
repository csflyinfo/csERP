<script setup>
import AppHeader from './AppHeader.vue'
import AppSidebar from './AppSidebar.vue'
import AppTabBar from './AppTabBar.vue'

defineProps({
  current: { type: String, required: true },
  currentName: { type: String, required: true },
  currentDesc: { type: String, required: true },
  currentUser: { type: Object, default: null },
  menus: { type: Object, required: true },
  activeTop: { type: String, required: true },
  menuLoading: { type: Boolean, default: false },
  tabs: { type: Array, required: true },
  toastText: { type: String, default: '' },
})

const emit = defineEmits(['toast', 'route', 'create', 'logout', 'set-active-top', 'quick-locate', 'close-tab'])
</script>

<template>
  <div class="shell">
    <AppHeader :current="current" :current-name="currentName" :current-desc="currentDesc" :current-user="currentUser" @toast="emit('toast', $event)" @route="emit('route', $event)" @create="emit('create')" @logout="emit('logout')" />
    <AppSidebar :menus="menus" :active-top="activeTop" :current="current" :menu-loading="menuLoading" @set-active-top="emit('set-active-top', $event)" @route="emit('route', $event)" @quick-locate="emit('quick-locate', $event)" />
    <main class="main">
      <AppTabBar :tabs="tabs" :current="current" @route="emit('route', $event)" @close-tab="emit('close-tab', $event)" />
      <div class="content"><slot /></div>
    </main>
    <div v-if="toastText" style="position:fixed;right:18px;bottom:18px;background:#12385f;color:#fff;border-radius:10px;padding:12px 16px;z-index:99">{{ toastText }}</div>
  </div>
</template>
