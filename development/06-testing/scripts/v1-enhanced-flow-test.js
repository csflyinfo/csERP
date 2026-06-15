const BASE = process.env.API_BASE || 'http://localhost:8080/api'
const pageBody = { pageNo: 1, pageSize: 20, filters: {} }

async function post(path, body = pageBody) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
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
  const auditBody = { bizId: 'demo', remark: '自动化增强流程测试' }
  const fundBody = { objectId: 'OBJ001', fundAccountId: 'A001', amount: 100, remark: '自动化增强流程测试' }

  const purchaseReturn = await post('/purchase/return/audit', auditBody)
  assert(purchaseReturn.effect.includes('库存'), 'purchase return should affect stock')

  const purchaseExpense = await post('/purchase/expense/audit', auditBody)
  assert(purchaseExpense.effect.includes('应付'), 'purchase expense should generate AP')

  const salesReturn = await post('/sales/return/audit', auditBody)
  assert(salesReturn.effect.includes('应收'), 'sales return should offset AR')

  const transfer = await post('/inventory/transfer/audit', auditBody)
  assert(transfer.effect.includes('调出') || transfer.effect.includes('流水'), 'transfer should generate ledgers')

  const damage = await post('/inventory/damage/audit', auditBody)
  assert(damage.effect.includes('库存'), 'damage should reduce stock')

  const costAdjust = await post('/inventory/cost-adjust/audit', auditBody)
  assert(costAdjust.effect.includes('成本'), 'cost adjust should affect cost')

  const financeExpense = await post('/finance/expense/audit', fundBody)
  assert(financeExpense.effect.includes('往来'), 'finance expense should generate AR/AP')

  const receive = await post('/finance/reconcile/receive', fundBody)
  assert(receive.effect.includes('资金流水'), 'receive reconcile should generate fund ledger')

  const pay = await post('/finance/reconcile/pay', fundBody)
  assert(pay.effect.includes('资金流水'), 'pay reconcile should generate fund ledger')

  const stockLedger = await post('/inventory/ledger/page')
  assert(stockLedger.records.length >= 1, 'stock ledger should have records')

  const fundLedger = await post('/finance/fund-ledger/page')
  assert(fundLedger.records.length >= 1, 'fund ledger should have records')

  console.log('V1 enhanced flow test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
