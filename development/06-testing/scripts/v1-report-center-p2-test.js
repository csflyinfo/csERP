/**
 * 报表中心二期（#8~#16）验收测试 —— 真实单据链路 + DWS 重算 + 九表取数断言。
 *
 * 运行：
 *   API_BASE=http://localhost:8081/api node development/06-testing/scripts/v1-report-center-p2-test.js
 * 默认打本机 8080；隔离验收用 8081 空库（worktree）。
 *
 * 业务场景（全部发生在当天，单一商品+客户+仓库+业务员）：
 *   1. 采购 100 @20 入库（库存 100，移动加权成本 20）
 *   2. 销售订单 80 @35 → 出库 80 → 司机签收 60 / 拒收 20
 *      · 签收金额 60×35 = 2100（含税）；自动生成 JSRK 20，回库成本 400
 *      · 签收成本 = 出库成本 1600 − 拒收成本 400 = 1200
 *   3. 销售退货 5 @35（THRK），退货成本 = 5×20 = 100
 *   4. 收款 1000（审核即 FIFO 自动核销）→ 本期回款 1000、应收余额 925（退货红冲175）
 *
 * 期末库存：100 − 80 + 20(JSRK) + 5(THRK) = 45，金额 900。
 *
 * 报表侧期望（期间=当天）：
 *   #16 明细两行：签收 +60/2100/成本1200，退货 −5/−175/成本−100；净 55/1925/1100/毛利825
 *   #10/#11/#14 汇总同口径；#14 另有去重客户数=1
 *   #12 客户汇总：签收单数1、净1925、回款1000、应收925、逾期0
 *   #13 业务员汇总：客户数1、新客户数1、回款率 1000/2100
 *   #15 订单执行：订80/出80/签60/拒收20/签收金额2100；状态过滤切换全量路径
 *   #8 进销存：期初0、采购入100、销退入5、其他入20(拒收)、销售出80、期末45
 *   #9 台账：无商品+仓库/超92天 必拒；期初虚拟行+4笔流水；期末45
 *
 * 脱敏验收：另建「仅报表查看、无任何 VIEW_* 字段码」角色+用户，
 *   数量可见、单价/金额/成本/毛利/回款应被置 null；未授权报表码返回非 0。
 *
 * 说明：测试数据带唯一后缀，可重复执行；使用标准单据前缀，不调用 /testing/cleanup-smoke
 * （避免影响同库其他数据），建议在隔离空库上执行。
 */
const BASE = process.env.API_BASE || 'http://localhost:8080/api'

let authToken = 'demo-token'

async function request(method, path, body, token = authToken) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const json = await res.json()
  return json
}

async function post(path, body = {}, token = authToken) {
  const json = await request('POST', path, body, token)
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

/** 预期业务失败（用于护栏断言），返回 {code,message}。 */
async function postExpectFail(path, body = {}, token = authToken) {
  const json = await request('POST', path, body, token)
  if (json.code === '0') throw new Error(`${path} 应失败但成功了`)
  return json
}

async function get(path, token = authToken) {
  const res = await fetch(`${BASE}${path}`, { headers: { Authorization: 'Bearer ' + token } })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

async function login(username, password) {
  const json = await request('POST', '/auth/login', { username, password }, null)
  if (json.code !== '0') throw new Error(`/auth/login failed: ${json.message}`)
  return json.data.token
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

const num = v => Number(v ?? 0)
/** 金额按 2 位小数容差比较。 */
function assertNum(actual, expected, label) {
  const a = Number(actual)
  assert(Math.abs(a - expected) < 0.011, `${label} 应为 ${expected}，实际 ${actual}`)
}

const today = new Date().toISOString().slice(0, 10)
const sfx = String(Date.now()).slice(-8)

const CTX = {
  warehouseCode: 'RPW' + sfx,
  warehouseName: '报测仓库-' + sfx,
  supplierCode: 'RPS' + sfx,
  supplierName: '报测供应商-' + sfx,
  customerCode: 'RPC' + sfx,
  customerName: '报测客户-' + sfx,
  goodsCode: 'RPG' + sfx,
  goodsName: '报测商品-' + sfx,
  salesman: '报测业务员-' + sfx,
  driver: '报测司机-' + sfx,
  batchNo: '20260801',
  productionDate: '2026-08-01',
  today,
}

// 标准报表入参
const rptBody = (filters = {}, extra = {}) => ({
  dateRange: { startDate: today, endDate: today },
  filters,
  pageNo: 1,
  pageSize: 100,
  ...extra,
})

// 明细类报表的页面响应不内嵌合计（引擎返回零占位），合计走独立 /summary 接口
const rptSummary = (code, body, token = authToken) =>
  post(`/report/center/${code}/summary`, body, token)

// ========== 1. 业务数据链路 ==========
async function buildBusinessChain() {
  const c = CTX
  // 1.1 基础资料
  await post('/base/warehouse/create', {
    warehouseCode: c.warehouseCode, warehouseName: c.warehouseName,
    warehouseType: '正常仓', inventoryType: '平台主仓', costGroup: 'CG01',
  })
  await post('/base/supplier/create', {
    supplierCode: c.supplierCode, supplierName: c.supplierName,
    supplierType: '普通供应商', settlementMethod: '月结30天',
  })
  await post('/base/customer/create', {
    customerCode: c.customerCode, customerName: c.customerName,
    channelType: '零售商超', salesman: c.salesman, accountPeriodType: '现结',
  })
  await post('/base/goods/create', {
    goodsCode: c.goodsCode, goodsName: c.goodsName, spec: '报测规格',
    baseUnit: '箱', goodsType: '正常商品', taxRate: '13%', latestPurchasePrice: 0,
  })

  // 1.2 采购 100 @20 → 审核 → 入库 100（单批次）→ 审核
  const po = await post('/purchase/order/create', {
    supplierCode: c.supplierCode, supplierName: c.supplierName, buyer: '张三',
    warehouseId: c.warehouseName, billDate: today,
    details: [{
      goodsCode: c.goodsCode, goodsName: c.goodsName, spec: '报测规格',
      unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 100, baseQty: 100, price: 20, amount: 2000, taxRate: '13%',
    }],
  })
  await post('/purchase/order/audit', { orderId: po.orderId })
  const pi = await post('/purchase/inbound/create', {
    sourceOrder: po.orderNo, supplier: c.supplierName, warehouse: c.warehouseName,
    billDate: today,
    details: [{
      goodsCode: c.goodsCode, goodsName: c.goodsName, unitName: '箱',
      productionDate: c.productionDate, receivedQty: 100, price: 20,
    }],
  })
  await post('/purchase/inbound/audit', { bizId: pi.inboundId })

  // 1.3 销售订单 80 @35 → 审核
  const so = await post('/sales/order/create', {
    customerCode: c.customerCode, customerName: c.customerName,
    salesman: c.salesman, warehouseId: c.warehouseName, billDate: today,
    details: [{
      goodsCode: c.goodsCode, goodsName: c.goodsName,
      unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 80, baseQty: 80, price: 35, amount: 2800,
      taxRate: '13%', salesAttribute: '正常',
    }],
  })
  await post('/sales/order/audit', { orderId: so.orderId })
  CTX.orderNo = so.orderNo

  // 1.4 出库 80（带司机、指定批次）→ 审核 → XSFH
  const sou = await post('/sales/outbound/create', {
    sourceOrder: so.orderNo, customer: c.customerName, warehouse: c.warehouseName,
    salesman: c.salesman, driver: c.driver, billDate: today,
    details: [{
      goodsCode: c.goodsCode, goodsName: c.goodsName, unitName: '箱',
      qty: 80, price: 35, batchNo: c.batchNo, productionDate: c.productionDate,
    }],
  })
  const souAudit = await post('/sales/outbound/audit', { bizId: sou.outboundId })
  CTX.outboundNo = sou.outboundNo
  CTX.receiptNo = souAudit.receiptNo

  // 1.5 签收 60 + 拒收 20 → 部分签收，自动 JSRK
  const srDetail = await get(`/sales/receipt/detail?receiptId=${encodeURIComponent(CTX.receiptNo)}`)
  const srLine = srDetail.details[0]
  const sign = await post('/sales/receipt/sign', {
    receiptId: CTX.receiptNo,
    details: [{ detailId: srLine.detailId, signedQty: 60, rejectQty: 20, rejectReason: '报测破损' }],
  })
  // 签收 API 自身状态：有拒收即「部分拒收」；#15 报表按签收量另归类为「部分签收」
  assert(sign.signStatus === '部分拒收', `签收状态应为部分拒收，实际 ${sign.signStatus}`)
  assertNum(sign.signAmount, 2100, '签收金额')
  assert(sign.rejectInboundNo && sign.rejectInboundNo.startsWith('JSRK'),
      `应生成 JSRK 拒收单，实际 ${sign.rejectInboundNo}`)
  CTX.arNo = sign.arNo
  CTX.rejectNo = sign.rejectInboundNo

  // 1.5b JSRK 仅自动生成、状态为待审核/未入库，必须仓库审核后才按原出库成本回库过账
  const rjAudit = await post('/sales/reject-inbound/audit', { bizId: CTX.rejectNo })
  assertNum(rjAudit.qty, 20, 'JSRK 回库数量')
  assertNum(rjAudit.costAmount, 400, 'JSRK 回库成本（20×20）')

  // 1.6 销售退货 5 @35：申请 → 确认 → 推送仓库 → 退货入库审核 → 退货单审核
  const sra = await post('/sales/return-order/create', {
    customer: c.customerName, customerCode: c.customerCode, warehouse: c.warehouseName,
    returnType: 'WAREHOUSE', returnReason: '报测退货',
    details: [{
      goodsCode: c.goodsCode, goodsName: c.goodsName, spec: '报测规格', unitName: '箱',
      qty: 5, price: 35, taxRate: '13%', returnMode: 'BY_GOODS', batchNo: c.batchNo,
    }],
  })
  await post('/sales/return-order/confirm', { bizId: sra.applyNo })
  const push = await post('/sales/return-order/push-warehouse', { bizId: sra.applyNo })
  assert(push.inboundNo && push.inboundNo.startsWith('THRK'),
      `推送仓库应返回 THRK 入库单，实际 ${push.inboundNo}`)
  CTX.returnInboundNo = push.inboundNo
  await post('/sales/return-inbound/audit', { bizId: push.inboundNo })
  await post('/sales/return-order/audit', { bizId: sra.applyNo })
  CTX.returnApplyNo = sra.applyNo

  // 1.7 收款 1000（资金账户）→ 审核 → 核销应收 1000
  const faCode = 'RPF' + sfx
  const faName = '报测现金账户-' + sfx
  await post('/base/master/save', {
    moduleCode: 'fundAccount', fundAccountCode: faCode, fundAccountName: faName,
    parentCode: '01', accountType: '现金', glAccountCode: '1001', status: 'NORMAL',
  })
  const rc = await post('/finance/receipt/create', {
    receiptDate: today, counterpartyType: 'CUSTOMER',
    counterpartyCode: c.customerCode, counterpartyName: c.customerName,
    handler: '张三', summary: '报测回款',
    details: [{ fundAccount: faName, amount: 1000, remark: '货款' }],
  })
  // 收款单审核即按 FIFO 自动核销该客户未结清 AR 并写 fin_reconcile_record
  //（FinanceController#auditReceipt → reconcileAndRecord）；不能再调 /receipt/reconcile，
  // 否则同一笔 1000 会写两条核销记录，#12/#13「本期回款」翻倍。
  await post('/finance/receipt/audit', { receiptId: rc.receiptId })

  console.log('  · 业务链路完成：',
      `PO=${po.orderNo} → PI=${pi.inboundNo}；SO=${so.orderNo} → SOU=${CTX.outboundNo}`
      + ` → SR=${CTX.receiptNo}(签60/拒20, AR=${CTX.arNo})；JSRK=${CTX.rejectNo}`
      + `；THRK=${CTX.returnInboundNo}；回款核销 1000`)
}

// ========== 2. DWS 重算（维度刷新 + 销售预聚合 + 库存流水全量重建） ==========
async function rebuildDws() {
  const salesRec = await post('/report/center/admin/recompute-sales', {})
  assert(salesRec.balanced === true, `销售 DWS 对账应平衡：${JSON.stringify(salesRec)}`)
  const moveRows = await post('/report/center/admin/rebuild-stock-move', {})
  assert(num(moveRows.rows) >= 4, `库存流水 DWS 至少应重建 4 行，实际 ${moveRows.rows}`)
  console.log('  · DWS 重算完成：', JSON.stringify(salesRec), `stockMoveRows=${moveRows.rows}`)
}

// ========== 3. #16 商品销售明细表 ==========
async function testSalesMoveDetail() {
  const c = CTX
  const data = await post('/report/center/sales_move_detail/page', rptBody({
    customer: c.customerCode,
  }))
  const rows = data.records.filter(r => r.goodsCode === c.goodsCode)
  assert(rows.length === 2, `#16 应只有签收/退货 2 行，实际 ${rows.length}`)
  const signRow = rows.find(r => r.billType === '销售签收')
  const retRow = rows.find(r => r.billType === '销售退货')
  assert(signRow && retRow, '#16 两行类型应为 销售签收/销售退货')
  assertNum(signRow.baseQty, 60, '#16 签收数量')
  assertNum(signRow.amount, 2100, '#16 签收金额')
  assertNum(signRow.costAmount, 1200, '#16 签收成本（1600−400）')
  assertNum(signRow.unitCostBase, 20, '#16 签收单位成本')
  assertNum(signRow.grossProfit, 900, '#16 签收毛利')
  assert(signRow.billNo === c.receiptNo, '#16 签收行单据号应为发货单号')
  // 后台代签时「司机」列按设计优先取签收操作人（司机端签收时即司机本人），本链路为 admin
  assert(signRow.driver === '系统管理员' || signRow.driver === c.driver,
      `#16 签收行司机应取签收操作人/出库司机，实际 ${signRow.driver}`)
  assertNum(retRow.baseQty, -5, '#16 退货数量（负）')
  assertNum(retRow.amount, -175, '#16 退货金额（负）')
  assertNum(retRow.costAmount, -100, '#16 退货成本（负）')
  assertNum(retRow.grossProfit, -75, '#16 退货毛利')
  assert(retRow.billNo === c.returnInboundNo, '#16 退货行单据号应为 THRK 入库单号')

  const body = rptBody({ customer: c.customerCode })
  const s = await rptSummary('sales_move_detail', body)
  assertNum(s.lineCount, 2, '#16 合计行数')
  assertNum(s.baseQty, 55, '#16 合计净数量')
  assertNum(s.amount, 1925, '#16 合计净金额')
  assertNum(s.costAmount, 1100, '#16 合计成本')
  assertNum(s.grossProfit, 825, '#16 合计毛利')
  assertNum(s.signedQtyBase, 60, '#16 合计签收数量')
  assertNum(s.returnQtyBase, -5, '#16 合计退货数量')

  // billType 过滤
  const onlySignBody = rptBody({ customer: c.customerCode, billType: '销售签收' })
  const onlySign = await post('/report/center/sales_move_detail/page', onlySignBody)
  assert(onlySign.records.length === 1 && onlySign.records[0].billType === '销售签收',
      '#16 billType=销售签收 应只回 1 行')
  const onlySignSum = await rptSummary('sales_move_detail', onlySignBody)
  assertNum(onlySignSum.baseQty, 60, '#16 仅签收合计数量')

  console.log('  · #16 商品销售明细表 OK（签60/2100/成本1200，退−5/−175/成本−100，净55/1925/825）')
}

// ========== 4. #10/#11/#14 销售汇总三表 ==========
async function testSalesSummaries() {
  const c = CTX
  const expected = (rows) => {
    assert(rows.length === 1, `应恰好 1 个叶子行，实际 ${rows.length}：${JSON.stringify(rows).slice(0, 300)}`)
    const r = rows[0]
    assertNum(r.signedQtyBase, 60, '签收数量')
    assertNum(r.signedAmount, 2100, '签收金额')
    assertNum(r.returnQtyBase, 5, '退货数量')
    assertNum(r.returnAmount, 175, '退货金额')
    assertNum(r.netQtyBase, 55, '净销售数量')
    assertNum(r.netAmount, 1925, '净销售额')
    assertNum(r.costAmount, 1100, '成本金额')
    assertNum(r.grossProfit, 825, '毛利额')
    assert(Math.abs(num(r.grossProfitRate) - 825 / 1925) < 0.0001,
        `毛利率应为 ${825 / 1925}，实际 ${r.grossProfitRate}`)
    return r
  }

  // #10 商品销售汇总（按商品）
  const g = await post('/report/center/sales_goods_summary/page',
      rptBody({ goods: c.goodsCode }, { groupBy: ['goods'], pageSize: 100000 }))
  expected(g.records)
  assert(g.records[0].goodsCode === c.goodsCode, '#10 行商品编号应匹配')
  assertNum(g.summary.netAmount, 1925, '#10 合计净销售额')
  assertNum(g.summary.grossProfit, 825, '#10 合计毛利')

  // #11 客户商品销售汇总（客户+商品）；按本轮客户过滤，隔离库内历史轮次数据
  const cg = await post('/report/center/customer_goods_summary/page',
      rptBody({ customer: c.customerCode }, { groupBy: ['customer', 'goods'], pageSize: 100000 }))
  expected(cg.records)
  assert(cg.records[0].customerCode === c.customerCode, '#11 行客户编号应匹配')

  // #11 服务端强制：分组必须含客户
  const bad = await postExpectFail('/report/center/customer_goods_summary/page',
      rptBody({}, { groupBy: ['goods'], pageSize: 100000 }))
  assert(/客户/.test(bad.message), `#11 无客户分组应报中文错误，实际：${bad.message}`)

  // #14 业务员商品销售汇总（业务员+商品），客户数为去重计数=1 且不进合计；按本轮业务员过滤
  const sg = await post('/report/center/salesman_goods_summary/page',
      rptBody({ salesman: c.salesman }, { groupBy: ['salesman', 'goods'], pageSize: 100000 }))
  expected(sg.records)
  assertNum(sg.records[0].customerCount, 1, '#14 客户数')
  assert(sg.summary.customerCount === null || sg.summary.customerCount === undefined,
      '#14 合计行不应输出去重客户数（noSum）')

  console.log('  · #10/#11/#14 销售汇总三表 OK（净55/1925/成本1100/毛利825，#14客户数1）')
}

// ========== 5. #12 客户汇总 / #13 业务员汇总（含回款/应收） ==========
async function testPartnerSummaries() {
  const c = CTX
  // #12（按本轮客户过滤，隔离库内历史轮次数据，占比分母才=1）
  const cs = await post('/report/center/customer_summary/page',
      rptBody({ customer: c.customerCode }, { pageSize: 100 }))
  assert(cs.records.length === 1, `#12 过滤后应仅 1 行客户，实际 ${cs.records.length}`)
  const row = cs.records[0]
  assert(row, `#12 应存在客户 ${c.customerCode} 的行`)
  assertNum(row.receiptCount, 1, '#12 签收单数')
  assertNum(row.signedAmount, 2100, '#12 销售金额')
  assertNum(row.returnAmount, 175, '#12 退货金额')
  assertNum(row.netAmount, 1925, '#12 净销售额')
  assertNum(row.netQtyBase, 55, '#12 净销售数量')
  assertNum(row.costAmount, 1100, '#12 成本金额')
  assertNum(row.grossProfit, 825, '#12 毛利额')
  assertNum(row.avgOrderAmount, 1925, '#12 客单价')
  assert(Math.abs(num(row.customerShare) - 1) < 0.0001, `#12 客户数占比应为 1，实际 ${row.customerShare}`)
  assertNum(row.receivedAmount, 1000, '#12 本期回款额')
  // 应收 = 签收 2100 − 退货红冲 175（退货审核自动冲减应收）− 回款 1000 = 925
  assertNum(row.arBalance, 925, '#12 期末应收余额')
  assertNum(row.overdueAmount, 0, '#12 逾期金额')
  assertNum(cs.summary.receivedAmount, 1000, '#12 合计回款')
  assertNum(cs.summary.arBalance, 925, '#12 合计应收')

  // #13（按本轮业务员过滤）
  const ss = await post('/report/center/salesman_summary/page',
      rptBody({ salesman: c.salesman }, { pageSize: 100 }))
  assert(ss.records.length === 1, `#13 过滤后应仅 1 行业务员，实际 ${ss.records.length}`)
  const srow = ss.records[0]
  assert(srow, `#13 应存在业务员 ${c.salesman} 的行`)
  assertNum(srow.receiptCount, 1, '#13 签收单数')
  assertNum(srow.customerCount, 1, '#13 客户数')
  assertNum(srow.newCustomerCount, 1, '#13 新客户数（首签在本期）')
  assertNum(srow.netAmount, 1925, '#13 净销售额')
  assertNum(srow.receivedAmount, 1000, '#13 本期回款额')
  assert(Math.abs(num(srow.receiveRate) - 1000 / 2100) < 0.0001,
      `#13 回款率应为 ${1000 / 2100}，实际 ${srow.receiveRate}`)
  assertNum(srow.arBalance, 925, '#13 应收余额（2100−退货红冲175−回款1000）')

  console.log('  · #12/#13 客户与业务员汇总 OK（回款1000、应收925、新客户1、回款率0.4762）')
}

// ========== 6. #15 销售订单明细查询 ==========
async function testSalesOrderDetail() {
  const c = CTX
  // 默认窗口路径（已审核）
  const data = await post('/report/center/sales_order_detail/page', rptBody({
    orderNo: c.orderNo,
  }))
  assert(data.records.length === 1, `#15 应 1 行订单行，实际 ${data.records.length}`)
  const r = data.records[0]
  assert(r.orderNo === c.orderNo, '#15 订单号')
  assert(r.statusText === '已审核', `#15 审核状态应为已审核，实际 ${r.statusText}`)
  assert(r.outboundStatusText === '已出库', `#15 出库状态应为已出库，实际 ${r.outboundStatusText}`)
  assert(r.signStatusText === '部分签收', `#15 签收状态应为部分签收，实际 ${r.signStatusText}`)
  assertNum(r.baseQty, 80, '#15 订单数量')
  assertNum(r.amount, 2800, '#15 订单金额')
  assertNum(r.outboundBase, 80, '#15 已出库数量')
  assertNum(r.unoutboundBase, 0, '#15 未出库数量')
  assertNum(r.signedBase, 60, '#15 已签收数量')
  assertNum(r.rejectBase, 20, '#15 已拒收数量')
  assertNum(r.signedAmount, 2100, '#15 签收金额')
  assert((r.signDate || '').slice(0, 10) === today, `#15 签收日期应为今天，实际 ${r.signDate}`)
  const sum = await rptSummary('sales_order_detail', rptBody({ orderNo: c.orderNo }))
  assertNum(sum.amount, 2800, '#15 合计订单金额')
  assertNum(sum.signedAmount, 2100, '#15 合计签收金额')
  assertNum(sum.rejectBase, 20, '#15 合计拒收数量')

  // 全量 JOIN 路径：按签收/出库状态过滤（叠加本轮客户隔离——累积库多轮订单会把本轮行挤出首页）
  for (const filters of [
    { customer: c.customerCode, signStatus: '部分签收' },
    { customer: c.customerCode, outboundStatus: '已出库' },
  ]) {
    const d2 = await post('/report/center/sales_order_detail/page', rptBody(filters, { pageSize: 100 }))
    assert(d2.records.some(x => x.orderNo === c.orderNo),
        `#15 过滤 ${JSON.stringify(filters)} 走全量路径应能命中订单 ${c.orderNo}`)
  }
  // 未签收/未出库不应命中
  const d3 = await post('/report/center/sales_order_detail/page',
      rptBody({ customer: c.customerCode, signStatus: '未签收' }, { pageSize: 100 }))
  assert(!d3.records.some(x => x.orderNo === c.orderNo), '#15 未签收过滤不应命中该订单')

  // 非法状态值 → 中文 400
  const bad = await postExpectFail('/report/center/sales_order_detail/page',
      rptBody({ signStatus: '瞎写的' }))
  assert(/签收状态/.test(bad.message), `#15 非法签收状态应中文报错，实际：${bad.message}`)

  // 待审核也能切出来看（状态映射）
  const d4 = await post('/report/center/sales_order_detail/page',
      rptBody({}, {}))
  assert(Array.isArray(d4.records), '#15 默认查询应正常返回')

  console.log('  · #15 销售订单明细查询 OK（订80/出80/签60/拒20/签收额2100，窗口+全量双路径）')
}

// ========== 7. #8 商品进销存汇总表 ==========
async function testInventoryRoll() {
  const c = CTX
  const data = await post('/report/center/inventory_roll/page', rptBody({
    goods: c.goodsCode, warehouse: c.warehouseName,
  }, { pageSize: 100000 }))
  assert(data.records.length === 1, `#8 应 1 行（商品+仓），实际 ${data.records.length}`)
  const r = data.records[0]
  assert(r.warehouse === c.warehouseName, '#8 仓库')
  assertNum(r.openingQty, 0, '#8 期初数量')
  assertNum(r.openingAmount, 0, '#8 期初金额')
  assertNum(r.purchaseInQty, 100, '#8 采购入库数量')
  assertNum(r.salesReturnInQty, 5, '#8 销售退货入库数量')
  assertNum(r.otherInQty, 20, '#8 其他入库数量（客户拒收）')
  assertNum(r.inQty, 125, '#8 收入小计数量')
  assertNum(r.salesOutQty, 80, '#8 销售出库数量')
  assertNum(r.outQty, 80, '#8 发出小计数量')
  assertNum(r.endingQty, 45, '#8 期末数量')
  assertNum(r.endingAmount, 900, '#8 期末金额')
  assertNum(r.inAmount, 2500, '#8 收入金额（2000+400+100）')
  assertNum(r.outAmount, 1600, '#8 发出金额')
  // 恒等式：期初 + 收入 − 发出 = 期末（数量/金额）
  assertNum(num(r.openingQty) + num(r.inQty) - num(r.outQty), num(r.endingQty), '#8 数量恒等式')
  assertNum(num(r.openingAmount) + num(r.inAmount) - num(r.outAmount), num(r.endingAmount), '#8 金额恒等式')
  assert(r.diffFlag === '一致', `#8 勾稽标记应为一致，实际 ${r.diffFlag}`)
  assertNum(data.summary.endingQty, 45, '#8 合计期末数量')

  console.log('  · #8 商品进销存汇总表 OK（期初0/入125/出80/期末45，金额恒等 0+2500−1600=900）')
}

// ========== 8. #9 商品库存台账 ==========
async function testStockLedger() {
  const c = CTX
  // 8.1 护栏：无商品+仓库
  const f1 = await postExpectFail('/report/center/stock_ledger/page', rptBody())
  assert(/商品或仓库/.test(f1.message), `#9 缺商品/仓库应中文报错，实际：${f1.message}`)
  // 8.2 护栏：93 天
  const f2 = await postExpectFail('/report/center/stock_ledger/page', {
    dateRange: { startDate: '2026-06-11', endDate: today },
    filters: { warehouse: c.warehouseName },
    pageNo: 1, pageSize: 100,
  })
  assert(/92/.test(f2.message), `#9 超 92 天应中文报错，实际：${f2.message}`)

  // 8.3 正常查询（今天，单商品+仓）
  const data = await post('/report/center/stock_ledger/page', rptBody({
    goods: c.goodsCode, warehouse: c.warehouseName,
  }, { pageSize: 100 }))
  const openings = data.records.filter(r => r.rowKind === 'OPENING')
  const moves = data.records.filter(r => r.rowKind === 'MOVE')
  assert(openings.length >= 1, '#9 应至少有 1 行期初虚拟行')
  assert(moves.length === 4, `#9 应有 4 笔流水（CGRK/XSCK/JSRK/THRK），实际 ${moves.length}：`
      + moves.map(m => `${m.billTypeText}:${num(m.inQty) || -num(m.outQty)}`).join(','))
  for (const o of openings) {
    assertNum(o.balanceQty, 0, '#9 期初行数量应为 0')
    assertNum(o.balanceAmount, 0, '#9 期初行金额应为 0')
  }
  const byType = Object.fromEntries(moves.map(m => [m.billTypeText, m]))
  assertNum(byType['采购入库'].inQty, 100, '#9 CGRK 收入100')
  assertNum(byType['销售出库'].outQty, 80, '#9 XSCK 发出80')
  assertNum(byType['客户拒收入库'].inQty, 20, '#9 JSRK 收入20')
  assertNum(byType['销售退货入库'].inQty, 5, '#9 THRK 收入5')
  // 每个批次分区最后一笔流水的结存之和 = 45
  const perBatch = new Map()
  for (const m of moves) perBatch.set(m.batchNo || '', m)
  let ending = 0
  for (const m of perBatch.values()) ending += num(m.balanceQty)
  assertNum(ending, 45, '#9 分区末笔结存之和（期末45）')

  // 8.4 合计行：明细报表页面不内嵌合计，走 /summary（含快照对账）
  const s9 = await rptSummary('stock_ledger', rptBody({
    goods: c.goodsCode, warehouse: c.warehouseName,
  }, { pageSize: 100 }))
  assertNum(s9.openingQty, 0, '#9 合计期初')
  assertNum(s9.inQty, 125, '#9 合计收入')
  assertNum(s9.outQty, 80, '#9 合计发出')
  assertNum(s9.endingQty, 45, '#9 合计期末')
  assertNum(s9.endingAmount, 900, '#9 合计期末金额')
  assertNum(s9.snapshotPhysical, 45, '#9 快照实物量')
  assertNum(s9.reconcileDiff, 0, '#9 快照对账差异')

  // 8.5 billTypes 白名单
  const f3 = await postExpectFail('/report/center/stock_ledger/page', rptBody({
    warehouse: c.warehouseName, billTypes: ['BOGUS'],
  }))
  assert(/不支持的单据类型/.test(f3.message), `#9 非法类型应报错，实际：${f3.message}`)

  // 8.6 单据类型钻取：只看销售出库，仅 1 笔且不包含期初行
  const onlyOut = await post('/report/center/stock_ledger/page', rptBody({
    goods: c.goodsCode, warehouse: c.warehouseName, billTypes: ['XSCK'],
  }, { pageSize: 100 }))
  const outMoves = onlyOut.records.filter(r => r.rowKind === 'MOVE')
  assert(outMoves.length === 1 && outMoves[0].billTypeText === '销售出库',
      '#9 billTypes=[XSCK] 应只回销售出库 1 笔')

  console.log('  · #9 商品库存台账 OK（护栏/期初行/4笔流水/分区结存45/快照勾稽一致）')
}

// ========== 9. 脱敏与授权（无字段码角色） ==========
async function testMasking() {
  const roleCode = 'RPR' + sfx
  const userName = 'rptv' + sfx
  const password = 'Report#2026'
  // 仅授 9 张报表的查看功能点 + 全部数据范围；不授任何 VIEW_* 字段码
  const viewFuncs = [
    'report.inventory_roll.view', 'report.stock_ledger.view',
    'report.sales_goods_summary.view', 'report.customer_goods_summary.view',
    'report.customer_summary.view', 'report.salesman_summary.view',
    'report.salesman_goods_summary.view', 'report.sales_order_detail.view',
    'report.sales_move_detail.view',
  ]
  // 数据范围语义：维度白名单只有七维（无 ALL 维度），ALL 是维度值令牌；
  // 一个维度都不配会 DEFAULT DENY（建档人列外 fail-closed），故授 CUSTOMER=ALL
  // ——已配任一维度即按已配维度过滤，其余维度放行，等价全量数据范围。
  const role = await post('/system/rbac/role/create', {
    roleCode, roleName: '报测只读角色-' + sfx,
    funcCodes: viewFuncs,
    dataScopes: [{ scopeType: 'CUSTOMER', scopeValue: 'ALL' }],
  })
  // 用户层不配收窄维度：空列表 = 全部跟随角色（用户层只减不增）
  await post('/system/rbac/user/create', {
    username: userName, displayName: '报测只读用户', password,
    roleIds: [role.roleId], mustChangePwd: false, status: 'NORMAL',
    dataScopes: [],
  })
  const t = await login(userName, password)

  // #12：数量可见，金额/成本/回款被脱敏为 null（按本轮客户过滤，避免多轮数据挤出首页）
  const cs = await post('/report/center/customer_summary/page',
      rptBody({ customer: CTX.customerCode }, { pageSize: 100 }), t)
  const row = cs.records.find(r => r.customerCode === CTX.customerCode)
  assert(row, '脱敏用户也应能看到客户行（空数据范围=全部可见）')
  assertNum(row.netQtyBase, 55, '脱敏用户数量列应可见')
  assert(row.signedAmount === null, '脱敏用户销售金额应为 null')
  assert(row.costAmount === null, '脱敏用户成本金额应为 null')
  assert(row.grossProfit === null, '脱敏用户毛利应为 null')
  assert(row.receivedAmount === null, '脱敏用户回款应为 null')
  assert(row.arBalance === null, '脱敏用户应收应为 null')

  // #16：数量可见、金额/成本为 null
  const sm = await post('/report/center/sales_move_detail/page',
      rptBody({ customer: CTX.customerCode }), t)
  for (const r of sm.records) {
    assert(r.baseQty !== null && r.baseQty !== undefined, '脱敏用户明细数量应可见')
    assert(r.amount === null, '脱敏用户明细金额应为 null')
    assert(r.costAmount === null, '脱敏用户明细成本应为 null')
  }

  // #8：数量可见、金额为 null
  const ir = await post('/report/center/inventory_roll/page', rptBody({
    goods: CTX.goodsCode, warehouse: CTX.warehouseName,
  }, { pageSize: 100000 }), t)
  assertNum(ir.records[0].endingQty, 45, '脱敏用户期末数量应可见')
  assert(ir.records[0].endingAmount === null, '脱敏用户期末金额应为 null')

  // 未授权报表码（一期采购明细）应被拒绝
  const denied = await postExpectFail('/report/center/purchase_order_detail/page', rptBody(), t)
  assert(denied.code !== '0', '未授权报表应返回非 0')

  console.log('  · 脱敏与授权 OK（无字段码：数量可见、金额/成本/毛利/回款/应收全部 null，越权报表拒绝）')
}

async function main() {
  authToken = await login('admin', 'admin123')
  console.log('报表中心二期验收开始：', BASE)
  await buildBusinessChain()
  await rebuildDws()
  await testSalesMoveDetail()
  await testSalesSummaries()
  await testPartnerSummaries()
  await testSalesOrderDetail()
  await testInventoryRoll()
  await testStockLedger()
  await testMasking()
  console.log('\n✅ 报表中心二期 #8~#16 全部验收通过')
}

main().catch(e => {
  console.error('\n❌ 验收失败：', e.message)
  console.error(e.stack)
  process.exit(1)
})
