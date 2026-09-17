/**
 * PRD-36 M2 端到端 API 验收：付款三类型（预付付款/退款）+ FX 预付核销单
 * + 供应商对账单结算使用预付（混合现金+预付、0 额锚点付款单、级联反审核）+ GL 三事件。
 *
 * 隔离环境：8082 + verify-prd36-m1 库（夹具 prd36-m1/m2-fixtures.sql 已灌入）。
 * 运行：API_BASE=http://127.0.0.1:8082/api node development/06-testing/scripts/prd36-m2-api-e2e.mjs
 *
 * 覆盖（对应设计方案 AC-03~AC-07）：
 *  A. 修复建形成流水；预付付款 600 审核 → 预付余额/资金 OUT/PREPAY_PAYMENT 流水
 *  A2. 守卫：预付退款超额拒、预付类必须供应商、正常退款 100（余额 500）
 *  B. 手工 FX：四类行校验拦截；审核 30（fin_ap 回写/双边流水/核销真值/快照）；反审核恢复；删除
 *  C. 对账单结算：两张对账单 + 抹零2 + 预付78 + 现金100；自动 FX(STATEMENT_SETTLE)；
 *     自动 FX 禁止单拆反审核；锚点付款单反审核级联冲回（对账单已付/AP/预付/真值全恢复）
 *  D. 全额预付 0 现金：也生 0 额 FK 锚点，不写资金；反审核级联恢复
 *  E. 总账：启用后 PREPAY_PAYMENT/PREPAY_REFUND/PREPAY_WRITE_OFF 事件池，FX 生成凭证 借2202/贷1123（供应商辅助）
 *  F. FX/付款分页过滤 + 无令牌 401
 */
process.env.API_BASE = process.env.API_BASE || 'http://127.0.0.1:8082/api'
const cm = (await import('./init-common.js')).default

const { post, login, expectFail, assert, num, todayStr } = cm
const BASE = cm.BASE

const S1 = 'S001'
const N1 = '甲厂家'
const S2 = 'S003'
const N2 = '丙厂家'
const TODAY = todayStr()

let passed = 0
function ok(cond, msg) {
  assert(cond, msg)
  passed++
  console.log('  ✅ ' + msg)
}
function section(title) {
  console.log('\n=== ' + title + ' ===')
}

// ---------------- 通用取数 ----------------
async function apMap() {
  const p = await post('/finance/ap/page', {
    pageNo: 1, pageSize: 200, filters: { supplier: N1 },
  })
  const m = {}
  for (const r of (p.records || [])) m[r.apNo] = r
  return m
}
async function prepayBalance(code) {
  const d = await post('/finance/supplier-account/prepay-balance', { supplierCode: code })
  return num(d.prepayBalance)
}
async function flowRows(code, accountType) {
  const p = await post('/finance/supplier-account/flow/page', {
    supplierCode: code, accountType, pageNo: 1, pageSize: 200,
  })
  return { records: p.records || [], currentBalance: num(p.currentBalance) }
}
async function reconRows() {
  const p = await post('/finance/reconcile-record/page', {
    pageNo: 1, pageSize: 200, filters: { counterparty: N1 },
  })
  return p.records || []
}
async function fxPage(q = {}) {
  return post('/finance/prepay-writeoff/page', { pageNo: 1, pageSize: 200, ...q })
}
async function fundRows(sourceBillNo) {
  const p = await post('/finance/fund-ledger/page', {
    pageNo: 1, pageSize: 200, filters: { dateFrom: TODAY, dateTo: TODAY },
  })
  return (p.records || []).filter(r => r.sourceBill === sourceBillNo)
}
async function paymentRow(paymentNo) {
  const p = await post('/finance/payment/page', {
    pageNo: 1, pageSize: 200,
    // payment/page 无单号过滤项：按往来单位+当日拉回后本地精确匹配
    filters: { counterparty: N1, dateFrom: TODAY, dateTo: TODAY },
  })
  return (p.records || []).find(r => r.paymentNo === paymentNo) || null
}

async function main() {
  await login()
  console.log('登录成功：' + BASE)

  // 基础档案
  const funds = await post('/base/master/fund-account/page', { pageNo: 1, pageSize: 50 })
  const fund = (funds.records || [])[0]
  assert(fund, '资金账户至少存在 1 个')
  const fundName = fund.fundAccountName
  const emps = await post('/base/master/employee/page', { pageNo: 1, pageSize: 50 })
  const handler = (emps.records && emps.records[0] && emps.records[0].employeeName) || '系统管理员'
  console.log(`档案：资金账户「${fundName}」经手人「${handler}」`)

  // 夹具就位检查
  const m0 = await apMap()
  if (!m0['AP-M2-10'] || !m0['AP-M2-CS1'] || !m0['AP-M2-40']) {
    throw new Error('缺少 AP-M2-* 夹具，请先执行 development/06-testing/scripts/prd36-m2-fixtures.sql')
  }
  console.log('夹具就位：AP-M2-* 共 ' + Object.keys(m0).filter(k => k.startsWith('AP-M2-')).length + ' 行')

  // 直插真值需修复补账户/形成流水
  await post('/finance/supplier-account/repair', { supplierCode: S1 })
  await post('/finance/supplier-account/repair', { supplierCode: S2 })
  const apFlow0 = await flowRows(S1, 'AP')
  // M1 真值 1100（700 收货 -100 退货 +500 期初）+ M2 真值 830 = 1930
  ok(Math.abs(apFlow0.currentBalance - 1930) < 0.001,
    `修复后 S001 应付余额=1930（实际 ${apFlow0.currentBalance}）`)
  ok((await prepayBalance(S1)) === 0, '修复后 S001 预付余额=0')

  // ---------------- A. 预付付款 600 ----------------
  section('A. 预付付款单审核 → 预付余额/资金/流水')
  const pp = await post('/finance/payment/create', {
    paymentDate: TODAY, paymentType: 'PREPAY',
    counterpartyType: 'SUPPLIER', counterpartyCode: S1, counterpartyName: N1,
    handler, summary: 'M2验证预付',
    details: [{ fundAccount: fundName, amount: 600, remark: '' }],
  })
  await post('/finance/payment/audit', { paymentId: pp.paymentId })
  ok((await prepayBalance(S1)) === 600, 'PREPAY 付款单审核后预付余额=600')
  const prepayFlow0 = await flowRows(S1, 'PREPAY')
  ok(prepayFlow0.records.some(r => r.bizType === 'PREPAY_PAYMENT' && num(r.increaseAmount) === 600
    && r.paymentNo === pp.paymentNo), '预付账户存在 PREPAY_PAYMENT +600 流水')
  const fA = await fundRows(pp.paymentNo)
  ok(fA.length === 1 && fA[0].direction === 'OUT' && num(fA[0].amount) === 600,
    `资金流水 OUT 600（${pp.paymentNo}）`)
  const ppRow = await paymentRow(pp.paymentNo)
  ok(ppRow.status === 'APPROVED' && ppRow.paymentType === 'PREPAY'
    && ppRow.paymentTypeText === '预付付款', '付款分页：类型码 PREPAY / 文案「预付付款」')

  // ---------------- A2. 守卫 + 正常退款 ----------------
  section('A2. 预付退款守卫与正常退款')
  await expectFail('/finance/payment/create', {
    paymentDate: TODAY, paymentType: 'PREPAY', counterpartyType: 'CUSTOMER',
    counterpartyCode: 'X', counterpartyName: '某客户', handler,
    details: [{ fundAccount: fundName, amount: 10 }],
  }, '必须是供应商')
  passed++; console.log('  ✅ 预付付款往来单位非供应商 → 拦截')

  const overRefund = await post('/finance/payment/create', {
    paymentDate: TODAY, paymentType: 'PREPAY_REFUND',
    counterpartyType: 'SUPPLIER', counterpartyCode: S1, counterpartyName: N1,
    handler, summary: 'M2超额退款',
    details: [{ fundAccount: fundName, amount: 700, remark: '' }],
  })
  await expectFail('/finance/payment/audit', { paymentId: overRefund.paymentId }, '预付余额不足')
  passed++; console.log('  ✅ 预付退款 700 > 余额 600 → 审核拦截整单回滚')
  ok((await prepayBalance(S1)) === 600, '拦截后预付余额仍为 600')
  // 待审核超额退款单删掉，避免污染后续过滤断言
  await post('/finance/payment/delete', { paymentId: overRefund.paymentId })

  const pr = await post('/finance/payment/create', {
    paymentDate: TODAY, paymentType: 'PREPAY_REFUND',
    counterpartyType: 'SUPPLIER', counterpartyCode: S1, counterpartyName: N1,
    handler, summary: 'M2正常退款',
    details: [{ fundAccount: fundName, amount: 100, remark: '' }],
  })
  await post('/finance/payment/audit', { paymentId: pr.paymentId })
  ok((await prepayBalance(S1)) === 500, '预付退款 100 审核后余额 600→500')
  const prepayFlowR = await flowRows(S1, 'PREPAY')
  ok(prepayFlowR.records.some(r => r.bizType === 'PREPAY_REFUND' && num(r.decreaseAmount) === 100
    && r.paymentNo === pr.paymentNo), '预付账户存在 PREPAY_REFUND -100 流水')
  const fR = await fundRows(pr.paymentNo)
  ok(fR.length === 1 && fR[0].direction === 'IN' && num(fR[0].amount) === 100,
    `退款资金流水 IN 100（${pr.paymentNo}）`)

  // ---------------- B. 手工 FX ----------------
  section('B. 手工预付核销单：校验/审核/反审核/删除')
  const mkFx = (details, extra = {}) => post('/finance/prepay-writeoff/create', {
    supplierCode: S1, supplierName: N1, handler, writeoffDate: TODAY,
    remark: 'M2验证', details, ...extra,
  })

  await expectFail('/finance/prepay-writeoff/create', {
    supplierCode: S1, supplierName: N1, handler, writeoffDate: TODAY,
    details: [{ apNo: 'AP-M2-10', writeoffAmount: 120 }],
  }, '超出未结金额')
  passed++; console.log('  ✅ 核销额超过未付 → 拦截')
  await expectFail('/finance/prepay-writeoff/create', {
    supplierCode: S2, supplierName: N2, handler, writeoffDate: TODAY,
    details: [{ apNo: 'AP-M2-10', writeoffAmount: 10 }],
  }, '不属于')
  passed++; console.log('  ✅ 核销售付应付不属于该供应商 → 拦截')
  await expectFail('/finance/prepay-writeoff/create', {
    supplierCode: S1, supplierName: N1, handler, writeoffDate: TODAY,
    details: [
      { apNo: 'AP-M2-10', writeoffAmount: 10 },
      { apNo: 'AP-M2-10', writeoffAmount: 5 },
    ],
  }, '重复')
  passed++; console.log('  ✅ 同应付单重复行 → 拦截')
  await expectFail('/finance/prepay-writeoff/create', {
    supplierCode: S1, supplierName: N1, handler, writeoffDate: TODAY,
    details: [
      { apNo: 'AP-M2-10', writeoffAmount: 100 },
      { apNo: 'AP-M2-20', writeoffAmount: 200 },
      { apNo: 'AP-M2-30', writeoffAmount: 300 },
    ],
  }, '预付余额不足')
  passed++; console.log('  ✅ 核销合计 600 > 预付 500 → 拦截')

  const fx = await mkFx([{ apNo: 'AP-M2-10', writeoffAmount: 30 }])
  ok(/^FX/.test(fx.writeoffNo), 'FX 单号前缀正确：' + fx.writeoffNo)
  const pendingPage = await fxPage({ status: 'PENDING' })
  ok(pendingPage.records.some(r => r.writeoffNo === fx.writeoffNo && r.statusText === '待审核'),
    '分页可见待审核 FX 单')
  await post('/finance/prepay-writeoff/audit', { writeoffId: fx.writeoffId })
  ok((await prepayBalance(S1)) === 470, '审核后预付余额 500→470')
  let m = await apMap()
  ok(num(m['AP-M2-10'].paidAmount) === 30 && num(m['AP-M2-10'].unpaidAmount) === 70,
    'AP-M2-10 已付30/未付70')
  const apFlowA = await flowRows(S1, 'AP')
  ok(Math.abs(apFlowA.currentBalance - 1900) < 0.001, `应付余额 1930→1900（实际 ${apFlowA.currentBalance}）`)
  ok(apFlowA.records.some(r => r.bizType === 'AP_SETTLE_PREPAY' && num(r.decreaseAmount) === 30
    && r.writeoffNo === fx.writeoffNo && r.apNo === 'AP-M2-10'), '应付账户 AP_SETTLE_PREPAY -30 流水')
  const prepayFlowA = await flowRows(S1, 'PREPAY')
  ok(prepayFlowA.records.some(r => r.bizType === 'PREPAY_WRITE_OFF' && num(r.decreaseAmount) === 30
    && r.writeoffNo === fx.writeoffNo), '预付账户 PREPAY_WRITE_OFF -30 流水')
  let recs = await reconRows()
  ok(recs.some(r => r.receiptNo === fx.writeoffNo && r.businessType === 'PREPAY_WRITE_OFF'
    && r.businessNo === 'AP-M2-10' && num(r.reconcileAmount) === 30), '核销真值 PREPAY_WRITE_OFF 30 落表')
  const xd = await post('/finance/prepay-writeoff/detail', { writeoffId: fx.writeoffId })
  ok(xd.status === 'APPROVED' && num(xd.totalAmount) === 30
    && xd.details[0].settleStatusAfter === '部分结算'
    && num(xd.details[0].unsettledBefore) === 100, 'FX 详情快照：核销前未付100/核销后部分结算')

  // 反审核 → 全部恢复
  await post('/finance/prepay-writeoff/cancel-audit', { writeoffId: fx.writeoffId })
  ok((await prepayBalance(S1)) === 500, '反审核后预付余额恢复=500')
  m = await apMap()
  ok(num(m['AP-M2-10'].paidAmount) === 0 && num(m['AP-M2-10'].unpaidAmount) === 100,
    '反审核后 AP-M2-10 已付0/未付100')
  const apFlowB = await flowRows(S1, 'AP')
  ok(Math.abs(apFlowB.currentBalance - 1930) < 0.001, '反审核后应付余额恢复=1930')
  recs = await reconRows()
  ok(!recs.some(r => r.receiptNo === fx.writeoffNo), '反审核后核销真值记录删除')
  const prepayFlowB = await flowRows(S1, 'PREPAY')
  ok(prepayFlowB.records.some(r => r.writeoffNo === fx.writeoffNo
    && (r.isRed === 'Y' || num(r.isRed) === 1 || r.isRed === true)
    && r.bizType === 'PREPAY_REVERSE'), '预付流水存在红字冲回行（PREPAY_REVERSE isRed=Y）')
  const xd2 = await post('/finance/prepay-writeoff/detail', { writeoffId: fx.writeoffId })
  ok(xd2.status === 'PENDING' && !xd2.details[0].settleStatusAfter, 'FX 回待审核且明细结算标志清空')

  await post('/finance/prepay-writeoff/delete', { writeoffId: fx.writeoffId })
  const pgDel = await fxPage({ writeoffNo: fx.writeoffNo })
  ok(pgDel.total === 0, '待审核 FX 单可删除且列表不可见')

  // ---------------- C. 对账单结算：现金100 + 预付78 + 抹零2 ----------------
  section('C. 供应商对账单结算：FIFO + 自动 FX + 级联反审核')
  const mkStmt = async (billNo, amount) => {
    const s = await post('/finance/supplier-statement/create', {
      customerCode: S1, customerName: N1, salesman: handler,
      statementDate: TODAY, remark: 'M2对账单',
      details: [{
        sourceBillNo: billNo, sourceBillType: '采购收货',
        billAmount: amount, reconcileAmount: amount, unpaidAmount: amount, billRemark: '',
      }],
    })
    await post('/finance/supplier-statement/audit', { statementId: s.statementId })
    return s
  }
  const st1 = await mkStmt('CGSH-M2-CS1', 120)
  const st2 = await mkStmt('CGSH-M2-CS2', 60)
  const cs = await post('/finance/supplier-statement/settle', {
    statementIds: [st1.statementId, st2.statementId],
    handler, settleDate: TODAY, remark: 'M2对账结算验证',
    writeOff: 2,
    accounts: [{ fundAccount: fundName, amount: 100 }],
    usePrepayAmount: 78,
  })
  ok(num(cs.amount) === 100 && num(cs.prepayAmount) === 78 && cs.writeoffNos.length === 1,
    `结算响应：现金100/预付78/自动FX 1张（${cs.writeoffNos.join(',')}）`)
  const fxCsNo = cs.writeoffNos[0]
  m = await apMap()
  // 预付 FIFO 全归最早到期 CS1（78）；现金 CS1=42、CS2=60；抹零 2 不生费用单（供应商侧现状）
  ok(num(m['AP-M2-CS1'].paidAmount) === 120 && m['AP-M2-CS1'].status === '已核销',
    'AP-M2-CS1 预付78+现金42=120 全额核销')
  ok(num(m['AP-M2-CS2'].paidAmount) === 60 && m['AP-M2-CS2'].status === '已核销',
    'AP-M2-CS2 现金60 全额核销')
  ok((await prepayBalance(S1)) === 422, '预付余额 500→422')
  const apFlowC = await flowRows(S1, 'AP')
  ok(Math.abs(apFlowC.currentBalance - 1750) < 0.001, `应付余额 1930→1750（实际 ${apFlowC.currentBalance}）`)
  ok(apFlowC.records.some(r => r.bizType === 'AP_SETTLE_PREPAY' && r.writeoffNo === fxCsNo
    && num(r.decreaseAmount) === 78 && r.apNo === 'AP-M2-CS1'), 'AP_SETTLE_PREPAY 78 归 CS1')
  const cashSettleSum = apFlowC.records
    .filter(r => r.bizType === 'AP_SETTLE_CASH' && r.paymentNo === cs.paymentNo
      && r.reverseStatus !== 'REVERSED')
    .reduce((s, r) => s + num(r.decreaseAmount), 0)
  ok(cashSettleSum === 102, `现金结算流水合计 102（CS1 42+CS2 60，实际 ${cashSettleSum}）`)
  const fxCs = (await fxPage({ writeoffNo: fxCsNo })).records[0]
  ok(fxCs.businessSource === 'STATEMENT_SETTLE' && fxCs.sourceBillNo === st1.statementNo
    && num(fxCs.totalAmount) === 78, `对账自动 FX 来源对账单 ${st1.statementNo}，金额78`)
  const d1 = await post('/finance/supplier-statement/detail', { statementId: st1.statementId })
  const d2 = await post('/finance/supplier-statement/detail', { statementId: st2.statementId })
  ok(d1.payStatus === '完成付款' && num(d1.paidAmount) === 122 && num(d1.writeOffAmount) === 2,
    `对账单1：完成付款 已付122(预付78+现金42+抹零2)（实际 ${d1.paidAmount}/${d1.writeOffAmount}）`)
  ok(d2.payStatus === '完成付款' && num(d2.paidAmount) === 60 && num(d2.writeOffAmount) === 0,
    '对账单2：完成付款 已付60 无抹零')
  recs = await reconRows()
  ok(recs.some(r => r.receiptNo === fxCsNo && r.businessType === 'PREPAY_WRITE_OFF'
    && r.businessNo === 'AP-M2-CS1' && num(r.reconcileAmount) === 78), '预付核销真值：CS1=78')
  ok(recs.some(r => r.receiptNo === cs.paymentNo && r.businessType === 'SUPPLIER_STATEMENT'
    && r.businessNo === 'AP-M2-CS2'), '现金核销真值：CS2 挂锚点付款单')
  ok(recs.some(r => r.receiptNo === cs.paymentNo && r.businessType === 'EXPENSE_WRITEOFF'
    && r.businessNo === st1.statementNo && num(r.reconcileAmount) === 2),
    '抹零真值 EXPENSE_WRITEOFF 2 挂对账单1（供反审核按单冲回）')
  const anchor = await paymentRow(cs.paymentNo)
  ok(anchor.status === 'APPROVED' && anchor.businessSource === 'SUPPLIER_STATEMENT'
    && anchor.paymentType === 'SETTLE' && num(anchor.totalAmount) === 100
    && anchor.relatedBillNo.includes(st1.statementNo) && anchor.relatedBillNo.includes(st2.statementNo),
    '锚点付款单：已审核 SETTLE 100 元，关联两张对账单')
  const fC = await fundRows(cs.paymentNo)
  ok(fC.length === 1 && fC[0].direction === 'OUT' && num(fC[0].amount) === 100,
    '锚点付款单资金 OUT 100')

  // 自动 FX 禁止单独反审核
  await expectFail('/finance/prepay-writeoff/cancel-audit', { writeoffId: fxCs.writeoffId }, '不能单独反审核')
  passed++; console.log('  ✅ 结算自动 FX 禁止单独反审核')

  // 锚点付款单反审核 → 级联冲回
  await post('/finance/payment/cancel-audit', { paymentId: anchor.paymentId })
  ok((await prepayBalance(S1)) === 500, '级联冲回后预付余额恢复=500')
  m = await apMap()
  ok(num(m['AP-M2-CS1'].unpaidAmount) === 120 && num(m['AP-M2-CS2'].unpaidAmount) === 60
    && m['AP-M2-CS1'].status === '未核销' && m['AP-M2-CS2'].status === '未核销',
    'CS1/CS2 反审核后全额恢复未付')
  const apFlowC2 = await flowRows(S1, 'AP')
  ok(Math.abs(apFlowC2.currentBalance - 1930) < 0.001, '应付余额恢复=1930')
  const d1b = await post('/finance/supplier-statement/detail', { statementId: st1.statementId })
  const d2b = await post('/finance/supplier-statement/detail', { statementId: st2.statementId })
  ok(d1b.payStatus === '未付款' && num(d1b.paidAmount) === 0 && num(d1b.writeOffAmount) === 0
    && d2b.payStatus === '未付款' && num(d2b.paidAmount) === 0,
    '两张对账单已付/抹零清零回未付款')
  recs = await reconRows()
  ok(!recs.some(r => r.receiptNo === fxCsNo || r.receiptNo === cs.paymentNo),
    '反审核后 FX 与现金核销真值全部删除')
  ok((await fxPage({ writeoffNo: fxCsNo })).records[0].status === 'CANCELLED',
    '对账自动 FX 级联置为已作废')
  const anchorAfter = await paymentRow(cs.paymentNo)
  ok(anchorAfter.status === 'PENDING', `锚点付款单反审核后回待审核（实际 ${anchorAfter.status}）`)

  // ---------------- D. 全额预付、0 现金也生锚点单 ----------------
  section('D. 全额预付 0 现金：0 额锚点付款单 + 自动 FX')
  const st3 = await mkStmt('CGSH-M2-40', 50)
  const cs2 = await post('/finance/supplier-statement/settle', {
    statementIds: [st3.statementId],
    handler, settleDate: TODAY, remark: 'M2全额预付',
    writeOff: 0, accounts: [], usePrepayAmount: 50,
  })
  ok(num(cs2.amount) === 0 && num(cs2.prepayAmount) === 50 && cs2.writeoffNos.length === 1,
    '全额预付：现金0也结算成功并生自动 FX')
  const fx0No = cs2.writeoffNos[0]
  const anchor0 = await paymentRow(cs2.paymentNo)
  ok(!!anchor0 && num(anchor0.totalAmount) === 0 && anchor0.status === 'APPROVED'
    && anchor0.businessSource === 'SUPPLIER_STATEMENT',
    `0 元锚点付款单 ${cs2.paymentNo} 已生成（SUPPLIER_STATEMENT）`)
  m = await apMap()
  ok(num(m['AP-M2-40'].paidAmount) === 50 && m['AP-M2-40'].status === '已核销',
    'AP-M2-40（空到期日）全额预付已核销')
  ok((await prepayBalance(S1)) === 450, '预付余额→450')
  const f0 = await fundRows(cs2.paymentNo)
  ok(f0.length === 0, '0 额锚点单不写资金流水')
  const d3 = await post('/finance/supplier-statement/detail', { statementId: st3.statementId })
  ok(d3.payStatus === '完成付款' && num(d3.paidAmount) === 50, '对账单3 完成付款 已付50')

  await post('/finance/payment/cancel-audit', { paymentId: anchor0.paymentId })
  ok((await prepayBalance(S1)) === 500, '0 额单反审核级联后预付恢复=500')
  m = await apMap()
  ok(num(m['AP-M2-40'].unpaidAmount) === 50 && m['AP-M2-40'].status === '未核销',
    'AP-M2-40 反审核后恢复未付50')
  const d3b = await post('/finance/supplier-statement/detail', { statementId: st3.statementId })
  ok(d3b.payStatus === '未付款' && num(d3b.paidAmount) === 0, '对账单3 回未付款')
  ok((await fxPage({ writeoffNo: fx0No })).records[0].status === 'CANCELLED',
    '全额预付自动 FX 级联已作废')

  // ---------------- E. 总账三事件 + FX 凭证 ----------------
  section('E. 总账：PREPAY 三事件入池，FX 生成凭证 借2202/贷1123')
  try {
    await post('/finance/gl/init/enable', { startPeriod: '202609' })
    console.log('  ✅ 试算平衡，总账启用成功（202609 进行中）')
  } catch (e) {
    // 重跑场景：验证库已启用过，启用是一次性动作，不影响事件入池
    if (!String(e.message).includes('已启用')) throw e
    console.log('  ✅ 总账已启用（重跑场景，跳过启用），202609 进行中')
  }
  passed++

  const glPp = await post('/finance/payment/create', {
    paymentDate: TODAY, paymentType: 'PREPAY',
    counterpartyType: 'SUPPLIER', counterpartyCode: S1, counterpartyName: N1,
    handler, summary: 'M2-GL预付',
    details: [{ fundAccount: fundName, amount: 50, remark: '' }],
  })
  await post('/finance/payment/audit', { paymentId: glPp.paymentId })
  const glPr = await post('/finance/payment/create', {
    paymentDate: TODAY, paymentType: 'PREPAY_REFUND',
    counterpartyType: 'SUPPLIER', counterpartyCode: S1, counterpartyName: N1,
    handler, summary: 'M2-GL退款',
    details: [{ fundAccount: fundName, amount: 20, remark: '' }],
  })
  await post('/finance/payment/audit', { paymentId: glPr.paymentId })
  const glFx = await mkFx([{ apNo: 'AP-M2-10', writeoffAmount: 30 }])
  await post('/finance/prepay-writeoff/audit', { writeoffId: glFx.writeoffId })
  ok((await prepayBalance(S1)) === 500, 'GL 段结束预付余额=500（500+50-20-30）')
  await new Promise(res => setTimeout(res, 500))

  for (const [code, billNo] of [['PREPAY_PAYMENT', glPp.paymentNo], ['PREPAY_REFUND', glPr.paymentNo]]) {
    const ev = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 50, eventCode: code })
    ok((ev.records || []).some(r => JSON.stringify(r).includes(billNo)),
      `事件池存在 ${code} 事件：${billNo}`)
  }
  const evFx = await post('/finance/gl/event/page', {
    pageNo: 1, pageSize: 50, eventCode: 'PREPAY_WRITE_OFF',
  })
  const eventFx = (evFx.records || []).find(r => JSON.stringify(r).includes(glFx.writeoffNo))
  ok(!!eventFx, '事件池存在 PREPAY_WRITE_OFF 事件：' + glFx.writeoffNo)
  await post('/finance/gl/event/generate', { ids: [eventFx.id] })
  passed++; console.log('  ✅ FX 事件生成凭证成功')
  const vouchers = await post('/finance/gl/voucher/by-bill', { billNo: glFx.writeoffNo })
  ok(Array.isArray(vouchers) && vouchers.length >= 1, 'FX 单据联查查到凭证')
  const voucher = await post('/finance/gl/voucher/detail', { id: vouchers[0].id })
  const entries = voucher.entries || voucher.lines || []
  const dump = JSON.stringify(entries)
  ok(dump.includes('2202') && dump.includes('1123'), '分录含应付账款2202与预付账款1123')
  ok(dump.includes(S1), '2202 分录带供应商辅助核算 S001')
  const debitSum = entries.reduce((s, e) => s + num(e.debitAmount ?? e.debit), 0)
  const creditSum = entries.reduce((s, e) => s + num(e.creditAmount ?? e.credit), 0)
  ok(Math.abs(debitSum - 30) < 0.001 && Math.abs(creditSum - 30) < 0.001,
    `借贷平衡 借30/贷30（实际 借${debitSum}/贷${creditSum}）`)

  // ---------------- F. 分页过滤 + 鉴权 ----------------
  section('F. FX/付款分页过滤与权限')
  const approvedFx = await fxPage({ status: 'APPROVED' })
  ok(approvedFx.records.length > 0 && approvedFx.records.every(r => r.status === 'APPROVED'),
    `FX 状态过滤 APPROVED 全部命中（${approvedFx.records.length} 张）`)
  const manualFx = await fxPage({ businessSource: 'MANUAL' })
  ok(manualFx.records.length >= 1 && manualFx.records.every(r => r.businessSource === 'MANUAL'),
    'FX 业务来源过滤 MANUAL 全部命中')
  const fxBySupp = await fxPage({ supplier: S2 })
  ok(fxBySupp.total === 0, '按 S003 过滤无 FX 单（测试单全部归 S001）')
  const fxByNo = await fxPage({ writeoffNo: 'FX' })
  ok(num(fxByNo.records.length) === num(fxByNo.total) && num(fxByNo.total) >= 3,
    'FX 单号模糊过滤返回全部 FX 单（≥3）')
  const dateQ = await fxPage({ dateFrom: TODAY, dateTo: TODAY })
  ok(dateQ.records.every(r => String(r.writeoffDate).startsWith(TODAY)), 'FX 核销日期区间过滤命中')

  const payPrepay = await post('/finance/payment/page', {
    pageNo: 1, pageSize: 200, filters: { paymentType: 'PREPAY' },
  })
  ok(payPrepay.records.length >= 2 && payPrepay.records.every(r => r.paymentType === 'PREPAY'),
    `付款分页付款类型=预付付款 全部命中（${payPrepay.records.length} 张）`)
  const payRefund = await post('/finance/payment/page', {
    pageNo: 1, pageSize: 200, filters: { paymentType: 'PREPAY_REFUND' },
  })
  ok(payRefund.records.length >= 1 && payRefund.records.every(r => r.paymentTypeText === '预付退款'),
    '付款分页付款类型=预付退款，文案「预付退款」')

  const noAuth = await fetch(BASE + '/finance/prepay-writeoff/page', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }).then(r => r.json())
  ok(noAuth.code !== '0', '无令牌访问 FX 分页被拒绝（code=' + noAuth.code + '）')

  // 收尾：账户总览一致性
  const acctPage = await post('/finance/supplier-account/page', {
    pageNo: 1, pageSize: 200, keyword: S1,
  })
  const acct = acctPage.records.find(r => r.supplierCode === S1)
  // GL 段手工 FX 30 审核后保留（凭证联查用，不反审核），故期末应付真值=1930-30=1900、预付=500
  ok(!!acct && Math.abs(num(acct.prepayBalance) - 500) < 0.001
    && Math.abs(num(acct.apBalance) - 1900) < 0.001,
    `供应商账户总览：预付500/应付1900（实际 ${acct && acct.prepayBalance}/${acct && acct.apBalance}）`)

  console.log(`\n🎉 PRD-36 M2 全部通过：${passed} 项断言`)
}

main().catch(e => {
  console.error('\n❌ 验收失败：' + (e && e.message))
  console.error(e && e.stack)
  process.exit(1)
})
