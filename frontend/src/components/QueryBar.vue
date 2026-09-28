<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'

const props = defineProps({
  // fields 可以是字符串（简单文本框）或对象 { label, type?, options?, key?, keyFrom?, keyTo? }
  //   type: 'select' | 'multiSelect' | 'dateRange' | 'date' | 'text'（默认）
  //   key: 覆盖回传字段名（默认取 label 去空格/冒号后的结果）
  fields: { type: Array, default: () => ['关键字', '状态'] },
  // 初始默认值 { fieldKey: value }；日期段用 keyFrom/keyTo 直接对应；多选值为数组
  defaults: { type: Object, default: () => ({}) },
  // 外部联查带入的一次性预填（如报表钻取到单据列表）：仅在字段集变化或预填内容变化时回显，
  // 用户手动点「重置」不会再带出（区别于常驻 defaults）
  prefill: { type: Object, default: () => ({}) },
  // 首屏显示的字段上限（超出可以塞进"展开更多"）
  maxVisible: { type: Number, default: 4 },
})

const emit = defineEmits(['query', 'reset', 'more'])
const values = ref({ ...props.defaults, ...props.prefill })

/** 当前展开的多选下拉字段 key（同时只开一个）。 */
const openKey = ref('')

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

function optValue(opt) { return typeof opt === 'object' ? opt.value : opt }
function optLabel(opt) { return typeof opt === 'object' ? opt.label : opt }

/** 多选当前值（始终返回数组，不写回）。 */
function selectedOf(f) {
  const v = values.value[fieldKey(f)]
  return Array.isArray(v) ? v : []
}
function toggleDropdown(f) {
  const k = fieldKey(f)
  openKey.value = openKey.value === k ? '' : k
}
function toggleOpt(f, opt) {
  const k = fieldKey(f)
  const cur = new Set(selectedOf(f))
  const v = optValue(opt)
  if (cur.has(v)) cur.delete(v); else cur.add(v)
  // 保留 options 原始顺序
  const ordered = optionsOf(f).map(optValue).filter(x => cur.has(x))
  values.value = { ...values.value, [k]: ordered }
}
function selectAll(f) {
  values.value = { ...values.value, [fieldKey(f)]: optionsOf(f).map(optValue) }
}
function clearAll(f) {
  values.value = { ...values.value, [fieldKey(f)]: [] }
}
/** 触发按钮文案：未选=全部；已选=中文标签逗号拼接（超长截断）。 */
function triggerText(f) {
  const sel = selectedOf(f)
  if (sel.length === 0) return '全部'
  const labelMap = new Map(optionsOf(f).map(o => [optValue(o), optLabel(o)]))
  const text = sel.map(v => labelMap.get(v) ?? v).join('、')
  return text.length > 12 ? `已选 ${sel.length} 项` : text
}

// 点击下拉外部关闭（mousedown 先于 click，面板内点击靠 closest 判断保留）
function onDocMouseDown(ev) {
  if (openKey.value && !ev.target.closest('.ms-fi')) openKey.value = ''
}

function buildFilters() {
  return Object.fromEntries(Object.entries(values.value).filter(([, value]) => {
    if (Array.isArray(value)) return value.length > 0
    return value !== undefined && value !== null && value !== ''
  }))
}

function query() { openKey.value = ''; emit('query', buildFilters()) }

function reset() {
  openKey.value = ''
  values.value = { ...props.defaults }
  emit('reset', buildFilters())
}

// 字段集（模块配置）变化时重建：常驻默认值 + 联查预填（预填随新模块上下文重新回显；
// 用户手动点「重置」走 reset()，只保留 defaults）
function syncOnFieldsChange() {
  openKey.value = ''
  values.value = { ...props.defaults, ...(props.prefill || {}) }
}

// 全局约定：查询条件只在点击「查询」按钮时生效，不自动触发（CLAUDE.md）
watch(() => props.fields, syncOnFieldsChange)
document.addEventListener('mousedown', onDocMouseDown)
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocMouseDown))
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
      <div v-else class="fi" :class="{ 'ms-fi': typeOf(f) === 'multiSelect' }">
        <label>{{ labelOf(f) }}</label>
        <!-- 多选：自定义复选下拉，回传值为数组 -->
        <div v-if="typeOf(f) === 'multiSelect'" class="ms-wrap">
          <button type="button" class="ms-trigger" :class="{ open: openKey === fieldKey(f) }" @click.stop="toggleDropdown(f)">
            <span class="ms-text">{{ triggerText(f) }}</span>
            <span class="ms-caret">▾</span>
          </button>
          <div v-if="openKey === fieldKey(f)" class="ms-panel" @click.stop>
            <div class="ms-ops">
              <button type="button" @click="selectAll(f)">全选</button>
              <button type="button" @click="clearAll(f)">清空</button>
            </div>
            <label v-for="opt in optionsOf(f)" :key="optValue(opt)" class="ms-opt">
              <input type="checkbox" :checked="selectedOf(f).includes(optValue(opt))" @change="toggleOpt(f, opt)" />
              <span>{{ optLabel(opt) }}</span>
            </label>
          </div>
        </div>
        <select v-else-if="typeOf(f) === 'select'" v-model="values[fieldKey(f)]" @keydown.enter="query">
          <option value="">全部</option>
          <option v-for="opt in optionsOf(f)" :key="optValue(opt)" :value="optValue(opt)">
            {{ optLabel(opt) }}
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

/* 多选下拉 */
.ms-wrap { position: relative; }
.ms-trigger {
  display: flex; align-items: center; justify-content: space-between; gap: 6px;
  min-width: 150px; height: 30px; padding: 0 8px;
  border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; cursor: pointer;
  color: #303133; font-size: 13px;
}
.ms-trigger.open, .ms-trigger:hover { border-color: #409eff; }
.ms-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ms-caret { color: #909399; font-size: 10px; }
.ms-panel {
  position: absolute; top: 32px; left: 0; z-index: 300;
  min-width: 100%; max-height: 280px; overflow-y: auto;
  background: #fff; border: 1px solid #e4e7ed; border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0,0,0,.12); padding: 4px 0;
}
.ms-ops {
  display: flex; gap: 8px; padding: 4px 10px 6px;
  border-bottom: 1px solid #f0f0f0; margin-bottom: 4px;
}
.ms-ops button { border: none; background: none; color: #409eff; cursor: pointer; font-size: 12px; padding: 0; }
.ms-opt {
  display: flex; align-items: center; gap: 6px;
  padding: 4px 10px; cursor: pointer; font-size: 13px; white-space: nowrap;
}
.ms-opt:hover { background: #f5f7fa; }
.ms-opt input { margin: 0; }
</style>
