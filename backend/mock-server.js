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

// 商品档案 mock：unitConfig 为多单位配置 JSON（索引 0=小 1=中 2=大）
// SP001 三级单位全启用；SP002 只启用小单位（用于验证中/大单位置灰逻辑）
const goodsRows = [
  {
    goodsCode: 'SP001', goodsName: '农夫山泉500ml*24', baseUnit: '瓶', spec: '500ml*24',
    barcode: '6941410749551', standardPrice: '35.00', latestPurchasePrice: '31.20', costPrice: '30.80',
    categoryName: '瓶装水', brandName: '农夫山泉', storageProperty: '常温', status: '正常',
    simpleCode: 'NFSQ', isWeighted: false,
    unitConfig: JSON.stringify([
      { unitName: '瓶', enabled: true, convertQty: 1, standardPrice: 2.00, barcode: '6941410749551' },
      { unitName: '提', enabled: true, convertQty: 6, standardPrice: 11.50, barcode: '6941410749552' },
      { unitName: '箱', enabled: true, convertQty: 24, standardPrice: 35.00, barcode: '6941410749553' },
    ]),
  },
  {
    goodsCode: 'SP002', goodsName: '康师傅红烧牛肉面', baseUnit: '箱', spec: '1*12',
    barcode: '690000000002', standardPrice: '48.00', latestPurchasePrice: '42.50', costPrice: '41.90',
    categoryName: '方便面', brandName: '康师傅', storageProperty: '常温', status: '正常',
    simpleCode: 'KSF', isWeighted: false,
    unitConfig: JSON.stringify([
      { unitName: '箱', enabled: true, convertQty: 1, standardPrice: 48.00, barcode: '690000000002' },
      { unitName: '', enabled: false, convertQty: 0, standardPrice: 0, barcode: '' },
      { unitName: '', enabled: false, convertQty: 0, standardPrice: 0, barcode: '' },
    ]),
  },
  {
    goodsCode: 'SP003', goodsName: '伊利纯牛奶250ml*16', baseUnit: '盒', spec: '250ml*16',
    barcode: '690000000003', standardPrice: '58.00', latestPurchasePrice: '52.00', costPrice: '51.00',
    categoryName: '乳制品', brandName: '伊利', storageProperty: '冷藏', status: '正常',
    simpleCode: 'YL', isWeighted: true,
    unitConfig: JSON.stringify([
      { unitName: '盒', enabled: true, convertQty: 1, standardPrice: 3.80, barcode: '690000000003' },
      { unitName: '排', enabled: true, convertQty: 4, standardPrice: 14.80, barcode: '690000000004' },
      { unitName: '', enabled: false, convertQty: 0, standardPrice: 0, barcode: '' },
    ]),
  },
]

const priceAdjustRows = [{
  adjustId: 'CPA-ID-001', adjustNo: 'CPA202606140001', customer: 'C001 华联超市',
  billDate: '2026-06-14', billDateText: '2026-06-14',
  effectiveMode: 'SCHEDULED', effectiveModeText: '定时生效 2026-06-15 08:00:00',
  validRange: '2026-06-15 ~ 2026-12-31', validRangeText: '2026-06-15 ~ 2026-12-31',
  detailCount: 2,
  creatorInfo: '管理员 2026-06-14 10:20', creatorNameText: '系统管理员', createTimeText: '2026-06-14 10:20:00',
  auditInfo: '', auditorNameText: '', auditTimeText: '',
  status: 'PENDING', statusText: '待审核',
}]
// 客户价格查询：一客户一商品一单位一条当前价（对应 base_customer_price_item）
const customerPriceRows = [
  {
    id: 'CPI001', customerCode: 'C001', customerName: '华联超市',
    goodsCode: 'SP001', goodsName: '农夫山泉500ml*24', spec: '500ml*24', barcode: '6941410749551',
    unitLevel: 1, unitName: '瓶', unitLevelText: '小单位',
    categoryName: '瓶装水', brandName: '农夫山泉',
    standardPrice: '2.00', price: '1.90',
    isActive: true, statusText: '生效中',
  },
  {
    id: 'CPI002', customerCode: 'C001', customerName: '华联超市',
    goodsCode: 'SP001', goodsName: '农夫山泉500ml*24', spec: '500ml*24', barcode: '6941410749553',
    unitLevel: 3, unitName: '箱', unitLevelText: '大单位',
    categoryName: '瓶装水', brandName: '农夫山泉',
    standardPrice: '35.00', price: '34.50',
    isActive: false, statusText: '已停用',
  },
]

// 客户商品变价查询：历史调价记录（对应 base_customer_price_change_log）
const customerPriceChangeRows = [
  {
    id: 'CPL001', adjustNo: 'CPA202606140001', billDate: '2026-06-14', billDateText: '2026-06-14',
    customerCode: 'C001', customerName: '华联超市',
    goodsCode: 'SP001', goodsName: '农夫山泉500ml*24',
    unitLevel: 1, unitName: '瓶', unitLevelText: '小单位',
    categoryName: '瓶装水', brandName: '农夫山泉',
    oldPrice: '2.00', oldPriceText: '2.00', newPrice: '1.90',
    effectiveMode: 'SCHEDULED', effectiveModeText: '定时生效',
    validRange: '2026-06-15 ~ 2026-12-31', validRangeText: '2026-06-15 ~ 2026-12-31',
    operator: '系统管理员', remark: '促销调价', createdAtText: '2026-06-14 10:30:00',
  },
  {
    id: 'CPL002', adjustNo: 'CPA202606130001', billDate: '2026-06-13', billDateText: '2026-06-13',
    customerCode: 'C001', customerName: '华联超市',
    goodsCode: 'SP001', goodsName: '农夫山泉500ml*24',
    unitLevel: 3, unitName: '箱', unitLevelText: '大单位',
    categoryName: '瓶装水', brandName: '农夫山泉',
    oldPrice: null, oldPriceText: '首次设价', newPrice: '35.00',
    effectiveMode: 'IMMEDIATE', effectiveModeText: '立即生效',
    validRange: '长期有效', validRangeText: '长期有效',
    operator: '系统管理员', remark: '', createdAtText: '2026-06-13 09:10:00',
  },
]

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
  // 商品候选列表：最近一年有交易的优先，按销量高→低；关键字非空则模糊查询
  'POST /api/base/goods/sale-ranking': body => {
    const kw = String(body?.keyword || '').trim().toLowerCase()
    const limit = Number(body?.limit) || 20
    // mock 最近一年销量：SP001 最多，SP003 一年内无交易
    const saleQty = { SP001: 120, SP002: 45, SP003: 0 }
    let rows = goodsRows.map(g => ({ ...g, saleQty: saleQty[g.goodsCode] ?? 0 }))
    if (kw) {
      rows = rows.filter(r =>
        String(r.goodsCode || '').toLowerCase().includes(kw)
        || String(r.goodsName || '').toLowerCase().includes(kw)
        || String(r.simpleCode || '').toLowerCase().includes(kw)
        || String(r.barcode || '').toLowerCase().includes(kw))
    }
    // 有交易的优先 → 销量降序 → 编号兜底
    return rows.sort((a, b) => {
      const ta = a.saleQty > 0 ? 1 : 0
      const tb = b.saleQty > 0 ? 1 : 0
      if (ta !== tb) return tb - ta
      if (b.saleQty !== a.saleQty) return b.saleQty - a.saleQty
      return String(a.goodsCode).localeCompare(String(b.goodsCode))
    }).slice(0, limit)
  },
  // 销售取价：客户商品价 > 客户价格组价 > 商品标价。mock 按 unitLevel 返回不同价，便于验证「按单位取价」
  'GET /api/base/goods/sale-price': (body, req) => {
    const q = new URL(req.url, 'http://x').searchParams
    const level = Number(q.get('unitLevel')) || 1
    // SP001 小单位配了客户价，中/大单位只有价格组价 —— 用于观察降级
    const customer = { 1: 1.75, 2: null, 3: null }[level] ?? null
    const group = { 1: 1.85, 2: 10.8, 3: 33.0 }[level] ?? null
    const standard = { 1: 2.0, 2: 11.5, 3: 35.0 }[level] ?? null
    let price = 0, priceSource = 'NONE', priceSourceText = '未设价'
    if (customer > 0) { price = customer; priceSource = 'CUSTOMER'; priceSourceText = '客户专属价' }
    else if (group > 0) { price = group; priceSource = 'PRICE_GROUP'; priceSourceText = '价格组 PG01' }
    else if (standard > 0) { price = standard; priceSource = 'GOODS_STANDARD'; priceSourceText = '商品标价' }
    return {
      goodsCode: q.get('goodsCode'), customerCode: q.get('customerCode'), unitLevel: level,
      price, priceSource, priceSourceText,
      customerPrice: customer, priceGroupPrice: group, priceGroupCode: 'PG01', standardPrice: standard,
    }
  },
  'POST /api/base/customer-price-adjust/page': () => page(priceAdjustRows),
  'GET /api/base/customer-price-adjust/detail': () => ({ ...priceAdjustRows[0], details: goodsRows.map(g => ({ ...g, originalPrice: g.standardPrice, currentPrice: g.goodsCode === 'SP001' ? '34.50' : '46.80' })) }),
  'POST /api/base/customer-price-adjust/create': body => ({ adjustId: 'CPA-ID-001', adjustNo: 'CPA202606140001', status: 'PENDING', detailCount: (body.details || []).length }),
  'POST /api/base/customer-price-adjust/update': () => true,
  'POST /api/base/customer-price-adjust/audit': () => ({ status: 'APPROVED', effect: '已生成客户价格，历史有效价格自动停用' }),
  'POST /api/base/customer-price-adjust/cancel': () => true,
  'POST /api/base/customer-price-adjust/import': () => ({ createdAdjustCount: 2, successRows: 120, failedRows: 0, templateFields: ['门店编号', '商品编号', '现价'] }),
  'POST /api/base/customer-price/query': () => page(customerPriceRows),
  'POST /api/base/customer-price-change-log/page': () => page(customerPriceChangeRows),
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
