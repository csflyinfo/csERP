<script setup>
defineProps({
  menus: { type: Object, required: true },
  activeTop: { type: String, required: true },
  current: { type: String, required: true },
  menuLoading: { type: Boolean, default: false },
})

const emit = defineEmits(['set-active-top', 'route', 'quick-locate'])
</script>

<template>
  <aside class="side">
    <div class="side-search">
      <input class="quick-search" placeholder="模块快捷搜索：客户、商品、供应商、单据号" @keydown.enter="emit('quick-locate', $event.target.value)" />
      <div v-if="menuLoading" class="menu-loading">正在加载权限菜单...</div>
    </div>
    <template v-for="(items, top) in menus" :key="top">
      <div class="lvl1" :class="{ on: activeTop === top }" @click="emit('set-active-top', top); emit('route', items[0].code)">
        <span class="dot"></span>{{ top }}
      </div>
      <div v-if="activeTop === top" class="submenu">
        <div v-for="item in items" :key="item.code" class="lvl2" :class="{ on: current === item.code }" @click.stop="emit('route', item.code)">
          {{ item.name }}
        </div>
      </div>
    </template>
  </aside>
</template>
