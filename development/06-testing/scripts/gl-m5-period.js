/**
 * 总账 M5（PRD-31）冒烟测试：期末处理——自动转账、结转损益、结账四检查、反结账、年结冻结
 *
 * 用法（专用冒烟库，必须在 backend/ 目录启动）：
 *   java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8081 \
 *     --spring.datasource.url="jdbc:h2:file:./data/erp-smoke-gl5;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     --spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,...
 *   node development/06-testing/scripts/gl-m5-period.js
 *
 * 覆盖（启用期 202611，前 10 期由启用流程批量结账）：
 *  1. 四检查拦截：草稿未过账拦「全部过账」；过账后损益未结转拦「损益结转」；
 *     JZ 草稿未过账再拦「全部过账」；反结账后跨期结账拦「上期已结」；
 *     「借贷平衡」恒过（凭证保存即强制平衡，断言通过且金额正确）；
 *  2. 结转损益 JZ{period} 幂等：202611 费用 100 → 借 3103 100 / 贷 560201 100，重复生成被拒；
 *  3. 反结账：原因必填（空/过短被拒）、仅最后一个已结账期间、成功后 JZ 回退草稿（凭证号清空）；
 *  4. 自动转账 ZZ：202612 销项 260/进项 130 → ZZ01 结转未交增值税 130，
 *     ZZ02 计提附加税费 15.60=9.10+3.90+2.60；零金额/条件不满足自动跳过；重复执行幂等（exists）；
 *  5. 年结：202612 结账 → 已冻结，自动生成 2027 年 12 期且 202701 进行中；
 *     冻结期间反结账被拒、冻结后不能反结以前期间。
 */
const BASE = process.env.API_BASE || 'http://localhost:8081/api'

let authToken = 'demo-token'

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
function assert(condition, message) {
  if (!condition) throw new Error(message)
}
/** 预期失败：调用应抛错且错误信息包含 keyword。 */
async function expectFail(fn, keyword, label) {
  let err = null
  try { await fn() } catch (e) { err = e }
  assert(err, `${label} 应失败但成功了`)
  assert(String(err.message).includes(keyword), `${label} 应提示「${keyword}」，实际：${err.message}`)
}
const num = v => Number(v || 0)
const round2 = v => Math.round(num(v) * 100) / 100

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

// ==================== 业务辅助 ====================

async function wizard(period) {
  return post('/finance/gl/period/wizard', { period })
}
function stepOf(wz, no) {
  const s = wz.steps.find(x => x.no === no)
  assert(s, `向导缺第 ${no} 步`)
  return s
}
function checksOf(wz) {
  return stepOf(wz, 6).data
}
function checkByName(wz, name) {
  const c = checksOf(wz).find(x => x.name === name)
  assert(c, `检查项缺失：${name}`)
  return c
}

/** 保存手工凭证（记字），返回凭证详情。entries: [{accountCode, debit?, credit?, summary?, auxDepartment?}] */
async function saveVoucher(date, summary, entries) {
  const rows = entries.map(e => ({
    accountCode: e.accountCode,
    debitAmount: e.debit || 0,
    creditAmount: e.credit || 0,
    summary: e.summary || summary,
    auxCustomer: e.auxCustomer || '',
    auxSupplier: e.auxSupplier || '',
    auxDepartment: e.auxDepartment || '',
    auxEmployee: e.auxEmployee || '',
    auxGoods: e.auxGoods || '',
    auxProject: e.auxProject || '',
    auxArea: e.auxArea || '',
    cashFlowItem: e.cashFlowItem || '',
  }))
  return post('/finance/gl/voucher/save', { voucherWord: '记', voucherDate: date, summary, entries: rows })
}
async function auditPost(id) {
  await post('/finance/gl/voucher/audit', { id })
  await post('/finance/gl/voucher/post', { id })
  return post('/finance/gl/voucher/detail', { id })
}
async function closeExpectFail(period, keyword, label) {
  await expectFail(() => post('/finance/gl/period/close', { period }), keyword, label)
}
/** 找凭证分录中某科目金额（direction: 'debit'|'credit'，可多条求和）。 */
function entrySum(v, accountCode, direction) {
  const key = direction === 'debit' ? 'debitAmount' : 'creditAmount'
  return round2((v.entries || []).filter(e => e.accountCode === accountCode).reduce((s, e) => s + num(e[key]), 0))
}

async function main() {
  await login()

  // ===== 1. 菜单含期末处理 =====
  const menus = await get('/system/menu/user-tree')
  const glJson = JSON.stringify(menus.find(m => m.name === '总账管理' || m.menuName === '总账管理') || {})
  assert(glJson.includes('glPeriodClose'), '总账菜单应含 glPeriodClose')

  // ===== 2. 启用总账（启用期 202611：01-10 已结账，11 进行中，12 未开始）=====
  await post('/finance/gl/init/balance-save', { rows: [
    { accountCode: '1001', openDebit: 100000 },
    { accountCode: '3001', openCredit: 100000 },
  ] })
  await post('/finance/gl/init/enable', { startPeriod: '202611' })

  const periodList = await post('/finance/gl/period/list', {})
  assert(periodList.find(p => p.period === '202611')?.status === '进行中', '202611 应为进行中')
  assert(periodList.find(p => p.period === '202610')?.status === '已结账', '202610 应为已结账（启用前期）')
  assert(periodList.find(p => p.period === '202612')?.status === '未开始', '202612 应为未开始')

  // ===== 3. 向导初始状态：8 步齐；无资产折旧③通过、业财对账⑧为非阻断查询项跳过 =====
  let wz = await wizard('202611')
  assert(wz.steps.length === 8, '向导应有 8 步')
  assert(stepOf(wz, 3).status === 'pass', '③ 无使用中资产时折旧计提应通过（无需计提）')
  assert(stepOf(wz, 8).status === 'skip', '⑧ 业财对账为非阻断项（结账后可随时查询）')
  assert(stepOf(wz, 1).status === 'pass', '① 事件检查应通过（无业务事件）')

  // ===== 4. 202611：草稿凭证 → 拦「全部过账」 =====
  const v1 = await saveVoucher('2026-11-15', '报销办公费', [
    { accountCode: '560201', debit: 100, auxDepartment: 'D01', summary: '办公费' },
    { accountCode: '1001', credit: 100, cashFlowItem: 'CF07', summary: '现金支付' },
  ])
  assert(v1.status === '草稿', '新凭证应为草稿')
  wz = await wizard('202611')
  assert(checkByName(wz, '全部过账').passed === false, '存在草稿时「全部过账」应不通过')
  await closeExpectFail('202611', '全部过账', '有草稿凭证时结账')

  // ===== 5. 过账后 → 拦「损益结转」；借贷平衡恒过 =====
  await auditPost(v1.id)
  wz = await wizard('202611')
  assert(checkByName(wz, '全部过账').passed === true, '凭证过账后「全部过账」应通过')
  assert(checkByName(wz, '借贷平衡').passed === true, '「借贷平衡」应通过（发生额借贷相等）')
  assert(checkByName(wz, '借贷平衡').detail.includes('100.00'), '平衡检查应展示本期发生额 100.00')
  assert(checkByName(wz, '损益结转').passed === false, '560201 有净发生未结转，「损益结转」应不通过')
  assert(checkByName(wz, '上期已结').passed === true, '上期 202610 已结账，「上期已结」应通过')
  await closeExpectFail('202611', '损益', '损益未结转时结账')

  // ===== 6. 自动转账：202611 无增值税发生 → 两张模板均跳过 =====
  const tp1 = await post('/finance/gl/period/transfer-preview', { period: '202611' })
  const zz01p = tp1.find(t => t.transferNo === 'ZZ01')
  const zz02p = tp1.find(t => t.transferNo === 'ZZ02')
  assert(zz01p.status === 'skip' && zz01p.message.includes('零'), 'ZZ01 无增值税发生应零金额跳过')
  assert(zz02p.status === 'skip', 'ZZ02 条件不满足应跳过')
  const exec1 = await post('/finance/gl/period/transfer-execute', { period: '202611' })
  assert(exec1.results.every(r => r.status === 'skip'), '202611 自动转账应全部跳过')

  // ===== 7. 结转损益：预览金额=净发生，生成 JZ 草稿，重复幂等拦截 =====
  const pp1 = await post('/finance/gl/period/profit-preview', { period: '202611' })
  assert(round2(pp1.totalExpense) === 100, `202611 成本费用合计应为 100，实际 ${pp1.totalExpense}`)
  assert(round2(pp1.netProfit) === -100, `净利润应为 -100，实际 ${pp1.netProfit}`)
  const jz1 = await post('/finance/gl/period/profit-carry', { period: '202611' })
  assert(round2(jz1.totalExpense) === 100 && round2(jz1.netProfit) === -100, 'JZ 返回金额应与预览一致')
  await expectFail(() => post('/finance/gl/period/profit-carry', { period: '202611' }),
    '已生成', '重复结转损益')
  const jz1v = await post('/finance/gl/voucher/detail', { id: jz1.voucherId })
  assert(jz1v.voucherWord === '转', '结转凭证应为「转」字')
  assert(jz1v.source === '结转损益', '结转凭证 source 应为 结转损益')
  assert(jz1v.sourceBillNo === 'JZ202611', '结转凭证来源单号应为 JZ202611')
  assert(jz1v.voucherDate === '2026-11-30', `结转凭证日期应为月末 2026-11-30，实际 ${jz1v.voucherDate}`)
  assert(entrySum(jz1v, '560201', 'credit') === 100, 'JZ 应贷 560201 100（冲平费用）')
  assert(entrySum(jz1v, '3103', 'debit') === 100, 'JZ 应借 3103 100（亏损）')
  assert(entrySum(jz1v, '560201', 'debit') === 0, '560201 不应有借方行')

  // ===== 8. JZ 草稿未过账 → 再拦「全部过账」；过账后四检查全过、结账 =====
  wz = await wizard('202611')
  assert(checkByName(wz, '全部过账').passed === false, 'JZ 草稿未过账时「全部过账」应不通过')
  await closeExpectFail('202611', '全部过账', 'JZ 草稿时结账')
  await auditPost(jz1.voucherId)
  wz = await wizard('202611')
  assert(checksOf(wz).every(c => c.passed), 'JZ 过账后四项检查应全过')
  assert(stepOf(wz, 7).status === 'ready', '⑦ 期末结账应为可结账状态')
  const closed1 = await post('/finance/gl/period/close', { period: '202611' })
  assert(closed1.status === '已结账' && closed1.nextPeriod === '202612', '202611 结账后应开 202612')
  wz = await wizard('202611')
  assert(stepOf(wz, 7).status === 'done', '结账后 ⑦ 应为已完成')
  assert(wz.lastClosed && wz.lastClosed.period === '202611', 'lastClosed 应为 202611')

  // ===== 9. 反结账：原因必填 + 仅最后期间 + 成功留痕回退 =====
  await expectFail(() => post('/finance/gl/period/reopen', { period: '202611' }),
    '原因', '反结账不填原因')
  await expectFail(() => post('/finance/gl/period/reopen', { period: '202611', reason: 'x' }),
    '原因', '反结账原因过短')
  await expectFail(() => post('/finance/gl/period/reopen', { period: '202610', reason: '冒烟测试反结账' }),
    '最后', '反结账非最后期间')
  const ro1 = await post('/finance/gl/period/reopen', { period: '202611', reason: '冒烟测试：补录凭证后重结' })
  assert(ro1.status === '进行中', '反结账后期间应恢复进行中')
  assert(ro1.revertedVouchers >= 1, `应回退 JZ 凭证为草稿，实际 ${ro1.revertedVouchers}`)
  const jz1After = await post('/finance/gl/voucher/detail', { id: jz1.voucherId })
  assert(jz1After.status === '草稿', '反结账后 JZ 应回退草稿')
  assert(!jz1After.voucherNo, '反结账后 JZ 凭证号应清空')

  // ===== 10. 反结账状态下结 202612 → 拦「上期已结」 =====
  await closeExpectFail('202612', '上期', '上期未结账时结账')

  // ===== 11. 重新过账 JZ 并结 202611 =====
  await auditPost(jz1.voucherId)
  const closed1b = await post('/finance/gl/period/close', { period: '202611' })
  assert(closed1b.status === '已结账', '202611 重新结账应成功')

  // ===== 12. 202612：销项 260 / 进项 130 业务 =====
  const sale = await saveVoucher('2026-12-10', '确认销售收入', [
    { accountCode: '1001', debit: 1260, cashFlowItem: 'CF01', summary: '收现' },
    { accountCode: '500101', credit: 1000, auxCustomer: 'CUS-M5', auxGoods: 'G-M5', auxArea: 'AREA-M5', summary: '主营业务收入' },
    { accountCode: '22210102', credit: 260, summary: '销项税额' },
  ])
  await auditPost(sale.id)
  const exp = await saveVoucher('2026-12-12', '报销费用（进项税）', [
    { accountCode: '560201', debit: 870, auxDepartment: 'D01', summary: '办公费' },
    { accountCode: '22210101', debit: 130, summary: '进项税额' },
    { accountCode: '1001', credit: 1000, cashFlowItem: 'CF07', summary: '现金支付' },
  ])
  await auditPost(exp.id)

  // ===== 13. 自动转账预览取数正确 =====
  const tp2 = await post('/finance/gl/period/transfer-preview', { period: '202612' })
  const z1 = tp2.find(t => t.transferNo === 'ZZ01')
  const z2 = tp2.find(t => t.transferNo === 'ZZ02')
  assert(z1.status === 'todo', 'ZZ01 有增值税应贷差应待执行')
  assert(round2(z1.debitTotal) === 130 && round2(z1.creditTotal) === 130, `ZZ01 金额应为 130，实际 ${z1.debitTotal}/${z1.creditTotal}`)
  assert(z1.entries.some(e => e.accountCode === '22210105' && e.direction === '借' && round2(e.amount) === 130), 'ZZ01 应借 22210105 130')
  assert(z1.entries.some(e => e.accountCode === '222102' && e.direction === '贷' && round2(e.amount) === 130), 'ZZ01 应贷 222102 130')
  assert(z2.status === 'todo', 'ZZ02 应待执行')
  assert(round2(z2.debitTotal) === 15.6, `ZZ02 借方合计应为 15.60，实际 ${z2.debitTotal}`)
  assert(z2.entries.some(e => e.accountCode === '222104' && round2(e.amount) === 9.1), 'ZZ02 城建税应为 9.10')
  assert(z2.entries.some(e => e.accountCode === '222105' && round2(e.amount) === 3.9), 'ZZ02 教育费附加应为 3.90')
  assert(z2.entries.some(e => e.accountCode === '222106' && round2(e.amount) === 2.6), 'ZZ02 地方教育附加应为 2.60')

  // ===== 14. 执行 + 幂等 =====
  const exec2 = await post('/finance/gl/period/transfer-execute', { period: '202612' })
  const created = exec2.results.filter(r => r.status === 'created')
  assert(created.length === 2, `应生成 2 张转账凭证，实际 ${created.length}`)
  const exec3 = await post('/finance/gl/period/transfer-execute', { period: '202612' })
  assert(exec3.results.every(r => r.status === 'exists'), '重复执行自动转账应全部幂等跳过')
  const tp3 = await post('/finance/gl/period/transfer-preview', { period: '202612' })
  assert(tp3.every(t => t.status === 'done'), '预览应显示两张模板均已生成')
  const zzVouchers = tp3.map(t => ({ no: t.transferNo, id: t.voucherId }))
  assert(zzVouchers.every(v => v.id), '已生成模板应带凭证 id')
  for (const v of zzVouchers) {
    const d = await post('/finance/gl/voucher/detail', { id: v.id })
    assert(d.source === '自动转账' && d.sourceBillNo === `${v.no}202612`, `${v.no} 凭证来源标识应为 ${v.no}202612`)
    assert(d.voucherWord === '转' && d.voucherDate === '2026-12-31', '转账凭证应为转字、月末日期')
    await auditPost(v.id)
  }

  // ===== 15. 结转损益 202612：收入 1000 / 费用 870+15.60 / 净利 114.40 =====
  const pp2 = await post('/finance/gl/period/profit-preview', { period: '202612' })
  assert(round2(pp2.totalIncome) === 1000, `收入合计应为 1000，实际 ${pp2.totalIncome}`)
  assert(round2(pp2.totalExpense) === 885.6, `成本费用合计应为 885.60，实际 ${pp2.totalExpense}`)
  assert(round2(pp2.netProfit) === 114.4, `净利润应为 114.40，实际 ${pp2.netProfit}`)
  const jz2 = await post('/finance/gl/period/profit-carry', { period: '202612' })
  const jz2v = await post('/finance/gl/voucher/detail', { id: jz2.voucherId })
  assert(entrySum(jz2v, '500101', 'debit') === 1000, 'JZ 应借 500101 1000（冲平收入）')
  assert(entrySum(jz2v, '560201', 'credit') === 870, 'JZ 应贷 560201 870')
  assert(entrySum(jz2v, '5403', 'credit') === 15.6, 'JZ 应贷 5403 15.60')
  assert(entrySum(jz2v, '3103', 'credit') === 1000, 'JZ 应贷 3103 1000（收入转入）')
  assert(entrySum(jz2v, '3103', 'debit') === 885.6, 'JZ 应借 3103 885.60（费用转入）')
  assert(jz2v.sourceBillNo === 'JZ202612' && jz2v.voucherDate === '2026-12-31', 'JZ 标识与日期应为 JZ202612/2026-12-31')
  await auditPost(jz2.voucherId)

  // ===== 16. 年结：202612 → 已冻结，2027 年期间自动建立 =====
  wz = await wizard('202612')
  assert(checksOf(wz).every(c => c.passed), '年结前四检查应全过')
  const closed12 = await post('/finance/gl/period/close', { period: '202612' })
  assert(closed12.status === '已冻结', `12 期结账应为已冻结，实际 ${closed12.status}`)
  assert(closed12.nextPeriod === '202701' && closed12.nextOpened === true, '年结后应自动开 202701')
  const list2 = await post('/finance/gl/period/list', {})
  assert(list2.length === 24, `应有 2026+2027 共 24 个期间，实际 ${list2.length}`)
  assert(list2.find(p => p.period === '202701')?.status === '进行中', '202701 应为进行中')
  assert(list2.find(p => p.period === '202702')?.status === '未开始', '202702 应为未开始')

  // ===== 17. 冻结不可反结账；冻结后不能反结以前期间 =====
  await expectFail(() => post('/finance/gl/period/reopen', { period: '202612', reason: '冒烟尝试反年结' }),
    '冻结', '反结账年度冻结期间')
  await expectFail(() => post('/finance/gl/period/reopen', { period: '202611', reason: '冒烟尝试反以前期间' }),
    '最后', '冻结后反结账以前期间')

  // ===== 18. 新年度向导：上期 202612 冻结视为已结 =====
  wz = await wizard('202701')
  assert(wz.periodStatus === '进行中', '202701 应为进行中')
  assert(checkByName(wz, '上期已结').passed === true, '上期 202612 已冻结，「上期已结」应通过')

  console.log('✅ gl-m5-period 冒烟全部通过：四检查拦截 / JZ 结转幂等 / ZZ 自动转账取数与幂等 / 反结账留痕 / 年结冻结与下年开账')
}

main().catch(e => { console.error('❌ 冒烟失败：', e.message); process.exit(1) })
