const BASE = process.env.API_BASE || 'http://localhost:8080/api'

async function get(path) {
  const res = await fetch(`${BASE}${path}`, { headers: { Authorization: 'Bearer demo-token' } })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

async function post(path, body = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer demo-token' },
    body: JSON.stringify(body),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

function flattenMenus(tree) {
  return tree.flatMap(item => [item.code, ...(item.children ? flattenMenus(item.children) : [])])
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function main() {
  const currentUser = await get('/auth/current-user')
  assert(currentUser.roleCode === 'ADMIN', 'current user should include role code')
  assert(currentUser.menuScope === '*', 'admin should include full menu scope')

  const saleMenus = await get('/system/menu/user-tree?roleCode=SALE')
  const saleCodes = flattenMenus(saleMenus)
  assert(saleCodes.includes('salesOrder'), 'sales role should include sales order menu')
  assert(!saleCodes.includes('purchaseOrder'), 'sales role should not include purchase order menu')

  const saleFieldScope = await post('/system/field-scope', { roleCode: 'SALE', moduleCode: 'salesOrder' })
  assert(Array.isArray(saleFieldScope.hiddenFields), 'field scope should return hidden fields')
  assert(saleFieldScope.hiddenFields.includes('成本金额'), 'sales role should hide cost amount')

  const adminFieldScope = await post('/system/field-scope', { roleCode: 'ADMIN', moduleCode: 'salesOrder' })
  assert(Array.isArray(adminFieldScope.hiddenFields) && adminFieldScope.hiddenFields.length === 0, 'admin should not hide fields')

  const adminSales = await post('/sales/order/page', { pageNo: 1, pageSize: 20, filters: { roleCode: 'ADMIN' } })
  const purchaseRoleSales = await post('/sales/order/page', { pageNo: 1, pageSize: 20, filters: { roleCode: 'PURCHASE' } })
  assert(adminSales.total >= purchaseRoleSales.total, 'purchase role data scope should not exceed admin sales orders')

  const adminPurchase = await post('/purchase/order/page', { pageNo: 1, pageSize: 20, filters: { roleCode: 'ADMIN' } })
  const salesRolePurchase = await post('/purchase/order/page', { pageNo: 1, pageSize: 20, filters: { roleCode: 'SALE' } })
  assert(adminPurchase.total >= salesRolePurchase.total, 'sales role data scope should not exceed admin purchase orders')

  console.log('V1 permission scope test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
