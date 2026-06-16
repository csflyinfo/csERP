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

async function createPurchaseOrder() {
  return post('/purchase/order/create', {
    supplierId: '生命周期供应商',
    warehouseId: '总仓',
    buyer: '生命周期采购员',
    ownerName: '平台货主',
    settlementMethod: '月结30天',
    details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', taxRate: '13%', qty: 1, price: 35 }],
  })
}

async function createSalesOrder() {
  return post('/sales/order/create', {
    customerId: '生命周期客户',
    warehouseId: '总仓',
    salesman: '生命周期业务员',
    lineType: '正常',
    details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', discountRate: '100%', taxRate: '13%', qty: 1, price: 35 }],
  })
}

async function main() {
  const purchase = await createPurchaseOrder()
  await post('/purchase/order/audit', { bizId: purchase.orderNo, remark: '审核测试' })
  let purchasePage = await post('/purchase/order/page', { pageNo: 1, pageSize: 20, filters: { keyword: purchase.orderNo } })
  assert(purchasePage.records.some(record => record.orderNo === purchase.orderNo && record.status === '已审核'), 'purchase order should be approved')
  await post('/purchase/order/reverse-audit', { bizId: purchase.orderNo, remark: '反审核测试' })
  purchasePage = await post('/purchase/order/page', { pageNo: 1, pageSize: 20, filters: { keyword: purchase.orderNo } })
  assert(purchasePage.records.some(record => record.orderNo === purchase.orderNo && record.status === '待审核'), 'purchase order should reverse to pending')
  await post('/purchase/order/close', { bizId: purchase.orderNo, remark: '关闭测试' })
  purchasePage = await post('/purchase/order/page', { pageNo: 1, pageSize: 20, filters: { keyword: purchase.orderNo } })
  assert(purchasePage.records.some(record => record.orderNo === purchase.orderNo && record.status === '已关闭'), 'purchase order should be closed')

  const purchaseToDelete = await createPurchaseOrder()
  await post('/purchase/order/delete', { bizId: purchaseToDelete.orderNo, remark: '删除测试' })
  purchasePage = await post('/purchase/order/page', { pageNo: 1, pageSize: 20, filters: { keyword: purchaseToDelete.orderNo } })
  assert(purchasePage.records.some(record => record.orderNo === purchaseToDelete.orderNo && record.status === '已删除'), 'purchase order should be soft deleted')

  const sales = await createSalesOrder()
  const salesDetail = await get(`/sales/order/detail?orderId=${sales.orderNo}`)
  assert(Array.isArray(salesDetail.details) && salesDetail.details.length === 1, 'sales order detail should be queryable')
  assert(String(salesDetail.details[0].discountRate) === '100%', 'sales detail should include discount rate')
  await post('/sales/order/audit', { bizId: sales.orderNo, remark: '审核测试' })
  let salesPage = await post('/sales/order/page', { pageNo: 1, pageSize: 20, filters: { keyword: sales.orderNo } })
  assert(salesPage.records.some(record => record.orderNo === sales.orderNo && record.status === '已审核'), 'sales order should be approved')
  await post('/sales/order/reverse-audit', { bizId: sales.orderNo, remark: '反审核测试' })
  salesPage = await post('/sales/order/page', { pageNo: 1, pageSize: 20, filters: { keyword: sales.orderNo } })
  assert(salesPage.records.some(record => record.orderNo === sales.orderNo && record.status === '待审核'), 'sales order should reverse to pending')
  await post('/sales/order/close', { bizId: sales.orderNo, remark: '关闭测试' })
  salesPage = await post('/sales/order/page', { pageNo: 1, pageSize: 20, filters: { keyword: sales.orderNo } })
  assert(salesPage.records.some(record => record.orderNo === sales.orderNo && record.status === '已关闭'), 'sales order should be closed')

  const salesToDelete = await createSalesOrder()
  await post('/sales/order/delete', { bizId: salesToDelete.orderNo, remark: '删除测试' })
  salesPage = await post('/sales/order/page', { pageNo: 1, pageSize: 20, filters: { keyword: salesToDelete.orderNo } })
  assert(salesPage.records.some(record => record.orderNo === salesToDelete.orderNo && record.status === '已删除'), 'sales order should be soft deleted')

  console.log('V1 order lifecycle test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
