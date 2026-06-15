const API_BASE = import.meta.env.VITE_API_BASE || '/api'
const TOKEN_KEY = 'erp-demo-token'

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
