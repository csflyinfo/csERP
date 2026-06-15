const http = require('http')

const json = (res, data, status = 200) => {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
  })
  res.end(JSON.stringify({ code: '0', message: 'success', data }))
}

const readBody = req => new Promise(resolve => {
  let body = ''
  req.on('data', chunk => { body += chunk })
  req.on('end', () => {
    try { resolve(body ? JSON.parse(body) : {}) } catch { resolve({}) }
  })
})

const page = records => ({ records, pageNo: 1, pageSize: 20, total: records.length, summary: {} })

const menus = [
  { code: 'dashboard', name: '首页', path: '/dashboard', children: [] },
  { code: 'base', name: '基础资料', path: '', children: [
    { code: 'goods', name: '商品档案', path: '/base/goods', children: [] },
    { code: 'category', name: '商品分类', path: '/base/category', children: [] },
    { code: 'unit', name: '单位管理', path: '/base/unit', children: [] },
    { code: 'brand', name: '品牌管理', path: '/base/brand', children: [] },
    { code: 'warehouse', name: '仓库资料', path: '/base/warehouse', children: [] },
    { code: 'customerPrice', name: '客户价格调整单', path: '/base/customer-price-adjust', children: [] },
    { code: 'customerPriceQuery', name: '客户价格查询', path: '/base/customer-price-query', children: [] },
  ] },
  { code: 'system', name: '系统管理', path: '', children: [
    { code: 'user', name: '用户管理', path: '/system/user', children: [] },
    { code: 'role', name: '权限组管理', path: '/system/role', children: [] },
    { code: 'param', name: '系统参数', path: '/system/param', children: [] },
    { code: 'billNo', name: '单据编号规则', path: '/system/bill-no-rule', children: [] },
    { code: 'log', name: '操作日志', path: '/system/operation-log', children: [] },
  ] },
]

const goodsRows = [
  { goodsCode: 'SP001', goodsName: '农夫山泉500ml*24', baseUnit: '瓶', spec: '500ml*24', barcode: '6941410749551', standardPrice: '35.00', latestPurchasePrice: '31.20', costPrice: '30.80', status: '正常' },
  { goodsCode: 'SP002', goodsName: '康师傅红烧牛肉面', baseUnit: '箱', spec: '1*12', barcode: '690000000002', standardPrice: '48.00', latestPurchasePrice: '42.50', costPrice: '41.90', status: '正常' },
]

const priceAdjustRows = [{ adjustNo: 'CPA202606140001', customer: 'C001 华联超市', billDate: '2026-06-14', effectiveMode: '定时生效 2026-06-15 08:00', validRange: '2026-06-15 ~ 2026-12-31', detailCount: 2, creatorInfo: '管理员 2026-06-14 10:20', auditInfo: '待审核', status: '待审核' }]
const customerPriceRows = [{ adjustNo: 'CPA202606140001', customer: 'C001 华联超市', billDate: '2026-06-14', effectiveMode: '定时生效', validRange: '2026-06-15 ~ 2026-12-31', goodsCode: 'SP001', goodsName: '农夫山泉500ml*24', baseUnit: '瓶', spec: '500ml*24', barcode: '6941410749551', originalPrice: '35.00', currentPrice: '34.50', latestPurchasePrice: '31.20', costPrice: '30.80', effectiveStatus: '生效中' }]

const routes = {
  'GET /api/auth/current-user': () => ({ userId: 'U0001', username: 'admin', displayName: '系统管理员', roles: ['ADMIN'] }),
  'GET /api/system/menu/user-tree': () => menus,
  'POST /api/auth/login': () => ({ token: 'demo-token', displayName: '系统管理员', permissions: ['*'] }),
  'POST /api/auth/logout': () => true,
  'POST /api/base/category/page': () => page([{ categoryCode: '010101', categoryName: '瓶装水', parentName: '饮料', defaultTaxRate: '13%', goodsCount: 32, status: '正常' }]),
  'POST /api/base/category/create': body => ({ categoryCode: `${body.parentCode || '01'}${body.categoryCode || '01'}`, categoryName: body.categoryName || '新分类', status: 'NORMAL' }),
  'POST /api/base/unit/page': () => page([{ unitCode: 'U001', unitName: '箱', canBaseUnit: true, goodsCount: 128, status: '正常' }]),
  'POST /api/base/brand/page': () => page([{ brandCode: 'B001', brandName: '农夫山泉', simpleCode: 'NFSQ', goodsCount: 86, status: '正常' }]),
  'POST /api/base/warehouse/page': () => page([{ warehouseCode: 'W001', warehouseName: '总仓', warehouseType: '正常仓', costGroup: 'CG01', status: '正常' }]),
  'POST /api/base/goods/page': () => page(goodsRows),
  'POST /api/base/goods/selector': () => goodsRows,
  'POST /api/base/customer-price-adjust/page': () => page(priceAdjustRows),
  'GET /api/base/customer-price-adjust/detail': () => ({ ...priceAdjustRows[0], details: goodsRows.map(g => ({ ...g, originalPrice: g.standardPrice, currentPrice: g.goodsCode === 'SP001' ? '34.50' : '46.80' })) }),
  'POST /api/base/customer-price-adjust/create': body => ({ adjustId: 'CPA-ID-001', adjustNo: 'CPA202606140001', status: 'PENDING', detailCount: (body.details || []).length }),
  'POST /api/base/customer-price-adjust/update': () => true,
  'POST /api/base/customer-price-adjust/audit': () => ({ status: 'APPROVED', effect: '已生成客户价格，历史有效价格自动停用' }),
  'POST /api/base/customer-price-adjust/cancel': () => true,
  'POST /api/base/customer-price-adjust/import': () => ({ createdAdjustCount: 2, successRows: 120, failedRows: 0, templateFields: ['门店编号', '商品编号', '现价'] }),
  'POST /api/base/customer-price/query': () => page(customerPriceRows),
  'POST /api/base/customer-price/stop': body => ({ stoppedCount: (body.priceIds || []).length || 1, reason: body.reason || '手工停用' }),
  'POST /api/system/user/page': () => page([{ username: 'admin', displayName: '系统管理员', mobile: '138****0000', role: '管理员组', status: '正常' }]),
  'POST /api/system/param/page': () => page([{ paramKey: 'CREDIT_CHECK_MODE', paramName: '信用控制', paramValue: '提醒', paramGroup: '销售' }]),
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') return json(res, true)
  const body = await readBody(req)
  const key = `${req.method} ${req.url.split('?')[0]}`
  const handler = routes[key]
  if (!handler) return json(res, null, 404)
  json(res, handler(body, req))
})

server.listen(8080, () => {
  console.log('Mock ERP API listening on http://localhost:8080/api')
})
