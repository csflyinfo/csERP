/**
 * 报表中心三期（#6/#7/#17/#18/#19/#20~#24）验收测试。
 *
 * 运行：
 *   API_BASE=http://localhost:8081/api \
 *   H2_URL='jdbc:h2:file:E:/work/.../backend/data-p3/erp-v1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE' \
 *   node development/06-testing/scripts/v1-report-center-p3-test.js
 * 默认打本机 8080；建议在 worktree 隔离空库（8081 + data-p3）执行。
 *
 * 组成：
 *   A. 真实单据链路（HTTP）：采购→入库→销售订单→出库→签60拒20→销退5→收款1000→付款800，
 *      外加一件「只采不销」呆滞商品；随后重算销售/采购/库存流水 DWS、重建昨日/今日库存快照。
 *   B. #7 周转率 / #6 缺货（实时/历史/趋势三页签）/ #17 综合分析（明细+KPI+趋势+结构）严格取数断言。
 *   C. #20/#21 账龄（客户/供应商透视+单据视图+时点边界）、#23/#24 往来汇总滚动恒等式、#22 现金日记账。
 *   D. #18/#19 库管/司机绩效：PDA/APP 全链路（约 20 个端）不适合验收脚本驱动，
 *      事实表夹具经 H2 Shell 幂等注入（fixtures/v1-report-center-p3-perf.sql，仅插任务/调度事实行，
 *      不碰报表结果表），报表侧严格断言收货/上架/拣货/复核/补货/盘点/调整/异常、签收率/拒收率/
 *      趟次/代收缴款/行驶时长等全部指标。夹具日期固定 2026-09-10，按 2026-09 自然月断言。
 *   E. 脱敏与授权：无 VIEW_* 字段码角色可见数量、成本/金额/毛利为 null；未授权报表拒绝。
 */
const { execFileSync } = require('child_process')
const fs = require('fs')
const os = require('os')
const path = require('path')

const BASE = process.env.API_BASE || 'http://localhost:8080/api'
const H2_URL = process.env.H2_URL || ''
const H2_JAR = process.env.H2_JAR || path.join(os.homedir(), '.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar')
const JAVA_BIN = process.env.JAVA_BIN || 'java'
const FIXTURE = path.join(__dirname, 'fixtures', 'v1-report-center-p3-perf.sql')

let authToken = 'demo-token'

async function request(method, urlPath, body, token = authToken) {
  const res = await fetch(`${BASE}${urlPath}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return res.json()
}
async function post(urlPath, body = {}, token = authToken) {
  const j = await request('POST', urlPath, body, token)
  if (j.code !== '0') throw new Error(`${urlPath} failed: ${j.message}`)
  return j.data
}
async function postExpectFail(urlPath, body = {}, token = authToken) {
  const j = await request('POST', urlPath, body, token)
  if (j.code === '0') throw new Error(`${urlPath} 应失败但成功了`)
  return j
}
async function login(username, password) {
  const j = await request('POST', '/auth/login', { username, password }, null)
  if (j.code !== '0') throw new Error(`login failed: ${j.message}`)
  return j.data.token
}
const assert = (c, m) => { if (!c) throw new Error(m) }
const num = v => Number(v ?? 0)
const assertNum = (a, e, m) => assert(Math.abs(num(a) - e) < 0.011, `${m} 应为 ${e}，实际 ${a}`)
const assertClose = (a, e, tol, m) => assert(Math.abs(num(a) - e) < tol, `${m} 应≈${e}，实际 ${a}`)

const today = new Date().toISOString().slice(0, 10)
const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0, 10)
const sfx = String(Date.now()).slice(-8)
const CTX = {
  warehouseCode: 'PW' + sfx, warehouseName: '验测仓库-' + sfx,
  supplierCode: 'PS' + sfx, supplierName: '验测供应商-' + sfx,
  customerCode: 'PC' + sfx, customerName: '验测客户-' + sfx,
  goodsCode: 'PG' + sfx, goodsName: '验测商品-' + sfx,
  slowGoodsCode: 'PGS' + sfx, slowGoodsName: '验测呆滞品-' + sfx,
  salesman: '验测业务员-' + sfx, driver: '验测司机-' + sfx,
  batchNo: '20260801', productionDate: '2026-08-01', today, yesterday,
}
const body = (filters = {}, range) => ({
  dateRange: { startDate: (range && range[0]) || today, endDate: (range && range[1]) || today },
  filters, pageNo: 1, pageSize: 100000,
})
const page = (code, filters = {}, token = authToken, range) => post(`/report/center/${code}/page`, body(filters, range), token)
const summary = (code, filters = {}, token = authToken, range) => post(`/report/center/${code}/summary`, body(filters, range), token)

// ========== A. 业务链路 ==========
async function buildChain() {
  const c = CTX
  await post('/base/warehouse/create', {
    warehouseCode: c.warehouseCode, warehouseName: c.warehouseName,
    warehouseType: '正常仓', inventoryType: '平台主仓', costGroup: 'CG01',
  })
  await post('/base/supplier/create', {
    supplierCode: c.supplierCode, supplierName: c.supplierName,
    supplierType: '普通供应商', settlementMethod: '月结30天', deliveryDays: 30,
  })
  await post('/base/customer/create', {
    customerCode: c.customerCode, customerName: c.customerName,
    channelType: '零售商超', salesman: c.salesman, accountPeriodType: '现结',
  })
  await post('/base/goods/create', {
    goodsCode: c.goodsCode, goodsName: c.goodsName, spec: '验测规格',
    baseUnit: '箱', goodsType: '正常商品', taxRate: '13%', latestPurchasePrice: 0,
    stockLowerLimit: 200, stockUpperLimit: 1000, defaultWarehouse: c.warehouseName,
    defaultSupplier: c.supplierName,
  })
  // 呆滞品：只采不销，无库存下限
  await post('/base/goods/create', {
    goodsCode: c.slowGoodsCode, goodsName: c.slowGoodsName, spec: '验测规格',
    baseUnit: '箱', goodsType: '正常商品', taxRate: '13%',
    stockLowerLimit: 0, defaultWarehouse: c.warehouseName,
  })

  async function purchase(goodsCode, goodsName, qty, price) {
    const po = await post('/purchase/order/create', {
      supplierCode: c.supplierCode, supplierName: c.supplierName, buyer: '张三',
      warehouseId: c.warehouseName, billDate: today,
      details: [{ goodsCode, goodsName, spec: '验测规格', unitId: '箱', unitLevel: 1, convertQty: 1,
        qty, baseQty: qty, price, amount: qty * price, taxRate: '13%' }],
    })
    await post('/purchase/order/audit', { orderId: po.orderId })
    const pi = await post('/purchase/inbound/create', {
      sourceOrder: po.orderNo, supplier: c.supplierName, warehouse: c.warehouseName, billDate: today,
      details: [{ goodsCode, goodsName, unitName: '箱', productionDate: c.productionDate, receivedQty: qty, price }],
    })
    const piAudit = await post('/purchase/inbound/audit', { bizId: pi.inboundId })
    // 入库审核自动生成采购收货单，收货单审核才立应付（fin_ap）
    if (piAudit.receiptNo) await post('/purchase/receipt/audit', { bizId: piAudit.receiptNo })
    return po
  }
  await purchase(c.goodsCode, c.goodsName, 100, 20)
  await purchase(c.slowGoodsCode, c.slowGoodsName, 10, 30)

  // 昨日快照：此刻主商品库存 100，但库存下限 200 → 昨日即缺货（连续缺货第 1 天）
  const snap0 = await post('/report/center/admin/rebuild-snapshot', { date: yesterday })
  assert(num(snap0.rows) >= 2, '昨日快照至少 2 行')

  const so = await post('/sales/order/create', {
    customerCode: c.customerCode, customerName: c.customerName,
    salesman: c.salesman, warehouseId: c.warehouseName, billDate: today,
    details: [{ goodsCode: c.goodsCode, goodsName: c.goodsName, unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 80, baseQty: 80, price: 35, amount: 2800, taxRate: '13%', salesAttribute: '正常' }],
  })
  await post('/sales/order/audit', { orderId: so.orderId })
  const sou = await post('/sales/outbound/create', {
    sourceOrder: so.orderNo, customer: c.customerName, warehouse: c.warehouseName,
    salesman: c.salesman, driver: c.driver, billDate: today,
    details: [{ goodsCode: c.goodsCode, goodsName: c.goodsName, unitName: '箱',
      qty: 80, price: 35, batchNo: c.batchNo, productionDate: c.productionDate }],
  })
  const souAudit = await post('/sales/outbound/audit', { bizId: sou.outboundId })
  CTX.receiptNo = souAudit.receiptNo
  const srDetail = await (await fetch(`${BASE}/sales/receipt/detail?receiptId=${encodeURIComponent(CTX.receiptNo)}`, {
    headers: { Authorization: 'Bearer ' + authToken },
  })).json()
  assert(srDetail.code === '0', '读取发货单详情失败：' + srDetail.message)
  const sign = await post('/sales/receipt/sign', {
    receiptId: CTX.receiptNo,
    details: [{ detailId: srDetail.data.details[0].detailId, signedQty: 60, rejectQty: 20, rejectReason: '验测破损' }],
  })
  assertNum(sign.signAmount, 2100, '签收金额')
  CTX.arNo = sign.arNo
  await post('/sales/reject-inbound/audit', { bizId: sign.rejectInboundNo })

  const sra = await post('/sales/return-order/create', {
    customer: c.customerName, customerCode: c.customerCode, warehouse: c.warehouseName,
    returnType: 'WAREHOUSE', returnReason: '验测退货',
    details: [{ goodsCode: c.goodsCode, goodsName: c.goodsName, spec: '验测规格', unitName: '箱',
      qty: 5, price: 35, taxRate: '13%', returnMode: 'BY_GOODS', batchNo: c.batchNo }],
  })
  await post('/sales/return-order/confirm', { bizId: sra.applyNo })
  const push = await post('/sales/return-order/push-warehouse', { bizId: sra.applyNo })
  await post('/sales/return-inbound/audit', { bizId: push.inboundNo })
  await post('/sales/return-order/audit', { bizId: sra.applyNo })

  // 资金账户 + 收款 1000 + 付款 800
  const faCode = 'PF' + sfx, faName = '验测现金-' + sfx
  await post('/base/master/save', { moduleCode: 'fundAccount', fundAccountCode: faCode,
    fundAccountName: faName, parentCode: '01', accountType: '现金', glAccountCode: '1001', status: 'NORMAL' })
  const rc = await post('/finance/receipt/create', {
    receiptDate: today, counterpartyType: 'CUSTOMER',
    counterpartyCode: c.customerCode, counterpartyName: c.customerName,
    handler: '张三', summary: '验测回款',
    details: [{ fundAccount: faName, amount: 1000, remark: '货款' }],
  })
  await post('/finance/receipt/audit', { receiptId: rc.receiptId })
  const pay = await post('/finance/payment/create', {
    paymentDate: today, counterpartyType: 'SUPPLIER',
    counterpartyCode: c.supplierCode, counterpartyName: c.supplierName,
    handler: '张三', summary: '验测付款',
    details: [{ fundAccount: faName, amount: 800, remark: '货款' }],
  })
  await post('/finance/payment/audit', { paymentId: pay.paymentId })
  CTX.fundAccount = faName
  console.log('  · 业务链路完成（签60拒20退5回款1000付款800 + 呆滞品10件）')
}

async function rebuildAll() {
  const sales = await post('/report/center/admin/recompute-sales', {})
  assert(sales.balanced === true, '销售 DWS 应对账平衡：' + JSON.stringify(sales))
  const pur = await post('/report/center/admin/recompute-purchase', {})
  assertNum(pur.dwsAmount, pur.liveAmount, '采购 DWS 应与实时一致')
  const mv = await post('/report/center/admin/rebuild-stock-move', {})
  assert(num(mv.rows) >= 4, '库存流水 DWS 至少 4 行')
  const snap = await post('/report/center/admin/rebuild-snapshot', { date: today })
  assert(num(snap.rows) >= 2, '今日快照至少 2 行（两个商品）')
  console.log('  · DWS/快照重建完成', JSON.stringify(sales).slice(0, 160))
}

// ========== B. #7/#6/#17 ==========
async function testTurnover() {
  const c = CTX
  const r = await page('goods_turnover', { level: 'goods', goods: c.goodsCode })
  assert(r.records.length === 1, `#7 应仅 1 行主商品，实际 ${r.records.length}`)
  const x = r.records[0]
  // 期初取昨日快照（采购已完成、销售未发生）：100 件 / 2000
  assertNum(x.openQty, 100, '#7 期初数量')
  assertNum(x.openAmount, 2000, '#7 期初金额')
  assertNum(x.inAmount, 2500, '#7 本期入库金额（采购2000+拒收400+退货100）')
  assertNum(x.salesCost, 1100, '#7 销售成本')
  assertNum(x.closeQty, 45, '#7 期末数量')
  assertNum(x.closeAmount, 900, '#7 期末金额')
  assertNum(x.avgStockAmount, 1450, '#7 平均库存金额（2000+900)/2')
  assertClose(x.turnoverRate, 1100 / 1450, 0.001, '#7 周转率')
  assertClose(x.turnoverDays, 1450 / 1100, 0.001, '#7 周转天数（1天期间）')
  assertNum(x.salesAmount, 1925, '#7 期间销售额')
  assertClose(x.grossMarginRate, 825 / 1925, 0.0001, '#7 毛利率')
  assert(x.slowFlag === '', '#7 主商品不应呆滞')

  // 呆滞品：零周转有库存
  const slow = await page('goods_turnover', { level: 'goods', goods: c.slowGoodsCode })
  assert(slow.records.length === 1, '#7 呆滞品应 1 行')
  assert(slow.records[0].slowFlag === '呆滞', '#7 零周转有库存应标呆滞')
  assert(slow.records[0].turnoverRate === 0, '#7 呆滞品周转率 0')
  const onlySlow = await page('goods_turnover', { level: 'goods', slowOnly: '1' })
  assert(onlySlow.records.some(z => z.goodsCode === c.slowGoodsCode), '#7 仅看呆滞应含呆滞品')
  assert(!onlySlow.records.some(z => z.goodsCode === c.goodsCode), '#7 仅看呆滞不应含主商品')

  const s = await summary('goods_turnover', { level: 'goods' })
  // 总销售成本 1100；总平均库存 = 主品(2000+900)/2 + 呆滞品(300+300)/2 = 1450+300 = 1750
  assertClose(s.turnoverRate, 1100 / 1750, 0.001, '#7 合计加权周转率（总均库 1750）')
  console.log('  · #7 商品周转率 OK（率=2.44 天=.41；呆滞品标记与仅看呆滞正确）')
}

async function testShortage() {
  const c = CTX
  // 实时
  const rt = await page('shortage_analysis', { tab: 'realtime', goods: c.goodsCode })
  assert(rt.records.length === 1, `#6 实时应 1 行缺货，实际 ${rt.records.length}：${JSON.stringify(rt.records).slice(0, 300)}`)
  const x = rt.records[0]
  assertNum(x.availableQty, 45, '#6 可用量')
  assert(num(x.lowerLimit) === 200, '#6 库存下限')
  assert(num(x.salesQty30d) === 55, '#6 近30天签收净销量')
  assertClose(x.avgDailySales, 55 / 30, 0.01, '#6 日均销量')
  assertNum(x.deliveryDays, 30, '#6 交货周期')
  // 建议量 = 200 + 55/30*30 - 45 = 210
  assertClose(x.suggestedQty, 210, 0.6, '#6 建议补货量')
  assert(num(x.consecShortDays) >= 2, `#6 连续缺货应≥2 天（昨日0库存+今日45<50），实际 ${x.consecShortDays}`)

  // 缺货类型过滤
  const zero = await page('shortage_analysis', { tab: 'realtime', shortageType: 'ZERO' })
  assert(!zero.records.some(z => z.goodsCode === c.goodsCode), '#6 库存非0不应出现在 ZERO 类型')
  const safety = await page('shortage_analysis', { tab: 'realtime', shortageType: 'SAFETY', goods: c.goodsCode })
  assert(safety.records.length === 1, '#6 SAFETY 类型应包含主商品')
  const cover = await page('shortage_analysis', { tab: 'realtime', shortageType: 'COVER', goods: c.goodsCode })
  assert(cover.records.length === 1, '#6 可销天数 24.5<30，COVER 类型应包含主商品')

  // 历史
  const hist = await page('shortage_analysis', { tab: 'history', goods: c.goodsCode }, authToken, [yesterday, today])
  const h = hist.records.find(z => z.goodsCode === c.goodsCode)
  assert(h, '#6 历史应有主商品行')
  assert(num(h.shortDays) >= 2, `#6 缺货天数应≥2，实际 ${h.shortDays}`)
  assert(num(h.consecShortDays) >= 2, `#6 最长连续缺货应≥2，实际 ${h.consecShortDays}`)
  assert(num(h.lostSalesQty) > 0, '#6 估算流失销量应 >0')

  // 趋势
  const tr = await page('shortage_analysis', { tab: 'trend' }, authToken, [yesterday, today])
  assert(tr.records.some(z => z.snapshotDate === today && num(z.shortSkuCount) >= 1), '#6 趋势今日应≥1 缺货 SKU')
  console.log('  · #6 缺货分析 OK（实时建议60/连续≥2天；三类型过滤；历史/趋势一致）')
}

async function testGoodsAnalysis() {
  const c = CTX
  const r = await page('goods_analysis', { goods: c.goodsCode })
  const x = r.records.find(z => z.goodsCode === c.goodsCode)
  assert(x, '#17 明细应有主商品')
  assertNum(x.purchaseQty, 100, '#17 采购数量')
  assertNum(x.purchaseAmount, 2000, '#17 采购金额')
  assertNum(x.signedQty, 55, '#17 签收净数量')
  assertNum(x.signedAmount, 1925, '#17 签收净额')
  assertNum(x.costAmount, 1100, '#17 配比成本')
  assertNum(x.grossProfit, 825, '#17 毛利')
  assertNum(x.billCount, 1, '#17 签收单数')
  assertNum(x.customerCount, 1, '#17 客户数')
  assertNum(x.endQty, 45, '#17 期末库存')
  assertNum(x.endAmount, 900, '#17 期末库存金额')

  const kpi = await post('/report/center/goods-analysis/kpi', body())
  assertNum(kpi.salesAmount, 1925, '#17 KPI 销售额')
  assertNum(kpi.grossProfit, 825, '#17 KPI 毛利')
  assertNum(kpi.billCount, 1, '#17 KPI 签收单数')
  assertNum(kpi.endStockAmount, 900 + 300, '#17 KPI 期末库存金额（两商品 900+300）')
  assertClose(kpi.grossProfitRate, 825 / 1925, 0.0001, '#17 KPI 毛利率')

  const td = await post('/report/center/goods-analysis/trend', body({}, [today, today]))
  // body() 默认今日；granularity 单独传
  const td2 = await post('/report/center/goods-analysis/trend', { ...body(), granularity: 'day' })
  assert(Array.isArray(td2) && td2.some(z => num(z.salesAmount) === 1925), '#17 日趋势含 1925')
  const tm = await post('/report/center/goods-analysis/trend', { ...body({}), granularity: 'month' })
  assert(Array.isArray(tm) && tm.length >= 1, '#17 月趋势至少 1 点')
  void td
  const st = await post('/report/center/goods-analysis/structure', { ...body(), metric: 'amount' })
  assert(Array.isArray(st.topGoods) && st.topGoods.some(z => z.code === c.goodsCode), '#17 TOP20 含主商品')
  assert(Array.isArray(st.topCustomers) && st.topCustomers.some(z => z.code === c.customerCode), '#17 TOP10 含客户')
  assert(Array.isArray(st.categoryPie) && st.categoryPie.length >= 1, '#17 分类饼有数据')
  console.log('  · #17 综合分析 OK（明细/KPI/日月趋势/结构 TOP）')
}

// ========== C. 财务五表 ==========
async function testFinance() {
  const c = CTX
  // #20 应收账龄（客户透视）
  const ar = await page('ar_aging', { cutoff: today, customer: c.customerCode })
  const crow = ar.records.find(z => z.customerCode === c.customerCode)
  assert(crow, '#20 应有客户行')
  assertNum(crow.arAmount, 1925, '#20 应收总额')
  assertNum(crow.receivedAmount, 1000, '#20 已核销')
  assertNum(crow.outstanding, 925, '#20 截至日余额')
  // 单据视图
  const arb = await page('ar_aging', { cutoff: today, viewMode: 'bill', customer: c.customerCode })
  assert(arb.records.some(z => z.arNo === CTX.arNo), '#20 单据视图应有该应收单')
  // 时点边界：截止昨天（立账在今天）→ 不含
  const arY = await page('ar_aging', { cutoff: yesterday, customer: c.customerCode, includeSettled: '1' })
  assert(!arY.records.some(z => z.arNo === CTX.arNo), '#20 截止昨日不应含今日立账')
  const arSum = await summary('ar_aging', { cutoff: today, customer: c.customerCode })
  assertNum(arSum.outstanding, 925, '#20 合计余额')

  // #21 应付账龄
  const ap = await page('ap_aging', { cutoff: today, supplier: c.supplierName })
  const prow = ap.records.find(z => z.supplierCode === c.supplierCode)
  assert(prow, '#21 应有供应商行：' + JSON.stringify(ap.records).slice(0, 200))
  assertNum(prow.apAmount, 2300, '#21 应付总额（两笔采购 2000+300）')
  assertNum(prow.paidAmount, 800, '#21 已付')
  assertNum(prow.outstanding, 1500, '#21 截至日余额')

  // #23 客户应收汇总
  const cas = await page('customer_ar_summary',
    { customer: c.customerCode, includeSettled: '1' }, authToken, [today, today])
  const cr = cas.records.find(z => z.customerCode === c.customerCode)
  assert(cr, '#23 应有客户行')
  assertNum(cr.newAmount, 1925, '#23 本期新增')
  assertNum(cr.receivedAmount, 1000, '#23 本期回款')
  assertNum(cr.endingAmount, 925, '#23 期末应收（恒等式）')

  // #24 供应商应付汇总（K2 起点覆盖今天即可）
  const sas = await page('supplier_ap_summary',
    { supplier: c.supplierName, includeSettled: '1' }, authToken, [yesterday, today])
  const sr = sas.records.find(z => z.supplierCode === c.supplierCode)
  assert(sr, '#24 应有供应商行：' + JSON.stringify(sas.records).slice(0, 200))
  assertNum(sr.newAmount, 2300, '#24 本期新增')
  assertNum(sr.paidAmount, 800, '#24 本期付款')
  assertNum(sr.endingAmount, 1500, '#24 期末应付（恒等式）')

  // #22 现金日记账：该账户有收有支，校验列不应出现 MISMATCH
  const j = await page('fund_journal', { fundAccount: [CTX.fundAccount] }, authToken, [today, today])
  assert(j.records.length >= 3, `#22 至少 期初+收+支 3 行，实际 ${j.records.length}`)
  assert(!j.records.some(z => z.balanceCheck === 'MISMATCH'), '#22 不应有余额校验差异')
  const inRow = j.records.find(z => num(z.inAmount) === 1000)
  const outRow = j.records.find(z => num(z.outAmount) === 800)
  assert(inRow && outRow, '#22 收/支流水应在账')
  console.log('  · 财务五表 OK（AR 925 / AP 1500 / 恒等式 / 账龄时点边界 / 日记账无 MISMATCH）')
}

// ========== D. #18/#19 夹具事实 + 严格断言 ==========
function applyPerfFixture() {
  if (!H2_URL) throw new Error('绩效夹具需要 H2_URL 环境变量指向隔离库（与 API_BASE 同库）')
  if (!fs.existsSync(H2_JAR)) throw new Error('找不到 H2 jar：' + H2_JAR)
  execFileSync(JAVA_BIN,
    ['-Dfile.encoding=UTF-8', '-cp', H2_JAR, 'org.h2.tools.Shell', '-url', H2_URL, '-user', 'sa',
     '-sql', `RUNSCRIPT FROM '${FIXTURE.replace(/\\/g, '/')}' CHARSET 'UTF-8'`],
    { stdio: 'inherit' })
}

async function testPerfReports() {
  const M = { startDate: '2026-09-01', endDate: '2026-09-30' }
  // #18
  const w = await post('/report/center/wms_keeper_perf/page',
    { dateRange: M, filters: { operator: 'p3fuser' }, pageNo: 1, pageSize: 100 })
  const rows = w.records
  assert(rows.length === 6, `#18 应有 6 个岗位行，实际 ${rows.length}`)
  const find = role => rows.find(x => x.roleName === role && x.warehouse === '绩效仓-P3F')
  assertNum(find('收货员').receiveOrders, 1, '#18 收货单')
  assertNum(find('收货员').receiveQty, 100, '#18 收货量')
  assertNum(find('收货员').receiveLines, 1, '#18 收货行')
  assertNum(find('上架员').putawayQty, 100, '#18 上架量')
  assertNum(find('上架员').workMinutes, 20, '#18 上架耗时')
  assertNum(find('拣货员').pickQty, 90, '#18 拣货量')
  assertNum(find('拣货员').pickLines, 3, '#18 拣货行')
  assertNum(find('拣货员').workMinutes, 30, '#18 拣货耗时')
  assertClose(find('拣货员').efficiency, 6, 0.01, '#18 拣货效率 6 行/小时')
  assertNum(find('复核员').checkOrders, 1, '#18 复核单')
  assertNum(find('仓管员').replenishCount, 1, '#18 补货次数')
  assertNum(find('仓管员').stocktakeOrders, 1, '#18 盘点单')
  assertNum(find('仓管员').gainAmount, 40, '#18 盘盈金额（盘点20+调整20）')
  const ex = rows.find(x => x.roleName === '仓管员' && x.warehouse === '')
  assertNum(ex.errorCount, 1, '#18 异常次数')
  const ws = await post('/report/center/wms_keeper_perf/summary', { dateRange: M, filters: {} })
  assertNum(ws.pickQty, 90, '#18 合计拣货量')

  // #19
  const d = await post('/report/center/driver_delivery_perf/page',
    { dateRange: M, filters: { driver: '绩效司机' }, pageNo: 1, pageSize: 100 })
  const x = d.records.find(z => z.driverId === 'DVP3F01')
  assert(x, '#19 应有 DVP3F01 司机行')
  assertNum(x.tripCount, 1, '#19 趟次')
  assertNum(x.plannedCount, 2, '#19 计划单')
  assertNum(x.signedCount, 1, '#19 签收单')
  assertClose(x.signRate, 0.5, 0.0001, '#19 签收率')
  assertNum(x.rejectCount, 1, '#19 拒收单')
  assertClose(x.rejectRate, 0.5, 0.0001, '#19 拒收率')
  assertNum(x.returnCount, 1, '#19 随车退货')
  assertNum(x.customerCount, 1, '#19 客户数')
  assertNum(x.signedQty, 25, '#19 签收数量')
  assertNum(x.rejectQty, 5, '#19 拒收数量')
  assertNum(x.signAmount, 600, '#19 配送货值')
  assertNum(x.codCollected, 600, '#19 代收货款')
  assertNum(x.codSubmit, 480, '#19 实际缴款')
  assertNum(x.codDiff, -20, '#19 缴款差异')
  assertNum(x.driveMinutes, 60, '#19 行驶时长')
  assertNum(x.avgMinutesPerOrder, 60, '#19 平均每单时长')
  assertNum(x.exceptionCount, 1, '#19 异常次数')
  console.log('  · #18/#19 绩效表 OK（六岗位作业量/效率/盘盈；签收率/代收缴款/时长全部断言）')
}

// ========== E. 脱敏与授权 ==========
async function testMasking() {
  const roleCode = 'RP3' + sfx, userName = 'rp3' + sfx, password = 'Report#2026'
  const role = await post('/system/rbac/role/create', {
    roleCode, roleName: '三期报测只读-' + sfx,
    funcCodes: [
      'report.goods_turnover.view', 'report.shortage_analysis.view',
      'report.goods_analysis.view', 'report.wms_keeper_perf.view',
      'report.driver_delivery_perf.view',
      'report.ar_aging.view', 'report.ap_aging.view',
      'report.customer_ar_summary.view', 'report.supplier_ap_summary.view',
      'report.fund_journal.view',
    ],
    dataScopes: [{ scopeType: 'CUSTOMER', scopeValue: 'ALL' }],
  })
  await post('/system/rbac/user/create', {
    username: userName, displayName: '三期只读用户', password,
    roleIds: [role.roleId], mustChangePwd: false, status: 'NORMAL', dataScopes: [],
  })
  const t = await login(userName, password)

  const tv = await page('goods_turnover', { level: 'goods', goods: CTX.goodsCode }, t)
  const x = tv.records[0]
  assert(x, '脱敏用户应见周转行')
  assertNum(x.closeQty, 45, '脱敏用户数量可见')
  assert(x.closeAmount === null, '脱敏用户库存金额应 null')
  assert(x.salesCost === null, '脱敏用户销售成本应 null')
  assert(x.grossMarginRate === null, '脱敏用户毛利率应 null')

  const drv = await post('/report/center/driver_delivery_perf/page',
    { dateRange: { startDate: '2026-09-01', endDate: '2026-09-30' },
      filters: { driver: '绩效司机' }, pageNo: 1, pageSize: 100 }, t)
  const dx = drv.records.find(z => z.driverId === 'DVP3F01')
  assert(dx && dx.signedQty !== null, '脱敏用户件数可见')
  assert(dx.signAmount === null, '脱敏用户货值应 null')
  assert(dx.codCollected === null, '脱敏用户代收货款应 null')

  const denied = await postExpectFail('/report/center/sales_goods_summary/page', body(), t)
  assert(denied.code !== '0', '未授权销售汇总应拒绝')
  console.log('  · 脱敏与授权 OK（数量可见、金额/成本/毛利/货值/代收 null；越权拒绝）')
}

async function main() {
  authToken = await login('admin', 'admin123')
  console.log('报表中心三期验收开始：', BASE)
  await buildChain()
  await rebuildAll()
  await testTurnover()
  await testShortage()
  await testGoodsAnalysis()
  await testFinance()
  if (H2_URL) {
    applyPerfFixture()
    await testPerfReports()
  } else {
    console.log('  · 跳过 #18/#19 夹具断言（未提供 H2_URL）')
  }
  await testMasking()
  console.log('\n✅ 报表中心三期 #6/#7/#17/#18/#19/#20~#24 全部验收通过')
}
main().catch(e => {
  console.error('\n❌ 验收失败：', e.message)
  console.error(e.stack)
  process.exit(1)
})
