/**
 * 总账 M7（PRD-31）冒烟测试：固定资产卡片 + 四方法折旧 + 月度计提到账
 *
 * 用法（专用冒烟库 erp-smoke-gl7，必须在 backend/ 目录启动）：
 *   java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8081 \
 *     --spring.datasource.url="jdbc:h2:file:./data/erp-smoke-gl7;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     --spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,...
 *   node development/06-testing/scripts/gl-m7-asset.js
 *
 * 覆盖：
 *  1. 资产类别 7 个种子（默认方法/年限/残值率/费用科目，运输设备走 560103）；
 *  2. 建卡校验：名称/原值/工作量法必填/对方科目禁带辅助核算/费用科目须末级；
 *     建卡可生成入账凭证草稿（借 1601[部门] / 贷对方科目）；
 *  3. 四方法数值用例（202609 期，资产 2026-08 购入 → 次月起提）：
 *     直线 12000/残值5%/3年 → 316.67；双倍余额 100000/残值5%/4年 → 4166.67；
 *     年数总和 60000/残值0/3年 → 2500.00；工作量 60000/残值5%/10000工时，本期 200 工时 → 1140.00；
 *     当月购入 → 当月新增不提 0；去年购入 1 年期直线 → 一次性提足 12000.00（末月补残值口径）；
 *  4. 计提执行：写明细+更新卡片（累计折旧/净值/已用工作量/最后折旧期），生成 ZJ{period} 转字草稿：
 *     借 560202/560103（按部门挂辅助）、贷 1602（部门辅助），借贷平衡；重复计提被拦截；
 *  5. 期末向导第 ③ 步：todo → 计提过账后 done；结账后对已关账期间再计提被拦截；
 *  6. 跨月（202610）再计提：第二月金额（含双倍/年数总和仍在首年、工作量 100 工时→570、
 *     当月新增卡次月开提 158.33、提足卡 0）；
 *  7. BS 取数：固定资产原价 QM(1601)=12000（仅入账卡）、累计折旧 QM(1602)=两期合计、
 *     资产负债表平衡（折旧费用经结转损益进 3103）。
 *
 * 说明：C1~C6 建卡不生成入账凭证（账外卡片），故 BS 固定资产仅 C7 的 12000；
 *   台账净值与总账 1601/1602 的差异属正常（账外卡），账实核对留待后续。
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
  const DEPT_CODE = 'D' + sfx
  const DEPT_NAME = '冒烟资产部-' + sfx

  // ===== 1. 菜单 =====
  const menus = await get('/system/menu/user-tree')
  const glJson = JSON.stringify(menus.find(m => m.name === '总账管理' || m.menuName === '总账管理') || {})
  assert(glJson.includes('glAssetCard'), '总账菜单应含 glAssetCard')
  assert(glJson.includes('glDepreciation'), '总账菜单应含 glDepreciation')
  console.log('✓ 菜单含资产卡片/折旧计提')

  // ===== 2. 基础档案 + 类别种子 =====
  await post('/base/master/save', {
    moduleCode: 'department', departmentCode: DEPT_CODE, departmentName: DEPT_NAME,
    headCount: 5, remark: '冒烟', status: 'NORMAL',
  })
  const cats = await post('/finance/gl/asset/categories', {})
  assert(cats.length === 7, `资产类别应有 7 个种子，实际 ${cats.length}`)
  const cat03 = cats.find(c => c.categoryCode === '03')
  assert(cat03.expenseAccount === '560103', '运输设备默认折旧费用科目应为 560103')
  const cat04 = cats.find(c => c.categoryCode === '04')
  assert(cat04.defaultLifeMonths === 36 && cat04.expenseAccount === '560202', '电子设备默认 36 月/560202')
  console.log('✓ 资产类别 7 个种子，默认科目正确')

  // ===== 3. 启用总账（期初 1001 现金 5 万 / 3001 权益 5 万，平衡）=====
  await post('/finance/gl/init/balance-save', { rows: [
    { accountCode: '1001', openDebit: 50000 },
    { accountCode: '3001', openCredit: 50000 },
  ] })
  await post('/finance/gl/init/enable', { startPeriod: '202609' })
  console.log('✓ 总账启用 202609')

  // ===== 4. 建卡校验 =====
  await expectFail(() => post('/finance/gl/asset/card-save', { categoryCode: '04' }),
    '资产名称', '无名称建卡')
  await expectFail(() => post('/finance/gl/asset/card-save',
    { assetName: '坏卡', categoryCode: '04', originalValue: 0, acquiredDate: '2026-08-01' }),
    '原值', '原值 0 建卡')
  await expectFail(() => post('/finance/gl/asset/card-save',
    { assetName: '坏卡', categoryCode: '04', originalValue: 1000, acquiredDate: '2026-08-01', depreciationMethod: '工作量' }),
    '总工作量', '工作量法无总工作量')
  await expectFail(() => post('/finance/gl/asset/card-save', {
    assetName: '坏卡', categoryCode: '04', originalValue: 1000, acquiredDate: '2026-08-01',
    depreciationMethod: '直线', createVoucher: true, creditAccount: '1122',
  }), '辅助核算', '对方科目带辅助核算')
  await expectFail(() => post('/finance/gl/asset/card-save', {
    assetName: '坏卡', categoryCode: '04', originalValue: 1000, acquiredDate: '2026-08-01',
    depreciationMethod: '直线', expenseAccount: '5602',
  }), '末级', '费用科目非末级')
  console.log('✓ 建卡校验拦截 5 类非法输入')

  // ===== 5. 建 7 张卡 =====
  const baseCard = {
    categoryCode: '04', departmentCode: DEPT_CODE, departmentName: DEPT_NAME,
    depreciationMethod: '直线', lifeYears: 3,
  }
  async function card(over) {
    const r = await post('/finance/gl/asset/card-save', { ...baseCard, ...over })
    return r.cardNo
  }
  // C1 直线：12000，残值 5%，3 年，2026-08 购入 → 月折 (12000-600)/36 = 316.67
  const c1 = await card({ assetName: '冒烟电脑-' + sfx, originalValue: 12000, acquiredDate: '2026-08-10' })
  // C2 双倍余额：100000，残值 5%，4 年 → 首年 100000*2/4=50000，月折 4166.67
  const c2 = await card({ assetName: '冒烟机床-' + sfx, categoryCode: '02', lifeYears: 4,
    originalValue: 100000, acquiredDate: '2026-08-10', depreciationMethod: '双倍余额', expenseAccount: '560202' })
  // C3 年数总和：60000，残值 0，3 年 → 首年 60000*3/6=30000，月折 2500.00
  const c3 = await card({ assetName: '冒烟仪器-' + sfx, originalValue: 60000, salvageRate: 0,
    acquiredDate: '2026-08-10', depreciationMethod: '年数总和' })
  // C4 工作量：运输设备 60000，残值 5%，总 10000 工时 → 单位 5.7；200 工时 = 1140.00；费用 560103
  const c4 = await card({ assetName: '冒烟货车-' + sfx, categoryCode: '03',
    originalValue: 60000, acquiredDate: '2026-08-10', depreciationMethod: '工作量',
    totalWorkload: 10000, workloadUnit: '工时' })
  // C5 当月购入（2026-09）→ 当月不提；6000，残值 5%，3 年 → 次月起 158.33
  const c5 = await card({ assetName: '冒烟打印机-' + sfx, originalValue: 6000, acquiredDate: '2026-09-05' })
  // C6 去年购入（2025-09），1 年直线无残值 12000 → 到 202609 满 12 个月，一次性提足 12000
  const c6 = await card({ assetName: '冒烟旧设备-' + sfx, originalValue: 12000, salvageRate: 0, lifeYears: 1,
    acquiredDate: '2025-09-10' })
  // C7 当月购入 + 入账凭证草稿：12000，残值 5%，贷 1001；当月不提
  const c7res = await post('/finance/gl/asset/card-save', {
    ...baseCard, assetName: '冒烟空调-' + sfx, originalValue: 12000, acquiredDate: '2026-09-05',
    createVoucher: true, creditAccount: '1001',
  })
  const c7 = c7res.cardNo
  assert(c7res.voucherId, 'C7 建卡应返回入账凭证 id')
  console.log(`✓ 建卡 7 张：${c1} … ${c7}`)

  // C7 入账凭证：借 1601 12000[部门] / 贷 1001 12000
  const acqVoucher = await post('/finance/gl/voucher/detail', { id: c7res.voucherId })
  const acqMap = entryMap(acqVoucher)
  assert(round2(acqMap['1601|借']) === 12000, `入账凭证应借 1601 12000，实际 ${acqMap['1601|借']}`)
  assert(round2(acqMap['1001|贷']) === 12000, `入账凭证应贷 1001 12000，实际 ${acqMap['1001|贷']}`)
  const acq1601 = (acqVoucher.entries || []).find(e => e.accountCode === '1601')
  assert(acq1601 && acq1601.auxDepartment === DEPT_CODE, '入账凭证 1601 分录应挂部门辅助')
  console.log('✓ 建卡入账凭证草稿：借1601[部门]/贷1001 各 12000')

  // ===== 6. 202609 折旧预览 =====
  const wiz0 = await post('/finance/gl/asset/wizard-status', { period: '202609' })
  assert(wiz0.status === 'todo', `计提前向导③应为 todo，实际 ${wiz0.status}`)

  const preview0 = await post('/finance/gl/asset/dep-preview', { period: '202609' })
  const byNo = Object.fromEntries(preview0.rows.map(r => [r.cardNo, r]))
  assert(round2(byNo[c1].amount) === 316.67, `直线月折应为 316.67，实际 ${byNo[c1].amount}`)
  assert(round2(byNo[c2].amount) === 4166.67, `双倍余额月折应为 4166.67，实际 ${byNo[c2].amount}`)
  assert(round2(byNo[c3].amount) === 2500.00, `年数总和月折应为 2500.00，实际 ${byNo[c3].amount}`)
  assert(byNo[c4].tag === '待填工作量' && num(byNo[c4].amount) === 0, '工作量法未填工作量应为 0/待填工作量')
  assert(byNo[c5].tag === '当月新增不提' && num(byNo[c5].amount) === 0, '当月购入应不提')
  assert(round2(byNo[c6].amount) === 12000.00 && byNo[c6].tag === '提足到期',
    `到期卡应一次性提足 12000，实际 ${byNo[c6].amount}/${byNo[c6].tag}`)
  assert(byNo[c7].tag === '当月新增不提', 'C7 当月购入应不提')
  console.log('✓ 202609 预览（无工作量）：直线316.67/双倍4166.67/年数2500/工作量待填/当月不提/到期提足12000')

  const preview1 = await post('/finance/gl/asset/dep-preview',
    { period: '202609', workloads: { [c4]: 200 } })
  const byNo1 = Object.fromEntries(preview1.rows.map(r => [r.cardNo, r]))
  assert(round2(byNo1[c4].amount) === 1140.00, `200 工时应折 1140.00，实际 ${byNo1[c4].amount}`)
  assert(round2(preview1.total) === 20123.34, `应提合计应为 20123.34，实际 ${preview1.total}`)
  assert(preview1.voucherExists === false, '计提前不应存在 ZJ 凭证')
  console.log('✓ 202609 预览（200 工时）：工作量 1140.00，合计 20123.34')

  // ===== 7. 执行折旧 + 幂等拦截 =====
  const exec1 = await post('/finance/gl/asset/dep-execute',
    { period: '202609', workloads: { [c4]: 200 } })
  assert(exec1.count === 5, `应有 5 张卡计提（C5/C7 当月不提），实际 ${exec1.count}`)
  assert(round2(exec1.total) === 20123.34, `执行合计应为 20123.34，实际 ${exec1.total}`)
  await expectFail(() => post('/finance/gl/asset/dep-execute',
    { period: '202609', workloads: { [c4]: 200 } }), '已计提折旧', '重复计提')
  console.log('✓ 202609 折旧执行：5 卡 / 20123.34，重复计提被拦截')

  // ZJ 凭证：借 560202 18983.34（C1+C2+C3+C6）、借 560103 1140.00（C4）、贷 1602 20123.34，全挂部门
  const zj1 = await post('/finance/gl/voucher/detail', { id: exec1.voucherId })
  const zj1Map = entryMap(zj1)
  assert(round2(zj1Map['560202|借']) === 18983.34, `560202 借方应为 18983.34，实际 ${zj1Map['560202|借']}`)
  assert(round2(zj1Map['560103|借']) === 1140.00, `560103 借方应为 1140.00，实际 ${zj1Map['560103|借']}`)
  assert(round2(zj1Map['1602|贷']) === 20123.34, `1602 贷方应为 20123.34，实际 ${zj1Map['1602|贷']}`)
  for (const e of zj1.entries || [])
    assert(e.auxDepartment === DEPT_CODE, `折旧凭证分录 ${e.accountCode} 应挂部门辅助`)
  console.log('✓ ZJ202609 凭证：借560202 18983.34 + 借560103 1140.00 / 贷1602 20123.34，全挂部门')

  // 卡片更新
  const cardsAfter = await post('/finance/gl/asset/cards', { status: '使用中' })
  const cAfter = Object.fromEntries(cardsAfter.map(c => [c.cardNo, c]))
  assert(round2(cAfter[c1].accumDepreciation) === 316.67 && round2(cAfter[c1].netValue) === 11683.33,
    `C1 累计/净值应为 316.67/11683.33，实际 ${cAfter[c1].accumDepreciation}/${cAfter[c1].netValue}`)
  assert(cAfter[c1].lastDepPeriod === '202609', 'C1 最后折旧期应为 202609')
  assert(round2(cAfter[c4].accumDepreciation) === 1140.00 && num(cAfter[c4].usedWorkload) === 200,
    `C4 累计/已用工作量应为 1140.00/200，实际 ${cAfter[c4].accumDepreciation}/${cAfter[c4].usedWorkload}`)
  assert(round2(cAfter[c6].accumDepreciation) === 12000.00 && num(cAfter[c6].netValue) === 0,
    `C6 应提足（累计 12000/净值 0），实际 ${cAfter[c6].accumDepreciation}/${cAfter[c6].netValue}`)
  console.log('✓ 卡片累计折旧/净值/最后折旧期/已用工作量更新正确')

  // 向导③ 转为 done
  const wiz1 = await post('/finance/gl/asset/wizard-status', { period: '202609' })
  assert(wiz1.status === 'done', `计提后向导③应为 done，实际 ${wiz1.status}`)

  // ===== 8. 过账 → 结转损益 → 结账 202609 =====
  await auditPost(exec1.voucherId)
  await auditPost(c7res.voucherId)
  const jz1res = await post('/finance/gl/period/profit-carry', { period: '202609' })
  const jz1Id = jz1res.voucherId || jz1res.id
  assert(jz1Id, '结转损益应返回凭证 id')
  await auditPost(jz1Id)
  const wizard = await post('/finance/gl/period/wizard', { period: '202609' })
  const step3 = (wizard.steps || []).find(s => s.no === 3)
  assert(step3 && step3.status === 'done', `期末向导第③步应为 done，实际 ${JSON.stringify(step3)}`)
  await post('/finance/gl/period/close', { period: '202609' })
  await expectFail(() => post('/finance/gl/asset/dep-execute', { period: '202609' }),
    '不是进行中', '已关账期间计提')
  console.log('✓ 202609：折旧/入账凭证过账、损益结转、结账完成；向导③=done；关账期计提被拦截')

  // ===== 9. 202610 第二期折旧 =====
  const preview2 = await post('/finance/gl/asset/dep-preview',
    { period: '202610', workloads: { [c4]: 100 } })
  const byNo2 = Object.fromEntries(preview2.rows.map(r => [r.cardNo, r]))
  assert(round2(byNo2[c1].amount) === 316.67, `C1 第二月应 316.67，实际 ${byNo2[c1].amount}`)
  assert(round2(byNo2[c2].amount) === 4166.67, `C2 首年内第二月应 4166.67，实际 ${byNo2[c2].amount}`)
  assert(round2(byNo2[c3].amount) === 2500.00, `C3 首年内第二月应 2500.00，实际 ${byNo2[c3].amount}`)
  assert(round2(byNo2[c4].amount) === 570.00, `C4 100 工時应 570.00，实际 ${byNo2[c4].amount}`)
  assert(round2(byNo2[c5].amount) === 158.33, `C5 次月开提应 158.33（5700/36），实际 ${byNo2[c5].amount}`)
  assert(byNo2[c6].tag === '已提满' && num(byNo2[c6].amount) === 0, 'C6 提满后应为 0/已提满')
  assert(round2(byNo2[c7].amount) === 316.67, `C7 次月开提应 316.67，实际 ${byNo2[c7].amount}`)
  assert(round2(preview2.total) === 8028.34, `202610 合计应为 8028.34，实际 ${preview2.total}`)
  console.log('✓ 202610 预览：316.67/4166.67/2500/570/158.33/0(提满)/316.67，合计 8028.34')

  const exec2 = await post('/finance/gl/asset/dep-execute',
    { period: '202610', workloads: { [c4]: 100 } })
  assert(exec2.count === 6, `202610 应有 6 卡计提，实际 ${exec2.count}`)
  const zj2 = await post('/finance/gl/voucher/detail', { id: exec2.voucherId })
  const zj2Map = entryMap(zj2)
  // 560202 借：C1 316.67 + C2 4166.67 + C3 2500 + C5 158.33 + C7 316.67 = 7458.34
  assert(round2(zj2Map['560202|借']) === 7458.34, `560202 借方应为 7458.34，实际 ${zj2Map['560202|借']}`)
  assert(round2(zj2Map['560103|借']) === 570.00, `560103 借方应为 570.00，实际 ${zj2Map['560103|借']}`)
  assert(round2(zj2Map['1602|贷']) === 8028.34, `1602 贷方应为 8028.34，实际 ${zj2Map['1602|贷']}`)
  await auditPost(exec2.voucherId)
  const jz2res = await post('/finance/gl/period/profit-carry', { period: '202610' })
  await auditPost(jz2res.voucherId || jz2res.id)
  console.log('✓ 202610 折旧执行 + 凭证过账 + 损益结转完成')

  // ===== 10. BS 取数 =====
  const bs = await post('/finance/gl/report/data', { reportCode: 'BS', period: '202610' })
  const find = namePart => bs.rows.find(r => r.itemName.includes(namePart))
  const r06 = find('固定资产原价')
  const r07 = find('累计折旧')
  const r08 = find('固定资产账面价值')
  assert(round2(r06.amount) === 12000.00, `固定资产原价应为 12000.00，实际 ${r06.amount}`)
  assert(round2(r07.amount) === 28151.68, `累计折旧应为 28151.68（20123.34+8028.34），实际 ${r07.amount}`)
  assert(round2(r08.amount) === -16151.68, `账面价值应为 -16151.68，实际 ${r08.amount}`)
  assert(bs.balance && bs.balance.balanced === true,
    `资产负债表应平衡，实际：${bs.balance && bs.balance.message}`)
  console.log('✓ BS：固定资产原价 12000.00、累计折旧 28151.68、表平衡')

  console.log('\n🎉 gl-m7-asset 全部断言通过')
}

main().catch(e => { console.error('❌', e.message); process.exit(1) })
