const BASE = process.env.API_BASE || 'http://localhost:8080/api'

async function post(path, body = { pageNo: 1, pageSize: 20, filters: {} }) {
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
  const customers = await post('/base/master/customer/page')
  assert(customers.records.length >= 1, 'customer page should return persisted records')
  const customer = customers.records.find(record => record.customerCode === 'C001')
  assert(customer, 'seed customer C001 should exist')
  assert(customer.channelType === '零售商超', 'customer should include channel type')
  assert(customer.accountPeriodType === '月结', 'customer should include account period type')
  assert(customer.invoiceTitle, 'customer should include invoice title')

  const suppliers = await post('/base/master/supplier/page')
  assert(suppliers.records.length >= 1, 'supplier page should return persisted records')
  const supplier = suppliers.records.find(record => record.supplierCode === 'G001')
  assert(supplier, 'seed supplier G001 should exist')
  assert(supplier.shortName === '农夫杭州', 'supplier should include short name')
  assert(Number(supplier.accountPeriodDays) === 30, 'supplier should include account period days')
  assert(supplier.invoiceTitle, 'supplier should include invoice title')

  const customerCode = `CT${Date.now()}`
  await post('/base/master/save', {
    moduleCode: 'customer',
    customerId: customerCode,
    customerCode,
    customerName: '自动化客户',
    channelType: '批发',
    accountPeriodType: '月结',
    invoiceTitle: '自动化客户有限公司',
    taxNo: 'AUTO-TAX',
  })
  const savedCustomers = await post('/base/master/customer/page', { pageNo: 1, pageSize: 20, filters: { keyword: customerCode } })
  assert(savedCustomers.records.some(record => record.customerCode === customerCode && record.invoiceTitle === '自动化客户有限公司'), 'saved customer should be persisted')
  await post('/base/master/stop', { moduleCode: 'customer', bizId: customerCode })
  const stoppedCustomers = await post('/base/master/customer/page', { pageNo: 1, pageSize: 20, filters: { keyword: customerCode } })
  assert(stoppedCustomers.records.some(record => record.customerCode === customerCode && record.status === '停用'), 'stopped customer should persist status')

  const supplierCode = `SP${Date.now()}`
  await post('/base/master/save', {
    moduleCode: 'supplier',
    supplierId: supplierCode,
    supplierCode,
    supplierName: '自动化供应商',
    shortName: '自动供应',
    accountPeriodDays: 45,
    invoiceTitle: '自动化供应商有限公司',
    taxNo: 'SUP-TAX',
  })
  const savedSuppliers = await post('/base/master/supplier/page', { pageNo: 1, pageSize: 20, filters: { keyword: supplierCode } })
  assert(savedSuppliers.records.some(record => record.supplierCode === supplierCode && Number(record.accountPeriodDays) === 45), 'saved supplier should be persisted')
  await post('/base/master/stop', { moduleCode: 'supplier', bizId: supplierCode })
  const stoppedSuppliers = await post('/base/master/supplier/page', { pageNo: 1, pageSize: 20, filters: { keyword: supplierCode } })
  assert(stoppedSuppliers.records.some(record => record.supplierCode === supplierCode && record.status === '停用'), 'stopped supplier should persist status')

  console.log('V1 base master persistence test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
