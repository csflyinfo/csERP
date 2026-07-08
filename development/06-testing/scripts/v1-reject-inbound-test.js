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

/** 只关心 HTTP 状态码的原始 POST —— 用于验证「不存在的端点」（如新建）。 */
async function rawPost(path, body = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + authToken },
    body: JSON.stringify(body),
  })
  let json = null
  try { json = await res.json() } catch { /* 404 可能不是 JSON */ }
  return { status: res.status, json }
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

/** 断言某个请求被后端拒绝，且错误信息命中给定正则。 */
async function assertRejected(fn, pattern, message) {
  let rejected = false
  let actual = ''
  try {
    await fn()
  } catch (e) {
    actual = e.message || String(e)
    rejected = pattern.test(actual)
  }
  assert(rejected, `${message}（实际：${actual || '请求居然成功了'}）`)
}

/**
 * 拒收入库单端到端测试 —— 拒收入库模块新增后追加。
 *
 * <p><b>计价口径前提（很重要，别写成按批次算成本的用例）</b>：系统按「商品 + 仓库」维度做移动加权平均，
 * 出库写流水取的是 {@code inv_stock_balance.cost_price}，<b>不是 inv_batch_stock.cost_price</b>
 * （见 {@code InventoryCostService.salesOutbound}）。所以<b>同一张单据、同一商品拆几个批次，
 * 成本单价都是同一个值</b>；采购入库同理。本用例因此：
 *   · 断言出库单同商品两个批次行的成本单价<b>相等</b>（把这条规则钉成回归）
 *   · 不靠「不同批次不同成本」去区分快照与当前均价 —— 那在真实系统里不存在。
 *     改用<b>时点差</b>：出库后再采一批更贵的货（@32）把当前均价推高，
 *     此时拒收入库仍必须用<b>出库时点</b>的 20，而不是审核时点的 32。
 *
 * <p>覆盖链路：
 *   1. 建资料 + 采购入库做库存：100 箱 @20，同一商品两个批次、<b>生产日期不同</b>（20260110 / 20260520）
 *   2. 销售订单 → 审核 → 出库单（带司机，两条明细分别走两个批次）→ 断言两行成本单价相等 = 20
 *   3. 出库审核 → 自动生成发货单，司机一路快照下来；库存清零
 *   4. <b>再采一批 @32</b>（生产日期 20260701）→ 当前库存成本均价被推到 32
 *   5. /sales/receipt/sign 部分拒收（10 箱）→ 自动生成 JSRK 拒收入库单
 *      · 规则一：明细<b>每个商品一行</b>，批次号/生产日期取<b>生产日期最新</b>的那批（20260520）
 *      · 规则二：成本单价 = <b>出库时点</b>的 20，<b>不是</b>审核时点的当前均价 32
 *   6. 不能新建：/sales/reject-inbound/create 端点不存在
 *   7. 可编辑：/update 改入库数量（10 → 6），超过拒收数量被拒绝，成本单价改不动
 *   8. 审核：写 direction='IN' 库存流水（成本 20），inv_stock_balance 重算为
 *      (10×32 + 6×20) ÷ 16 = 27.5
 *   9. 已审核时撤销签收被拒绝 → 反审核（按批次扣回）→ 撤销签收删单
 */
async function testRejectInboundCloseLoop(scope) {
  const suffix = String(Date.now()).slice(-8)
  const warehouseCode = 'WHS' + suffix
  const warehouseName = '拒收仓库-' + suffix
  const supplierCode = 'SPS' + suffix
  const supplierName = '拒收供应商-' + suffix
  const customerCode = 'CTS' + suffix
  const customerName = '拒收客户-' + suffix
  const goodsCode = 'GDS' + suffix
  const goodsName = '拒收商品-' + suffix
  const salesman = '业务员-' + suffix
  const driver = '司机-' + suffix
  const today = new Date().toISOString().slice(0, 10)

  // 三个批次：生产日期不同，批次号按全局规则由生产日期派生（yyyyMMdd）
  const prodDateOld = '2026-01-10'
  const prodDateNew = '2026-05-20'
  const prodDateLater = '2026-07-01'   // 出库之后才采进来的那批
  const batchOld = '20260110'
  const batchNew = '20260520'
  const batchLater = '20260701'

  // 成本：第一次采购 @20（出库时点成本），出库后再采 @32 把当前均价推高
  const costAtOutbound = 20
  const costLater = 32
  // 审核后重算：(10 × 32 + 6 × 20) ÷ 16 = 440 ÷ 16 = 27.5
  const costAfterReject = 27.5

  // ---------- 1. 建资料 ----------
  scope.goodsCodes.push(goodsCode)
  scope.warehouseCodes.push(warehouseCode)
  scope.customerCodes.push(customerCode)
  scope.supplierCodes.push(supplierCode)

  await post('/base/warehouse/create', {
    warehouseCode, warehouseName,
    warehouseType: '正常仓', inventoryType: '平台主仓', costGroup: 'CG01',
  })
  await post('/base/supplier/create', {
    supplierCode, supplierName, supplierType: '普通供应商', settlementMethod: '现结',
  })
  await post('/base/customer/create', {
    customerCode, customerName, channelType: '零售商超',
    salesman, accountPeriodType: '现结',
  })
  await post('/base/goods/create', {
    goodsCode, goodsName, spec: '拒收规格', baseUnit: '箱',
    goodsType: '正常商品', taxRate: '13%',
  })

  // ---------- 2. 采购入库做库存：100 箱 @20，拆成 60(旧批次) + 40(新批次) ----------
  const poCreate = await post('/purchase/order/create', {
    supplierCode, supplierName, buyer: '张三', warehouseId: warehouseName,
    billDate: today,
    details: [{
      goodsCode, goodsName, spec: '拒收规格',
      unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 100, baseQty: 100, price: 20, amount: 2000, taxRate: '13%',
    }],
  })
  scope.billNos.push(poCreate.orderNo)
  await post('/purchase/order/audit', { orderId: poCreate.orderId })

  const piCreate = await post('/purchase/inbound/create', {
    sourceOrder: poCreate.orderNo,
    supplier: supplierName,
    warehouse: warehouseName,
    billDate: today,
    details: [
      { goodsCode, goodsName, unitName: '箱', productionDate: prodDateOld, receivedQty: 60, price: 20 },
      { goodsCode, goodsName, unitName: '箱', productionDate: prodDateNew, receivedQty: 40, price: 20 },
    ],
  })
  scope.billNos.push(piCreate.inboundNo)
  const piAudit = await post('/purchase/inbound/audit', { bizId: piCreate.inboundId })
  scope.billNos.push(piAudit.receiptNo)

  // 2.1 批次号应按生产日期派生（全局规则：yyyyMMdd，无前缀）
  const batches = await get(`/sales/outbound/available-batches`
      + `?goodsCode=${encodeURIComponent(goodsCode)}&warehouse=${encodeURIComponent(warehouseName)}`)
  const batchNos = (batches || []).map(b => String(b.batchNo ?? b.batch_no))
  assert(batchNos.includes(batchOld) && batchNos.includes(batchNew),
      `批次号应按生产日期派生为 ${batchOld}/${batchNew}，实际：${batchNos.join(',')}`)

  // ---------- 3. 销售订单 → 审核 ----------
  const soCreate = await post('/sales/order/create', {
    customerCode, customerName, salesman, warehouseId: warehouseName,
    billDate: today,
    details: [{
      goodsCode, goodsName, unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 100, baseQty: 100, price: 35, amount: 3500,
      taxRate: '13%', salesAttribute: '正常',
    }],
  })
  scope.billNos.push(soCreate.orderNo)
  await post('/sales/order/audit', { orderId: soCreate.orderId })

  // ---------- 4. 出库单：带司机，两条明细走两个批次 ----------
  // 不传 costPrice，让后端按「商品 + 仓库」当前均价取值 —— 两行必然拿到同一个 20
  const souCreate = await post('/sales/outbound/create', {
    sourceOrder: soCreate.orderNo,
    customer: customerName,
    warehouse: warehouseName,
    salesman,
    driver,
    billDate: today,
    details: [
      { goodsCode, goodsName, unitName: '箱', qty: 60, price: 35, batchNo: batchOld, productionDate: prodDateOld },
      { goodsCode, goodsName, unitName: '箱', qty: 40, price: 35, batchNo: batchNew, productionDate: prodDateNew },
    ],
  })
  assert(souCreate.outboundId, '出库单创建应返回 outboundId')
  scope.billNos.push(souCreate.outboundNo)

  // 4.0 钉死计价口径：同一单据同一商品拆两个批次，成本单价必须相等
  const souDetail = await get(`/sales/outbound/detail?outboundId=${encodeURIComponent(souCreate.outboundId)}`)
  const souLines = (souDetail.details || []).filter(d => String(d.goodsCode) === goodsCode)
  assert(souLines.length === 2, `出库单应有 2 行同商品明细，实际 ${souLines.length}`)
  const souCosts = souLines.map(d => Number(d.costPrice))
  assert(souCosts[0] === souCosts[1],
      `同一单据同一商品不同批次的成本单价必须相等（系统按商品+仓库加权平均计价，不按批次），实际 ${souCosts.join(' / ')}`)
  assert(souCosts[0] === costAtOutbound,
      `出库成本单价应为当前均价 ${costAtOutbound}，实际 ${souCosts[0]}`)

  // 4.1 出库单列表带出司机
  const souPage = await post('/sales/outbound/page', { pageNo: 1, pageSize: 50, filters: { q: souCreate.outboundNo } })
  const souRow = (souPage.records || []).find(r => r.outboundNo === souCreate.outboundNo)
  assert(souRow, `出库单 ${souCreate.outboundNo} 应在列表中`)
  assert(souRow.driver === driver, `出库单司机应为 ${driver}，实际 ${souRow.driver}`)

  // ---------- 5. 出库审核 → 自动生成发货单，司机快照下来 ----------
  const souAudit = await post('/sales/outbound/audit', { bizId: souCreate.outboundId })
  assert(souAudit.status === 'APPROVED', '出库单审核后应为 APPROVED')
  const receiptNo = souAudit.receiptNo
  assert(receiptNo && receiptNo.startsWith('XSFH'), '出库审核应生成 XSFH 前缀发货单')
  scope.billNos.push(receiptNo)

  const srDetail = await get(`/sales/receipt/detail?receiptId=${encodeURIComponent(receiptNo)}`)
  assert(srDetail.driver === driver, `发货单司机应从出库单快照为 ${driver}，实际 ${srDetail.driver}`)
  assert(srDetail.signStatusText === '待签收', `新发货单签收状态应为「待签收」，实际 ${srDetail.signStatusText}`)
  assert(Array.isArray(srDetail.details) && srDetail.details.length === 1,
      '发货单明细应把两个批次聚合为 1 行')
  const srLine = srDetail.details[0]
  assert(Number(srLine.qty) === 100, `发货数量应为 100，实际 ${srLine.qty}`)

  // 5.0 生单口径（V52）：只有发货金额，签收/拒收/税额/不含税金额一律 0
  assert(Number(srDetail.deliverAmount) === 3500,
      `生单时发货金额应为 100 × 35 = 3500，实际 ${srDetail.deliverAmount}`)
  for (const [k, label] of [['signAmount', '签收金额'], ['rejectAmount', '拒收金额'],
    ['taxAmount', '税额'], ['untaxedAmount', '不含税金额']]) {
    assert(Number(srDetail[k] || 0) === 0, `生单时${label}应为 0，实际 ${srDetail[k]}`)
  }

  // 5.1 拒收数量填了但没填原因 → 应被拒绝
  await assertRejected(
      () => post('/sales/receipt/sign', {
        receiptId: receiptNo,
        details: [{ detailId: srLine.detailId, rejectQty: 10 }],
      }),
      /必须填写拒收原因/, '拒收未填原因应被拒绝')

  // 5.2 签收数量 + 拒收数量 ≠ 发货数量 → 应被拒绝
  await assertRejected(
      () => post('/sales/receipt/sign', {
        receiptId: receiptNo,
        details: [{ detailId: srLine.detailId, signedQty: 80, rejectQty: 10, rejectReason: '商品破损' }],
      }),
      /应等于发货数量/, '签收+拒收数量不等于发货数量应被拒绝')

  // ---------- 5.3 出库后再采一批更贵的货 @32：把当前库存成本均价推高 ----------
  // 出库把库存清零了，所以这批进来后当前均价 = 32。后面拒收入库必须仍用「出库时点」的 20。
  const po2 = await post('/purchase/order/create', {
    supplierCode, supplierName, buyer: '张三', warehouseId: warehouseName,
    billDate: today,
    details: [{
      goodsCode, goodsName, spec: '拒收规格',
      unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 10, baseQty: 10, price: costLater, amount: 10 * costLater, taxRate: '13%',
    }],
  })
  scope.billNos.push(po2.orderNo)
  await post('/purchase/order/audit', { orderId: po2.orderId })

  const pi2 = await post('/purchase/inbound/create', {
    sourceOrder: po2.orderNo,
    supplier: supplierName,
    warehouse: warehouseName,
    billDate: today,
    details: [
      { goodsCode, goodsName, unitName: '箱', productionDate: prodDateLater, receivedQty: 10, price: costLater },
    ],
  })
  scope.billNos.push(pi2.inboundNo)
  const pi2Audit = await post('/purchase/inbound/audit', { bizId: pi2.inboundId })
  scope.billNos.push(pi2Audit.receiptNo)

  // 5.4 确认当前均价已被推到 32 —— 这样后面的 20 才有区分度
  const balAfterPo2 = await post('/inventory/balance/page', {
    pageNo: 1, pageSize: 50, filters: { q: goodsCode },
  })
  const balRowPo2 = (balAfterPo2.records || []).find(r => String(r.goodsCode) === goodsCode)
  assert(balRowPo2, `库存余额应有 ${goodsCode} 的行`)
  assert(Number(balRowPo2.costPrice) === costLater,
      `再采一批后当前库存成本均价应为 ${costLater}，实际 ${balRowPo2.costPrice}`)
  const batchPagePo2 = await post('/inventory/batch/page', {
    pageNo: 1, pageSize: 50, filters: { goodsCode },
  })
  const batchRowPo2 = (batchPagePo2.records || []).find(r =>
      r.goodsCode === goodsCode && r.warehouse === warehouseName && r.batchNo === batchLater)
  assert(Number(batchRowPo2?.physicalQty ?? 0) === 10,
      `新采批次 ${batchLater} 应有 10 箱，实际 ${batchRowPo2?.physicalQty}`)

  // ---------- 6. 确认签收：拒收 10 箱 → 自动生成拒收入库单 ----------
  const signResult = await post('/sales/receipt/sign', {
    receiptId: receiptNo,
    details: [{ detailId: srLine.detailId, rejectQty: 10, rejectReason: '商品破损' }],
  })
  assert(signResult.signStatus === '部分拒收', `签收状态应为「部分拒收」，实际 ${signResult.signStatus}`)
  assert(Number(signResult.rejectQty) === 10, `拒收数量应为 10，实际 ${signResult.rejectQty}`)
  const rejectNo = signResult.rejectInboundNo
  assert(rejectNo && rejectNo.startsWith('JSRK'),
      `应自动生成 JSRK 前缀拒收入库单，实际 ${rejectNo}`)
  scope.billNos.push(rejectNo)

  // 6.0 金额口径（V52）：发货金额出库审核时定死不动，签收金额/拒收金额/税额/不含税金额签收时才有。
  //     100 箱 × 35（含税）→ 发货金额 3500；签收 90 箱 → 签收金额 3150、拒收金额 350；
  //     价内税倒算 3150 × 13% ÷ 1.13 = 362.39，不含税 3150 − 362.39 = 2787.61。
  //     应收取【签收金额（含税）】3150 —— 拒收的 10 箱不开票给客户。
  const deliverAmount = 3500
  const signAmount = 3150
  const rejectAmount = 350
  const taxAmount = 362.39
  const untaxedAmount = 2787.61
  assert(Number(signResult.deliverAmount) === deliverAmount,
      `发货金额应保持 ${deliverAmount}（签收不改它），实际 ${signResult.deliverAmount}`)
  assert(Number(signResult.signAmount) === signAmount,
      `签收金额应为 90 × 35 = ${signAmount}，实际 ${signResult.signAmount}`)
  assert(Number(signResult.rejectAmount) === rejectAmount,
      `拒收金额应为 10 × 35 = ${rejectAmount}，实际 ${signResult.rejectAmount}`)
  assert(Number(signResult.taxAmount) === taxAmount,
      `税额应为价内倒算 ${taxAmount}，实际 ${signResult.taxAmount}`)
  assert(Number(signResult.untaxedAmount) === untaxedAmount,
      `不含税金额应为 签收金额 − 税额 = ${untaxedAmount}，实际 ${signResult.untaxedAmount}`)
  assert(signResult.status === 'APPROVED', `签收后应自动审核，实际 ${signResult.status}`)
  assert(signResult.arNo && signResult.arNo.startsWith('AR'),
      `签收应自动生成应收，实际 ${signResult.arNo}`)
  const arPage = await post('/finance/ar/page', { pageNo: 1, pageSize: 50, filters: { q: receiptNo } })
  const arRow = (arPage.records || []).find(r => r.sourceBill === receiptNo)
  assert(arRow, `应收应挂在发货单 ${receiptNo} 上`)
  assert(Number(arRow.arAmount) === signAmount,
      `应收金额应为签收金额（含税）${signAmount}（不是发货金额 ${deliverAmount}，拒收的 10 箱不开票），实际 ${arRow.arAmount}`)

  // 6.1 重复签收应被拒绝（幂等保护）
  await assertRejected(
      () => post('/sales/receipt/sign', {
        receiptId: receiptNo,
        details: [{ detailId: srLine.detailId, rejectQty: 5, rejectReason: '商品破损' }],
      }),
      /已生成应收账款|已签收/, '重复签收应被拒绝')

  // ---------- 7. 校验拒收入库单：主单来源信息 + 最新生产日期批次 + 原出库成本 ----------
  const rj = await get(`/sales/reject-inbound/detail?id=${encodeURIComponent(rejectNo)}`)
  assert(rj.sourceReceiptNo === receiptNo, `拒收入库单发货单号应为 ${receiptNo}，实际 ${rj.sourceReceiptNo}`)
  assert(rj.sourceOutboundNo === souCreate.outboundNo,
      `拒收入库单出库单号应为 ${souCreate.outboundNo}，实际 ${rj.sourceOutboundNo}`)
  assert(rj.sourceOrderNo === soCreate.orderNo,
      `拒收入库单销售订单号应为 ${soCreate.orderNo}，实际 ${rj.sourceOrderNo}`)
  assert(rj.driver === driver, `拒收入库单司机应为 ${driver}，实际 ${rj.driver}`)
  assert(rj.customerCode === customerCode, '拒收入库单应带出客户编号')
  assert(rj.warehouse === warehouseName, '拒收入库单应带出仓库')
  assert(rj.status === 'PENDING', `新生成的拒收入库单应为 PENDING，实际 ${rj.status}`)
  assert(rj.stockUpdated === false, '新生成的拒收入库单不应已更新库存')

  assert(Array.isArray(rj.details) && rj.details.length === 1,
      `拒收明细应每个商品一行，实际 ${rj.details?.length} 行`)
  const rjLine = rj.details[0]
  assert(Number(rjLine.rejectQty) === 10, `明细拒收数量应为 10，实际 ${rjLine.rejectQty}`)
  assert(Number(rjLine.qty) === 10, `明细本次入库数量默认应等于拒收数量 10，实际 ${rjLine.qty}`)
  assert(rjLine.batchNo === batchNew,
      `批次号应取生产日期最新的那批 ${batchNew}，实际 ${rjLine.batchNo}`)
  assert(String(rjLine.productionDate).startsWith(prodDateNew),
      `生产日期应取最新的 ${prodDateNew}，实际 ${rjLine.productionDate}`)
  assert(Number(rjLine.costPrice) === costAtOutbound,
      `成本单价应取「出库时点」的成本 ${costAtOutbound}，而不是审核时点的当前均价 ${costLater}，实际 ${rjLine.costPrice}`)

  // 7.1 发货单列表/详情能反查到拒收入库单号
  const srDetail2 = await get(`/sales/receipt/detail?receiptId=${encodeURIComponent(receiptNo)}`)
  assert(srDetail2.rejectInboundNo === rejectNo, '发货单详情应能反查拒收入库单号')
  assert(srDetail2.signStatus === '部分拒收', '发货单签收状态应落库为「部分拒收」')
  assert(Number(srDetail2.details[0].signedQty) === 90, '签收数量应为 90')
  assert(Number(srDetail2.details[0].rejectQty) === 10, '拒收数量应为 10')

  // ---------- 8. 不能新建 ----------
  const createProbe = await rawPost('/sales/reject-inbound/create', {})
  assert(createProbe.status === 404 || createProbe.status === 405
        || (createProbe.json && createProbe.json.code !== '0'),
      `拒收入库单不应提供新建端点，实际 HTTP ${createProbe.status}`)

  // ---------- 9. 可编辑 ----------
  // 9.1 入库数量超过拒收数量 → 拒绝
  await assertRejected(
      () => post('/sales/reject-inbound/update', {
        inboundId: rejectNo,
        details: [{ detailId: rjLine.detailId, qty: 20 }],
      }),
      /超过签收拒收数量/, '入库数量超过拒收数量应被拒绝')

  // 9.2 正常改量 10 → 6，同时试图篡改成本单价（应被忽略）
  const upd = await post('/sales/reject-inbound/update', {
    inboundId: rejectNo,
    remark: '破损 4 箱客户自行处理，实际回库 6 箱',
    details: [{ detailId: rjLine.detailId, qty: 6, costPrice: 999 }],
  })
  assert(Number(upd.qty) === 6, `修改后入库数量应为 6，实际 ${upd.qty}`)
  const rjAfterUpd = await get(`/sales/reject-inbound/detail?id=${encodeURIComponent(rejectNo)}`)
  assert(Number(rjAfterUpd.details[0].costPrice) === costAtOutbound,
      `成本单价不可被前端覆盖，应仍为 ${costAtOutbound}，实际 ${rjAfterUpd.details[0].costPrice}`)
  assert(Number(rjAfterUpd.details[0].rejectQty) === 10,
      '拒收数量是签收快照，不应被编辑改动')
  assert(Number(rjAfterUpd.costAmount) === 6 * costAtOutbound,
      `成本金额应为 6 × ${costAtOutbound} = ${6 * costAtOutbound}，实际 ${rjAfterUpd.costAmount}`)

  // ---------- 10. 审核：按入库流水走，用原出库成本重算 ----------
  const rjAudit = await post('/sales/reject-inbound/audit', { bizId: rejectNo })
  assert(rjAudit.status === 'APPROVED', `拒收入库单审核后应为 APPROVED，实际 ${rjAudit.status}`)

  const rjApproved = await get(`/sales/reject-inbound/detail?id=${encodeURIComponent(rejectNo)}`)
  assert(rjApproved.stockUpdated === true, '审核后 stock_updated 应为 true')
  assert(Number(rjApproved.qty) === 6, '审核后主单数量应为 6')

  // 10.1 库存流水：一条 direction='IN'，入库金额 = 6 × 出库时点成本 20 = 120
  // 注意 inv_stock_ledger.cost_price 的口径：purchaseInbound 往这一列写的是「入库后的库存成本均价」
  // （见 InventoryCostService.purchaseInbound 第 4 步 writeLedger(..., newCost, inboundAmount, ...)），
  // 全系统所有采购入库都是这个语义，不是拒收入库的特例。所以「用了出库时点成本」要看 amount，不是 cost_price。
  const ledgerPage = await post('/inventory/ledger/page', {
    pageNo: 1, pageSize: 50, filters: { q: rejectNo },
  })
  const inRows = (ledgerPage.records || []).filter(r => r.sourceBill === rejectNo)
  assert(inRows.length === 1, `拒收入库审核应产生 1 条流水，实际 ${inRows.length} 条`)
  const inRow = inRows[0]
  assert(inRow.direction === '入库', `流水方向应为入库，实际 ${inRow.direction}`)
  assert(Number(inRow.qty) === 6, `流水数量应为 6，实际 ${inRow.qty}`)
  assert(Number(inRow.amount) === 6 * costAtOutbound,
      `入库金额应为 6 × 出库时点成本 ${costAtOutbound} = ${6 * costAtOutbound}`
      + `（若按审核时点均价 ${costLater} 算会是 ${6 * costLater}），实际 ${inRow.amount}`)
  assert(Number(inRow.costPrice) === costAfterReject,
      `流水 cost_price 是入库后的库存均价 ${costAfterReject}，实际 ${inRow.costPrice}`)
  assert(inRow.batchNo === batchNew, `流水批次应为 ${batchNew}，实际 ${inRow.batchNo}`)

  // 10.2 库存余额：现存 10 箱 @32，按 20 入 6 箱 → (10×32 + 6×20) ÷ 16 = 27.5
  const balPage = await post('/inventory/balance/page', {
    pageNo: 1, pageSize: 50, filters: { goodsCode },
  })
  const balRow = (balPage.records || []).find(r => r.goodsCode === goodsCode && r.warehouse === warehouseName)
  assert(balRow, `库存余额应有 ${goodsCode} / ${warehouseName} 一行`)
  assert(Number(balRow.physicalQty) === 16, `库存数量应为 16（10 箱新采 + 6 箱拒收回库），实际 ${balRow.physicalQty}`)
  assert(Number(balRow.costPrice) === costAfterReject,
      `重算后库存成本应为 (10×${costLater} + 6×${costAtOutbound}) ÷ 16 = ${costAfterReject}，实际 ${balRow.costPrice}`)

  // 10.3 批次库存：回到最新生产日期那批
  const batchPage = await post('/inventory/batch/page', {
    pageNo: 1, pageSize: 50, filters: { goodsCode },
  })
  const batchRow = (batchPage.records || []).find(r =>
      r.goodsCode === goodsCode && r.warehouse === warehouseName && r.batchNo === batchNew)
  assert(batchRow, `批次库存应有 ${batchNew} 一行`)
  assert(Number(batchRow.physicalQty) === 6, `批次 ${batchNew} 数量应为 6，实际 ${batchRow.physicalQty}`)

  // 10.4 重复审核应被拒绝
  await assertRejected(
      () => post('/sales/reject-inbound/audit', { bizId: rejectNo }),
      /仅待审核/, '已审核的拒收入库单重复审核应被拒绝')

  // ---------- 11. 已审核入库时撤销签收必须被拒绝 ----------
  await assertRejected(
      () => post('/sales/receipt/unsign', { bizId: receiptNo }),
      /无法撤销签收|反审核/, '拒收入库单已审核时撤销签收应被拒绝')

  // ---------- 12. 反审核：按批次扣回 6 箱，只剩后采的 10 箱 ----------
  const rjReverse = await post('/sales/reject-inbound/reverse-audit', { bizId: rejectNo })
  assert(rjReverse.status === 'PENDING', `反审核后应回到 PENDING，实际 ${rjReverse.status}`)
  const balPage2 = await post('/inventory/balance/page', {
    pageNo: 1, pageSize: 50, filters: { goodsCode },
  })
  const balRow2 = (balPage2.records || []).find(r => r.goodsCode === goodsCode && r.warehouse === warehouseName)
  assert(Number(balRow2?.physicalQty ?? 0) === 10,
      `反审核后应只剩后采的 10 箱，实际 ${balRow2?.physicalQty}`)
  // 注意：移动加权平均天生不可逆 —— 反审核扣回数量，但成本均价不会精确还原到 32。
  // 这是全系统一致的行为（采购入库反审核同样如此），不是拒收入库的特例，所以这里不断言成本。
  const batchPage2 = await post('/inventory/batch/page', {
    pageNo: 1, pageSize: 50, filters: { goodsCode },
  })
  const batchRow2 = (batchPage2.records || []).find(r =>
      r.goodsCode === goodsCode && r.warehouse === warehouseName && r.batchNo === batchNew)
  assert(Number(batchRow2?.physicalQty ?? 0) === 0,
      `反审核后批次 ${batchNew} 应归零，实际 ${batchRow2?.physicalQty}`)

  // ---------- 13. 撤销签收：清空签收登记 + 删除拒收入库单 + 撤销应收 + 金额还原 ----------
  await post('/sales/receipt/unsign', { bizId: receiptNo })
  const srDetail3 = await get(`/sales/receipt/detail?receiptId=${encodeURIComponent(receiptNo)}`)
  assert(srDetail3.signStatus === '待签收', `撤销签收后应回到「待签收」，实际 ${srDetail3.signStatus}`)
  assert(Number(srDetail3.details[0].rejectQty) === 0, '撤销签收后拒收数量应清零')
  assert(!srDetail3.rejectInboundNo, '撤销签收后拒收入库单应被删除')
  assert(srDetail3.status === 'PENDING', `撤销签收后应退回 PENDING，实际 ${srDetail3.status}`)
  // 撤销签收 = 回到生单口径：发货金额不动，签收才产生的四个金额全部归 0
  assert(Number(srDetail3.deliverAmount) === deliverAmount,
      `撤销签收后发货金额应保持 ${deliverAmount}，实际 ${srDetail3.deliverAmount}`)
  assert(Number(srDetail3.signAmount) === 0, `撤销签收后签收金额应归 0，实际 ${srDetail3.signAmount}`)
  assert(Number(srDetail3.rejectAmount) === 0, `撤销签收后拒收金额应归 0，实际 ${srDetail3.rejectAmount}`)
  assert(Number(srDetail3.taxAmount) === 0, `撤销签收后税额应归 0，实际 ${srDetail3.taxAmount}`)
  assert(Number(srDetail3.untaxedAmount) === 0,
      `撤销签收后不含税金额应归 0，实际 ${srDetail3.untaxedAmount}`)
  const arPage2 = await post('/finance/ar/page', { pageNo: 1, pageSize: 50, filters: { q: receiptNo } })
  assert(!(arPage2.records || []).find(r => r.sourceBill === receiptNo),
      '撤销签收后自动生成的应收应被删除')

  const rjPage = await post('/sales/reject-inbound/page', { pageNo: 1, pageSize: 50, filters: { q: rejectNo } })
  const rjStillThere = (rjPage.records || []).find(r => r.inboundNo === rejectNo)
  assert(!rjStillThere, `拒收入库单 ${rejectNo} 应已随撤销签收删除`)

  // ---------- 14. 重新签收：全部拒收 → 签收金额 0，不生成应收也不自动审核 ----------
  const signAll = await post('/sales/receipt/sign', {
    receiptId: receiptNo,
    details: [{ detailId: srLine.detailId, rejectQty: 100, rejectReason: '客户临时取消' }],
  })
  assert(signAll.signStatus === '全部拒收', `全量拒收签收状态应为「全部拒收」，实际 ${signAll.signStatus}`)
  assert(signAll.rejectInboundNo && signAll.rejectInboundNo.startsWith('JSRK'),
      '全部拒收也应生成拒收入库单')
  scope.billNos.push(signAll.rejectInboundNo)
  assert(Number(signAll.signAmount) === 0, `全部拒收签收金额应为 0，实际 ${signAll.signAmount}`)
  assert(Number(signAll.rejectAmount) === deliverAmount,
      `全部拒收时拒收金额应等于发货金额 ${deliverAmount}，实际 ${signAll.rejectAmount}`)
  assert(Number(signAll.taxAmount) === 0, `全部拒收税额应为 0，实际 ${signAll.taxAmount}`)
  assert(Number(signAll.untaxedAmount) === 0, `全部拒收不含税金额应为 0，实际 ${signAll.untaxedAmount}`)
  assert(!signAll.arNo, `全部拒收不应生成应收，实际 ${signAll.arNo}`)
  assert(signAll.status === 'PENDING', `全部拒收不应自动审核，实际 ${signAll.status}`)
  // 全部拒收时手工审核也应被拒绝（没有开票对象）
  await assertRejected(
      () => post('/sales/receipt/audit', { bizId: receiptNo }),
      /全部拒收/, '全部拒收的发货单手工审核应被拒绝')
  // 收尾：撤销签收，把发货单还原成干净状态
  await post('/sales/receipt/unsign', { bizId: receiptNo })

  console.log('  · reject-inbound close-loop verified:',
      `SO=${soCreate.orderNo} → SOU=${souCreate.outboundNo} → SR=${receiptNo}`
      + ` → 签收拒收10 → ${rejectNo}（批次${batchNew}/成本取出库时点${costAtOutbound}，非当前均价${costLater}）`
      + ` → 改量6 → 审核（均价重算为${costAfterReject}）→ 反审核 → 撤销签收 OK`)
}

/**
 * 精准清理本次测试产生的数据 —— 走 /testing/cleanup-scoped，只删点名的单号 / 编码。
 *
 * <p><b>刻意不用 /testing/cleanup-smoke</b>：那个端点按单号前缀（XSDD/XSCK/XSFH/JSRK…）全表清，
 * 会连带删掉<b>用户手工建的同前缀单据</b>。本脚本把自己造的单号收集起来点名删除，
 * 因此可以安全地跑在有真实业务数据的库上。
 *
 * <p>失败时不抛异常（cleanup 是收尾动作，不该阻断测试结果的判定）。
 */
async function cleanupScoped(scope) {
  try {
    const counts = await post('/testing/cleanup-scoped', scope)
    const total = counts._total_rows_deleted ?? 0
    console.log(`  · cleanup(scoped): removed ${total} rows`
        + `（单号 ${scope.billNos.filter(Boolean).length} 张 / 商品 ${scope.goodsCodes.length} 个）`)
  } catch (e) {
    console.warn('  · cleanup skipped:', e.message || e)
  }
}

async function main() {
  await login()
  // 测试过程中造出来的单号往这里塞，finally 里点名清理
  const scope = { billNos: [], goodsCodes: [], warehouseCodes: [], customerCodes: [], supplierCodes: [] }
  try {
    await testRejectInboundCloseLoop(scope)
    console.log('V1 reject inbound test passed')
  } finally {
    await cleanupScoped(scope)
  }
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
