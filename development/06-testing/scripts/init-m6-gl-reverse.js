/**
 * PRD-34 期初 M6：总账一键引入衔接 + 反建账全链路 + GL 启用锁定。
 *
 * 前置：按序跑过 m4（已建账）、m5（已反日结，当前无日结记录、总账未启用）。
 *   node development/06-testing/scripts/init-m6-gl-reverse.js
 *
 * 流程：业务期初引数核对 → 三模块反建账（正式账清零）→ 重新过账 → 再引数
 *      → 试算补齐到平衡 → 启用总账 → 期初全部锁定。
 */
const { post, login, assert, expectFail, bootstrapMasters } = require('./init-common.js')

async function findInPage(path, filters, pred) {
  const p = await post(path, { pageNo: 1, pageSize: 200, filters: filters || {} })
  return (p.records || []).filter(pred)
}

async function main() {
  await login()
  const M = await bootstrapMasters('INIT')
  const G = M.goods.code, G2 = M.goods2.code, WHN = M.warehouse.name, BIN = M.bin.code

  // GL 初始应为未启用
  const gl0 = await post('/finance/gl/init/status')
  assert(gl0.initialized === false, 'M6 前置：总账未启用')

  // ===== 1. 一键引入业务期初：AR 1 户 800 / AP 1 户 400 / 库存 2 品 51 个 582 =====
  const imp = await post('/finance/gl/init/business-import')
  assert(imp.arCount === 1 && Math.abs(Number(imp.arTotal) - 800) < 0.01,
    `引数应 AR 1 户 800，实际 ${imp.arCount}/${imp.arTotal}`)
  assert(imp.apCount === 1 && Math.abs(Number(imp.apTotal) - 400) < 0.01,
    `引数应 AP 1 户 400，实际 ${imp.apCount}/${imp.apTotal}`)
  assert(imp.goodsCount === 2 && Math.abs(Number(imp.goodsQty) - 51) < 0.0001
    && Math.abs(Number(imp.goodsTotal) - 582) < 0.01,
    `引数应库存 2 品 51/582，实际 ${imp.goodsCount}/${imp.goodsQty}/${imp.goodsTotal}`)
  console.log('总账一键引入业务期初：1122=800 / 2202=400 / 1405=582(51)')

  // ===== 2. 反建账全链路（GL 未启用、无日结、无核销/无后续流水）=====
  await post('/init/ar/reverse', { reason: 'M6 验收：反建账全链路' })
  await post('/init/ap/reverse', { reason: 'M6 验收：反建账全链路' })
  await post('/init/stock/reverse', { reason: 'M6 验收：反建账全链路' })

  for (const [api, label] of [['/init/stock', '库存'], ['/init/ar', '应收'], ['/init/ap', '应付']]) {
    const s = await post(api + '/status')
    assert(s.posted === false, `${label} 反建账后 posted=false`)
    assert(s.canPost === true && s.canReverse === false, `${label} 反建账后可重新过账、无反建账`)
  }
  // 正式账清零
  const imp0 = await post('/finance/gl/init/business-import')
  assert(Number(imp0.arCount) === 0 && Number(imp0.apCount) === 0 && Number(imp0.goodsCount) === 0,
    `反建账后业务期初应全 0，实际 ${JSON.stringify({ ar: imp0.arCount, ap: imp0.apCount, g: imp0.goodsCount })}`)
  const leftBals = await findInPage('/inventory/balance/page', {}, r => r.warehouse === WHN && [G, G2].includes(r.goodsCode))
  assert(leftBals.length === 0, `反建账后商品仓余额行应删除，实际 ${leftBals.length} 行`)
  const leftBinStocks = await findInPage('/wms/bin-stock/page', {}, r => r.goodsCode === G && r.binCode === BIN)
  assert(leftBinStocks.length === 0, '反建账后 wms_bin_stock 应清空')
  const bins = await post('/wms/bin/page', { pageNo: 1, pageSize: 200, filters: { warehouse: WHN } })
  assert(Number(bins.records.find(b => b.binCode === BIN)?.usedQty || 0) === 0, '反建账后库位 used_qty 应回 0')
  const leftArs = await findInPage('/finance/ar/page', {}, r => String(r.sourceBill || '').startsWith('QCAR-'))
  const leftAps = await findInPage('/finance/ap/page', {}, r => String(r.sourceBill || '').startsWith('QCAP-'))
  assert(leftArs.length === 0 && leftAps.length === 0, '反建账后 fin_ar/fin_ap 期初行应删除')
  console.log('反建账回退干净：三账/往来/库位/容量占用全部归零，暂存行恢复')

  // ===== 3. 重新过账（暂存行仍是 VALID）=====
  const sp = await post('/init/stock/post')
  const arp = await post('/init/ar/post')
  const app = await post('/init/ap/post')
  assert(Math.abs(Number(sp.totalAmount) - 582) < 0.01, `重新过账库存金额 582，实际 ${sp.totalAmount}`)
  assert(Math.abs(Number(arp.totalAmount) - 800) < 0.01, `重新过账 AR 800，实际 ${arp.totalAmount}`)
  assert(Math.abs(Number(app.totalAmount) - 400) < 0.01, `重新过账 AP 400，实际 ${app.totalAmount}`)
  const imp2 = await post('/finance/gl/init/business-import')
  assert(Number(imp2.goodsTotal) === 582 && Number(imp2.arTotal) === 800 && Number(imp2.apTotal) === 400,
    '重新过账后引数应恢复 800/400/582')

  // ===== 4. 试算平衡：把差额补到 3001 贷方，启用总账 =====
  let trial = await post('/finance/gl/init/trial-balance')
  const diff = Number(trial.totalDebit) - Number(trial.totalCredit)
  assert(Math.abs(diff) > 0, '期初引数后试算应有差额（无权益类期初）')
  if (diff > 0) {
    await post('/finance/gl/init/balance-save', { rows: [{ accountCode: '3001', openCredit: diff }] })
  } else {
    await post('/finance/gl/init/balance-save', { rows: [{ accountCode: '3001', openDebit: -diff }] })
  }
  trial = await post('/finance/gl/init/trial-balance')
  assert(trial.balanced === true, `补差额后试算应平衡，实际 ${trial.totalDebit}/${trial.totalCredit}`)
  const period = new Date().toISOString().slice(0, 7).replace('-', '')
  await post('/finance/gl/init/enable', { startPeriod: period })

  // ===== 5. GL 启用后：期初三模块锁死，业务期初不能再引 =====
  await expectFail('/finance/gl/init/business-import', {}, ['已启用'])
  for (const [api, label] of [['/init/stock', '库存'], ['/init/ar', '应收'], ['/init/ap', '应付']]) {
    const s = await post(api + '/status')
    assert(s.glInitialized === true && s.canPost === false && s.canReverse === false,
      `${label} GL 启用后应 glInitialized/不可过账/不可反建账`)
  }
  await expectFail('/init/stock/import',
    { mode: 'BATCH', rows: [{ goodsCode: G, warehouseCode: M.warehouse.code, qty: 1, unitPrice: 1 }], fileName: 'x' },
    ['总账', '锁定'])
  await expectFail('/init/stock/reverse', { reason: 'GL 启用后试反建账' }, ['总账', '锁定'])
  await expectFail('/init/ar/reverse', { reason: 'GL 启用后试反建账' }, ['总账', '锁定'])

  console.log('PRD-34 init M6 (GL link + reverse chain + GL lock) PASSED ✅')
}
main().catch(e => { console.error('init M6 FAILED ❌', e); process.exit(1) })
