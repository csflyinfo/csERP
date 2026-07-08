const API_BASE = import.meta.env.VITE_API_BASE || '/api'
const TOKEN_KEY = 'erp-token'

function authHeaders(extra = {}) {
  const token = localStorage.getItem(TOKEN_KEY)
  return token ? { ...extra, Authorization: `Bearer ${token}` } : extra
}

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: authHeaders(options.headers || {}),
  })
  const result = await response.json().catch(() => ({ code: String(response.status), message: '服务响应异常' }))
  if (response.status === 401) {
    localStorage.removeItem(TOKEN_KEY)
    window.dispatchEvent(new CustomEvent('erp-auth-expired'))
  }
  if (!response.ok || result.code !== '0') throw new Error(result.message || '请求失败')
  return result.data
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
 * 上传文件（multipart/form-data）
 */
export async function upload(path, formData) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: authHeaders(),
    body: formData,
  })
  const result = await response.json().catch(() => ({ code: String(response.status), message: '上传失败' }))
  if (response.status === 401) {
    localStorage.removeItem(TOKEN_KEY)
    window.dispatchEvent(new CustomEvent('erp-auth-expired'))
  }
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
  if (response.status === 401) {
    localStorage.removeItem(TOKEN_KEY)
    window.dispatchEvent(new CustomEvent('erp-auth-expired'))
    throw new Error('登录已过期')
  }
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
  if (response.status === 401) {
    localStorage.removeItem(TOKEN_KEY)
    window.dispatchEvent(new CustomEvent('erp-auth-expired'))
    throw new Error('登录已过期')
  }
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
