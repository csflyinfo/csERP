import { ref, nextTick, onBeforeUnmount } from 'vue'

/**
 * Teleport 到 body 的 fixed 下拉面板定位与生命周期。
 *
 * 适用场景：面板挂在 body 下（脱离 overflow:auto 的抽屉/弹窗滚动容器，避免被裁剪），
 * 视觉上仍锚定控件：打开时按控件 getBoundingClientRect 算 fixed 坐标，
 * 捕获阶段监听页面滚动与窗口缩放（rAF 节流）实时跟随；
 * 下方空间不足时自动向上展开。
 *
 * 用法：
 *   const { open, panelStyle, controlRef, panelRef, show, hide, toggle } =
 *     useAnchoredPanel({ minWidth: 240 })
 *   // 控件：<div ref="controlRef" @click="toggle(() => inputRef.focus())">
 *   // 面板：<Teleport to="body"><div v-if="open" ref="panelRef" :style="panelStyle" @mousedown.stop>
 *
 * 注意：面板根节点要加 @mousedown.stop，否则面板内点击会被宿主组件的
 *「点击外部关闭」document 监听误判。
 */
export function useAnchoredPanel(options = {}) {
  const {
    minWidth = 220,
    maxHeight = 340,
    minHeight = 160,
    gap = 4,
    viewportPadding = 8,
  } = options

  const open = ref(false)
  const panelStyle = ref({})
  const controlRef = ref(null)
  const panelRef = ref(null)
  let rafPending = false

  /** 按控件在视口中的位置重算面板坐标；下方不够向上展开；宽度不窄于 minWidth 且不出视口 */
  function updatePanelPos() {
    const ctl = controlRef.value?.getBoundingClientRect()
    if (!ctl || ctl.width === 0) return
    const vw = window.innerWidth
    const vh = window.innerHeight
    const below = vh - ctl.bottom - gap - viewportPadding
    const above = ctl.top - gap - viewportPadding
    const preferBelow = below >= 200 || below >= above
    const avail = Math.max(minHeight, Math.min(maxHeight, preferBelow ? below : above))
    const width = Math.min(Math.max(ctl.width, minWidth), vw - viewportPadding * 2)
    let left = ctl.left
    if (left + width > vw - viewportPadding) left = vw - viewportPadding - width
    if (left < viewportPadding) left = viewportPadding
    const s = { left: `${left}px`, width: `${width}px`, maxHeight: `${avail}px` }
    if (preferBelow) s.top = `${ctl.bottom + gap}px`
    else s.bottom = `${vh - ctl.top + gap}px`
    panelStyle.value = s
  }

  function schedulePos() {
    if (rafPending) return
    rafPending = true
    requestAnimationFrame(() => {
      rafPending = false
      if (open.value) updatePanelPos()
    })
  }

  function bindListeners() {
    document.addEventListener('scroll', schedulePos, true)
    window.addEventListener('resize', schedulePos)
  }
  function unbindListeners() {
    document.removeEventListener('scroll', schedulePos, true)
    window.removeEventListener('resize', schedulePos)
  }

  /** @param afterShow nextTick 定位完成后的回调（通常用来聚焦搜索框） */
  function show(afterShow) {
    if (open.value) return
    open.value = true
    nextTick(() => {
      updatePanelPos()
      afterShow?.()
    })
    bindListeners()
  }

  function hide() {
    if (!open.value) return
    open.value = false
    unbindListeners()
  }

  function toggle(afterShow) {
    if (open.value) hide()
    else show(afterShow)
  }

  onBeforeUnmount(unbindListeners)

  return { open, panelStyle, controlRef, panelRef, updatePanelPos, show, hide, toggle }
}
