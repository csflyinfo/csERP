const BASE = process.env.API_BASE || 'http://localhost:8080/api'

async function post(path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer demo-token' },
    body: JSON.stringify(body),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function main() {
  const asc = await post('/base/goods/page', { pageNo: 1, pageSize: 20, sortField: 'goodsName', sortOrder: 'asc', filters: {} })
  const desc = await post('/base/goods/page', { pageNo: 1, pageSize: 20, sortField: 'goodsName', sortOrder: 'desc', filters: {} })

  assert(asc.records.length >= 2, 'asc sort should return at least two goods')
  assert(desc.records.length >= 2, 'desc sort should return at least two goods')
  assert(asc.records[0].goodsName !== desc.records[0].goodsName, 'asc and desc first goods should differ')
  assert(asc.total === desc.total, 'sort should not change total')

  const stockDesc = await post('/base/goods/page', { pageNo: 1, pageSize: 1, sortField: 'physicalQty', sortOrder: 'desc', filters: {} })
  assert(stockDesc.records.length === 1, 'sorted paging should respect page size')
  assert(stockDesc.total >= 2, 'sorted paging should keep total')

  console.log('V1 page sort test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
