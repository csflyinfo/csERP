<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  // fields 可以是字符串（简单文本框）或对象 { label, type?, options?, key?, keyFrom?, keyTo? }
  //   type: 'select' | 'dateRange' | 'date' | 'text'（默认）
  //   key: 覆盖回传字段名（默认取 label 去空格/冒号后的结果）
  fields: { type: Array, default: () => ['关键字', '状态'] },
  // 初始默认值 { fieldKey: value }；日期段用 keyFrom/keyTo 直接对应
  defaults: { type: Object, default: () => ({}) },
  // 首屏显示的字段上限（超出可以塞进"展开更多"）
  maxVisible: { type: Number, default: 4 },
})

const emit = defineEmits(['query', 'reset', 'more'])
const values = ref({ ...props.defaults })

function labelOf(f) { return typeof f === 'string' ? f : (f?.label || '') }
function fieldKey(f) {
  if (typeof f === 'object' && f?.key) return f.key
  return labelOf(f).replace(/[\s/：:]+/g, '_')
}
function optionsOf(f) {
  if (typeof f === 'object' && Array.isArray(f.options)) return f.options
  const label = labelOf(f)
  if (label.includes('状态')) return ['正常', '停用']
  if (label.includes('是否启用') || label === '启用状态') return [{ value: 'true', label: '启用' }, { value: 'false', label: '停用' }]
  if (label.startsWith('是否')) return [{ value: 'true', label: '是' }, { value: 'false', label: '否' }]
  return []
}
function typeOf(f) {
  if (typeof f !== 'object') return isSelectField(f) ? 'select' : 'text'
  if (f.type) return f.type
  return isSelectField(f) ? 'select' : 'text'
}
function isSelectField(f) {
  const label = labelOf(f)
  if (typeof f === 'object' && Array.isArray(f.options)) return true
  return label.includes('状态') || label.startsWith('是否') || label === '启用状态'
}

function buildFilters() {
  return Object.fromEntries(Object.entries(values.value).filter(([, value]) => value !== undefined && value !== null && value !== ''))
}

function query() { emit('query', buildFilters()) }

function reset() {
  values.value = { ...props.defaults }
  emit('reset', buildFilters())
}

// 全局约定：查询条件只在点击「查询」按钮时生效，不自动触发（CLAUDE.md）
watch(() => props.fields, () => reset())
</script>

<template>
  <div class="query-inline">
    <template v-for="f in fields.slice(0, maxVisible)" :key="labelOf(f)">
      <!-- 日期段：两个 date input -->
      <div v-if="typeOf(f) === 'dateRange'" class="fi date-range">
        <label>{{ labelOf(f) }}</label>
        <div class="date-range-inputs">
          <input type="date" v-model="values[f.keyFrom || (fieldKey(f) + '_from')]" />
          <span class="dash">~</span>
          <input type="date" v-model="values[f.keyTo || (fieldKey(f) + '_to')]" />
        </div>
      </div>
      <div v-else class="fi">
        <label>{{ labelOf(f) }}</label>
        <select v-if="typeOf(f) === 'select'" v-model="values[fieldKey(f)]" @keydown.enter="query">
          <option value="">全部</option>
          <option v-for="opt in optionsOf(f)" :key="typeof opt === 'object' ? opt.value : opt" :value="typeof opt === 'object' ? opt.value : opt">
            {{ typeof opt === 'object' ? opt.label : opt }}
          </option>
        </select>
        <input v-else-if="typeOf(f) === 'date'" type="date" v-model="values[fieldKey(f)]" @keydown.enter="query" />
        <input v-else v-model="values[fieldKey(f)]" :placeholder="labelOf(f)" @keydown.enter="query" />
      </div>
    </template>
    <button class="btn primary" @click="query">查询</button>
    <button class="btn" @click="reset">重置</button>
    <slot name="after-reset" />
    <button v-if="fields.length > maxVisible" class="btn" @click="emit('more', fields.slice(maxVisible))">展开更多</button>
  </div>
</template>

<style scoped>
.date-range .date-range-inputs { display: flex; align-items: center; gap: 4px; }
.date-range input[type=date] { min-width: 130px; }
.date-range .dash { color: #909399; padding: 0 2px; }
</style>
