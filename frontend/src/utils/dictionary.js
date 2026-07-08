// 字典公共取值方法（带内存缓存）
// 规范：docs/PRD-版本化产品需求/00-总览与规范/规范-日期时间字段格式.md 同级
// 使用：
//   import { getDict, invalidateDict } from '@/utils/dictionary'
//   const list = await getDict('delivery_method')   // [{ code, name, sortOrder }]
//   invalidateDict('delivery_method')               // 强制刷新缓存
//   invalidateDict()                                // 清空全部缓存

import { get } from '../api/client.js'

const cache = new Map()          // type -> Promise<Array>
const CACHE_TTL_MS = 5 * 60_000   // 5 分钟

/**
 * 取字典值（只返回 status=NORMAL 的，停用的自动过滤）
 * @param {string} type 字典类型编码
 * @param {{ force?: boolean }} [opts] force=true 时忽略缓存直取
 * @returns {Promise<Array<{code: string, name: string, sortOrder: number}>>}
 */
export async function getDict(type, opts = {}) {
  if (!type) return []
  const cached = cache.get(type)
  const now = Date.now()
  if (!opts.force && cached && (now - cached.ts) < CACHE_TTL_MS) {
    return cached.promise
  }
  const promise = get(`/base/dictionary?type=${encodeURIComponent(type)}`)
    .then(data => (Array.isArray(data) ? data : []).map(d => ({
      code: d.code || d.CODE || '',
      name: d.name || d.NAME || d.code || d.CODE || '',
      sortOrder: Number(d.sortOrder || d.SORT_ORDER || 0),
    })))
    .catch(() => [])
  cache.set(type, { promise, ts: now })
  return promise
}

/**
 * 使字典类型的缓存失效；不传 type 则清全部。字典维护后调用。
 */
export function invalidateDict(type) {
  if (type) cache.delete(type)
  else cache.clear()
}
