/**
 * PRD-34 期初 M3：手工增改删 + 过账守卫（ERROR 行/已有库存余额整批拒绝）。
 * 前置：可先跑 m2（脚本开头会清空三模块暂存，不依赖 m2 结果）。
 *   node development/06-testing/scripts/init-m3-crud-postguard.js
 */
const { post, login, assert, expectFail, bootstrapMasters } = require('./init-common.js')

async function resetModule(api) {
  const s = await post(api + '/status')
  if (s.posted) await post(api + '/reverse', { reason: 'M3 验收脚本重置冒烟库' })
  await post(api + '/clear', {})
}

async function main() {
  await login()
  const M = await bootstrapMasters('INIT')
  const G = M.goods.code, WH = M.warehouse.code, NORMAL_BIN = M.bin.code
  const CUS = M.customer.code, SUP = M.supplier.code
  for (const api of ['/init/stock', '/init/ar', '/init/ap']) await resetModule(api)

  // ===== 1. 库存手工行：增 → 改 → 删 =====
  const save = await post('/init/stock/line/save', {
    initMode: 'BATCH', goodsCode: G, warehouseCode: WH,
    qty: 4, unitPrice: 8, batchNoInput: 'M3B1',
  })
  assert(save === null || typeof save === 'object', '手工新增应返回 ok')
  let page = await post('/init/stock/line/page', { pageNo: 1, pageSize: 50, posted: 'N' })
  assert(page.total === 1 && Number(page.records[0].qty) === 4, '手工行应落库 qty=4')
  const lineId = page.records[0].lineId
  await post('/init/stock/line/update', {
    lineId, initMode: 'BATCH', goodsCode: G, warehouseCode: WH,
    qty: 6, unitPrice: 8, batchNoInput: 'M3B1',
  })
  page = await post('/init/stock/line/page', { pageNo: 1, pageSize: 50, posted: 'N' })
  assert(Number(page.records[0].qty) === 6, '编辑后 qty 应为 6')
  await post('/init/stock/line/delete', { lineId })
  page = await post('/init/stock/line/page', { pageNo: 1, pageSize: 50, posted: 'N' })
  assert(page.total === 0, '删除后应为 0 行')

  // BIN 手工行缺库位 → 拒
  await expectFail('/init/stock/line/save', {
    initMode: 'BIN', goodsCode: G, warehouseCode: WH, qty: 1, unitPrice: 10, batchNoInput: 'M3X',
  }, '库位')

  // 双流程混用：先 BATCH 行，再存同（商品+仓+批号）BIN 行 → 拒
  await post('/init/stock/line/save', {
    initMode: 'BATCH', goodsCode: G, warehouseCode: WH, qty: 1, unitPrice: 10, batchNoInput: 'M3MIX',
  })
  await expectFail('/init/stock/line/save', {
    initMode: 'BIN', goodsCode: G, warehouseCode: WH, binCode: NORMAL_BIN,
    qty: 1, unitPrice: 10, batchNoInput: 'M3MIX',
  }, ['流程', '混'])
  await post('/init/stock/clear', {})

  // ===== 2. AR 手工行增改删；AP 缺金额拦截 =====
  await post('/init/ar/line/save', { customerCode: CUS, arAmount: 123, originalBillNo: 'M3A1' })
  let apage = await post('/init/ar/line/page', { pageNo: 1, pageSize: 50, posted: 'N' })
  assert(apage.total === 1 && Number(apage.records[0].arAmount) === 123, 'AR 手工行 123')
  const arId = apage.records[0].lineId
  await post('/init/ar/line/update', { lineId: arId, customerCode: CUS, arAmount: 321, originalBillNo: 'M3A1' })
  apage = await post('/init/ar/line/page', { pageNo: 1, pageSize: 50, posted: 'N' })
  assert(Number(apage.records[0].arAmount) === 321, 'AR 编辑后 321')
  await expectFail('/init/ap/line/save', { supplierCode: SUP }, '金额')
  await post('/init/ar/clear', {})

  // ===== 3. ERROR 行存在时过账被拒（AR：1 对 1 错）=====
  await post('/init/ar/import', { rows: [
    { customerCode: CUS, arAmount: 99, originalBillNo: 'M3OK' },
    { customerCode: 'GHOST-CUS', arAmount: 1, originalBillNo: 'M3BAD' },
  ], fileName: 'm3-ar.xlsx' })
  await expectFail('/init/ar/post', {}, ['错误', 'ERROR'])
  await post('/init/ar/clear', {})

  // ===== 4. 已有库存余额 → 整批拒绝 =====
  // 用专用仓库造数：其他入库单反审核会留存台账流水，若与 m4 同商品同仓会污染
  // m4 的加权成本与 m6 的反建账守卫；期初流程只在 WHM3 内发生，WHINIT 保持干净。
  const GD_WH = { code: 'WHM3GD', name: '期初守卫仓-M3' }
  try {
    await post('/base/warehouse/create', {
      warehouseCode: GD_WH.code, warehouseName: GD_WH.name,
      warehouseType: '正常仓', inventoryType: '平台主仓', costGroup: 'CG01',
    })
  } catch (e) { /* 已存在则跳过 */ }
  await post('/init/stock/line/save', {
    initMode: 'BATCH', goodsCode: G, warehouseCode: GD_WH.code, qty: 5, unitPrice: 10, batchNoInput: 'M3S1',
  })
  const inb = await post('/inventory/other-inbound/create', {
    warehouse: GD_WH.name, inboundType: '1', supplier: M.supplier.name, remark: 'M3 余额守卫造数',
    details: [{ goodsCode: G, goodsName: M.goods.name, unitName: '个', qty: 1, price: 10, costPrice: 10 }],
  })
  await post('/inventory/other-inbound/audit', { bizId: inb.inboundNo })
  await expectFail('/init/stock/post', {}, ['余额', '清零'])
  // 清理造数：反审核 → 删除，余额回 0（台账历史行留在专用仓，不影响正式验收仓）
  await post('/inventory/other-inbound/reverse-audit', { bizId: inb.inboundNo })
  await post('/inventory/other-inbound/delete', { bizId: inb.inboundNo })

  // 清空库存暂存，交给 M4 全新过账
  await post('/init/stock/clear', {})
  const s = await post('/init/stock/status')
  assert(s.lineCount === 0 && s.errorCount === 0, 'M3 结束时库存暂存应为空')

  console.log('PRD-34 init M3 (crud + post guard) PASSED ✅')
}
main().catch(e => { console.error('init M3 FAILED ❌', e); process.exit(1) })
