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
