<script setup>
/**
 * 商品分类树形下拉选择器
 *
 * 交互：
 *  - 未输入关键字：以树状结构展示「正常」状态的商品分类，一级默认收起，点 ▶/节点名展开下级；
 *    只有末级分类（无子级）可点选回填，非末级点击只做展开/收起。
 *  - 输入关键字：在「末级分类」名称中做不区分大小写（含拼音首字母）的模糊匹配，
 *    下拉平铺命中的末级（附带完整层级路径，便于同名消歧），点选回填。
 *
 * 输入：扁平节点数组 nodes（全量、已过滤停用），组件内部建树与判定叶子，
 * 避免父级集合只来自部分分页数据而把中间层误判成末级。
 */
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { pinyin } from 'pinyin-pro'
import { useAnchoredPanel } from '../../../composables/useAnchoredPanel.js'

const props = defineProps({
  /** 当前选中的分类名称（商品表只存 categoryName） */
  modelValue: { type: String, default: '' },
  /** 扁平分类节点：{ categoryCode, categoryName, parentCode, defaultTaxRate } */
  nodes: { type: Array, default: () => [] },
  placeholder: { type: String, default: '请选择' },
  emptyText: { type: String, default: '暂无可选分类，请先在【商品分类】维护末级分类' },
})

const emit = defineEmits(['update:modelValue', 'select'])

// 面板 minWidth 260：四列表格中控件仅约 110px，过窄会截断长分类名与祖先路径
const { open, panelStyle, controlRef, show, hide } = useAnchoredPanel({ minWidth: 260 })
const keyword = ref('')
const expanded = ref(new Set())
const activeIndex = ref(-1)
const rootRef = ref(null)
const searchInputRef = ref(null)

// 切换数据源（抽屉每次打开重新拉取）时复位展开状态
watch(() => props.nodes, () => { expanded.value = new Set() })

// ==================== 树构建 ====================
const tree = computed(() => {
  const map = new Map()
  for (const n of props.nodes) {
    map.set(n.categoryCode, { ...n, children: [] })
  }
  const roots = []
  for (const node of map.values()) {
    const parent = node.parentCode ? map.get(node.parentCode) : null
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  const sortRec = (arr) => {
    arr.sort((a, b) => String(a.categoryCode).localeCompare(String(b.categoryCode), 'zh', { numeric: true }))
    arr.forEach(n => { if (n.children.length) sortRec(n.children) })
  }
  sortRec(roots)
  return roots
})

const byCode = computed(() => new Map(props.nodes.map(n => [n.categoryCode, n])))

// 扁平化可视树行（含层级/叶子标记），供模板 v-for 渲染缩进与 caret
const visibleRows = computed(() => {
  const rows = []
  const walk = (list, level) => {
    for (const n of list) {
      const hasChildren = n.children.length > 0
      rows.push({
        code: n.categoryCode,
        name: n.categoryName,
        taxRate: n.defaultTaxRate || '',
        level,
        hasChildren,
        isLeaf: !hasChildren,
        expanded: expanded.value.has(n.categoryCode),
      })
      if (hasChildren && expanded.value.has(n.categoryCode)) walk(n.children, level + 1)
    }
  }
  walk(tree.value, 0)
  return rows
})

// ==================== 搜索（仅末级名称模糊匹配） ====================
function pinyinInitials(text) {
  try {
    return pinyin(text, { pattern: 'first', type: 'string', separator: '', v: true }).toLowerCase()
  } catch {
    return ''
  }
}

const searchResults = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return []
  const pyKw = kw.replace(/[^a-z0-9]/g, '')
  const hits = []
  for (const n of props.nodes) {
    const hasChild = props.nodes.some(x => x.parentCode === n.categoryCode)
    if (hasChild || !n.categoryName) continue
    const label = n.categoryName.toLowerCase()
    const matched = label.includes(kw) || (pyKw && pinyinInitials(n.categoryName).includes(pyKw))
    if (matched) {
      hits.push({ code: n.categoryCode, name: n.categoryName, taxRate: n.defaultTaxRate || '', path: pathNamesOf(n.categoryCode) })
    }
    if (hits.length >= 100) break // 结果过多时截断，面板内滚动即可
  }
  return hits
})

function pathNamesOf(code) {
  const parts = []
  let cur = byCode.value.get(code)
  let guard = 0
  while (cur && guard++ < 20) {
    parts.unshift(cur.categoryName)
    if (!cur.parentCode) break
    cur = byCode.value.get(cur.parentCode)
  }
  return parts
}

// ==================== 交互 ====================
function toggleOpen() {
  if (open.value) { hide(); return }
  keyword.value = ''
  show(() => searchInputRef.value?.focus())
}

function toggleExpand(code) {
  const next = new Set(expanded.value)
  if (next.has(code)) next.delete(code)
  else next.add(code)
  expanded.value = next
}

function selectNode(node) {
  // node: { code/name/taxRate }
  emit('update:modelValue', node.name)
  emit('select', { name: node.name, taxRate: node.taxRate, code: node.code })
  hide()
  keyword.value = ''
  activeIndex.value = -1
}

function onRowClick(row) {
  if (row.isLeaf) selectNode({ code: row.code, name: row.name, taxRate: row.taxRate })
  else toggleExpand(row.code)
}

function clearValue(e) {
  e.stopPropagation()
  emit('update:modelValue', '')
  emit('select', { name: '', taxRate: '', code: '' })
}

// 搜索结果键盘：↑↓ 移动、Enter 选中、Esc 关闭
function onSearchKeydown(e) {
  const list = searchResults.value
  if (e.key === 'Escape') { hide(); return }
  if (!list.length) return
  if (e.key === 'ArrowDown') { e.preventDefault(); activeIndex.value = Math.min(activeIndex.value + 1, list.length - 1) }
  else if (e.key === 'ArrowUp') {
    e.preventDefault()
    const next = activeIndex.value - 1
    activeIndex.value = next < 0 ? 0 : next
  } else if (e.key === 'Enter' && activeIndex.value >= 0) { e.preventDefault(); selectNode(list[activeIndex.value]) }
}
watch(keyword, () => { activeIndex.value = -1 })

// 点击组件外部关闭（面板 teleport 到 body，面板内部 mousedown 已 .stop）
function onDocMousedown(e) {
  if (open.value && rootRef.value && !rootRef.value.contains(e.target)) {
    hide()
    keyword.value = ''
  }
}
document.addEventListener('mousedown', onDocMousedown)
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocMousedown))

// 当前值是否仍属于「正常分类」中的节点（历史数据可能挂在已停用/已删除分类上，给出警示但不丢值）
const valueKnown = computed(() =>
  !props.modelValue || props.nodes.some(n => n.categoryName === props.modelValue))
</script>

<template>
  <div ref="rootRef" class="cts" :class="{ 'cts-open': open, 'cts-stale': !valueKnown && !!modelValue }">
    <div ref="controlRef" class="cts-control" @click="toggleOpen" :title="!valueKnown && modelValue ? '该分类已停用或不存在，请重新选择末级分类' : modelValue">
      <span class="cts-value" :class="{ 'cts-placeholder': !modelValue }">{{ modelValue || placeholder }}</span>
      <span v-if="modelValue" class="cts-clear" title="清除" @click="clearValue">×</span>
      <span class="cts-arrow" :class="{ 'cts-arrow-up': open }"></span>
    </div>

    <!-- Teleport 到 body：抽屉 .drawer-body 是 overflow 滚动容器，内嵌 absolute 面板会被裁剪 -->
    <Teleport to="body">
    <div v-if="open" class="cts-panel" :style="panelStyle" @mousedown.stop>
      <div class="cts-search">
        <input
          ref="searchInputRef"
          v-model="keyword"
          type="text"
          placeholder="输入关键字搜索末级分类（支持拼音首字母）"
          @keydown="onSearchKeydown"
        />
      </div>

      <div class="cts-body">
        <!-- 搜索态：平铺命中的末级分类（名称 + 完整路径） -->
        <template v-if="keyword.trim()">
          <div v-if="searchResults.length === 0" class="cts-empty">未找到匹配的末级分类</div>
          <div
            v-for="(r, i) in searchResults"
            :key="r.code"
            class="cts-result"
            :class="{ 'cts-active': i === activeIndex, 'cts-selected': r.name === modelValue }"
            @click="selectNode(r)"
            @mouseenter="activeIndex = i"
          >
            <span class="cts-result-name" :title="r.name">{{ r.name }}</span>
            <span class="cts-result-path" :title="r.path.slice(0, -1).join(' / ')">{{ r.path.slice(0, -1).join(' / ') }}</span>
          </div>
        </template>

        <!-- 树态 -->
        <template v-else>
          <div v-if="visibleRows.length === 0" class="cts-empty">{{ emptyText }}</div>
          <div
            v-for="row in visibleRows"
            :key="row.code"
            class="cts-row"
            :class="{ 'cts-leaf': row.isLeaf, 'cts-selected': row.isLeaf && row.name === modelValue }"
            :style="{ paddingLeft: 8 + row.level * 16 + 'px' }"
            @click="onRowClick(row)"
          >
            <span class="cts-caret" :class="{ 'cts-caret-leaf': row.isLeaf, 'cts-caret-open': row.expanded }">
              <template v-if="row.hasChildren">▶</template>
            </span>
            <span class="cts-row-name" :title="row.name">{{ row.name }}</span>
            <span v-if="row.isLeaf && row.name === modelValue" class="cts-check">✓</span>
          </div>
        </template>
      </div>
    </div>
    </Teleport>
  </div>
</template>

<style scoped>
.cts { position: relative; width: 100%; }

.cts-control {
  display: flex;
  align-items: center;
  height: 32px;
  padding: 0 8px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  font-size: 12px;
  color: #303133;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
  box-sizing: border-box;
}
.cts-control:hover { border-color: #c0c4cc; }
.cts-open .cts-control {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}
/* 历史值对应分类已停用/删除：橙色警示但不阻止显示与保存 */
.cts-stale .cts-control { border-color: #e6a23c; }
.cts-value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 30px;
}
.cts-placeholder { color: #a8abb2; }
.cts-clear {
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
.cts-clear:hover { background: #c0c4cc; color: #fff; }
.cts-arrow {
  flex: none;
  width: 0;
  height: 0;
  border-left: 5px solid transparent;
  border-right: 5px solid transparent;
  border-top: 6px solid #909399;
  transition: transform 0.2s;
}
.cts-arrow-up { transform: rotate(180deg); }

.cts-panel {
  /* 坐标由 useAnchoredPanel 以 fixed 写入（空间不足时向上展开）；需高于抽屉遮罩 z-index:500 */
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
.cts-search { padding: 8px; border-bottom: 1px solid #f0f2f5; flex: none; }
.cts-search input {
  width: 100%;
  height: 30px;
  padding: 0 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 12px;
  outline: none;
  box-sizing: border-box;
}
.cts-search input:focus { border-color: #409eff; }

.cts-body { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 0; }
.cts-empty { padding: 16px 12px; color: #909399; font-size: 12px; text-align: center; }

.cts-row {
  display: flex;
  align-items: center;
  height: 28px;
  padding-right: 10px;
  font-size: 12px;
  color: #303133;
  cursor: pointer;
  white-space: nowrap;
}
.cts-row:hover { background: #f5f7fa; }
.cts-leaf:hover { background: #ecf5ff; color: #409eff; }
.cts-selected { color: #409eff; font-weight: 600; background: #ecf5ff; }
.cts-caret {
  flex: none;
  display: inline-block;
  width: 16px;
  font-size: 9px;
  color: #909399;
  transition: transform 0.15s;
}
.cts-caret-open { transform: rotate(90deg); }
.cts-caret-leaf { cursor: default; }
.cts-row-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cts-check { flex: none; margin-left: 6px; color: #409eff; font-size: 12px; }

/* 搜索结果 */
.cts-result {
  display: flex;
  flex-direction: column;
  padding: 5px 12px;
  cursor: pointer;
}
.cts-result:hover, .cts-result.cts-active { background: #ecf5ff; }
.cts-result.cts-selected .cts-result-name { color: #409eff; font-weight: 600; }
.cts-result-name {
  font-size: 12px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cts-result-path {
  font-size: 11px;
  color: #909399;
  margin-top: 1px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cts-result:hover .cts-result-path,
.cts-result.cts-active .cts-result-path { color: #79bbff; }
</style>
