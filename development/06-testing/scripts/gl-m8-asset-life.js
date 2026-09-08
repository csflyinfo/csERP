/**
 * 总账 M8（PRD-31）冒烟测试：固定资产（下）——变更/拆分/合并/清理/盘点/台账滚动
 *
 * 用法（专用冒烟库 erp-smoke-gl8，必须在 backend/ 目录启动）：
 *   java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8081 \
 *     --spring.datasource.url="jdbc:h2:file:./data/erp-smoke-gl8;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     --spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,...
 *   node development/06-testing/scripts/gl-m8-asset-life.js
 *
 * 覆盖：
 *  1. 变更：原值禁改被拒；费用科目 560202→560103、年限 3→4 年生效（折旧凭证分组随之变）；
 *  2. 拆分：比例和≠1 被拒；60/40 拆分（末张吃尾差），后继卡继承购入日期/方法/已提期，
 *     原卡置「已拆分」不生凭证；
 *  3. 合并：跨类别被拒、少于 2 张被拒；同类两卡合并后原值/残值相加、购入日期取最早、
 *     原卡置「已合并」不生凭证；
 *  4. 清理：借 1602+1606 / 贷 1601 转入、收支过 1606、损益结 5711（损失 4000）/5301（收益 1500），
 *     现金收入带 CF10；非现金科目被拒、重复清理被拒；清理凭证 QL 幂等；
 *  5. 折旧：处置后的卡不再计提；拆分/合并后继卡按拆得原值折旧；本期合计 2375.00
 *     （借 560103 1425.00 = A237.50+G1187.50，借 560202 950.00 = B1 190+B2 126.67+M633.33）；
 *  6. 盘点：在用 5 张全盘，盘亏 1 张只留痕（卡片状态不变），盘点单明细可查；
 *  7. 台账滚动表：期末=期初+增−减 恒等（原值 108000/折旧 2375），GL 列取 1601/1602 发生；
 *     CF 处置收现 CF10=18000；BS 恒平。
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
function entryMap(detail) {
  const m = {}
  for (const e of detail.entries || []) {
    const key = `${e.accountCode}|${e.debitAmount > 0 ? '借' : '贷'}`
    m[key] = round2(num(m[key]) + num(e.debitAmount) + num(e.creditAmount))
  }
  return m
}

async function main() {
  await login()
  const sfx = Math.random().toString(16).slice(2, 8)
  const DEPT = 'D' + sfx

  // ===== 1. 菜单 / 档案 / 启用总账 =====
  const menus = await get('/system/menu/user-tree')
  const glJson = JSON.stringify(menus.find(m => m.name === '总账管理' || m.menuName === '总账管理') || {})
  assert(glJson.includes('glAssetCheck'), '总账菜单应含 glAssetCheck')
  await post('/base/master/save', {
    moduleCode: 'department', departmentCode: DEPT, departmentName: '冒烟资产二部-' + sfx,
    headCount: 3, remark: '冒烟', status: 'NORMAL',
  })
  await post('/finance/gl/init/balance-save', { rows: [
    { accountCode: '1001', openDebit: 50000 },
    { accountCode: '3001', openCredit: 50000 },
  ] })
  await post('/finance/gl/init/enable', { startPeriod: '202609' })
  console.log('✓ 菜单/档案/启用就绪')

  const common = {
    categoryCode: '04', departmentCode: DEPT, departmentName: '冒烟资产二部-' + sfx,
    depreciationMethod: '直线', lifeYears: 3,
    acquiredDate: '2026-08-10',
  }
  async function card(over) {
    const r = await post('/finance/gl/asset/card-save', { ...common, ...over })
    return r.cardNo
  }

  // ===== 2. 建 7 张卡 =====
  const a = await card({ assetName: '变更测试电脑-' + sfx, originalValue: 12000 })                 // A 直线3年
  const b = await card({ assetName: '拆分测试设备-' + sfx, originalValue: 12000 })                 // B 待拆分
  const c = await card({ assetName: '合并测试设备甲-' + sfx, originalValue: 12000 })               // C
  const d = await card({ assetName: '合并测试设备乙-' + sfx, originalValue: 12000 })               // D
  const e = await card({ assetName: '清理损失车-' + sfx, categoryCode: '03', lifeYears: 2,
    salvageRate: 0, originalValue: 10000, expenseAccount: '560103' })                              // E 运输 2年残值0
  const f = await card({ assetName: '清理收益设备-' + sfx, originalValue: 10000, salvageRate: 0 })  // F
  const g = await card({ assetName: '盘点盘亏货车-' + sfx, categoryCode: '03', lifeYears: 4,
    originalValue: 60000 })                                                                        // G 运输 4年
  const idOf = async cardNo => (await post('/finance/gl/asset/cards', { status: '' })).find(x => x.cardNo === cardNo)
  const idA = (await idOf(a)).cardId, idB = (await idOf(b)).cardId
  const idC = (await idOf(c)).cardId, idD = (await idOf(d)).cardId
  const idE = (await idOf(e)).cardId, idF = (await idOf(f)).cardId, idG = (await idOf(g)).cardId
  console.log('✓ 建卡 7 张')

  // ===== 3. 变更 =====
  await expectFail(() => post('/finance/gl/asset/change', { cardId: idA, originalValue: 99999 }),
    '原值', '变更原值')
  const chg = await post('/finance/gl/asset/change', {
    cardId: idA, expenseAccount: '560103', depreciationMethod: '直线', lifeMonths: 48, reason: '冒烟-费用归属调整+年限调整',
  })
  assert(chg.changeNo, '变更应返回变更单号')
  console.log('✓ 变更：原值禁改被拒；费用科目+年限变更留痕 ' + chg.changeNo)

  // ===== 4. 拆分 =====
  await expectFail(() => post('/finance/gl/asset/split', {
    cardId: idB, parts: [{ assetName: 'x1', ratio: 0.6 }, { assetName: 'x2', ratio: 0.3 }],
  }), '比例之和', '比例和≠1 拆分')
  const splitRes = await post('/finance/gl/asset/split', {
    cardId: idB,
    parts: [{ assetName: '拆出主机-' + sfx, ratio: 0.6 }, { assetName: '拆出配件-' + sfx, ratio: 0.4 }],
  })
  assert(splitRes.newCards.length === 2, '拆分应得 2 张新卡')
  const b1 = splitRes.newCards[0], b2 = splitRes.newCards[1]
  assert(round2(b1.originalValue) === 7200.00 && round2(b2.originalValue) === 4800.00,
    `拆分原值应为 7200/4800，实际 ${b1.originalValue}/${b2.originalValue}`)
  const cardB = await idOf(b)
  assert(cardB.status === '已拆分', `原卡应置已拆分，实际 ${cardB.status}`)
  // 后继卡继承购入日期 2026-08（次月起提口径不变）
  const b1Row = await idOf(b1.cardNo)
  assert(String(b1Row.acquiredDate).slice(0, 10) === '2026-08-10', '后继卡应继承购入日期')
  console.log(`✓ 拆分：${b} → ${b1.cardNo}(7200) + ${b2.cardNo}(4800，尾差)，原卡已拆分`)

  // ===== 5. 合并 =====
  await expectFail(() => post('/finance/gl/asset/merge', { cardIds: [idA], assetName: 'x' }),
    '至少选择 2', '合并少于 2 张')
  await expectFail(() => post('/finance/gl/asset/merge', { cardIds: [idA, idG], assetName: '乱合-' + sfx }),
    '同一资产类别', '跨类别合并')
  const mergeRes = await post('/finance/gl/asset/merge', {
    cardIds: [idC, idD], assetName: '合并生产线-' + sfx,
  })
  assert(round2(mergeRes.originalValue) === 24000.00, `合并原值应为 24000，实际 ${mergeRes.originalValue}`)
  assert(round2(mergeRes.accumDepreciation) === 0, '合并累计折旧应为 0')
  const afterC = await idOf(c), afterD = await idOf(d)
  assert(afterC.status === '已合并' && afterD.status === '已合并', '原卡应置已合并')
  const mRow = await idOf(mergeRes.cardNo)
  assert(String(mRow.acquiredDate).slice(0, 10) === '2026-08-10' && mRow.lifeMonths === 36,
    '合并卡应继承最早购入日期/年限')
  console.log(`✓ 合并：${c}+${d} → ${mergeRes.cardNo}(24000)，原卡已合并；跨类别/单张被拒`)

  // ===== 6. 清理（损失 E / 收益 F）=====
  await expectFail(() => post('/finance/gl/asset/disposal-execute', {
    cardId: idE, incomeAmount: 6000, cashAccount: '1122',
  }), '现金类', '非现金科目清理')

  const dpE = await post('/finance/gl/asset/disposal-preview', { cardId: idE, incomeAmount: 6000, cashAccount: '1001' })
  assert(round2(dpE.netValue) === 10000.00, `E 净值应为 10000，实际 ${dpE.netValue}`)
  assert(dpE.resultType === '损失' && round2(dpE.gainLoss) === 4000.00,
    `E 应为损失 4000，实际 ${dpE.resultType} ${dpE.gainLoss}`)
  assert(dpE.depWarn === true, '未提足折旧清理应给 depWarn')
  const exE = await post('/finance/gl/asset/disposal-execute', { cardId: idE, incomeAmount: 6000, cashAccount: '1001' })
  const vE = await post('/finance/gl/voucher/detail', { id: exE.voucherId })
  const mE = entryMap(vE)
  assert(round2(mE['1606|借']) === 10000.00, `1606 借方应 10000（转入净值），实际 ${mE['1606|借']}`)
  assert(round2(mE['1606|贷']) === 10000.00, `1606 贷方应 10000（收入6000+结转损失4000），实际 ${mE['1606|贷']}`)
  assert(round2(mE['1601|贷']) === 10000.00, `1601 贷方应 10000，实际 ${mE['1601|贷']}`)
  assert(round2(mE['1001|借']) === 6000.00 && round2(mE['5711|借']) === 4000.00,
    `应借 1001 6000 / 5711 4000，实际 1001:${mE['1001|借']} 5711:${mE['5711|借']}`)
  const cashInE = vE.entries.find(x => x.accountCode === '1001' && num(x.debitAmount) > 0)
  assert(cashInE.cashFlowItem === 'CF10', '清理收入现金分录应带 CF10')

  const exF = await post('/finance/gl/asset/disposal-execute', {
    cardId: idF, incomeAmount: 12000, expenseAmount: 500, cashAccount: '1001', remark: '冒烟-出售溢价',
  })
  assert(exF.resultType === '收益' && round2(exF.gainLoss) === 1500.00,
    `F 应为收益 1500（12000-500-10000），实际 ${exF.resultType} ${exF.gainLoss}`)
  const vF = await post('/finance/gl/voucher/detail', { id: exF.voucherId })
  const mF = entryMap(vF)
  assert(round2(mF['1606|借']) === 12000.00, `1606 借方应 12000（净值10000+费用500+收益结平1500），实际 ${mF['1606|借']}`)
  assert(round2(mF['1606|贷']) === 12000.00, `1606 贷方应 12000（收入），实际 ${mF['1606|贷']}`)
  assert(round2(mF['5301|贷']) === 1500.00 && round2(mF['1001|贷']) === 500.00,
    `应贷 5301 1500 / 1001 500，实际 5301:${mF['5301|贷']} 1001:${mF['1001|贷']}`)
  await expectFail(() => post('/finance/gl/asset/disposal-execute', { cardId: idE, cashAccount: '1001' }),
    '已清理', '重复清理')
  const afterE = await idOf(e)
  assert(afterE.status === '已清理', 'E 应置已清理')
  console.log('✓ 清理：E 损失 4000（5711）、F 收益 1500（5301），1606 双向结平，CF10 带标，重复清理被拒')

  // ===== 7. 折旧（处置卡不再计提；拆合后继卡生效）=====
  const preview = await post('/finance/gl/asset/dep-preview', { period: '202609' })
  const byNo = Object.fromEntries(preview.rows.map(r => [r.cardNo, r]))
  assert(round2(byNo[a].amount) === 237.50, `A 改 4 年后月折应为 237.50（11400/48），实际 ${byNo[a]?.amount}`)
  assert(round2(byNo[b1.cardNo].amount) === 190.00, `B1 月折应为 190.00（6840/36），实际 ${byNo[b1.cardNo]?.amount}`)
  assert(round2(byNo[b2.cardNo].amount) === 126.67, `B2 月折应为 126.67（4560/36），实际 ${byNo[b2.cardNo]?.amount}`)
  assert(round2(byNo[g].amount) === 1187.50, `G 月折应为 1187.50（57000/48），实际 ${byNo[g]?.amount}`)
  assert(round2(byNo[mergeRes.cardNo].amount) === 633.33, `M 月折应为 633.33（22800/36），实际 ${byNo[mergeRes.cardNo]?.amount}`)
  assert(!byNo[e] && !byNo[f], '已清理卡片不应进折旧预览')
  assert(round2(preview.total) === 2375.00, `折旧合计应为 2375.00，实际 ${preview.total}`)
  const depExec = await post('/finance/gl/asset/dep-execute', { period: '202609' })
  assert(depExec.count === 5, `应 5 张卡计提，实际 ${depExec.count}`)
  const zj = await post('/finance/gl/voucher/detail', { id: depExec.voucherId })
  const zjm = entryMap(zj)
  assert(round2(zjm['560103|借']) === 1425.00, `560103 借方应 1425.00（A237.50+G1187.50），实际 ${zjm['560103|借']}`)
  assert(round2(zjm['560202|借']) === 950.00, `560202 借方应 950.00（B1 190+B2 126.67+M633.33），实际 ${zjm['560202|借']}`)
  assert(round2(zjm['1602|贷']) === 2375.00, `1602 贷方应 2375.00，实际 ${zjm['1602|贷']}`)
  console.log('✓ 折旧 2375.00：变更后 A 进 560103，拆合后继卡金额正确，清理卡不提')

  // 过账全部凭证（2 张 QL + 1 张 ZJ）
  await auditPost(exE.voucherId)
  await auditPost(exF.voucherId)
  await auditPost(depExec.voucherId)

  // ===== 8. 盘点（盘亏只留痕）=====
  const active = await post('/finance/gl/asset/cards', { status: '使用中' })
  const checkRows = active.map(c => ({
    cardId: c.cardId,
    checkResult: c.cardNo === g ? '盘亏' : '相符',
    remark: c.cardNo === g ? '冒烟-找不到资产' : '',
  }))
  const check = await post('/finance/gl/asset/check-save', { period: '202609', rows: checkRows })
  assert(check.totalCount === 5, `盘点应 5 项（A/B1/B2/G/M），实际 ${check.totalCount}`)
  assert(check.lossCount === 1, `盘亏应 1 项，实际 ${check.lossCount}`)
  const gAfter = await idOf(g)
  assert(gAfter.status === '使用中', '盘亏只留痕，卡片状态不应改变')
  const checkList = await post('/finance/gl/asset/check-list', {})
  assert(checkList.length >= 1 && checkList[0].lossCount === 1, '盘点单列表应有记录')
  const detail = await post('/finance/gl/asset/check-detail', { checkId: checkList[0].checkId })
  const gLine = (detail.details || []).find(x => x.cardNo === g)
  assert(gLine && gLine.checkResult === '盘亏', '盘点明细应含 G 盘亏行')
  console.log('✓ 盘点：5 项全盘、盘亏 1 项只留痕（状态不变），明细可查')

  // ===== 9. 台账滚动表 + 报表 =====
  const jz = await post('/finance/gl/period/profit-carry', { period: '202609' })
  await auditPost(jz.voucherId || jz.id)

  const lg = await post('/finance/gl/asset/ledger', { period: '202609' })
  const origRow = lg.rows.find(r => r.item === '固定资产原值')
  const accumRow = lg.rows.find(r => r.item === '累计折旧')
  assert(lg.origBalanced === true && lg.accumBalanced === true, '台账滚动应恒等')
  assert(round2(origRow.endAmount) === 108000.00, `台账期末原值应 108000（A12000+B1 7200+B2 4800+G60000+M24000），实际 ${origRow.endAmount}`)
  assert(round2(origRow.beginAmount) === 128000.00, `台账期初原值应 128000（期末+清理20000），实际 ${origRow.beginAmount}`)
  assert(round2(origRow.reduceAmount) === -20000.00, `本期减少应 -20000（E/F 清理），实际 ${origRow.reduceAmount}`)
  assert(round2(accumRow.endAmount) === 2375.00, `台账期末累计折旧应 2375.00，实际 ${accumRow.endAmount}`)
  assert(round2(accumRow.addAmount) === 2375.00, `本期计提应 2375.00，实际 ${accumRow.addAmount}`)
  assert(round2(lg.glEndAmount || origRow.glEndAmount) === -20000.00 || round2(origRow.glEndAmount) === -20000.00,
    `GL 1601 期末应为 -20000（账外卡转清理致贷方余额），实际 ${origRow.glEndAmount}`)
  assert(round2(accumRow.glEndAmount) === 2375.00, `GL 1602 期末应为 2375.00，实际 ${accumRow.glEndAmount}`)
  assert(round2(lg.depExpenseGl) === 2375.00, `GL 折旧费用本期发生应 2375.00，实际 ${lg.depExpenseGl}`)
  console.log('✓ 台账滚动：原值 128000+0−20000=108000、折旧 0+2375−0=2375 恒等；GL 列取数一致')

  const cf = await post('/finance/gl/report/data', { reportCode: 'CF', period: '202609' })
  const cf10 = cf.rows.find(r => r.itemName.includes('处置固定资产'))
  assert(round2(cf10.amount) === 18000.00, `CF10 处置收现应 18000（6000+12000），实际 ${cf10.amount}`)
  const bs = await post('/finance/gl/report/data', { reportCode: 'BS', period: '202609' })
  assert(bs.balance && bs.balance.balanced === true,
    `资产负债表应平衡，实际：${bs.balance && bs.balance.message}`)
  console.log('✓ CF10 处置收现 18000；BS 恒平')

  console.log('\n🎉 gl-m8-asset-life 全部断言通过')
}

main().catch(e => { console.error('❌', e.message); process.exit(1) })
