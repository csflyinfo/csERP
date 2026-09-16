/**
 * PRD-35 M4 端到端 API 验收：
 *   AC-13 TMS 门店结算溢收自动转预收（参数 tms.settle.overpay-to-advance，P0189）
 *   AC-14 客户期初预收 ADV_OPENING（暂存/导入/建账/反建账守卫/GL 2203 引入）
 *   日结：advance_account_balance 定版 + 向导预收勾稽提示 + 封账日守卫回归
 *
 * 专用隔离库运行（勿连用户库 8080）：
 *   cd backend
 *   java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8082 \
 *     "--spring.datasource.url=jdbc:h2:file:./data/verify-m4;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"
 *   API_BASE=http://localhost:8082/api node development/06-testing/scripts/prd35-m4-api-e2e.mjs
 *
 * 脚本会：①经 H2 RunScript 套用 prd35-m4-fixtures.sql（AUTO_SERVER 并发直连）；
 * ②全程使用 M4 前缀数据，夹具自带幂等清理，可重复执行；
 * ③结束日会做当日日结（隔离库专用）。
 */
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import cm from './init-common.js'

const { post, login, expectFail, assert, num, todayStr } = cm
const BASE = cm.BASE

const H2_JAR = process.env.H2_JAR
  || 'C:/Users/Administrator/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar'
const JAVA = process.env.JAVA_BIN || 'C:/Users/Administrator/jdk21/bin/java.exe'
const H2_URL = process.env.H2_URL
  || 'jdbc:h2:file:E:/work/erp-wms-tms/backend/data/verify-m4;AUTO_SERVER=TRUE;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE'
const FIXTURE = path.resolve('development/06-testing/scripts/prd35-m4-fixtures.sql')

const C_A = 'M4CUS_A'
const C_B = 'M4CUS_B'
const C_C = 'M4CUS_C'
const C_O1 = 'M4ADV1'
const C_O2 = 'M4ADV2'
const CUSTOMERS = [
  [C_A, 'M4溢收客户甲'], [C_B, 'M4溢收客户乙'], [C_C, 'M4溢收客户丙'],
  [C_O1, 'M4期初客户甲'], [C_O2, 'M4期初客户乙'],
]
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

// ---------------- H2 直连（只读探针 + 夹具套用） ----------------

function runShell(sql) {
  const out = execFileSync(JAVA, ['-Dfile.encoding=UTF-8', '-cp', H2_JAR, 'org.h2.tools.Shell',
    '-url', H2_URL, '-user', 'sa', '-sql', sql], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 })
  return out
}
/** 单值 SELECT：返回第二行（表头之后的第一行数据）trim 文本。 */
function probeOne(sql) {
  const lines = runShell(sql).split(/\r?\n/).map(s => s.trim()).filter(s => s.length)
  if (lines.length < 2) throw new Error('探针无数据行：' + sql + '\n' + runShell(sql))
  return lines[1]
}
function probeNum(sql) {
  return Number(probeOne(sql) || 0)
}
function applyFixtures(fundName, fundCode) {
  let sql = fs.readFileSync(FIXTURE, 'utf8')
  sql = sql.replaceAll('__FUND__', fundName).replaceAll('__FUND_CODE__', fundCode)
  const tmp = path.join(os.tmpdir(), 'prd35-m4-fixtures.applied.sql')
  fs.writeFileSync(tmp, sql, 'utf8')
  execFileSync(JAVA, ['-Dfile.encoding=UTF-8', '-cp', H2_JAR, 'org.h2.tools.RunScript',
    '-url', H2_URL, '-user', 'sa', '-script', tmp], { stdio: 'inherit' })
}

// ---------------- 业务取数 ----------------

async function advanceBalance(code) {
  const d = await post('/finance/customer-account/advance-balance', { customerCode: code })
  return num(d.advanceBalance)
}
async function flowRows(code, accountType = 'ADVANCE') {
  const p = await post('/finance/customer-account/flow/page', { customerCode: code, accountType, pageNo: 1, pageSize: 200 })
  return p.records || []
}
async function receiptByNo(no) {
  const p = await post('/finance/receipt/page', { pageNo: 1, pageSize: 20, filters: { receiptNo: no } })
  return (p.records || [])[0] || null
}
async function overpayAdvBills() {
  const p = await post('/finance/receipt/page', { pageNo: 1, pageSize: 200, filters: { businessSource: 'DRIVER_OVERPAY_ADV' } })
  return (p.records || []).filter(r => String(r.receiptNo || '').startsWith('SK-M4-')
    || ['MJ-M4-S1', 'MJ-M4-S2', 'MJ-M4-S3'].includes(r.relatedBillNo))
}

async function main() {
  await login()
  console.log('登录成功：' + BASE)

  // ---------- 档案 ----------
  const funds = await post('/base/master/fund-account/page', { pageNo: 1, pageSize: 50 })
  const fund = (funds.records || []).find(f => f.status === 'NORMAL' || !f.status) || funds.records[0]
  assert(fund, '资金账户至少存在 1 个')
  const fundName = fund.fundAccountName
  const fundCode = fund.fundAccountCode || fundName
  console.log(`资金账户：${fundName}（${fundCode}）`)
  for (const [code, name] of CUSTOMERS) {
    try {
      await post('/base/customer/create', {
        customerCode: code, customerName: name,
        channelType: '零售商超', accountPeriodType: '月结30天',
      })
    } catch (e) {
      if (!String(e.message).includes('已存在') && !String(e.message).includes('重复')) throw e
    }
  }
  console.log('客户档案就位：' + CUSTOMERS.map(c => c[0]).join('/'))

  // ---------- 夹具 ----------
  // 重跑复位：上轮若已日结，必须走标准反日结链——BizDayCloseGuard 的封单日是 JVM 内懒加载缓存，
  // 夹具直接 DELETE biz_day_close 无法让它失效，只有反日结事务 afterCommit 的 evict 能复位。
  try {
    await post('/finance/day-close/reopen', { date: TODAY, reason: 'PRD-35 M4 回归重跑复位' })
    console.log('检测到上轮日结，已走反日结链复位 ' + TODAY)
  } catch (e) {
    if (!String(e.message).includes('未日结')) throw e
  }
  applyFixtures(fundName, fundCode)
  // 夹具直接改了 sys_param_runtime（建账标志/溢收参数），经一次合法参数更新触发全量缓存 evict，
  // 让随后读取落到 SQL 重置后的值（夹具支持中断重跑）
  await post('/system/param/update', { paramKey: 'tms.settle.overpay-to-advance', paramValue: 'Y' })
  ok(probeNum("SELECT COUNT(*) FROM fin_ar WHERE ar_no LIKE 'AR-M4-%'") === 4, '夹具：4 行 AR-M4 应收就位')
  ok(probeNum("SELECT COUNT(*) FROM tms_store_settlement WHERE settle_id LIKE 'MJID-M4-%'") === 3, '夹具：3 张门店结算就位')
  for (const code of [C_A, C_B, C_C, C_O1]) {
    await post('/finance/customer-account/repair', { customerCode: code })
  }

  // ================= AC-14：期初预收（总账未启用阶段） =================
  section('AC-14-1 导入校验：有效行进暂存，错误行落 ERROR')
  const importRows = [
    { customerCode: C_O1, customerName: '', originalBillNo: 'M4-YK-1', originalBillDate: '2026-08-31', advAmount: '120.00', remark: '期初预收甲' },
    { customerCode: C_O2, customerName: '', originalBillNo: 'M4-YK-2', originalBillDate: '', advAmount: '80', remark: '' },
    { customerCode: 'M4NOEXIST', originalBillNo: 'X', advAmount: '10' },                 // 客户不存在
    { customerCode: C_O1, originalBillNo: 'M4-YK-1', advAmount: '5' },                    // 文件内重复
    { customerCode: C_O2, originalBillNo: 'M4-YK-3', advAmount: '0' },                    // 金额<=0
    { customerCode: C_O2, originalBillNo: 'M4-YK-4', originalBillDate: '2026/09/01', advAmount: '5' }, // 日期格式错
  ]
  const imp = await post('/init/adv/import', { rows: importRows, fileName: 'M4期初预收.xlsx' })
  ok(imp.inserted === 2 && imp.failed === 4, `导入成功2/失败4（实际 ${imp.inserted}/${imp.failed}）`)
  let st = await post('/init/adv/status', {})
  ok(st.posted === false && st.lineCount === 2 && st.errorCount === 4 && st.canPost === false,
    `状态：未建账/有效2/错误4/不可建账（canPost=${st.canPost}）`)
  const errPage = await post('/init/adv/line/page', { pageNo: 1, pageSize: 50, lineStatus: 'ERROR' })
  ok(errPage.records.length === 4, '错误行分页可见 4 行')
  for (const r of errPage.records) {
    await post('/init/adv/line/delete', { lineId: r.lineId })
  }
  st = await post('/init/adv/status', {})
  ok(st.errorCount === 0 && st.lineCount === 2 && st.canPost === true, '删除错误行后可建账')

  section('AC-14-2 手工增改与守卫')
  await expectFail('/init/adv/line/save', { customerCode: C_O1, advAmount: '' }, '预收金额必填')
  passed++; console.log('  ✅ 手工行缺金额 → 拦截')
  const vPage = await post('/init/adv/line/page', { pageNo: 1, pageSize: 50, lineStatus: 'VALID' })
  ok(vPage.records.length === 2, '暂存有效行 2 行（标准名按编码回写）')
  const row1 = vPage.records.find(r => r.customerCode === C_O1)
  ok(row1.customerName === 'M4期初客户甲' && num(row1.advAmount) === 120, '客户名称按档案回写、预收金额 120')

  section('AC-14-3 期初建账：ADV_OPENING 流水 / QCYK 源号 / 余额一次性建立 / 不动资金')
  const posted = await post('/init/adv/post', {})
  ok(/^QC\d{8}\d{4}/.test(posted.postNo) && posted.lineCount === 2 && num(posted.totalAmount) === 200,
    `建账成功：批号 ${posted.postNo}，2 行合计 200`)
  const postNo = posted.postNo
  const f1 = await flowRows(C_O1)
  const op1 = f1.find(r => r.bizType === 'ADV_OPENING')
  ok(!!op1 && num(op1.increaseAmount) === 120
    && (op1.sourceBill === `QCYK-${postNo}-1` || op1.receiptNo === `QCYK-${postNo}-1`),
    `M4ADV1 期初流水 ADV_OPENING +120，来源号 QCYK-${postNo}-1`)
  ok(String(op1.postDate || '').startsWith(TODAY), '期初流水 post_date=建账日 ' + TODAY)
  const f2 = await flowRows(C_O2)
  ok(f2.some(r => r.bizType === 'ADV_OPENING' && num(r.increaseAmount) === 80), 'M4ADV2 期初流水 ADV_OPENING +80')
  ok((await advanceBalance(C_O1)) === 120 && (await advanceBalance(C_O2)) === 80, '两客户预收余额 120/80 一次性建立')
  ok(probeNum(`SELECT COUNT(*) FROM fin_fund_ledger WHERE source_bill LIKE 'QCYK-${postNo}-%'`) === 0,
    '建账不登记资金流水（fin_fund_ledger 无 QCYK 行）')
  ok(probeNum("SELECT COUNT(*) FROM fin_receipt_bill WHERE receipt_no LIKE 'QCYK-%'") === 0, '建账不生成收款单')
  const postedPage = await post('/init/adv/line/page', { pageNo: 1, pageSize: 50, posted: 'Y' })
  ok(postedPage.records.length === 2 && postedPage.records.every(r => r.posted === 'Y' && r.generatedFlowId),
    '暂存行回写 posted=Y 与 generated_flow_id')
  st = await post('/init/adv/status', {})
  ok(st.posted === true && st.canPost === false && st.canReverse === true
    && num(st.postTotalAmount) === 200, `状态：已建账 ${st.postNo}，可反建账`)
  await expectFail('/init/adv/post', {}, '已期初建账')
  passed++; console.log('  ✅ 重复建账 → 拦截（锁定）')

  section('AC-14-4 反建账守卫：预收被核销时整批拒绝；撤销下游后成功')
  // 用期初预收对 M4ADV1 的 AR-M4-OP1 做一笔 XH 核销 100
  const xh = await post('/finance/advance-writeoff/create', {
    customerCode: C_O1, customerName: 'M4期初客户甲', handler: '系统管理员',
    writeoffDate: TODAY, remark: 'M4期初守卫',
    details: [{ arNo: 'AR-M4-OP1', writeoffAmount: 100 }],
  })
  await post('/finance/advance-writeoff/audit', { writeoffId: xh.writeoffId })
  ok((await advanceBalance(C_O1)) === 20, 'XH 核销 100 后 M4ADV1 预收 120→20')
  await expectFail('/init/adv/reverse', { reason: '' }, '必须填写原因')
  passed++; console.log('  ✅ 反建账无原因 → 拦截')
  await expectFail('/init/adv/reverse', { reason: 'M4验证反建账应被拒' }, '已被预收核销或退款使用')
  passed++; console.log('  ✅ 期初预收已被 XH 核销 → 反建账整批拒绝（防删一半/余额链断裂）')
  // 撤销下游
  await post('/finance/advance-writeoff/cancel-audit', { writeoffId: xh.writeoffId })
  ok((await advanceBalance(C_O1)) === 120, '反审核 XH 后 M4ADV1 预收恢复 120')
  await post('/init/adv/reverse', { reason: 'M4验证：下游已撤销，反建账' })
  passed++; console.log('  ✅ 下游撤销后反建账成功')
  ok((await advanceBalance(C_O1)) === 0 && (await advanceBalance(C_O2)) === 0, '反建账后两客户预收余额清零')
  ok((await flowRows(C_O1)).filter(r => r.bizType === 'ADV_OPENING').length === 0
    && (await flowRows(C_O2)).filter(r => r.bizType === 'ADV_OPENING').length === 0, 'ADV_OPENING 流水已删除')
  st = await post('/init/adv/status', {})
  ok(st.posted === false && st.canPost === true && st.lineCount === 2, '暂存回退为未建账、可重新建账')

  section('AC-14-5 重新建账（供 GL 引入与日结验证）')
  const posted2 = await post('/init/adv/post', {})
  ok(num(posted2.totalAmount) === 200, `重新建账成功：批号 ${posted2.postNo}，合计 200`)
  ok((await advanceBalance(C_O1)) === 120 && (await advanceBalance(C_O2)) === 80, '预收余额恢复 120/80')

  // ---------- GL：一键引入 2203（引入后清空期初余额表恢复试算 0 平衡，再启用总账） ----------
  section('AC-14-6 总账一键引入预收 2203（按客户辅助）')
  const biz = await post('/finance/gl/init/business-import', {})
  ok(num(biz.advCount) >= 2 && num(biz.advTotal) >= 200,
    `引入结果含预收 ${biz.advCount} 户/${biz.advTotal} 元（≥2户/200）`)
  const aux2203 = await post('/finance/gl/init/aux-balance-list', { accountCode: '2203' })
  const aux1 = aux2203.find(r => r.auxCustomer === C_O1)
  const aux2 = aux2203.find(r => r.auxCustomer === C_O2)
  ok(!!aux1 && num(aux1.openCredit) === 120 && !!aux2 && num(aux2.openCredit) === 80,
    '2203 辅助期初：M4ADV1 贷120、M4ADV2 贷80')
  // 一键引入改写的是 GL 期初余额表；验证库恢复到空试算以启用总账（生产走正常期初流程，不在此路径）
  runShell("DELETE FROM fin_init_balance WHERE account_code IN ('1122','2203','2202','1405')")
  await post('/finance/gl/init/enable', { startPeriod: '202609' })
  passed++
  // 正常启用配置：资金账户→总账科目映射（ADVANCE_RECEIPT 模板 @FUND 借方取它；夹具复位段会清空）
  runShell("UPDATE base_fund_account SET gl_account_code='1001' WHERE fund_account_code='01'")
  console.log('  ✅ 清空引入试算行后总账启用成功（202609；ADV_OPENING 本身不发 GL 事件；现金→1001 映射就位）')
  await new Promise(r => setTimeout(r, 300))
  const evAll = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 200 })
  ok(!(evAll.records || []).some(e => JSON.stringify(e).includes('QCYK-')), '事件池无 QCYK 期初事件（期初不造凭证）')

  // ================= AC-13：门店结算溢收自动转预收 =================
  section('AC-13-1 参数Y：交账审核溢收100 → 自动 ADVANCE 单（资金只入一笔）')
  const a1 = await post('/tms/settlement/JZID-M4-1/audit', {})
  ok(a1.status === 'APPROVED', '交账单 JZ-M4-1 审核通过')
  const s1Bill = await receiptByNo('SK-M4-S1')
  ok(!!s1Bill && s1Bill.status === 'APPROVED' && num(s1Bill.totalAmount) === 300
    && num(s1Bill.verifiedAmount) === 200 && s1Bill.businessSource === 'DRIVER_SETTLE',
    '结算收款单 SK-M4-S1 已审核：实收300/核销应收200/未匹配100')
  ok(probeOne("SELECT fin_status FROM tms_store_settlement WHERE settle_id='MJID-M4-S1'") === 'APPROVED',
    '门店结算 MJ-M4-S1 fin_status=APPROVED')
  const adv1 = (await overpayAdvBills()).find(r => r.relatedBillNo === 'MJ-M4-S1')
  ok(!!adv1, '自动生成溢收预收单')
  if (adv1) {
    ok(adv1.status === 'APPROVED' && adv1.receiptType === 'ADVANCE' && num(adv1.totalAmount) === 100
      && adv1.relatedBillNo === 'MJ-M4-S1' && adv1.businessSource === 'DRIVER_OVERPAY_ADV',
      `自动单 ${adv1.receiptNo}：APPROVED/ADVANCE/100/来源 DRIVER_OVERPAY_ADV/related=MJ-M4-S1`)
    ok((await advanceBalance(C_A)) === 100, 'M4CUS_A 预收余额=100（溢收自动转入）')
    const fa = await flowRows(C_A)
    ok(fa.some(r => r.bizType === 'ADV_RECEIPT' && num(r.increaseAmount) === 100
      && (r.receiptNo === adv1.receiptNo || r.sourceBill === adv1.receiptNo)),
      '预收流水 ADV_RECEIPT +100，单号=' + adv1.receiptNo)
    // 资金不双计
    ok(probeNum(`SELECT COUNT(*) FROM fin_fund_ledger WHERE source_bill='SK-M4-S1' AND direction='IN'`) === 1
      && probeNum(`SELECT COALESCE(SUM(amount),0) FROM fin_fund_ledger WHERE source_bill='SK-M4-S1'`) === 300,
      '资金流水：结算收款单仅一笔 IN 300')
    ok(probeNum(`SELECT COUNT(*) FROM fin_fund_ledger WHERE source_bill='${adv1.receiptNo}'`) === 0,
      `自动预收单 ${adv1.receiptNo} 不写资金流水（资金已在结算单全额入账）`)
    // 往来台账：结算 IN 300 + 预收 IN 100
    ok(probeNum(`SELECT COUNT(*) FROM fin_counterparty_ledger WHERE source_bill_no='SK-M4-S1' AND direction='IN'`) === 1
      && probeNum(`SELECT COUNT(*) FROM fin_counterparty_ledger WHERE source_bill_no='${adv1.receiptNo}' AND direction='IN'`) === 1,
      '往来台账：结算 IN 300 一行、自动预收 IN 100 一行')
    // 核销真值
    ok(probeNum(`SELECT COALESCE(SUM(reconcile_amount),0) FROM fin_reconcile_record
        WHERE receipt_no='SK-M4-S1' AND business_no='AR-M4-A1'`) === 200,
      '应收核销真值：SK-M4-S1 → AR-M4-A1 核销 200')
    // GL 事件（总账已启用）
    await new Promise(r => setTimeout(r, 400))
    const ev1 = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 50, eventCode: 'ADVANCE_RECEIPT' })
    const e1 = (ev1.records || []).find(e => JSON.stringify(e).includes(adv1.receiptNo) && e.reverseFlag !== true && e.isReverse !== true)
    ok(!!e1, 'GL 事件池存在自动预收单 ADVANCE_RECEIPT 事件：' + adv1.receiptNo)
    if (e1) {
      await post('/finance/gl/event/generate', { ids: [e1.id] })
      const vouchers = await post('/finance/gl/voucher/by-bill', { billNo: adv1.receiptNo })
      ok(Array.isArray(vouchers) && vouchers.length >= 1, '自动预收单生成凭证并可按单联查')
      const voucher = await post('/finance/gl/voucher/detail', { id: vouchers[0].id })
      const entries = voucher.entries || voucher.lines || []
      const dump = JSON.stringify(entries)
      ok(dump.includes('2203'), '凭证含预收账款 2203（贷方）')
      ok(dump.includes(C_A), '2203 分录带客户辅助 M4CUS_A')
      const debit = entries.reduce((s, e) => s + num(e.debitAmount ?? e.debit), 0)
      const credit = entries.reduce((s, e) => s + num(e.creditAmount ?? e.credit), 0)
      ok(Math.abs(debit - 100) < 0.001 && Math.abs(credit - 100) < 0.001,
        `凭证借贷各 100（实际 借${debit}/贷${credit}）`)
    }
  }

  section('AC-13-2 自动预收单走标准反审核链：回补预收/红冲 GL/资金不动')
  if (adv1) {
    await post('/finance/receipt/cancel-audit', { receiptId: adv1.receiptId })
    ok((await advanceBalance(C_A)) === 0, '反审核自动预收单后 M4CUS_A 预收余额回补为 0')
    const fa2 = await flowRows(C_A)
    ok(fa2.some(r => (r.bizType === 'ADV_REVERSE' || r.isRed === 'Y' || r.isRed === true)
      && (r.receiptNo === adv1.receiptNo || r.sourceBill === adv1.receiptNo)),
      '预收流水存在红字冲回行 ADV_REVERSE')
    ok(probeNum(`SELECT COUNT(*) FROM fin_fund_ledger WHERE source_bill='SK-M4-S1'`) === 1
      && probeNum(`SELECT COUNT(*) FROM fin_fund_ledger WHERE source_bill='${adv1.receiptNo}'`) === 0,
      '反审核不补资金 OUT 流水：结算单仍只一笔，自动单始终零资金流水')
    await new Promise(r => setTimeout(r, 400))
    const evR = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 50, eventCode: 'ADVANCE_RECEIPT' })
    const hits = (evR.records || []).filter(e => JSON.stringify(e).includes(adv1.receiptNo))
    ok(hits.length >= 2, `自动预收单反审核产生红字 GL 事件（事件 ${hits.length} 条，正/红各一）`)
    const back = await receiptByNo(adv1.receiptNo)
    ok(back.status === 'PENDING', '自动预收单反审核后回待审核（可追溯/可再审，与标准收款单一致）')
  }

  section('AC-13-3 参数Y：第二张交账单溢收40 → 自动转预收（幂等键不重复）')
  const a2 = await post('/tms/settlement/JZID-M4-2/audit', {})
  ok(a2.status === 'APPROVED', '交账单 JZ-M4-2 审核通过')
  const adv2 = (await overpayAdvBills()).find(r => r.relatedBillNo === 'MJ-M4-S2')
  ok(!!adv2 && num(adv2.totalAmount) === 40 && adv2.status === 'APPROVED',
    `第二张自动预收单 ${adv2 && adv2.receiptNo}：40 元已审核`)
  ok((await advanceBalance(C_B)) === 40, 'M4CUS_B 预收余额=40')

  section('AC-13-4 参数N：仅留日志提示，不生成预收单/不动预收余额')
  await post('/system/param/update', { paramKey: 'tms.settle.overpay-to-advance', paramValue: 'N' })
  passed++; console.log('  ✅ 参数 tms.settle.overpay-to-advance 置为 N')
  const a3 = await post('/tms/settlement/JZID-M4-3/audit', {})
  ok(a3.status === 'APPROVED', '交账单 JZ-M4-3 审核通过')
  const s3 = await receiptByNo('SK-M4-S3')
  ok(!!s3 && s3.status === 'APPROVED' && num(s3.verifiedAmount) === 30,
    '结算收款单 SK-M4-S3 已审核，核销 30，余 50 未匹配')
  const adv3 = (await overpayAdvBills()).find(r => r.relatedBillNo === 'MJ-M4-S3')
  ok(!adv3, '参数关：未生成 DRIVER_OVERPAY_ADV 预收单')
  ok((await advanceBalance(C_C)) === 0, '参数关：M4CUS_C 无预收余额')
  ok(probeOne("SELECT fin_status FROM tms_store_settlement WHERE settle_id='MJID-M4-S3'") === 'APPROVED',
    '参数关：门店结算仍正常置 APPROVED（不阻断交账）')
  const logCnt = probeNum("SELECT COUNT(*) FROM sys_operation_log_runtime WHERE module_code='tms.storeSettle' "
    + "AND biz_no='MJ-M4-S3' AND action='RECONCILE_REMAIN'")
  ok(logCnt >= 1, 'RECONCILE_REMAIN 留痕：未匹配 50 提示财务人工处理')
  // 恢复参数默认 Y
  await post('/system/param/update', { paramKey: 'tms.settle.overpay-to-advance', paramValue: 'Y' })

  // ================= 日结：预收定版列 + 勾稽提示 + 封账守卫 =================
  section('日结-1 向导第5步：预收取客户账户，重分类兜底为提示项')
  const wz = await post('/finance/day-close/wizard', { date: TODAY })
  const arTie = wz.step5 && wz.step5.ar
  ok(!!arTie && arTie.advanceAccountTotal !== undefined, '向导 step5.ar 含 advanceAccountTotal')
  const expectAdvTotal = 120 + 80 + 0 + 40 + 0 // M4ADV1/M4ADV2/M4CUS_A(反审核回0)/M4CUS_B/M4CUS_C
  const accountTotal = num(arTie && arTie.advanceAccountTotal)
  ok(Math.abs(accountTotal - expectAdvTotal) < 0.01,
    `账户预收余额合计=${expectAdvTotal}（实际 ${accountTotal}；种子库无其他预收时严格相等）`)
  ok(arTie.advanceReclassTotal !== undefined && Array.isArray(arTie.advanceReclassDiffs),
    '负应收重分类兜底合计/逐户提示字段存在（不进硬勾稽）')

  section('日结-2 执行当日日结（首次，勾选确认）')
  const closeRes = await post('/finance/day-close/close', {
    date: TODAY,
    openingConfirmed: true,
    acknowledgeHanging: true,
    acknowledgeAnomaly: true,
    cashCounts: {},
  })
  ok(!!closeRes.closeNo, '日结完成：' + closeRes.closeNo + (closeRes.idempotent ? '（幂等回显）' : ''))

  const arDaily = await post('/finance/day-close/ar-daily/page', {
    pageNo: 1, pageSize: 200, filters: { from: TODAY, to: TODAY },
  })
  const findDaily = code => (arDaily.records || []).find(r => r.customerCode === code)
  const dO1 = findDaily(C_O1)
  const dO2 = findDaily(C_O2)
  ok(!!dO1 && num(dO1.advanceAccountBalance) === 120, '定版台账：M4ADV1 预收余额列=120（纯预收无应收也建行）')
  ok(!!dO2 && num(dO2.advanceAccountBalance) === 80, '定版台账：M4ADV2 预收余额列=80')
  const dB = findDaily(C_B)
  ok(!!dB && num(dB.advanceAccountBalance) === 40, '定版台账：M4CUS_B 预收余额列=40')
  const dA = findDaily(C_A)
  ok(!dA || num(dA.advanceAccountBalance) === 0, '定版台账：M4CUS_A 反审核后预收余额列=0/无溢额')
  // 原重分类列仍在（负应收兜底口径）
  ok(arDaily.records.every(r => r.advanceAmount !== undefined), 'advance_amount 重分类列保留（兜底口径未删）')

  section('日结-3 封账日守卫回归：当日收款单审核被拒')
  const guardReceipt = await post('/finance/receipt/create', {
    receiptDate: TODAY, counterpartyType: 'CUSTOMER',
    counterpartyCode: C_A, counterpartyName: 'M4溢收客户甲', handler: '系统管理员',
    summary: 'M4封账守卫', receiptType: 'SETTLE',
    details: [{ fundAccount: fundName, amount: 10, remark: '' }],
  })
  await expectFail('/finance/receipt/audit', { receiptId: guardReceipt.receiptId }, ['封账', '日结', '不能'])
  passed++; console.log('  ✅ 封账日收款单审核 → 拦截')
  await expectFail('/tms/settlement/JZID-M4-1/audit', {}, ['已审核'])
  passed++; console.log('  ✅ 交账单重复审核仍被拒（幂等）')

  // ================= 权限 =================
  section('权限：无令牌访问期初预收被拒')
  const noAuth = await fetch(BASE + '/init/adv/status', { method: 'POST', headers: { 'Content-Type': 'application/json' } })
    .then(r => r.json())
  ok(noAuth.code !== '0', '无令牌 /init/adv/status 被拒（code=' + noAuth.code + '）')

  console.log(`\n🎉 PRD-35 M4 全部通过：${passed} 项断言`)
}

main().catch(e => {
  console.error('\n❌ 验收失败：' + (e && e.message))
  console.error(e && e.stack)
  process.exit(1)
})
