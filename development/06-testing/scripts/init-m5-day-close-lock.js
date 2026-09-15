/**
 * PRD-34 期初 M5：三模块建账后做首次业务日结（端到端关键验收点），
 * 日结后期初全部锁定；反日结后恢复可改。
 *
 * 前置：先跑 init-m4-post-verify.js（三模块均已建账、无日结记录）。
 *   node development/06-testing/scripts/init-m5-day-close-lock.js
 */
const { post, login, assert, expectFail, todayStr, bootstrapMasters } = require('./init-common.js')

async function main() {
  await login()
  const M = await bootstrapMasters('INIT')
  const D = todayStr()
  const RJ_NO = 'RJ-' + D.replace(/-/g, '')

  // 前置：三模块已建账
  for (const [api, label] of [['/init/stock', '库存'], ['/init/ar', '应收'], ['/init/ap', '应付']]) {
    const s = await post(api + '/status')
    assert(s.posted === true, `M5 前置：${label}应已建账（先跑 M4）`)
    assert(s.dayCloseLocked === false, '开跑前不应有日结')
  }

  // 1. 向导七步结构
  const wz = await post('/finance/day-close/wizard', { date: D })
  for (const k of ['step1', 'step2', 'step3', 'step4', 'step5', 'step6', 'step7', 'canClose']) {
    assert(k in wz, `向导缺字段 ${k}`)
  }
  // 期初不产生未审核「期初入库单」，step1 应通过
  assert(wz.step1.passed === true, `step1 应通过：${JSON.stringify(wz.step1.errors || [])}`)

  // 2. 首次日结：未勾选确认 → 拒；勾齐 → 成功
  await expectFail('/finance/day-close/close',
    { date: D, openingConfirmed: false }, ['期初', '首次'])
  const closeRes = await post('/finance/day-close/close', {
    date: D,
    openingConfirmed: true,
    acknowledgeHanging: true,
    acknowledgeAnomaly: true,
    cashCounts: {},
    fundRemark: 'PRD-34 期初验收：首次日结',
  })
  assert(closeRes.closeNo === RJ_NO, `日结单号应 ${RJ_NO}，实际 ${closeRes.closeNo}`)
  assert(closeRes.idempotent === false, '首次日结 idempotent=false')
  console.log(`首次业务日结成功：${RJ_NO}`)

  // 3. 期初三模块全部锁定：导入/手工/过账/反建账都被拒，status.dayCloseLocked=true
  for (const api of ['/init/stock', '/init/ar', '/init/ap']) {
    const s = await post(api + '/status')
    assert(s.dayCloseLocked === true, `${api} 日结后 dayCloseLocked=true`)
    assert(s.canPost === false && s.canReverse === false, `${api} 日结后不可建账/反建账`)
  }
  const G = M.goods.code, WH = M.warehouse.code
  await expectFail('/init/stock/import',
    { mode: 'BATCH', rows: [{ goodsCode: G, warehouseCode: WH, qty: 1, unitPrice: 1 }], fileName: 'x.xlsx' },
    ['日结', '锁定'])
  await expectFail('/init/stock/line/save',
    { initMode: 'BATCH', goodsCode: G, warehouseCode: WH, qty: 1, unitPrice: 1 }, ['日结', '锁定'])
  await expectFail('/init/ar/import',
    { rows: [{ customerCode: M.customer.code, arAmount: 1 }], fileName: 'x.xlsx' }, ['日结', '锁定'])
  await expectFail('/init/ap/post', {}, ['日结', '锁定'])
  await expectFail('/init/stock/reverse', { reason: '日结后试反建账' }, ['日结', '锁定'])

  // 4. 反日结（原因留痕）→ 期初恢复可反建账
  const ro = await post('/finance/day-close/reopen', { date: D, reason: 'PRD-34 验收：日结锁定验证后反日结' })
  assert(ro !== undefined, '反日结应成功')
  for (const api of ['/init/stock', '/init/ar', '/init/ap']) {
    const s = await post(api + '/status')
    assert(s.dayCloseLocked === false, `${api} 反日结后 dayCloseLocked=false`)
    assert(s.canReverse === true, `${api} 反日结后 canReverse=true（供 M6 反建账链路）`)
  }

  console.log('PRD-34 init M5 (first day-close + lock + reopen) PASSED ✅')
}
main().catch(e => { console.error('init M5 FAILED ❌', e); process.exit(1) })
