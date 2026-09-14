/**
 * 业务日结（按日封单，PRD-33）前端接口封装。
 * 所有路径由 client.js 自动加 /api 前缀与 Bearer token；分页统一 POST + PageRequest。
 */
import { post } from './client.js'

/** 7 步向导预览（body: { date: 'yyyy-MM-dd' }） */
export const dayCloseWizard = (date) => post('/finance/day-close/wizard', { date })

/** 工作台红色提醒（过 P0188 时刻昨日未结 / 最近自动任务失败） */
export const dayCloseReminder = () => post('/finance/day-close/reminder', {})

/**
 * 手工执行日结。
 * @param {{date:string, openingConfirmed?:boolean, acknowledgeHanging?:boolean,
 *          acknowledgeAnomaly?:boolean, cashCounts?:Object, fundRemark?:string}} body
 */
export const dayCloseExecute = (body) => post('/finance/day-close/close', body)

/** 单日反日结（仅当前封单日），reason 必填 ≥2 字 */
export const dayCloseReopen = (date, reason) => post('/finance/day-close/reopen', { date, reason })

/** 批量反日结到指定日期（含该日），reason 必填 ≥2 字 */
export const dayCloseReopenBatch = (toDate, reason) =>
  post('/finance/day-close/reopen-batch', { toDate, reason })

/** 日结记录分页（filters: from/to） */
export const dayClosePage = (body) => post('/finance/day-close/page', body)

/** 日结操作日志分页（filters: from/to/action） */
export const dayCloseLogPage = (body) => post('/finance/day-close/log-page', body)

/** 应收定版台账分页（filters: from/to/keyword） */
export const dayCloseArDailyPage = (body) => post('/finance/day-close/ar-daily/page', body)

/** 应付定版台账分页 */
export const dayCloseApDailyPage = (body) => post('/finance/day-close/ap-daily/page', body)

/** 资金定版台账分页 */
export const dayCloseFundDailyPage = (body) => post('/finance/day-close/fund-daily/page', body)

/** 商品收发存定版台账分页（区间滚算，filters: from/to/keyword；V121） */
export const dayCloseGoodsDailyPage = (body) => post('/finance/day-close/goods-daily/page', body)

/** RJ 日结单详情（打印用，body: { date }） */
export const dayCloseTicket = (date) => post('/finance/day-close/ticket', { date })
