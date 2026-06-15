<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  fields: { type: Array, default: () => ['关键字', '状态'] },
})

const emit = defineEmits(['query', 'reset', 'more'])
const values = ref({})

function fieldKey(field) {
  return field.replace(/[\s/：:]+/g, '_')
}

function buildFilters() {
  return Object.fromEntries(Object.entries(values.value).filter(([, value]) => value !== undefined && value !== null && value !== ''))
}

function query() {
  emit('query', buildFilters())
}

function reset() {
  values.value = {}
  emit('reset', {})
}

watch(() => props.fields, reset)
</script>

<template>
  <div class="query-inline">
    <div v-for="field in fields.slice(0, 4)" :key="field" class="fi">
      <label>{{ field }}</label>
      <select v-if="field.includes('状态') || field.includes('方式') || field.includes('类型')" v-model="values[fieldKey(field)]" @keydown.enter="query">
        <option value="">全部{{ field }}</option>
        <option>正常</option>
        <option>待审核</option>
        <option>已审核</option>
        <option>已核销</option>
      </select>
      <input v-else v-model="values[fieldKey(field)]" :placeholder="field" @keydown.enter="query" />
    </div>
    <button class="btn primary" @click="query">查询</button>
    <button class="btn" @click="reset">重置</button>
    <button v-if="fields.length > 4" class="btn" @click="emit('more', fields.slice(4))">展开更多</button>
  </div>
</template>
