const BASE = process.env.API_BASE || 'http://localhost:8080/api'

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

async function get(path) {
  const res = await fetch(`${BASE}${path}`, { headers: { Authorization: 'Bearer demo-token' } })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function main() {
  const purchase = await post('/purchase/order/create', {
    supplierId: '自动化供应商',
    warehouseId: '总仓',
    buyer: '自动采购员',
    ownerName: '平台货主',
    settlementMethod: '月结45天',
    details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', taxRate: '13%', qty: 2, price: 35 }],
  })
  assert(purchase.orderNo, 'purchase order should return order no')
  assert(Number(purchase.amount) === 70, 'purchase order should calculate amount')
  const purchasePage = await post('/purchase/order/page', { pageNo: 1, pageSize: 20, filters: { keyword: purchase.orderNo } })
  assert(purchasePage.records.some(record => record.orderNo === purchase.orderNo && record.ownerName === '平台货主'), 'purchase order page should expose persisted owner')
  const purchaseDetail = await get(`/purchase/order/detail?orderId=${purchase.orderNo}`)
  assert(Array.isArray(purchaseDetail.details) && purchaseDetail.details.length === 1, 'purchase order detail should be persisted')
  assert(String(purchaseDetail.details[0].taxRate) === '13%', 'purchase detail should include tax rate')

  const sales = await post('/sales/order/create', {
    customerId: '自动化客户',
    warehouseId: '总仓',
    salesman: '自动业务员',
    lineType: '正常',
    details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', discountRate: '95%', taxRate: '13%', qty: 3, price: 35 }],
  })
  assert(sales.orderNo, 'sales order should return order no')
  assert(Number(sales.amount) === 105, 'sales order should calculate amount')
  const salesPage = await post('/sales/order/page', { pageNo: 1, pageSize: 20, filters: { keyword: sales.orderNo } })
  assert(salesPage.records.some(record => record.orderNo === sales.orderNo && record.lineType === '正常' && Number(record.costAmount) > 0), 'sales order page should expose persisted line type and cost')

  console.log('V1 order persistence test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
