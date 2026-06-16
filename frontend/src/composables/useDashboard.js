import { computed, ref } from 'vue'
import { get, post } from '../api/client.js'

export function useDashboard(toast) {
  const flowResult = ref(null)
  const dashboardSummary = ref(null)
  const dashboardLoading = ref(false)
  const dashboardError = ref('')
  const recentLogs = ref([])

  const dashboardCards = computed(() => {
    const summary = dashboardSummary.value || {}
    return [
      { label: '销售金额', value: money(summary.salesAmount ?? 82450) },
      { label: '采购金额', value: money(summary.purchaseAmount ?? 3500) },
      { label: '库存金额', value: money(summary.stockAmount ?? 1420000) },
      { label: '可用库存', value: numberText(summary.availableQty ?? 1020) },
      { label: '应收余额', value: money(summary.arBalance ?? 350) },
      { label: '应付余额', value: money(summary.apBalance ?? 3955) },
      { label: '销售单数', value: numberText(summary.salesOrderCount ?? 0) },
      { label: '采购单数', value: numberText(summary.purchaseOrderCount ?? 0) },
      { label: '未核销应收', value: numberText(summary.arCount ?? 0) },
      { label: '未核销应付', value: numberText(summary.apCount ?? 0) },
      { label: '导入完成', value: numberText(summary.importFinishedCount ?? 0) },
      { label: '导出完成', value: numberText(summary.exportFinishedCount ?? 0) },
    ]
  })

  async function loadRecentLogs() {
    try {
      const data = await post('/system/operation-log/page', { pageNo: 1, pageSize: 6, sortField: 'operateAt', sortOrder: 'desc', filters: {} })
      recentLogs.value = data.records || []
    } catch (error) {
      recentLogs.value = []
    }
  }

  async function loadDashboardSummary() {
    dashboardLoading.value = true
    dashboardError.value = ''
    try {
      dashboardSummary.value = await get('/report/dashboard/summary')
    } catch (error) {
      dashboardError.value = '经营概览接口加载失败，已显示演示指标'
    } finally {
      dashboardLoading.value = false
    }
  }

  async function runCoreFlow() {
    try {
      flowResult.value = await post('/flow/v1-core/self-test', {})
      toast('V1.0核心闭环自测通过')
    } catch (error) {
      toast(`核心闭环自测失败：${error.message}`)
    }
  }

  return { flowResult, dashboardSummary, dashboardLoading, dashboardError, recentLogs, dashboardCards, loadRecentLogs, loadDashboardSummary, runCoreFlow }
}

function money(value) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function numberText(value) {
  return Number(value || 0).toLocaleString('zh-CN')
}
