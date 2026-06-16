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
    supplierId: '编辑前供应商',
    warehouseId: '总仓',
    buyer: '编辑采购员',
    ownerName: '平台货主',
    settlementMethod: '月结30天',
    details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', taxRate: '13%', qty: 1, price: 35 }],
  })
  await post('/purchase/order/update', {
    orderId: purchase.orderNo,
    supplierId: '编辑后供应商',
    warehouseId: '总仓',
    buyer: '编辑采购员',
    ownerName: '平台货主',
    settlementMethod: '月结60天',
    details: [{ goodsId: 'SP002', goodsName: '康师傅红烧牛肉面', unitId: '箱', lineType: '赠品', taxRate: '9%', qty: 2, price: 48 }],
  })
  const purchaseDetail = await get(`/purchase/order/detail?orderId=${purchase.orderNo}`)
  assert((purchaseDetail.supplier || purchaseDetail.SUPPLIER) === '编辑后供应商', 'purchase order should update supplier')
  assert(Array.isArray(purchaseDetail.details) && purchaseDetail.details.length === 1, 'purchase order should replace details')
  assert(purchaseDetail.details[0].goodsCode === 'SP002', 'purchase detail should update goods')
  assert(Number(purchaseDetail.details[0].amount) === 96, 'purchase detail should recalculate amount')

  const sales = await post('/sales/order/create', {
    customerId: '编辑前客户',
    warehouseId: '总仓',
    salesman: '编辑业务员',
    lineType: '正常',
    details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', discountRate: '100%', taxRate: '13%', qty: 1, price: 35 }],
  })
  await post('/sales/order/update', {
    orderId: sales.orderNo,
    customerId: '编辑后客户',
    warehouseId: '总仓',
    salesman: '编辑业务员',
    lineType: '赠品',
    details: [{ goodsId: 'SP002', goodsName: '康师傅红烧牛肉面', unitId: '箱', lineType: '赠品', discountRate: '90%', taxRate: '9%', qty: 2, price: 48 }],
  })
  const salesDetail = await get(`/sales/order/detail?orderId=${sales.orderNo}`)
  assert((salesDetail.customer || salesDetail.CUSTOMER) === '编辑后客户', 'sales order should update customer')
  assert((salesDetail.line_type || salesDetail.LINE_TYPE) === '赠品', 'sales order should update line type')
  assert(Array.isArray(salesDetail.details) && salesDetail.details.length === 1, 'sales order should replace details')
  assert(salesDetail.details[0].goodsCode === 'SP002', 'sales detail should update goods')
  assert(String(salesDetail.details[0].discountRate) === '90%', 'sales detail should update discount rate')
  assert(Number(salesDetail.details[0].amount) === 96, 'sales detail should recalculate amount')

  console.log('V1 order edit detail test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
