const BASE = process.env.API_BASE || 'http://localhost:8080/api'
const body = { pageNo: 1, pageSize: 20, filters: {} }

const endpoints = [
  '/base/category/page',
  '/base/unit/page',
  '/base/brand/page',
  '/base/warehouse/page',
  '/base/goods/page',
  '/base/master/price-group/page',
  '/base/master/customer/page',
  '/base/master/supplier/page',
  '/base/master/counterparty/page',
  '/base/master/fund-account/page',
  '/base/master/expense-type/page',
  '/base/master/territory/page',
  '/base/master/route-line/page',
  '/base/master/employee/page',
  '/base/master/department/page',
  '/base/master/owner/page',
  '/base/customer-price-adjust/page',
  '/base/customer-price/query',
  '/inventory/balance/page',
  '/inventory/ledger/page',
  '/inventory/lock/page',
  '/inventory/batch/page',
  '/inventory/warning/page',
  '/inventory/transfer/page',
  '/inventory/damage/page',
  '/inventory/cost-adjust/page',
  '/purchase/order/page',
  '/purchase/inbound/page',
  '/purchase/receipt/page',
  '/purchase/return-apply/page',
  '/purchase/return-outbound/page',
  '/purchase/return/page',
  '/purchase/expense/page',
  '/purchase/invoice/page',
  '/sales/order/page',
  '/sales/outbound/page',
  '/sales/receipt/page',
  '/sales/reject-inbound/page',
  '/sales/return/page',
  '/sales/invoice/page',
  '/sales/fly-order/page',
  '/sales/empty-adjust/page',
  '/finance/ar/page',
  '/finance/ap/page',
  '/finance/receipt-payment/page',
  '/finance/ar-settlement/page',
  '/finance/ap-settlement/page',
  '/finance/expense/page',
  '/finance/fund-ledger/page',
  '/system/user/page',
  '/system/param/page',
  '/system/bill-no-rule/page',
  '/system/precision/page',
  '/system/dictionary/page',
  '/system/workflow/page',
  '/system/print-template/page',
  '/system/import-list/page',
  '/system/export-center/page',
  '/system/operation-log/page',
  '/report/sales/page',
  '/report/purchase/page',
  '/report/stock/page',
  '/report/finance/page',
]

let authToken = 'demo-token'   // 登录前占位；登录后替换为真实 JWT

async function login() {
  const res = await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`/auth/login failed: ${json.message}`)
  authToken = json.data.token
}

async function post(path) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + authToken },
    body: JSON.stringify(body),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  if (!json.data || !Array.isArray(json.data.records)) throw new Error(`${path} missing page records`)
  return json.data.records.length
}

async function main() {
  // 早前写死的 'demo-token' 已被后端拒绝（登录已过期），改成和其它脚本一样先登录换真实 JWT
  await login()
  const results = []
  const failures = []
  // 不在第一个失败就退出：一次跑完才能看清到底是哪几个端点坏了（早前一失败就中断，
  // 后面的端点等于从没被覆盖过）
  for (const endpoint of endpoints) {
    try {
      results.push({ endpoint, count: await post(endpoint) })
    } catch (e) {
      failures.push({ endpoint, message: e.message || String(e) })
    }
  }
  if (failures.length > 0) {
    console.error(`V1 module coverage test FAILED: ${failures.length}/${endpoints.length} endpoints broken`)
    for (const f of failures) console.error(`  ✗ ${f.endpoint} —— ${f.message}`)
    console.error(`  ✓ ${results.length} endpoints OK`)
    process.exit(1)
  }
  console.log(`V1 module coverage test passed: ${results.length} endpoints`)
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
