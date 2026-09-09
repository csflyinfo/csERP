import { usePermStore } from '../stores/perm.js'

/**
 * v-permission 功能点指令（PRD-28，卡片6）。
 *
 * 用法：
 *   v-permission="'sales.order.add'"                 // 无权限直接移除元素
 *   v-permission="['sales.order.audit','global.audit']" // 任一命中即可见
 *   v-permission="{ code: 'sales.order.delete', disable: true }" // 无权限改为禁用（保留布局）
 *
 * 权限集由路由守卫在进页面前 ensure 完成，挂载时即可判定；超管在 store 内短路放行。
 */
function normalize(binding) {
  const v = binding.value
  if (v == null) return { codes: [], disable: false }
  if (typeof v === 'string') return { codes: [v], disable: false }
  if (Array.isArray(v)) return { codes: v, disable: false }
  const codes = []
  if (v.code) codes.push(v.code)
  if (Array.isArray(v.codes)) codes.push(...v.codes)
  return { codes, disable: !!v.disable }
}

function apply(el, binding) {
  // 已被移除的元素（无父节点）无需再判
  if (!el.parentNode) return
  const { codes, disable } = normalize(binding)
  if (codes.length === 0) return
  const ok = usePermStore().hasAnyFunc(codes)
  if (ok) {
    if (disable) {
      el.removeAttribute('disabled')
      el.classList.remove('is-perm-disabled')
    }
    return
  }
  if (disable) {
    el.setAttribute('disabled', 'disabled')
    el.classList.add('is-perm-disabled')
  } else {
    el.parentNode.removeChild(el)
  }
}

export const permissionDirective = {
  mounted: apply,
  updated: apply,
}

/**
 * v-action-perms 容器型功能点指令（PRD-28 卡片6）。
 *
 * 用于一个容器内存在大量「按文案分发」的按钮（如 GenericBusinessList 的顶部
 * 操作区、行内操作列、批量浮动栏），不便逐个挂 v-permission 的场景。指令扫描
 * 容器内全部 <button>，以按钮文本为参数调用绑定的裁决函数：
 *
 *   v-action-perms="actionHidden"
 *   // actionHidden(label: string): boolean  —— 返回 true 则隐藏该按钮
 *
 * 只隐藏、不移除：Vue 的 v-if 状态切换会复用/重建按钮，updated 时全量重算即可，
 * 且内联 display 优先级最高，不受组件 CSS 影响。弹窗（关闭/取消/保存等）不要挂
 * 此指令，避免与业务动作文案撞车。
 */
function syncActionPerms(el, resolver) {
  if (typeof resolver !== 'function') return
  el.querySelectorAll('button').forEach((btn) => {
    const label = (btn.textContent || '').trim()
    if (!label) { btn.style.display = ''; return }
    btn.style.display = resolver(label, btn) === true ? 'none' : ''
  })
}

export const actionPermsDirective = {
  mounted(el, binding) { syncActionPerms(el, binding.value) },
  updated(el, binding) { syncActionPerms(el, binding.value) },
}
