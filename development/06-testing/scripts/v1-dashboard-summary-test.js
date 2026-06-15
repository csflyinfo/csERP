const BASE = process.env.API_BASE || 'http://localhost:8080/api'

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
  const summary = await get('/report/dashboard/summary')
  const required = [
    'salesAmount',
    'purchaseAmount',
    'stockAmount',
    'availableQty',
    'arBalance',
    'apBalance',
    'salesOrderCount',
    'purchaseOrderCount',
    'arCount',
    'apCount',
    'importFinishedCount',
    'exportFinishedCount',
    'operationLogCount',
  ]
  required.forEach(key => assert(summary[key] !== undefined && summary[key] !== null, `summary should include ${key}`))
  assert(Number(summary.operationLogCount) >= 1, 'summary should include operation log count')
  console.log('V1 dashboard summary test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
