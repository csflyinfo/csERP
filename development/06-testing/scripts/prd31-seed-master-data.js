// PRD-31 样板验证：给隔离空库播种最小基础资料（单位/仓库/商品/客户/供应商），
// 让采购/销售订单新建表单的下拉框有数据可选。幂等：已存在同编码则跳过。
// 用法：API_BASE=http://localhost:8090/api node prd31-seed-master-data.js
const BASE = process.env.API_BASE || 'http://localhost:8090/api'
let token
async function call(method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: token ? `Bearer ${token}` : undefined },
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} -> code=${json.code} msg=${json.message}`)
  return json.data
}
async function total(path, key, val) {
  const data = await call('POST', path, { pageNo: 1, pageSize: 200, filters: {} })
  const recs = data.records || []
  return { count: recs.length, exists: recs.some(r => String(r[key]) === String(val)) }
}
async function ensure(path, pagePath, key, label, payload) {
  const st = await total(pagePath, key, payload[key])
  if (st.exists) { console.log(`- ${label} 已存在（${payload[key]}），跳过`); return }
  await call('POST', path, payload)
  console.log(`- 已创建 ${label}（${payload[key]}）`)
}
async function main() {
  const login = await fetch(`${BASE}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  }).then(r => r.json())
  if (login.code !== '0' || !login.data?.token) throw new Error('登录失败: ' + login.message)
  token = login.data.token
  console.log('登录成功，开始播种基础资料…')

  await ensure('/base/unit/create', '/base/unit/page', 'unitCode', '单位',
    { unitCode: 'BOX', unitName: '箱' })
  await ensure('/base/warehouse/create', '/base/warehouse/page', 'warehouseCode', '仓库',
    { warehouseCode: 'WH01', warehouseName: '总仓', warehouseType: '正常仓', inventoryType: '平台主仓', managerName: '仓管' })
  await ensure('/base/goods/create', '/base/goods/page', 'goodsCode', '商品',
    { goodsCode: 'SP001', goodsName: '农夫山泉500ml*24', spec: '500ml*24', baseUnit: '箱',
      categoryName: '饮料', brandName: '农夫山泉', standardPrice: 35, latestPurchasePrice: 31.2,
      minSalePrice: 30, suggestedRetailPrice: 39.9, storageProperty: '常温' })
  await ensure('/base/customer/create', '/base/customer/page', 'customerCode', '客户',
    { customerCode: 'C001', customerName: '生命周期客户', contactName: '李采购', mobile: '13800000000',
      channelType: '商超', customerLevel: 'A级', salesman: '生命周期业务员', settlementType: '月结', termDays: 30 })
  await ensure('/base/supplier/create', '/base/supplier/page', 'supplierCode', '供应商',
    { supplierCode: 'S001', supplierName: '生命周期供应商', contactName: '王经理', phone: '13900000000',
      supplierType: '普通供应商', settlementMethod: '月结30天', deliveryMethod: '送货上门', defaultBuyer: '生命周期采购员' })

  // 采购员 / 业务员：采购/销售订单建单必填，走 /base/master/save（moduleCode=employee）
  await ensure('/base/master/save', '/base/master/employee/page', 'employeeCode', '采购员',
    { moduleCode: 'employee', employeeCode: 'EMP_B001', employeeName: '生命周期采购员', position: '采购员', isBuyer: true, status: 'NORMAL' })
  await ensure('/base/master/save', '/base/master/employee/page', 'employeeCode', '业务员',
    { moduleCode: 'employee', employeeCode: 'EMP_S001', employeeName: '生命周期业务员', position: '业务员', isSalesman: true, status: 'NORMAL' })

  console.log('\n✅ 基础资料播种完成')
}
main().catch(e => { console.error('\n❌', e.message); process.exit(1) })
