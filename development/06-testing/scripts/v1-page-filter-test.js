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
  const matched = await post('/base/goods/page', { pageNo: 1, pageSize: 20, filters: { keyword: '康师傅' } })
  assert(matched.total === 1, 'goods filter should keep one matching record')
  assert(matched.records.length === 1, 'goods filter should return one record')
  assert(String(matched.records[0].goodsName).includes('康师傅'), 'matched goods should contain keyword')

  const empty = await post('/base/goods/page', { pageNo: 1, pageSize: 20, filters: { keyword: '不存在商品' } })
  assert(empty.total === 0, 'unknown keyword should return zero total')
  assert(empty.records.length === 0, 'unknown keyword should return empty records')

  const paged = await post('/system/operation-log/page', { pageNo: 1, pageSize: 1, filters: { keyword: 'SUCCESS' } })
  assert(paged.records.length <= 1, 'filtered page should respect page size')
  assert(paged.total >= paged.records.length, 'filtered page should keep filtered total')

  console.log('V1 page filter test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
