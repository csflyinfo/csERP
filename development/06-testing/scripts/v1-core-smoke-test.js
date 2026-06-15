const BASE = process.env.API_BASE || 'http://localhost:8080/api'

async function post(path, body = {}) {
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
  const self = await post('/flow/v1-core/self-test')
  assert(self.passed === true, 'self test should pass')
  assert(self.purchaseCycle.purchaseInbound.effect.includes('库存增加'), 'purchase inbound should increase stock')
  assert(self.purchaseCycle.ap.apNo, 'purchase receipt should generate AP')
  assert(self.salesCycle.salesOrder.effect.includes('锁定库存'), 'sales order should lock stock')
  assert(self.salesCycle.ar.arNo, 'sales receipt should generate AR')
  assert(self.arReceipt.ar.status === 'VERIFIED', 'AR should be verified after receipt')
  assert(self.apPayment.ap.status === 'VERIFIED', 'AP should be verified after payment')
  assert(self.customerPrice.effect.includes('历史有效价自动停用'), 'customer price should stop old price')

  const dashboard = await get('/flow/dashboard')
  assert(Array.isArray(dashboard.stockLedger) && dashboard.stockLedger.length >= 2, 'stock ledger should exist')
  assert(Array.isArray(dashboard.fundLedger) && dashboard.fundLedger.length >= 2, 'fund ledger should exist')
  assert(Array.isArray(dashboard.customerPrices) && dashboard.customerPrices.length >= 1, 'customer price should exist')

  console.log('V1 core smoke test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
