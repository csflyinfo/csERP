/**
 * 动态定时任务管理（PRD-33 §7.1）前端接口封装。
 * 任务体只来自后端代码注册白名单，前端仅可改 cron/备注/启停/立即执行。
 */
import { post } from './client.js'

/** 任务列表分页 */
export const scheduleTaskPage = (body) => post('/system/schedule-task/page', body)

/** 修改 cron/备注（后端保存前校验 6 段 cron），返回 nextFireTimes 未来 5 次 */
export const scheduleTaskUpdate = (body) => post('/system/schedule-task/update', body)

/** 启用/停用（enabled: true/false） */
export const scheduleTaskToggle = (taskCode, enabled) =>
  post('/system/schedule-task/toggle', { taskCode, enabled })

/** 立即执行（手动触发，操作人记执行日志） */
export const scheduleTaskRunOnce = (taskCode) =>
  post('/system/schedule-task/run-once', { taskCode })

/** 预览 cron 未来 5 次执行时间（不保存） */
export const scheduleTaskNext5 = (cronExpr) =>
  post('/system/schedule-task/next5', { cronExpr })

/** 执行日志分页（filters: taskCode） */
export const scheduleTaskLogPage = (body) => post('/system/schedule-task/log-page', body)
