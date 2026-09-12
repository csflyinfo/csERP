/**
 * 报表中心统一接口（一期：采购五表 + 异步导出中心 + 运维）。
 * 所有路径由 client.js 自动加 /api 前缀与 Bearer token。
 */
import { post, downloadBlob } from './client.js'

/** 报表分页/汇总（元数据驱动，code 为注册表里的报表编码） */
export const reportPage = (code, body) => post(`/report/center/${code}/page`, body)
export const reportSummary = (code, body) => post(`/report/center/${code}/summary`, body)

/** 异步导出：入队后返回 { taskNo, status:'CREATED', message }，文件在导出中心下载 */
export const enqueueReportExport = (code, body) => post(`/report/center/${code}/export`, body)

/** 报表5：采购预测（点查询才计算）/ 一键生成待审核采购订单 */
export const forecastCompute = (body) => post('/report/center/purchase-forecast/compute', body)
export const forecastGenerate = (body) => post('/report/center/purchase-forecast/generate', body)

/** 异步导出中心：任务列表 / 文件下载（真实 xlsx 流） */
export const exportTaskPage = (body = {}) => post('/report/center/export/page', body)
export const downloadExportTask = (taskNo) => downloadBlob('/report/center/export/download', { taskNo })

/** 运维（SYS_ADMIN）：采购 DWS 手工重算 / 库存日快照重建 */
export const recomputePurchaseDws = (body) => post('/report/center/admin/recompute-purchase', body)
export const rebuildStockSnapshot = (body) => post('/report/center/admin/rebuild-snapshot', body)

/** 报表17：商品综合分析 KPI/趋势/结构 */
export const goodsAnalysisKpi = (body) => post('/report/center/goods-analysis/kpi', body)
export const goodsAnalysisTrend = (body) => post('/report/center/goods-analysis/trend', body)
export const goodsAnalysisStructure = (body) => post('/report/center/goods-analysis/structure', body)

/** 仓库下拉（报表仓库筛选存的是名称，与数据范围口径一致） */
export async function loadWarehouses() {
  const res = await post('/base/warehouse/page', { pageNo: 1, pageSize: 500, filters: {} })
  return (res?.records || []).map(r => r.warehouseName).filter(Boolean)
}
