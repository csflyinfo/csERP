/**
 * PRD-36 M1 接口 E2E：供应商账户（只读账户页 + 流水抽屉 + 数据修复）
 *
 * 隔离环境：8082 + verify-prd36-m1 库；夹具 prd36-m1-fixtures.sql
 * （S001：收货应付 1000/已付 300/未付 700、退货负应付 -100、期初应付 500、
 *   付款核销 300、费用记录 50 应排除；S002：空账户）。
 * 运行：node development/06-testing/scripts/prd36-m1-api-e2e.mjs
 */
const BASE = process.env.BASE || 'http://127.0.0.1:8082/api'
let pass = 0, fail = 0
function ok(cond, msg) {
  if (cond) { pass++; console.log('  ✓', msg) }
  else { fail++; console.log('  ✗ FAIL:', msg) }
}
async function call(path, body, token) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json;charset=utf-8', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: JSON.stringify(body || {}),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(path + ' -> ' + JSON.stringify(json).slice(0, 260))
  return json.data
}
const num = (v) => Number(v || 0)

// ==================== 0. 登录 ====================
const login = await call('/auth/login', { username: 'admin', password: 'admin123' })
const token = login.token
console.log('0) login OK')

// ==================== 1. 迁移回填结果（V128 在夹具真值上重跑） ====================
const page = await call('/finance/supplier-account/page', { pageNo: 1, pageSize: 50 }, token)
const s001 = page.records.find(r => r.supplierCode === 'S001') || {}
const s002 = page.records.find(r => r.supplierCode === 'S002') || {}
console.log('1) 账户分页与迁移回填')
ok(page.records.length >= 2, '在册供应商均有账户行（实际 ' + page.records.length + '）')
ok(s001.supplierName === '甲厂家', '供应商名称 UTF-8 正常：' + s001.supplierName)
ok(num(s001.apBalance) === 1100, 'S001 应付余额=700-100+500=1100，实际 ' + s001.apBalance)
ok(num(s001.prepayBalance) === 0, 'S001 预付余额=0，实际 ' + s001.prepayBalance)
ok(num(s001.expenseBalance) === 0, 'S001 费用余额=0，实际 ' + s001.expenseBalance)
ok(s001.defaultBuyer === '张采购' && s001.settlementMethod === '月结' && num(s001.accountPeriodDays) === 30,
  '档案快照（采购员/结算方式/账期）正确：' + [s001.defaultBuyer, s001.settlementMethod, s001.accountPeriodDays].join('/'))
ok(num(s002.apBalance) === 0 && num(s002.prepayBalance) === 0, 'S002 空账户三余额为 0')

// 关键字查询仅点查询生效（后端过滤验证）
const kw = await call('/finance/supplier-account/page',
  { pageNo: 1, pageSize: 50, keyword: '乙厂家' }, token)
ok(kw.records.length === 1 && kw.records[0].supplierCode === 'S002', '关键字按名称过滤生效')
const hideZero = await call('/finance/supplier-account/page',
  { pageNo: 1, pageSize: 50, hideZero: true }, token)
ok(!hideZero.records.some(r => num(r.apBalance) === 0 && num(r.prepayBalance) === 0 && num(r.expenseBalance) === 0),
  'hideZero 隐藏零余额行')

// ==================== 2. AP 流水分页 ====================
console.log('2) AP 往来流水')
const flow = await call('/finance/supplier-account/flow/page',
  { supplierCode: 'S001', accountType: 'AP', beginTime: '2026-01-01 00:00:00', endTime: '2026-12-31 23:59:59',
    pageNo: 1, pageSize: 100 }, token)
ok(num(flow.total) === 4, 'AP 流水 4 笔（3 形成 + 1 结算），实际 ' + flow.total)
ok(num(flow.currentBalance) === 1100, '滚存当前余额 1100，实际 ' + flow.currentBalance)
const byBiz = {}
for (const r of flow.records) byBiz[r.bizType] = r
ok(byBiz.AP_RECEIPT && num(byBiz.AP_RECEIPT.increaseAmount) === 1000 && byBiz.AP_RECEIPT.isRed === 'N'
  && byBiz.AP_RECEIPT.sourceBill === 'CGSH-TEST-1', '采购收货形成 +1000')
ok(byBiz.AP_RETURN && num(byBiz.AP_RETURN.increaseAmount) === -100 && byBiz.AP_RETURN.isRed === 'Y',
  '采购退货红字形成 -100（isRed=Y）')
ok(byBiz.AP_OPENING && num(byBiz.AP_OPENING.increaseAmount) === 500 && byBiz.AP_OPENING.sourceBill === 'QCAP-TEST-1'
  && String(byBiz.AP_OPENING.postDate).startsWith('2026-08-01'), '期初应付形成 +500（记账日期取 due_date=2026-08-01）')
ok(byBiz.AP_SETTLE_CASH && num(byBiz.AP_SETTLE_CASH.decreaseAmount) === 300
  && byBiz.AP_SETTLE_CASH.paymentNo === 'FK-TEST-1' && byBiz.AP_SETTLE_CASH.reconcileId === 'RR-TEST-001',
  '付款结算 -300（单号/核销记录 id 回填）')
ok(!flow.records.some(r => r.apNo === 'FE-TEST-1'), '费用类核销记录不生成 AP 流水')
// 结算标志：AP-1 部分结算
ok(byBiz.AP_RECEIPT.settleStatus === '部分结算' && num(byBiz.AP_RECEIPT.settledAmount) === 300,
  '收货形成行标志=部分结算、已结 300，实际 ' + byBiz.AP_RECEIPT.settleStatus + '/' + byBiz.AP_RECEIPT.settledAmount)
ok(byBiz.AP_OPENING.settleStatus === '未结算', '期初形成行标志=未结算')
// bizLabel 文案
ok(byBiz.AP_SETTLE_CASH.bizLabel === '付款结算', '业务单据文案：付款结算')

// 此前余额：区间从 2026-08-20 起，此前末笔=收货 1000
const flow2 = await call('/finance/supplier-account/flow/page',
  { supplierCode: 'S001', accountType: 'AP', beginTime: '2026-08-20 00:00:00', endTime: '2026-08-31 23:59:59',
    pageNo: 1, pageSize: 100 }, token)
ok(num(flow2.priorBalance) === 1000, '此前余额=1000（8/20 前末笔），实际 ' + flow2.priorBalance)

// 结算标志过滤
const unsettled = await call('/finance/supplier-account/flow/page',
  { supplierCode: 'S001', accountType: 'AP', settleStatus: '未结算', beginTime: '2026-01-01', endTime: '2026-12-31',
    pageNo: 1, pageSize: 100 }, token)
ok(unsettled.records.every(r => r.settleStatus === '未结算'), '结算标志过滤只回未结算行')

// PREPAY / EXPENSE 空账
const prepay = await call('/finance/supplier-account/flow/page',
  { supplierCode: 'S001', accountType: 'PREPAY', pageNo: 1, pageSize: 100 }, token)
const expense = await call('/finance/supplier-account/flow/page',
  { supplierCode: 'S001', accountType: 'EXPENSE', pageNo: 1, pageSize: 100 }, token)
ok(num(prepay.total) === 0 && num(prepay.currentBalance) === 0, '预付账空')
ok(num(expense.total) === 0 && num(expense.currentBalance) === 0, '费用账空')

// 缺供应商/非法账套报错
const bad1 = await fetch(BASE + '/finance/supplier-account/flow/page', {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
  body: JSON.stringify({ accountType: 'AP' }) }).then(r => r.json())
ok(bad1.code !== '0' && JSON.stringify(bad1).includes('缺少供应商'), '缺供应商给中文错误')
const bad2 = await fetch(BASE + '/finance/supplier-account/flow/page', {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
  body: JSON.stringify({ supplierCode: 'S001', accountType: 'BAD' }) }).then(r => r.json())
ok(bad2.code !== '0' && JSON.stringify(bad2).includes('账户类型不正确'), '非法账套给中文错误')

// ==================== 3. 数据修复幂等 ====================
console.log('3) 数据修复')
const rep = await call('/finance/supplier-account/repair', {}, token)
ok(num(rep.missingForming) === 0 && num(rep.missingSettle) === 0,
  '全量修复不重复补流水（缺形成 ' + rep.missingForming + '、缺结算 ' + rep.missingSettle + '）')
ok((rep.mismatches || []).length === 0, '无真值差异（实际 ' + (rep.mismatches || []).length + ' 项）')
const after = await call('/finance/supplier-account/page', { pageNo: 1, pageSize: 50 }, token)
const s001b = after.records.find(r => r.supplierCode === 'S001') || {}
ok(num(s001b.apBalance) === 1100, '修复后 S001 应付仍为 1100')

console.log(`\n结果：${pass} 通过，${fail} 失败`)
process.exit(fail ? 1 : 0)
