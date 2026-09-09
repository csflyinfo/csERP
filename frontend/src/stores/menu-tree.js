/**
 * 用户菜单树纯函数（PRD-28 卡片8）。无 Vue/Pinia 依赖，可被 node 直接单测。
 *
 * 后端 /system/menu/user-tree 节点形态（MenuMetaService#buildUserTree）：
 *   { code, name, path, menuType: 'DIR'|'PAGE', children: [] }
 * 整树深度 ≤ 3：一级目录 → 二级页面或子目录（如 财务管理 > 总账）→ 三级页面。
 */
import { fallbackMenus } from '../fallback-menus.js'

/** 收集树内全部节点编码（含目录与页面）。 */
export function collectCodes(nodes, out = new Set()) {
  for (const n of nodes || []) {
    if (n.code) out.add(n.code)
    if (Array.isArray(n.children)) collectCodes(n.children, out)
  }
  return out
}

/** 深度优先找到第一个 PAGE 节点的编码（点一级目录时跳其首个页面）。 */
export function firstPageCode(node) {
  if (!node) return null
  if (node.menuType === 'PAGE' || (!Array.isArray(node.children) || node.children.length === 0)) {
    return node.code || null
  }
  for (const child of node.children) {
    const code = firstPageCode(child)
    if (code) return code
  }
  return null
}

/** 找到某菜单编码所属的一级根节点。 */
export function rootOfCode(nodes, code) {
  for (const root of nodes || []) {
    if (root.code === code || collectCodes(root.children || []).has(code)) return root
  }
  return null
}

/** 递归按名称/编码模糊查找节点（快捷搜索用）。 */
export function findNode(nodes, keyword) {
  const kw = String(keyword || '').trim()
  if (!kw) return null
  for (const n of nodes || []) {
    if ((n.name && n.name.includes(kw)) || (n.code && n.code.toLowerCase().includes(kw.toLowerCase()))) return n
    const hit = findNode(n.children, kw)
    if (hit) return hit
  }
  return null
}

/** 前端历史 moduleCode（camelCase，如 goodsPriceAdjust）→ 路由路径。 */
export function moduleCodeToPath(code) {
  return '/' + String(code).replace(/([a-z0-9]|(?=[A-Z]))([A-Z])/g, '$1-$2').toLowerCase()
}

/**
 * 菜单接口失败时的本地降级树：由 fallback-menus.js 转换为与服务端一致的节点形态。
 * 根节点使用伪编码 '__fallback::<分类名>'，叶子保留原 moduleCode，
 * 跳转时 MENU_PATH 命中走映射、命不中按 moduleCode 转 kebab-path（AppShell.navigate 双路兼容）。
 */
export function buildFallbackTree() {
  return Object.entries(fallbackMenus).map(([top, items], idx) => ({
    code: `__fallback::${top}`,
    name: top,
    path: '',
    menuType: 'DIR',
    sortOrder: idx,
    children: items.map((item) => ({
      // 叶子保留 moduleCode（如 goodsPriceAdjust），跳转端 MENU_PATH 未命中时按 kebab 规则转路径
      code: item.code,
      name: item.name,
      path: moduleCodeToPath(item.code),
      menuType: 'PAGE',
      // adminOnly 项（模块菜单管理）由侧边栏按超管身份过滤
      adminOnly: !!item.adminOnly,
      children: [],
    })),
  }))
}
