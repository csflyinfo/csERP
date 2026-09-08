/**
 * 总账 M9（PRD-31）冒烟测试：多栏账 / 辅助核算余额表·明细表 / 凭证打印 /
 * 自动转账模板维护 / 报表公式可视化编辑 / 辅助核算期初补录 / 操作日志查漏
 *
 * 用法（专用冒烟库 erp-smoke-gl9，必须在 backend/ 目录启动）：
 *   java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8081 \
 *     --spring.datasource.url="jdbc:h2:file:./data/erp-smoke-gl9;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     --spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,...
 *   node development/06-testing/scripts/gl-m9-aux-export.js
 *
 * 数据布局：
 *  期初：1001 借 50000 / 3001 贷 50000；1601 借 500（部门 DEPT）/ 1602 贷 500（部门 DEPT）——试算 50500 平衡
 *  V1（2026-09-10）：借 560202 950.00（DEPT） / 贷 1602 950.00（DEPT）
 *  V2（2026-09-15）：借 560202 1234.56（DEPT2）/ 贷 1602 1234.56（DEPT2）
 *  → 多栏账 5602：560202 栏本期 2184.56；辅助余额 1602：DEPT 期末贷 1450.00、DEPT2 期末贷 1234.56
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
function assert(condition, message) { if (!condition) throw new Error(message) }
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
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`/auth/login failed: ${json.message}`)
  authToken = json.data.token
}
async function auditPost(id) {
  await post('/finance/gl/voucher/audit', { id })
  await post('/finance/gl/voucher/post', { id })
  return post('/finance/gl/voucher/detail', { id })
}

async function main() {
  await login()
  const sfx = Math.random().toString(16).slice(2, 8)
  const DEPT = 'D9' + sfx
  const DEPT2 = 'D9' + sfx + 'B'
  const NAME1 = '冒烟九部-' + sfx
  const NAME2 = '冒烟十部-' + sfx

  // ===== 1. 菜单 / 部门档案 / 启用总账（含辅助核算期初）=====
  const menus = await get('/system/menu/user-tree')
  const glJson = JSON.stringify(menus.find(m => m.name === '总账管理' || m.menuName === '总账管理') || {})
  assert(glJson.includes('glTransferTemplate'), '总账菜单应含 glTransferTemplate（转账模板）')

  for (const [code, name] of [[DEPT, NAME1], [DEPT2, NAME2]]) {
    await post('/base/master/save', {
      moduleCode: 'department', departmentCode: code, departmentName: name,
      headCount: 3, remark: '冒烟M9', status: 'NORMAL',
    })
  }

  await post('/finance/gl/init/balance-save', { rows: [
    { accountCode: '1001', openDebit: 50000 },
    { accountCode: '3001', openCredit: 50000 },
    { accountCode: '1601', openDebit: 500, auxDepartment: DEPT },
    { accountCode: '1602', openCredit: 500, auxDepartment: DEPT },
  ] })
  const trial = await post('/finance/gl/init/trial-balance')
  assert(round2(trial.totalDebit) === 50500 && round2(trial.totalCredit) === 50500,
    `试算应 50500 平衡，实际 ${trial.totalDebit}/${trial.totalCredit}`)
  // 辅助核算期初补录可查
  const auxInit = await post('/finance/gl/init/aux-balance-list', { accountCode: '1602' })
  const deptInit = auxInit.find(r => r.auxDepartment === DEPT)
  assert(deptInit && round2(deptInit.openCredit) === 500, '1602 辅助期初应含 DEPT 贷 500')
  await post('/finance/gl/init/enable', { startPeriod: '202609' })
  console.log('✓ 菜单/部门档案/辅助期初（1602 DEPT 贷500）/启用就绪')

  // ===== 2. 两张部门折旧凭证并过账 =====
  const depVoucher = (date, amount, dept) => ({
    voucherWord: '记', voucherDate: date, summary: '部门折旧',
    entries: [
      { summary: '计提折旧-管理费用', accountCode: '560202', debitAmount: amount, creditAmount: 0, auxDepartment: dept },
      { summary: '累计折旧', accountCode: '1602', debitAmount: 0, creditAmount: amount, auxDepartment: dept },
    ],
  })
  const v1 = await post('/finance/gl/voucher/save', depVoucher('2026-09-10', 950.00, DEPT))
  const v2 = await post('/finance/gl/voucher/save', depVoucher('2026-09-15', 1234.56, DEPT2))
  await auditPost(v1.id)
  await auditPost(v2.id)
  console.log('✓ 两张部门折旧凭证已过账：950.00（DEPT）/ 1234.56（DEPT2）')

  // ===== 3. 多栏账 =====
  const mc = await post('/finance/gl/ledger/multi-column', {
    accountCode: '5602', periodFrom: '202609', periodTo: '202609',
  })
  assert(mc.account.analyzeSide === '借', `5602 为借方向科目，分析侧应借，实际 ${mc.account.analyzeSide}`)
  assert(mc.columns.some(c => c.accountCode === '560202'), '多栏账栏目应含 560202')
  const row = mc.rows.find(r => r.period === '202609')
  assert(row && round2(row.cells['560202']) === 2184.56,
    `560202 栏 202609 应 2184.56（950+1234.56），实际 ${row && row.cells['560202']}`)
  assert(round2(row.total) === 2184.56 && round2(mc.totalRow.total) === 2184.56,
    `多栏账合计应 2184.56，实际 ${row && row.total}/${mc.totalRow.total}`)
  assert(round2(mc.totalRow.cells['560202']) === 2184.56, '多栏账栏目合计应 2184.56')
  await expectFail(() => post('/finance/gl/ledger/multi-column', {
    accountCode: '1001', periodFrom: '202609', periodTo: '202609',
  }), '非末级科目', '末级科目查多栏账')
  console.log('✓ 多栏账：5602 分析借方，560202 栏本期/合计 2184.56；末级科目被拒')

  // ===== 4. 辅助核算余额表 =====
  await expectFail(() => post('/finance/gl/ledger/aux-balance', {
    dimension: 'foo', periodFrom: '202609', periodTo: '202609',
  }), '辅助核算维度应为', '非法辅助维度')
  const ab = await post('/finance/gl/ledger/aux-balance', {
    dimension: 'department', periodFrom: '202609', periodTo: '202609', accountCode: '1602',
  })
  assert(ab.dimensionLabel === '部门', `维度标签应为部门，实际 ${ab.dimensionLabel}`)
  const deptRow = ab.rows.find(r => r.auxCode === DEPT)
  const dept2Row = ab.rows.find(r => r.auxCode === DEPT2)
  assert(deptRow && deptRow.auxName === NAME1, `DEPT 应解析档案名称「${NAME1}」，实际 ${deptRow && deptRow.auxName}`)
  assert(round2(deptRow.opening.credit) === 500 && deptRow.opening.direction === '贷',
    `DEPT 期初应贷 500，实际 ${deptRow && JSON.stringify(deptRow.opening)}`)
  assert(round2(deptRow.periodCredit) === 950 && round2(deptRow.closing.credit) === 1450,
    `DEPT 本期贷 950/期末贷 1450，实际 ${deptRow && deptRow.periodCredit}/${deptRow && deptRow.closing.credit}`)
  assert(dept2Row && round2(dept2Row.periodCredit) === 1234.56 && round2(dept2Row.closing.credit) === 1234.56,
    `DEPT2 本期/期末应贷 1234.56，实际 ${dept2Row && dept2Row.periodCredit}/${dept2Row && dept2Row.closing.credit}`)
  assert(dept2Row.auxName === NAME2, 'DEPT2 应解析档案名称')
  console.log('✓ 辅助余额表：DEPT 期初贷500+本期950=期末1450，DEPT2 本期1234.56，档案名称解析正确')

  // ===== 5. 辅助核算明细账 =====
  await expectFail(() => post('/finance/gl/ledger/aux-subsidiary', {
    dimension: 'department', dateFrom: '2026-09-01', dateTo: '2026-09-30',
  }), '请选择', '缺辅助对象查明细账')
  const as1 = await post('/finance/gl/ledger/aux-subsidiary', {
    dimension: 'department', auxCode: DEPT, dateFrom: '2026-09-01', dateTo: '2026-09-30', accountCode: '1602',
  })
  assert(as1.auxName === NAME1, '明细账辅助名称应解析')
  assert(round2(as1.opening.credit) === 500, `明细账期初应贷 500，实际 ${JSON.stringify(as1.opening)}`)
  assert(as1.rows.length === 1, `1602 过滤后 DEPT 应 1 行，实际 ${as1.rows.length}`)
  assert(round2(as1.rows[0].creditAmount) === 950 && as1.rows[0].accountCode === '1602',
    '明细行应为 1602 贷 950')
  assert(round2(as1.totalCredit) === 950 && round2(as1.closing.credit) === 1450,
    `明细合计贷 950/期末贷 1450，实际 ${as1.totalCredit}/${as1.closing.credit}`)
  assert(as1.rows[0].balanceDirection === '贷' && round2(as1.rows[0].balanceCredit) === 1450,
    '滚动余额应贷 1450')
  const asAll = await post('/finance/gl/ledger/aux-subsidiary', {
    dimension: 'department', auxCode: DEPT, dateFrom: '2026-09-01', dateTo: '2026-09-30',
  })
  assert(asAll.rows.length === 2, `跨科目 DEPT 应 2 行（借560202+贷1602），实际 ${asAll.rows.length}`)
  assert(round2(asAll.totalDebit) === 950 && round2(asAll.totalCredit) === 950,
    '跨科目合计应借 950 贷 950')
  console.log('✓ 辅助明细账：期初500+逐笔滚动余额=期末1450；跨科目 2 行借贷各 950')

  // ===== 6. 自动转账模板维护 =====
  const tplList = await post('/finance/gl/transfer/list')
  const zz01 = tplList.find(t => t.transferNo === 'ZZ01')
  const zz02 = tplList.find(t => t.transferNo === 'ZZ02')
  assert(zz01 && zz02 && zz01.entries.length >= 2 && zz02.entries.length >= 2,
    '预置模板 ZZ01/ZZ02 应存在且各≥2 条分录')
  assert(zz01.isSystem === true && zz02.isSystem === true, 'ZZ01/ZZ02 应为系统预置')

  const zz03Body = {
    transferName: '冒烟-折旧费用结转测试-' + sfx,
    conditionExpr: '', remark: 'M9 冒烟', enabled: true,
    entries: [
      { direction: '借', summary: '冒烟结转借', accountCode: '5403', amountExpr: "FSD('560202')" },
      { direction: '贷', summary: '冒烟结转贷', accountCode: '1001', amountExpr: "FSD('560202')" },
    ],
  }
  const saved = await post('/finance/gl/transfer/save', zz03Body)
  assert(saved.transferNo === 'ZZ03', `自动编号应 ZZ03，实际 ${saved.transferNo}`)
  const zz03Id = saved.id

  await expectFail(() => post('/finance/gl/transfer/save', {
    transferName: '坏公式模板', entries: [
      { direction: '借', summary: 'x', accountCode: '5403', amountExpr: "BADFUNC('1001')" },
      { direction: '贷', summary: 'y', accountCode: '1001', amountExpr: "QM('1001')" },
    ],
  }), '无法取数', '错误金额公式')
  await expectFail(() => post('/finance/gl/transfer/save', {
    transferName: '单分录模板', entries: [
      { direction: '借', summary: 'x', accountCode: '5403', amountExpr: "QM('1001')" },
    ],
  }), '至少需要 2 条分录', '仅 1 条分录')
  await expectFail(() => post('/finance/gl/transfer/save', {
    transferName: '双借模板', entries: [
      { direction: '借', summary: 'x', accountCode: '5403', amountExpr: "QM('1001')" },
      { direction: '借', summary: 'y', accountCode: '1001', amountExpr: "QM('1001')" },
    ],
  }), '同时有借方和贷方', '两条都借')
  await expectFail(() => post('/finance/gl/transfer/save', {
    transferName: '父级科目模板', entries: [
      { direction: '借', summary: 'x', accountCode: '5602', amountExpr: "QM('1001')" },
      { direction: '贷', summary: 'y', accountCode: '1001', amountExpr: "QM('1001')" },
    ],
  }), '不是末级启用科目', '分录用父级科目')
  await expectFail(() => post('/finance/gl/transfer/delete', { id: zz01.id }),
    '系统预置', '删除系统模板')

  // 停用 → 预览不含；启用 → 预览含且金额取到 2184.56
  await post('/finance/gl/transfer/toggle', { id: zz03Id, enabled: false })
  let preview = await post('/finance/gl/period/transfer-preview', { period: '202609' })
  assert(!preview.some(t => t.transferNo === 'ZZ03'), '停用后预览不应含 ZZ03')
  await post('/finance/gl/transfer/toggle', { id: zz03Id, enabled: true })
  preview = await post('/finance/gl/period/transfer-preview', { period: '202609' })
  const pz = preview.find(t => t.transferNo === 'ZZ03')
  assert(pz && pz.status === 'todo', `ZZ03 预览应可生成(todo)，实际 ${pz && pz.status}/${pz && pz.message}`)
  assert(round2(pz.debitTotal) === 2184.56 && round2(pz.creditTotal) === 2184.56,
    `ZZ03 预览金额应 2184.56，实际 ${pz.debitTotal}/${pz.creditTotal}`)

  // 执行只选 ZZ03 → 生成草稿转账凭证；再预览显示 done；幂等
  const exec = await post('/finance/gl/period/transfer-execute', { period: '202609', transferNos: ['ZZ03'] })
  const rz = exec.results.find(r => r.transferNo === 'ZZ03')
  assert(rz && rz.status === 'created' && rz.voucherId, `ZZ03 执行应生成凭证，实际 ${rz && rz.status}`)
  const zDetail = await post('/finance/gl/voucher/detail', { id: rz.voucherId })
  const m = {}
  for (const e of zDetail.entries) {
    const k = `${e.accountCode}|${num(e.debitAmount) > 0 ? '借' : '贷'}`
    m[k] = round2(num(m[k]) + num(e.debitAmount) + num(e.creditAmount))
  }
  assert(round2(m['5403|借']) === 2184.56 && round2(m['1001|贷']) === 2184.56,
    `转账凭证应借5403/贷1001 各 2184.56，实际 ${JSON.stringify(m)}`)
  assert(zDetail.status === '草稿', '自动转账凭证应为草稿待审核')
  preview = await post('/finance/gl/period/transfer-preview', { period: '202609' })
  assert(preview.find(t => t.transferNo === 'ZZ03').status === 'done', '执行后预览应显示 done')
  const exec2 = await post('/finance/gl/period/transfer-execute', { period: '202609', transferNos: ['ZZ03'] })
  assert(exec2.results[0].status === 'exists', '重复执行应幂等跳过')

  await post('/finance/gl/transfer/delete', { id: zz03Id })
  const afterDel = await post('/finance/gl/transfer/list')
  assert(!afterDel.some(t => t.transferNo === 'ZZ03'), '删除后列表不应含 ZZ03')
  console.log('✓ 转账模板：ZZ03 增/校验拦截/停用启用/预览2184.56/执行生草稿/幂等/删除；系统模板禁删')

  // ===== 7. 报表公式可视化编辑 =====
  const bsItems = await post('/finance/gl/report/formula-list', { reportCode: 'BS' })
  const isItems = await post('/finance/gl/report/formula-list', { reportCode: 'IS' })
  const cfItems = await post('/finance/gl/report/formula-list', { reportCode: 'CF' })
  assert(bsItems.length >= 20 && isItems.length > 0 && cfItems.length > 0,
    `三表公式行应齐全，实际 BS${bsItems.length}/IS${isItems.length}/CF${cfItems.length}`)
  const cashRow = bsItems.find(r => (r.formula || '').includes('1001'))
  assert(cashRow, 'BS 应存在引用 1001 的货币资金行')
  const origFormula = cashRow.formula, origBegin = cashRow.formulaBegin
  await expectFail(() => post('/finance/gl/report/formula-save', {
    id: cashRow.id, formula: "BADFUNC('1')",
  }), '无法取数', '报表公式写坏函数')
  await post('/finance/gl/report/formula-save', { id: cashRow.id, formula: "QM('1001')" })
  const bs = await post('/finance/gl/report/data', { reportCode: 'BS', period: '202609' })
  const cashData = bs.rows.find(r => r.itemName === cashRow.itemName)
  assert(cashData && round2(cashData.amount) === 50000,
    `公式改为 QM('1001') 后该行应取 50000，实际 ${cashData && cashData.amount}`)
  // 还原公式
  await post('/finance/gl/report/formula-save', { id: cashRow.id, formula: origFormula, formulaBegin: origBegin })
  console.log('✓ 报表公式：三表公式可列示；坏公式拒存；改 QM(\'1001\') 后报表取 50000；已还原')

  // ===== 8. 凭证打印数据 =====
  const p1 = await post('/finance/gl/voucher/print-data', { id: v1.id })
  assert(p1.amountCn === '玖佰伍拾元整', `950.00 大写应「玖佰伍拾元整」，实际「${p1.amountCn}」`)
  const p1aux = p1.entries.find(e => e.accountCode === '1602')
  assert(p1aux.auxText && p1aux.auxText.includes('部门') && p1aux.auxText.includes(NAME1),
    `打印分录辅助文本应含部门和名称，实际「${p1aux.auxText}」`)
  const p2 = await post('/finance/gl/voucher/print-data', { id: v2.id })
  assert(p2.amountCn === '壹仟贰佰叁拾肆元伍角陆分',
    `1234.56 大写应「壹仟贰佰叁拾肆元伍角陆分」，实际「${p2.amountCn}」`)
  assert(p2.companyName !== undefined, '打印数据应带 companyName 字段')
  console.log('✓ 凭证打印：大写 950→玖佰伍拾元整、1234.56→壹仟贰佰叁拾肆元伍角陆分；辅助文本补全')

  // ===== 9. 操作日志查漏 =====
  const logPage = await post('/system/operation-log/page', { page: 1, pageSize: 300 })
  // 该端点用裸 jdbcTemplate 查询，H2 返回大写字段名（MODULECODE/ACTION）
  const logs = (logPage.records || []).map(l => ({
    moduleCode: l.moduleCode || l.MODULECODE,
    action: l.action || l.ACTION,
  }))
  const tplSaveLogs = logs.filter(l => l.moduleCode === 'finance.gl.transfer' && l.action === 'SAVE')
  const formulaLogs = logs.filter(l => l.moduleCode === 'finance.gl.report' && l.action === 'FORMULA')
  const toggleLogs = logs.filter(l => l.moduleCode === 'finance.gl.transfer' && l.action === 'TOGGLE')
  const deleteLogs = logs.filter(l => l.moduleCode === 'finance.gl.transfer' && l.action === 'DELETE')
  // 失败的保存在校验阶段即拒绝、不写日志；成功 SAVE 仅 ZZ03 一次
  assert(tplSaveLogs.length >= 1, `转账模板 SAVE 应留日志，实际 ${tplSaveLogs.length}`)
  assert(formulaLogs.length >= 2, `报表公式 FORMULA 应留日志（改+还原 ≥2），实际 ${formulaLogs.length}`)
  assert(toggleLogs.length >= 2, `转账模板 TOGGLE 应留日志（停+启 ≥2），实际 ${toggleLogs.length}`)
  assert(deleteLogs.length >= 1, `转账模板 DELETE 应留日志，实际 ${deleteLogs.length}`)
  console.log('✓ 操作日志：转账 SAVE/TOGGLE/DELETE、报表 FORMULA 均写 sys_operation_log_runtime')

  console.log('\n🎉 gl-m9-aux-export 全部断言通过')
}

main().catch(e => { console.error('❌', e.message); process.exit(1) })
