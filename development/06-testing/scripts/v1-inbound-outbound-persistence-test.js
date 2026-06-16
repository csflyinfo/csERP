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
    supplierId: '入库供应商', warehouseId: '总仓', buyer: '入库采购员', ownerName: '平台货主', settlementMethod: '月结30天',
    details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', taxRate: '13%', qty: 2, price: 35 }],
  })
  const inbound = await post('/purchase/inbound/create', { sourceOrder: purchase.orderNo })
  assert(inbound.inboundNo, 'purchase inbound should return inbound no')
  const inboundDetail = await get(`/purchase/inbound/detail?inboundId=${inbound.inboundNo}`)
  assert(Array.isArray(inboundDetail.details) && inboundDetail.details.length === 1, 'purchase inbound should have details')
  assert(Number(inboundDetail.details[0].receivedQty) === 2, 'purchase inbound detail should use order qty')
  await post('/purchase/inbound/audit', { bizId: inbound.inboundNo, remark: '入库审核测试' })
  const inboundPage = await post('/purchase/inbound/page', { pageNo: 1, pageSize: 20, filters: { keyword: inbound.inboundNo } })
  assert(inboundPage.records.some(record => record.inboundNo === inbound.inboundNo && record.status === '已审核' && record.stockUpdated === '是'), 'purchase inbound should be approved and update stock')

  const sales = await post('/sales/order/create', {
    customerId: '出库客户', warehouseId: '总仓', salesman: '出库业务员', lineType: '正常',
    details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', discountRate: '100%', taxRate: '13%', qty: 1, price: 35 }],
  })
  await post('/sales/order/audit', { bizId: sales.orderNo, remark: '锁库存' })
  const outbound = await post('/sales/outbound/create', { sourceOrder: sales.orderNo })
  assert(outbound.outboundNo, 'sales outbound should return outbound no')
  const outboundDetail = await get(`/sales/outbound/detail?outboundId=${outbound.outboundNo}`)
  assert(Array.isArray(outboundDetail.details) && outboundDetail.details.length === 1, 'sales outbound should have details')
  assert(Number(outboundDetail.details[0].qty) === 1, 'sales outbound detail should use order qty')
  await post('/sales/outbound/audit', { bizId: outbound.outboundNo, remark: '出库审核测试' })
  const outboundPage = await post('/sales/outbound/page', { pageNo: 1, pageSize: 20, filters: { keyword: outbound.outboundNo } })
  assert(outboundPage.records.some(record => record.outboundNo === outbound.outboundNo && record.status === '已审核' && record.stockUpdated === '是'), 'sales outbound should be approved and update stock')

  console.log('V1 inbound outbound persistence test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
