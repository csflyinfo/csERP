import { computed, ref, watch } from 'vue'

/**
 * 表格列设置 composable —— 提供列可见性、顺序、宽度、固定列的统一管理。
 *
 * 使用方式：
 *   const {
 *     visibleColumns, columnSettings, pendingSettings, dialogColumnList,
 *     dialogOpen, openDialog, saveSettings, resetSettings, moveInDialog,
 *     onHeaderDragStart, onHeaderDragOver, onHeaderDrop,
 *     startResize, cellStyle, draggingKey,
 *   } = useColumnSettings({
 *     storageKey: () => `erp-field-setting-v2:module:${moduleCode.value}`,
 *     allColumns: computed(() => [...]),   // { key, title, width?, align?, num?, action? }
 *   })
 *
 * 存储结构（localStorage）：
 *   { [colKey]: { visible: boolean, width: number, fixed: boolean, order: number } }
 *
 * 兼容早期 v1 格式（{ [colKey]: boolean }），自动升级为完整结构。
 */
export function useColumnSettings({ storageKey, allColumns, leftBaseOffset }) {
  const columnSettings = ref({})    // 已生效（用于渲染）
  const pendingSettings = ref({})   // 弹窗草稿
  const dialogOpen = ref(false)
  const draggingKey = ref('')

  function currentKey() {
    return typeof storageKey === 'function' ? storageKey() : storageKey
  }

  /** 第一个固定列的 left 起点：如果表格有 sticky 勾选列，从 40px 开始，避免视觉重叠 */
  function currentLeftBase() {
    if (typeof leftBaseOffset === 'function') return Number(leftBaseOffset()) || 0
    return Number(leftBaseOffset) || 0
  }

  function makeDefaultSettings(cols) {
    return Object.fromEntries(cols.map((c, i) => [c.key, {
      visible: true,
      width: c.width || 100,
      fixed: false,
      order: i,
    }]))
  }

  function loadSettings() {
    const key = currentKey()
    if (!key) return
    const saved = localStorage.getItem(key)
    const defaults = makeDefaultSettings(allColumns.value)
    if (!saved) {
      columnSettings.value = defaults
      return
    }
    try {
      const parsed = JSON.parse(saved)
      const merged = {}
      allColumns.value.forEach((c, i) => {
        const raw = parsed[c.key]
        if (raw && typeof raw === 'object') {
          merged[c.key] = {
            visible: raw.visible !== false,
            width: Number(raw.width) || c.width || 100,
            fixed: !!raw.fixed,
            order: raw.order != null ? Number(raw.order) : i,
          }
        } else if (raw === false) {
          merged[c.key] = { ...defaults[c.key], visible: false }
        } else {
          merged[c.key] = defaults[c.key]
        }
      })
      columnSettings.value = merged
    } catch (e) {
      columnSettings.value = defaults
    }
  }

  function openDialog() {
    pendingSettings.value = JSON.parse(JSON.stringify(columnSettings.value))
    dialogOpen.value = true
  }

  function closeDialog() {
    dialogOpen.value = false
  }

  function saveSettings() {
    columnSettings.value = JSON.parse(JSON.stringify(pendingSettings.value))
    const key = currentKey()
    if (key) localStorage.setItem(key, JSON.stringify(columnSettings.value))
    dialogOpen.value = false
  }

  function resetSettings() {
    pendingSettings.value = makeDefaultSettings(allColumns.value)
  }

  function moveInDialog(colKey, dir) {
    const arr = Object.entries(pendingSettings.value)
        .sort(([, a], [, b]) => (a.order || 0) - (b.order || 0))
        .map(([k]) => k)
    const i = arr.indexOf(colKey)
    const j = i + dir
    if (i < 0 || j < 0 || j >= arr.length) return
    ;[arr[i], arr[j]] = [arr[j], arr[i]]
    arr.forEach((k, idx) => { pendingSettings.value[k].order = idx })
  }

  // 生效的可见列 —— 按 fixed 优先 + order 升序，并计算每个固定列的 left 偏移量
  const visibleColumns = computed(() => {
    const s = columnSettings.value
    const sorted = allColumns.value
        .filter(c => c.action || (s[c.key] && s[c.key].visible !== false))
        .map(c => ({
          ...c,
          width: (s[c.key] && s[c.key].width) || c.width || 100,
          fixed: c.action ? false : !!(s[c.key] && s[c.key].fixed),
          order: (s[c.key] && s[c.key].order) != null ? s[c.key].order : 999,
        }))
        .sort((a, b) => {
          if (a.fixed !== b.fixed) return a.fixed ? -1 : 1
          if (a.action !== b.action) return a.action ? 1 : -1
          return (a.order || 0) - (b.order || 0)
        })
    let leftOffset = currentLeftBase()
    let lastFixedKey = ''
    sorted.forEach(col => {
      if (col.fixed) {
        col.stickyLeft = leftOffset
        leftOffset += col.width
        lastFixedKey = col.key
      }
    })
    sorted.forEach(col => { col.isLastFixed = col.fixed && col.key === lastFixedKey })
    return sorted
  })

  // 弹窗里排好序的字段列表（操作列不允许调）
  const dialogColumnList = computed(() => {
    const s = pendingSettings.value
    return allColumns.value
        .filter(c => !c.action)
        .slice()
        .sort((a, b) => {
          const oa = s[a.key]?.order ?? 999
          const ob = s[b.key]?.order ?? 999
          return oa - ob
        })
  })

  // ==================== 表头拖拽 ====================
  function onHeaderDragStart(e, col) {
    if (col.fixed || col.action) { e.preventDefault(); return }
    draggingKey.value = col.key
    e.dataTransfer.effectAllowed = 'move'
  }
  function onHeaderDragOver(e) {
    e.preventDefault()
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
  }
  function onHeaderDrop(e, targetCol) {
    e.preventDefault()
    const srcKey = draggingKey.value
    draggingKey.value = ''
    if (!srcKey || srcKey === targetCol.key || targetCol.fixed || targetCol.action) return
    const s = columnSettings.value
    const sorted = Object.keys(s)
        .filter(k => !allColumns.value.find(c => c.key === k)?.action)
        .sort((a, b) => (s[a].order || 0) - (s[b].order || 0))
    const src = sorted.indexOf(srcKey)
    if (src < 0 || !sorted.includes(targetCol.key)) return
    sorted.splice(src, 1)
    sorted.splice(sorted.indexOf(targetCol.key), 0, srcKey)
    sorted.forEach((k, idx) => { s[k].order = idx })
  }

  // ==================== 列宽拖拽 ====================
  let resizeState = null
  function startResize(e, col) {
    resizeState = {
      key: col.key,
      startX: e.clientX,
      startWidth: visibleColumns.value.find(c => c.key === col.key)?.width || 100,
    }
    document.addEventListener('mousemove', onResizing)
    document.addEventListener('mouseup', endResize)
    e.preventDefault()
    e.stopPropagation()   // 避免触发 th 的拖拽排序
  }
  function onResizing(e) {
    if (!resizeState) return
    const w = Math.max(60, resizeState.startWidth + (e.clientX - resizeState.startX))
    const s = columnSettings.value
    if (s[resizeState.key]) s[resizeState.key].width = w
  }
  function endResize() {
    resizeState = null
    document.removeEventListener('mousemove', onResizing)
    document.removeEventListener('mouseup', endResize)
  }

  // ==================== th/td 内联样式 ====================
  /**
   * 生成 th/td 的内联样式：宽度 + 对齐 + 固定列 sticky 定位。
   *
   * @param {'th'|'td'|'tf'} type 用于分配 z-index / background
   */
  function cellStyle(col, type = 'td') {
    const s = {
      width: col.width + 'px',
      minWidth: col.width + 'px',
      textAlign: col.align || 'left',
    }
    if (col.fixed) {
      s.position = 'sticky'
      s.left = col.stickyLeft + 'px'
      // thead 固定列 z-index 最高（4）> tfoot 固定列（3）> 普通 th sticky top（2）> tbody 固定列（1）> 普通 td（0）
      s.zIndex = type === 'th' ? 4 : type === 'tf' ? 3 : 1
      s.background = type === 'tf' ? '#fafbfc' : type === 'th' ? '#f5f7fa' : '#fff'
    }
    return s
  }

  // 初始加载 + 关键依赖变化时刷新
  loadSettings()
  watch(() => currentKey(), loadSettings)
  // allColumns 变化：如果 columnSettings 为空（初次到达）或有新增列，重新加载
  watch(() => allColumns.value?.length || 0, (n, old) => {
    if (n === 0) return
    const settingKeys = Object.keys(columnSettings.value)
    if (settingKeys.length === 0) {
      loadSettings()
      return
    }
    // 已有部分设置：合并新列的默认值
    const defaults = makeDefaultSettings(allColumns.value)
    Object.keys(defaults).forEach(k => {
      if (!columnSettings.value[k]) columnSettings.value[k] = defaults[k]
    })
  }, { immediate: true })

  return {
    columnSettings, pendingSettings, visibleColumns, dialogColumnList,
    dialogOpen, draggingKey,
    openDialog, closeDialog, saveSettings, resetSettings, moveInDialog,
    onHeaderDragStart, onHeaderDragOver, onHeaderDrop,
    startResize, cellStyle,
  }
}
