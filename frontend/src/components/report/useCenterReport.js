/**
 * 报表中心页面共享状态：K2 默认期间、统一入参 {dateRange, filters, groupBy, pageNo...}、
 * 查询/翻页/排序/异步导出。日期每次回默认，非日期条件由页面自行 localStorage 记忆。
 */
import { ref } from 'vue'
import { reportPage, reportSummary, enqueueReportExport } from '../../api/report-center.js'

function fmt(d) {
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** K2：截止昨天，起始=截止「上月同日的前一天」 */
export function defaultRange() {
  const end = new Date()
  end.setHours(0, 0, 0, 0)
  end.setDate(end.getDate() - 1)
  const start = new Date(end)
  start.setMonth(start.getMonth() - 1)
  start.setDate(start.getDate() - 1)
  return { start: fmt(start), end: fmt(end) }
}

/**
 * 月结类报表默认期间（与后端 ReportDateRange.naturalMonthPeriod 严格一致）：
 * 自然月本月 1 号至昨天；本月 1 号当天无已结账日期时退到上月整月。
 */
export function naturalMonthRange() {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  let start = new Date(today.getFullYear(), today.getMonth(), 1)
  let end = yesterday
  if (start > end) {
    start = new Date(today.getFullYear(), today.getMonth() - 1, 1)
    end = new Date(today.getFullYear(), today.getMonth(), 0)
  }
  return { start: fmt(start), end: fmt(end) }
}

export function useCenterReport(code, opts = {}) {
  const dr = opts.naturalMonth ? naturalMonthRange() : defaultRange()
  const start = ref(dr.start)
  const end = ref(dr.end)
  const filters = ref({})
  const groupBy = ref([])
  const pageNo = ref(1)
  const pageSize = ref(100)
  const sortField = ref('')
  const sortOrder = ref('')
  const rows = ref([])
  const summary = ref({})
  const total = ref(0)
  const loading = ref(false)

  function cleanFilters(obj) {
    const out = {}
    for (const [k, v] of Object.entries(obj || {})) {
      if (v === null || v === undefined) continue
      if (typeof v === 'string' && v.trim() === '') continue
      out[k] = v
    }
    return out
  }

  function buildBody(extra = {}) {
    const body = {
      dateRange: { startDate: start.value, endDate: end.value },
      filters: cleanFilters(filters.value),
      pageNo: pageNo.value,
      pageSize: pageSize.value,
    }
    if (groupBy.value.length) body.groupBy = [...groupBy.value]
    if (sortField.value) {
      body.sortField = sortField.value
      body.sortOrder = sortOrder.value || 'desc'
    }
    return { ...body, ...extra }
  }

  async function query() {
    loading.value = true
    const body = buildBody()
    // 合计行独立懒加载：全区间重合计（百万行聚合）不再拖累翻页；两者并行发出
    const summaryPromise = reportSummary(code, body)
      .then(s => { summary.value = s || {} })
      .catch(() => { summary.value = {} })
    try {
      const res = await reportPage(code, body)
      rows.value = res.records || []
      total.value = res.total || 0
    } finally {
      loading.value = false
    }
    await summaryPromise
  }

  function resetDate() {
    const d = opts.naturalMonth ? naturalMonthRange() : defaultRange()
    start.value = d.start
    end.value = d.end
  }

  async function exportAsync(filterText) {
    const res = await enqueueReportExport(code, { ...buildBody(), filterText })
    return res
  }

  return {
    start, end, filters, groupBy, pageNo, pageSize, sortField, sortOrder,
    rows, summary, total, loading,
    buildBody, query, resetDate, exportAsync,
  }
}
