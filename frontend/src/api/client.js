import { promptApproval } from './approval-dialog.js'

const API_BASE = import.meta.env.VITE_API_BASE || '/api'
const TOKEN_KEY = 'erp-token'

/**
 * 业务错误：保留后端 code 与 data。普通调用方仍可读 e.message；
 * 特殊流程（如 NEED_APPROVAL）据 e.code/e.data 分支处理。
 */
export class ApiError extends Error {
  constructor(code, message, data) {
    super(message || '请求失败')
    this.name = 'ApiError'
    this.code = code
    this.data = data
  }
}

function authHeaders(extra = {}) {
  const token = localStorage.getItem(TOKEN_KEY)
  return token ? { ...extra, Authorization: `Bearer ${token}` } : extra
}

/**
 * 认证/鉴权响应分流（PRD-28 卡片8）：
 *  - 401 未认证（含令牌失效）：清 token + 广播 erp-auth-expired，App.vue 跳登录页；
 *  - 403 已认证但无权限：会话保留不登出，广播 erp-perm-denied；错误仍抛给调用方，
 *    由触发操作的页面 toast 后端 message（无操作权限：xxx），避免与页面内 catch 双重弹窗。
 */
function notifyAuthOutcome(status, result) {
  if (status === 401 || (status === 200 && String(result?.code) === '401')) {
    localStorage.removeItem(TOKEN_KEY)
    window.dispatchEvent(new CustomEvent('erp-auth-expired'))
  } else if (status === 403 || (status === 200 && String(result?.code) === '403')) {
    window.dispatchEvent(new CustomEvent('erp-perm-denied', {
      detail: { code: result?.code || '403', message: result?.message || '无操作权限' },
    }))
  }
}

async function rawFetch(path, options) {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: authHeaders(options.headers || {}),
  })
  const result = await response.json().catch(() => ({ code: String(response.status), message: '服务响应异常' }))
  notifyAuthOutcome(response.status, result)
  return { response, result }
}

async function request(path, options = {}) {
  const { response, result } = await rawFetch(path, options)
  if (response.ok && result.code === '0') return result.data

  // PRD-28 §6.2.1：低价/超信用/负库存命中红线 → 弹授权框，合并凭证后重放原请求（仅一次）
  const method = (options.method || 'GET').toUpperCase()
  if (result.code === 'NEED_APPROVAL' && !options.__approvalRetried
      && (method === 'POST' || method === 'PUT') && options.body) {
    const cred = await promptApproval(result.data || {})
    if (cred) {
      const originalBody = JSON.parse(options.body || '{}')
      const retryOptions = {
        ...options,
        body: JSON.stringify({ ...originalBody, approverAccount: cred.account, approverPassword: cred.password }),
        __approvalRetried: true,
      }
      const retried = await rawFetch(path, retryOptions)
      if (retried.response.ok && retried.result.code === '0') return retried.result.data
      // 重放仍失败：按普通业务错误抛出（授权被拒/密码错等，后端已写失败日志）
      throw new ApiError(retried.result.code, retried.result.message || '授权后重试失败', retried.result.data)
    }
  }

  throw new ApiError(result.code, result.message || '请求失败', result.data)
}

export async function post(path, body = {}) {
  return request(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function get(path) {
  return request(path)
}

/**
 * PUT 请求（模块菜单管理的改名/移动/排序使用 PUT 语义）
 */
export async function put(path, body = {}) {
  return request(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/**
 * 上传文件（multipart/form-data）
 */
export async function upload(path, formData) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: authHeaders(),
    body: formData,
  })
  const result = await response.json().catch(() => ({ code: String(response.status), message: '上传失败' }))
  notifyAuthOutcome(response.status, result)
  if (!response.ok || result.code !== '0') throw new Error(result.message || '上传失败')
  return result.data
}

/**
 * 下载文件流（返回 Blob）
 */
export async function downloadBlob(path, body = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  })
  notifyAuthOutcome(response.status, null)
  if (response.status === 401) throw new Error('登录已过期')
  if (response.status === 403) throw new ApiError('403', '无操作权限')
  if (!response.ok) throw new Error('下载失败')
  return response.blob()
}

/**
 * GET 方式下载文件流（返回 Blob）
 */
export async function getBlob(path) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'GET',
    headers: authHeaders(),
  })
  notifyAuthOutcome(response.status, null)
  if (response.status === 401) throw new Error('登录已过期')
  if (response.status === 403) throw new ApiError('403', '无操作权限')
  if (!response.ok) throw new Error('下载失败')
  return response.blob()
}

export function saveTextFile(fileName, content, mimeType = 'text/plain;charset=UTF-8') {
  const blob = new Blob([content || ''], { type: mimeType })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName || 'download.txt'
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

/**
 * 保存 Blob 为文件
 */
export function saveBlobFile(fileName, blob) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName || 'download.xlsx'
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
