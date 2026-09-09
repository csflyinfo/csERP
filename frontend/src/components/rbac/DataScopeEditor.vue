<script setup>
/**
 * 七维数据范围编辑器（PRD-28 §5.3），用户收窄与角色授权共用。
 * v-model 为后端形态 [{scopeType, scopeValue}]；scopeValue ∈ ALL/SELF/SUB_TREE/逗号分隔ID。
 * 「默认」选项 = 该维不出行：用户层表示跟随角色，角色层表示系统默认（仅自己创建）。
 */
import { computed, ref } from 'vue'
import ScopeValuePicker from './ScopeValuePicker.vue'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  // 默认选项文案（用户层：跟随角色；角色层：系统默认）
  defaultLabel: { type: String, default: '跟随角色' },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])

const DIMS = [
  { scopeType: 'WAREHOUSE', scopeName: '仓库' },
  { scopeType: 'CUSTOMER', scopeName: '客户' },
  { scopeType: 'SUPPLIER', scopeName: '供应商' },
  { scopeType: 'SALESMAN', scopeName: '业务员' },
  { scopeType: 'OWNER', scopeName: '建档人/老板' },
  { scopeType: 'GOODS_CATEGORY', scopeName: '商品分类' },
  { scopeType: 'BRAND', scopeName: '品牌' },
]
const MODES = [
  { value: 'ALL', label: '全部' },
  { value: 'SELF', label: '仅自己' },
  { value: 'SUB_TREE', label: '自己及下级' },
  { value: 'IDS', label: '指定' },
]

/** 行视图：scopeType -> {mode, ids:[{id,name}]} */
const rows = computed(() => {
  return DIMS.map(d => {
    const saved = (props.modelValue || []).find(x => x.scopeType === d.scopeType)
    if (!saved) return { ...d, mode: '', ids: [] }
    const v = String(saved.scopeValue || '').trim()
    if (['ALL', 'SELF', 'SUB_TREE'].includes(v)) return { ...d, mode: v, ids: [] }
    const ids = v ? v.split(',').filter(Boolean).map(id => ({ id, name: id })) : []
    return { ...d, mode: ids.length ? 'IDS' : '', ids }
  })
})

function patch(row, mode) {
  const next = []
  for (const r of rows.value) {
    if (r.scopeType === row.scopeType) {
      if (mode) next.push({ scopeType: r.scopeType, scopeValue: mode === 'IDS' ? r.ids.map(x => x.id).join(',') : mode })
    } else if (r.mode === 'IDS') {
      next.push({ scopeType: r.scopeType, scopeValue: r.ids.map(x => x.id).join(',') })
    } else if (r.mode) {
      next.push({ scopeType: r.scopeType, scopeValue: r.mode })
    }
  }
  emit('update:modelValue', next)
}

// 指定值选择弹窗
const picker = ref({ visible: false, row: null })
function openPicker(row) {
  if (props.disabled) return
  picker.value = { visible: true, row }
}
function onPick(items) {
  const row = picker.value.row
  const next = []
  for (const r of rows.value) {
    if (r.scopeType === row.scopeType) {
      const ids = items.map(x => x.id)
      if (ids.length) next.push({ scopeType: r.scopeType, scopeValue: ids.join(',') })
    } else if (r.mode === 'IDS') {
      next.push({ scopeType: r.scopeType, scopeValue: r.ids.map(x => x.id).join(',') })
    } else if (r.mode) {
      next.push({ scopeType: r.scopeType, scopeValue: r.mode })
    }
  }
  // 保存名字用于回显（仅前端展示用）
  row.ids.splice(0, row.ids.length, ...items)
  emit('update:modelValue', next)
  picker.value.visible = false
}
</script>

<template>
  <div class="scope-editor">
    <div v-for="r in rows" :key="r.scopeType" class="scope-row">
      <span class="scope-name">{{ r.scopeName }}</span>
      <select :value="r.mode" :disabled="disabled" @change="patch(r, $event.target.value)">
        <option value="">{{ defaultLabel }}</option>
        <option v-for="m in MODES" :key="m.value" :value="m.value">{{ m.label }}</option>
      </select>
      <button
        v-if="r.mode === 'IDS'" type="button" class="btn link-like"
        :disabled="disabled" @click="openPicker(r)"
      >
        选择（{{ r.ids.length }} 项）
      </button>
      <span v-else-if="r.mode === 'IDS'" class="muted"></span>
    </div>
    <ScopeValuePicker
      :visible="picker.visible"
      :scope-type="picker.row?.scopeType || ''"
      :scope-name="picker.row?.scopeName || ''"
      :selected="picker.row?.ids || []"
      @close="picker.visible = false"
      @confirm="onPick"
    />
  </div>
</template>

<style scoped>
.scope-editor { display: flex; flex-direction: column; gap: 6px; }
.scope-row { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.scope-name { width: 92px; color: #334155; }
.scope-row select {
  height: 28px; border: 1px solid var(--line); border-radius: 6px;
  background: #fff; padding: 0 6px; font-size: 12px; min-width: 120px;
}
.link-like { height: 26px; padding: 0 10px; font-size: 12px; }
.muted { color: #909399; font-size: 12px; }
</style>
