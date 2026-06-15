const BASE = process.env.API_BASE || 'http://localhost:8080/api'

async function request(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    ...options,
    headers: { Authorization: 'Bearer demo-token', ...(options.headers || {}) },
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function main() {
  const login = await request('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  })
  assert(login.token === 'demo-token', 'login should return demo token')
  assert(login.displayName, 'login should return display name')

  const currentUser = await request('/auth/current-user')
  assert(currentUser.username === 'admin', 'current user should be admin')

  const menus = await request('/system/menu/user-tree')
  assert(Array.isArray(menus) && menus.length >= 6, 'menu tree should contain top modules')
  const report = menus.find(item => item.code === 'report')
  assert(report, 'menu tree should include report center')
  assert(report.children.some(item => item.code === 'salesReport'), 'report center should include sales report')
  assert(report.children.some(item => item.code === 'financeReport'), 'report center should include finance report')

  const base = menus.find(item => item.code === 'base')
  assert(base.children.some(item => item.code === 'fundAccount'), 'base menu should include fund account')

  console.log('V1 auth menu smoke test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
