/**
 * 总账 M4（PRD-31）冒烟测试：业务钩子埋点 + 反审核联动 + 凭证导出 + 档案科目映射
 *
 * 用法（专用冒烟库，必须在 backend/ 目录启动）：
 *   java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8081 \
 *     --spring.datasource.url="jdbc:h2:file:./data/erp-smoke-gl4;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     --spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,...
 *   node development/06-testing/scripts/gl-m4-hooks-export.js
 *
 * 覆盖：
 *  1. 真实单据审核落事件（13 类）：PUR_IN/PUR_RETURN/SALE_OUT/SALE_SIGN/SALE_RETURN/SALE_RETURN_COST/
 *     RECEIPT/PAYMENT/EXPENSE/STOCK_CHECK/OTHER_OUT/OTHER_IN/DAMAGE，payload 关键字段完整；
 *  2. 费用两场景：有资金账户 → 仅 EXPENSE 事件（自动 FK 单 business_source=EXPENSE 不丢 PAYMENT）；
 *     无资金账户 → EXPENSE + 手工 PAYMENT 两张凭证；
 *  3. OTHER_OUT 内部领用→560299、活动消耗→560105；OTHER_IN 赠品→5301、期初库存不丢事件；
 *  4. 反审核联动两分支：无凭证/草稿凭证 → 双向置「已冲回」并删草稿；已过账 → 反向事件留待红字、
 *     生成后正向置「已冲销」；重新审核复用事件行（四元组不新增行）；
 *  5. 幂等：同一单据事件行数不随审核/反审核/重审膨胀；
 *  6. CSV 导出：金蝶/用友两格式表头结构 + 导出日志 + export_flag；
 *  7. 档案科目映射接口 list/save。
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
const num = v => Number(v || 0)
const round2 = v => Math.round(num(v) * 100) / 100
const today = '2026-09-08'

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

// ==================== 事件池辅助 ====================

async function findEvent(eventCode, billNo, reverse = false) {
  const page = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 300, keyword: billNo })
  return (page.records || []).find(x => x.eventCode === eventCode && x.sourceBillNo === billNo
    && String(x.reverseFlag) === (reverse ? '1' : '0'))
}
async function eventPayload(ev) {
  const r = await post('/finance/gl/event/payload', { id: ev.id })
  return r.payload
}
/** 生成单个事件凭证，断言不失败；返回事件最新状态。 */
async function genEvent(eventCode, billNo, reverse = false) {
  const ev0 = await findEvent(eventCode, billNo, reverse)
  assert(ev0, `事件 ${eventCode}/${billNo} reverse=${reverse} 未落池`)
  const r = await post('/finance/gl/event/generate', { ids: [ev0.id] })
  const ev = await findEvent(eventCode, billNo, reverse)
  if (r.fail > 0 || ev.status === '生成失败')
    throw new Error(`事件 ${eventCode}/${billNo} 生成失败：${ev.errMsg || JSON.stringify(r.results)}`)
  return ev
}
async function voucherOf(ev) {
  assert(ev.voucherId, `事件 ${ev.eventCode}/${ev.sourceBillNo} 无凭证`)
  return post('/finance/gl/voucher/detail', { id: ev.voucherId })
}
async function eventCount(eventCode, billNo) {
  const page = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 300, keyword: billNo })
  return (page.records || []).filter(x => x.eventCode === eventCode && x.sourceBillNo === billNo).length
}

async function main() {
  await login()
  const sfx = Math.random().toString(16).slice(2, 8)

  // ===== 1. 菜单含档案科目映射 =====
  const menus = await get('/system/menu/user-tree')
  const glJson = JSON.stringify(menus.find(m => m.name === '总账管理' || m.menuName === '总账管理') || {})
  assert(glJson.includes('glArchiveMapping'), '总账菜单应含 glArchiveMapping')

  // ===== 2. 基础档案 =====
  const WH = '冒烟仓-' + sfx
  const SUP = { code: 'SUP' + sfx, name: '冒烟供应商-' + sfx }
  const CUS = { code: 'CUS' + sfx, name: '冒烟客户-' + sfx }
  const G1 = { code: 'G1' + sfx, name: '冒烟商品甲-' + sfx }
  const G2 = { code: 'G2' + sfx, name: '冒烟商品乙-' + sfx }
  const FA = { code: 'FA' + sfx, name: '冒烟现金账户-' + sfx }
  const ET = { code: 'ET' + sfx, name: '冒烟办公费-' + sfx }
  const DEPT = '冒烟管理部-' + sfx
  const BATCH_A = 'BATCH-A-' + sfx
  const BATCH_GIFT = 'GIFT-' + sfx
  const BATCH_DMG = 'BATCH-DMG-' + sfx

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
  await post('/base/goods/create', {
    goodsCode: G2.code, goodsName: G2.name, spec: '1*1', categoryName: '冒烟分类-' + sfx,
    baseUnit: '箱', goodsType: '正常商品', taxRate: '13%',
  })
  await post('/base/master/save', {
    moduleCode: 'fundAccount', fundAccountCode: FA.code, fundAccountName: FA.name,
    parentCode: '01', accountType: '现金', glAccountCode: '1001', status: 'NORMAL',
  })
  await post('/base/master/save', {
    moduleCode: 'expenseType', expenseTypeCode: ET.code, expenseTypeName: ET.name,
    direction: 'OUT', glExpenseAccountCode: '560201', status: 'NORMAL',
  })
  await post('/base/master/save', {
    moduleCode: 'department', departmentCode: 'D' + sfx, departmentName: DEPT,
    headCount: 5, remark: '冒烟', status: 'NORMAL',
  })

  // ===== 3. 档案科目映射接口 =====
  const mapping = await post('/finance/gl/archive-mapping/list', {})
  assert(Array.isArray(mapping.fundAccounts) && Array.isArray(mapping.expenseTypes)
    && Array.isArray(mapping.categories) && Array.isArray(mapping.leafAccounts), '映射列表应返回四类数组')
  const faRow = mapping.fundAccounts.find(x => x.code === FA.code)
  assert(faRow && faRow.glAccountCode === '1001', '资金账户应带出 glAccountCode=1001')
  const etRow = mapping.expenseTypes.find(x => x.code === ET.code)
  assert(etRow && etRow.glAccountCode === '560201', '费用类型应带出 glExpenseAccountCode=560201')
  // save 端点幂等保存
  await post('/finance/gl/archive-mapping/save', { type: 'fund', code: FA.code, glAccountCode: '1001' })
  await post('/finance/gl/archive-mapping/save', { type: 'expense', code: ET.code, glAccountCode: '560201' })
  const catRow = mapping.categories.find(x => x.name === '冒烟分类-' + sfx)
  if (catRow) {
    await post('/finance/gl/archive-mapping/save', {
      type: 'category', code: catRow.code, glIncomeAccountCode: '500101', glCostAccountCode: '5401',
    })
  }

  // ===== 4. 启用总账 =====
  await post('/finance/gl/init/balance-save', { rows: [
    { accountCode: '1001', openDebit: 100000 },
    { accountCode: '3001', openCredit: 100000 },
  ] })
  await post('/finance/gl/init/enable', { startPeriod: '202609' })

  // ===== 5. 采购链路：订单 → 入库审核（自动 CGSH）→ 收货审核 → PUR_IN =====
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

  let ev = await findEvent('PUR_IN', CGSH)
  assert(ev, '采购收货审核应落 PUR_IN 事件')
  let pl = await eventPayload(ev)
  assert(num(pl.amount_tax_incl) > 0 && num(pl.tax_amount) >= 0, 'PUR_IN payload 含税/税额应完整')
  assert(pl.supplier_code === SUP.code, `PUR_IN 供应商编码应为 ${SUP.code}，实际 ${pl.supplier_code}`)
  assert(Array.isArray(pl.lines) && pl.lines.length === 1, 'PUR_IN 应 1 行明细')
  assert(num(pl.lines[0].qty) === 100 && pl.lines[0].goods_code === G1.code, 'PUR_IN 行商品/数量应正确')
  assert(round2(num(pl.amount_excl)) === round2(num(pl.amount_tax_incl) - num(pl.tax_amount)), 'PUR_IN 不含税=含税-税')

  // ===== 6. 反审核联动分支 A1：无凭证 → 双向已冲回；重审复用事件行 =====
  await post('/purchase/receipt/reverse-audit', { bizId: CGSH })
  ev = await findEvent('PUR_IN', CGSH)
  const evRev = await findEvent('PUR_IN', CGSH, true)
  assert(ev.status === '已冲回', `无凭证反审核后正向事件应已冲回，实际 ${ev.status}`)
  assert(evRev && evRev.status === '已冲回', `反向事件应已冲回，实际 ${evRev && evRev.status}`)
  assert(await eventCount('PUR_IN', CGSH) === 2, '反审核后 PUR_IN 应恰好 2 行（正/反）')

  // 重新审核：正向行复用重开，不新增行
  await post('/purchase/receipt/audit', { bizId: CGSH })
  ev = await findEvent('PUR_IN', CGSH)
  assert(ev.status === '待生成', `重审后正向事件应复用为待生成，实际 ${ev.status}`)
  assert(await eventCount('PUR_IN', CGSH) === 2, '重审不得新增事件行')

  // ===== 7. 销售链路：订单 → 出库审核（自动 XSFH，SALE_OUT）→ 签收（SALE_SIGN） =====
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

  ev = await findEvent('SALE_OUT', sob.outboundNo)
  assert(ev, '出库审核应落 SALE_OUT 事件')
  pl = await eventPayload(ev)
  assert(num(pl.cost_amount) > 0, `SALE_OUT 成本应>0（取库存流水），实际 ${pl.cost_amount}`)
  assert(Array.isArray(pl.lines) && pl.lines.length === 1 && num(pl.lines[0].qty) === 60, 'SALE_OUT 行数量应 60')

  const sign = await post('/sales/receipt/sign', { receiptId: XSFH })
  assert(sign.arNo, '全签收应生成应收单号')
  ev = await findEvent('SALE_SIGN', XSFH)
  assert(ev, '签收入库应落 SALE_SIGN 事件')
  pl = await eventPayload(ev)
  assert(num(pl.amount_tax_incl) > 0 && pl.customer_code === CUS.code, 'SALE_SIGN 含税金额/客户应完整')
  assert(Array.isArray(pl.lines) && pl.lines.length === 1 && num(pl.lines[0].qty) === 60, 'SALE_SIGN 行数量应 60')

  // 早期事件全部可生成凭证
  for (const [code, no] of [['PUR_IN', CGSH], ['SALE_OUT', sob.outboundNo], ['SALE_SIGN', XSFH]]) {
    const e = await genEvent(code, no)
    const v = await voucherOf(e)
    assert(v.status === '草稿' && v.source === '自动', `${code} 凭证应为自动草稿`)
  }

  // ===== 8. 收款单 RECEIPT → 生成 → 审核 → 过账（为导出/红冲分支准备） =====
  const rc = await post('/finance/receipt/create', {
    receiptDate: today, counterpartyType: 'CUSTOMER',
    counterpartyCode: CUS.code, counterpartyName: CUS.name,
    handler: '张三', summary: '冒烟收款',
    details: [{ fundAccount: FA.name, amount: 500, remark: '货款' }],
  })
  await post('/finance/receipt/audit', { receiptId: rc.receiptId })
  ev = await findEvent('RECEIPT', rc.receiptNo)
  assert(ev, '收款审核应落 RECEIPT 事件')
  pl = await eventPayload(ev)
  assert(num(pl.amount_tax_incl) === 500, 'RECEIPT 金额应为 500')
  assert(pl.fund_subject_code === '1001', `RECEIPT 资金科目应为 1001，实际 ${pl.fund_subject_code}`)
  ev = await genEvent('RECEIPT', rc.receiptNo)
  let v = await voucherOf(ev)
  assert(v.entries.some(e => e.accountCode === '1001' && num(e.debitAmount) === 500 && e.cashFlowItem === 'CF01'),
    '收款凭证应借 1001 500 带 CF01')
  assert(v.entries.some(e => ['1122', '1221'].includes(e.accountCode) && num(e.creditAmount) === 500),
    '收款凭证应贷 1122/1221 500')
  await post('/finance/gl/voucher/audit', { id: ev.voucherId })
  await post('/finance/gl/voucher/post', { id: ev.voucherId })
  v = await post('/finance/gl/voucher/detail', { id: ev.voucherId })
  assert(v.status === '已过账', '凭证过账后应已过账')

  // ===== 9. CSV 导出（金蝶/用友） =====
  async function exportCsv(format) {
    const res = await fetch(`${BASE}/finance/gl/export/csv`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + authToken },
      body: JSON.stringify({ format, periodFrom: '202609', periodTo: '202609' }),
    })
    const text = await res.text()
    if (text.trimStart().startsWith('{')) {
      const j = JSON.parse(text)
      throw new Error('导出失败：' + (j.message || res.status))
    }
    return text.replace(/^﻿/, '')
  }
  const csvKd = await exportCsv('KINGDEE')
  const headerKd = csvKd.split('\n')[0]
  assert(headerKd.includes('凭证日期') && headerKd.includes('凭证字') && headerKd.includes('科目代码')
    && headerKd.includes('核算项目'), `金蝶表头结构不符：${headerKd}`)
  assert(csvKd.includes(v.voucherNo), '金蝶 CSV 应含已过账凭证号')
  const csvYy = await exportCsv('YONYOU')
  const headerYy = csvYy.split('\n')[0]
  assert(headerYy.includes('凭证日期') && headerYy.includes('凭证号') && headerYy.includes('附单据数')
    && headerYy.includes('科目编码') && !headerYy.includes('科目代码'), `用友表头结构不符：${headerYy}`)
  const logs = await post('/finance/gl/export/logs', {})
  assert(logs.length >= 2, `导出日志应至少 2 条，实际 ${logs.length}`)
  assert(logs.some(l => l.exportFormat === 'KINGDEE' && num(l.voucherCount) >= 1), '日志应有金蝶记录')
  assert(logs.some(l => l.exportFormat === 'YONYOU' && num(l.entryCount) >= 1), '日志应用友记录')
  const byBill = await post('/finance/gl/voucher/by-bill', { billNo: rc.receiptNo })
  assert(byBill.some(x => x.exportFlag && x.status === '已过账'), '已导出凭证应带 export_flag')

  // ===== 10. 反审核联动分支 B：已过账 → 反向事件留待红字 → 生成红票 → 正向已冲销 =====
  await post('/finance/receipt/cancel-audit', { receiptId: rc.receiptId })
  ev = await findEvent('RECEIPT', rc.receiptNo)
  const evRevRc = await findEvent('RECEIPT', rc.receiptNo, true)
  assert(ev.status === '已生成', `过账后反审核正向事件应保持已生成，实际 ${ev.status}`)
  assert(evRevRc && evRevRc.status === '待生成', `反向事件应留待生成红字，实际 ${evRevRc && evRevRc.status}`)
  assert(String(evRevRc.warnMsg || '').includes('红字'), `反向事件应提示红字冲销：${evRevRc.warnMsg}`)
  const revDone = await genEvent('RECEIPT', rc.receiptNo, true)
  const vRed = await voucherOf(revDone)
  assert(vRed.isRed === true, '红字凭证应 isRed')
  assert(vRed.entries.some(e => e.accountCode === '1001' && num(e.debitAmount) === -500),
    '红字凭证 1001 应为 -500')
  ev = await findEvent('RECEIPT', rc.receiptNo)
  assert(ev.status === '已冲销', `红票生成后正向事件应已冲销，实际 ${ev.status}`)

  // 重新审核收款单：正向行（已冲销）复用重开
  await post('/finance/receipt/audit', { receiptId: rc.receiptId })
  ev = await findEvent('RECEIPT', rc.receiptNo)
  assert(ev.status === '待生成', `重审收款后正向事件应复用为待生成，实际 ${ev.status}`)
  assert(await eventCount('RECEIPT', rc.receiptNo) === 2, '收款事件应始终恰好 2 行')
  await genEvent('RECEIPT', rc.receiptNo)

  // ===== 11. 费用场景 A：有资金账户 → 仅 EXPENSE 事件（自动 FK 不丢 PAYMENT） =====
  const expA = await post('/finance/expense/create', {
    expenseDate: today, direction: 'OUT',
    counterpartyType: 'SUPPLIER', counterpartyCode: SUP.code, counterpartyName: SUP.name,
    handler: '张三', department: DEPT, fundAccount: FA.name, remark: '冒烟费用-有账户',
    details: [{
      expenseType: ET.name, qty: 1, price: 300, amount: 300,
      taxRate: 0, taxAmount: 0, excludingTaxAmount: 300, remark: '办公费',
    }],
  })
  await post('/finance/expense/audit', { expenseId: expA.expenseId })
  ev = await findEvent('EXPENSE', expA.expenseNo)
  assert(ev, '费用单审核应落 EXPENSE 事件')
  pl = await eventPayload(ev)
  assert(num(pl.amount_tax_incl) === 300, 'EXPENSE 含税金额应 300')
  assert(pl.fund_subject_code === '1001', 'EXPENSE 资金科目应 1001')
  assert(Array.isArray(pl.lines) && pl.lines.length === 1 && pl.lines[0].subject_code === '560201',
    'EXPENSE 行应带费用类型科目 560201')
  assert(pl.lines[0].expense_type_name === ET.name, 'EXPENSE 行应带费用类型名')
  // 自动生单（business_source=EXPENSE）不得丢 PAYMENT 事件
  const payPage = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 300, eventCode: 'PAYMENT' })
  assert((payPage.records || []).length === 0, '费用联动自动付款单不应丢 PAYMENT 事件')
  ev = await genEvent('EXPENSE', expA.expenseNo)
  v = await voucherOf(ev)
  assert(v.entries.some(e => e.accountCode === '560201' && num(e.debitAmount) === 300 && e.auxDepartment),
    '费用凭证应借 560201 300 挂部门')
  assert(v.entries.some(e => e.accountCode === '1001' && num(e.creditAmount) === 300 && e.cashFlowItem === 'CF07'),
    '费用凭证应贷 1001 300 带 CF07')

  // ===== 12. 费用场景 B：无资金账户 → EXPENSE 贷应付；手工付款 → PAYMENT 两凭证 =====
  const expB = await post('/finance/expense/create', {
    expenseDate: today, direction: 'OUT',
    counterpartyType: 'SUPPLIER', counterpartyCode: SUP.code, counterpartyName: SUP.name,
    handler: '张三', department: DEPT, remark: '冒烟费用-无账户',
    details: [{
      expenseType: ET.name, qty: 1, price: 200, amount: 200,
      taxRate: 0, taxAmount: 0, excludingTaxAmount: 200, remark: '办公费',
    }],
  })
  await post('/finance/expense/audit', { expenseId: expB.expenseId })
  ev = await genEvent('EXPENSE', expB.expenseNo)
  v = await voucherOf(ev)
  assert(v.entries.some(e => e.accountCode === '2202' && num(e.creditAmount) === 200 && e.auxSupplier === SUP.code),
    '无账户费用应贷 2202 挂供应商')
  assert(!v.entries.find(e => e.accountCode === '1001'), '无账户费用凭证不应出现资金科目')

  const pay = await post('/finance/payment/create', {
    paymentDate: today, counterpartyType: 'SUPPLIER',
    counterpartyCode: SUP.code, counterpartyName: SUP.name,
    handler: '张三', summary: '冒烟付款',
    details: [{ fundAccount: FA.name, amount: 200, remark: '付费用款' }],
  })
  await post('/finance/payment/audit', { paymentId: pay.paymentId })
  ev = await findEvent('PAYMENT', pay.paymentNo)
  assert(ev, '付款审核应落 PAYMENT 事件')
  pl = await eventPayload(ev)
  assert(num(pl.amount_tax_incl) === 200 && pl.fund_subject_code === '1001', 'PAYMENT 金额/资金科目应完整')
  ev = await genEvent('PAYMENT', pay.paymentNo)
  v = await voucherOf(ev)
  assert(v.entries.some(e => e.accountCode === '2202' && num(e.debitAmount) === 200), '付款凭证应借 2202 200')
  assert(v.entries.some(e => e.accountCode === '1001' && num(e.creditAmount) === 200), '付款凭证应贷 1001 200')

  // ===== 13. OTHER_OUT 两类型 → 560299 / 560105 =====
  async function otherOutbound(typeCode, qty, remark) {
    const o = await post('/inventory/other-outbound/create', {
      warehouse: WH, customer: CUS.name, outboundType: typeCode, billDate: today, remark,
      details: [{ goodsCode: G1.code, goodsName: G1.name, spec: '1*6', unitName: '箱', qty, price: 35, batchNo: BATCH_A }],
    })
    await post('/inventory/other-outbound/audit', { bizId: o.outboundNo })
    return o.outboundNo
  }
  const OO1 = await otherOutbound('0', 2, '内部领用')
  const OO2 = await otherOutbound('3', 1, '活动消耗')
  let evO1 = await findEvent('OTHER_OUT', OO1)
  let evO2 = await findEvent('OTHER_OUT', OO2)
  assert(evO1 && evO2, '其他出库审核应落 OTHER_OUT 事件')
  pl = await eventPayload(evO1)
  assert(pl.biz_type_code === '0' && pl.biz_type_name === '内部领用', `OTHER_OUT 类型字段应完整：${JSON.stringify(pl)}`)
  evO1 = await genEvent('OTHER_OUT', OO1)
  v = await voucherOf(evO1)
  assert(v.entries.some(e => e.accountCode === '560299' && num(e.debitAmount) > 0), '内部领用应借 560299')
  assert(v.entries.some(e => e.accountCode === '1405' && num(e.creditAmount) > 0), '应贷 1405 库存商品')
  evO2 = await genEvent('OTHER_OUT', OO2)
  v = await voucherOf(evO2)
  assert(v.entries.some(e => e.accountCode === '560105' && num(e.debitAmount) > 0), '活动消耗应借 560105')

  // ===== 14. 反审核联动分支 A2：草稿凭证自动删除 + 双向已冲回；重审再生成 =====
  const draftVoucherId = evO2.voucherId
  await post('/inventory/other-outbound/reverse-audit', { bizId: OO2 })
  evO2 = await findEvent('OTHER_OUT', OO2)
  const evO2rev = await findEvent('OTHER_OUT', OO2, true)
  assert(evO2.status === '已冲回' && evO2rev.status === '已冲回',
    `草稿反审核双向应已冲回：${evO2.status}/${evO2rev && evO2rev.status}`)
  const detailRes = await fetch(`${BASE}/finance/gl/voucher/detail`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + authToken },
    body: JSON.stringify({ id: draftVoucherId }),
  })
  const detailJson = await detailRes.json()
  assert(detailJson.code !== '0' && String(detailJson.message).includes('不存在'),
    '草稿凭证应已被联动删除')
  // 重新审核 → 事件行复用 → 可再生成
  await post('/inventory/other-outbound/audit', { bizId: OO2 })
  evO2 = await findEvent('OTHER_OUT', OO2)
  assert(evO2.status === '待生成', '重审其他出库后事件应复用为待生成')
  assert(await eventCount('OTHER_OUT', OO2) === 2, '其他出库事件应恰好 2 行')
  await genEvent('OTHER_OUT', OO2)

  // ===== 15. OTHER_IN：赠品→事件→5301；期初库存→不丢事件 =====
  const oiGift = await post('/inventory/other-inbound/create', {
    warehouse: WH, supplier: SUP.name, inboundType: '2', billDate: today, remark: '赠品入库',
    details: [{
      goodsCode: G1.code, goodsName: G1.name, spec: '1*6', unitName: '箱',
      qty: 10, price: 0, costPrice: 20, batchNo: BATCH_GIFT, productionDate: '2026-09-01',
    }],
  })
  await post('/inventory/other-inbound/audit', { bizId: oiGift.inboundNo })
  ev = await findEvent('OTHER_IN', oiGift.inboundNo)
  assert(ev, '赠品入库应落 OTHER_IN 事件')
  pl = await eventPayload(ev)
  assert(pl.biz_type_code === '2' && pl.biz_type_name.includes('赠'), `赠品类型字段应完整：${JSON.stringify(pl)}`)
  ev = await genEvent('OTHER_IN', oiGift.inboundNo)
  v = await voucherOf(ev)
  assert(v.entries.some(e => e.accountCode === '5301' && num(e.creditAmount) > 0), '赠品入库应贷 5301 营业外收入')
  assert(v.entries.some(e => e.accountCode === '1405' && num(e.debitAmount) > 0), '赠品入库应借 1405')

  const oiInit = await post('/inventory/other-inbound/create', {
    warehouse: WH, supplier: SUP.name, inboundType: '0', billDate: today, remark: '期初库存',
    details: [{
      goodsCode: G1.code, goodsName: G1.name, spec: '1*6', unitName: '箱',
      qty: 5, price: 0, costPrice: 20, batchNo: 'INIT-' + sfx,
    }],
  })
  await post('/inventory/other-inbound/audit', { bizId: oiInit.inboundNo })
  ev = await findEvent('OTHER_IN', oiInit.inboundNo)
  assert(!ev, '期初库存入库不应落 ANY 事件')

  // ===== 16. STOCK_CHECK 盘亏 =====
  const sheet = await post('/inventory/stock-take/create', {
    warehouse: WH, countType: '2', itemNos: [G1.code], countDate: today, remark: '冒烟盘点',
  })
  const sd = await post('/inventory/stock-take/detail', { sheetNo: sheet.sheetNo })
  assert(sd.details && sd.details.length >= 1, '盘点应生成明细')
  // 盘点明细按批次生成多行，每行实盘都必须填（未填按 0 算即全盘亏）；
  // 第一行盘亏 1，其余行账实相符
  const countLines = sd.details.map((d, i) => ({
    detailId: d.detailId,
    realQty: i === 0 ? num(d.bookQty) - 1 : num(d.bookQty),
    diffRemark: i === 0 ? '盘亏1' : '',
  }))
  assert(num(sd.details[0].bookQty) >= 1, `盘亏测试需要账面库存≥1，实际 ${sd.details[0].bookQty}`)
  await post('/inventory/stock-take/update-real', { sheetNo: sheet.sheetNo, details: countLines })
  await post('/inventory/stock-take/audit', { sheetNo: sheet.sheetNo })
  ev = await findEvent('STOCK_CHECK', sheet.sheetNo)
  assert(ev, '盘点审核应落 STOCK_CHECK 事件')
  pl = await eventPayload(ev)
  assert(Array.isArray(pl.loss_lines) && pl.loss_lines.length === 1, `盘亏应 1 行 loss_lines：${JSON.stringify(pl)}`)
  assert(Array.isArray(pl.profit_lines) && pl.profit_lines.length === 0, '不应有盘盈行')
  await genEvent('STOCK_CHECK', sheet.sheetNo)

  // ===== 17. DAMAGE 大额报损 =====
  // G2 先采购入库（高价商品，确保成本 ≥ 2000 阈值触发 large_loss）
  const po2 = await post('/purchase/order/create', {
    supplierCode: SUP.code, supplierName: SUP.name, buyer: '张三', warehouseId: WH,
    billDate: today,
    details: [{
      goodsCode: G2.code, goodsName: G2.name, spec: '1*1', unitId: '箱', unitLevel: 1, convertQty: 1,
      qty: 5, baseQty: 5, price: 1000, amount: 5000, taxRate: '13%',
    }],
  })
  await post('/purchase/order/audit', { orderId: po2.orderId })
  const pib2 = await post('/purchase/inbound/create', {
    sourceOrder: po2.orderNo, supplier: SUP.name, warehouse: WH, billDate: today,
    details: [{ goodsCode: G2.code, goodsName: G2.name, unitName: '箱', batchNo: BATCH_DMG, receivedQty: 5, price: 1000 }],
  })
  const pib2Audit = await post('/purchase/inbound/audit', { bizId: pib2.inboundId })
  await post('/purchase/receipt/audit', { bizId: pib2Audit.receiptNo })

  const dmg = await post('/inventory/damage/create', {
    warehouse: WH, billDate: today, remark: '冒烟大额报损',
    details: [{ goodsCode: G2.code, goodsName: G2.name, spec: '1*1', unitName: '箱', qty: 3, price: 1000, batchNo: BATCH_DMG }],
  })
  const dmgAudit = await post('/inventory/damage/audit', { bizId: dmg.damageNo })
  assert(num(dmgAudit.costAmount) >= 2000, `报损成本应≥2000 触发大额，实际 ${dmgAudit.costAmount}`)
  ev = await findEvent('DAMAGE', dmg.damageNo)
  assert(ev, '报损审核应落 DAMAGE 事件')
  pl = await eventPayload(ev)
  assert(pl.large_loss === true, `large_loss 应为 true：${JSON.stringify(pl)}`)
  assert(num(pl.cost_amount) >= 2000, 'DAMAGE 成本应≥2000')
  await genEvent('DAMAGE', dmg.damageNo)

  // ===== 18. 采购退货链：申请审核→退货出库审核→退货单审核 → PUR_RETURN（红字同构） =====
  const pra = await post('/purchase/return-apply/create', {
    supplier: SUP.name, supplierCode: SUP.code, warehouse: WH, billDate: today, returnReason: '质量问题',
    details: [{
      goodsCode: G1.code, goodsName: G1.name, spec: '1*6', unitName: '箱',
      qty: 5, price: 35, batchNo: BATCH_A, returnMode: 'BY_GOODS', taxRate: '13%',
    }],
  })
  const praAudit = await post('/purchase/return-apply/audit', { bizId: pra.applyNo })
  const pobAudit = await post('/purchase/return-outbound/audit', { bizId: praAudit.outboundNo })
  const CGTH = pobAudit.returnNo
  assert(CGTH, '退货出库审核应返回采购退货单号')
  await post('/purchase/return/audit', { bizId: CGTH })
  ev = await findEvent('PUR_RETURN', CGTH)
  assert(ev, '采购退货审核应落 PUR_RETURN 事件')
  pl = await eventPayload(ev)
  assert(num(pl.amount_tax_incl) < 0, `采购退货 payload 应为负（红字同构）：${pl.amount_tax_incl}`)
  assert(Array.isArray(pl.lines) && pl.lines.length === 1 && num(pl.lines[0].qty) === -5, '退货行数量应为 -5')
  assert(pl.supplier_code === SUP.code, 'PUR_RETURN 供应商应完整')
  ev = await genEvent('PUR_RETURN', CGTH)
  v = await voucherOf(ev)
  assert(round2(v.debitTotal) === round2(v.creditTotal), '采购退货凭证借贷应平衡')
  // 红字同构：L3 为「贷 2202」行，负金额落在贷方（贷方红字=冲减应付）
  assert(v.entries.some(e => e.accountCode === '2202' && num(e.creditAmount) < 0), '采购退货应红字冲应付 2202（贷方红字）')

  // ===== 19. 销售退货链：申请→确认→推送仓库→退货入库审核（SALE_RETURN_COST）→退货单审核（SALE_RETURN） =====
  const sra = await post('/sales/return-order/create', {
    customer: CUS.name, customerCode: CUS.code, warehouse: WH,
    returnType: 'WAREHOUSE', returnReason: '客户退货',
    details: [{
      goodsCode: G1.code, goodsName: G1.name, spec: '1*6', unitName: '箱',
      qty: 5, price: 35, taxRate: '13%', returnMode: 'BY_GOODS', batchNo: BATCH_A,
    }],
  })
  await post('/sales/return-order/confirm', { bizId: sra.applyNo })
  const push = await post('/sales/return-order/push-warehouse', { bizId: sra.applyNo })
  const THRK = push.inboundNo
  assert(THRK, '推送仓库应返回退货入库单号')
  await post('/sales/return-inbound/audit', { bizId: THRK })
  ev = await findEvent('SALE_RETURN_COST', THRK)
  assert(ev, '退货入库审核应落 SALE_RETURN_COST 事件')
  pl = await eventPayload(ev)
  assert(num(pl.cost_amount) > 0, `退货成本应>0：${pl.cost_amount}`)
  await genEvent('SALE_RETURN_COST', THRK)

  await post('/sales/return-order/audit', { bizId: sra.applyNo })
  ev = await findEvent('SALE_RETURN', sra.applyNo)
  assert(ev, '销售退货审核应落 SALE_RETURN 事件')
  pl = await eventPayload(ev)
  assert(num(pl.amount_tax_incl) < 0, `销售退货 payload 应为负：${pl.amount_tax_incl}`)
  assert(Array.isArray(pl.lines) && pl.lines.length === 1 && num(pl.lines[0].qty) === -5, '销售退货行数量应为 -5')
  assert(pl.customer_code === CUS.code, 'SALE_RETURN 客户应完整')
  ev = await genEvent('SALE_RETURN', sra.applyNo)
  v = await voucherOf(ev)
  assert(round2(v.debitTotal) === round2(v.creditTotal), '销售退货凭证借贷应平衡')

  // ===== 20. 收尾：事件池不得再有生成失败 =====
  const finalGen = await post('/finance/gl/event/generate', { allPending: true })
  assert(finalGen.fail === 0, `收尾批量生成不应有失败：${JSON.stringify(finalGen.results.filter(x => x.status === '生成失败'))}`)

  console.log('GL M4 (business hooks + reverse linkage + CSV export + archive mapping) smoke test PASSED ✅')
}

main().catch(e => { console.error('SMOKE FAILED ❌', e.message); process.exit(1) })
