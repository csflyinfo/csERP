/**
 * 报表中心前端共享工具：数字展示、分组层级行构建、本地 xlsx 导出。
 * 纯函数，DrillGridReport 与五个报表页共用，保证「屏幕看到的层级」=「导出的层级」。
 */
import * as XLSX from 'xlsx'

/** 千分位 + 去尾零：数量最多 4 位小数，金额固定 2 位。 */
export function fmtNum(v, kind = 'qty') {
  if (v === null || v === undefined || v === '') return ''
  const n = Number(v)
  if (!Number.isFinite(n)) return String(v)
  const digits = kind === 'money' ? 2 : 4
  const fixed = n.toFixed(digits).replace(/\.?0+$/, '')
  const [int, dec] = fixed.split('.')
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return dec ? `${grouped}.${dec}` : grouped
}

/** 日期时间截短展示 */
export function fmtDateTime(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 19)
}

/**
 * 按分组键把叶子行整理成「组小计 + 叶子」有序序列（遇即建组，保持服务端返回顺序：
 * 汇总默认按净额降序，先遇到的组即贡献最大的组）。
 *
 * @returns Array<{kind:'group', level, key, value, values:Map, dims:Array<{key,value}>}>
 *                    |{kind:'leaf', level, row}>
 */
export function buildTreeRows(rows, groupKeys, numKeys) {
  if (!groupKeys || groupKeys.length === 0) {
    return rows.map(row => ({ kind: 'leaf', level: 0, row }))
  }
  // 每层：Map(keyValue → {entry, children:Map, leaves:[]})；服务端按净额降序，
  // Map 插入顺序即「组首次出现」顺序（贡献最大的组在前），建树后深度优先展平。
  const root = new Map()

  for (const row of rows) {
    let level = root
    let node = null
    const dims = []
    for (let i = 0; i < groupKeys.length; i++) {
      const key = groupKeys[i]
      const value = row[key] ?? ''
      dims.push({ key, value })
      node = level.get(value)
      if (!node) {
        node = { entry: { kind: 'group', level: i, key, value, dims: [...dims], sums: {} },
                 children: new Map(), leaves: [] }
        level.set(value, node)
      }
      accumulate(node.entry.sums, row, numKeys)
      level = node.children
    }
    node.leaves.push(row)
  }

  const out = []
  const walk = (levelMap, depth) => {
    for (const node of levelMap.values()) {
      out.push(node.entry)
      for (const row of node.leaves) out.push({ kind: 'leaf', level: depth + 1, row })
      walk(node.children, depth + 1)
    }
  }
  walk(root, 0)
  return out
}

function accumulate(sums, row, numKeys) {
  for (const k of numKeys) {
    const n = Number(row[k])
    if (Number.isFinite(n)) sums[k] = (sums[k] || 0) + n
  }
}

/**
 * 本地导出当前结果集为 xlsx（小数据量即时导出；大数据请走异步导出中心）。
 * 抬头 4 行：报表名 / 查询条件 / 生成时间 / 空行，第 5 行表头，与后端异步导出格式一致。
 */
export function exportRowsXlsx({ reportName, filterText, columns, treeRows, summary, fileName }) {
  const aoa = []
  aoa.push([reportName])
  aoa.push([filterText || ''])
  aoa.push([`生成时间：${new Date().toLocaleString('zh-CN', { hour12: false })}`])
  aoa.push([])
  aoa.push(columns.map(c => c.title))

  for (const item of treeRows) {
    if (item.kind === 'group') {
      const indent = '  '.repeat(item.level)
      const line = columns.map(c => {
        if (c.key === item.key) return `${indent}${item.value || '（空）'} 小计`
        if (c.num && item.sums[c.key] !== undefined) return round(item.sums[c.key], c)
        return ''
      })
      aoa.push(line)
    } else {
      aoa.push(columns.map(c => cellValue(item.row[c.key], c)))
    }
  }
  if (summary) {
    aoa.push(columns.map(c => {
      if (c.key === columns[0].key) return '合计'
      if (c.num && summary[c.key] !== undefined && summary[c.key] !== null) return round(summary[c.key], c)
      return ''
    }))
  }

  const ws = XLSX.utils.aoa_to_sheet(aoa)
  ws['!cols'] = columns.map(c => ({ wch: c.widthExcel || Math.max(10, Math.min(28, c.title.length * 2 + 4)) }))
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, (reportName || '报表').slice(0, 28))
  const stamp = XLSXReadableStamp()
  XLSX.writeFile(wb, fileName || `${reportName}_${stamp}.xlsx`)
}

function XLSXReadableStamp() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}${p(d.getMinutes())}`
}

function cellValue(v, col) {
  if (v === null || v === undefined) return ''
  if (col.num) return round(v, col)
  return v
}

function round(v, col) {
  const n = Number(v)
  if (!Number.isFinite(n)) return v
  return Number(n.toFixed(col.money ? 2 : 4))
}
