const BASE = process.env.API_BASE || 'http://localhost:8080/api'
const pageBody = { pageNo: 1, pageSize: 20, filters: {} }

async function post(path, body = pageBody) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

async function get(path) {
  const res = await fetch(`${BASE}${path}`)
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function main() {
  const menus = await get('/system/menu/user-tree')
  assert(Array.isArray(menus) && menus.length >= 5, 'menu tree should include business domains')

  const users = await post('/system/user/page')
  assert(users.records.length >= 1, 'user page should have records')

  const savedUser = await post('/system/user/save', {
    username: `u${Date.now()}`,
    displayName: '自动化用户',
    mobile: '13900000000',
    roleName: '普通用户',
    dataScope: '本人',
  })
  assert(savedUser.success === true, 'save user should succeed')

  const roles = await post('/system/role/page')
  assert(roles.records.length >= 1, 'role page should have records')

  const savedRole = await post('/system/role/save', {
    roleCode: `R${Date.now()}`,
    roleName: '自动化权限组',
    menuScope: '基础资料',
    fieldScope: '隐藏成本',
  })
  assert(savedRole.success === true, 'save role should succeed')

  const params = await post('/system/param/page')
  assert(params.records.length >= 1, 'param page should have records')

  await post('/system/param/update', { paramKey: 'CREDIT_CHECK_MODE', paramValue: 'BLOCK' })

  const rules = await post('/system/bill-no-rule/page')
  assert(rules.records.length >= 1, 'bill no rule page should have records')

  await post('/system/bill-no-rule/update', { billType: '销售订单', prefix: 'SO', serialLength: 5 })

  const logs = await post('/system/operation-log/page')
  assert(logs.records.length >= 1, 'operation log should have records')

  console.log('V1 system smoke test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
