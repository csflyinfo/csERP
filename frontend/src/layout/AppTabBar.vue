<script setup>
defineProps({
  tabs: { type: Array, required: true },
  current: { type: String, required: true },
})

const emit = defineEmits(['route', 'close-tab'])
</script>

<template>
  <div class="tabbar">
    <div v-for="tab in tabs" :key="tab.code" class="top-tab" :class="{ active: current === tab.code }" @click="emit('route', tab.code)">
      <span>{{ tab.name }}</span>
      <button v-if="tab.code !== 'dashboard'" class="tab-close" @click.stop="emit('close-tab', tab.code)">×</button>
    </div>
  </div>
</template>
