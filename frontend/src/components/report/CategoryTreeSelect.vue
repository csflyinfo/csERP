<template>
  <div class="cat-select" ref="root">
    <button type="button" class="cat-trigger" @click="open = !open">
      <span :class="{ placeholder: !names.length }">
        {{ names.length ? names.slice(0, 2).join('、') + (names.length > 2 ? ` 等${names.length}项` : '') : placeholder }}
      </span>
      <span class="cat-arrow" :class="{ up: open }">▾</span>
    </button>
    <div v-if="open" class="cat-panel">
      <div class="cat-panel-bar">
        <a @click.prevent="clear">清空</a>
        <span class="muted">勾选上级自动选中下级</span>
      </div>
      <div class="cat-tree">
        <CategoryTreeNode v-for="n in tree" :key="n.code" :node="n"
                          :checked-set="checkedSet" @toggle="toggle" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import CategoryTreeNode from './CategoryTreeNode.vue'

/**
 * 商品分类树多选：值为分类「名称」数组（后端按 category_name IN(...) 匹配）。
 * 勾选上级自动勾选全部下级；清空按钮一键移除。
 */
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  tree: { type: Array, default: () => [] },
  placeholder: { type: String, default: '全部商品分类' },
})
const emit = defineEmits(['update:modelValue', 'change'])

const open = ref(false)
const root = ref(null)
/** 兼容旧字符串记忆（逗号分隔）；内部统一按数组处理。 */
const values = computed(() => {
  const v = props.modelValue
  if (Array.isArray(v)) return v
  if (typeof v === 'string' && v) return v.split(',').map(s => s.trim()).filter(Boolean)
  return []
})
const checkedSet = computed(() => new Set(values.value))
const names = values

function descendants(node, acc = []) {
  acc.push(node.name)
  for (const c of node.children || []) descendants(c, acc)
  return acc
}
function toggle(node) {
  const next = new Set(checkedSet.value)
  const ns = descendants(node)
  if (ns.every(n => next.has(n))) ns.forEach(n => next.delete(n))
  else ns.forEach(n => next.add(n))
  const out = [...next]
  emit('update:modelValue', out)
  emit('change', out)
}
function clear() {
  emit('update:modelValue', [])
  emit('change', [])
}
function onDocClick(e) {
  if (root.value && !root.value.contains(e.target)) open.value = false
}
nextTick(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))
</script>

<style scoped>
.cat-select { position: relative; }
.cat-trigger {
  height: 28px; width: 100%; min-width: 0; border: 1px solid #dcdfe6; border-radius: 4px;
  background: #fff; padding: 0 8px; font-size: 13px; color: #303133;
  display: flex; align-items: center; justify-content: space-between; gap: 8px; cursor: pointer;
}
.cat-trigger .placeholder { color: #909399; }
.cat-arrow { color: #909399; font-size: 10px; transition: transform .15s; }
.cat-arrow.up { transform: rotate(180deg); }
.cat-panel {
  position: absolute; z-index: 60; top: 32px; left: 0; width: 260px;
  max-height: 320px; overflow: auto; background: #fff; border: 1px solid #dcdfe6;
  border-radius: 4px; box-shadow: 0 4px 14px rgba(0,0,0,.12); padding: 6px 8px;
}
.cat-panel-bar { display: flex; justify-content: space-between; align-items: center; font-size: 12px; padding: 2px 2px 6px; border-bottom: 1px solid #f0f0f0; margin-bottom: 4px; }
.cat-panel-bar a { color: #409eff; cursor: pointer; }
.cat-panel-bar .muted { color: #909399; }
:deep(.cat-node label) { display: flex; align-items: center; gap: 4px; font-size: 13px; padding: 2px 0; cursor: pointer; color: #303133; }
:deep(.cat-node input) { width: 14px; height: 14px; min-width: auto; margin: 0; }
:deep(.cat-node .partial) { color: #409eff; }
:deep(.cat-children) { padding-left: 16px; }
</style>
