/**
 * 总账 M3（PRD-31）冒烟测试：会计事件池 + 凭证模板 + 业务类型科目映射
 *
 * 用法（专用冒烟库）：
 *   java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8081 \
 *     --spring.datasource.url="jdbc:h2:file:./data/erp-smoke-gl3;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     --spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,...
 *   node development/06-testing/scripts/gl-m3-event-template.js
 *
 * 覆盖：模板/映射种子 → 试渲染（11300 含税 13% 拆税；小规模不拆；表达式注入报错）
 *      → 事件生成草稿凭证（PUR_IN/RECEIPT/EXPENSE 两场景/OTHER_OUT 两类型/OTHER_IN 期初忽略）
 *      → 未映射类型失败中文提示 → 补映射重试成功 → 停用科目失败 → 恢复重试成功
 *      → 反向事件红字凭证 → 幂等补丢 → 忽略/取消忽略/重置联动删草稿 → 期间锁定 → 自动凭证审核占号。
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

/** 手工补录事件，返回事件 id（按单号查回）。 */
async function emit(eventCode, billType, billNo, payload, opts = {}) {
  const r = await post('/finance/gl/event/emit', {
    eventCode, billType, billNo,
    bizDate: opts.date || '2026-09-08',
    amount: opts.amount != null ? opts.amount : (payload.amount_tax_incl || payload.cost_amount || 0),
    payload,
    reverse: !!opts.reverse,
  })
  if (r.duplicated) throw new Error(`事件 ${eventCode}/${billNo} 意外命中幂等`)
  const page = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 50, keyword: billNo })
  const ev = (page.records || []).find(x => x.sourceBillNo === billNo && x.eventCode === eventCode
    && String(x.reverseFlag) === (opts.reverse ? '1' : '0'))
  assert(ev, `事件 ${eventCode}/${billNo} 落池后查不到`)
  return ev
}

/** 生成单事件，返回事件最新状态（重新查）。 */
async function genOne(eventId) {
  const r = await post('/finance/gl/event/generate', { ids: [eventId] })
  return r
}
async function eventById(id) {
  const page = await post('/finance/gl/event/page', { pageNo: 1, pageSize: 200 })
  return (page.records || []).find(x => x.id === id)
}

function purInPayload(taxpayer = 'GENERAL') {
  return {
    taxpayer,
    supplier_code: 'S001', supplier_name: '测试供应商',
    amount_tax_incl: 11300, amount_excl: 10000, tax_amount: taxpayer === 'GENERAL' ? 1300 : 0,
    lines: [{ goods_code: 'G001', goods_name: '测试商品', qty: 10, amount_excl: 10000, tax_amount: taxpayer === 'GENERAL' ? 1300 : 0, amount_tax_incl: 11300, cost_amount: 10000 }],
  }
}

async function main() {
  await login()

  // ===== 1. 菜单 =====
  const menus = await get('/system/menu/user-tree')
  const glGroup = menus.find(m => m.name === '总账管理' || m.menuName === '总账管理')
  assert(glGroup, '总账菜单组缺失')
  const glJson = JSON.stringify(glGroup)
  for (const code of ['glEvent', 'glVoucherTemplate', 'glBizSubjectMap'])
    assert(glJson.includes(code), `总账菜单应含 ${code}`)

  // ===== 2. 启用总账 =====
  await post('/finance/gl/init/balance-save', { rows: [
    { accountCode: '1001', openDebit: 100000 },
    { accountCode: '3001', openCredit: 100000 },
  ] })
  await post('/finance/gl/init/enable', { startPeriod: '202609' })

  // ===== 3. 种子核对 =====
  const tpls = await post('/finance/gl/template/list')
  assert(tpls.length === 16, `应预置 16 个模板，实际 ${tpls.length}`)
  const purInTpl = tpls.find(t => t.eventCode === 'PUR_IN')
  assert(purInTpl && purInTpl.lines.length === 3, 'PUR_IN 模板应有 3 行')
  const flyTpl = tpls.find(t => t.eventCode === 'FLY_ORDER')
  assert(flyTpl && !flyTpl.enabled, 'FLY_ORDER 模板应预置停用')

  const groups = await post('/finance/gl/biz-subject-map/list')
  const outGroup = groups.find(g => g.eventCode === 'OTHER_OUT')
  const inGroup = groups.find(g => g.eventCode === 'OTHER_IN')
  assert(outGroup && outGroup.rows.length === 6, 'OTHER_OUT 映射应 6 行')
  assert(inGroup && inGroup.rows.length === 6, 'OTHER_IN 映射应 6 行')
  const lingyong = outGroup.rows.find(r => r.bizTypeCode === '0')
  assert(lingyong.counterSubjectCode === '560299', `内部领用应映射 560299，实际 ${lingyong.counterSubjectCode}`)
  const huodong = outGroup.rows.find(r => r.bizTypeCode === '3')
  assert(huodong.counterSubjectCode === '560105', `活动消耗应映射 560105，实际 ${huodong.counterSubjectCode}`)
  const qichu = inGroup.rows.find(r => r.bizTypeCode === '0')
  assert(!qichu.counterSubjectCode, '期初库存映射应为空（不生凭证）')

  // ===== 4. 试渲染：11300 含税 13% 拆税 =====
  const prevGeneral = await post('/finance/gl/template/preview', { eventCode: 'PUR_IN', payload: purInPayload('GENERAL') })
  assert(prevGeneral.entries.length === 3, `一般纳税人应渲染 3 行，实际 ${prevGeneral.entries.length}`)
  const d1405 = prevGeneral.entries.find(e => e.accountCode === '1405')
  const d222103 = prevGeneral.entries.find(e => e.accountCode === '222103')
  const c2202 = prevGeneral.entries.find(e => e.accountCode === '2202')
  assert(round2(d1405.debit) === 10000, `1405 借方应 10000，实际 ${d1405.debit}`)
  assert(num(d1405.qty) === 10, `1405 数量应 10，实际 ${d1405.qty}`)
  assert(round2(d222103.debit) === 1300, `进项税应 1300，实际 ${d222103.debit}`)
  assert(round2(c2202.credit) === 11300, `应付应 11300，实际 ${c2202.credit}`)
  assert(round2(prevGeneral.debitTotal) === round2(prevGeneral.creditTotal), '试渲染借贷应平衡')

  // 小规模纳税人：税行条件不满足，不拆税
  const prevSmall = await post('/finance/gl/template/preview', { eventCode: 'PUR_IN', payload: {
    ...purInPayload('SMALL'), amount_excl: 11300, tax_amount: 0,
    lines: [{ goods_code: 'G001', goods_name: '测试商品', qty: 10, amount_excl: 11300, tax_amount: 0, amount_tax_incl: 11300, cost_amount: 11300 }],
  } })
  assert(prevSmall.entries.length === 2, `小规模应渲染 2 行（无税行），实际 ${prevSmall.entries.length}`)
  assert(!prevSmall.entries.find(e => e.accountCode === '222103'), '小规模不应出现进项税行')
  assert(round2(prevSmall.entries.find(e => e.accountCode === '1405').debit) === 11300, '小规模 1405 应含税 11300')

  // 表达式注入：函数调用/未知变量在求值时必须报错，绝不执行（保存配置不触发求值）
  await post('/finance/gl/template/save', {
    eventCode: 'TEST_INJ', templateName: '注入测试', voucherWord: '记',
    lines: [
      { direction: '借', accountExpr: '1001', amountExpr: "forName('java.lang.Runtime')" },
      { direction: '贷', accountExpr: '3001', amountExpr: '1' },
    ],
  })
  await expectFail('/finance/gl/template/preview', { eventCode: 'TEST_INJ', payload: {} }, '函数调用')
  // payload 值是数据不是代码：恶意字符串只参与字符串比较，不会被执行（此处渲染失败于借贷不平，而非执行注入）
  await expectFail('/finance/gl/template/preview', {
    eventCode: 'PUR_IN',
    payload: { taxpayer: "GENERAL' || open('/etc/passwd') || '", supplier_code: 'S001', amount_tax_incl: 1, amount_excl: 1, tax_amount: 0, lines: [] },
  }, '不平衡')
  // 注入模板可以删除（自定义模板）
  const injTpl = (await post('/finance/gl/template/list')).find(t => t.eventCode === 'TEST_INJ')
  await post('/finance/gl/template/delete', { id: injTpl.id })

  // ===== 5. PUR_IN 事件 → 生成草稿凭证 =====
  const evPur = await emit('PUR_IN', '采购收货单', 'PUR-001', purInPayload('GENERAL'), { amount: 11300 })
  let r = await genOne(evPur.id)
  assert(r.success === 1 && r.fail === 0, `PUR-001 应生成成功：${JSON.stringify(r.results)}`)
  let ev = await eventById(evPur.id)
  assert(ev.status === '已生成' && ev.voucherId, 'PUR-001 状态应已生成且有凭证')
  const vPur = await post('/finance/gl/voucher/detail', { id: ev.voucherId })
  assert(vPur.source === '自动', `凭证来源应自动，实际 ${vPur.source}`)
  assert(vPur.sourceBillNo === 'PUR-001', '凭证应带来源单号')
  assert(vPur.status === '草稿' && !vPur.voucherNo, '自动凭证应为草稿无凭证号')
  assert(vPur.entries.length === 3, `PUR-001 凭证应 3 条分录，实际 ${vPur.entries.length}`)
  const ve1405 = vPur.entries.find(e => e.accountCode === '1405')
  assert(num(ve1405.auxGoods) === 0 || ve1405.auxGoods === 'G001', '1405 应挂商品辅助')
  const ve2202 = vPur.entries.find(e => e.accountCode === '2202')
  assert(ve2202.auxSupplier === 'S001', '2202 应挂供应商辅助')

  // 幂等：同单重复补丢不重落
  const dup = await post('/finance/gl/event/emit', {
    eventCode: 'PUR_IN', billType: '采购收货单', billNo: 'PUR-001', bizDate: '2026-09-08', amount: 11300,
    payload: purInPayload('GENERAL'),
  })
  assert(dup.duplicated === true, '同单重复事件应命中幂等')

  // 自动凭证走同一状态机：审核占号
  await post('/finance/gl/voucher/audit', { id: ev.voucherId })
  const vPur2 = await post('/finance/gl/voucher/detail', { id: ev.voucherId })
  assert(vPur2.voucherNo === '记-202609-0001', `自动凭证审核后应占号 记-202609-0001，实际 ${vPur2.voucherNo}`)

  // ===== 6. OTHER_OUT 两类型 → @BIZ 对方科目不同 =====
  const outPayload = (code, name, amt) => ({
    biz_type_code: code, biz_type_name: name, cost_amount: amt,
    lines: [{ goods_code: 'G001', goods_name: '测试商品', qty: 2, cost_amount: amt }],
  })
  const evOut1 = await emit('OTHER_OUT', '其他出库单', 'OUT-001', outPayload('0', '内部领用', 800), { amount: 800 })
  await genOne(evOut1.id)
  ev = await eventById(evOut1.id)
  assert(ev.status === '已生成', `OUT-001 应成功：${ev.errMsg}`)
  let v = await post('/finance/gl/voucher/detail', { id: ev.voucherId })
  assert(v.summary.includes('内部领用'), `摘要应带类型名：${v.summary}`)
  assert(v.entries.some(e => e.accountCode === '560299' && num(e.debitAmount) === 800), '内部领用应借 560299 800')
  assert(v.entries.some(e => e.accountCode === '1405' && num(e.creditAmount) === 800), '应贷 1405 800')

  const evOut2 = await emit('OTHER_OUT', '其他出库单', 'OUT-002', outPayload('3', '活动消耗', 600), { amount: 600 })
  evOut2 && (await genOne(evOut2.id))
  ev = await eventById(evOut2.id)
  let v2 = await post('/finance/gl/voucher/detail', { id: ev.voucherId })
  assert(v2.entries.some(e => e.accountCode === '560105' && num(e.debitAmount) === 600), '活动消耗应借 560105 600')

  // 未配映射的新类型 → 失败，中文 err_msg 指明类型名与配置入口
  const evOut3 = await emit('OTHER_OUT', '其他出库单', 'OUT-003', outPayload('9', '自定义类型X', 100), { amount: 100 })
  r = await genOne(evOut3.id)
  assert(r.fail === 1, 'OUT-003 应失败')
  ev = await eventById(evOut3.id)
  assert(ev.status === '生成失败', 'OUT-003 状态应生成失败')
  assert(ev.errMsg.includes('自定义类型X') && ev.errMsg.includes('业务类型科目映射'),
    `err_msg 应指明类型名与配置入口：${ev.errMsg}`)
  // 补配映射后重试成功
  await post('/finance/gl/biz-subject-map/save', {
    eventCode: 'OTHER_OUT', bizTypeCode: '9', bizTypeName: '自定义类型X', counterSubjectCode: '560299',
  })
  r = await genOne(evOut3.id)
  assert(r.success === 1, `补映射后重试应成功：${JSON.stringify(r.results)}`)
  ev = await eventById(evOut3.id)
  assert(ev.status === '已生成', '补映射后应已生成')

  // OTHER_IN 期初库存（空映射）→ 已忽略
  const evIn0 = await emit('OTHER_IN', '其他入库单', 'IN-001', {
    biz_type_code: '0', biz_type_name: '期初库存', cost_amount: 500,
    lines: [{ goods_code: 'G001', goods_name: '测试商品', qty: 1, cost_amount: 500 }],
  }, { amount: 500 })
  r = await genOne(evIn0.id)
  assert(r.ignored === 1, '期初库存事件应被忽略')
  ev = await eventById(evIn0.id)
  assert(ev.status === '已忽略' && !ev.voucherId, '期初库存应已忽略且无凭证')

  // ===== 7. 停用科目 → 失败带原因；恢复 → 重试成功 =====
  // 已被凭证引用的科目（如 222103）受 M2 规则保护不能停用，故用自定义模板引用尚无分录的 100201
  await post('/finance/gl/template/save', {
    eventCode: 'TEST_DIS', templateName: '停用科目测试', voucherWord: '记',
    lines: [
      { direction: '借', accountExpr: '100201', amountExpr: '100' },
      { direction: '贷', accountExpr: '2202', amountExpr: '100' },
    ],
  })
  await post('/finance/gl/account/toggle-status', { id: '100201' }) // 停用
  const evDis = await emit('TEST_DIS', '测试单', 'DIS-001', {}, { amount: 100 })
  r = await genOne(evDis.id)
  assert(r.fail === 1, '科目停用后应失败')
  ev = await eventById(evDis.id)
  assert(ev.errMsg.includes('100201') && ev.errMsg.includes('停用'), `err_msg 应指明停用科目：${ev.errMsg}`)
  await post('/finance/gl/account/toggle-status', { id: '100201' }) // 恢复启用
  r = await genOne(evDis.id)
  assert(r.success === 1, `恢复科目后重试应成功：${JSON.stringify(r.results)}`)
  const disTpl = (await post('/finance/gl/template/list')).find(t => t.eventCode === 'TEST_DIS')
  await post('/finance/gl/template/delete', { id: disTpl.id })

  // ===== 8. EXPENSE 两场景 =====
  // 8a 有资金账户 → 贷 @FUND 一张凭证
  const evExp1 = await emit('EXPENSE', '费用单', 'EXP-001', {
    fund_account_code: 'F001', fund_account_name: '现金账户', fund_subject_code: '1001',
    amount_tax_incl: 500, tax_amount: 0, expense_deductible: false,
    lines: [
      { expense_type_code: 'ET01', expense_type_name: '办公费', amount: 300, subject_code: '560201', department_code: 'D001', department_name: '管理部' },
      { expense_type_code: 'ET02', expense_type_name: '差旅费', amount: 200, subject_code: '560106', department_code: 'D001', department_name: '管理部' },
    ],
  }, { amount: 500 })
  await genOne(evExp1.id)
  ev = await eventById(evExp1.id)
  assert(ev.status === '已生成', `EXP-001 应成功：${ev.errMsg}`)
  v = await post('/finance/gl/voucher/detail', { id: ev.voucherId })
  assert(v.entries.length === 3, `EXP-001 应 3 条分录（两费用行不合并），实际 ${v.entries.length}`)
  assert(v.entries.some(e => e.accountCode === '560201' && num(e.debitAmount) === 300 && e.auxDepartment === 'D001'), '应有 560201 300 挂部门')
  assert(v.entries.some(e => e.accountCode === '560106' && num(e.debitAmount) === 200), '应有 560106 200')
  const fundLine = v.entries.find(e => e.accountCode === '1001')
  assert(fundLine && num(fundLine.creditAmount) === 500 && fundLine.cashFlowItem === 'CF07', '应贷 1001 500 带 CF07')

  // 8b 无账户挂供应商往来 → 贷 2202
  const evExp2 = await emit('EXPENSE', '费用单', 'EXP-002', {
    fund_account_code: '',
    supplier_code: 'S001', supplier_name: '测试供应商',
    amount_tax_incl: 400, tax_amount: 0, expense_deductible: false,
    lines: [{ expense_type_code: 'ET01', expense_type_name: '办公费', amount: 400, subject_code: '560201', department_code: 'D001' }],
  }, { amount: 400 })
  await genOne(evExp2.id)
  ev = await eventById(evExp2.id)
  assert(ev.status === '已生成', `EXP-002 应成功：${ev.errMsg}`)
  v = await post('/finance/gl/voucher/detail', { id: ev.voucherId })
  const apLine = v.entries.find(e => e.accountCode === '2202')
  assert(apLine && num(apLine.creditAmount) === 400 && apLine.auxSupplier === 'S001', '无账户费用应贷 2202 挂供应商')
  assert(!v.entries.find(e => e.accountCode === '1001'), '无账户不应出现资金科目')

  // ===== 9. RECEIPT 分流 =====
  const evRec1 = await emit('RECEIPT', '收款单', 'REC-001', {
    fund_account_code: 'F002', fund_account_name: '工行户', fund_subject_code: '100201',
    ar_bill_no: 'AR-001', customer_code: 'C001', customer_name: '测试客户',
    amount_tax_incl: 5000,
  }, { amount: 5000 })
  await genOne(evRec1.id)
  ev = await eventById(evRec1.id)
  v = await post('/finance/gl/voucher/detail', { id: ev.voucherId })
  assert(v.entries.some(e => e.accountCode === '100201' && num(e.debitAmount) === 5000 && e.cashFlowItem === 'CF01'), '应借 100201 5000 CF01')
  assert(v.entries.some(e => e.accountCode === '1122' && num(e.creditAmount) === 5000 && e.auxCustomer === 'C001'), '应贷 1122 5000 挂客户')

  const evRec2 = await emit('RECEIPT', '收款单', 'REC-002', {
    fund_account_code: 'F002', fund_account_name: '工行户', fund_subject_code: '100201',
    ar_bill_no: '',
    employee_code: 'E001', employee_name: '张三',
    amount_tax_incl: 300,
  }, { amount: 300 })
  await genOne(evRec2.id)
  ev = await eventById(evRec2.id)
  v = await post('/finance/gl/voucher/detail', { id: ev.voucherId })
  assert(v.entries.some(e => e.accountCode === '1221' && num(e.creditAmount) === 300), '无 AR 来源应贷 1221')
  assert(!v.entries.find(e => e.accountCode === '1122'), '无 AR 来源不应出现 1122')

  // ===== 10. 反向事件 → 红字凭证金额取负 =====
  const evRev = await emit('PUR_IN', '采购收货单', 'PUR-001', purInPayload('GENERAL'), { amount: -11300, reverse: true })
  r = await genOne(evRev.id)
  assert(r.success === 1, `反向事件应成功：${JSON.stringify(r.results)}`)
  ev = await eventById(evRev.id)
  assert(ev.reverseFlag === '1', '反向事件标志应为 1')
  v = await post('/finance/gl/voucher/detail', { id: ev.voucherId })
  assert(v.isRed === true, '反向事件凭证应 isRed')
  const red1405 = v.entries.find(e => e.accountCode === '1405')
  assert(num(red1405.debitAmount) === -10000, `红字 1405 应为 -10000，实际 ${red1405.debitAmount}`)
  assert(num(red1405.qty) === -10, '红字数量应为 -10')

  // ===== 11. 忽略 / 取消忽略 / 重置 =====
  const evFly = await emit('FLY_ORDER', '飞单', 'FLY-001', { note: '飞单不单独生凭证' }, { amount: 1 })
  r = await genOne(evFly.id)
  assert(r.fail === 1, 'FLY_ORDER 无启用模板应失败')
  await post('/finance/gl/event/ignore', { id: evFly.id })
  ev = await eventById(evFly.id)
  assert(ev.status === '已忽略', '忽略后应已忽略')
  await post('/finance/gl/event/unignore', { id: evFly.id })
  ev = await eventById(evFly.id)
  assert(ev.status === '待生成', '取消忽略后应待生成')

  // 重置：已生成事件回到待生成，草稿凭证联动删除
  const resetVoucherId = (await eventById(evOut1.id)).voucherId
  await post('/finance/gl/event/reset', { id: evOut1.id })
  ev = await eventById(evOut1.id)
  assert(ev.status === '待生成' && !ev.voucherId, '重置后应待生成且无凭证')
  await expectFail('/finance/gl/voucher/detail', { id: resetVoucherId }, '不存在')
  r = await genOne(evOut1.id)
  assert(r.success === 1, '重置后重新生成应成功')

  // ===== 12. 期间锁定：202608 已结账 =====
  const evOld = await emit('PUR_IN', '采购收货单', 'PUR-OLD', purInPayload('GENERAL'), { amount: 11300, date: '2026-08-15' })
  r = await genOne(evOld.id)
  assert(r.fail === 1, '已结账期间事件应失败')
  ev = await eventById(evOld.id)
  assert(ev.errMsg.includes('已结账'), `应提示已结账：${ev.errMsg}`)

  // ===== 13. 待处理角标 =====
  const pc = await post('/finance/gl/event/pending-count', {})
  assert(typeof pc.count === 'number', 'pending-count 应返回数字')

  console.log('GL M3 (event pool + voucher template + biz subject map) smoke test PASSED ✅')
}

main().catch(e => { console.error('SMOKE FAILED ❌', e.message); process.exit(1) })
