/**
 * 商品档案常量（V122 商品档案优化）。
 *
 * 商品类型库存数字码（goods_type VARCHAR 存字符串数字），页面统一显示中文：
 * 0 正常商品（可采可销）、1 赠品（可采可销）、2 设备辅材（可采不可销）、
 * 3 包装物（可采不可销）、4 兑换物（不可采不可销，仅允许采退销退）。
 * 旧中文类型（组合商品/服务商品）不迁移，goodsTypeLabel 兜底原样显示。
 */
export const GOODS_TYPES = [
  { value: '0', label: '正常商品' },
  { value: '1', label: '赠品' },
  { value: '2', label: '设备辅材' },
  { value: '3', label: '包装物' },
  { value: '4', label: '兑换物' },
]

/** 码 → 中文；未知值（如旧中文类型）原样返回，空值返回空串。 */
export function goodsTypeLabel(code) {
  if (code === null || code === undefined || code === '') return ''
  const key = String(code)
  const hit = GOODS_TYPES.find((t) => t.value === key)
  return hit ? hit.label : key
}

/** 中文名 → 码（导入模板里可能填中文）；无法识别时返回原值（交给后端归一化报错）。 */
export function goodsTypeCode(label) {
  if (label === null || label === undefined || label === '') return ''
  const key = String(label).trim()
  const hit = GOODS_TYPES.find((t) => t.label === key)
  return hit ? hit.value : key
}

/** 税率：库存纯数字 → 页面带 % 展示；空值按 0 处理。 */
export function taxRateText(value) {
  if (value === null || value === undefined || value === '') return '0%'
  return `${String(value).replace('%', '')}%`
}
