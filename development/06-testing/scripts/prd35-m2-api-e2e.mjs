/**
 * PRD-35 M2 接口 E2E：收款单三类（SETTLE/ADVANCE/ADVANCE_REFUND）
 * 隔离环境：8082 + verify-m2 库；夹具 base_customer KTEST1 + 2 笔 AR + 1 笔结算（M1 夹具）
 */
const BASE = 'http://127.0.0.1:8082/api';
let pass = 0, fail = 0;
function ok(cond, msg) {
  if (cond) { pass++; console.log('  ✓', msg); }
  else { fail++; console.log('  ✗ FAIL:', msg); }
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
// 本地今天：夹具日结封单到昨天，硬编码隔日日期会导致审核 400
const TODAY = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);

async function call(path, body, token, { raw = false } = {}) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json;charset=utf-8', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: JSON.stringify(body || {}),
  });
  const json = await res.json();
  if (raw) return json;
  if (json.code !== '0') throw new Error(path + ' -> ' + JSON.stringify(json).slice(0, 260));
  return json.data;
}
async function expectFail(path, body, token, msgIncludes, label) {
  const json = await call(path, body, token, { raw: true });
  const m = JSON.stringify(json);
  ok(json.code !== '0' && (msgIncludes ? m.includes(msgIncludes) : true),
    label + '（应报错含「' + msgIncludes + '」）实际：' + m.slice(0, 180));
}
const num = (v) => Number(v || 0);

// ==================== 0. 登录 + 修复到 M1 基线 ====================
const login = await call('/auth/login', { username: 'admin', password: 'admin123' });
const token = login.token;
console.log('0) login OK');
await call('/finance/customer-account/repair', {}, token);

async function accountOf(code) {
  const p = await call('/finance/customer-account/page',
    { pageNo: 1, pageSize: 20, filters: { keyword: code } }, token);
  return p.records.find(r => r.customerCode === code) || {};
}
async function flows(accountType) {
  const p = await call('/finance/customer-account/flow/page',
    { customerCode: 'KTEST1', accountType, pageNo: 1, pageSize: 100 }, token);
  return p;
}

let acct = await accountOf('KTEST1');
ok(num(acct.arBalance) === 250, '基线应收余额 250，实际 ' + acct.arBalance);
ok(num(acct.advanceBalance) === 0, '基线预收余额 0，实际 ' + acct.advanceBalance);

// advance-balance 端点
let ab = await call('/finance/customer-account/advance-balance', { customerCode: 'KTEST1' }, token);
ok(num(ab.advanceBalance) === 0, '预收余额查询=0');
ab = await call('/finance/customer-account/advance-balance', { customerCode: 'NO_SUCH_CUST' }, token);
ok(num(ab.advanceBalance) === 0, '不存在客户预收余额按 0');

function receiptBody(type, amount, cpType = 'CUSTOMER', code = 'KTEST1', name = '测试客户甲') {
  return {
    receiptDate: TODAY,
    receiptType: type,
    counterpartyType: cpType,
    counterpartyCode: code,
    counterpartyName: name,
    handler: '张三',
    summary: 'M2自动化' + Math.random().toString(36).slice(2, 6),
    details: [{ fundAccount: '现金', amount, remark: '' }],
  };
}
async function findReceipt(no) {
  const p = await call('/finance/receipt/page', { pageNo: 1, pageSize: 50, filters: { receiptNo: no } }, token);
  return p.records[0] || {};
}
async function findEvents(eventCode, billNo) {
  const p = await call('/finance/gl/event/page',
    { pageNo: 1, pageSize: 50, eventCode, keyword: billNo }, token);
  return p.records || [];
}

// ==================== 1. 建单校验：类型/往来单位组合 ====================
console.log('1) 建单校验');
await expectFail('/finance/receipt/create', receiptBody('BAD_TYPE', 10), token,
  '收款类型不正确', '非法收款类型拒绝');
await expectFail('/finance/receipt/create', receiptBody('ADVANCE', 10, 'SUPPLIER', 'GYS001', '测试供应商'),
  token, '必须是客户', '预收收款对供应商拒绝');

// ==================== 2. 预收收款 300 → 审核 ====================
console.log('2) 预收收款审核');
const r1 = await call('/finance/receipt/create', receiptBody('ADVANCE', 300), token);
ok(!!r1.receiptNo, '预收收款单已创建 ' + r1.receiptNo);
let advNo = r1.receiptNo;
let row = await findReceipt(advNo);
ok(row.receiptType === 'ADVANCE' && row.receiptTypeText === '预收收款',
  '列表收款类型=预收收款，实际 ' + row.receiptType + '/' + row.receiptTypeText);
ok(row.reconcileStatusText === '—', '预收单核销状态展示 —，实际 ' + row.reconcileStatusText);

await call('/finance/receipt/audit', { receiptId: r1.receiptId }, token);
await sleep(400); // 总账事件 afterCommit
acct = await accountOf('KTEST1');
ok(num(acct.advanceBalance) === 300, '审核后预收余额 300，实际 ' + acct.advanceBalance);
ok(num(acct.arBalance) === 250, '应收余额不受影响仍 250，实际 ' + acct.arBalance);

let fp = await flows('ADVANCE');
ok(fp.total === 1, '预收流水 1 条，实际 ' + fp.total);
let f = fp.records[0];
ok(f.bizType === 'ADV_RECEIPT' && f.bizLabel === '预收收款',
  '流水类型 ADV_RECEIPT/预收收款，实际 ' + f.bizType + '/' + f.bizLabel);
ok(num(f.increaseAmount) === 300 && num(f.balanceAfter) === 300,
  '增加 300 / 滚存 300，实际 +' + f.increaseAmount + ' 余' + f.balanceAfter);
ok(f.receiptNo === advNo, '流水关联收款单号 ' + f.receiptNo);
ok(f.reverseStatus === 'NORMAL', '正向流水 NORMAL');

let evs = await findEvents('ADVANCE_RECEIPT', advNo);
ok(evs.length === 1, 'ADVANCE_RECEIPT 事件 1 条，实际 ' + evs.length);
let ev = evs[0] || {};
ok(ev.status === '待生成' && num(ev.amount) === 300, '事件待生成/金额300：' + ev.status + '/' + ev.amount);
const pl = await call('/finance/gl/event/payload', { id: ev.id }, token);
const payload = pl.payload || pl;
ok(payload.fund_subject_code === '1001', '载荷资金科目 1001，实际 ' + payload.fund_subject_code);
ok(payload.customer_code === 'KTEST1', '载荷客户编码 KTEST1，实际 ' + payload.customer_code);
ok(num(payload.amount_tax_incl) === 300, '载荷金额 300');

// ==================== 3. 预收退款超额拦截 ====================
console.log('3) 预收退款余额校验');
const bad = await call('/finance/receipt/create', receiptBody('ADVANCE_REFUND', 400), token);
await expectFail('/finance/receipt/audit', { receiptId: bad.receiptId }, token,
  '预收余额不足', '退款 400 > 预收 300 被拒');
let badRow = await findReceipt(bad.receiptNo);
ok(badRow.status === 'PENDING', '超额退款单仍待审核（事务回滚），实际 ' + badRow.status);
acct = await accountOf('KTEST1');
ok(num(acct.advanceBalance) === 300, '拦截后预收余额仍 300');

// ==================== 4. 预收退款 120 → 审核 ====================
console.log('4) 预收退款审核');
const r2 = await call('/finance/receipt/create', receiptBody('ADVANCE_REFUND', 120), token);
const refundNo = r2.receiptNo;
await call('/finance/receipt/audit', { receiptId: r2.receiptId }, token);
await sleep(400);
acct = await accountOf('KTEST1');
ok(num(acct.advanceBalance) === 180, '退款后预收余额 180，实际 ' + acct.advanceBalance);
fp = await flows('ADVANCE');
ok(fp.total === 2, '预收流水 2 条，实际 ' + fp.total);
f = fp.records[0];
ok(f.bizType === 'ADV_REFUND' && f.bizLabel === '预收退款',
  '流水类型 ADV_REFUND/预收退款，实际 ' + f.bizType + '/' + f.bizLabel);
ok(num(f.decreaseAmount) === 120 && num(f.balanceAfter) === 180,
  '扣减 120 / 滚存 180，实际 -' + f.decreaseAmount + ' 余' + f.balanceAfter);
ok(f.receiptNo === refundNo, '退款流水关联单号 ' + f.receiptNo);
evs = await findEvents('ADVANCE_REFUND', refundNo);
ok(evs.length === 1 && evs[0].status === '待生成', 'ADVANCE_REFUND 事件 1 条待生成');

// ==================== 5. 生成凭证，验模板渲染 ====================
console.log('5) 凭证模板渲染');
const evReceipt = (await findEvents('ADVANCE_RECEIPT', advNo))[0];
const evRefund = (await findEvents('ADVANCE_REFUND', refundNo))[0];
const gen = await call('/finance/gl/event/generate', { ids: [evReceipt.id, evRefund.id] }, token);
console.log('  generate:', JSON.stringify(gen).slice(0, 200));
await sleep(300);
evs = await findEvents('ADVANCE_RECEIPT', advNo);
ok(evs[0].status === '已生成' && !!evs[0].voucherId, '预收收款事件已生成凭证 ' + evs[0].voucherId);
evs = await findEvents('ADVANCE_REFUND', refundNo);
ok(evs[0].status === '已生成' && !!evs[0].voucherId, '预收退款事件已生成凭证 ' + evs[0].voucherId);

async function voucherEntries(voucherId) {
  const d = await call('/finance/gl/voucher/detail', { id: voucherId }, token);
  console.log('  凭证字段:', Object.keys(d).join(','));
  return d;
}
let v = await voucherEntries((await findEvents('ADVANCE_RECEIPT', advNo))[0].voucherId);
let entries = (v && v.entries) || [];
let deb = entries.filter(e => num(e.debitAmount) > 0), cre = entries.filter(e => num(e.creditAmount) > 0);
console.log('  收款凭证借/贷:', entries.map(e => `${num(e.debitAmount) > 0 ? '借' : '贷'}${e.accountCode} 借${e.debitAmount}/贷${e.creditAmount} aux=${e.auxCustomer || ''}`).join(' , '));
ok(entries.length === 2, '收款凭证 2 行，实际 ' + entries.length);
ok(deb.length === 1 && deb[0].accountCode === '1001', '借方资金科目 1001：' + (deb[0] && deb[0].accountCode));
ok(cre.length === 1 && cre[0].accountCode === '2203', '贷方预收账款 2203：' + (cre[0] && cre[0].accountCode));
ok(num(deb[0].debitAmount) === 300 && num(cre[0].creditAmount) === 300, '借贷各 300');
ok(cre[0].auxCustomer === 'KTEST1', '2203 行带客户辅助核算 KTEST1，实际 ' + cre[0].auxCustomer);

v = await voucherEntries((await findEvents('ADVANCE_REFUND', refundNo))[0].voucherId);
entries = (v && v.entries) || [];
deb = entries.filter(e => num(e.debitAmount) > 0); cre = entries.filter(e => num(e.creditAmount) > 0);
console.log('  退款凭证借/贷:', entries.map(e => `${num(e.debitAmount) > 0 ? '借' : '贷'}${e.accountCode} 借${e.debitAmount}/贷${e.creditAmount} aux=${e.auxCustomer || ''}`).join(' , '));
ok(entries.length === 2, '退款凭证 2 行，实际 ' + entries.length);
ok(deb.length === 1 && deb[0].accountCode === '2203', '借方预收账款 2203：' + deb[0].accountCode);
ok(cre.length === 1 && cre[0].accountCode === '1001', '贷方资金科目 1001：' + cre[0].accountCode);
ok(deb[0].auxCustomer === 'KTEST1', '退款 2203 行带客户辅助核算 KTEST1，实际 ' + deb[0].auxCustomer);

// ==================== 6. 反审核顺序守卫 ====================
console.log('6) 反审核守卫与冲回');
await expectFail('/finance/receipt/cancel-audit', { receiptId: r1.receiptId }, token,
  '已被预收核销或退款使用', '预收已被退款占用时禁止反审核收款单');

// 先反审核退款
await call('/finance/receipt/cancel-audit', { receiptId: r2.receiptId }, token);
await sleep(400);
acct = await accountOf('KTEST1');
ok(num(acct.advanceBalance) === 300, '退款冲回后预收余额恢复 300，实际 ' + acct.advanceBalance);
fp = await flows('ADVANCE');
ok(fp.total === 3, '预收流水 3 条（含退款冲回），实际 ' + fp.total);
f = fp.records[0];
ok(f.bizType === 'ADV_REVERSE' && num(f.increaseAmount) === 120 && f.isRed === 'Y',
  '红字冲回行 ADV_REVERSE +120 isRed，实际 ' + f.bizType + ' +' + f.increaseAmount + ' red=' + f.isRed);
const refundFwd = fp.records.find(x => x.bizType === 'ADV_REFUND');
ok(refundFwd.reverseStatus === 'REVERSED', '退款原行标记 REVERSED，实际 ' + refundFwd.reverseStatus);
let revEvs = await findEvents('ADVANCE_REFUND', refundNo);
ok(revEvs.some(e => e.reverseFlag === '1'), '退款反向红字事件已落池');

// 再反审核预收收款
await call('/finance/receipt/cancel-audit', { receiptId: r1.receiptId }, token);
await sleep(400);
acct = await accountOf('KTEST1');
ok(num(acct.advanceBalance) === 0, '收款冲回后预收余额归零，实际 ' + acct.advanceBalance);
fp = await flows('ADVANCE');
ok(fp.total === 4, '预收流水 4 条，实际 ' + fp.total);
f = fp.records[0];
ok(f.bizType === 'ADV_REVERSE' && num(f.decreaseAmount) === 300 && f.isRed === 'Y',
  '收款冲回行 ADV_REVERSE -300 红字');
const recvFwd = fp.records.find(x => x.bizType === 'ADV_RECEIPT');
ok(recvFwd.reverseStatus === 'REVERSED', '收款原行标记 REVERSED');
revEvs = await findEvents('ADVANCE_RECEIPT', advNo);
ok(revEvs.some(e => e.reverseFlag === '1'), '收款反向红字事件已落池');

// ==================== 7. 再审核 → 第二轮 #2 ====================
console.log('7) 反审核后修改再审核（round #2）');
await call('/finance/receipt/update', {
  receiptId: r1.receiptId,
  receiptDate: TODAY,
  receiptType: 'ADVANCE',
  counterpartyType: 'CUSTOMER',
  counterpartyCode: 'KTEST1',
  counterpartyName: '测试客户甲',
  handler: '张三',
  summary: 'M2再审第二轮',
  details: [{ fundAccount: '现金', amount: 260, remark: '' }],
}, token);
await call('/finance/receipt/audit', { receiptId: r1.receiptId }, token);
await sleep(400);
acct = await accountOf('KTEST1');
ok(num(acct.advanceBalance) === 260, '第二轮审核预收余额 260，实际 ' + acct.advanceBalance);
fp = await flows('ADVANCE');
ok(fp.total === 5, '预收流水 5 条，实际 ' + fp.total);
f = fp.records[0];
ok(f.bizType === 'ADV_RECEIPT' && num(f.increaseAmount) === 260 && num(f.balanceAfter) === 260,
  '第二轮 ADV_RECEIPT +260 滚存260');
ok(f.reverseStatus === 'NORMAL', '第二轮新行 NORMAL');
evs = await findEvents('ADVANCE_RECEIPT', advNo);
ok(evs.filter(e => e.reverseFlag !== '1' && e.status === '待生成').length === 1,
  '第二轮正向事件独立落池（事件唯一键未撞）');

// ==================== 8. SETTLE 应收结算回归 ====================
console.log('8) 应收结算回归');
const s1 = await call('/finance/receipt/create', receiptBody('SETTLE', 80), token);
await call('/finance/receipt/audit', { receiptId: s1.receiptId }, token);
row = await findReceipt(s1.receiptNo);
ok(row.receiptType === 'SETTLE' && row.receiptTypeText === '应收结算',
  '结算单类型=应收结算：' + row.receiptType + '/' + row.receiptTypeText);
ok(['部分核销', '已核销'].includes(row.reconcileStatusText),
  '结算单核销状态正常：' + row.reconcileStatusText);
const recs = await call('/finance/receipt/page',
  { pageNo: 1, pageSize: 5, filters: { receiptNo: s1.receiptNo } }, token);
ok(num(recs.records[0].verifiedAmount) === 80, '核销金额 80，实际 ' + recs.records[0].verifiedAmount);
// 反审核回归
await call('/finance/receipt/cancel-audit', { receiptId: s1.receiptId }, token);
row = await findReceipt(s1.receiptNo);
ok(row.status === 'PENDING', '结算单反审核回待审核');
// 反审核后 AR 已收回退
const arPage = await call('/finance/ar/page', { pageNo: 1, pageSize: 10, filters: { customer: 'KTEST1' } }, token);
const ar2 = (arPage.records || []).find(a => a.arNo === 'AR-TEST-002');
ok(ar2 && num(ar2.receivedAmount) === 50, 'AR-TEST-002 已收回退到夹具基线 50，实际 ' + (ar2 && ar2.receivedAmount));

// ==================== 9. 列表筛选：收款类型 ====================
console.log('9) 列表筛选');
let p = await call('/finance/receipt/page',
  { pageNo: 1, pageSize: 50, filters: { receiptType: '预收收款' } }, token);
ok((p.records || []).every(r => r.receiptType === 'ADVANCE'),
  '筛「预收收款」全部为 ADVANCE，实际 ' + (p.records || []).map(r => r.receiptType).join(','));
ok((p.records || []).some(r => r.receiptNo === advNo), '筛中第二轮预收单 ' + advNo);
p = await call('/finance/receipt/page',
  { pageNo: 1, pageSize: 50, filters: { receiptType: 'ADVANCE_REFUND' } }, token);
ok((p.records || []).every(r => r.receiptType === 'ADVANCE_REFUND'),
  '码值筛选同样生效（预收退款 ' + p.total + ' 条）');
p = await call('/finance/receipt/page',
  { pageNo: 1, pageSize: 50, filters: { reconcileStatus: '未核销' } }, token);
ok((p.records || []).every(r => r.receiptType === 'SETTLE'),
  '核销状态筛选自动排除预收类，实际类型集合 ' + [...new Set((p.records || []).map(r => r.receiptType))].join(','));

console.log(`\n===== M2 接口 E2E：${pass} 通过 / ${fail} 失败 =====`);
process.exit(fail ? 1 : 0);
