import { usePermStore } from '../stores/perm.js'

/**
 * 功能点 / 敏感字段权限组合式（PRD-28，卡片6）。
 * 模板里控制按钮显隐优先用 v-permission 指令；脚本分支（如是否拼某列、是否带某操作）用本函数。
 *
 * @example
 * const { hasFunc, canViewField } = usePerm()
 * hasFunc('sales.order.add')
 * canViewField('VIEW_SALE_PRICE')
 */
export function usePerm() {
  const perm = usePermStore()
  return {
    perm,
    hasFunc: (code) => perm.hasFunc(code),
    hasAnyFunc: (codes) => perm.hasAnyFunc(codes),
    canViewField: (code) => perm.canViewField(code),
    ensurePerm: (force) => perm.ensure(force),
  }
}

/**
 * 敏感字段权限组合式（PRD-28 卡片8 命名收口）：与 usePerm 同源，
 * 只暴露字段判定，供只关心列脱敏的页面使用。
 *
 * @example
 * const { canViewField } = useFieldPerm()
 * if (!canViewField('VIEW_SALE_PRICE')) col.hidden = true
 */
export function useFieldPerm() {
  const perm = usePermStore()
  return {
    canViewField: (code) => perm.canViewField(code),
    /** 多字段码并集：任一可见即放行（与 useRbac.fieldForColumn 数组语义一致）；空数组不校验 */
    canViewAnyField: (codes) => !codes || codes.length === 0 || codes.some((c) => perm.canViewField(c)),
  }
}
