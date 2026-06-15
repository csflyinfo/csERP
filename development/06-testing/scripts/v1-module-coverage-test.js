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
  '/purchase/return/page',
  '/purchase/expense/page',
  '/purchase/invoice/page',
  '/sales/order/page',
  '/sales/outbound/page',
  '/sales/receipt/page',
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

async function post(path) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer demo-token' },
    body: JSON.stringify(body),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  if (!json.data || !Array.isArray(json.data.records)) throw new Error(`${path} missing page records`)
  return json.data.records.length
}

async function main() {
  const results = []
  for (const endpoint of endpoints) {
    const count = await post(endpoint)
    results.push({ endpoint, count })
  }
  console.log(`V1 module coverage test passed: ${results.length} endpoints`)
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
