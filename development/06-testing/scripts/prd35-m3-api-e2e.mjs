/**
 * PRD-35 M3 端到端 API 验收：预收核销单（XH）+ 应收结算/对账单结算使用预收。
 *
 * 专用隔离库运行（勿连用户库 8080）：
 *   后端：java -jar backend/target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8082 \
 *     "--spring.datasource.url=jdbc:h2:file:./data/verify-m3;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"
 *   夹具：先经 API 建客户 KTEST1/KTEST2，再执行 prd35-m3-fixtures.sql
 *   跑测：API_BASE=http://localhost:8082/api node development/06-testing/scripts/prd35-m3-api-e2e.mjs
 *
 * 覆盖：
 *  A. 数据修复建账户缓存；ADV 预收收款单审核 → 预收余额 500
 *  B. 手工 XH：建单四类校验拦截 / 审核 / 余额回写 / 流水 / 发货状态 / 反审核恢复 / 删除
 *  C. 应收结算：跨客户预收守卫、金额守卫、余额不足守卫；FIFO（预收冲最早到期）；
 *     自动 XH 禁止单独反审核；收款单反审核级联冲回；全额预收 0 现金也生 SK 单
 *  D. 对账单结算：两张对账单合并结算 + 抹零 + 预收；自动 XH 按对账单归属；反审核级联
 *  E. 总账：启用后审核 XH → 事件池 → 生成草稿凭证（借2203/贷1122，客户辅助）
 *  F. XH 分页过滤 + 无令牌 401
 */
import cm from './init-common.js'

const { post, login, expectFail, assert, num, todayStr, plusDays } = cm
const BASE = cm.BASE

const C1 = 'KTEST1'
const N1 = '预收核销测试客户甲'
const C2 = 'KTEST2'
const N2 = '预收核销测试客户乙'
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

async function arAll() {
  const p = await post('/finance/ar/page', { pageNo: 1, pageSize: 200, filters: {} })
  return (p.records || []).filter(r => String(r.arNo || '').startsWith('AR-M3-'))
}
async function arMap() {
  const m = {}
  for (const r of await arAll()) m[r.arNo] = r
  return m
}
async function advanceBalance(code) {
  const d = await post('/finance/customer-account/advance-balance', { customerCode: code })
  return num(d.advanceBalance)
}
async function flowRows(code, accountType) {
  const p = await post('/finance/customer-account/flow/page', {
    customerCode: code, accountType, pageNo: 1, pageSize: 200,
  })
  return { records: p.records || [], currentBalance: num(p.currentBalance) }
}
async function reconAll() {
  const p = await post('/finance/reconcile-record/page', {
    pageNo: 1, pageSize: 200, filters: { counterparty: '预收核销测试客户' },
  })
  return p.records || []
}
async function salesRows() {
  const p = await post('/sales/receipt/page', { pageNo: 1, pageSize: 200, filters: {} })
  return (p.records || []).filter(r => String(r.receiptNo || '').startsWith('SR-M3-'))
}
async function salesMap() {
  const m = {}
  for (const r of await salesRows()) m[r.receiptNo] = r
  return m
}
async function xhPage(q = {}) {
  return post('/finance/advance-writeoff/page', { pageNo: 1, pageSize: 200, ...q })
}
async function latestReceipt(businessSource) {
  const p = await post('/finance/receipt/page', {
    pageNo: 1, pageSize: 20,
    filters: { businessSource, counterparty: '预收核销测试客户甲' },
  })
  const recs = (p.records || []).filter(r => r.status === 'APPROVED')
  return recs[0] || null
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
  // 空验证库可能无员工档案：经手人只校验非空，回退用管理员显示名
  const handler = (emps.records && emps.records[0] && emps.records[0].employeeName) || '系统管理员'
  let fees = await post('/base/master/expense-type/page', { pageNo: 1, pageSize: 50 })
  if (!(fees.records || []).length) {
    // 空验证库无费用类型：幂等自建一个抹零费用类型
    try {
      await post('/base/master/save', {
        moduleCode: 'expenseType', mode: 'add',
        expenseTypeCode: 'FYM3', expenseTypeName: 'M3抹零费用',
        direction: 'OUT', costParticipation: '否', status: 'NORMAL',
      })
    } catch (e) {
      if (!String(e.message).includes('已存在')) throw e
    }
    fees = await post('/base/master/expense-type/page', { pageNo: 1, pageSize: 50 })
  }
  assert(fees.records[0], '费用类型至少存在 1 个')
  const feeCode = fees.records[0].expenseTypeCode
  console.log(`档案：资金账户「${fundName}」经手人「${handler}」费用类型「${feeCode}」`)

  // 幂等确保客户存在
  for (const [code, name] of [[C1, N1], [C2, N2]]) {
    try {
      await post('/base/customer/create', {
        customerCode: code, customerName: name,
        channelType: '零售商超', accountPeriodType: '月结30天',
      })
    } catch (e) {
      if (!String(e.message).includes('已存在') && !String(e.message).includes('重复')) throw e
    }
  }

  // 夹具必须已灌入（AR 行只能 SQL 构造）
  const ar0 = await arMap()
  if (!ar0['AR-M3-10'] || !ar0['AR-M3-CS1']) {
    throw new Error('缺少 AR-M3-* 夹具，请先执行 development/06-testing/scripts/prd35-m3-fixtures.sql')
  }
  console.log('夹具就位：AR-M3-* 共 ' + Object.keys(ar0).length + ' 行')

  // 账户缓存修复（夹具为直插 SQL，无流水；修复从真值补账户与形成流水）
  await post('/finance/customer-account/repair', { customerCode: C1 })
  await post('/finance/customer-account/repair', { customerCode: C2 })
  const arFlow0 = await flowRows(C1, 'AR')
  // 甲客户夹具 AR 合计 100+200+300+50+120+60=830
  ok(Math.abs(arFlow0.currentBalance - 830) < 0.001, `修复后甲客户应收余额=830（实际 ${arFlow0.currentBalance}）`)
  ok(Math.abs((await advanceBalance(C1))) < 0.001, '修复后甲客户预收余额=0')

  // ---------------- A. 预收收款 500 ----------------
  section('A. 预收收款单审核 → 预收余额')
  const adv = await post('/finance/receipt/create', {
    receiptDate: TODAY, counterpartyType: 'CUSTOMER',
    counterpartyCode: C1, counterpartyName: N1, handler,
    summary: 'M3验证预收', receiptType: 'ADVANCE',
    details: [{ fundAccount: fundName, amount: 500, remark: '' }],
  })
  await post('/finance/receipt/audit', { receiptId: adv.receiptId })
  ok((await advanceBalance(C1)) === 500, 'ADV 收款单审核后预收余额=500')
  const advFlow0 = await flowRows(C1, 'ADVANCE')
  ok(advFlow0.records.some(r => r.bizType === 'ADV_RECEIPT' && num(r.increaseAmount) === 500
    && r.receiptNo === adv.receiptNo), '预收账户存在 ADV_RECEIPT +500 流水')

  // ---------------- B. 手工 XH ----------------
  section('B. 手工预收核销单：校验/审核/反审核/删除')
  const mkXh = (details, extra = {}) => post('/finance/advance-writeoff/create', {
    customerCode: C1, customerName: N1, handler, writeoffDate: TODAY,
    remark: 'M3验证', details, ...extra,
  })

  // B1 建单校验
  await expectFail('/finance/advance-writeoff/create', {
    customerCode: C1, customerName: N1, handler, writeoffDate: TODAY,
    details: [{ arNo: 'AR-M3-10', writeoffAmount: 120 }],
  }, '超出未结金额')
  passed++; console.log('  ✅ 核销额超过未收 → 拦截')
  await expectFail('/finance/advance-writeoff/create', {
    customerCode: C2, customerName: N2, handler, writeoffDate: TODAY,
    details: [{ arNo: 'AR-M3-10', writeoffAmount: 10 }],
  }, '不属于')
  passed++; console.log('  ✅ 核销售货应收不属于该客户 → 拦截')
  await expectFail('/finance/advance-writeoff/create', {
    customerCode: C1, customerName: N1, handler, writeoffDate: TODAY,
    details: [
      { arNo: 'AR-M3-10', writeoffAmount: 10 },
      { arNo: 'AR-M3-10', writeoffAmount: 5 },
    ],
  }, '重复')
  passed++; console.log('  ✅ 同应收单重复行 → 拦截')
  await expectFail('/finance/advance-writeoff/create', {
    customerCode: C1, customerName: N1, handler, writeoffDate: TODAY,
    details: [
      { arNo: 'AR-M3-10', writeoffAmount: 100 },
      { arNo: 'AR-M3-20', writeoffAmount: 200 },
      { arNo: 'AR-M3-30', writeoffAmount: 300 },
    ],
  }, '预收余额不足')
  passed++; console.log('  ✅ 核销合计 600 > 预收 500 → 拦截')

  // B2 建单 30 → 审核 → 真值/余额/流水/发货状态
  const xa = await mkXh([{ arNo: 'AR-M3-10', writeoffAmount: 30 }])
  ok(/^XH/.test(xa.writeoffNo), 'XH 单号前缀正确：' + xa.writeoffNo)
  const pendingPage = await xhPage({ status: 'PENDING' })
  ok(pendingPage.records.some(r => r.writeoffNo === xa.writeoffNo && r.statusText === '待审核'),
    '分页可见待审核 XH 单')
  await post('/finance/advance-writeoff/audit', { writeoffId: xa.writeoffId })
  ok((await advanceBalance(C1)) === 470, '审核后预收余额 500→470')
  let m = await arMap()
  ok(num(m['AR-M3-10'].receivedAmount) === 30 && num(m['AR-M3-10'].unreceivedAmount) === 70
    && m['AR-M3-10'].status === '未核销', 'AR-M3-10 已收30/未收70/未核销')
  const arFlowA = await flowRows(C1, 'AR')
  ok(Math.abs(arFlowA.currentBalance - 800) < 0.001, `应收余额 830→800（实际 ${arFlowA.currentBalance}）`)
  ok(arFlowA.records.some(r => r.bizType === 'AR_SETTLE_ADVANCE' && num(r.decreaseAmount) === 30
    && r.writeoffNo === xa.writeoffNo && r.arNo === 'AR-M3-10'), '应收账户 AR_SETTLE_ADVANCE -30 流水')
  const advFlowA = await flowRows(C1, 'ADVANCE')
  ok(advFlowA.records.some(r => r.bizType === 'ADV_WRITE_OFF' && num(r.decreaseAmount) === 30
    && r.writeoffNo === xa.writeoffNo), '预收账户 ADV_WRITE_OFF -30 流水')
  let sr = await salesMap()
  ok(sr['SR-M3-10'].receiveStatus === '部分收款', '发货单 SR-M3-10 收款状态→部分收款')
  let recs = await reconAll()
  ok(recs.some(r => r.receiptNo === xa.writeoffNo && r.businessType === 'ADVANCE_WRITE_OFF'
    && r.businessNo === 'AR-M3-10' && num(r.reconcileAmount) === 30), '核销真值 ADVANCE_WRITE_OFF 30 落表')
  const xd = await post('/finance/advance-writeoff/detail', { writeoffId: xa.writeoffId })
  ok(xd.status === 'APPROVED' && num(xd.totalAmount) === 30
    && xd.details[0].settleStatusAfter === '部分结算'
    && num(xd.details[0].unsettledBefore) === 100, 'XH 详情快照：核销前未收100/核销后部分结算')

  // B3 反审核 → 全部恢复
  await post('/finance/advance-writeoff/cancel-audit', { writeoffId: xa.writeoffId })
  ok((await advanceBalance(C1)) === 500, '反审核后预收余额恢复=500')
  m = await arMap()
  ok(num(m['AR-M3-10'].receivedAmount) === 0 && num(m['AR-M3-10'].unreceivedAmount) === 100,
    '反审核后 AR-M3-10 已收0/未收100')
  const arFlowB = await flowRows(C1, 'AR')
  ok(Math.abs(arFlowB.currentBalance - 830) < 0.001, '反审核后应收余额恢复=830')
  sr = await salesMap()
  ok(sr['SR-M3-10'].receiveStatus === '未收款', '发货单 SR-M3-10 收款状态恢复未收款')
  recs = await reconAll()
  ok(!recs.some(r => r.receiptNo === xa.writeoffNo), '反审核后核销真值记录删除')
  const advFlowB = await flowRows(C1, 'ADVANCE')
  ok(advFlowB.records.some(r => r.writeoffNo === xa.writeoffNo
    && (r.isRed === 'Y' || num(r.isRed) === 1 || r.isRed === true)),
  '预收流水存在红字冲回行（ADV_REVERSE isRed=Y）')
  const xd2 = await post('/finance/advance-writeoff/detail', { writeoffId: xa.writeoffId })
  ok(xd2.status === 'PENDING' && !xd2.details[0].settleStatusAfter, 'XH 回待审核且明细结算标志清空')

  // B4 删除待审核单
  await post('/finance/advance-writeoff/delete', { writeoffId: xa.writeoffId })
  const pgAfterDel = await xhPage({ writeoffNo: xa.writeoffNo })
  ok(pgAfterDel.total === 0, '待审核 XH 单可删除且列表不可见')

  // ---------------- C. 应收结算使用预收 ----------------
  section('C. 应收结算：守卫 + FIFO + 级联反审核')
  const settle = (arList, useAdvance, accounts, extra = {}) => post('/finance/ar/settle', {
    receiptDate: TODAY, handler, summary: 'M3结算验证',
    arList, useAdvanceAmount: useAdvance, accounts, ...extra,
  })

  // C1 守卫
  await expectFail('/finance/ar/settle', {
    receiptDate: TODAY, handler,
    arList: [{ arNo: 'AR-M3-10', settleAmount: 10 }, { arNo: 'AR-M3-50', settleAmount: 10 }],
    useAdvanceAmount: 5, accounts: [{ fundAccount: fundName, amount: 15 }],
  }, '跨客户')
  passed++; console.log('  ✅ 跨客户结算使用预收 → 拦截')
  await expectFail('/finance/ar/settle', {
    receiptDate: TODAY, handler,
    arList: [{ arNo: 'AR-M3-10', settleAmount: 100 }],
    useAdvanceAmount: 30, accounts: [{ fundAccount: fundName, amount: 80 }],
  }, '资金账户合计')
  passed++; console.log('  ✅ 现金账户合计≠净额-预收 → 拦截')
  await expectFail('/finance/ar/settle', {
    receiptDate: TODAY, handler,
    arList: [{ arNo: 'AR-M3-10', settleAmount: 100 }],
    useAdvanceAmount: 120, accounts: [],
  }, '不能超过本次结算净额')
  passed++; console.log('  ✅ 预收>结算净额 → 拦截')
  await expectFail('/finance/ar/settle', {
    receiptDate: TODAY, handler,
    arList: [
      { arNo: 'AR-M3-10', settleAmount: 100 },
      { arNo: 'AR-M3-20', settleAmount: 200 },
      { arNo: 'AR-M3-30', settleAmount: 300 },
      { arNo: 'AR-M3-40', settleAmount: 50 },
    ],
    useAdvanceAmount: 600, accounts: [{ fundAccount: fundName, amount: 50 }],
  }, '预收余额不足')
  passed++; console.log('  ✅ 预收 600 > 余额 500（自动 XH 审核硬校验整单回滚）→ 拦截')
  m = await arMap()
  ok(num(m['AR-M3-10'].receivedAmount) === 0, '余额不足回滚后 AR-M3-10 未收仍为 100')

  // C2 FIFO：100/200/300，预收 250 冲最早到期（100 + 150），现金接续（50 + 300）
  const r1 = await settle([
    { arNo: 'AR-M3-10', settleAmount: 100 },
    { arNo: 'AR-M3-20', settleAmount: 200 },
    { arNo: 'AR-M3-30', settleAmount: 300 },
  ], 250, [{ fundAccount: fundName, amount: 350 }])
  ok(r1.created === 1 && r1.receiptAmount === 350 && r1.advanceAmount === 250
    && r1.writeoffNos.length === 1, `结算响应：现金350/预收250/自动XH 1张（${r1.writeoffNos.join(',')}）`)
  const xh1No = r1.writeoffNos[0]
  m = await arMap()
  ok(num(m['AR-M3-10'].receivedAmount) === 100 && m['AR-M3-10'].status === '已核销', 'FIFO AR-M3-10 全额预收核销 100')
  ok(num(m['AR-M3-20'].receivedAmount) === 200 && m['AR-M3-20'].status === '已核销', 'FIFO AR-M3-20 预收150+现金50=200')
  ok(num(m['AR-M3-30'].receivedAmount) === 300 && m['AR-M3-30'].status === '已核销', 'FIFO AR-M3-30 现金300')
  ok((await advanceBalance(C1)) === 250, '预收余额 500→250')
  let arFlowC = await flowRows(C1, 'AR')
  ok(Math.abs(arFlowC.currentBalance - 230) < 0.001, `应收余额 830→230（实际 ${arFlowC.currentBalance}）`)
  // 注：XH 按 MAX 取号，手工删除待审核单后号会被自动单复用；靠 reverseStatus 排除上一轮已冲回行
  ok(arFlowC.records.filter(r => r.bizType === 'AR_SETTLE_ADVANCE' && r.writeoffNo === xh1No
    && r.reverseStatus !== 'REVERSED')
    .reduce((s, r) => s + num(r.decreaseAmount), 0) === 250, '应收预收结算流水合计 250（本轮）')
  ok(arFlowC.records.filter(r => r.bizType === 'AR_SETTLE_CASH' && r.receiptNo && r.receiptNo !== xh1No
    && num(r.decreaseAmount) > 0).reduce((s, r) => s + num(r.decreaseAmount), 0) >= 350, '应收现金结算流水合计 350')
  const advFlowC = await flowRows(C1, 'ADVANCE')
  ok(advFlowC.records.some(r => r.writeoffNo === xh1No && r.bizType === 'ADV_WRITE_OFF'
    && num(r.decreaseAmount) === 250), '预收核销流水 -250')
  sr = await salesMap()
  ok(sr['SR-M3-10'].receiveStatus === '已收款' && sr['SR-M3-20'].receiveStatus === '已收款'
    && sr['SR-M3-30'].receiveStatus === '已收款', '三张发货单收款状态均→已收款')
  let sk1 = await latestReceipt('AR_SETTLE')
  ok(!!sk1 && num(sk1.totalAmount) === 350 && sk1.businessSource === 'AR_SETTLE',
    `结算收款单 ${sk1 && sk1.receiptNo} 已审核金额350（AR_SETTLE 生单）`)
  recs = await reconAll()
  const cashByNo = {}
  for (const r of recs.filter(r => r.receiptNo === sk1.receiptNo && r.businessType === 'AR_SETTLE'))
    cashByNo[r.businessNo] = num(r.reconcileAmount)
  ok(cashByNo['AR-M3-20'] === 50 && cashByNo['AR-M3-30'] === 300 && cashByNo['AR-M3-10'] === undefined,
    '现金核销真值：AR-20=50、AR-30=300、AR-10 无现金记录')
  const xh1Recs = {}
  for (const r of recs.filter(r => r.receiptNo === xh1No && r.businessType === 'ADVANCE_WRITE_OFF'))
    xh1Recs[r.businessNo] = num(r.reconcileAmount)
  ok(xh1Recs['AR-M3-10'] === 100 && xh1Recs['AR-M3-20'] === 150,
    '预收核销真值：AR-10=100、AR-20=150')
  const xh1Page = await xhPage({ businessSource: 'AR_SETTLE' })
  const xh1Row = xh1Page.records.find(r => r.writeoffNo === xh1No)
  ok(!!xh1Row && xh1Row.status === 'APPROVED' && xh1Row.sourceBillNo === sk1.receiptNo,
    '自动 XH 单来源=AR_SETTLE，来源单号=SK 单号')

  // 自动单禁止单独反审核
  await expectFail('/finance/advance-writeoff/cancel-audit', { writeoffId: xh1Row.writeoffId }, '不能单独反审核')
  passed++; console.log('  ✅ 结算自动 XH 禁止单独反审核')

  // C3 收款单反审核 → 级联冲回
  await post('/finance/receipt/cancel-audit', { receiptId: sk1.receiptId })
  ok((await advanceBalance(C1)) === 500, '级联冲回后预收余额恢复=500')
  m = await arMap()
  for (const no of ['AR-M3-10', 'AR-M3-20', 'AR-M3-30']) {
    ok(num(m[no].receivedAmount) === 0 && num(m[no].unreceivedAmount) === num(m[no].arAmount)
      && m[no].status === '未核销', `${no} 反审核后全额恢复未收`)
  }
  arFlowC = await flowRows(C1, 'AR')
  ok(Math.abs(arFlowC.currentBalance - 830) < 0.001, '应收余额恢复=830')
  sr = await salesMap()
  ok(sr['SR-M3-10'].receiveStatus === '未收款' && sr['SR-M3-20'].receiveStatus === '未收款'
    && sr['SR-M3-30'].receiveStatus === '未收款', '发货单收款状态恢复未收款')
  recs = await reconAll()
  ok(!recs.some(r => r.receiptNo === xh1No || r.receiptNo === sk1.receiptNo),
    '反审核后 XH 与现金核销真值记录全部删除')
  const xh1After = (await xhPage({ writeoffNo: xh1No })).records[0]
  ok(xh1After.status === 'CANCELLED', '自动 XH 单级联置为已作废')
  const sk1After = (await post('/finance/receipt/page', {
    pageNo: 1, pageSize: 20, filters: { receiptNo: sk1.receiptNo },
  })).records[0]
  // 收款单反审核按系统既有约定回「待审核」（可改/再审/删除），不做作废；级联的自动 XH 才置 CANCELLED
  ok(sk1After.status === 'PENDING', `收款单反审核后回待审核（实际 ${sk1After.status}）`)

  // C4 全额预收、0 现金也必须生成 SK 单并级联可回
  const r2 = await settle([{ arNo: 'AR-M3-40', settleAmount: 50 }], 50, [])
  ok(r2.created === 1 && r2.receiptAmount === 0 && r2.advanceAmount === 50
    && r2.writeoffNos.length === 1, '全额预收：现金0也结算成功并生自动 XH')
  let sk2 = await latestReceipt('AR_SETTLE')
  ok(!!sk2 && num(sk2.totalAmount) === 0, `0 元结算收款单 ${sk2 && sk2.receiptNo} 已生成`)
  m = await arMap()
  ok(num(m['AR-M3-40'].receivedAmount) === 50 && m['AR-M3-40'].status === '已核销', 'AR-M3-40 全额预收已核销')
  ok((await advanceBalance(C1)) === 450, '预收余额→450')
  await post('/finance/receipt/cancel-audit', { receiptId: sk2.receiptId })
  ok((await advanceBalance(C1)) === 500, '0元单反审核级联后预收恢复=500')
  m = await arMap()
  ok(num(m['AR-M3-40'].receivedAmount) === 0 && m['AR-M3-40'].status === '未核销', 'AR-M3-40 反审核后恢复')

  // ---------------- D. 对账单结算使用预收 ----------------
  section('D. 对账单结算：两张对账单 + 抹零 + 预收')
  const mkStmt = async (billNo, amount) => {
    const s = await post('/finance/customer-statement/create', {
      customerCode: C1, customerName: N1, salesman: handler,
      statementDate: TODAY, expectedPayDate: plusDays(5), remark: 'M3对账单',
      details: [{
        sourceBillNo: billNo, sourceBillType: '销售发货',
        billAmount: amount, reconcileAmount: amount, unpaidAmount: amount, billRemark: '',
      }],
    })
    await post('/finance/customer-statement/audit', { statementId: s.statementId })
    return s
  }
  const st1 = await mkStmt('SR-M3-CS1', 120)
  const st2 = await mkStmt('SR-M3-CS2', 60)
  const cs = await post('/finance/customer-statement/settle', {
    statementIds: [st1.statementId, st2.statementId],
    handler, settleDate: TODAY, remark: 'M3对账结算验证',
    writeOff: 2, writeOffExpenseType: feeCode,
    accounts: [{ fundAccount: fundName, amount: 100 }],
    useAdvanceAmount: 78,
  })
  ok(num(cs.settleAmount) === 100 && num(cs.advanceAmount) === 78
    && cs.writeoffNos.length === 1, `对账结算：现金100/预收78/自动XH 1张（${cs.writeoffNos.join(',')}）`)
  const xhCsNo = cs.writeoffNos[0]
  m = await arMap()
  // csSettle 按对账明细全额分摊（抹零不扣减 AR 计划额）：预收 FIFO 先归单1(78)，
  // 现金部分=应收额-预收：CS1=42、CS2=60；抹零 2 通过单1 落 EXPENSE_WRITEOFF，
  // 故现金核销真值合计 102 比收款单 100 多 2（抹零份额），两张 AR 都全额结清，此为既有设计
  ok(num(m['AR-M3-CS1'].receivedAmount) === 120 && m['AR-M3-CS1'].status === '已核销'
    && num(m['AR-M3-CS2'].receivedAmount) === 60 && m['AR-M3-CS2'].status === '已核销',
    '两张对账单对应 AR 全额核销（现金+预收+抹零份额）')
  ok((await advanceBalance(C1)) === 422, '预收余额 500→422')
  const arFlowD = await flowRows(C1, 'AR')
  ok(Math.abs(arFlowD.currentBalance - 650) < 0.001, `应收余额 830→650（实际 ${arFlowD.currentBalance}）`)
  const xhCs = (await xhPage({ writeoffNo: xhCsNo })).records[0]
  ok(xhCs.businessSource === 'STATEMENT_SETTLE' && xhCs.sourceBillNo === st1.statementNo
    && num(xhCs.totalAmount) === 78, `对账自动 XH 来源对账单 ${st1.statementNo}，金额78`)
  const xhCsDetail = await post('/finance/advance-writeoff/detail', { writeoffId: xhCs.writeoffId })
  ok(xhCsDetail.details.length === 1 && xhCsDetail.details[0].arNo === 'AR-M3-CS1'
    && num(xhCsDetail.details[0].writeoffAmount) === 78, '对账 XH 行：预收78 全归最早对账单 AR-M3-CS1')
  const d1 = await post('/finance/customer-statement/detail', { statementId: st1.statementId })
  const d2 = await post('/finance/customer-statement/detail', { statementId: st2.statementId })
  ok(d1.payStatus === '完成收款' && num(d1.paidAmount) === 122 && num(d1.writeOffAmount) === 2,
    `对账单1：完成收款 已收122(含预收78+现金42) 抹零2（实际 ${d1.paidAmount}/${d1.writeOffAmount}）`)
  ok(d2.payStatus === '完成收款' && num(d2.paidAmount) === 60 && num(d2.writeOffAmount) === 0,
    '对账单2：完成收款 已收60 无抹零')
  recs = await reconAll()
  ok(recs.some(r => r.businessType === 'EXPENSE_WRITEOFF' && r.businessNo === st1.statementNo
    && num(r.reconcileAmount) === 2), '抹零真值 EXPENSE_WRITEOFF 2 挂对账单1')
  ok(recs.some(r => r.receiptNo === cs.receiptNo && r.businessType === 'CUSTOMER_STATEMENT'
    && r.businessNo === 'AR-M3-CS2'), '现金核销真值（对账单2 AR-M3-CS2）存在')
  const skCs = (await post('/finance/receipt/page', {
    pageNo: 1, pageSize: 20, filters: { receiptNo: cs.receiptNo },
  })).records[0]
  ok(skCs.status === 'APPROVED' && skCs.businessSource === 'CUSTOMER_STATEMENT'
    && skCs.relatedBillNo.includes(st1.statementNo) && skCs.relatedBillNo.includes(st2.statementNo),
    '对账结算收款单已审核且关联两张对账单')

  // 反审核 → 级联冲回
  await post('/finance/receipt/cancel-audit', { receiptId: skCs.receiptId })
  ok((await advanceBalance(C1)) === 500, '对账结算级联冲回后预收恢复=500')
  m = await arMap()
  ok(num(m['AR-M3-CS1'].receivedAmount) === 0 && num(m['AR-M3-CS2'].receivedAmount) === 0
    && m['AR-M3-CS1'].status === '未核销' && m['AR-M3-CS2'].status === '未核销',
    '两张 AR 反审核后恢复未收')
  const d1b = await post('/finance/customer-statement/detail', { statementId: st1.statementId })
  const d2b = await post('/finance/customer-statement/detail', { statementId: st2.statementId })
  ok(d1b.payStatus === '未收款' && num(d1b.paidAmount) === 0 && num(d1b.writeOffAmount) === 0
    && d2b.payStatus === '未收款' && num(d2b.paidAmount) === 0,
    '对账单已收/抹零清零回未收款')
  recs = await reconAll()
  ok(!recs.some(r => r.businessNo === st1.statementNo && r.businessType === 'EXPENSE_WRITEOFF'),
    '抹零真值随反审核删除')
  ok(!recs.some(r => r.receiptNo === cs.receiptNo), '对账结算核销真值随反审核删除')
  ok((await xhPage({ writeoffNo: xhCsNo })).records[0].status === 'CANCELLED', '对账自动 XH 级联已作废')

  // ---------------- E. 总账凭证 ----------------
  section('E. 总账：启用 → XH 事件 → 草稿凭证 借2203/贷1122')
  await post('/finance/gl/init/enable', { startPeriod: '202609' })
  passed++; console.log('  ✅ 空库试算平衡，总账启用成功（202609 进行中）')
  const xgl = await mkXh([{ arNo: 'AR-M3-10', writeoffAmount: 30 }])
  await post('/finance/advance-writeoff/audit', { writeoffId: xgl.writeoffId })
  ok((await advanceBalance(C1)) === 470, 'GL 验证 XH 审核后预收=470')
  // AFTER_COMMIT 事件，给事务提交留一点余量
  await new Promise(res => setTimeout(res, 500))
  const ev = await post('/finance/gl/event/page', {
    pageNo: 1, pageSize: 50, eventCode: 'ADVANCE_WRITE_OFF',
  })
  const event = (ev.records || []).find(r => JSON.stringify(r).includes(xgl.writeoffNo))
  ok(!!event, '事件池存在 ADVANCE_WRITE_OFF 事件：' + xgl.writeoffNo)
  await post('/finance/gl/event/generate', { ids: [event.id] })
  passed++; console.log('  ✅ 事件生成凭证成功')
  // 事件生成的凭证 source_bill_type 存中文单据名「预收核销单」，联查按单号即可（与 gl-m4 既有用法一致）
  const vouchers = await post('/finance/gl/voucher/by-bill', { billNo: xgl.writeoffNo })
  ok(Array.isArray(vouchers) && vouchers.length >= 1
    && vouchers.some(v => v.sourceBillType === '预收核销单'), '单据联查查到预收核销凭证')
  const voucher = await post('/finance/gl/voucher/detail', { id: vouchers[0].id })
  const entries = voucher.entries || voucher.lines || []
  const dump = JSON.stringify(entries)
  ok(dump.includes('2203') && dump.includes('1122'), '分录含预收账款2203与应收账款1122')
  ok(dump.includes(C1), '2203 分录带客户辅助核算 KTEST1')
  const amounts = entries.map(e => num(e.debitAmount ?? e.debit) + num(e.creditAmount ?? e.credit))
  ok(amounts.length >= 2 && amounts.every(a => Math.abs(a - 30) < 0.001), '借贷分录金额均为30')
  const debitSum = entries.reduce((s, e) => s + num(e.debitAmount ?? e.debit), 0)
  const creditSum = entries.reduce((s, e) => s + num(e.creditAmount ?? e.credit), 0)
  ok(Math.abs(debitSum - 30) < 0.001 && Math.abs(creditSum - 30) < 0.001,
    `借贷平衡 借30/贷30（实际 借${debitSum}/贷${creditSum}）`)

  // ---------------- F. 分页过滤 + 鉴权 ----------------
  section('F. XH 分页过滤与权限')
  const approved = await xhPage({ status: 'APPROVED' })
  ok(approved.records.length > 0 && approved.records.every(r => r.status === 'APPROVED'),
    `状态过滤 APPROVED 全部命中（${approved.records.length} 张）`)
  const manual = await xhPage({ businessSource: 'MANUAL' })
  ok(manual.records.every(r => r.businessSource === 'MANUAL') && manual.records.length >= 1,
    '业务来源过滤 MANUAL 全部命中')
  const byCust = await xhPage({ customer: 'KTEST2' })
  ok(byCust.total === 0, '按 KTEST2 客户过滤无 XH 单（全部测试单归 KTEST1）')
  const byNo = await xhPage({ writeoffNo: 'XH' })
  ok(byNo.records.length === byNo.total && byNo.total >= 3, '单号模糊过滤返回全部 XH 单')
  const dateQ = await xhPage({ dateFrom: TODAY, dateTo: TODAY })
  ok(dateQ.records.every(r => String(r.writeoffDate).startsWith(TODAY)), '核销日期区间过滤命中')
  // 无令牌访问
  const noAuth = await fetch(BASE + '/finance/advance-writeoff/page', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }).then(r => r.json())
  ok(noAuth.code !== '0', '无令牌访问 XH 分页被拒绝（code=' + noAuth.code + '）')

  // 收尾：账户总览余额一致性
  const acctPage = await post('/finance/customer-account/page', {
    pageNo: 1, pageSize: 200, keyword: 'KTEST1',
  })
  const acct = acctPage.records.find(r => r.customerCode === C1)
  ok(!!acct && Math.abs(num(acct.advanceBalance) - 470) < 0.001
    && Math.abs(num(acct.arBalance) - 800) < 0.001,
    `客户账户总览：预收470/应收800（实际 ${acct && acct.advanceBalance}/${acct && acct.arBalance}）`)

  console.log(`\n🎉 PRD-35 M3 全部通过：${passed} 项断言`)
}

main().catch(e => {
  console.error('\n❌ 验收失败：' + (e && e.message))
  console.error(e && e.stack)
  process.exit(1)
})
