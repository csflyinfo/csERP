/**
 * 总账 M2（PRD-31）冒烟测试：手工凭证 + 账簿查询
 *
 * 用法：
 *   1. 后端用空库启动（Flyway 自动建表）：
 *      java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8081 \
 *        --spring.datasource.url="jdbc:h2:file:./data/erp-smoke-gl2;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *        --spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration
 *   2. node development/06-testing/scripts/gl-m2-voucher-ledger.js
 *
 * 覆盖：凭证保存全套校验 → 审核/反审核（占号）→ 过账 → 作废留号 → 红冲（红字直接过账、原单已冲销）
 *      → 期间锁定 → 余额表/明细账/总账/序时账实时聚合数字断言。
 * 注意：本脚本会把空库总账启用至 202609，必须跑在专用冒烟库上。
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
async function expectFail(path, body, keyword) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + authToken },
    body: JSON.stringify(body),
  })
  const json = await res.json()
  if (json.code === '0') throw new Error(`${path} 应当失败但成功了：${JSON.stringify(body)}`)
  if (keyword && !String(json.message).includes(keyword)) {
    throw new Error(`${path} 报错信息不含「${keyword}」：${json.message}`)
  }
  return json.message
}
function assert(condition, message) {
  if (!condition) throw new Error(message)
}
const num = v => Number(v || 0)

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

/** 标准 2 条分录凭证：办公费 1000 / 库存现金 1000（560201 挂部门辅助）。 */
function officeExpenseVoucher(amount = 1000, opts = {}) {
  return {
    voucherWord: '记',
    voucherDate: opts.date || '2026-09-07',
    summary: '办公费支出',
    entries: [
      { summary: '办公费', accountCode: '560201', debitAmount: amount, creditAmount: 0, auxDepartment: 'D001' },
      { summary: '付现', accountCode: '1001', debitAmount: 0, creditAmount: amount, cashFlowItem: 'CF07' },
    ],
  }
}

async function main() {
  await login()

  // ===== 1. 菜单 =====
  const menus = await get('/system/menu/user-tree')
  const glGroup = menus.find(m => m.name === '总账管理' || m.menuName === '总账管理')
  assert(glGroup && JSON.stringify(glGroup).includes('glVoucher') && JSON.stringify(glGroup).includes('glLedger'),
    '总账菜单应含 glVoucher/glLedger')

  // ===== 2. 未启用拦截 =====
  await expectFail('/finance/gl/voucher/save', officeExpenseVoucher(), '尚未启用')

  // ===== 3. 启用总账：1001 借 100000 / 3001 贷 100000 =====
  await post('/finance/gl/init/balance-save', { rows: [
    { accountCode: '1001', openDebit: 100000 },
    { accountCode: '3001', openCredit: 100000 },
  ] })
  const trial = await post('/finance/gl/init/trial-balance')
  assert(num(trial.totalDebit) === 100000 && num(trial.totalCredit) === 100000, `试算应 10 万平衡，实际 ${trial.totalDebit}/${trial.totalCredit}`)
  await post('/finance/gl/init/enable', { startPeriod: '202609' })

  // 辅助下拉
  const auxOpts = await post('/finance/gl/voucher/aux-options')
  assert(auxOpts.cashFlowItems.length === 20, `现金流量项目应 20 个，实际 ${auxOpts.cashFlowItems.length}`)

  // ===== 4. 保存校验 =====
  await expectFail('/finance/gl/voucher/save', {
    voucherDate: '2026-09-07', entries: [{ accountCode: '1001', debitAmount: 100 }],
  }, '至少需要 2 条')
  await expectFail('/finance/gl/voucher/save', {
    voucherDate: '2026-09-07', entries: [
      { accountCode: '1001', debitAmount: 100, creditAmount: 0 },
      { accountCode: '3001', debitAmount: 0, creditAmount: 99 },
    ],
  }, '借贷不平衡')
  await expectFail('/finance/gl/voucher/save', {
    voucherDate: '2026-09-07', entries: [
      { accountCode: '1002', debitAmount: 100, creditAmount: 0 },
      { accountCode: '1001', debitAmount: 0, creditAmount: 100 },
    ],
  }, '末级启用科目')
  await expectFail('/finance/gl/voucher/save', {
    voucherDate: '2026-09-07', entries: [
      { accountCode: '1122', debitAmount: 100, creditAmount: 0 },
      { accountCode: '1001', debitAmount: 0, creditAmount: 100 },
    ],
  }, '辅助核算：客户')
  await expectFail('/finance/gl/voucher/save', {
    voucherDate: '2026-09-07', entries: [
      { accountCode: '1001', debitAmount: 100, creditAmount: 0, auxSupplier: 'S001' },
      { accountCode: '3001', debitAmount: 0, creditAmount: 100 },
    ],
  }, '未配置')
  await expectFail('/finance/gl/voucher/save', {
    voucherDate: '2026-09-07', entries: [
      { accountCode: '1405', debitAmount: 5000, creditAmount: 0, auxGoods: 'G001' },
      { accountCode: '1001', debitAmount: 0, creditAmount: 5000 },
    ],
  }, '数量核算')
  await expectFail('/finance/gl/voucher/save', {
    voucherDate: '2026-09-07', entries: [
      { accountCode: '1001', debitAmount: 100, creditAmount: 50 },
      { accountCode: '3001', debitAmount: 0, creditAmount: 50 },
    ],
  }, '不能同时')
  // 期间锁定：202608 已结账、202610 未开始
  await expectFail('/finance/gl/voucher/save', officeExpenseVoucher(100, { date: '2026-08-15' }), '已结账')
  await expectFail('/finance/gl/voucher/save', officeExpenseVoucher(100, { date: '2026-10-15' }), '未开账')

  // ===== 5. V1：保存草稿 → 审核（0001）→ 反审核 → 再审核（0002 占号）→ 过账 =====
  const v1 = await post('/finance/gl/voucher/save', officeExpenseVoucher(1000))
  assert(v1.status === '草稿' && v1.entries.length === 2, 'V1 保存后应为草稿 2 条分录')
  assert(num(v1.debitTotal) === 1000 && num(v1.creditTotal) === 1000, 'V1 借贷各 1000')
  await post('/finance/gl/voucher/audit', { id: v1.id })
  let d1 = await post('/finance/gl/voucher/detail', { id: v1.id })
  assert(d1.voucherNo === '记-202609-0001', `首次审核凭证号应为 记-202609-0001，实际 ${d1.voucherNo}`)
  await expectFail('/finance/gl/voucher/audit', { id: v1.id }, '只有草稿')
  await post('/finance/gl/voucher/unaudit', { id: v1.id })
  d1 = await post('/finance/gl/voucher/detail', { id: v1.id })
  assert(d1.status === '草稿', '反审核后应回到草稿')
  await post('/finance/gl/voucher/audit', { id: v1.id })
  d1 = await post('/finance/gl/voucher/detail', { id: v1.id })
  assert(d1.voucherNo === '记-202609-0002', `反审核后再审核应占新号 0002，实际 ${d1.voucherNo}`)
  await post('/finance/gl/voucher/post', { id: v1.id })
  d1 = await post('/finance/gl/voucher/detail', { id: v1.id })
  assert(d1.status === '已过账', 'V1 应已过账')
  await expectFail('/finance/gl/voucher/post', { id: v1.id }, '只有已审核')
  await expectFail('/finance/gl/voucher/void', { id: v1.id }, '已过账')
  await expectFail('/finance/gl/voucher/unaudit', { id: v1.id }, '只有已审核')
  // 已过账凭证不允许再改
  await expectFail('/finance/gl/voucher/save', { ...officeExpenseVoucher(1000), id: v1.id }, '只有草稿')

  // ===== 6. V2：草稿修改 → 审核（0003）→ 作废（留号）=====
  let v2 = await post('/finance/gl/voucher/save', officeExpenseVoucher(500))
  v2 = await post('/finance/gl/voucher/save', { ...officeExpenseVoucher(600), id: v2.id })
  assert(num(v2.debitTotal) === 600, '草稿修改后金额应为 600')
  await post('/finance/gl/voucher/audit', { id: v2.id })
  d1 = await post('/finance/gl/voucher/detail', { id: v2.id })
  assert(d1.voucherNo === '记-202609-0003', `V2 凭证号应为 0003，实际 ${d1.voucherNo}`)
  await post('/finance/gl/voucher/void', { id: v2.id, reason: '录错' })
  d1 = await post('/finance/gl/voucher/detail', { id: v2.id })
  assert(d1.status === '已作废', 'V2 应已作废')
  await expectFail('/finance/gl/voucher/audit', { id: v2.id }, '只有草稿')

  // ===== 7. V3：购商品 5000（数量 100）/ 现金 5000，审核 0004 → 过账 =====
  const v3 = await post('/finance/gl/voucher/save', {
    voucherWord: '记', voucherDate: '2026-09-07', summary: '购商品',
    entries: [
      { summary: '入库', accountCode: '1405', debitAmount: 5000, creditAmount: 0, qty: 100, price: 50, auxGoods: 'G001' },
      { summary: '付现', accountCode: '1001', debitAmount: 0, creditAmount: 5000, cashFlowItem: 'CF04' },
    ],
  })
  await post('/finance/gl/voucher/audit', { id: v3.id })
  d1 = await post('/finance/gl/voucher/detail', { id: v3.id })
  assert(d1.voucherNo === '记-202609-0004', `V3 凭证号应为 0004，实际 ${d1.voucherNo}`)
  await post('/finance/gl/voucher/post', { id: v3.id })

  // ===== 8. 红冲 V1 → 红字凭证 0005 直接过账，V1 已冲销 =====
  const red = await post('/finance/gl/voucher/red-reverse', { id: v1.id })
  assert(red.voucherNo === '记-202609-0005', `红字凭证号应为 0005，实际 ${red.voucherNo}`)
  d1 = await post('/finance/gl/voucher/detail', { id: v1.id })
  assert(d1.status === '已冲销', '红冲后原凭证应为已冲销')
  const redDetail = await post('/finance/gl/voucher/page', { pageNo: 1, pageSize: 20, status: '已过账' })
  const redRow = redDetail.records.find(r => r.voucherNo === '记-202609-0005')
  assert(redRow && redRow.isRed === true, '红字凭证应 isRed=true 且已过账')
  assert(num(redRow.debitTotal) === -1000 && num(redRow.creditTotal) === -1000, '红字凭证金额应为 -1000')
  await expectFail('/finance/gl/voucher/red-reverse', { id: v1.id }, '只有已过账')

  // ===== 9. 列表过滤 =====
  const pageAll = await post('/finance/gl/voucher/page', { pageNo: 1, pageSize: 20, period: '202609' })
  assert(pageAll.total === 4, `202609 应有 4 张凭证（V1/V2/V3/红字），实际 ${pageAll.total}`)

  // ===== 10. 余额表（202609）=====
  // 1001：期初借 100000，本期贷 = 1000(V1) - 1000(红冲) + 5000(V3) = 5000，期末借 95000
  const bt = await post('/finance/gl/ledger/balance-table', { periodFrom: '202609', periodTo: '202609' })
  const row1001 = bt.find(r => r.accountCode === '1001')
  assert(row1001, '余额表应含 1001')
  assert(num(row1001.opening.debit) === 100000, `1001 期初借应为 100000，实际 ${row1001.opening.debit}`)
  assert(num(row1001.periodCredit) === 5000, `1001 本期贷应为 5000，实际 ${row1001.periodCredit}`)
  assert(num(row1001.closing.debit) === 95000, `1001 期末借应为 95000，实际 ${row1001.closing.debit}`)
  // 1405：本期借 5000、数量 100
  const row1405 = bt.find(r => r.accountCode === '1405')
  assert(row1405 && num(row1405.periodDebit) === 5000 && num(row1405.closing.debit) === 5000, '1405 本期/期末借应为 5000')
  assert(num(row1405.closeQty) === 100, `1405 期末数量应为 100，实际 ${row1405.closeQty}`)
  // 父级 5602 管理费用：560201 借贷 1000 互相抵消 → 0
  const row5602 = bt.find(r => r.accountCode === '5602')
  assert(!row5602 || (num(row5602.periodDebit) === 0 && num(row5602.periodCredit) === 0),
    '5602 红字冲回后发生额应为 0')
  // 全表借贷平衡
  const sumD = bt.reduce((s, r) => s + num(r.periodDebit), 0)
  const sumC = bt.reduce((s, r) => s + num(r.periodCredit), 0)
  assert(sumD === sumC, `余额表本期借贷应平衡，实际 ${sumD}/${sumC}`)

  // ===== 11. 明细账 =====
  const sub1001 = await post('/finance/gl/ledger/subsidiary', { accountCode: '1001', dateFrom: '2026-09-01', dateTo: '2026-09-30' })
  assert(num(sub1001.opening.debit) === 100000, '1001 明细账期初借 100000')
  assert(sub1001.rows.length === 3, `1001 明细分录应为 3 行（V1/红字/V3），实际 ${sub1001.rows.length}`)
  assert(num(sub1001.totalCredit) === 5000, `1001 本期贷方合计应为 5000，实际 ${sub1001.totalCredit}`)
  assert(num(sub1001.closing.debit) === 95000, `1001 期末借应为 95000，实际 ${sub1001.closing.debit}`)
  // 红字行金额为负
  assert(sub1001.rows.some(r => r.isRed && num(r.creditAmount) === -1000), '明细账应含红字行 -1000')
  // 非末级科目拒绝
  await expectFail('/finance/gl/ledger/subsidiary', { accountCode: '1002' }, '末级科目')
  // 辅助过滤：560201 + 部门 D001 → 2 行（V1 + 红字），净额 0
  const sub560 = await post('/finance/gl/ledger/subsidiary', {
    accountCode: '560201', dateFrom: '2026-09-01', dateTo: '2026-09-30', auxDepartment: 'D001',
  })
  assert(sub560.rows.length === 2, `560201+D001 应 2 行，实际 ${sub560.rows.length}`)
  assert(num(sub560.totalDebit) === 0, `560201 红冲后借方合计应为 0，实际 ${sub560.totalDebit}`)

  // ===== 12. 总账（年度逐月）=====
  const gl = await post('/finance/gl/ledger/general', { year: '2026', accountCode: '1001' })
  assert(gl.length === 1, '总账应只返回 1001 一个科目')
  const sept = gl[0].months.find(m => m.period === '202609')
  assert(num(sept.closing.debit) === 95000, `总账 9 月期末借应为 95000，实际 ${sept.closing.debit}`)
  assert(num(gl[0].yearCredit) === 5000, `总账本年累贷应为 5000，实际 ${gl[0].yearCredit}`)

  // ===== 13. 序时账 =====
  const journal = await post('/finance/gl/ledger/journal', { dateFrom: '2026-09-01', dateTo: '2026-09-30' })
  assert(journal.length === 6, `序时账应 6 行（V1/红/V3 各 2 行，作废 V2 排除），实际 ${journal.length}`)
  const journalVoid = await post('/finance/gl/ledger/journal', { dateFrom: '2026-09-01', dateTo: '2026-09-30', status: '已作废' })
  assert(journalVoid.length === 2, `作废凭证序时账应 2 行（V2），实际 ${journalVoid.length}`)

  console.log('GL M2 (voucher + ledger) smoke test PASSED ✅')
}

main().catch(error => {
  console.error('GL M2 smoke test FAILED ❌', error)
  process.exit(1)
})
