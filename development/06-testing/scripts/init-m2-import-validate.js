/**
 * PRD-34 期初 M2：混合坏行导入校验（全行落库 VALID/ERROR、失败文件、双流程混用拦截）。
 * 专用冒烟库执行；脚本会先反建账+清空本模块暂存，再重导。
 *   node development/06-testing/scripts/init-m2-import-validate.js
 */
const { post, getRaw, login, assert, bootstrapMasters } = require('./init-common.js')

async function resetModule(api) {
  const s = await post(api + '/status')
  if (s.posted) await post(api + '/reverse', { reason: 'M2 验收脚本重置冒烟库' })
  await post(api + '/clear', {})
}
async function lineCount(api, extra = {}) {
  const p = await post(api + '/line/page', { pageNo: 1, pageSize: 200, ...extra })
  return p
}

async function main() {
  await login()
  const M = await bootstrapMasters('INIT')
  const G = M.goods.code, G2 = M.goods2.code, WH = M.warehouse.code, NORMAL_BIN = M.bin.code, BAD_BIN = M.bin.disabledCode
  const CUS = M.customer.code, SUP = M.supplier.code

  for (const api of ['/init/stock', '/init/ar', '/init/ap']) await resetModule(api)

  // ===== 库存-按批次：7 错 2 对（含文件内重复键、BATCH 模式填库位）=====
  const batchRows = [
    { goodsCode: G, warehouseCode: WH, qty: 10, unitPrice: 10, batchNoInput: 'M2DUP' },
    { goodsCode: G, warehouseCode: WH, qty: 10, unitPrice: 10, batchNoInput: 'M2DUP' },
    { goodsCode: 'GHOST-GOODS', warehouseCode: WH, qty: 1, unitPrice: 10, batchNoInput: 'E1' },
    { goodsCode: G, warehouseCode: 'GHOST-WH', qty: 1, unitPrice: 10, batchNoInput: 'E2' },
    { goodsCode: G, warehouseCode: WH, qty: -1, unitPrice: 10, batchNoInput: 'E3' },
    { goodsCode: G, warehouseCode: WH, qty: 5, batchNoInput: 'E4' },
    { goodsCode: G, warehouseCode: WH, qty: 5, unitPrice: 10,
      productionDate: '2026-01-10', expiryDate: '2025-12-01', batchNoInput: 'E5' },
    { goodsCode: G2, warehouseCode: WH, binCode: NORMAL_BIN, qty: 1, unitPrice: 10, batchNoInput: 'E6' },
    { goodsCode: G2, warehouseCode: WH, qty: 7, unitPrice: 10, batchNoInput: 'M2OK' },
  ]
  const r1 = await post('/init/stock/import', { mode: 'BATCH', rows: batchRows, fileName: 'm2-batch.xlsx' })
  assert(r1.inserted === 2, `按批次应成功 2 行，实际 ${r1.inserted}`)
  assert(r1.failed === 7, `按批次应失败 7 行，实际 ${r1.failed}`)

  // ===== 库存-按库位：3 错 1 对（库位不存在/停用/跨模式混用）=====
  const binRows = [
    { goodsCode: G2, warehouseCode: WH, binCode: NORMAL_BIN, qty: 3, unitPrice: 10, batchNoInput: 'M2BINOK' },
    { goodsCode: G2, warehouseCode: WH, binCode: 'NO-SUCH-BIN', qty: 1, unitPrice: 10, batchNoInput: 'B1' },
    { goodsCode: G2, warehouseCode: WH, binCode: BAD_BIN, qty: 1, unitPrice: 10, batchNoInput: 'B2' },
    { goodsCode: G, warehouseCode: WH, binCode: NORMAL_BIN, qty: 1, unitPrice: 10, batchNoInput: 'M2DUP' },
  ]
  const r2 = await post('/init/stock/import', { mode: 'BIN', rows: binRows, fileName: 'm2-bin.xlsx' })
  assert(r2.inserted === 1, `按库位应成功 1 行，实际 ${r2.inserted}`)
  assert(r2.failed === 3, `按库位应失败 3 行，实际 ${r2.failed}`)

  // ERROR 行行内原因可见
  const errPage = await lineCount('/init/stock', { lineStatus: 'ERROR' })
  assert(errPage.total === 10, `库存暂存 ERROR 应 10 行，实际 ${errPage.total}`)
  assert(errPage.records.every(r => r.errorMsg), 'ERROR 行必须带失败原因')
  const reasons = errPage.records.map(r => r.errorMsg).join('|')
  for (const kw of ['商品', '仓库', '数量', '成本', '效期', '库位', '重复', '流程']) {
    assert(reasons.includes(kw), `失败原因应覆盖「${kw}」场景，实际：${reasons.slice(0, 300)}`)
  }
  // 失败文件可下载
  const fileRes = await getRaw(`/system/import-list/failure-file/${r1.taskNo}`)
  assert(fileRes.status === 200, `失败文件应 200，实际 ${fileRes.status}`)

  // 库存状态汇总：有效 3（M2DUP/M2OK/M2BINOK）、错误 10
  const stkStatus = await post('/init/stock/status')
  assert(stkStatus.lineCount === 3, `库存有效行应 3，实际 ${stkStatus.lineCount}`)
  assert(stkStatus.errorCount === 10, `库存错误行应 10，实际 ${stkStatus.errorCount}`)
  assert(stkStatus.batchCount >= 1 && stkStatus.binCount >= 1, '应同时有按批次/按库位有效行')

  // ===== AR：坏客户 / 负金额 / 文件内重复 =====
  const arRows = [
    { customerCode: CUS, arAmount: 100, originalBillNo: 'M2A1' },
    { customerCode: CUS, arAmount: 100, originalBillNo: 'M2A1' },
    { customerCode: 'GHOST-CUS', arAmount: 50, originalBillNo: 'M2A2' },
    { customerCode: CUS, arAmount: -5, originalBillNo: 'M2A3' },
  ]
  const ra = await post('/init/ar/import', { rows: arRows, fileName: 'm2-ar.xlsx' })
  assert(ra.inserted === 1 && ra.failed === 3, `AR 应 1 成 3 败，实际 ${ra.inserted}/${ra.failed}`)
  const arErr = await lineCount('/init/ar', { lineStatus: 'ERROR' })
  assert(arErr.total === 3 && arErr.records.every(r => r.errorMsg), 'AR ERROR 应 3 行且带原因')

  // ===== AP：坏供应商 / 0 金额 =====
  const apRows = [
    { supplierCode: SUP, apAmount: 200, originalBillNo: 'M2P1' },
    { supplierCode: 'GHOST-SUP', apAmount: 50, originalBillNo: 'M2P2' },
    { supplierCode: SUP, apAmount: 0, originalBillNo: 'M2P3' },
  ]
  const rp = await post('/init/ap/import', { rows: apRows, fileName: 'm2-ap.xlsx' })
  assert(rp.inserted === 1 && rp.failed === 2, `AP 应 1 成 2 败，实际 ${rp.inserted}/${rp.failed}`)

  console.log('PRD-34 init M2 (import validation) PASSED ✅')
}
main().catch(e => { console.error('init M2 FAILED ❌', e); process.exit(1) })
