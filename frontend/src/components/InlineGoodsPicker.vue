<script setup>
/**
 * 内联商品选择器 —— 挂在明细表「商品编号」单元格里的轻量下拉。
 *
 * 与 GoodsAddDialog（全屏弹窗）的区别：这个不接管整个录单流程，
 * 只负责「搜出商品 → 交给父组件写入当前行」。
 *
 * 交互：
 *   · 输入关键字（编号/名称/简拼/条码）实时过滤
 *   · ↑↓ 移动高亮，Enter 选中高亮项（单选，立即写入当前行）
 *   · 勾选多个 → 「添加所选」批量返回（鼠标多选）
 *   · Esc 关闭下拉
 *
 * 键盘冲突约定：下拉展开时 ↑↓/Enter 归下拉使用，父组件的单元格导航需让行。
 * 组件通过 expose 的 isOpen 让父组件判断。
 */
import { ref, computed, watch, nextTick } from 'vue'

const props = defineProps({
  /** 搜索关键字（v-model） */
  modelValue: { type: String, default: '' },
  /** 商品列表，由父组件加载后传入，避免每行各拉一次 */
  goodsList: { type: Array, default: () => [] },
  /** 已添加的商品编码，用于在下拉里标记（不禁用，允许同商品不同单位多行） */
  existingCodes: { type: Array, default: () => [] },
  /** 是否只读（查看模式） */
  disabled: { type: Boolean, default: false },
  /**
   * 远程检索模式：为 true 时不做本地过滤，直接展示 goodsList，
   * 输入关键字改为 emit('search') 交给父组件去后端查（用于按销量排序的候选列表）。
   * 默认 false —— 保持既有调用方行为不变。
   */
  remote: { type: Boolean, default: false },
  /** 远程检索中 */
  loading: { type: Boolean, default: false },
  /** 是否展示「销量」列 */
  showSaleQty: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'select', 'select-multi', 'nav', 'search'])

const open = ref(false)
const highlight = ref(0)
const checked = ref(new Set())
const inputRef = ref(null)
const MAX_ROWS = 50

// ==================== 过滤 ====================
const filtered = computed(() => {
  const list = props.goodsList.filter(g => String(g.status || '').toUpperCase() !== 'STOPPED')
  // 远程模式：列表已由后端按销量排好并截断，前端不再过滤
  if (props.remote) return list
  const q = String(props.modelValue || '').trim().toLowerCase()
  if (!q) return list.slice(0, MAX_ROWS)
  return list.filter(g => {
    const code = String(g.goodsCode || '').toLowerCase()
    const name = String(g.goodsName || '').toLowerCase()
    const simple = String(g.simpleCode || '').toLowerCase()   // 简拼
    const bar = String(g.barcode || '').toLowerCase()
    return code.includes(q) || name.includes(q) || simple.includes(q) || bar.includes(q)
  }).slice(0, MAX_ROWS)
})

// 关键字变化 → 展开下拉并把高亮重置到第一条。
// 空串不展开（行初始 modelValue 就是 ''）；刚选择关闭后也不展开（父组件回写 goodsCode）。
let searchTimer = null
watch(() => props.modelValue, (v) => {
  if (props.disabled) return
  if (justClosed) { justClosed = false; return }
  if (!String(v || '').trim()) { open.value = false; return }
  open.value = true
  highlight.value = 0
  // 远程模式：防抖 300ms 后交给父组件查后端（避免逐字敲键都发请求）
  if (props.remote) {
    clearTimeout(searchTimer)
    searchTimer = setTimeout(() => emit('search', String(v || '').trim()), 300)
  }
})

watch(filtered, () => {
  if (highlight.value >= filtered.value.length) highlight.value = Math.max(0, filtered.value.length - 1)
})

function isExisting(code) {
  return props.existingCodes.includes(code)
}

// ==================== 键盘 ====================
function onKeyDown(e) {
  const k = e.key
  // 下拉未展开时，↑↓ 交给父组件做单元格导航
  if (!open.value || filtered.value.length === 0) {
    if (k === 'ArrowUp' || k === 'ArrowDown') {
      emit('nav', k === 'ArrowUp' ? 'up' : 'down')
      e.preventDefault()
      return
    }
    if (k === 'Enter') {
      // 没有候选项时，回车交给父组件走「同行下一字段」
      emit('nav', 'enter')
      e.preventDefault()
      return
    }
    return
  }

  if (k === 'ArrowDown') {
    highlight.value = (highlight.value + 1) % filtered.value.length
    e.preventDefault()
  } else if (k === 'ArrowUp') {
    highlight.value = (highlight.value - 1 + filtered.value.length) % filtered.value.length
    e.preventDefault()
  } else if (k === 'Enter') {
    // 有勾选就批量添加，否则选中高亮项
    if (checked.value.size > 0) confirmMulti()
    else pick(filtered.value[highlight.value])
    e.preventDefault()
  } else if (k === 'Escape') {
    open.value = false
    e.preventDefault()
  }
}

// ==================== 选择 ====================
/** 选中后抑制下一次 watch 展开 —— 父组件会回写 goodsCode 到 modelValue，
 *  不加这个标志的话 watch 会以为是用户输入而重新弹开下拉。 */
let justClosed = false

function pick(g) {
  if (!g) return
  emit('select', g)
  open.value = false
  checked.value = new Set()
  justClosed = true
}

function toggleCheck(code, ev) {
  ev?.stopPropagation()
  if (checked.value.has(code)) checked.value.delete(code)
  else checked.value.add(code)
}

function confirmMulti() {
  const rows = props.goodsList.filter(g => checked.value.has(g.goodsCode))
  if (rows.length === 0) return
  emit('select-multi', rows)
  open.value = false
  checked.value = new Set()
  justClosed = true
}

function onInput(e) {
  justClosed = false   // 用户主动输入，允许 watch 展开
  emit('update:modelValue', e.target.value)
}

function onFocus() {
  if (!props.disabled) open.value = true
}

// 延迟关闭：否则点击下拉项时 blur 先触发，选不中
function onBlur() {
  setTimeout(() => { open.value = false }, 150)
}

function focus() {
  inputRef.value?.focus?.()
  inputRef.value?.select?.()
}

defineExpose({ focus, isOpen: () => open.value && filtered.value.length > 0 })
</script>

<template>
  <div class="igp">
    <input
      ref="inputRef"
      class="igp-input"
      :value="modelValue"
      :disabled="disabled"
      placeholder="编号/名称/简拼/条码"
      @input="onInput"
      @focus="onFocus"
      @blur="onBlur"
      @keydown="onKeyDown" />

    <div v-if="open && (filtered.length > 0 || loading)" class="igp-pop">
      <div v-if="loading" class="igp-loading">检索中…</div>
      <table v-else>
        <thead>
          <tr>
            <th style="width:28px"></th>
            <th style="width:92px">商品编号</th>
            <th style="min-width:150px">商品名称</th>
            <th style="width:100px">规格</th>
            <th style="width:110px">条码</th>
            <th v-if="showSaleQty" style="width:64px" class="r">销量</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(g, i) in filtered" :key="g.goodsCode"
            :class="{ hl: i === highlight, added: isExisting(g.goodsCode) }"
            @mouseenter="highlight = i"
            @mousedown.prevent="pick(g)">
            <td @mousedown.stop.prevent="toggleCheck(g.goodsCode, $event)">
              <input type="checkbox" :checked="checked.has(g.goodsCode)" @click.stop />
            </td>
            <td class="mono">{{ g.goodsCode }}</td>
            <td>{{ g.goodsName }}</td>
            <td>{{ g.spec || '-' }}</td>
            <td class="mono">{{ g.barcode || '-' }}</td>
            <td v-if="showSaleQty" class="mono r">{{ Number(g.saleQty ?? 0) }}</td>
          </tr>
        </tbody>
      </table>
      <div class="igp-foot">
        <span>↑↓ 选择 · Enter 添加 · Esc 关闭</span>
        <button v-if="checked.size > 0" class="btn primary igp-btn" @mousedown.prevent="confirmMulti">
          添加所选 ({{ checked.size }})
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.igp { position: relative; width: 100%; }
.igp-input {
  width: 100%; height: 24px; padding: 0 4px;
  border: 1px solid #dcdfe6; border-radius: 3px; font-size: 12px;
}
.igp-input:focus { border-color: #409eff; outline: none; }
.igp-input:disabled { background: #f5f7fa; }

.igp-pop {
  position: absolute; z-index: 1200; top: 26px; left: 0;
  min-width: 520px; max-height: 260px; overflow: auto;
  background: #fff; border: 1px solid #dcdfe6; border-radius: 4px;
  box-shadow: 0 4px 16px rgba(0,0,0,.14);
}
.igp-pop table { width: 100%; border-collapse: collapse; font-size: 12px; }
.igp-pop th {
  background: #f5f7fa; padding: 5px 6px; text-align: left; font-weight: 600;
  color: #303133; border-bottom: 1px solid #e5e7eb;
  position: sticky; top: 0; z-index: 1;
}
.igp-pop td { padding: 4px 6px; border-bottom: 1px solid #f5f5f5; cursor: pointer; }
.igp-pop tr.hl td { background: #ecf5ff; }
.igp-pop tr.added td { color: #909399; }
.igp-pop .mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.igp-pop .r { text-align: right; }
.igp-loading { padding: 14px; text-align: center; color: #909399; font-size: 12px; }

.igp-foot {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4px 8px; border-top: 1px solid #e5e7eb;
  font-size: 11px; color: #909399;
  position: sticky; bottom: 0; background: #fff;
}
.igp-btn { height: 22px; padding: 0 8px; font-size: 11px; }
</style>
