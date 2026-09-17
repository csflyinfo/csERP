/**
 * PRD-36 M3 端到端 API 验收：厂家费用单 JF + 厂家费用兑现单 DX（现金/冲应付/其他核销）。
 *
 * 隔离环境：8082 + verify-prd36-m1 库。
 * 前置复位（停后端后用 H2 RunScript 依次执行）：
 *   prd36-m2-reset-v129.sql → prd36-m3-reset-v130.sql → prd36-m3-fixtures.sql
 * 运行：API_BASE=http://127.0.0.1:8082/api node development/06-testing/scripts/prd36-m3-api-e2e.mjs
 *
 * 夹具：FE-M3-1~4（客户费用单）、M3推广费/M3仓储费（末级费用类型）、C-M3-01/02（客户）；
 *       AP 复用 M2 夹具 AP-M2-*（S001，复位后全未付）；资金账户「现金」「银行卡」。
 *
 * 覆盖（方案 AC-08~AC-15、AC-21~AC-26）：
 *  1. JF 代垫关联（行级 id + 只录单号）审核：EXPENSE 形成流水、FE 整单占用/释放、行金额可小于 FE
 *  2. 红字：默认分摊/限额/重复红冲拦截、审核冲减原单未兑现额与状态、反审核恢复、红字不复制 FE
 *  3. P0190 补录开关：N 保存拒 / Y 审核立费用账户但不发 GL 事件
 *  4. CASH：多资金行守恒、资金 IN/余额链、EXPENSE 兑现流水、GL @FUND+CF03、反审核红字 OUT（半角括号）
 *  5. OFFSET：两张 DX 交错冲同一 AP，反其中一张按剩余 SUM 重算 fin_ap/pur_receipt；
 *     核销真值 FACTORY_EXPENSE_OFFSET、AP 流水 AP_SETTLE_EXPENSE、GL 2202/1221；
 *     负 AP/超额/跨供应商/不平衡守卫；JF 被引用时反审核拒
 *  6. OTHER：1123 转预付（DXP）后被 FX 占用→反审核守卫；5711 无原因拒；560105 减免；
 *     GL 对方科目（1123 挂供应商辅助、5711/560105 自动跳过）/1221
 *  7. 反审核→修改→再审核：同单号第二轮 FACTORY_EXP_FORM 流水
 *  8. 导入：按供应商+日期+性质分组生 PENDING、FE 关联整单占用、部分失败
 *  9. JF/DX 分页过滤、候选接口、print-data、export-notice、无令牌 401
 */
process.env.API_BASE = process.env.API_BASE || 'http://127.0.0.1:8082/api'
const cm = (await import('./init-common.js')).default

const { post, get, login, expectFail, assert, num, todayStr } = cm
const BASE = cm.BASE

/** init-common 不导出 token，二进制下载场景自行登录取令牌。 */
async function freshToken() {
  const res = await fetch(BASE + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error('二进制下载登录失败：' + json.message)
  return json.data.token
}

const S1 = 'S001'
const N1 = '甲厂家'
const S2 = 'S003'
const TODAY = todayStr()
const CASH = '现金'
const BANK = '银行卡'

let passed = 0
function ok(cond, msg) {
  assert(cond, msg)
  passed++
  console.log('  ✅ ' + msg)
}
function section(title) {
  console.log('\n=== ' + title + ' ===')
}
const sleep = ms => new Promise(res => setTimeout(res, ms))

// ---------------- 取数助手 ----------------
async function jfPage(q = {}) {
  return post('/finance/factory-expense/page', { pageNo: 1, pageSize: 200, ...q })
}
async function jfDetail(id) {
  return post('/finance/factory-expense/detail', { factoryExpenseId: id })
}
async function dxPage(q = {}) {
  return post('/finance/factory-settle/page', { pageNo: 1, pageSize: 200, ...q })
}
async function dxById(no) {
  const p = await dxPage({ settleNo: no })
  return (p.records || []).find(r => r.settleNo === no) || null
}
async function dxDetail(id) {
  return post('/finance/factory-settle/detail', { settleId: id })
}
async function flowRows(code, accountType) {
  const p = await post('/finance/supplier-account/flow/page', {
    supplierCode: code, accountType, pageNo: 1, pageSize: 200,
  })
  return { records: p.records || [], currentBalance: num(p.currentBalance) }
}
async function apMap() {
  const p = await post('/finance/ap/page', { pageNo: 1, pageSize: 200, filters: { supplier: N1 } })
  const m = {}
  for (const r of (p.records || [])) m[r.apNo] = r
  return m
}
async function prepayBalance(code = S1) {
  const d = await post('/finance/supplier-account/prepay-balance', { supplierCode: code })
  return num(d.prepayBalance)
}
async function reconRows() {
  const p = await post('/finance/reconcile-record/page', {
    pageNo: 1, pageSize: 200, filters: { counterparty: N1 },
  })
  return p.records || []
}
async function feCandidates(currentJfNo = '') {
  return post('/finance/factory-expense/customer-expense-candidates', { currentJfNo })
}
async function receiptStatus(no) {
  const p = await post('/purchase/receipt/page', { pageNo: 1, pageSize: 200 })
  const r = (p.records || []).find(x => x.receiptNo === no)
  return r ? r.payStatus : '(不存在)'
}
async function fundArchive(name) {
  const p = await post('/base/master/fund-account/page', { pageNo: 1, pageSize: 50 })
  const r = (p.records || []).find(x => x.fundAccountName === name)
  return r ? num(r.balance) : null
}
async function fundLedger(predicate) {
  const p = await post('/finance/fund-ledger/page', {
    pageNo: 1, pageSize: 200, filters: { dateFrom: TODAY, dateTo: TODAY },
  })
  return (p.records || []).filter(predicate)
}
async function setParam(key, value) {
  await post('/system/param/update', { paramKey: key, paramValue: value })
}

/** 从事件池取事件生成凭证，返回凭证分录（事件在事务提交后异步入池，取前等待）。 */
async function glVoucherEntries(eventCode, billNo) {
  await sleep(500)
  const ev = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 200, eventCode })
  const rec = (ev.records || []).find(r => JSON.stringify(r).includes(billNo))
  if (!rec) throw new Error(`事件池缺少 ${eventCode} / ${billNo}`)
  await post('/finance/gl/event/generate', { ids: [rec.id] })
  const vouchers = await post('/finance/gl/voucher/by-bill', { billNo })
  assert(Array.isArray(vouchers) && vouchers.length >= 1, `${billNo} 联查不到凭证`)
  const v = await post('/finance/gl/voucher/detail', { id: vouchers[0].id })
  return v.entries || v.lines || []
}
async function glEventExists(eventCode, billNo) {
  await sleep(500)
  const ev = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 200, eventCode })
  return (ev.records || []).some(r => JSON.stringify(r).includes(billNo))
}
const sumSide = (entries, side) =>
  entries.reduce((s, e) => s + num(e[side === 'debit' ? 'debitAmount' : 'creditAmount']), 0)
const entriesOf = (entries, code) => entries.filter(e => e.accountCode === code)

async function main() {
  await login()
  console.log('登录成功：' + BASE)

  const emps = await post('/base/master/employee/page', { pageNo: 1, pageSize: 50 })
  const handler = (emps.records && emps.records[0] && emps.records[0].employeeName) || '系统管理员'
  console.log('经手人：' + handler)

  // ---------------- 0. 夹具/复位检查 ----------------
  section('0. 夹具就位与账户修复')
  const jf0 = await jfPage()
  const dx0 = await dxPage()
  if (num(jf0.total) !== 0 || num(dx0.total) !== 0) {
    throw new Error('验证库存在残留 JF/DX，请先执行 prd36-m3-reset-v130.sql（JF=' + jf0.total + ' DX=' + dx0.total + '）')
  }
  const cand0 = await feCandidates()
  for (const no of ['FE-M3-1', 'FE-M3-2', 'FE-M3-3', 'FE-M3-4']) {
    ok(cand0.some(r => r.expenseNo === no), `FE 候选存在 ${no}`)
  }
  const m0 = await apMap()
  assert(m0['AP-M2-10'] && m0['AP-M2-CS1'] && m0['AP-M2-30'], '缺少 AP-M2-* 夹具')
  assert(num(m0['AP-M2-10'].unpaidAmount) === 100, 'AP-M2-10 复位为未付100')
  console.log('  AP-M2-* 夹具已复位为全未付')

  await post('/finance/supplier-account/repair', { supplierCode: S1 })
  await post('/finance/supplier-account/repair', { supplierCode: S2 })
  const apBase = (await flowRows(S1, 'AP')).currentBalance
  ok(Math.abs(apBase - 1930) < 0.001, `修复后 S001 应付余额=1930（实际 ${apBase}）`)
  ok((await prepayBalance()) === 0, '修复后预付余额=0')
  const expBase = (await flowRows(S1, 'EXPENSE')).currentBalance
  ok(expBase === 0, `修复后费用余额=0（实际 ${expBase}）`)
  const cash0 = await fundArchive(CASH)
  const bank0 = await fundArchive(BANK)
  console.log(`资金档案基线：现金 ${cash0} / 银行卡 ${bank0}`)

  // 总账启用（重跑场景容忍已启用）
  try {
    await post('/finance/gl/init/enable', { startPeriod: '202609' })
    console.log('  ✅ 总账启用成功（202609）')
  } catch (e) {
    if (!String(e.message).includes('已启用')) throw e
    console.log('  ✅ 总账已启用（重跑场景）')
  }
  passed++

  // ---------------- 1. JF 建单守卫 ----------------
  section('1. JF 建单校验')
  const lineA = { expenseTypeName: 'M3推广费', customerCode: 'C-M3-01', customerName: '甲客户',
    customerExpenseNo: 'FE-M3-1', customerExpenseDetailId: 'FED-M3-1A', amount: 60, remark: '推广' }
  const lineB = { expenseTypeName: 'M3仓储费', customerCode: 'C-M3-01', customerName: '甲客户',
    customerExpenseNo: 'FE-M3-1', customerExpenseDetailId: 'FED-M3-1B', amount: 40, remark: '仓储' }
  const mkJf = (body) => post('/finance/factory-expense/create', {
    supplierCode: S1, supplierName: N1, expenseDate: TODAY, claimType: 'ADVANCE',
    handler, department: '销售部', remark: 'M3-E2E', ...body,
  })

  await expectFail('/finance/factory-expense/create', {
    expenseDate: TODAY, claimType: 'ADVANCE', handler,
    details: [lineA],
  }, '请选择供应商')
  passed++; console.log('  ✅ 无供应商 → 拦截')

  await expectFail('/finance/factory-expense/create', {
    supplierCode: S1, expenseDate: TODAY, claimType: 'OTHER', handler,
    details: [{ expenseTypeName: 'M3推广费', customerCode: 'C-M3-01',
      customerExpenseNo: 'FE-M3-2', customerExpenseDetailId: 'FED-M3-2A', amount: 10 }],
  }, '不能关联客户费用单')
  passed++; console.log('  ✅ 其他性质关联 FE → 拦截')

  await expectFail('/finance/factory-expense/create', {
    supplierCode: S1, expenseDate: TODAY, claimType: 'ADVANCE', handler,
    details: [
      { expenseTypeName: 'M3仓储费', customerCode: 'C-M3-02', customerExpenseNo: 'FE-M3-2', amount: 30 },
      { expenseTypeName: 'M3仓储费', customerCode: 'C-M3-02', customerExpenseNo: 'FE-M3-2', amount: 50 },
    ],
  }, '重复关联，请合并为一行')
  passed++; console.log('  ✅ 同 FE 只录单号多行 → 拦截合并')

  await expectFail('/finance/factory-expense/create', {
    supplierCode: S1, expenseDate: TODAY, claimType: 'ADVANCE', handler,
    details: [{ expenseTypeName: 'M3推广费', customerCode: 'C-NOPE', amount: 10 }],
  }, '垫付客户编码')
  passed++; console.log('  ✅ 不存在客户 → 拦截')

  // ---------------- 2. JF 建单/审核：行级关联 + 只录单号 + FE 整单占用 ----------------
  section('2. JF 代垫建单与审核（FE 占用、费用账户流水、GL）')
  const jfA = await mkJf({ details: [lineA, lineB], externalVoucherNo: 'HT-M3-A' })
  ok(/^JF/.test(jfA.factoryExpenseNo), 'JF-A 已建：' + jfA.factoryExpenseNo)
  // PENDING 建单即占 FE（候选消失）
  const candAfterPending = await feCandidates()
  ok(!candAfterPending.some(r => r.expenseNo === 'FE-M3-1'), 'JF-A 保存后 FE-M3-1 整单移出候选（含未关联行 FED 维度）')

  // 第二张 JF 再关联 FE-M3-1（行级 / 只录单号两种姿势都拒）
  await expectFail('/finance/factory-expense/create', {
    supplierCode: S1, expenseDate: TODAY, claimType: 'ADVANCE', handler,
    details: [{ expenseTypeName: 'M3仓储费', customerCode: 'C-M3-01',
      customerExpenseNo: 'FE-M3-1', customerExpenseDetailId: 'FED-M3-1B', amount: 40 }],
  }, '明细行已被其他厂家费用单关联')
  passed++; console.log('  ✅ FE-M3-1 行级二次关联 → 拦截')
  await expectFail('/finance/factory-expense/create', {
    supplierCode: S1, expenseDate: TODAY, claimType: 'ADVANCE', handler,
    details: [{ expenseTypeName: 'M3仓储费', customerCode: 'C-M3-01',
      customerExpenseNo: 'FE-M3-1', amount: 40 }],
  }, '一张客户费用单只能关联一张厂家费用')
  passed++; console.log('  ✅ FE-M3-1 只录单号二次关联 → 拦截')

  // JF-B：只录单号（无行 id）
  const jfB = await mkJf({
    details: [{ expenseTypeName: 'M3仓储费', customerCode: 'C-M3-02', customerName: '乙客户',
      customerExpenseNo: 'FE-M3-2', amount: 80 }],
  })
  // JF-C：行金额 30 < FE 行金额 50（差额我方自担）
  const jfC = await mkJf({
    details: [{ expenseTypeName: 'M3推广费', customerCode: 'C-M3-01', customerName: '甲客户',
      customerExpenseNo: 'FE-M3-3', customerExpenseDetailId: 'FED-M3-3A', amount: 30 }],
  })
  // JF-D：其他性质手工行（不带 FE，贷方科目取费用类型厂家科目 560199）
  const jfD = await mkJf({
    claimType: 'OTHER',
    details: [{ expenseTypeName: 'M3推广费', amount: 20, remark: '厂家承担其他费用' }],
  })
  ok(jfB.factoryExpenseNo && jfC.factoryExpenseNo && jfD.factoryExpenseNo, 'JF-B/C/D 已建')

  const dPending = await jfDetail(jfD.factoryExpenseId)
  ok(dPending.details[0].glCreditSubject === '560199', '其他性质行贷方科目=560199（费用类型档案）')

  // PENDING JF 不能被兑现
  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, settleType: 'OTHER', handler,
    contraSubjectCode: '5711', remark: 'M3坏账',
    jfDetails: [{ factoryExpenseNo: jfD.factoryExpenseNo, settleAmount: 20 }],
  }, '未审核，不能兑现')
  passed++; console.log('  ✅ PENDING JF 不可兑现 → 拦截')

  await post('/finance/factory-expense/audit', { factoryExpenseId: jfA.factoryExpenseId })
  await post('/finance/factory-expense/audit', { factoryExpenseId: jfB.factoryExpenseId })
  await post('/finance/factory-expense/audit', { factoryExpenseId: jfC.factoryExpenseId })
  await post('/finance/factory-expense/audit', { factoryExpenseId: jfD.factoryExpenseId })

  let exp = await flowRows(S1, 'EXPENSE')
  for (const [j, amt] of [[jfA, 100], [jfB, 80], [jfC, 30], [jfD, 20]]) {
    const r = exp.records.find(x => x.factoryExpenseNo === j.factoryExpenseNo
      && x.bizType === 'FACTORY_EXP_FORM' && x.reverseStatus !== 'REVERSED')
    ok(!!r && num(r.increaseAmount) === amt, `EXPENSE 形成流水 ${j.factoryExpenseNo} +${amt}`)
  }
  ok(Math.abs(exp.currentBalance - 230) < 0.001, `四张 JF 审核后费用余额=230（实际 ${exp.currentBalance}）`)
  // 形成行兑现标志
  const formA = exp.records.find(x => x.factoryExpenseNo === jfA.factoryExpenseNo
    && x.bizType === 'FACTORY_EXP_FORM' && x.reverseStatus !== 'REVERSED')
  ok(formA.settleStatus === '待兑现' && num(formA.settledAmount) === 0, '形成行标志：待兑现/已兑现0')

  // JF 头三金额/状态
  const aRow = (await jfPage({ factoryExpenseNo: jfA.factoryExpenseNo })).records[0]
  ok(num(aRow.totalAmount) === 100 && num(aRow.unsettledAmount) === 100
    && aRow.settleStatus === '待兑现' && aRow.status === 'APPROVED',
    'JF-A：总额100/未兑现100/待兑现/已审核')

  // GL：JF-A 借1221（供应商辅助）/贷560105 60 + 560102 40
  const eA = await glVoucherEntries('FACTORY_EXPENSE', jfA.factoryExpenseNo)
  ok(sumSide(eA, 'debit') === 100 && sumSide(eA, 'credit') === 100,
    `JF-A 凭证借贷平衡 100（借${sumSide(eA, 'debit')}/贷${sumSide(eA, 'credit')}）`)
  const debit1221 = entriesOf(eA, '1221')
  ok(debit1221.length === 1 && num(debit1221[0].debitAmount) === 100
    && debit1221[0].auxSupplier === S1, '借1221 100 挂供应商辅助 S001')
  ok(num((entriesOf(eA, '560105')[0] || {}).creditAmount) === 60
    && num((entriesOf(eA, '560102')[0] || {}).creditAmount) === 40,
    '贷按明细行展开：560105 60 / 560102 40')

  // ---------------- 3. 红字 ----------------
  section('3. 红字 JF：限额/状态联动/反审核/红字不复制 FE')
  await expectFail('/finance/factory-expense/red-create', {
    redSourceNo: jfA.factoryExpenseNo,
    lines: [{ expenseTypeName: 'M3推广费', amount: -200 }],
  }, '超过原单剩余未兑现额')
  passed++; console.log('  ✅ 红字 -200 > 未兑现 100 → 拦截')

  const red = await post('/finance/factory-expense/red-create', {
    redSourceNo: jfA.factoryExpenseNo, remark: 'M3部分红冲',
    lines: [{ expenseTypeName: 'M3推广费', amount: -40 }],
  })
  ok(/^JF/.test(red.factoryExpenseNo), '红字单已建：' + red.factoryExpenseNo)
  const redPending = await jfDetail(red.factoryExpenseId)
  ok(num(redPending.details[0].amount) === -40 && !redPending.details[0].customerExpenseNo
    && redPending.isRed === 'Y', '红字行金额 -40 且不复制 FE 单号/行 id')
  await post('/finance/factory-expense/audit', { factoryExpenseId: red.factoryExpenseId })

  exp = await flowRows(S1, 'EXPENSE')
  const redFlow = exp.records.find(x => x.factoryExpenseNo === red.factoryExpenseNo
    && x.bizType === 'FACTORY_EXP_RED')
  ok(!!redFlow && num(redFlow.increaseAmount) === -40, 'EXPENSE 红字流水 FACTORY_EXP_RED -40')
  const aPart = (await jfPage({ factoryExpenseNo: jfA.factoryExpenseNo })).records[0]
  ok(num(aPart.unsettledAmount) === 60 && aPart.settleStatus === '部分兑现',
    `原单冲减后未兑现60/部分兑现（实际 ${aPart.unsettledAmount}/${aPart.settleStatus}）`)
  ok(Math.abs(exp.currentBalance - 190) < 0.001, `费用余额 230→190（实际 ${exp.currentBalance}）`)

  // 红字 GL：同构负金额事件
  const eRed = await glVoucherEntries('FACTORY_EXPENSE', red.factoryExpenseNo)
  ok(sumSide(eRed, 'debit') === -40 && sumSide(eRed, 'credit') === -40,
    `红字凭证借贷同为 -40（借${sumSide(eRed, 'debit')}/贷${sumSide(eRed, 'credit')}）`)

  // 已有审核红字期间禁止再红
  await expectFail('/finance/factory-expense/red-create', {
    redSourceNo: jfA.factoryExpenseNo,
    lines: [{ expenseTypeName: 'M3推广费', amount: -10 }],
  }, '已有审核通过的红字单')
  passed++; console.log('  ✅ 已审红字期间再开红字 → 拦截')

  // 已审红字单不能被兑现
  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, handler,
    settleType: 'CASH',
    jfDetails: [{ factoryExpenseNo: red.factoryExpenseNo, settleAmount: 40 }],
    fundDetails: [{ fundAccount: CASH, amount: 40 }],
  }, '红字厂家费用单')
  passed++; console.log('  ✅ 红字厂家费用单不能被兑现 → 拦截')

  // 红字反审核 → 原单恢复
  await post('/finance/factory-expense/cancel-audit', { factoryExpenseId: red.factoryExpenseId })
  exp = await flowRows(S1, 'EXPENSE')
  ok(exp.records.some(x => x.factoryExpenseNo === red.factoryExpenseNo
    && x.bizType === 'FACTORY_EXP_REVERSE' && num(x.increaseAmount) === 40),
    '红字反审核追加 FACTORY_EXP_REVERSE +40')
  const aBack = (await jfPage({ factoryExpenseNo: jfA.factoryExpenseNo })).records[0]
  ok(num(aBack.unsettledAmount) === 100 && aBack.settleStatus === '待兑现',
    '红字反审核后原单恢复未兑现100/待兑现')
  ok(Math.abs(exp.currentBalance - 230) < 0.001, `费用余额恢复=230（实际 ${exp.currentBalance}）`)

  // ---------------- 4. DX 建单守卫 ----------------
  section('4. DX 建单校验（超额/负AP/跨供应商/不平衡/科目白名单/坏账原因）')
  const dxCreate = (body) => post('/finance/factory-settle/create', {
    supplierCode: S1, supplierName: N1, settleDate: TODAY, handler, remark: 'M3-E2E', ...body,
  })
  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, handler,
    settleType: 'CASH',
    jfDetails: [{ factoryExpenseNo: jfB.factoryExpenseNo, settleAmount: 9999 }],
    fundDetails: [{ fundAccount: CASH, amount: 9999 }],
  }, '剩余未兑现额仅')
  passed++; console.log('  ✅ 兑现超 JF 未兑现额 → 拦截')

  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, handler,
    settleType: 'CASH',
    jfDetails: [{ factoryExpenseNo: jfB.factoryExpenseNo, settleAmount: 80 }],
    fundDetails: [{ fundAccount: CASH, amount: 50 }, { fundAccount: BANK, amount: 20 }],
  }, '资金到账合计')
  passed++; console.log('  ✅ CASH 资金合计 70 ≠ 费用 80 → 拦截')

  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, handler,
    settleType: 'OFFSET',
    jfDetails: [{ factoryExpenseNo: jfB.factoryExpenseNo, settleAmount: 80 }],
    apDetails: [{ apNo: 'AP-M2-10', offsetAmount: 10 }],
  }, '冲应付合计')
  passed++; console.log('  ✅ OFFSET 冲应付合计 10 ≠ 费用 80 → 拦截')

  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, handler,
    settleType: 'OFFSET',
    jfDetails: [{ factoryExpenseNo: jfB.factoryExpenseNo, settleAmount: 80 }],
    apDetails: [{ apNo: 'AP-TEST-2', offsetAmount: 80 }],
  }, '负数应付单')
  passed++; console.log('  ✅ 负数应付单 AP-TEST-2 账扣 → 拦截')

  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, handler,
    settleType: 'OFFSET',
    jfDetails: [{ factoryExpenseNo: jfB.factoryExpenseNo, settleAmount: 80 }],
    apDetails: [{ apNo: 'AP-M2-30', offsetAmount: 301 }],
  }, '本次冲销')
  passed++; console.log('  ✅ 冲销额超 AP 未结 → 拦截')

  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, handler,
    settleType: 'OFFSET',
    jfDetails: [{ factoryExpenseNo: jfB.factoryExpenseNo, settleAmount: 80 }],
    apDetails: [{ apNo: 'AP-M2-X1', offsetAmount: 80 }],
  }, '不属于供应商')
  passed++; console.log('  ✅ 他供应商 AP（AP-M2-X1）→ 拦截')

  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, handler,
    settleType: 'OTHER',
    jfDetails: [{ factoryExpenseNo: jfB.factoryExpenseNo, settleAmount: 80 }],
  }, '请选择对方科目')
  passed++; console.log('  ✅ OTHER 无对方科目 → 拦截')

  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, handler,
    settleType: 'OTHER',
    jfDetails: [{ factoryExpenseNo: jfB.factoryExpenseNo, settleAmount: 80 }],
    contraSubjectCode: '2202',
  }, '不在允许范围')
  passed++; console.log('  ✅ 对方科目 2202 不在白名单 → 拦截')

  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, handler,
    settleType: 'OTHER',
    jfDetails: [{ factoryExpenseNo: jfD.factoryExpenseNo, settleAmount: 20 }],
    contraSubjectCode: '5711', remark: '',
  }, '坏账核销')
  passed++; console.log('  ✅ 5711 无原因备注 → 拦截（建单校验）')

  await expectFail('/finance/factory-settle/create', {
    supplierCode: S1, settleDate: TODAY, handler: '',
    settleType: 'CASH',
    jfDetails: [{ factoryExpenseNo: jfB.factoryExpenseNo, settleAmount: 80 }],
    fundDetails: [{ fundAccount: CASH, amount: 80 }],
  }, '请填写经手人')
  passed++; console.log('  ✅ 无经手人 → 拦截')

  // ---------------- 5. CASH 现金结算（多资金行） ----------------
  section('5. CASH 现金结算：多资金行守恒 / GL CF03 / 反审核红字')
  const dxCash = await dxCreate({
    settleType: 'CASH',
    jfDetails: [{ factoryExpenseNo: jfB.factoryExpenseNo, settleAmount: 80 }],
    fundDetails: [
      { fundAccount: CASH, amount: 50, remark: '现金到账' },
      { fundAccount: BANK, amount: 30, remark: '银行到账' },
    ],
  })
  ok(/^DX/.test(dxCash.settleNo), 'CASH 兑现单已建：' + dxCash.settleNo)
  await post('/finance/factory-settle/audit', { settleId: dxCash.settleId })

  ok((await fundArchive(CASH)) === cash0 + 50, `现金档案余额 +50（${cash0}→${cash0 + 50}）`)
  ok((await fundArchive(BANK)) === bank0 + 30, `银行卡档案余额 +30（${bank0}→${bank0 + 30}）`)
  const flIn = await fundLedger(r => r.sourceBill === dxCash.settleNo && r.direction === 'IN')
  ok(flIn.length === 2, '资金流水两行 IN（DX 号）')
  const cashIn = flIn.find(r => r.fundAccount === CASH)
  const bankIn = flIn.find(r => r.fundAccount === BANK)
  ok(num(cashIn.amount) === 50 && num(bankIn.amount) === 30
    && num(cashIn.balanceAfter) === cash0 + 50
    && num(bankIn.balanceAfter) === bank0 + 30,
    'IN 金额与滚存余额链正确（50/30）')

  exp = await flowRows(S1, 'EXPENSE')
  const cashSettle = exp.records.find(x => x.paymentNo === dxCash.settleNo
    && x.bizType === 'FACTORY_EXP_SETTLE_CASH' && x.reverseStatus !== 'REVERSED')
  ok(!!cashSettle && num(cashSettle.decreaseAmount) === 80, 'EXPENSE FACTORY_EXP_SETTLE_CASH -80')
  const bDone = (await jfPage({ factoryExpenseNo: jfB.factoryExpenseNo })).records[0]
  ok(num(bDone.unsettledAmount) === 0 && bDone.settleStatus === '已兑现',
    'JF-B 全额兑现：未兑现0/已兑现')
  ok(Math.abs(exp.currentBalance - 150) < 0.001, `费用余额 230→150（实际 ${exp.currentBalance}）`)

  const eCash = await glVoucherEntries('FACTORY_SETTLE_CASH', dxCash.settleNo)
  ok(sumSide(eCash, 'debit') === 80 && sumSide(eCash, 'credit') === 80, 'CASH 凭证借贷平衡 80')
  ok(num((entriesOf(eCash, '1001')[0] || {}).debitAmount) === 50
    && num((entriesOf(eCash, '100201')[0] || {}).debitAmount) === 30,
    '借按资金行展开：1001 50 / 100201 30')
  ok(eCash.filter(e => num(e.debitAmount) > 0).every(e => e.cashFlowItem === 'CF03'),
    '所有借方资金行现金流量=CF03')
  const credit1221 = entriesOf(eCash, '1221')
  ok(credit1221.length === 1 && num(credit1221[0].creditAmount) === 80
    && credit1221[0].auxSupplier === S1, '贷1221 80 挂供应商辅助')

  // 反审核 → 红字 OUT、半角括号、余额链回退、JF 恢复
  await post('/finance/factory-settle/cancel-audit', { settleId: dxCash.settleId })
  const flOut = await fundLedger(r => r.sourceBill === dxCash.settleNo + '(取消审核)' && r.direction === 'OUT')
  ok(flOut.length === 2 && flOut.every(r => num(r.amount) > 0),
    '反审核追加两行红字 OUT，sourceBill 含半角「(取消审核)」')
  ok((await fundArchive(CASH)) === cash0 && (await fundArchive(BANK)) === bank0,
    '资金档案余额恢复基线')
  exp = await flowRows(S1, 'EXPENSE')
  ok(exp.records.some(x => x.paymentNo === dxCash.settleNo
    && x.bizType === 'FACTORY_EXP_REVERSE' && num(x.increaseAmount) === 80),
    'EXPENSE 现金兑现冲回 +80')
  const bBack = (await jfPage({ factoryExpenseNo: jfB.factoryExpenseNo })).records[0]
  ok(num(bBack.unsettledAmount) === 80 && bBack.settleStatus === '待兑现',
    'JF-B 反审核后恢复未兑现80/待兑现')
  ok(Math.abs(exp.currentBalance - 230) < 0.001, `费用余额恢复=230（实际 ${exp.currentBalance}）`)

  // ---------------- 6. OFFSET 冲应付（两张 DX 交错冲同一 AP） ----------------
  section('6. OFFSET 冲应付：交错核销 / 按剩余 SUM 重算 / 真值 / GL')
  const dxO1 = await dxCreate({
    settleType: 'OFFSET',
    jfDetails: [{ factoryExpenseNo: jfA.factoryExpenseNo, settleAmount: 60 }],
    apDetails: [{ apNo: 'AP-M2-10', offsetAmount: 60 }],
  })
  await post('/finance/factory-settle/audit', { settleId: dxO1.settleId })
  let m = await apMap()
  ok(num(m['AP-M2-10'].paidAmount) === 60 && num(m['AP-M2-10'].unpaidAmount) === 40
    && m['AP-M2-10'].status === '未核销', 'AP-M2-10：已付60/未付40/部分')
  ok(await receiptStatus('CGSH-M2-10') === '部分付款', '收货单 CGSH-M2-10 付款状态=部分付款')
  let apf = await flowRows(S1, 'AP')
  ok(apf.records.some(x => x.bizType === 'AP_SETTLE_EXPENSE' && x.apNo === 'AP-M2-10'
    && num(x.decreaseAmount) === 60 && x.receiptNo === dxO1.settleNo), 'AP 流水 AP_SETTLE_EXPENSE -60')
  let recs = await reconRows()
  ok(recs.some(r => r.receiptNo === dxO1.settleNo && r.businessType === 'FACTORY_EXPENSE_OFFSET'
    && r.businessNo === 'AP-M2-10' && num(r.reconcileAmount) === 60),
    '核销真值：FACTORY_EXPENSE_OFFSET 60（businessNo=AP-M2-10）')
  const o1Detail = await dxDetail(dxO1.settleId)
  ok(num(o1Detail.apDetails[0].unpaidBefore) === 100
    && o1Detail.apDetails[0].settleStatusAfter === '部分结算',
    'O1 快照：核销前未付100/核销后部分结算')

  const eO1 = await glVoucherEntries('FACTORY_SETTLE_OFFSET', dxO1.settleNo)
  ok(entriesOf(eO1, '2202').length === 1 && entriesOf(eO1, '2202')[0].auxSupplier === S1
    && entriesOf(eO1, '1221').length === 1 && entriesOf(eO1, '1221')[0].auxSupplier === S1
    && sumSide(eO1, 'debit') === 60 && sumSide(eO1, 'credit') === 60,
    'O1 凭证：借2202/贷1221 各60，均挂供应商辅助')

  // JF-A 已被 APPROVED DX 引用 → 反审核拒
  await expectFail('/finance/factory-expense/cancel-audit', { factoryExpenseId: jfA.factoryExpenseId },
    '已被兑现单兑现')
  passed++; console.log('  ✅ JF-A 被已审 DX 引用时反审核 → 拦截')

  // 第二张 DX 冲剩余 40 → AP 全额核销、收货单完成付款
  const dxO2 = await dxCreate({
    settleType: 'OFFSET',
    jfDetails: [{ factoryExpenseNo: jfA.factoryExpenseNo, settleAmount: 40 }],
    apDetails: [{ apNo: 'AP-M2-10', offsetAmount: 40 }],
  })
  await post('/finance/factory-settle/audit', { settleId: dxO2.settleId })
  m = await apMap()
  ok(num(m['AP-M2-10'].paidAmount) === 100 && num(m['AP-M2-10'].unpaidAmount) === 0
    && m['AP-M2-10'].status === '已核销', 'AP-M2-10：已付100/未付0/已核销')
  ok(await receiptStatus('CGSH-M2-10') === '完成付款', '收货单付款状态=完成付款')
  recs = await reconRows()
  ok(recs.filter(r => r.businessNo === 'AP-M2-10' && r.businessType === 'FACTORY_EXPENSE_OFFSET')
    .reduce((s, r) => s + num(r.reconcileAmount), 0) === 100, '两条核销真值合计 100')
  const aFully = (await jfPage({ factoryExpenseNo: jfA.factoryExpenseNo })).records[0]
  ok(num(aFully.settledAmount) === 100 && aFully.settleStatus === '已兑现', 'JF-A 已兑现100/已兑现')

  // 反 O1：真值删 O1，按剩余 O2 记录 SUM 重算 → paid40/unpaid60
  await post('/finance/factory-settle/cancel-audit', { settleId: dxO1.settleId })
  m = await apMap()
  ok(num(m['AP-M2-10'].paidAmount) === 40 && num(m['AP-M2-10'].unpaidAmount) === 60
    && m['AP-M2-10'].status === '未核销',
    `反 O1 后按剩余真值重算：已付40/未付60（实际 ${m['AP-M2-10'].paidAmount}/${m['AP-M2-10'].unpaidAmount}）`)
  ok(await receiptStatus('CGSH-M2-10') === '部分付款', '收货单回退=部分付款')
  apf = await flowRows(S1, 'AP')
  ok(apf.records.some(x => x.bizType === 'AP_REVERSE' && x.apNo === 'AP-M2-10'
    && num(x.increaseAmount) === 60 && x.receiptNo === dxO1.settleNo), 'AP 冲回流水 AP_REVERSE +60')
  recs = await reconRows()
  ok(!recs.some(r => r.receiptNo === dxO1.settleNo)
    && recs.some(r => r.receiptNo === dxO2.settleNo && num(r.reconcileAmount) === 40),
    'O1 真值删除、O2 真值保留 40')
  const aMid = (await jfPage({ factoryExpenseNo: jfA.factoryExpenseNo })).records[0]
  ok(num(aMid.settledAmount) === 40 && aMid.settleStatus === '部分兑现',
    '反 O1 后 JF-A 回退：已兑现40/部分兑现')

  // 反 O2 → 全恢复
  await post('/finance/factory-settle/cancel-audit', { settleId: dxO2.settleId })
  m = await apMap()
  ok(num(m['AP-M2-10'].paidAmount) === 0 && num(m['AP-M2-10'].unpaidAmount) === 100
    && m['AP-M2-10'].status === '未核销', '反 O2 后 AP-M2-10 恢复未付100')
  ok(await receiptStatus('CGSH-M2-10') === '未付款', '收货单恢复=未付款')
  const aFin = (await jfPage({ factoryExpenseNo: jfA.factoryExpenseNo })).records[0]
  ok(num(aFin.unsettledAmount) === 100 && aFin.settleStatus === '待兑现', 'JF-A 恢复未兑现100/待兑现')

  // ---------------- 7. OTHER：1123 转预付 + FX 占用守卫 ----------------
  section('7. OTHER-1123：费用转预付 / 下游占用守卫 / 反审核')
  const dx1123 = await dxCreate({
    settleType: 'OTHER', contraSubjectCode: '1123', relatedBillNo: 'YF-M3-1',
    jfDetails: [{ factoryExpenseNo: jfC.factoryExpenseNo, settleAmount: 30 }],
  })
  await post('/finance/factory-settle/audit', { settleId: dx1123.settleId })
  ok((await prepayBalance()) === 30, '1123 兑现后预付余额 +30')
  let pre = await flowRows(S1, 'PREPAY')
  ok(pre.records.some(x => x.bizType === 'PREPAY_EXPENSE_TRANSFER'
    && num(x.increaseAmount) === 30 && x.receiptNo === dx1123.settleNo), '预付流水 PREPAY_EXPENSE_TRANSFER +30')
  exp = await flowRows(S1, 'EXPENSE')
  ok(exp.records.some(x => x.bizType === 'FACTORY_EXP_SETTLE_OTHER'
    && num(x.decreaseAmount) === 30 && x.receiptNo === dx1123.settleNo), 'EXPENSE FACTORY_EXP_SETTLE_OTHER -30')
  const e1123 = await glVoucherEntries('FACTORY_SETTLE_OTHER', dx1123.settleNo)
  const d1123 = entriesOf(e1123, '1123')
  ok(d1123.length === 1 && num(d1123[0].debitAmount) === 30 && d1123[0].auxSupplier === S1,
    '借1123 30 挂供应商辅助')
  ok(entriesOf(e1123, '1221')[0].auxSupplier === S1, '贷1221 挂供应商辅助')

  // 用预付核销把 30 占用掉，再反审核 DX → 守卫拦截
  const fx = await post('/finance/prepay-writeoff/create', {
    supplierCode: S1, supplierName: N1, handler, writeoffDate: TODAY, remark: 'M3占用转预付',
    details: [{ apNo: 'AP-M2-CS1', writeoffAmount: 30 }],
  })
  await post('/finance/prepay-writeoff/audit', { writeoffId: fx.writeoffId })
  ok((await prepayBalance()) === 0, 'FX 核销后预付余额=0（转预付已被占用）')
  await expectFail('/finance/factory-settle/cancel-audit', { settleId: dx1123.settleId },
    '请先反审核相关预付核销单或退款单')
  passed++; console.log('  ✅ 转预付已被 FX 占用时反审核 DX → 中文守卫拦截')

  // 先反 FX 再反 DX
  await post('/finance/prepay-writeoff/cancel-audit', { writeoffId: fx.writeoffId })
  ok((await prepayBalance()) === 30, 'FX 反审核后预付恢复 30')
  await post('/finance/factory-settle/cancel-audit', { settleId: dx1123.settleId })
  ok((await prepayBalance()) === 0, 'DX 反审核后预付恢复 0')
  pre = await flowRows(S1, 'PREPAY')
  ok(pre.records.some(x => x.bizType === 'PREPAY_REVERSE'
    && num(x.decreaseAmount) === 30 && x.receiptNo === dx1123.settleNo), '预付冲回 PREPAY_REVERSE -30')
  m = await apMap()
  ok(num(m['AP-M2-CS1'].unpaidAmount) === 120, 'FX 反审核后 AP-M2-CS1 恢复未付120')

  // ---------------- 8. OTHER：5711 坏账 / 560105 减免 ----------------
  section('8. OTHER：5711 坏账（原因必填、辅助跳过）/ 560105 减免')
  const dx5711 = await dxCreate({
    settleType: 'OTHER', contraSubjectCode: '5711', remark: 'M3坏账：客户倒闭确认无法收回',
    jfDetails: [{ factoryExpenseNo: jfD.factoryExpenseNo, settleAmount: 20 }],
  })
  await post('/finance/factory-settle/audit', { settleId: dx5711.settleId })
  const e5711 = await glVoucherEntries('FACTORY_SETTLE_OTHER', dx5711.settleNo)
  const d5711 = entriesOf(e5711, '5711')
  ok(d5711.length === 1 && num(d5711[0].debitAmount) === 20 && !d5711[0].auxSupplier,
    '借5711 20 且自动跳过供应商辅助核算')
  ok(entriesOf(e5711, '1221')[0].auxSupplier === S1, '贷1221 挂供应商辅助')
  await post('/finance/factory-settle/cancel-audit', { settleId: dx5711.settleId })
  passed++; console.log('  ✅ 5711 坏账反审核完成')

  // ---------------- 9. 导入（分组/FE 占用/部分失败） ----------------
  section('9. Excel 导入：按供应商+日期+性质分组 / FE 关联 / 部分失败')
  const imp = await post('/finance/factory-expense/import', {
    fileName: 'M3导入验证.xlsx',
    rows: [
      { supplierCode: S1, expenseTypeName: 'M3推广费', claimTypeText: '代垫', customerCode: 'C-M3-02',
        customerExpenseNo: 'FE-M3-4', expenseDate: TODAY, amount: 30, externalVoucherNo: 'HT-IMP-1', remark: '导入代垫' },
      { supplierCode: S1, expenseTypeName: 'M3仓储费', claimTypeText: '其他', customerCode: '',
        customerExpenseNo: '', expenseDate: TODAY, amount: 15, externalVoucherNo: '', remark: '导入其他' },
      // FE-M3-3 已被 JF-C 整单占用 → 失败行
      { supplierCode: S1, expenseTypeName: 'M3推广费', claimTypeText: '代垫', customerCode: 'C-M3-01',
        customerExpenseNo: 'FE-M3-3', expenseDate: TODAY, amount: 50, externalVoucherNo: '', remark: '应失败' },
    ],
  })
  ok(num(imp.success) === 2 && num(imp.failed) === 1 && num(imp.billCount) === 2,
    `导入：成功2/失败1/生成2张JF（实际 ${imp.success}/${imp.failed}/${imp.billCount}）`)
  const impBills = await jfPage({ sourceMode: 'IMPORT' })
  ok(num(impBills.records.length) === 2 && impBills.records.every(r => r.sourceMode === 'IMPORT'),
    '两张导入 JF sourceMode=IMPORT')
  const jfE = impBills.records.find(r => r.claimType === 'ADVANCE' && num(r.totalAmount) === 30)
  const jfImpOther = impBills.records.find(r => r.claimType === 'OTHER' && num(r.totalAmount) === 15)
  assert(jfE && jfImpOther, '导入分组：代垫30/其他15 各一张')
  const eImpDetail = await jfDetail(jfE.factoryExpenseId)
  ok(eImpDetail.details[0].customerExpenseNo === 'FE-M3-4'
    && !eImpDetail.details[0].customerExpenseDetailId
    && eImpDetail.details[0].glCreditSubject === '560105',
    '导入代垫行关联 FE-M3-4（只录单号）、贷方科目 560105')
  ok((await feCandidates()).every(r => r.expenseNo !== 'FE-M3-4'), '导入 PENDING 单已占用 FE-M3-4')

  // 审核导入代垫单 → 560105 减免兑现
  await post('/finance/factory-expense/audit', { factoryExpenseId: jfE.factoryExpenseId })
  const dx5601 = await dxCreate({
    settleType: 'OTHER', contraSubjectCode: '560105',
    jfDetails: [{ factoryExpenseNo: jfE.factoryExpenseNo, settleAmount: 30 }],
  })
  await post('/finance/factory-settle/audit', { settleId: dx5601.settleId })
  const e5601 = await glVoucherEntries('FACTORY_SETTLE_OTHER', dx5601.settleNo)
  const d5601 = entriesOf(e5601, '560105')
  ok(d5601.length === 1 && num(d5601[0].debitAmount) === 30 && !d5601[0].auxSupplier,
    '560105 减免：借560105 30 自动跳过供应商辅助')
  await post('/finance/factory-settle/cancel-audit', { settleId: dx5601.settleId })
  passed++; console.log('  ✅ 560105 减免兑现/反审核完成')

  // ---------------- 10. P0190 上线历史补录 ----------------
  section('10. P0190 历史补录开关：关时拒保存 / 开时立账不发 GL')
  const P0190 = 'fin.fexp.opening-backfill.enabled'
  ok((await get('/finance/factory-expense/backfill-flag')).enabled === false, 'backfill-flag 默认 false')
  await expectFail('/finance/factory-expense/create', {
    supplierCode: S1, expenseDate: TODAY, claimType: 'OTHER', handler,
    businessSource: 'OPENING_BACKFILL',
    details: [{ expenseTypeName: 'M3推广费', amount: 25 }],
  }, 'P0190')
  passed++; console.log('  ✅ 开关关闭时补录单保存 → 拦截')

  await setParam(P0190, 'Y')
  ok((await get('/finance/factory-expense/backfill-flag')).enabled === true, '参数更新后 backfill-flag=true（evict 生效）')
  const jfBack = await post('/finance/factory-expense/create', {
    supplierCode: S1, expenseDate: TODAY, claimType: 'OTHER', handler,
    businessSource: 'OPENING_BACKFILL', remark: 'M3上线历史补录',
    details: [{ expenseTypeName: 'M3推广费', amount: 25 }],
  })
  await post('/finance/factory-expense/audit', { factoryExpenseId: jfBack.factoryExpenseId })
  exp = await flowRows(S1, 'EXPENSE')
  ok(exp.records.some(x => x.factoryExpenseNo === jfBack.factoryExpenseNo
    && x.bizType === 'FACTORY_EXP_FORM' && num(x.increaseAmount) === 25),
    '补录单审核：费用账户照常形成流水 +25')
  const hasEvent = await glEventExists('FACTORY_EXPENSE', jfBack.factoryExpenseNo)
  ok(hasEvent === false, '补录单不发 GL 事件（避免与1221期初重复记账）')
  await setParam(P0190, 'N')
  ok((await get('/finance/factory-expense/backfill-flag')).enabled === false, '参数已归位 N')

  // ---------------- 11. 反审核→修改→再审核（第二轮流水） ----------------
  section('11. JF-B 反审核→改金额 80→70→再审核（#round2）')
  // FE-M3-2 在 JF-B 审核期间锁定
  ok((await feCandidates()).every(r => r.expenseNo !== 'FE-M3-2'), '反审核前 FE-M3-2 锁定')
  await post('/finance/factory-expense/cancel-audit', { factoryExpenseId: jfB.factoryExpenseId })
  // 反审核回 PENDING：明细行仍在 → 全局候选继续整单占用（仅删单才释放）；本单编辑视角可见
  const candMid = await feCandidates()
  ok(candMid.every(r => r.expenseNo !== 'FE-M3-2'), '反审核后 JF-B 待审核仍占用 FE-M3-2（不回全局候选）')
  const candSelf = await feCandidates(jfB.factoryExpenseNo)
  ok(candSelf.some(r => r.expenseNo === 'FE-M3-2'), '本单编辑视角（currentJfNo）FE-M3-2 仍可选')
  await post('/finance/factory-expense/update', {
    factoryExpenseId: jfB.factoryExpenseId, supplierCode: S1, expenseDate: TODAY,
    claimType: 'ADVANCE', handler, remark: 'M3-E2E 改单',
    details: [{ expenseTypeName: 'M3仓储费', customerCode: 'C-M3-02', customerName: '乙客户',
      customerExpenseNo: 'FE-M3-2', amount: 70 }],
  })
  await post('/finance/factory-expense/audit', { factoryExpenseId: jfB.factoryExpenseId })
  exp = await flowRows(S1, 'EXPENSE')
  const formsB = exp.records.filter(x => x.factoryExpenseNo === jfB.factoryExpenseNo
    && x.bizType === 'FACTORY_EXP_FORM')
  const activeFormsB = formsB.filter(x => x.reverseStatus !== 'REVERSED')
  ok(formsB.length === 2 && activeFormsB.length === 1
    && num(activeFormsB[0].increaseAmount) === 70,
    '同单号第二轮形成流水：共2轮 FORM，当前有效 +70')
  ok(exp.records.some(x => x.factoryExpenseNo === jfB.factoryExpenseNo
    && x.bizType === 'FACTORY_EXP_REVERSE' && num(x.increaseAmount) === -80),
    '第一轮反审核冲回行 -80')
  const bRow2 = (await jfPage({ factoryExpenseNo: jfB.factoryExpenseNo })).records[0]
  ok(num(bRow2.totalAmount) === 70 && num(bRow2.unsettledAmount) === 70,
    'JF-B 再审核后总额/未兑现=70')
  ok((await feCandidates()).every(r => r.expenseNo !== 'FE-M3-2'), '再审核后 FE-M3-2 重新锁定')

  // ---------------- 12. 分页过滤 / 候选 / 打印导出 / 鉴权 ----------------
  section('12. 分页过滤、候选、扣款通知单、鉴权')
  const approvedJf = await jfPage({ status: 'APPROVED' })
  // 已审：JF-A/B/C/D/E/补录 = 6
  ok(num(approvedJf.total) === 6 && approvedJf.records.every(r => r.status === 'APPROVED'),
    `JF 状态=已审核 全部命中（${approvedJf.total} 张）`)
  const redJf = await jfPage({ isRed: 'Y' })
  ok(num(redJf.total) === 1 && redJf.records[0].factoryExpenseNo === red.factoryExpenseNo
    && redJf.records[0].status === 'PENDING', '红字过滤命中 1 张（PENDING）')
  const otherJf = await jfPage({ claimType: 'OTHER' })
  ok(otherJf.records.length >= 3 && otherJf.records.every(r => r.claimType === 'OTHER'),
    `费用性质=其他 全部命中（${otherJf.records.length} 张）`)
  const linkedJf = await jfPage({ sourceMode: 'CUSTOMER_EXPENSE' })
  ok(linkedJf.records.length === 3 && linkedJf.records.every(r => r.sourceMode === 'CUSTOMER_EXPENSE'),
    '来源=客户费用单关联：JF-A/B/C 三张')
  const backfillJf = await jfPage({ businessSource: 'OPENING_BACKFILL' })
  ok(num(backfillJf.total) === 1 && backfillJf.records[0].factoryExpenseNo === jfBack.factoryExpenseNo,
    '业务来源=上线历史补录 命中补录单')
  const unclaimed = await jfPage({ settleStatus: '待兑现' })
  ok(unclaimed.records.length === 6 && unclaimed.records.every(r => r.settleStatus === '待兑现'),
    '兑现状态=待兑现：6 张已审 JF（DX 全部反审核后）')
  const dateQ = await jfPage({ dateFrom: TODAY, dateTo: TODAY })
  ok(dateQ.records.every(r => String(r.expenseDate).startsWith(TODAY)), 'JF 日期区间过滤命中')

  const pendingDx = await dxPage({ status: 'PENDING' })
  ok(num(pendingDx.total) === 6 && pendingDx.records.every(r => r.status === 'PENDING'),
    `DX 状态=待审核 6 张（CASH/O1/O2/1123/5711/5601 全部反审核）（实际 ${pendingDx.total}）`)
  const cashDx = await dxPage({ settleType: 'CASH' })
  ok(num(cashDx.total) === 1 && cashDx.records[0].settleNo === dxCash.settleNo, 'DX 方式=现金结算 命中')
  const offDx = await dxPage({ settleType: 'OFFSET' })
  ok(num(offDx.total) === 2 && offDx.records.every(r => r.settleType === 'OFFSET'), 'DX 方式=冲应付 命中2张')
  const otherDx = await dxPage({ settleType: 'OTHER' })
  ok(num(otherDx.total) === 3, 'DX 方式=其他核销 命中3张')
  const dxBySupp = await dxPage({ supplier: S2 })
  ok(num(dxBySupp.total) === 0, 'DX 按 S003 过滤无单据')

  // 候选：JF 6 张全部未全兑现（红字排除）；AP 候选排除负 AP 与他供应商 AP
  const jfCand = await post('/finance/factory-settle/jf-candidates', { supplierCode: S1 })
  ok(jfCand.length === 6 && jfCand.every(r => num(r.unsettledAmount) > 0)
    && jfCand.map(r => r.factoryExpenseNo).includes(jfA.factoryExpenseNo),
    `JF 候选 6 张（含改单后 JF-B 70），红字单不在候选（实际 ${jfCand.length}）`)
  const apCand = await post('/finance/factory-settle/ap-candidates', { supplierCode: S1 })
  ok(apCand.length === 8 && !apCand.some(r => ['AP-TEST-2', 'AP-M2-X1'].includes(r.apNo)),
    `AP 候选 8 行：排除负数 AP-TEST-2 与 S003 的 AP-M2-X1（实际 ${apCand.length}）`)

  // print-data 三方式冒烟
  for (const [label, id] of [['CASH', dxCash.settleId], ['OFFSET', dxO1.settleId], ['OTHER', dx5711.settleId]]) {
    const pd = await post('/finance/factory-settle/print-data', { settleId: id })
    ok(!!pd.settleNo && Array.isArray(pd.jfDetails) && pd.jfDetails.length === 1,
      `print-data ${label} 返回主单+JF明细`)
  }
  // export-notice 二进制
  const expRes = await fetch(BASE + '/finance/factory-settle/export-notice', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + await freshToken() },
    body: JSON.stringify({ settleId: dxCash.settleId }),
  })
  const buf = await expRes.arrayBuffer()
  ok(expRes.ok && buf.byteLength > 1000
    && (expRes.headers.get('content-disposition') || '').includes('%E6%89%A3%E6%AC%BE%E9%80%9A%E7%9F%A5%E5%8D%95'),
    'export-notice 下载扣款通知单 xlsx（' + buf.byteLength + ' 字节）')

  // 无令牌
  const noAuthJf = await fetch(BASE + '/finance/factory-expense/page', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }).then(r => r.json())
  ok(noAuthJf.code !== '0', '无令牌 JF 分页被拒绝（code=' + noAuthJf.code + '）')
  const noAuthDx = await fetch(BASE + '/finance/factory-settle/page', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }).then(r => r.json())
  ok(noAuthDx.code !== '0', '无令牌 DX 分页被拒绝（code=' + noAuthDx.code + '）')

  // ---------------- 13. 期末账户一致性 ----------------
  section('13. 期末账户勾稽')
  // AP：所有 OFFSET 已反审核 → 回到基线 1930
  const apEnd = (await flowRows(S1, 'AP')).currentBalance
  ok(Math.abs(apEnd - 1930) < 0.001, `期末应付余额=1930（实际 ${apEnd}）`)
  ok((await prepayBalance()) === 0, '期末预付余额=0')
  // EXPENSE：A100 + B70 + C30 + D20 + E30 + 补录25 = 275
  const expEnd = (await flowRows(S1, 'EXPENSE')).currentBalance
  ok(Math.abs(expEnd - 275) < 0.001, `期末费用余额=275（A100+B70+C30+D20+E30+补录25，实际 ${expEnd}）`)
  const acctPage = await post('/finance/supplier-account/page', { pageNo: 1, pageSize: 200, keyword: S1 })
  const acct = acctPage.records.find(r => r.supplierCode === S1)
  ok(!!acct && Math.abs(num(acct.expenseBalance) - 275) < 0.001
    && Math.abs(num(acct.apBalance) - 1930) < 0.001 && num(acct.prepayBalance) === 0,
    `供应商账户总览三余额：费用275/应付1930/预付0（实际 ${acct && acct.expenseBalance}/${acct && acct.apBalance}/${acct && acct.prepayBalance}）`)
  ok((await fundArchive(CASH)) === cash0 && (await fundArchive(BANK)) === bank0,
    '资金档案余额恢复基线（CASH 反审核净额为 0）')
  m = await apMap()
  ok(num(m['AP-M2-10'].unpaidAmount) === 100 && num(m['AP-M2-CS1'].unpaidAmount) === 120,
    'AP-M2-10/CS1 未付均恢复（100/120）')
  const recEnd = await reconRows()
  ok(!recEnd.some(r => r.businessType === 'FACTORY_EXPENSE_OFFSET'),
    '期末无残留 FACTORY_EXPENSE_OFFSET 核销真值')

  console.log(`\n🎉 PRD-36 M3 全部通过：${passed} 项断言`)
}

main().catch(e => {
  console.error('\n❌ 验收失败：' + (e && e.message))
  console.error(e && e.stack)
  process.exit(1)
})
