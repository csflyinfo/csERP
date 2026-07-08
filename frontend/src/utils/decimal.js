/**
 * 小数精度工具
 *
 * 项目里「单价 4 位 / 金额 2 位 / 成本 6 位」的截断逻辑原先散落在各组件内联重复，
 * 本文件把它抽成公共函数，供新代码统一调用。
 *
 * 注意：既有组件（GoodsAddDialog 等）的内联实现暂不改动，避免牵动已上线逻辑；
 * 新增代码请一律用这里的函数。
 */

/** 单价：最多 4 位小数（CLAUDE.md 规范） */
export const PRICE_DECIMALS = 4
/** 金额：2 位小数 */
export const AMOUNT_DECIMALS = 2
/** 成本单价：6 位小数 */
export const COST_DECIMALS = 6
/** 称重品数量：最多 3 位小数 */
export const WEIGHTED_QTY_DECIMALS = 3

/**
 * 截断输入串的小数位，供 input 事件实时纠正用户输入。
 *
 * 只保留数字与小数点，多余的小数点丢弃，小数位超出则截断。
 * `maxDecimals = 0` 表示只允许整数（非称重品数量场景）。
 *
 * @param {string|number} raw 用户输入的原始值
 * @param {number} maxDecimals 允许的最大小数位；0 = 仅整数
 * @returns {{ text: string, value: number|null }}
 *          text  —— 回写到输入框的字符串（保留用户正在输入的尾随小数点）
 *          value —— 解析后的数值；空串时为 null
 */
export function clampDecimalInput(raw, maxDecimals = PRICE_DECIMALS) {
  let v = String(raw ?? '').replace(/[^\d.]/g, '')

  if (maxDecimals <= 0) {
    // 仅整数：直接去掉小数点及其后内容
    v = v.replace(/\./g, '')
  } else {
    const dot = v.indexOf('.')
    if (dot >= 0) {
      // 首个小数点保留，后续小数点删除，小数位截断到 maxDecimals
      const intPart = v.slice(0, dot)
      const decPart = v.slice(dot + 1).replace(/\./g, '').slice(0, maxDecimals)
      v = intPart + '.' + decPart
    }
  }

  // 尾随小数点（如 "12."）保留在 text 里，让用户能继续输入，但 value 取整数部分
  return { text: v, value: v === '' || v === '.' ? null : Number(v) }
}

/**
 * 按数量精度规则截断：称重品允许 3 位小数，非称重品只能整数。
 *
 * @param {string|number} raw 输入值
 * @param {boolean} isWeighted 是否称重品
 */
export function clampQtyInput(raw, isWeighted) {
  return clampDecimalInput(raw, isWeighted ? WEIGHTED_QTY_DECIMALS : 0)
}

/**
 * 数值四舍五入到指定小数位（用于计算结果，而非用户输入）。
 * 用 Number.EPSILON 修正浮点误差，避免 1.005 → 1.00 这类问题。
 */
export function roundTo(value, decimals = AMOUNT_DECIMALS) {
  const n = Number(value)
  if (!Number.isFinite(n)) return 0
  const f = Math.pow(10, decimals)
  return Math.round((n + Number.EPSILON) * f) / f
}
