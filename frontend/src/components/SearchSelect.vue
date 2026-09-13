<script setup>
/**
 * 通用可搜索下拉选择（单选）
 *
 * - options 支持字符串数组（['正常商品',...]）或对象数组（{value,label,disabled?}），
 *   value 原样回传（保留 Boolean/Number 类型，如 true/false 布尔字段）；
 * - 打开即搜索框（自动聚焦）：关键字对 label 做不区分大小写包含匹配，
 *   中文 label 额外支持拼音首字母匹配（输 kl 命中「可乐」），无需输全；
 * - 面板 Teleport 到 body + fixed 定位（抽屉 .drawer-body 是 overflow:auto 容器，
 *   内嵌 absolute 会被裁剪），滚动/缩放实时跟随，下方空间不足向上展开；
 * - 键盘：↑↓ 移动、Enter 选中、Esc 关闭；点外部关闭；
 * - 视觉与表单原生 select 保持一致（32px 高、12px 字号、聚焦蓝光）。
 */
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { pinyin } from 'pinyin-pro'
import { useAnchoredPanel } from '../composables/useAnchoredPanel.js'

const props = defineProps({
  modelValue: { default: '' },
  options: { type: Array, default: () => [] },
  placeholder: { type: String, default: '请选择' },
  /** options 本身为空时面板里的维护提示（区别于「搜不到」） */
  emptyText: { type: String, default: '暂无可选项' },
  searchPlaceholder: { type: String, default: '输入关键字搜索' },
  disabled: { type: Boolean, default: false },
  clearable: { type: Boolean, default: false },
  minWidth: { type: Number, default: 240 },
  /** small：28px 高/11px 字，用于紧凑表格单元格（默认 32px 表单规格） */
  size: { type: String, default: 'default' },
})

const emit = defineEmits(['update:modelValue', 'change'])

const { open, panelStyle, controlRef, show, hide } = useAnchoredPanel({ minWidth: props.minWidth })
const keyword = ref('')
const activeIndex = ref(-1)
const searchInputRef = ref(null)
const rootRef = ref(null)

const normOptions = computed(() => props.options.map(o =>
  (o && typeof o === 'object')
    ? { value: o.value, label: o.label ?? String(o.value ?? ''), disabled: !!o.disabled }
    : { value: o, label: String(o), disabled: false }))

const isEmptyVal = v => v === '' || v === null || v === undefined
const selected = computed(() => normOptions.value.find(o => o.value === props.modelValue))
// 历史值不在当前选项（停用/删除）时原样显示，给出橙色警示但不丢值
const displayLabel = computed(() => selected.value?.label ?? (isEmptyVal(props.modelValue) ? '' : String(props.modelValue)))
const stale = computed(() => !isEmptyVal(props.modelValue) && !selected.value)

function pinyinInitials(text) {
  try {
    return pinyin(text, { pattern: 'first', type: 'string', separator: '', v: true }).toLowerCase()
  } catch {
    return ''
  }
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return normOptions.value
  const pyKw = kw.replace(/[^a-z0-9]/g, '')
  return normOptions.value.filter(o => {
    if (o.label.toLowerCase().includes(kw)) return true
    if (pyKw) {
      const py = pinyinInitials(o.label)
      if (py.includes(pyKw)) return true
    }
    return false
  })
})

function toggleOpen() {
  if (props.disabled) return
  if (open.value) { hide(); return }
  keyword.value = ''
  activeIndex.value = -1
  show(() => searchInputRef.value?.focus())
}

function pick(o) {
  if (o.disabled) return
  emit('update:modelValue', o.value)
  emit('change', o.value, o)
  hide()
  keyword.value = ''
  activeIndex.value = -1
}

function clearValue(e) {
  e.stopPropagation()
  emit('update:modelValue', '')
  emit('change', '', null)
}

function onSearchKeydown(e) {
  const list = filtered.value
  if (e.key === 'Escape') { hide(); return }
  if (!list.length) return
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    activeIndex.value = Math.min(activeIndex.value + 1, list.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    const next = activeIndex.value - 1
    activeIndex.value = next < 0 ? 0 : next
  } else if (e.key === 'Enter' && activeIndex.value >= 0) {
    e.preventDefault()
    pick(list[activeIndex.value])
  }
}
watch(keyword, () => { activeIndex.value = -1 })

function onDocMousedown(e) {
  if (open.value && rootRef.value && !rootRef.value.contains(e.target)) {
    hide()
    keyword.value = ''
  }
}
document.addEventListener('mousedown', onDocMousedown)
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocMousedown))
</script>

<template>
  <div ref="rootRef" class="ss" :class="{ 'ss-open': open, 'ss-disabled': disabled, 'ss-stale': stale, 'ss-small': size === 'small' }">
    <div ref="controlRef" class="ss-control" :title="displayLabel" @click="toggleOpen">
      <span class="ss-value" :class="{ 'ss-placeholder': !displayLabel }">{{ displayLabel || placeholder }}</span>
      <span v-if="clearable && displayLabel" class="ss-clear" title="清除" @click="clearValue">×</span>
      <span class="ss-arrow" :class="{ 'ss-arrow-up': open }"></span>
    </div>

    <Teleport to="body">
      <div v-if="open" class="ss-panel" :style="panelStyle" @mousedown.stop>
        <div class="ss-search">
          <input
            ref="searchInputRef"
            v-model="keyword"
            type="text"
            :placeholder="searchPlaceholder"
            @keydown="onSearchKeydown"
          />
        </div>
        <div class="ss-body">
          <div v-if="normOptions.length === 0" class="ss-empty">{{ emptyText }}</div>
          <div v-else-if="filtered.length === 0" class="ss-empty">未找到匹配项</div>
          <div
            v-for="(o, i) in filtered"
            :key="String(o.value) + '_' + i"
            class="ss-option"
            :class="{
              'ss-active': i === activeIndex,
              'ss-selected': o.value === modelValue,
              'ss-option-disabled': o.disabled,
              'ss-option-empty': isEmptyVal(o.value),
            }"
            @click="pick(o)"
            @mouseenter="activeIndex = i"
          >
            <span class="ss-option-label" :title="o.label">{{ o.label }}</span>
            <span v-if="o.value === modelValue && !isEmptyVal(o.value)" class="ss-check">✓</span>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.ss { width: 100%; min-width: 0; flex: 1; }
/* 紧凑表格规格：与 .input-cell 内 28px/11px 的输入框对齐 */
.ss-small .ss-control { height: 28px; font-size: 11px; }
.ss-small .ss-value { line-height: 26px; text-align: center; }
.ss-small .ss-arrow { margin-left: -2px; }

.ss-control {
  display: flex;
  align-items: center;
  width: 100%;
  height: 32px;
  padding: 0 8px 0 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  font-size: 12px;
  color: #303133;
  cursor: pointer;
  box-sizing: border-box;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.ss-control:hover { border-color: #c0c4cc; }
.ss-open .ss-control {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}
.ss-disabled .ss-control {
  background: #f5f7fa;
  border-color: #e4e7ed;
  color: #c0c4cc;
  cursor: not-allowed;
}
/* 当前值已不在选项中（停用/删除）：橙色警示但不阻止显示 */
.ss-stale .ss-control { border-color: #e6a23c; }

.ss-value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 30px;
  font-weight: 400;
}
.ss-placeholder { color: #a8abb2; }
.ss-disabled .ss-placeholder { color: #c0c4cc; }

.ss-clear {
  flex: none;
  width: 16px;
  height: 16px;
  margin-right: 4px;
  border-radius: 50%;
  color: #909399;
  text-align: center;
  line-height: 15px;
  font-size: 14px;
}
.ss-clear:hover { background: #c0c4cc; color: #fff; }

.ss-arrow {
  flex: none;
  width: 0;
  height: 0;
  border-left: 5px solid transparent;
  border-right: 5px solid transparent;
  border-top: 6px solid #909399;
  transition: transform 0.2s;
}
.ss-arrow-up { transform: rotate(180deg); }
.ss-disabled .ss-arrow { border-top-color: #c0c4cc; }

/* Teleport 到 body，坐标由 useAnchoredPanel 以 fixed 写入；z-index 高于抽屉遮罩 500 */
.ss-panel {
  position: fixed;
  z-index: 1200;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.ss-search {
  flex: none;
  padding: 8px;
  border-bottom: 1px solid #f0f2f5;
}
.ss-search input {
  width: 100%;
  height: 30px;
  padding: 0 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 12px;
  outline: none;
  box-sizing: border-box;
}
.ss-search input:focus { border-color: #409eff; }

.ss-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 0;
}
.ss-empty {
  padding: 16px 12px;
  color: #909399;
  font-size: 12px;
  text-align: center;
}
.ss-option {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 12px;
  font-size: 12px;
  color: #303133;
  cursor: pointer;
  white-space: nowrap;
}
.ss-option:hover,
.ss-option.ss-active { background: #f5f7fa; }
.ss-option.ss-selected { color: #409eff; font-weight: 600; background: #ecf5ff; }
.ss-option-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ss-check { flex: none; color: #409eff; }
/* value 为空的「请选择」类选项 */
.ss-option-empty { color: #a8abb2; }
.ss-option-empty.ss-selected { color: #409eff; }
.ss-option-disabled { color: #c0c4cc; cursor: not-allowed; background: #fafafa; }
.ss-option-disabled:hover { background: #fafafa; }
</style>
