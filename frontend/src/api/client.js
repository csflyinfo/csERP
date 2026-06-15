const API_BASE = import.meta.env.VITE_API_BASE || '/api'

export async function post(path, body = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const result = await response.json()
  if (result.code !== '0') throw new Error(result.message || '请求失败')
  return result.data
}

export async function get(path) {
  const response = await fetch(`${API_BASE}${path}`)
  const result = await response.json()
  if (result.code !== '0') throw new Error(result.message || '请求失败')
  return result.data
}
