// 日期时间格式化：全局统一
// 规范：docs/PRD-版本化产品需求/00-总览与规范/规范-日期时间字段格式.md
// - 时刻 → 'YYYY-MM-DD HH:mm:ss'
// - 日期 → 'YYYY-MM-DD'

function pad2(n) { return String(n).padStart(2, '0') }

/** 兼容多种输入解析成 Date；无法解析或空值返回 null */
function toDate(v) {
  if (v == null || v === '') return null
  if (v instanceof Date) return isNaN(v.getTime()) ? null : v
  if (typeof v === 'number') {
    const d = new Date(v)
    return isNaN(d.getTime()) ? null : d
  }
  if (typeof v === 'string') {
    const s = v.trim()
    if (!s) return null
    // 已经是 'YYYY-MM-DD HH:mm:ss' 或 'YYYY-MM-DD' 或 ISO 带 T
    // 优先尝试原生解析（现代浏览器支持含 T 或不含 T 的 ISO 变体）
    // 但 'YYYY-MM-DD HH:mm:ss'（带空格）Safari 老版本会 NaN；手动兜底
    const iso = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?/.exec(s)
    if (iso) {
      const [, y, m, d, hh, mm, ss] = iso
      return new Date(Number(y), Number(m) - 1, Number(d), Number(hh), Number(mm), Number(ss))
    }
    const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s)
    if (dateOnly) {
      const [, y, m, d] = dateOnly
      return new Date(Number(y), Number(m) - 1, Number(d))
    }
    const t = Date.parse(s)
    if (!isNaN(t)) return new Date(t)
    return null
  }
  return null
}

/** 时刻：'YYYY-MM-DD HH:mm:ss' */
export function formatDateTime(v) {
  const d = toDate(v)
  if (!d) return ''
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}

/** 日期：'YYYY-MM-DD' */
export function formatDate(v) {
  const d = toDate(v)
  if (!d) return ''
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

/**
 * 按列表表头文案自动选择格式化：
 * - 表头含「时间」 → 时刻
 * - 表头含「日期」 → 日期
 * - 其它 → 原值返回
 */
export function autoFormatByTitle(title, value) {
  if (value == null || value === '') return value
  if (!title) return value
  if (/时间/.test(title)) return formatDateTime(value)
  if (/日期/.test(title)) return formatDate(value)
  return value
}
