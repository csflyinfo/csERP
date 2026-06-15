<script setup>
defineProps({
  fields: { type: Array, default: () => ['关键字', '状态'] },
})

const emit = defineEmits(['query', 'reset', 'more'])
</script>

<template>
  <div class="query-inline">
    <div v-for="field in fields.slice(0, 4)" :key="field" class="fi">
      <label>{{ field }}</label>
      <select v-if="field.includes('状态') || field.includes('方式') || field.includes('类型')"><option>全部{{ field }}</option></select>
      <input v-else :placeholder="field" />
    </div>
    <button class="btn primary" @click="emit('query')">查询</button>
    <button class="btn" @click="emit('reset')">重置</button>
    <button v-if="fields.length > 4" class="btn" @click="emit('more', fields.slice(4))">展开更多</button>
  </div>
</template>
