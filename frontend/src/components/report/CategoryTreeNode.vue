<template>
  <div class="cat-node">
    <label>
      <input type="checkbox" :checked="state === 'all'" :ref="cb"
             :indeterminate.prop="state === 'partial'" @change="$emit('toggle', node)">
      <span :class="{ partial: state === 'partial' }">{{ node.name }}</span>
    </label>
    <div v-if="node.children?.length" class="cat-children">
      <CategoryTreeNode v-for="c in node.children" :key="c.code" :node="c"
                        :checked-set="checkedSet" @toggle="n => $emit('toggle', n)" />
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'

/**
 * 分类树递归节点：自身+全部后代名称；全选/半选/未选三态。
 */
const props = defineProps({
  node: { type: Object, required: true },
  checkedSet: { type: Set, required: true },
})
defineEmits(['toggle'])

function allNames(n, acc = []) {
  acc.push(n.name)
  ;(n.children || []).forEach(c => allNames(c, acc))
  return acc
}
const state = computed(() => {
  const names = allNames(props.node)
  const n = names.filter(x => props.checkedSet.has(x)).length
  if (n === names.length) return 'all'
  if (n > 0) return 'partial'
  return 'none'
})
// :indeterminate 在重渲染后可能丢失，显式同步
const cb = ref(null)
watch(state, async s => {
  await nextTick()
  if (cb.value) cb.value.indeterminate = s === 'partial'
}, { immediate: true })
</script>
