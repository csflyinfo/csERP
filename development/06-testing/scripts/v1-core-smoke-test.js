const BASE = process.env.API_BASE || 'http://localhost:8080/api'

let authToken = 'demo-token'   // 登录前占位；登录后替换为真实 JWT

async function post(path, body = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + authToken },
    body: JSON.stringify(body),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

async function get(path) {
  const res = await fetch(`${BASE}${path}`, { headers: { Authorization: 'Bearer ' + authToken } })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

async function login() {
  const res = await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`/auth/login failed: ${json.message}`)
  authToken = json.data.token
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

/**
 * 采购闭环端到端测试 —— Step D 新增。
 *
 * 覆盖真实 REST 链路（区别于 /flow/v1-core/self-test 的内存 mock 流程）：
 *   1. 建资料：仓库 + 供应商 + 商品（唯一后缀，可重复运行）
 *   2. 创建 PO → /purchase/order/create
 *   3. 审核 PO → /purchase/order/audit（状态 APPROVED，inbound_status='待入库'）
 *   4. 引入订单预填 → /purchase/inbound/from-order（remainQty = orderQty）
 *   5. 拆行创建入库单 → /purchase/inbound/create（把 100 拆成 60+40 两个批次）
 *   6. 审核入库单 → /purchase/inbound/audit
 *      · pur_receipt 自动生成（PENDING）
 *      · base_goods.latest_purchase_price 同步（通过商品详情验证）
 *      · purchase_order.inbound_status 更新为「已入库」
 *   7. 审核收货单 → /purchase/receipt/audit（写 fin_ap，ap_status='已生成'）
 *   8. 反审核收货单 → /purchase/receipt/reverse-audit（fin_ap 删除，ap_status 恢复未生成）
 */
async function testPurchaseCloseLoop() {
  const suffix = String(Date.now()).slice(-8)
  const warehouseCode = 'WHT' + suffix
  const warehouseName = '测试仓库-' + suffix
  const supplierCode = 'SPT' + suffix
  const supplierName = '测试供应商-' + suffix
  const goodsCode = 'GDT' + suffix
  const goodsName = '测试商品-' + suffix

  // 1. 资料
  await post('/base/warehouse/create', {
    warehouseCode, warehouseName,
    warehouseType: '正常仓', inventoryType: '平台主仓', costGroup: 'CG01',
  })
  await post('/base/supplier/create', {
    supplierCode, supplierName, supplierType: '普通供应商',
    settlementMethod: '月结30天',
  })
  await post('/base/goods/create', {
    goodsCode, goodsName, spec: '测试规格', baseUnit: '箱',
    goodsType: '正常商品', taxRate: '13%',
    latestPurchasePrice: 0,
  })

  // 2. 创建 PO：单价 35 * 100 = 3500
  const orderCreate = await post('/purchase/order/create', {
    supplierCode, supplierName, buyer: '张三', warehouseId: warehouseName,
    billDate: new Date().toISOString().slice(0, 10),
    details: [{
      goodsCode, goodsName, spec: '测试规格',
      unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 100, baseQty: 100, price: 35, amount: 3500,
      taxRate: '13%',
    }],
  })
  assert(orderCreate.orderId, 'PO create should return orderId')
  assert(Number(orderCreate.amount) === 3500, 'PO amount should be 3500')

  // 3. 审核 PO
  const orderAudit = await post('/purchase/order/audit', { orderId: orderCreate.orderId })
  assert(orderAudit.status === 'APPROVED', 'PO should be APPROVED after audit')

  // 4. 引入订单预填
  const preFilled = await get(`/purchase/inbound/from-order?orderNo=${encodeURIComponent(orderCreate.orderNo)}`)
  assert(preFilled.orderNo === orderCreate.orderNo, 'from-order should echo orderNo')
  assert(preFilled.details.length === 1, 'from-order should return 1 detail line')
  const line = preFilled.details[0]
  assert(Number(line.remainQty) === 100, 'remainQty should equal orderQty for fresh PO')
  assert(Number(line.price) === 35, 'unit price should pass through from order')

  // 5. 拆行入库：60 + 40（两个批次）
  const inboundCreate = await post('/purchase/inbound/create', {
    sourceOrder: orderCreate.orderNo,
    supplier: preFilled.supplier,
    warehouse: preFilled.warehouse,
    billDate: new Date().toISOString().slice(0, 10),
    details: [
      {
        goodsCode, goodsName, unitName: '箱',
        batchNo: 'BATCH-A-' + suffix, receivedQty: 60, price: 35,
      },
      {
        goodsCode, goodsName, unitName: '箱',
        batchNo: 'BATCH-B-' + suffix, receivedQty: 40, price: 35,
      },
    ],
  })
  assert(inboundCreate.inboundId, 'inbound create should return inboundId')
  assert(inboundCreate.status === 'PENDING', 'new inbound should be PENDING')

  // 5.1. 超量拆行应被后端拒绝
  let overQtyRejected = false
  try {
    await post('/purchase/inbound/create', {
      sourceOrder: orderCreate.orderNo,
      details: [{
        goodsCode, goodsName, unitName: '箱',
        batchNo: 'OVER-' + suffix, receivedQty: 999, price: 35,
      }],
    })
  } catch (e) {
    overQtyRejected = /超过订单剩余/.test(e.message)
  }
  assert(overQtyRejected, 'inbound quantity exceeding order remainder should be rejected')

  // 6. 审核入库单
  const inboundAudit = await post('/purchase/inbound/audit', { bizId: inboundCreate.inboundId })
  assert(inboundAudit.status === 'APPROVED', 'inbound should be APPROVED')
  assert(inboundAudit.receiptNo && inboundAudit.receiptNo.startsWith('CGSH'),
      'inbound audit should return auto-generated receiptNo starting with CGSH')
  const receiptNo = inboundAudit.receiptNo

  // 6.1. 商品 latest_purchase_price 已同步
  const goodsAfter = await post('/base/goods/page', { pageNo: 1, pageSize: 10, filters: { goodsCode } })
  const goodsRow = (goodsAfter.records || []).find(r => (r.goodsCode || r.GOODS_CODE) === goodsCode)
  assert(goodsRow, 'test goods should exist')
  const latestPrice = Number(goodsRow.latestPurchasePrice ?? goodsRow.LATEST_PURCHASE_PRICE)
  assert(latestPrice === 35, `base_goods.latest_purchase_price should be 35, got ${latestPrice}`)

  // 6.2. 订单 inbound_status = '已入库'
  const orderPage = await post('/purchase/order/page', { pageNo: 1, pageSize: 50, filters: {} })
  const orderRow = (orderPage.records || []).find(r =>
      (r.orderNo || r.orderno) === orderCreate.orderNo)
  assert(orderRow, 'order should be visible in page')
  const inboundStatus = orderRow.inboundStatus || orderRow.inboundstatus
  assert(inboundStatus === '已入库',
      `order inbound_status should be '已入库', got '${inboundStatus}'`)

  // 6.3. 收货单已生成（PENDING）
  const receiptPage = await post('/purchase/receipt/page', { pageNo: 1, pageSize: 50, filters: {} })
  const receiptRow = (receiptPage.records || []).find(r => r.receiptNo === receiptNo)
  assert(receiptRow, `receipt ${receiptNo} should be in list`)
  assert(receiptRow.status === 'PENDING', 'auto-generated receipt should be PENDING')
  assert(receiptRow.apStatus === '未生成', 'auto-generated receipt ap_status should be 未生成')
  // 采购走【价内税】：goods_amount 3500 本身就是含税金额，税额从里面倒算出来
  //   tax_amount   = 3500 × 13% ÷ 113% = 402.65
  //   final_amount = 含税 3500 − 税额 402.65 = 3097.35（不含税货款，仅供参考，不用于结算）
  // 销售发货单（V52 起）口径完全对称：签收金额 3500 含税、税额 402.65、不含税 3097.35，见下文 7。
  // 两边最终落到结算的都是「含税金额」：应付取 goods_amount 3500，应收取 sign_amount 3500。
  assert(Number(receiptRow.goodsAmount) === 3500, 'receipt goods_amount should be 3500 (含税)')
  assert(Number(receiptRow.taxAmount) === 402.65,
      `receipt tax_amount 应为价内税倒算 3500×13%÷113% = 402.65，got ${receiptRow.taxAmount}`)
  assert(Number(receiptRow.finalAmount) === 3097.35,
      `receipt final_amount 应为含税 3500 − 税额 402.65 = 3097.35（不含税），got ${receiptRow.finalAmount}`)

  // 6.4. 收货单明细：两个批次 → 聚合为 1 行 goods_code
  const receiptDetail = await get(`/purchase/receipt/detail?receiptId=${encodeURIComponent(receiptNo)}`)
  assert(Array.isArray(receiptDetail.details) && receiptDetail.details.length === 1,
      'receipt detail should aggregate all batches into 1 goods line')
  assert(Number(receiptDetail.details[0].qty) === 100, 'receipt detail qty should sum to 100')

  // 7. 审核收货单
  const receiptAudit = await post('/purchase/receipt/audit', { bizId: receiptNo })
  assert(receiptAudit.status === 'APPROVED', 'receipt should be APPROVED')
  assert(receiptAudit.apNo && receiptAudit.apNo.startsWith('AP'),
      'receipt audit should generate fin_ap with AP-prefixed no')

  // 7.1. fin_ap 已落地
  const apPage = await post('/finance/ap/page', { pageNo: 1, pageSize: 50, filters: {} })
  const apRow = (apPage.records || []).find(r =>
      (r.apNo || r.APNO || r.apno) === receiptAudit.apNo)
  assert(apRow, `fin_ap ${receiptAudit.apNo} should exist`)
  const apAmount = Number(apRow.apAmount ?? apRow.APAMOUNT ?? apRow.apamount)
  assert(apAmount === 3500,
      `采购应付取【含税金额】= goods_amount 3500（不是不含税的 final_amount 3097.35），got ${apAmount}`)
  const paidAmount = Number(apRow.paidAmount ?? apRow.PAIDAMOUNT ?? apRow.paidamount)
  assert(paidAmount === 0, 'fin_ap paid_amount should be 0')

  // 8. 反审核收货单 → fin_ap 应被删除
  const receiptReverse = await post('/purchase/receipt/reverse-audit', { bizId: receiptNo })
  assert(receiptReverse.status === 'PENDING', 'receipt should be PENDING after reverse audit')
  const apPage2 = await post('/finance/ap/page', { pageNo: 1, pageSize: 50, filters: {} })
  const apStillThere = (apPage2.records || []).find(r =>
      (r.apNo || r.APNO || r.apno) === receiptAudit.apNo)
  assert(!apStillThere, 'fin_ap should be removed after receipt reverse audit')

  console.log('  · purchase close-loop verified:',
      `PO=${orderCreate.orderNo} → PI=${inboundCreate.inboundNo} → PR=${receiptNo} → AP=${receiptAudit.apNo} → reverse OK`)
}

/**
 * 销售闭环端到端测试 —— 销售模块整体迁移后追加。
 *
 * 覆盖真实 REST 链路：
 *   1. 建资料：仓库 + 供应商 + 客户 + 商品；先跑采购入库把库存做起来（销售才有货可出）
 *   2. 创建销售订单 → 审核 → outbound_status='待出库'
 *   3. /sales/outbound/from-order 预填 + /sales/outbound/available-batches 拿批次列表
 *   4. 拆行创建出库单：60+40 两条明细分别指定不同批次（同商品）
 *   5. 出库审核 → 扣减 inv_batch_stock，回写 sales_order.outbound_status='已出库'，生成 sales_receipt(PENDING)
 *   6. 收货单审核 → 写 fin_ar 生成 AR 单
 *   7. 收货单反审核 → fin_ar 删除
 */
async function testSalesCloseLoop() {
  const suffix = String(Date.now()).slice(-8)
  const warehouseName = '销测仓库-' + suffix
  const supplierCode = 'SPS' + suffix
  const supplierName = '销测供应商-' + suffix
  const customerCode = 'CTS' + suffix
  const customerName = '销测客户-' + suffix
  const goodsCode = 'GDS' + suffix
  const goodsName = '销测商品-' + suffix

  // 1. 建资料
  await post('/base/warehouse/create', {
    warehouseCode: 'WHS' + suffix, warehouseName,
    warehouseType: '正常仓', inventoryType: '平台主仓', costGroup: 'CG01',
  })
  await post('/base/supplier/create', { supplierCode, supplierName, supplierType: '普通供应商', settlementMethod: '现结' })
  await post('/base/customer/create', {
    customerCode, customerName, channelType: '零售商超',
    salesman: '销售员-' + suffix, accountPeriodType: '现结',
  })
  await post('/base/goods/create', {
    goodsCode, goodsName, spec: '销测规格', baseUnit: '箱',
    goodsType: '正常商品', taxRate: '13%',
  })

  // 2. 先跑采购入库把库存做起来：下 PO 100 → 审核 → 拆两批次入库（BS-A 60, BS-B 40）→ 审核
  const poCreate = await post('/purchase/order/create', {
    supplierCode, supplierName, buyer: '张三', warehouseId: warehouseName,
    billDate: new Date().toISOString().slice(0, 10),
    details: [{
      goodsCode, goodsName, spec: '销测规格',
      unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 100, baseQty: 100, price: 20, amount: 2000,
      taxRate: '13%',
    }],
  })
  await post('/purchase/order/audit', { orderId: poCreate.orderId })

  const batchA = 'BS-A-' + suffix
  const batchB = 'BS-B-' + suffix
  const piCreate = await post('/purchase/inbound/create', {
    sourceOrder: poCreate.orderNo,
    supplier: supplierName,
    warehouse: warehouseName,
    billDate: new Date().toISOString().slice(0, 10),
    details: [
      { goodsCode, goodsName, unitName: '箱', batchNo: batchA, receivedQty: 60, price: 20 },
      { goodsCode, goodsName, unitName: '箱', batchNo: batchB, receivedQty: 40, price: 20 },
    ],
  })
  await post('/purchase/inbound/audit', { bizId: piCreate.inboundId })

  // 3. 创建销售订单
  const soCreate = await post('/sales/order/create', {
    customerCode, customerName, salesman: '销售员-' + suffix, warehouseId: warehouseName,
    billDate: new Date().toISOString().slice(0, 10),
    details: [{
      goodsCode, goodsName, unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 100, baseQty: 100, price: 35, amount: 3500,
      taxRate: '13%', salesAttribute: '正常',
    }],
  })
  assert(soCreate.orderId, 'sales order create should return orderId')

  // 3.1 审核销售订单
  const soAudit = await post('/sales/order/audit', { orderId: soCreate.orderId })
  assert(soAudit.status === 'APPROVED', 'sales order should be APPROVED')

  // 4. 引入订单预填
  const preFilled = await get(`/sales/outbound/from-order?orderNo=${encodeURIComponent(soCreate.orderNo)}`)
  assert(preFilled.orderNo === soCreate.orderNo, 'from-order should echo orderNo')
  assert(preFilled.details.length === 1, 'from-order should return 1 detail line')
  assert(Number(preFilled.details[0].remainQty) === 100, 'remain qty should be 100')
  assert(Number(preFilled.details[0].price) === 35, 'price should pass through 35')

  // 4.1 拿可用批次
  const batches = await get(
      `/sales/outbound/available-batches?goodsCode=${encodeURIComponent(goodsCode)}&warehouse=${encodeURIComponent(warehouseName)}`)
  assert(Array.isArray(batches) && batches.length >= 2, 'should have at least 2 batches available')

  // 5. 拆行出库：60(batchA) + 40(batchB)
  const souCreate = await post('/sales/outbound/create', {
    sourceOrder: soCreate.orderNo,
    customer: preFilled.customer,
    warehouse: preFilled.warehouse,
    billDate: new Date().toISOString().slice(0, 10),
    details: [
      { goodsCode, goodsName, unitName: '箱', qty: 60, price: 35, batchNo: batchA },
      { goodsCode, goodsName, unitName: '箱', qty: 40, price: 35, batchNo: batchB },
    ],
  })
  assert(souCreate.outboundId, 'sales outbound create should return outboundId')

  // 5.1 超量校验
  let overQtyRejected = false
  try {
    await post('/sales/outbound/create', {
      sourceOrder: soCreate.orderNo,
      details: [{ goodsCode, goodsName, unitName: '箱', qty: 999, price: 35, batchNo: batchA }],
    })
  } catch (e) {
    overQtyRejected = /超过订单剩余/.test(e.message)
  }
  assert(overQtyRejected, 'sales outbound quantity exceeding order remainder should be rejected')

  // 6. 审核出库单
  const souAudit = await post('/sales/outbound/audit', { bizId: souCreate.outboundId })
  assert(souAudit.status === 'APPROVED', 'sales outbound should be APPROVED')
  assert(souAudit.receiptNo && souAudit.receiptNo.startsWith('XSFH'),
      'outbound audit should return auto-generated SR receiptNo starting with XSFH')
  const salesReceiptNo = souAudit.receiptNo

  // 6.1 销售订单 outbound_status='已出库'
  const soPage = await post('/sales/order/page', { pageNo: 1, pageSize: 50, filters: {} })
  const soRow = (soPage.records || []).find(r =>
      (r.orderNo || r.orderno) === soCreate.orderNo)
  assert(soRow, 'sales order should be visible')
  const outStatus = soRow.outboundStatus || soRow.outboundstatus
  assert(outStatus === '已出库', `sales order outbound_status should be '已出库', got '${outStatus}'`)

  // 6.2 生单口径（V52）：发货金额 = 100 × 35 = 3500（含税），
  //     签收金额 / 拒收金额 / 税额 / 不含税金额一律 0，要等签收才有
  const srPage = await post('/sales/receipt/page', { pageNo: 1, pageSize: 50, filters: {} })
  const srRow = (srPage.records || []).find(r => r.receiptNo === salesReceiptNo)
  assert(srRow, `sales receipt ${salesReceiptNo} should be in list`)
  assert(srRow.status === 'PENDING', 'auto-generated receipt should be PENDING')
  assert(Number(srRow.deliverAmount) === 3500,
      `sales receipt deliver_amount should be 3500, got ${srRow.deliverAmount}`)
  assert(Number(srRow.signAmount) === 0, `生单时签收金额应为 0，got ${srRow.signAmount}`)
  assert(Number(srRow.rejectAmount) === 0, `生单时拒收金额应为 0，got ${srRow.rejectAmount}`)
  assert(Number(srRow.taxAmount) === 0, `生单时税额应为 0，got ${srRow.taxAmount}`)
  assert(Number(srRow.untaxedAmount) === 0, `生单时不含税金额应为 0，got ${srRow.untaxedAmount}`)

  // 6.3 收货明细：两个批次聚合为 1 行
  const srDetail = await get(`/sales/receipt/detail?receiptId=${encodeURIComponent(salesReceiptNo)}`)
  assert(Array.isArray(srDetail.details) && srDetail.details.length === 1,
      'sales receipt detail should aggregate all batches into 1 line')
  assert(Number(srDetail.details[0].qty) === 100, 'sales receipt qty should sum to 100')

  // 7. 确认签收（全部签收）→ 自动审核生成应收
  //    应收金额按签收数量算，所以必须先签收；未签收直接调 /audit 会被拒绝（见 7.2）。
  const srSign = await post('/sales/receipt/sign', { receiptId: salesReceiptNo })
  assert(srSign.signStatus === '已签收', `全部签收后签收状态应为「已签收」，got ${srSign.signStatus}`)
  assert(srSign.status === 'APPROVED', `签收后应自动审核为 APPROVED，got ${srSign.status}`)
  assert(srSign.arNo && srSign.arNo.startsWith('AR'),
      'sign should auto-generate fin_ar with AR-prefix')
  const srAudit = { status: srSign.status, arNo: srSign.arNo }
  // 全部签收 → 签收金额 = 发货金额 3500；价内税 3500 × 13% ÷ 1.13 = 402.65；不含税 3097.35
  // 与采购完全对称（采购入库同一算法）
  assert(Number(srSign.deliverAmount) === 3500,
      `发货金额应保持 3500，got ${srSign.deliverAmount}`)
  assert(Number(srSign.signAmount) === 3500,
      `全部签收时签收金额应等于发货金额 3500，got ${srSign.signAmount}`)
  assert(Number(srSign.rejectAmount) === 0, `无拒收时拒收金额应为 0，got ${srSign.rejectAmount}`)
  assert(Number(srSign.taxAmount) === 402.65,
      `税额应为价内倒算 3500 × 13% ÷ 1.13 = 402.65，got ${srSign.taxAmount}`)
  assert(Number(srSign.untaxedAmount) === 3097.35,
      `不含税金额应为 3500 − 402.65 = 3097.35，got ${srSign.untaxedAmount}`)

  // 7.1 fin_ar 已落地
  const arPage = await post('/finance/ar/page', { pageNo: 1, pageSize: 50, filters: {} })
  const arRow = (arPage.records || []).find(r =>
      (r.arNo || r.ARNO || r.arno) === srAudit.arNo)
  assert(arRow, `fin_ar ${srAudit.arNo} should exist`)
  const arAmount = Number(arRow.arAmount ?? arRow.ARAMOUNT ?? arRow.aramount)
  assert(arAmount === 3500,
      `应收取【签收金额（含税）】100×35 = 3500（与采购应付取含税对称），got ${arAmount}`)

  // 7.2 已生成应收后重复签收应被拒绝
  let signAgainErr = ''
  try { await post('/sales/receipt/sign', { receiptId: salesReceiptNo }) }
  catch (e) { signAgainErr = e.message || '' }
  assert(/已生成应收账款|已签收/.test(signAgainErr),
      `已签收单据重复签收应被拒绝，got "${signAgainErr}"`)

  // 8. 反审核收货单 → fin_ar 应被删除
  const srReverse = await post('/sales/receipt/reverse-audit', { bizId: salesReceiptNo })
  assert(srReverse.status === 'PENDING', 'sales receipt should be PENDING after reverse audit')
  const arPage2 = await post('/finance/ar/page', { pageNo: 1, pageSize: 50, filters: {} })
  const arStillThere = (arPage2.records || []).find(r =>
      (r.arNo || r.ARNO || r.arno) === srAudit.arNo)
  assert(!arStillThere, 'fin_ar should be removed after receipt reverse audit')

  console.log('  · sales close-loop verified:',
      `SO=${soCreate.orderNo} → SOU=${souCreate.outboundNo} → SR=${salesReceiptNo} → AR=${srAudit.arNo} → reverse OK`)
}

/**
 * 清理本次冒烟测试链路产生的所有数据。
 *
 * <p>调用后端 /testing/cleanup-smoke 一次性删除：
 *   - 库存流水/批次库存/库存余额（按 GDT/GDS 前缀 goods_code）
 *   - 应付/应收（按 PR/SR 前缀 source_bill）
 *   - 采购/销售链的订单/入库/出库/收货单（按前缀 PO/PI/PR/SO/SOU/SR）
 *   - 测试基础资料（GDT/GDS/WHT/WHS/CTT/CTS/SPT/SPS 前缀）
 *   - 相关操作日志
 *
 * <p>失败时不抛异常（cleanup 是收尾动作，不该阻断测试结果的判定）。
 */
async function cleanupSmokeData() {
  try {
    const counts = await post('/testing/cleanup-smoke', {})
    const total = counts._total_rows_deleted ?? 0
    if (total > 0) {
      console.log(`  · cleanup: removed ${total} rows across ${Object.keys(counts).length - 1} tables`)
    } else {
      console.log('  · cleanup: nothing to clean')
    }
  } catch (e) {
    // login 都没成功的情况可能会到这里；只提示，不抛
    console.warn('  · cleanup skipped:', e.message || e)
  }
}

async function main() {
  // 登录拿 token
  await login()

  try {
    // ============ 原有 mock 流程自检 ============
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

    // ============ 采购闭环真实链路（Step D 新增） ============
    await testPurchaseCloseLoop()

    // ============ 销售闭环真实链路（销售模块整体迁移后追加） ============
    await testSalesCloseLoop()

    console.log('V1 core smoke test passed')
  } finally {
    // 无论成败都清理本次测试产生的数据，避免污染用户手工建的资料
    await cleanupSmokeData()
  }
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
