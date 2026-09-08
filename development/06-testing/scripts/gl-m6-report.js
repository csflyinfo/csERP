/**
 * 总账 M6（PRD-31）冒烟测试：总账报表（BS/IS/CF）、业财对账、现金流量项目补录
 *
 * 用法（专用冒烟库，必须在 backend/ 目录启动）：
 *   java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8081 \
 *     --spring.datasource.url="jdbc:h2:file:./data/erp-smoke-gl6;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     --spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,...
 *   node development/06-testing/scripts/gl-m6-report.js
 *
 * 思路：全部业务走真实业务单据（采购收货/销售出库签收/收款/付款），
 *   业务侧（fin_ar/fin_ap/base_fund_account）与总账侧凭证由同一批事件生成，
 *   故业财对账三组（1122 应收 / 2202 应付 / 资金账户）天然相等；
 *   期初用 1901/3001 这对非对账科目，保证三组都从 0 起步。
 *
 * 覆盖：
 *  1. 三表取数：IS 营业收入=2100、营业成本=采购成本、净利润勾稽；CF CF01=500/CF04=1000、
 *     净增加额 RCF30 与货币资金期末-期初勾稽（-500）；BS 期中（未结转损益）资产=负债权益 balanced；
 *  2. 业财对账：三组全平（含客户/供应商/资金账户明细金额）；手工凭证制造 1122 差异 100
 *     → 对账报差额且明细定位客户 → 红字反向凭证冲回 → 恢复平衡；
 *  3. 现金流量补录：无流量项目的现金已过账分录进 cf-pending；批量补录 CF07 后
 *     CF 表 RCF08=200、RCF30 联动、pending 清空；非法项目编码/非现金分录被拒。
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

async function findEvent(eventCode, billNo) {
  const page = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 300, keyword: billNo })
  return (page.records || []).find(x => x.eventCode === eventCode && x.sourceBillNo === billNo
    && String(x.reverseFlag) === '0')
}
async function genAndPost(eventCode, billNo) {
  const ev0 = await findEvent(eventCode, billNo)
  assert(ev0, `事件 ${eventCode}/${billNo} 未落池`)
  await post('/finance/gl/event/generate', { ids: [ev0.id] })
  const ev = await findEvent(eventCode, billNo)
  assert(ev.status !== '生成失败', `事件 ${eventCode}/${billNo} 生成失败：${ev.errMsg || ''}`)
  assert(ev.voucherId, `事件 ${eventCode}/${billNo} 无凭证`)
  await post('/finance/gl/voucher/audit', { id: ev.voucherId })
  await post('/finance/gl/voucher/post', { id: ev.voucherId })
  return post('/finance/gl/voucher/detail', { id: ev.voucherId })
}

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

async function report(code, period = '202609') {
  return post('/finance/gl/report/data', { reportCode: code, period })
}
function row(rp, namePart) {
  const r = rp.rows.find(x => x.itemName.includes(namePart))
  assert(r, `报表缺行：${namePart}`)
  return r
}
async function reconcile() {
  return post('/finance/gl/report/reconcile', { period: '202609' })
}
function group(rc, key) {
  const g = rc.groups.find(x => x.key === key)
  assert(g, `对账缺分组：${key}`)
  return g
}

async function main() {
  await login()
  const sfx = Math.random().toString(16).slice(2, 8)
  const today = new Date().toISOString().slice(0, 10)

  // ===== 1. 菜单含总账报表/业财对账 =====
  const menus = await get('/system/menu/user-tree')
  const glJson = JSON.stringify(menus.find(m => m.name === '总账管理' || m.menuName === '总账管理') || {})
  assert(glJson.includes('glReport'), '总账菜单应含 glReport')
  assert(glJson.includes('glReconcile'), '总账菜单应含 glReconcile')

  // ===== 2. 基础档案 =====
  const WH = '冒烟仓-' + sfx
  const SUP = { code: 'SUP' + sfx, name: '冒烟供应商-' + sfx }
  const CUS = { code: 'CUS' + sfx, name: '冒烟客户-' + sfx }
  const G1 = { code: 'G1' + sfx, name: '冒烟商品甲-' + sfx }
  const FA = { code: 'FA' + sfx, name: '冒烟现金账户-' + sfx }
  const DEPT_CODE = 'D' + sfx
  const BATCH_A = 'BATCH-A-' + sfx

  await post('/base/warehouse/create', {
    warehouseCode: 'WH' + sfx, warehouseName: WH,
    warehouseType: '正常仓', inventoryType: '平台主仓', costGroup: 'CG01',
  })
  await post('/base/supplier/create', {
    supplierCode: SUP.code, supplierName: SUP.name, supplierType: '普通供应商', settlementMethod: '月结30天',
  })
  await post('/base/customer/create', {
    customerCode: CUS.code, customerName: CUS.name, channelType: '零售商超',
    salesman: '销售员', accountPeriodType: '现结',
  })
  await post('/base/goods/create', {
    goodsCode: G1.code, goodsName: G1.name, spec: '1*6', categoryName: '冒烟分类-' + sfx,
    baseUnit: '箱', goodsType: '正常商品', taxRate: '13%',
  })
  await post('/base/master/save', {
    moduleCode: 'fundAccount', fundAccountCode: FA.code, fundAccountName: FA.name,
    parentCode: '01', accountType: '现金', glAccountCode: '1001', status: 'NORMAL',
  })
  await post('/base/master/save', {
    moduleCode: 'department', departmentCode: DEPT_CODE, departmentName: '冒烟管理部-' + sfx,
    headCount: 5, remark: '冒烟', status: 'NORMAL',
  })
  // 商品分类 → 收入/成本科目映射（分类随商品内联创建，可能未落映射列表，缺省时走系统默认科目）
  const mapping = await post('/finance/gl/archive-mapping/list', {})
  const catRow = (mapping.categories || []).find(x => x.name === '冒烟分类-' + sfx)
  if (catRow) {
    await post('/finance/gl/archive-mapping/save', {
      type: 'category', code: catRow.code, glIncomeAccountCode: '500101', glCostAccountCode: '5401',
    })
  }

  // ===== 3. 启用总账（期初用非对账科目 1901/3001，应收/应付/资金三组从 0 起步）=====
  await post('/finance/gl/init/balance-save', { rows: [
    { accountCode: '1901', openDebit: 100000 },
    { accountCode: '3001', openCredit: 100000 },
  ] })
  await post('/finance/gl/init/enable', { startPeriod: '202609' })

  // ===== 4. 采购链路：订单 → 入库审核（CGSH）→ 收货审核（PUR_IN + fin_ap）=====
  const po = await post('/purchase/order/create', {
    supplierCode: SUP.code, supplierName: SUP.name, buyer: '张三', warehouseId: WH,
    billDate: today,
    details: [{
      goodsCode: G1.code, goodsName: G1.name, spec: '1*6', unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 100, baseQty: 100, price: 35, amount: 3500, taxRate: '13%',
    }],
  })
  await post('/purchase/order/audit', { orderId: po.orderId })
  const pib = await post('/purchase/inbound/create', {
    sourceOrder: po.orderNo, supplier: SUP.name, warehouse: WH, billDate: today,
    details: [{ goodsCode: G1.code, goodsName: G1.name, unitName: '箱', batchNo: BATCH_A, receivedQty: 100, price: 35 }],
  })
  const pibAudit = await post('/purchase/inbound/audit', { bizId: pib.inboundId })
  const CGSH = pibAudit.receiptNo
  assert(CGSH, '入库审核应返回收货单号')
  await post('/purchase/receipt/audit', { bizId: CGSH })
  const vPur = await genAndPost('PUR_IN', CGSH)
  assert(vPur.entries.some(e => e.accountCode === '2202' && num(e.creditAmount) === 3500),
    '采购凭证应贷 2202 3500')

  // ===== 5. 销售链路：订单 → 出库审核（SALE_OUT）→ 签收（SALE_SIGN + fin_ar）=====
  const so = await post('/sales/order/create', {
    customerCode: CUS.code, customerName: CUS.name, salesman: '销售员', warehouseId: WH,
    billDate: today,
    details: [{
      goodsCode: G1.code, goodsName: G1.name, unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 60, baseQty: 60, price: 35, amount: 2100, taxRate: '13%', salesAttribute: '正常',
    }],
  })
  await post('/sales/order/audit', { orderId: so.orderId })
  const sob = await post('/sales/outbound/create', {
    sourceOrder: so.orderNo, customer: CUS.name, warehouse: WH, billDate: today,
    details: [{
      goodsCode: G1.code, goodsName: G1.name, unitName: '箱', qty: 60, price: 35,
      batchNo: BATCH_A, productionDate: '2026-01-10',
    }],
  })
  const sobAudit = await post('/sales/outbound/audit', { bizId: sob.outboundId })
  const XSFH = sobAudit.receiptNo
  assert(XSFH, '出库审核应返回发货单号')
  const sign = await post('/sales/receipt/sign', { receiptId: XSFH })
  assert(sign.arNo, '签收应生成应收单')
  await genAndPost('SALE_OUT', sob.outboundNo)
  const vSign = await genAndPost('SALE_SIGN', XSFH)
  // 销售单价 35 为含税价：应收 1122=2100（60*35），收入 500101=1858.41（2100/1.13），销项税 241.59
  assert(vSign.entries.some(e => e.accountCode === '1122' && num(e.debitAmount) === 2100),
    '签收凭证应借 1122 2100（含税售价 60*35）')

  // ===== 6. 收款 500（RECEIPT，CF01）=====
  const rc = await post('/finance/receipt/create', {
    receiptDate: today, counterpartyType: 'CUSTOMER',
    counterpartyCode: CUS.code, counterpartyName: CUS.name,
    handler: '张三', summary: '冒烟收款',
    details: [{ fundAccount: FA.name, amount: 500, remark: '货款' }],
  })
  await post('/finance/receipt/audit', { receiptId: rc.receiptId })
  const vRc = await genAndPost('RECEIPT', rc.receiptNo)
  assert(vRc.entries.some(e => e.accountCode === '1001' && num(e.debitAmount) === 500 && e.cashFlowItem === 'CF01'),
    '收款凭证应借 1001 500 带 CF01')

  // ===== 7. 付款 1000（PAYMENT，CF04）=====
  const py = await post('/finance/payment/create', {
    paymentDate: today, counterpartyType: 'SUPPLIER',
    counterpartyCode: SUP.code, counterpartyName: SUP.name,
    handler: '张三', summary: '冒烟付款',
    details: [{ fundAccount: FA.name, amount: 1000, remark: '货款' }],
  })
  await post('/finance/payment/audit', { paymentId: py.paymentId })
  const vPy = await genAndPost('PAYMENT', py.paymentNo)
  assert(vPy.entries.some(e => e.accountCode === '1001' && num(e.creditAmount) === 1000 && e.cashFlowItem === 'CF04'),
    '付款凭证应贷 1001 1000 带 CF04')

  // ===== 8. 三表取数 =====
  // 利润表：营业收入 1858.41（2100/1.13）；营业成本 2100（出库单售价取成本，业务侧口径）；净利润 = 收入−成本
  const is = await report('IS')
  assert(round2(row(is, '营业收入').amount) === 1858.41,
    `利润表营业收入应为 1858.41（2100/1.13），实际 ${row(is, '营业收入').amount}`)
  const costAmt = round2(row(is, '营业成本').amount)
  assert(costAmt === 2100, `营业成本应为 2100（60*35 出库成本口径），实际 ${costAmt}`)
  assert(round2(row(is, '净利润').amount) === round2(1858.41 - costAmt),
    '净利润应 = 营业收入 − 营业成本（期中无其他损益）')
  assert(round2(row(is, '营业收入').beginAmount) === 1858.41, '利润表年累列应与本月一致（年内首期）')

  // 现金流量表：CF01=500、CF04=1000、净增加额 -500（货币资金期末-期初勾稽）
  const cf = await report('CF')
  assert(round2(row(cf, '销售商品、提供劳务收到的现金').amount) === 500,
    `CF 销售收现应为 500，实际 ${row(cf, '销售商品').amount}`)
  assert(round2(row(cf, '购买商品、接受劳务支付的现金').amount) === 1000,
    `CF 购买付现应为 1000，实际 ${row(cf, '购买商品').amount}`)
  assert(round2(row(cf, '现金及现金等价物净增加额').amount) === -500,
    `CF 净增加额应为 -500（500 收 -1000 付），实际 ${row(cf, '净增加额').amount}`)

  // 资产负债表：期中未结转损益也应平（未分配利润行含 5xxx 余额）
  const bs = await report('BS')
  assert(bs.balance && bs.balance.balanced === true,
    `资产负债表应平衡，实际：${bs.balance && bs.balance.message}`)
  assert(round2(row(bs, '货币资金').amount) === -500,
    `BS 货币资金应为 -500，实际 ${row(bs, '货币资金').amount}`)
  assert(round2(row(bs, '应收账款').amount) === 1600,
    `BS 应收账款应为 1600（2100-500），实际 ${row(bs, '应收账款').amount}`)

  // 非法报表代码
  await expectFail(() => report('XX'), 'BS', '非法报表代码应被拒')

  // ===== 9. 业财对账：三组全平 =====
  let rc9 = await reconcile()
  assert(rc9.matched === true, '业财对账三组应全部平衡')
  const ar = group(rc9, 'ar')
  assert(round2(ar.glAmount) === 1600 && round2(ar.bizAmount) === 1600,
    `应收对账 GL/业务应均为 1600（2100-500），实际 ${ar.glAmount}/${ar.bizAmount}`)
  const arDetail = ar.details.find(d => d.party.includes(CUS.name))
  assert(arDetail && round2(arDetail.glAmount) === 1600 && round2(arDetail.bizAmount) === 1600,
    '应收明细应按客户列示 1600/1600')
  const ap = group(rc9, 'ap')
  assert(round2(ap.glAmount) === 2500 && round2(ap.bizAmount) === 2500,
    `应付对账 GL/业务应均为 2500（3500-1000），实际 ${ap.glAmount}/${ap.bizAmount}`)
  const apDetail = ap.details.find(d => d.party.includes(SUP.name))
  assert(apDetail && round2(apDetail.diff) === 0, '应付明细供应商行差额应为 0')
  const cash = group(rc9, 'cash')
  assert(round2(cash.glAmount) === -500 && round2(cash.bizAmount) === -500,
    `资金对账 GL/业务应均为 -500（500 收-1000 付），实际 ${cash.glAmount}/${cash.bizAmount}`)
  const faDetail = cash.details.find(d => d.party.includes(FA.name))
  assert(faDetail && faDetail.glAccountCode === '1001' && round2(faDetail.diff) === 0,
    '资金明细应列示冒烟账户映射 1001 且差额 0')

  // ===== 10. 制造差异：手工凭证借 1122 100 / 贷 1901 100（客户辅助）→ 对账报差 =====
  const vBad = await saveVoucher('2026-09-20', '冒烟-虚增应收', [
    { accountCode: '1122', debit: 100, auxCustomer: CUS.code },
    { accountCode: '1901', credit: 100 },
  ])
  await auditPost(vBad.voucherId || vBad.id)
  rc9 = await reconcile()
  assert(rc9.matched === false, '存在差异时对账应报不平衡')
  const arBad = group(rc9, 'ar')
  assert(arBad.matched === false && round2(arBad.diff) === 100,
    `应收差异应为 100，实际 ${arBad.diff}`)
  const arBadDetail = arBad.details.find(d => d.party.includes(CUS.name))
  assert(arBadDetail && round2(arBadDetail.glAmount) === 1700 && round2(arBadDetail.bizAmount) === 1600,
    '差异应定位到客户明细 GL 1700 / 业务 1600')

  // ===== 11. 反向凭证冲回 → 恢复平衡 =====
  const vFix = await saveVoucher('2026-09-21', '冒烟-冲回虚增应收', [
    { accountCode: '1901', debit: 100 },
    { accountCode: '1122', credit: 100, auxCustomer: CUS.code },
  ])
  await auditPost(vFix.voucherId || vFix.id)
  rc9 = await reconcile()
  assert(rc9.matched === true, '差异冲回后三组应恢复平衡')

  // ===== 12. 现金流量项目补录 =====
  // 手工费用凭证：贷 1001 200 但不指定流量项目 → 进 pending
  const vExp = await saveVoucher('2026-09-22', '冒烟-办公费现金支出（待补流量）', [
    { accountCode: '560201', debit: 200, auxDepartment: DEPT_CODE },
    { accountCode: '1001', credit: 200 },
  ])
  const vExpDetail = await auditPost(vExp.voucherId || vExp.id)
  let pending = await post('/finance/gl/report/cf-pending', { period: '202609' })
  assert(pending.length === 1, `待补录现金分录应 1 条，实际 ${pending.length}`)
  assert(num(pending[0].amount) === 200 && pending[0].direction.includes('流出'),
    '待补录分录应为 200 流出（贷方）')
  const cashEntryId = pending[0].entryId

  // 非法项目编码被拒
  await expectFail(() => post('/finance/gl/report/cf-fill', {
    items: [{ entryId: cashEntryId, cashFlowItem: 'CF99' }],
  }), '现金流量项目不存在', '非法流量项目应被拒')
  // 非现金分录被拒（取 560201 那行）
  const nonCashEntry = vExpDetail.entries.find(e => e.accountCode === '560201')
  await expectFail(() => post('/finance/gl/report/cf-fill', {
    items: [{ entryId: nonCashEntry.entryId || nonCashEntry.id, cashFlowItem: 'CF07' }],
  }), '现金类分录', '非现金分录补录应被拒')

  // 批量补录 CF07（支付其他与经营活动有关的现金）
  const fillR = await post('/finance/gl/report/cf-fill', {
    items: [{ entryId: cashEntryId, cashFlowItem: 'CF07' }],
  })
  assert(fillR.updated === 1, `补录应更新 1 条，实际 ${fillR.updated}`)
  pending = await post('/finance/gl/report/cf-pending', { period: '202609' })
  assert(pending.length === 0, `补录后待补录应为 0，实际 ${pending.length}`)

  const cf2 = await report('CF')
  assert(round2(row(cf2, '支付其他与经营活动有关的现金').amount) === 200,
    `补录后 CF07 应为 200，实际 ${row(cf2, '支付其他').amount}`)
  assert(round2(row(cf2, '现金及现金等价物净增加额').amount) === -700,
    `补录不影响资金余额，净增加额仍应 = 货币资金变动 -700，实际 ${row(cf2, '净增加额').amount}`)

  console.log('✅ gl-m6-report 全部通过：三表取数/平衡校验、业财对账（含差异定位与冲回）、现金流量补录')
}

main().catch(e => {
  console.error('❌ 冒烟失败：', e.message)
  process.exit(1)
})
