/**
 * PRD-36 M4 端到端 API 验收（V131）：
 *   ① fin_ap_init 同批号 QCAP/QCYF：应付期初新增「期初预付金额」列，一次过账同时建应付与预付
 *   ② 已上线库预付补录（/init/ap/prepay-supplement）：不锁定、可多批、封账/总账月结双守卫
 *   ③ biz_close_ap_daily 预付账户/费用账户分列 + 向导第5步应付勾稽五项提示（负应付重分类/双计）
 *   ④ 总账一键引入 1123 供应商预付（借/供应商辅助）；业财对账五组（预付/厂家费用 advisory）
 *   ⑤ 供应商停用/删除五守卫（三余额/未结AP/未兑JF/未作废DX/未作废FX）
 *   ⑥ 供应商档案三余额改取账户值 + VIEW_AP_BALANCE 字段脱敏
 *
 * 专用隔离库（勿连用户库 8080/5173）：
 *   cd backend
 *   java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8082 \
 *     "--spring.datasource.url=jdbc:h2:file:E:/work/erp-wms-tms/backend/verify-prd36-m1/erp-v1;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"
 *   API_BASE=http://127.0.0.1:8082/api node development/06-testing/scripts/prd36-m4-api-e2e.mjs
 *
 * 脚本自复位：先走反日结 API（清 JVM 封账缓存），再经 H2 RunScript 串联执行
 *   prd36-m2-reset-v129.sql → prd36-m3-reset-v130.sql → prd36-m4-reset-v131.sql，
 * 然后灌 prd36-m4-fixtures.sql，可重复执行。
 */
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import cm from './init-common.js'

const { post, get, login, expectFail, assert, num, todayStr, plusDays } = cm
const BASE = cm.BASE

const H2_JAR = process.env.H2_JAR
  || 'C:/Users/Administrator/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar'
const JAVA = process.env.JAVA_BIN || 'C:/Users/Administrator/jdk21/bin/java.exe'
const H2_URL = process.env.H2_URL
  || 'jdbc:h2:file:E:/work/erp-wms-tms/backend/verify-prd36-m1/erp-v1;AUTO_SERVER=TRUE;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE'
const DIR = path.resolve('development/06-testing/scripts')
const sqlFile = n => path.join(DIR, n)

// ---------------- 测试主数据 ----------------
const S01 = 'S-M4-01', N01 = 'M4期初混合户'   // AP100 + PP40 同批号
const S02 = 'S-M4-02', N02 = 'M4期初预付户'   // 仅 PP60
const S03 = 'S-M4-03', N03 = 'M4期初应付户'   // 仅 AP70
const SAP = 'S-M4-AP', NAP = 'M4应付守卫户'
const SJF = 'S-M4-JF', NJF = 'M4费用守卫户'
const SDX = 'S-M4-DX', NDX = 'M4兑现守卫户'
const SFX = 'S-M4-FX', NFX = 'M4核销守卫户'
const SNEG = 'S-M4-NEG', NNEG = 'M4负应付户'   // 夹具 -30 AP，补录 70 预付造双计
const SOK = 'S-M4-OK', NOK = 'M4干净户'
const SB2 = 'S-M4-B2', NB2 = 'M4补录二批户'
const SUPPLIERS = [
  [S01, N01], [S02, N02], [S03, N03],
  [SAP, NAP], [SJF, NJF], [SDX, NDX], [SFX, NFX],
  [SNEG, NNEG], [SOK, NOK], [SB2, NB2],
]
const TODAY = todayStr()
const DUE30 = plusDays(30)

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

// ---------------- H2 并发直连（AUTO_SERVER） ----------------
function runShell(sql) {
  return execFileSync(JAVA, ['-Dfile.encoding=UTF-8', '-cp', H2_JAR, 'org.h2.tools.Shell',
    '-url', H2_URL, '-user', 'sa', '-sql', sql], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 })
}
function probeOne(sql) {
  const lines = runShell(sql).split(/\r?\n/).map(s => s.trim()).filter(s => s.length)
  if (lines.length < 2) throw new Error('探针无数据行：' + sql)
  return lines[1]
}
function probeNum(sql) {
  return Number(probeOne(sql) || 0)
}
function runScriptFile(file, replacements = {}) {
  let sql = fs.readFileSync(file, 'utf8')
  for (const [k, v] of Object.entries(replacements)) sql = sql.replaceAll(k, v)
  const tmp = path.join(os.tmpdir(), path.basename(file) + '.applied.sql')
  fs.writeFileSync(tmp, sql, 'utf8')
  execFileSync(JAVA, ['-Dfile.encoding=UTF-8', '-cp', H2_JAR, 'org.h2.tools.RunScript',
    '-url', H2_URL, '-user', 'sa', '-script', tmp], { stdio: 'inherit' })
}
function applyReset() {
  // 三复位脚本拼成一个临时脚本（顺序：M2 付款核销域 → M3 厂家费用域 → M4 全量复位）
  const parts = [
    'prd36-m2-reset-v129.sql',
    'prd36-m3-reset-v130.sql',
    'prd36-m4-reset-v131.sql',
  ].map(f => fs.readFileSync(sqlFile(f), 'utf8'))
  const tmp = path.join(os.tmpdir(), 'prd36-m4-reset-chain.applied.sql')
  fs.writeFileSync(tmp, parts.join('\n'), 'utf8')
  execFileSync(JAVA, ['-Dfile.encoding=UTF-8', '-cp', H2_JAR, 'org.h2.tools.RunScript',
    '-url', H2_URL, '-user', 'sa', '-script', tmp], { stdio: 'inherit' })
}

// ---------------- 业务取数助手 ----------------
async function apRows(supplierName) {
  const p = await post('/finance/ap/page', {
    pageNo: 1, pageSize: 200, filters: { supplier: supplierName },
  })
  return p.records || []
}
async function flowRows(code, accountType) {
  const p = await post('/finance/supplier-account/flow/page', {
    supplierCode: code, accountType, pageNo: 1, pageSize: 200,
  })
  return { records: p.records || [], currentBalance: num(p.currentBalance) }
}
async function prepayBalance(code) {
  const d = await post('/finance/supplier-account/prepay-balance', { supplierCode: code })
  return num(d.prepayBalance)
}
async function initStatus() {
  return post('/init/ap/status', {})
}
async function supStatus() {
  return post('/init/ap/prepay-supplement/status', {})
}
async function initLines(q = {}) {
  const p = await post('/init/ap/line/page', { pageNo: 1, pageSize: 200, ...q })
  return p.records || []
}
async function supLines(q = {}) {
  const p = await post('/init/ap/prepay-supplement/line/page', { pageNo: 1, pageSize: 200, ...q })
  return p.records || []
}
/** 捕获完整错误文案（多守卫拼接消息要一次断言多个片段）。 */
async function expectFailCapture(url, body) {
  try {
    await post(url, body)
  } catch (e) {
    return String(e.message || '')
  }
  throw new Error('预期失败但成功：' + url)
}
/** 建 FX 预付核销单并审核，返回 {writeoffId, writeoffNo}。 */
async function fxAudit(code, name, apNo, amount, no) {
  const fx = await post('/finance/prepay-writeoff/create', {
    supplierCode: code, supplierName: name, handler: '系统管理员', writeoffDate: TODAY,
    writeoffNo: no, remark: 'M4守卫核销',
    details: [{ apNo, writeoffAmount: amount }],
  })
  await post('/finance/prepay-writeoff/audit', { writeoffId: fx.writeoffId })
  return fx
}

async function main() {
  await login()
  console.log('登录成功：' + BASE)

  const funds = await post('/base/master/fund-account/page', { pageNo: 1, pageSize: 50 })
  const fund = (funds.records || []).find(f => f.status === 'NORMAL' || !f.status) || funds.records[0]
  assert(fund, '资金账户至少存在 1 个')
  const fundName = fund.fundAccountName
  console.log('资金账户：' + fundName)

  // ================= 0. 全量复位 =================
  section('0. 全量复位：反日结 API → m2/m3/m4 reset 链 → 建供应商 → 灌夹具')
  // 0.1 先把所有已封账日走标准反日结（清 BizDayCloseGuard JVM 懒缓存），再直删快照
  let closedDates = []
  try {
    closedDates = runShell('SELECT close_date FROM biz_day_close ORDER BY close_date')
      .split(/\r?\n/).map(s => s.trim()).filter(s => /^\d{4}-\d{2}-\d{2}/.test(s))
  } catch { closedDates = [] }
  for (const d of closedDates) {
    try {
      await post('/finance/day-close/reopen', { date: d.slice(0, 10), reason: 'PRD-36 M4 回归重跑复位' })
      console.log('  反日结：' + d.slice(0, 10))
    } catch (e) {
      if (!String(e.message).includes('未日结')) throw e
    }
  }
  applyReset()
  // 夹具/复位直改了 sys_param_runtime，经一次合法参数更新触发 SysParamService 全量缓存 evict
  await post('/system/param/update', { paramKey: 'tms.settle.overpay-to-advance', paramValue: 'Y' })
  console.log('  reset 链（m2→m3→m4）执行完成，参数缓存已 evict')

  for (const [code, name] of SUPPLIERS) {
    try {
      await post('/base/supplier/create', {
        supplierCode: code, supplierName: name,
        settlementMethod: '现结', accountPeriodDays: 0, defaultBuyer: '系统管理员',
      })
    } catch (e) {
      if (!String(e.message).includes('已存在') && !String(e.message).includes('重复')) throw e
    }
  }
  console.log('  M4 供应商 ' + SUPPLIERS.length + ' 户就位')
  runScriptFile(sqlFile('prd36-m4-fixtures.sql'), { __TODAY__: TODAY, __DUE30__: DUE30 })
  for (const code of ['S001', 'S002', 'S003']) {
    await post('/finance/supplier-account/repair', { supplierCode: code })
  }
  ok(probeNum("SELECT COUNT(*) FROM fin_ap WHERE ap_no IN ('AP-M4-G1','AP-M4-NEG')") === 2,
    '夹具：AP-M4-G1 +50 / AP-M4-NEG -30 就位')

  // 复位基线：AP 硬勾稽应为平（夹具两行 bill_date 都落当日：+50-30=+20）
  const wz0 = await post('/finance/day-close/wizard', { date: TODAY })
  const tie0 = wz0.step5 && wz0.step5.ap
  ok(!!tie0, '向导 step5.ap 存在')
  ok(Math.abs(num(tie0.prior) - 0) < 0.005 && Math.abs(num(tie0.added) - 20) < 0.005
    && Math.abs(num(tie0.current) - 20) < 0.005 && Math.abs(num(tie0.diff)) < 0.005,
    `复位基线应付硬勾稽平：prior ${tie0.prior}/added ${tie0.added}/settled ${tie0.settled}/current ${tie0.current}/diff ${tie0.diff}`)
  ok(Math.abs(num(tie0.prepayAccountTotal)) < 0.005
    && Math.abs(num(tie0.expenseAccountTotal)) < 0.005
    && Math.abs(num(tie0.prepayReclassTotal) - 30) < 0.005,
    '复位基线：预付/费用账户合计 0，负应付重分类已提示 30（夹具 -30 行）')
  ok((tie0.prepayReclassDiffs || []).some(r => r.supplier === NNEG)
    && (tie0.prepayDoubleCount || []).length === 0,
    '基线：重分类黄名单含负应付户、双计红名单为空（账户预付尚未补录）')
  let st0 = await initStatus()
  ok(st0.posted === false && st0.glInitialized === false && st0.lineCount === 0,
    `应付期初未建账/总账未启用/暂存空（posted=${st0.posted}, glInit=${st0.glInitialized}）`)

  // ================= 1. AP 期初：导入校验 + 同批号 QCAP/QCYF 过账 =================
  section('1. 应付期初导入：3 行有效（混填/仅预付/仅应付），5 行错误落 ERROR')
  const importRows = [
    { supplierCode: S01, originalBillNo: 'M4-AP-1', originalBillDate: '2026-08-31', apAmount: '100', prepayAmount: '40' },
    { supplierCode: S02, apAmount: '', prepayAmount: '60' },
    { supplierCode: S03, originalBillNo: 'M4-AP-3', apAmount: '70' },
    { supplierCode: 'S-M4-NOEX', apAmount: '10' },                                   // 供应商不存在
    { supplierCode: S01, originalBillNo: 'M4-AP-1', apAmount: '5' },                // 文件内供应商+单号重复
    { supplierCode: S02, apAmount: '0', prepayAmount: '0' },                        // 两项都为 0
    { supplierCode: S03, originalBillNo: 'M4-BAD-DATE', originalBillDate: '2026/09/01', apAmount: '5' }, // 日期格式
    { supplierCode: S01, prepayAmount: '-5' },                                      // 负数
  ]
  const imp = await post('/init/ap/import', { rows: importRows, fileName: 'M4应付期初.xlsx' })
  ok(imp.inserted === 3 && imp.failed === 5, `导入成功3/失败5（实际 ${imp.inserted}/${imp.failed}）`)
  ok((imp.failures || []).some(f => String(f.message || f.errorMsg || f.reason || '').includes('不存在')),
    '失败明细含「供应商编码不存在」')

  let st = await initStatus()
  ok(st.lineCount === 3 && st.errorCount === 5 && num(st.totalAmount) === 170
    && num(st.totalPrepayAmount) === 100 && st.canPost === false,
    `状态：有效3/错误5/应付合计170/预付合计100/有错不可建账`)
  const errs = await initLines({ lineStatus: 'ERROR' })
  ok(errs.length === 5, '错误行分页可见 5 行')
  for (const r of errs) await post('/init/ap/line/delete', { lineId: r.lineId })
  await expectFail('/init/ap/line/save',
    { supplierCode: S01, apAmount: '0', prepayAmount: '0' }, '至少一项大于 0')
  passed++; console.log('  ✅ 手工行两项金额都为 0 → 拦截')
  st = await initStatus()
  ok(st.errorCount === 0 && st.canPost === true, '删除错误行后可建账')

  section('1b. 过账：同批号 QCAP 应付单 + QCYF 预付流水（不造付款单/不动资金）')
  const posted = await post('/init/ap/post', {})
  ok(/^QC\d{12}$/.test(posted.postNo) && posted.lineCount === 3
    && num(posted.totalAmount) === 170 && num(posted.totalPrepayAmount) === 100,
    `建账批号 ${posted.postNo}：3 行，应付 170，期初预付 100`)
  const postNo = posted.postNo

  const ap01 = await apRows(N01)
  const ap03 = await apRows(N03)
  const qcap1 = ap01.find(r => r.sourceBill === `QCAP-${postNo}-1`)
  const qcap3 = ap03.find(r => r.sourceBill === `QCAP-${postNo}-3`)
  ok(!!qcap1 && num(qcap1.apAmount) === 100 && num(qcap1.unpaidAmount) === 100,
    `S01 QCAP 应付单 ${qcap1 && qcap1.apNo}：100 未付，来源号 QCAP-${postNo}-1`)
  ok(!ap01.some(r => r.sourceBill === `QCAP-${postNo}-2`), '仅预付行（seq=2）不生成应付单')
  ok(!!qcap3 && num(qcap3.apAmount) === 70,
    `S03 QCAP 应付单 ${qcap3 && qcap3.apNo}：70，来源号 QCAP-${postNo}-3`)

  const f01 = await flowRows(S01, 'PREPAY')
  const f02 = await flowRows(S02, 'PREPAY')
  const f03 = await flowRows(S03, 'PREPAY')
  const op01 = f01.records.find(r => r.sourceBill === `QCYF-${postNo}-1`)
  const op02 = f02.records.find(r => r.sourceBill === `QCYF-${postNo}-2`)
  ok(!!op01 && op01.bizType === 'PREPAY_OPENING' && num(op01.increaseAmount) === 40
    && String(op01.postDate || '').startsWith(TODAY),
    `S01 QCYF 预付期初流水 +40（${op01 && op01.bizKey || `PPO:${postNo}:1`}），postDate=${TODAY}`)
  ok(!!op02 && op02.bizType === 'PREPAY_OPENING' && num(op02.increaseAmount) === 60,
    'S02 QCYF 预付期初流水 +60（同批号同序号 seq=2）')
  ok(!f03.records.some(r => r.bizType === 'PREPAY_OPENING'), 'S03 仅应付无预付流水')
  ok((await prepayBalance(S01)) === 40 && (await prepayBalance(S02)) === 60
    && (await prepayBalance(S03)) === 0, '预付余额 S01=40 / S02=60 / S03=0')
  ok(probeNum(`SELECT COUNT(*) FROM fin_fund_ledger WHERE source_bill LIKE 'QCYF-${postNo}-%'`) === 0,
    'QCYF 不登记资金流水')
  ok(probeNum(`SELECT COUNT(*) FROM fin_payment_bill WHERE payment_no LIKE 'QCYF-%'`) === 0,
    'QCYF 不生成付款单')

  const postedLines = await initLines({ posted: 'Y' })
  ok(postedLines.length === 3
    && postedLines.every(r => r.posted === 'Y' && r.postNo === postNo)
    && postedLines.filter(r => r.generatedPrepayFlowId).length === 2
    && postedLines.filter(r => r.generatedApNo).length === 2,
    '暂存回写 posted=Y/批号（3 行），QCYF flowId 2 行（S01/S02），QCAP apNo 2 行（S01/S03）')
  st = await initStatus()
  ok(st.posted === true && st.postNo === postNo && num(st.postTotalAmount) === 170
    && num(st.postTotalPrepayAmount) === 100 && st.canPost === false && st.canReverse === true,
    '状态：已建账，应付/预付双合计正确，可反建账（GL 未启用）')

  // ================= 2. 反建账双守卫 → 成功 → 重新过账 =================
  section('2. 反建账守卫：QCAP 侧被 FX 占用拒 / 释放后 QCYF 侧被退款占用拒')
  await expectFail('/init/ap/reverse', { reason: '' }, '原因')
  passed++; console.log('  ✅ 反建账无原因 → 拦截')

  // QCAP 侧占用：对 S01 QCAP 应付做 FX 核销 30（预付 40 足够）
  const fx1 = await fxAudit(S01, N01, qcap1.apNo, 30)
  let msg = await expectFailCapture('/init/ap/reverse', { reason: 'M4验证应被拒' })
  ok(msg.includes('已发生付款核销'), 'QCAP 应付已被 FX 核销 → 整批拒绝：' + msg.slice(0, 60))
  await post('/finance/prepay-writeoff/cancel-audit', { writeoffId: fx1.writeoffId })
  ok((await prepayBalance(S01)) === 40, '反审核 FX 后 S01 预付恢复 40')

  // QCYF 侧占用：S02 预付退款 30（PREPAY_REFUND 下游流水）
  const refund = await post('/finance/payment/create', {
    paymentDate: TODAY, paymentType: 'PREPAY_REFUND',
    counterpartyType: 'SUPPLIER', counterpartyCode: S02, counterpartyName: N02,
    handler: '系统管理员', summary: 'M4期初退款守卫',
    details: [{ fundAccount: fundName, amount: 30, remark: '' }],
  })
  await post('/finance/payment/audit', { paymentId: refund.paymentId })
  ok((await prepayBalance(S02)) === 30, '退款审核后 S02 预付 60→30')
  msg = await expectFailCapture('/init/ap/reverse', { reason: 'M4验证应被拒' })
  ok(msg.includes('期初预付已被'), 'QCYF 期初预付已被退款使用 → 整批拒绝：' + msg.slice(0, 60))
  await post('/finance/payment/cancel-audit', { paymentId: refund.paymentId })
  ok((await prepayBalance(S02)) === 60, '反审核退款后 S02 预付恢复 60')

  section('2b. 下游全部释放 → 反建账成功并回退干净')
  await post('/init/ap/reverse', { reason: 'M4验证：下游已撤销，反建账' })
  passed++; console.log('  ✅ 反建账成功')
  ok((await prepayBalance(S01)) === 0 && (await prepayBalance(S02)) === 0
    && (await prepayBalance(S03)) === 0, '三户预付余额清零')
  ok(probeNum(`SELECT COUNT(*) FROM fin_ap WHERE source_bill LIKE 'QCAP-${postNo}-%'`) === 0,
    'QCAP 应付单物理删除')
  ok(probeNum(`SELECT COUNT(*) FROM fin_supplier_account_flow WHERE biz_key LIKE 'PPO:${postNo}:%'`) === 0,
    'QCYF 期初流水物理删除（余额链重建）')
  ok(probeOne(`SELECT status FROM biz_init_post WHERE post_no='${postNo}'`) === 'REVERSED',
    'biz_init_post 批号状态 REVERSED')
  st = await initStatus()
  ok(st.posted === false && st.canPost === true && st.lineCount === 3, '暂存回退未建账、可重新建账')

  section('2c. 重新过账（供 GL 引入/补录/日结使用）')
  const posted2 = await post('/init/ap/post', {})
  ok(/^QC\d{12}$/.test(posted2.postNo) && posted2.postNo !== postNo
    && num(posted2.totalPrepayAmount) === 100,
    `重新建账新批号 ${posted2.postNo}，预付合计仍为 100`)
  const postNo2 = posted2.postNo
  ok((await prepayBalance(S01)) === 40 && (await prepayBalance(S02)) === 60,
    '重新建账后预付恢复 40/60')
  const ap01b = await apRows(N01)
  const qcap1b = ap01b.find(r => r.sourceBill === `QCAP-${postNo2}-1`)
  ok(!!qcap1b && num(qcap1b.unpaidAmount) === 100, `新 QCAP 应付单 ${qcap1b && qcap1b.apNo} 100 未付`)

  // ================= 3. GL：一键引入 1123 预付 → 清试算 → 启用总账 =================
  section('3. 总账一键引入：AP→2202 / 预付→1123（借/供应商辅助）')
  const biz = await post('/finance/gl/init/business-import', {})
  ok(num(biz.apCount) === 3 && Math.abs(num(biz.apTotal) - 220) < 0.005,
    `引入应付 3 户/220（S01 100+S03 70+守卫户 50；负应付 -30 被排除；实际 ${biz.apCount}/${biz.apTotal}）`)
  ok(num(biz.prepayCount) === 2 && Math.abs(num(biz.prepayTotal) - 100) < 0.005,
    `引入预付 2 户/100（S01 40+S02 60；实际 ${biz.prepayCount}/${biz.prepayTotal}）`)
  const aux1123 = await post('/finance/gl/init/aux-balance-list', { accountCode: '1123' })
  const ax1 = aux1123.find(r => r.auxSupplier === S01 || r.auxSupplier === N01)
  const ax2 = aux1123.find(r => r.auxSupplier === S02 || r.auxSupplier === N02)
  ok(!!ax1 && num(ax1.openDebit) === 40 && !!ax2 && num(ax2.openDebit) === 60,
    '1123 供应商辅助期初：S01 借40、S02 借60')

  // 验证库恢复空试算后启用（生产走正常期初流程）
  runShell('DELETE FROM fin_init_balance')
  try {
    await post('/finance/gl/init/enable', { startPeriod: '202609' })
  } catch (e) {
    if (!String(e.message).includes('已启用')) throw e
  }
  passed++; console.log('  ✅ 总账启用成功（202609 进行中）')
  await sleep(300)
  const evAll = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 200 })
  ok(!(evAll.records || []).some(e => JSON.stringify(e).includes('QCYF-')),
    'QCYF 期初预付不发 GL 事件（期初不造凭证）')

  // ================= 4. 已上线预付补录：多批/月结守卫/批号归属/整批反建账 =================
  section('4. 预付补录：导入 3 有效 4 错误，状态含批号历史/累计预付/负应付名单')
  let ss = await supStatus()
  ok(ss.glInitialized === true && ss.glMonthClosed === false && ss.todayClosed === false
    && ss.lineCount === 0 && Array.isArray(ss.batches) && ss.batches.length === 0,
    '补录状态：GL 已启用/当月未结/当日未封/无历史批号')
  const supImp = await post('/init/ap/prepay-supplement/import', {
    rows: [
      { supplierCode: S01, prepayAmount: '25', remark: 'M4补录混合户' },
      { supplierCode: SNEG, prepayAmount: '70', remark: 'M4补录负应付户' },
      { supplierCode: SB2, prepayAmount: '15', remark: 'M4补录二批户' },
      { supplierCode: 'S-M4-NOEX', prepayAmount: '10' },       // 不存在
      { supplierCode: S01, prepayAmount: '5' },                // 文件内同供应商多行
      { supplierCode: SB2, prepayAmount: '0' },                // 金额为 0
      { supplierCode: S02, prepayAmount: '-5' },               // 负数
    ],
    fileName: 'M4预付补录.xlsx',
  })
  ok(supImp.inserted === 3 && supImp.failed === 4,
    `补录导入成功3/失败4（实际 ${supImp.inserted}/${supImp.failed}）`)
  const supErrs = await supLines({ lineStatus: 'ERROR' })
  ok(supErrs.length === 4, '补录错误行 4 行可见')
  for (const r of supErrs) await post('/init/ap/prepay-supplement/line/delete', { lineId: r.lineId })
  ss = await supStatus()
  ok(ss.lineCount === 3 && num(ss.totalPrepayAmount) === 110 && ss.canPost === true,
    '补录暂存：3 行合计 110，可过账')

  section('4b. 总账月结守卫：202609 置「已结账」→ 状态/过账均拦，复原后放行')
  runShell("UPDATE fin_accounting_period SET status='已结账' WHERE period='202609'")
  ss = await supStatus()
  ok(ss.glMonthClosed === true && String(ss.glBlockReason).includes('已结账') && ss.canPost === false,
    '状态识别 202609 已结账：canPost=false，原因「' + ss.glBlockReason + '」')
  await expectFail('/init/ap/prepay-supplement/post', {}, '已结账')
  passed++; console.log('  ✅ 已结账月份补录过账 → 拦截')
  runShell("UPDATE fin_accounting_period SET status='进行中' WHERE period='202609'")
  ss = await supStatus()
  ok(ss.glMonthClosed === false && ss.canPost === true, '期间复原「进行中」→ canPost=true')

  section('4c. 第一批补录过账：独立批号 AP_PREPAY，不锁定，可继续补录')
  const b1 = await post('/init/ap/prepay-supplement/post', {})
  ok(/^QC\d{12}$/.test(b1.postNo) && b1.lineCount === 3 && num(b1.totalPrepayAmount) === 110,
    `补录第一批 ${b1.postNo}：3 行 110`)
  const bf01 = (await flowRows(S01, 'PREPAY')).records.find(r => r.sourceBill === `QCYF-${b1.postNo}-1`)
  const bfNeg = (await flowRows(SNEG, 'PREPAY')).records.find(r => r.sourceBill === `QCYF-${b1.postNo}-2`)
  ok(!!bf01 && num(bf01.increaseAmount) === 25 && bf01.bizType === 'PREPAY_OPENING',
    `S01 补录 QCYF-${b1.postNo}-1 +25`)
  ok(!!bfNeg && num(bfNeg.increaseAmount) === 70, `负应付户补录 QCYF-${b1.postNo}-2 +70`)
  ok((await prepayBalance(S01)) === 65 && (await prepayBalance(SNEG)) === 70
    && (await prepayBalance(SB2)) === 15, '补录后预付：S01=65（40+25）/ NEG=70 / B2=15')
  ok(probeOne(`SELECT init_type FROM biz_init_post WHERE post_no='${b1.postNo}'`) === 'AP_PREPAY',
    'biz_init_post.init_type=AP_PREPAY')
  ss = await supStatus()
  const batch1 = ss.batches.find(b => b.postNo === b1.postNo)
  ok(!!batch1 && batch1.status === 'POSTED' && num(batch1.totalAmount) === 110,
    '批号历史含第一批 POSTED/110')
  const so01 = ss.supplierOpening.find(r => r.supplierCode === S01)
  ok(!!so01 && num(so01.openingAmount) === 65, '供应商累计预付：S01=65（期初40+补录25）')
  ok((ss.negativeAp || []).some(r => r.supplier === NNEG && num(r.unpaidAmount) === -30),
    '负应付黄名单含 M4负应付户 -30')

  // 第二批：补录不设建账标志，可继续
  await post('/init/ap/prepay-supplement/line/save', { supplierCode: SB2, prepayAmount: '10' })
  const b2 = await post('/init/ap/prepay-supplement/post', {})
  ok(b2.postNo !== b1.postNo && b2.lineCount === 1 && num(b2.totalPrepayAmount) === 10,
    `补录第二批 ${b2.postNo}：1 行 10（不锁定，新批号）`)
  ok((await prepayBalance(SB2)) === 25, 'B2 预付 15→25')

  section('4d. 整批反建账：无原因/批号归属/下游占用/重复反建账守卫')
  await expectFail('/init/ap/prepay-supplement/reverse', { reason: '' }, '原因')
  passed++; console.log('  ✅ 补录反建账无原因 → 拦截')
  await expectFail('/init/ap/prepay-supplement/reverse',
    { postNo: 'QC999901010001', reason: 'M4验证' }, '不是预付补录批号')
  passed++; console.log('  ✅ 不存在批号 → 拦截')
  // 拿 AP 期初批号反补录 → 批号归属校验
  await expectFail('/init/ap/prepay-supplement/reverse',
    { postNo: postNo2, reason: 'M4验证' }, '不是预付补录批号')
  passed++; console.log('  ✅ 拿 AP 期初批号反补录 → 归属校验拦截')

  // 第一批下游占用：对 S01 新 QCAP 单 FX 核销 25（补录部分）
  const fx2 = await fxAudit(S01, N01, qcap1b.apNo, 25, 'FX-M4-B1')
  msg = await expectFailCapture('/init/ap/prepay-supplement/reverse',
    { postNo: b1.postNo, reason: 'M4验证应被拒' })
  ok(msg.includes('期初预付已被'), `第一批补录预付被 FX 占用 → 整批拒绝：${msg.slice(0, 70)}`)
  await post('/finance/prepay-writeoff/cancel-audit', { writeoffId: fx2.writeoffId })
  ok((await prepayBalance(S01)) === 65, '反审核后 S01 预付恢复 65')

  // 第二批干净 → 反建账成功；重复拒绝
  await post('/init/ap/prepay-supplement/reverse', { postNo: b2.postNo, reason: 'M4验证：撤销第二批' })
  passed++; console.log('  ✅ 第二批补录整批反建账成功')
  ok((await prepayBalance(SB2)) === 15, 'B2 预付回退 25→15')
  ss = await supStatus()
  const batch2 = ss.batches.find(b => b.postNo === b2.postNo)
  ok(!!batch2 && batch2.status === 'REVERSED', '批号历史第二批状态 REVERSED')
  await expectFail('/init/ap/prepay-supplement/reverse',
    { postNo: b2.postNo, reason: 'M4验证重复' }, '已反建账')
  passed++; console.log('  ✅ REVERSED 批号重复反建账 → 拦截')
  // 第一批保持生效（postNo b1：S01 65 / NEG 70）

  // ================= 5. 日结：AP 台账三列 + 向导五项 + 封账守卫 =================
  section('5. 向导第5步应付勾稽：硬勾稽平 + 预付分列/重分类/双计提示')
  const wz = await post('/finance/day-close/wizard', { date: TODAY })
  const tie = wz.step5.ap
  // current：QCAP 170 + 夹具 50 -30 = 190；FX 均已反审核，settled=0；全部 bill_date=当日
  ok(Math.abs(num(tie.current) - 190) < 0.005 && Math.abs(num(tie.added) - 190) < 0.005
    && Math.abs(num(tie.settled)) < 0.005 && Math.abs(num(tie.diff)) < 0.005,
    `应付硬勾稽平：prior ${tie.prior}/added ${tie.added}/settled ${tie.settled}/current ${tie.current}/diff ${tie.diff}`)
  ok(Math.abs(num(tie.prepayAccountTotal) - 210) < 0.005,
    `预付账户余额合计=210（65+60+70+15；实际 ${tie.prepayAccountTotal}）`)
  ok(Math.abs(num(tie.expenseAccountTotal)) < 0.005,
    `费用账户余额合计=0（夹具 JF 直插不写账户流水；实际 ${tie.expenseAccountTotal}）`)
  ok(Math.abs(num(tie.prepayReclassTotal) - 30) < 0.005,
    `负应付重分类合计=30（实际 ${tie.prepayReclassTotal}）`)
  const diffRow = (tie.prepayReclassDiffs || []).find(r => r.supplier === NNEG)
  ok(!!diffRow && num(diffRow.reclassAmount) === 30, '重分类黄名单：M4负应付户 30.00')
  const dcRow = (tie.prepayDoubleCount || []).find(r => r.supplier === NNEG)
  ok(!!dcRow && num(dcRow.reclassAmount) === 30 && num(dcRow.prepayAccountBalance) === 70,
    '双计红名单：M4负应付户 重分类30 + 账户预付70')

  section('5b. 执行日结并校验 AP 定版台账三列')
  const closeRes = await post('/finance/day-close/close', {
    date: TODAY, openingConfirmed: true, acknowledgeHanging: true,
    acknowledgeAnomaly: true, cashCounts: {},
  })
  ok(!!closeRes.closeNo, '日结完成：' + closeRes.closeNo + (closeRes.idempotent ? '（幂等回显）' : ''))
  const apDaily = await post('/finance/day-close/ap-daily/page', {
    pageNo: 1, pageSize: 200, filters: { from: TODAY, to: TODAY },
  })
  const dRecs = apDaily.records || []
  const dFind = code => dRecs.find(r => r.supplierCode === code)
  const dNEG = dFind(SNEG)
  ok(!!dNEG && num(dNEG.apAmount) === -30 && num(dNEG.unpaidAmount) === 0
    && num(dNEG.prepaidAmount) === 30 && num(dNEG.prepayAccountBalance) === 70
    && dNEG.expenseAccountBalance !== undefined,
    '负应付户：apAmount -30 / unpaid 0 / prepaid 重分类 30 / 预付账户列 70 / 费用账户列存在')
  const d02 = dFind(S02)
  ok(!!d02 && num(d02.billCount) === 0 && num(d02.prepayAccountBalance) === 60,
    'S02 无未结 AP 仅有预付：台账仍出行，billCount=0、预付账户列 60')
  const dB2 = dFind(SB2)
  ok(!!dB2 && num(dB2.billCount) === 0 && num(dB2.prepayAccountBalance) === 15,
    'B2 纯预付户出行：billCount=0、预付账户列 15')
  const d01 = dFind(S01)
  ok(!!d01 && num(d01.unpaidAmount) === 100 && num(d01.prepayAccountBalance) === 65,
    'S01：应付 100、预付账户列 65（分列不轧差）')
  const dAP = dFind(SAP)
  ok(!!dAP && num(dAP.unpaidAmount) === 50 && num(dAP.prepayAccountBalance) === 0,
    '守卫户 AP 50 正常入台账')

  section('5c. 封账日守卫：补录过账/反建账、应付期初导入均拦截')
  await post('/init/ap/prepay-supplement/line/save', { supplierCode: SB2, prepayAmount: '5' })
  await expectFail('/init/ap/prepay-supplement/post', {}, ['封账'])
  passed++; console.log('  ✅ 封账日补录过账 → 拦截（暂存保存不拦，只拦过账）')
  await expectFail('/init/ap/prepay-supplement/reverse',
    { postNo: b1.postNo, reason: 'M4封账验证' }, ['封账'])
  passed++; console.log('  ✅ 封账日补录反建账 → 拦截')
  // AP 期初已建账且 GL 启用后不可反建账：导入先撞「建账锁定」守卫（封账守卫在其之前不可达），任一拦截均可
  await expectFail('/init/ap/import',
    { rows: [{ supplierCode: S03, apAmount: '5' }], fileName: 'M4封账.xlsx' }, ['锁定', '日结', '封账'])
  passed++; console.log('  ✅ 封账日应付期初导入 → 拦截（已建账户由建账锁守卫优先拦截）')

  await post('/finance/day-close/reopen', { date: TODAY, reason: 'PRD-36 M4 反日结继续验收' })
  const staleLine = (await supLines({ lineStatus: 'VALID' })).find(r => num(r.prepayAmount) === 5)
  if (staleLine) await post('/init/ap/prepay-supplement/line/delete', { lineId: staleLine.lineId })
  ss = await supStatus()
  // 二批已反建账，其暂存行回退为 posted='N'（B2 10 元）仍在暂存；一批 3 行保持 posted='Y'
  const openSup = await supLines({ posted: 'N', lineStatus: 'VALID' })
  ok(ss.todayClosed === false && ss.lineCount === 1 && openSup.length === 1
    && openSup[0].supplierCode === SB2 && num(openSup[0].prepayAmount) === 10,
    '反日结后补录状态恢复：未封账；封账日 5 元暂存行已删，未过账暂存仅余二批反建账回退行（B2 10）')

  // ================= 6. 业财对账五组 =================
  section('6. 业财对账：ar/ap/prepay/factoryExpense/cash 五组，预付与厂家费用 advisory')
  const recon = await post('/finance/gl/report/reconcile', { period: '202609' })
  ok(recon.period === '202609' && Array.isArray(recon.groups) && recon.groups.length === 5,
    '对账返回五组：' + recon.groups.map(g => g.key).join('/'))
  ok(recon.groups.map(g => g.key).join(',') === 'ar,ap,prepay,factoryExpense,cash',
    '五组顺序 ar → ap → prepay → factoryExpense → cash')
  const gPrepay = recon.groups.find(g => g.key === 'prepay')
  const gFe = recon.groups.find(g => g.key === 'factoryExpense')
  ok(gPrepay.advisory === true, '预付组 advisory=true（提示性，不平不翻 hardMatched）')
  ok(gFe.advisory === true, '厂家费用组 advisory=true')
  for (const k of ['note', 'glOutstanding', 'bizOutstanding', 'outstandingDiff', 'glCreditYtd', 'bizSettled']) {
    ok(gFe[k] !== undefined, `厂家费用组含字段 ${k}`)
  }
  ok(typeof recon.matched === 'boolean' && typeof recon.hardMatched === 'boolean',
    `总标志 matched=${recon.matched} / hardMatched=${recon.hardMatched}（结构断言）`)

  // ================= 7. 供应商停用/删除五守卫 =================
  section('7. 供应商停用五守卫（三余额/未结AP/未兑JF/未作废DX/未作废FX）')
  msg = await expectFailCapture('/base/supplier/stop', { supplierCode: S01 })
  ok(msg.includes('供应商账户仍有余额') && msg.includes('未结清应付单'),
    'S01 同时命中余额+未结 AP 双原因：' + msg.replace(/\s+/g, ' ').slice(0, 90))
  await expectFail('/base/supplier/stop', { supplierCode: SAP }, '存在 1 张未结清应付单')
  passed++; console.log('  ✅ SAP 未结 AP → 停用拒')
  await expectFail('/base/supplier/stop', { supplierCode: SJF }, '已审核未全部兑现的厂家费用单')
  passed++; console.log('  ✅ SJF 已审未兑 JF → 停用拒')
  await expectFail('/base/supplier/stop', { supplierCode: SDX }, '未作废的厂家费用兑现单')
  passed++; console.log('  ✅ SDX PENDING DX → 停用拒')
  await expectFail('/base/supplier/stop', { supplierCode: SFX }, '未作废的预付核销单')
  passed++; console.log('  ✅ SFX PENDING FX → 停用拒')
  await expectFail('/base/supplier/delete', { supplierCode: SNEG }, '供应商账户仍有余额')
  passed++; console.log('  ✅ SNEG 预付 70（重分类户）→ 删除拒')
  // 守卫不改动任何数据
  const still = await post('/base/supplier/page', { pageNo: 1, pageSize: 200, filters: { keyword: SAP } })
  ok((still.records || []).some(r => r.supplierCode === SAP && r.status === 'NORMAL'),
    '被拒供应商仍为 NORMAL（停用未生效）')

  section('7b. 干净户：停用 → 启用 → 删除全通')
  await post('/base/supplier/stop', { supplierCode: SOK })
  let okRow = (await post('/base/supplier/page', { pageNo: 1, pageSize: 50, filters: { keyword: SOK } })).records[0]
  ok(okRow && okRow.status === 'STOPPED', '干净户停用成功：STOPPED')
  await post('/base/supplier/update', { supplierCode: SOK, supplierName: NOK, status: '正常' })
  okRow = (await post('/base/supplier/page', { pageNo: 1, pageSize: 50, filters: { keyword: SOK } })).records[0]
  ok(okRow && okRow.status === 'NORMAL', '干净户重新启用：NORMAL')
  await post('/base/supplier/delete', { supplierCode: SOK })
  okRow = (await post('/base/supplier/page', { pageNo: 1, pageSize: 50, filters: { keyword: SOK } })).records[0]
  ok(!okRow, '干净户删除成功：列表已无')

  // ================= 8. 档案取值 / 字段脱敏 / 401 / 模板 =================
  section('8. 供应商档案三余额取账户值（旧列停用）')
  const page01 = (await post('/base/supplier/page', { pageNo: 1, pageSize: 200, filters: { keyword: S01 } })).records[0]
  ok(!!page01 && num(page01.apBalance) === 100 && num(page01.prepayBalance) === 65
    && num(page01.expenseBalance) === 0,
    `分页行：apBalance=100（QCAP 账户值）/ prepayBalance=65 / expenseBalance=0`)
  const det01 = await get('/base/supplier/detail?code=' + S01)
  ok(num(det01.apBalance) === 100 && num(det01.prepayBalance) === 65 && num(det01.expenseBalance) === 0,
    '详情接口三余额与账户一致')

  section('8b. VIEW_AP_BALANCE 字段权限：复制管理员角色去掉该字段，新用户三余额置 null')
  const roles = await post('/system/rbac/role/page', { pageNo: 1, pageSize: 200 })
  // 精确选系统管理员（copy 会克隆其菜单/功能/字段/数据范围四区；isSystem 序列化不可靠，不做布尔判断）
  const adminRole = (roles.records || []).find(r => r.roleCode === 'SYS_ADMIN')
  assert(adminRole, '找不到 SYS_ADMIN 角色')
  const roleCode = 'ROLE_M4_FIELD'
  let roleId
  try {
    const cp = await post('/system/rbac/role/copy', {
      sourceRoleId: adminRole.roleId, newRoleCode: roleCode, newRoleName: 'M4字段权限测试角色',
    })
    roleId = cp.roleId
  } catch (e) {
    const exist = (roles.records || []).find(r => r.roleCode === roleCode)
    if (!exist) throw e
    roleId = exist.roleId
  }
  const srcGrant = await get('/system/rbac/role/' + roleId)
  const fieldCodes = (srcGrant.fieldCodes || []).filter(c => c !== 'VIEW_AP_BALANCE')
  ok(!fieldCodes.includes('VIEW_AP_BALANCE'), '复制角色已剔除 VIEW_AP_BALANCE（源角色 '
    + (srcGrant.fieldCodes || []).length + ' 字段 → ' + fieldCodes.length + '）')
  // /grants 全量替换菜单时拒绝 admin_only=TRUE 的超管专属菜单（copy 可克隆、回授会拦），先剔除
  const adminOnlyMenus = runShell('SELECT menu_id FROM sys_menu_meta WHERE admin_only=TRUE')
    .split(/\r?\n/).map(s => s.trim()).filter(Boolean).slice(1)
    .map(s => s.split('|')[0].trim())
  const menuIds = (srcGrant.menuIds || []).filter(m => !adminOnlyMenus.includes(m))
  await post('/system/rbac/role/grants', {
    roleId, menuIds, funcCodes: srcGrant.funcCodes || [],
    fieldCodes, dataScopes: srcGrant.dataScopes || [],
  })
  // 隔离库 sys_menu_meta 默认可用标志 ENABLED 多为 FALSE（超管走服务端短路不受影响），
  // 普通角色用户 /system/menu/user-tree 只返回 ENABLED=TRUE 菜单，不启用则前端按零菜单裁剪路由。
  // 启用本角色已授权的全部 NORMAL 菜单，模拟一份「去掉 VIEW_AP_BALANCE 的可用管理员副本」。
  runShell("UPDATE sys_menu_meta SET enabled=TRUE WHERE status='NORMAL' AND menu_id IN ("
    + "SELECT menu_id FROM sys_role_menu_rel WHERE role_id='" + roleId.replace(/'/g, "''") + "')")
  try {
    await post('/system/rbac/user/create', {
      username: 'm4field', displayName: 'M4字段用户', password: 'M4test@123',
      roleIds: [roleId], mustChangePwd: false, status: 'NORMAL',
    })
  } catch (e) {
    if (!String(e.message).includes('已存在')) throw e
  }
  const maskedLogin = await fetch(BASE + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'm4field', password: 'M4test@123' }),
  }).then(r => r.json())
  assert(maskedLogin.code === '0' && maskedLogin.data.token, 'm4field 登录成功')
  const maskedPage = await fetch(BASE + '/base/supplier/page', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + maskedLogin.data.token },
    body: JSON.stringify({ pageNo: 1, pageSize: 200, filters: { keyword: S01 } }),
  }).then(r => r.json())
  assert(maskedPage.code === '0', '脱敏用户可查供应商分页（有 base.supplier.view 功能码）')
  const mRow = (maskedPage.data.records || []).find(r => r.supplierCode === S01)
  ok(!!mRow && mRow.apBalance === null && mRow.prepayBalance === null && mRow.expenseBalance === null,
    '无 VIEW_AP_BALANCE：三余额全部置 null（供应商名称等非敏感字段正常）')
  // admin 仍可见
  const adminPage = (await post('/base/supplier/page', { pageNo: 1, pageSize: 50, filters: { keyword: S01 } })).records[0]
  ok(num(adminPage.apBalance) === 100, 'admin 短路不受脱敏影响：apBalance=100')

  section('8c. 无令牌 401 + 两个导入模板可下载')
  for (const url of ['/init/ap/status', '/init/ap/prepay-supplement/status']) {
    const noAuth = await fetch(BASE + url, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
    }).then(r => r.json()).catch(() => ({ code: 'NETERR' }))
    ok(noAuth.code !== '0', '无令牌 ' + url + ' 被拒（code=' + noAuth.code + '）')
  }
  const token = await fetch(BASE + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  }).then(r => r.json()).then(j => j.data.token)
  for (const tpl of ['/init/ap/import-template', '/init/ap/prepay-supplement/import-template']) {
    const res = await fetch(BASE + tpl, { headers: { Authorization: 'Bearer ' + token } })
    const buf = Buffer.from(await res.arrayBuffer())
    ok(res.status === 200 && buf.length > 2000,
      `模板下载 ${tpl}：HTTP ${res.status}，${buf.length} 字节`)
  }

  console.log(`\n🎉 PRD-36 M4（V131）全部通过：${passed} 项断言`)
}

main().catch(e => {
  console.error('\n❌ 验收失败：' + (e && e.message))
  console.error(e && e.stack)
  process.exit(1)
})
