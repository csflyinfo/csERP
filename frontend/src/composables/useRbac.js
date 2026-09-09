import { unref } from 'vue'
import { moduleConfigs } from '../module-config.js'
import { usePermStore } from '../stores/perm.js'

/**
 * 销售/采购模块的 RBAC 适配层（PRD-28 卡片6）。
 *
 * 三个职责：
 *  1. 前端 moduleCode（camelCase）→ 后端菜单码（点号），用于拼 <menu>.<action> 功能码；
 *  2. 列表中文列标题 → 敏感字段码（VIEW_*），用于整列显隐（值脱敏后端已做，前端按权限连列头一并收走）；
 *  3. 按钮文案（新建/审核/导出…）→ 功能码，供 v-permission / v-action-perms / 函数闸门。
 *
 * 只对 sales/purchase 模块生效；其余模块（库存/财务/基础档案/WMS/TMS）返回「不校验」，
 * 留待卡片7、9、10 接入。命中不到功能码时一律「不拦截」（不隐藏按钮），最终安全由后端 403 兜底。
 */

// 前端 moduleCode → 后端菜单码（与 @RequirePerm 的前缀严格一致）
const MODULE_MENU = {
  purchaseOrder: 'purchase.order',
  purchaseInbound: 'purchase.inbound',
  purchaseReceipt: 'purchase.receipt',
  purchaseReturnApply: 'purchase.return_apply',
  purchaseReturnOutbound: 'purchase.return_outbound',
  purchaseReturn: 'purchase.return_bill',
  purchaseExpense: 'purchase.fee',
  purchaseInvoice: 'purchase.invoice',
  salesOrder: 'sales.order',
  salesOutbound: 'sales.outbound',
  salesReceipt: 'sales.receipt',
  salesReturn: 'sales.return',
  salesReturnInbound: 'sales.return_inbound',
  rejectInbound: 'sales.reject_inbound',
  flyOrder: 'sales.flying',
  quickOrder: 'sales.quick',
  // 报表中心（卡片6：销售/采购报表是业务数据旁路出口，菜单码与 MenuConfig/后端注解一致）
  salesReport: 'report.sales',
  purchaseReport: 'report.purchase',
}

/**
 * 各菜单后端「真实存在」的功能点动作集合（权威来源：10 个 sales/purchase Controller 上
 * 的 @RequirePerm 注解）。文案映射出的码若不在此集合，按幽灵码丢弃（不拦截），
 * 避免把后端本就没有的动作（如 purchase.order.edit、purchase.fee.unaudit）对所有人隐藏。
 * global.* 全局点不经过此白名单。
 */
const MENU_ACTION_SET = {
  'purchase.order': ['add', 'view', 'audit', 'unaudit', 'close', 'delete'],
  'purchase.inbound': ['add', 'view', 'audit'],
  'purchase.receipt': ['view', 'audit', 'unaudit', 'edit'],
  'purchase.return_apply': ['view', 'add', 'edit', 'audit', 'unaudit', 'delete'],
  'purchase.return_outbound': ['view', 'edit', 'audit'],
  'purchase.return_bill': ['view', 'audit', 'unaudit'],
  'purchase.fee': ['view', 'audit'],
  'purchase.invoice': ['view', 'add', 'edit', 'audit', 'unaudit', 'close', 'delete', 'biz_certify', 'biz_report'],
  'sales.order': ['add', 'view', 'edit', 'audit', 'unaudit', 'close', 'delete'],
  'sales.outbound': ['view', 'add', 'edit', 'audit'],
  'sales.receipt': ['view', 'audit', 'unaudit', 'biz_sign', 'biz_unsign'],
  'sales.return': ['view', 'add', 'edit', 'biz_confirm', 'biz_reject', 'biz_push_warehouse', 'biz_cancel_push', 'biz_change_type', 'audit', 'unaudit', 'delete'],
  'sales.return_inbound': ['view', 'edit', 'audit', 'biz_sync_wms_tasks'],
  'sales.reject_inbound': ['view', 'edit', 'audit', 'unaudit'],
  'sales.flying': ['add', 'edit', 'view', 'audit', 'unaudit', 'close', 'delete', 'export'],
  'sales.quick': ['add'],
  // 报表只读：导出按钮走 global.export（codesForAction 特例），不落模块点
  'report.sales': ['view'],
  'report.purchase': ['view'],
}

// 模块级特殊业务动作（biz_*）：[按钮文案正则, action]，命中优先于标准动作
const BIZ_FUNCS = {
  'sales.receipt': [
    [/反签收|取消签收|撤销签收/, 'biz_unsign'],
    [/签收/, 'biz_sign'],
  ],
  'sales.return': [
    [/取消推送|撤销推送/, 'biz_cancel_push'],
    [/推送仓库|推送WMS|推送 WMS/, 'biz_push_warehouse'],
    [/变更.*方式|切换.*方式|改退货方式|改为(自提|司机)/, 'biz_change_type'],
    [/拒收|驳回/, 'biz_reject'],
    [/确认退货|^确认$/, 'biz_confirm'],
  ],
  'sales.return_inbound': [
    [/同步.*WMS|同步.*仓库|sync/i, 'biz_sync_wms_tasks'],
  ],
  'purchase.invoice': [
    [/认证/, 'biz_certify'],
  ],
}

/**
 * 跨模块/复合动作特判（先于 biz 与标准动作）：
 * 「生成出库单」在销售订单页露出但请求落到出库模块；「改价/审核」任一点满足即可见。
 */
const ACTION_OVERRIDES = {
  'sales.order': [
    [/生成出库单/, ['sales.outbound.add']],
  ],
  'purchase.order': [
    [/生成入库单/, ['purchase.inbound.add']],
  ],
  'purchase.receipt': [
    // 「改价/审核」打开抽屉改价（receipt.edit）并可直接审核（receipt.audit），任一即可见
    [/改价/, ['purchase.receipt.edit', 'purchase.receipt.audit']],
  ],
  'purchase.return_outbound': [
    // UI 唯一写入口「确认出库」实为编辑实发数量后保存（update）
    [/确认出库/, ['purchase.return_outbound.edit']],
  ],
  'purchase.return_apply': [
    // 「保存并提交审核」是制单提交（后端走 create/update，add/edit 点），不是审核动作本身；
    // 不能让 /审核/ 抢先映射成 audit，否则制单员提交入口被误隐藏
    [/提交审核/, ['purchase.return_apply.add', 'purchase.return_apply.edit']],
  ],
}

// 标准动作（按文案关键词，顺序即优先级）
const STANDARD_ACTIONS = [
  [/反审核|取消审核|撤销审核/, 'unaudit'],
  [/审核并打印|审核/, 'audit'],
  [/作废|关闭|终止/, 'close'],
  [/删除/, 'delete'],
  [/编辑|修改/, 'edit'],
  [/新建|新增|挂单|保存草稿|添加|引入|开单|常购|生成.*单/, 'add'],
]

/** 模块是否纳入本卡 RBAC（销售/采购） */
export function isRbacModule(moduleCode) {
  return !!MODULE_MENU[moduleCode]
}

/** 列标题 → 敏感字段码；非敏感列返回 null（始终展示）。 */
export function fieldForColumn(moduleCode, title) {
  if (!title || !isRbacModule(moduleCode)) return null
  const t = String(title)
  const type = moduleConfigs[moduleCode]?.type

  // 状态类列（即使名字含应收/应付）不是金额，不脱敏
  if (/状态/.test(t)) return null

  // 成本与毛利（跨模块语义固定）
  if (/成本单价|单位成本/.test(t)) return 'VIEW_COST'
  if (/成本金额|库存金额/.test(t)) return 'VIEW_COST_AMOUNT'
  // 兜底：其余含「成本」的列（如快速开单行内「成本」）归成本单价视角
  if (/成本/.test(t)) return 'VIEW_COST'
  if (/毛利/.test(t)) return 'VIEW_PROFIT'

  // 往来款（先于通用金额判定，避免被销售/采购金额吞掉）
  if (/已收金额|未收金额|应收余额|应收金额/.test(t)) return 'VIEW_AR_BALANCE'
  if (/已付金额|未付金额|应付余额|应付金额/.test(t)) return 'VIEW_AP_BALANCE'

  // 具名采购价/销售价
  if (/采购价|参考进价|进价|采购单价/.test(t)) return 'VIEW_PURCHASE_PRICE'
  // 折扣本质是价格让利率，与销售单价同视角
  if (/销售价|售价|销售单价|标准售价|折扣/.test(t)) return 'VIEW_SALE_PRICE'
  if (/采购金额/.test(t)) return 'VIEW_PURCHASE_AMOUNT'
  if (/销售金额/.test(t)) return 'VIEW_SALE_AMOUNT'

  // 采购发票：金额/税额/来票属应付往来视角
  if (moduleCode === 'purchaseInvoice' && /金额|税额|不含税/.test(t)) return 'VIEW_AP_BALANCE'
  // 采购收货单：来票/开票金额是应付往来信息（与发票、后端 PURCHASE_BILL profile 同口径）
  if (moduleCode === 'purchaseReceipt' && /开票|来票/.test(t)) return 'VIEW_AP_BALANCE'
  // 采购报表的入库金额（type=report 不进泛化采购金额分支，显式声明同口径）
  if (moduleCode === 'purchaseReport' && /入库金额/.test(t)) return 'VIEW_PURCHASE_AMOUNT'

  // 泛化单价 / 金额按业务类型归属
  if (/单价|原价|现价/.test(t)) {
    return type === 'purchase' ? 'VIEW_PURCHASE_PRICE' : 'VIEW_SALE_PRICE'
  }
  if (/金额|税额|不含税/.test(t)) {
    if (type === 'purchase') return 'VIEW_PURCHASE_AMOUNT'
    if (type === 'sales') return 'VIEW_SALE_AMOUNT'
  }

  // 快速开单的可用库存属库存数量敏感列
  if (moduleCode === 'quickOrder' && /可用库存/.test(t)) return 'VIEW_STOCK_AMOUNT'
  return null
}

/** 校验模块动作码真实存在；global.* 与非本卡模块不校验 */
function resolveCodes(menu, rawCodes) {
  const codes = Array.isArray(rawCodes) ? rawCodes : [rawCodes]
  if (!menu) return codes.filter(Boolean)
  const allowed = MENU_ACTION_SET[menu]
  if (!allowed) return []
  return codes.filter(c => c && allowed.includes(c))
}

/**
 * 按钮文案 → 完整功能码数组（多码=任一满足即放行）；映射不到或幽灵码过滤后为空返回 null。
 * 导入/导出/打印走全局功能点（飞单导出是模块点，单独处理）。
 */
export function codesForAction(moduleCode, label) {
  if (!label || !isRbacModule(moduleCode)) return null
  const menu = MODULE_MENU[moduleCode]
  const text = String(label)

  if (/导入/.test(text)) return ['global.import']
  // 「审核并打印」主语义是审核（先过审核接口），打印判定让位于 audit
  if (/打印/.test(text) && !/审核/.test(text)) return ['global.print']
  if (/导出/.test(text)) {
    return moduleCode === 'flyOrder' ? ['sales.flying.export'] : ['global.export']
  }

  // 快速开单：创建即审核（/sales/quick-order/create-and-audit），后端无独立 audit 点，
  // 「审核并打印」等审核语义统一归 sales.quick.add
  if (moduleCode === 'quickOrder' && /审核/.test(text)) return ['sales.quick.add']

  // 跨模块/复合动作特判（码已经过人工核实，仍走白名单过滤防漂移）
  const override = ACTION_OVERRIDES[menu]
  if (override) {
    for (const [re, codes] of override) {
      if (re.test(text)) {
        const resolved = resolveCodes(menu, codes)
        if (resolved.length) return resolved
      }
    }
  }

  // biz_* 特殊动作优先
  const biz = BIZ_FUNCS[menu]
  if (biz) {
    for (const [re, action] of biz) {
      if (re.test(text)) {
        const resolved = resolveCodes(menu, `${menu}.${action}`)
        if (resolved.length) return resolved
      }
    }
  }
  // 标准动作
  for (const [re, action] of STANDARD_ACTIONS) {
    if (re.test(text)) {
      const resolved = resolveCodes(menu, `${menu}.${action}`)
      if (resolved.length) return resolved
      // 标准动作在该模块不存在（幽灵码）：不拦截，维持该入口既有可用性（后端无对应端点）
      return null
    }
  }
  return null
}

/** 单码便捷版（v-permission 单按钮）：取第一个码，无映射返回 undefined。 */
export function funcForAction(moduleCode, label) {
  return codesForAction(moduleCode, label)?.[0]
}

/**
 * 组件内使用：返回响应式判定函数（背后是 pinia perm store）。
 * @param {string|import('vue').Ref<string>|ComputedRef<string>} [moduleRef]
 *        可选绑定当前模块码；绑定后可用 canView/actionHidden/guard 便捷方法，
 *        不传则只用显式带 moduleCode 参数的方法（GenericBusinessList 两者都用）。
 */
export function useRbac(moduleRef) {
  const perm = usePermStore()
  const mod = () => unref(moduleRef) || ''
  return {
    isRbacModule,
    fieldForColumn,
    codesForAction,
    funcForAction,
    /** 该列在当前用户下是否可见（非敏感列或非 RBAC 模块恒可见）。 */
    canViewColumn(moduleCode, title) {
      const field = fieldForColumn(moduleCode, title)
      return field ? perm.canViewField(field) : true
    },
    /** 绑定模块版列判定：canView('单价') === canViewColumn(mod(), '单价')。 */
    canView(title) {
      const field = fieldForColumn(mod(), title)
      return field ? perm.canViewField(field) : true
    },
    /** 按钮 v-permission 用：返回功能码或 undefined（指令对 undefined 不校验）。 */
    permOf(moduleCode, label) {
      return funcForAction(moduleCode, label) ?? undefined
    },
    /** 多码版：供 v-permission 数组绑定（任一满足即可见）。 */
    permCodesFor(label) {
      return codesForAction(mod(), label) ?? undefined
    },
    /**
     * v-action-perms 容器指令裁决（绑定模块）：label 映射不到功能点时不拦截。
     * 返回 true = 当前用户无权（多码任一不满足全无时隐藏）、按钮应隐藏。
     */
    actionHidden(label) {
      const codes = codesForAction(mod(), String(label || '').trim())
      return codes ? !perm.hasAnyFunc(codes) : false
    },
    /**
     * 写操作处理函数入口闸门（绑定模块）：无权返回 false（调用方应中止并提示），
     * 无功能点映射或多码任一满足时返回 true。最终安全仍由后端 403 兜底。
     */
    guard(label) {
      const codes = codesForAction(mod(), String(label || '').trim())
      return !codes || perm.hasAnyFunc(codes)
    },
    /** 显式功能点码闸门（按钮文案无法映射时直接用码）。 */
    guardCode(code) {
      return perm.hasFunc(code)
    },
  }
}
