/**
 * PRD-34 期初 M4：三模块正式过账 + 正式账核账。
 * 全新冒烟库执行（脚本会先反建账/清空）：
 *   node development/06-testing/scripts/init-m4-post-verify.js
 *
 * 库存核账口径：
 *   G 商品：BATCH 10@8 + 30@12，BIN 6@7（不同批次 PB1）
 *   加权均价 = (80+360+42)/46 = 10.478261；cost_price 列为 2 位小数显示 10.48，
 *   但建账金额必须是实际金额 482.00（台账/批次账同口径，不用截断单价反乘）
 *   G2 商品：5@20 = 100
 */
const { post, login, assert, bootstrapMasters } = require('./init-common.js')

async function resetModule(api) {
  const s = await post(api + '/status')
  if (s.posted) await post(api + '/reverse', { reason: 'M4 验收脚本重置冒烟库' })
  await post(api + '/clear', {})
}
async function findInPage(path, filters, pred) {
  const p = await post(path, { pageNo: 1, pageSize: 200, filters: filters || {} })
  return (p.records || []).filter(pred)
}

async function main() {
  await login()
  const M = await bootstrapMasters('INIT')
  const G = M.goods.code, G2 = M.goods2.code, WH = M.warehouse.code, WHN = M.warehouse.name
  const BIN = M.bin.code, CUS = M.customer.code, SUP = M.supplier.code

  for (const api of ['/init/stock', '/init/ar', '/init/ap']) await resetModule(api)

  // ===== 库存暂存：BATCH 3 行（G 两批 + G2 一批）=====
  await post('/init/stock/import', { mode: 'BATCH', rows: [
    { goodsCode: G, warehouseCode: WH, qty: 10, unitPrice: 8, batchNoInput: 'M4P1', productionDate: '2026-08-01' },
    { goodsCode: G, warehouseCode: WH, qty: 30, unitPrice: 12, batchNoInput: 'M4P2' },
    { goodsCode: G2, warehouseCode: WH, qty: 5, unitPrice: 20, batchNoInput: 'M4Q1' },
  ], fileName: 'm4-batch.xlsx' })
  // BIN 1 行（G 的新批次，允许——同键不同批）
  await post('/init/stock/import', { mode: 'BIN', rows: [
    { goodsCode: G, warehouseCode: WH, binCode: BIN, qty: 6, unitPrice: 7, batchNoInput: 'M4PB1' },
  ], fileName: 'm4-bin.xlsx' })

  let st = await post('/init/stock/status')
  assert(st.lineCount === 4 && st.errorCount === 0, `过账前应 4 有效 0 错误，实际 ${st.lineCount}/${st.errorCount}`)

  // ===== AR 2 行（同客户两笔）；AP 1 行 =====
  await post('/init/ar/import', { rows: [
    { customerCode: CUS, arAmount: 500, originalBillNo: 'M4A1', salesman: '冒烟员' },
    { customerCode: CUS, arAmount: 300, originalBillNo: 'M4A2' },
  ], fileName: 'm4-ar.xlsx' })
  await post('/init/ap/import', { rows: [
    { supplierCode: SUP, apAmount: 400, originalBillNo: 'M4P01' },
  ], fileName: 'm4-ap.xlsx' })

  // ===== 过账 =====
  const stockPost = await post('/init/stock/post')
  assert(/^QC\d{12}$/.test(stockPost.postNo), `库存过批号格式异常：${stockPost.postNo}`)
  assert(stockPost.lineCount === 4, `库存过账 4 行，实际 ${stockPost.lineCount}`)
  assert(Math.abs(Number(stockPost.totalQty) - 51) < 0.0001, `总量 51，实际 ${stockPost.totalQty}`)
  assert(Math.abs(Number(stockPost.totalAmount) - 582) < 0.01, `总金额 582.00，实际 ${stockPost.totalAmount}`)

  const arPost = await post('/init/ar/post')
  assert(arPost.lineCount === 2 && Math.abs(Number(arPost.totalAmount) - 800) < 0.01,
    `AR 过账 2 行 800，实际 ${arPost.lineCount}/${arPost.totalAmount}`)
  const apPost = await post('/init/ap/post')
  assert(apPost.lineCount === 1 && Math.abs(Number(apPost.totalAmount) - 400) < 0.01,
    `AP 过账 1 行 400，实际 ${apPost.lineCount}/${apPost.totalAmount}`)

  // 状态：已建账、不可重复过账
  st = await post('/init/stock/status')
  assert(st.posted === true && st.canPost === false && st.canReverse === true, '库存过账后状态应锁定可反建账')
  assert(st.postLineCount === 4 && Math.abs(Number(st.postTotalQty) - 51) < 0.0001, '已建账汇总行/量')

  // ===== 库存余额核账 =====
  const bals = await findInPage('/inventory/balance/page', {}, r => r.warehouse === WHN && [G, G2].includes(r.goodsCode))
  const bg = bals.find(b => b.goodsCode === G)
  const bg2 = bals.find(b => b.goodsCode === G2)
  assert(bg && bg2, '余额表应含 G/G2 两行')
  assert(Math.abs(Number(bg.physicalQty) - 46) < 0.0001 && Math.abs(Number(bg.availableQty) - 46) < 0.0001,
    `G 物理/可用应 46，实际 ${bg.physicalQty}/${bg.availableQty}`)
  assert(Math.abs(Number(bg.costPrice) - 10.48) < 0.0001, `G 加权均价列存 2 位应为 10.48，实际 ${bg.costPrice}`)
  assert(Math.abs(Number(bg.stockAmount) - 482) < 0.01, `G 金额 482.00，实际 ${bg.stockAmount}`)
  assert(Math.abs(Number(bg2.physicalQty) - 5) < 0.0001 && Math.abs(Number(bg2.costPrice) - 20) < 0.0001,
    `G2 应 5@20，实际 ${bg2.physicalQty}/${bg2.costPrice}`)

  // ===== 批次账：三批数量 + 批次自身成本 + 效期（P1 显式生产日期，效期按保质期 365 天）=====
  // 批次 page 的 keyword 不搜批号，全量拉回按批号过滤；数量字段为 physicalQty
  const batches = await findInPage('/inventory/batch/page', {}, r => String(r.batchNo || '').startsWith('M4'))
  const byBatch = Object.fromEntries(batches.map(b => [b.batchNo, b]))
  assert(Math.abs(Number(byBatch.M4P1?.physicalQty) - 10) < 0.0001, '批次 M4P1 应 10')
  assert(Math.abs(Number(byBatch.M4P2?.physicalQty) - 30) < 0.0001, '批次 M4P2 应 30')
  assert(Math.abs(Number(byBatch.M4PB1?.physicalQty) - 6) < 0.0001, '批次 M4PB1 应 6')
  // 批次成本是批次自身单价（与采购入库口径一致），金额合计 80+360+42+100=582
  for (const [bn, price, amount] of [['M4P1', 8, 80], ['M4P2', 12, 360], ['M4PB1', 7, 42], ['M4Q1', 20, 100]]) {
    assert(Math.abs(Number(byBatch[bn]?.costPrice) - price) < 0.0001 && Math.abs(Number(byBatch[bn]?.stockAmount) - amount) < 0.01,
      `批次 ${bn} 应 ${price}@${amount}，实际 ${byBatch[bn]?.costPrice}/${byBatch[bn]?.stockAmount}`)
  }
  // 2026-08-01 + 365 天 = 2027-08-01（保质期推算，允许引擎日期差一两天）
  assert(String(byBatch.M4P1?.expiryDate || '').startsWith('2027-'), `M4P1 效期应推算到 2027，实际 ${byBatch.M4P1?.expiryDate}`)

  // ===== 台账：QTRK 前缀、IN、行内余额累计 =====
  const ledgers = await findInPage('/inventory/ledger/page', { keyword: 'QTRK-' + stockPost.postNo }, () => true)
  assert(ledgers.length >= 4, `台账应至少 4 行 QTRK，实际 ${ledgers.length}`)
  assert(ledgers.every(l => ['IN', '入库'].includes(l.direction) && String(l.sourceBill).startsWith('QTRK-' + stockPost.postNo)),
    '期初台账应为入库方向且同过批号前缀')
  // 台账金额按批次实际成本记：80+360+42+100 = 582，不允许 2 位单价截断尾差
  const ledgerAmount = ledgers.reduce((s, l) => s + Number(l.amount || 0), 0)
  assert(Math.abs(ledgerAmount - 582) < 0.01, `期初台账金额合计应 582.00，实际 ${ledgerAmount.toFixed(2)}`)

  // ===== wms_bin_stock + 库位容量占用 =====
  const binStocks = await findInPage('/wms/bin-stock/page', {}, r => r.goodsCode === G && r.binCode === BIN)
  const bs = binStocks[0]
  assert(bs && Math.abs(Number(bs.qty) - 6) < 0.0001, `库位账应 6，实际 ${bs?.qty}`)
  const bins = await post('/wms/bin/page', { pageNo: 1, pageSize: 200, filters: { warehouse: WHN } })
  const usedBin = bins.records.find(b => b.binCode === BIN)
  assert(Math.abs(Number(usedBin.usedQty || 0) - 6) < 0.0001, `库位 used_qty 应 6，实际 ${usedBin?.usedQty}`)

  // 暂存行回写来源号
  const initLines = await post('/init/stock/line/page', { pageNo: 1, pageSize: 50, posted: 'Y' })
  assert(initLines.records.every(r => r.postNo === stockPost.postNo && r.generatedLedgerIds), '暂存行应回写过批号/台账来源号')
  const binLine = initLines.records.find(r => r.initMode === 'BIN')
  assert(binLine && binLine.generatedBinStockIds, 'BIN 行应回写库位账 ID')

  // ===== fin_ar：QCAR、UNVERIFIED、未开票、received=0、created_at=今天、due=+30 =====
  const ars = await findInPage('/finance/ar/page', {}, r => String(r.sourceBill || '').startsWith('QCAR-' + arPost.postNo))
  assert(ars.length === 2, `fin_ar 应 2 行，实际 ${ars.length}`)
  const today = new Date().toISOString().slice(0, 10)
  const due = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10)
  for (const a of ars) {
    assert(['UNVERIFIED', '未核销'].includes(a.status), `AR 状态应未核销(UNVERIFIED)，实际 ${a.status}`)
    assert(a.invoiceStatus === '未开票', `AR 票态应未开票，实际 ${a.invoiceStatus}`)
    assert(Number(a.receivedAmount) === 0 && Number(a.unreceivedAmount) === Number(a.arAmount), 'AR 已收 0/未收=全额')
    assert(String(a.createdAt).slice(0, 10) === today, `AR created_at 应=建账日 ${today}，实际 ${a.createdAt}`)
    assert(String(a.dueDate).slice(0, 10) === due, `AR due_date 应 +30 ${due}，实际 ${a.dueDate}`)
  }
  const arInit = await post('/init/ar/line/page', { pageNo: 1, pageSize: 50, posted: 'Y' })
  assert(arInit.records.every(r => r.generatedArNo && r.postNo === arPost.postNo), 'AR 暂存行回写 ar_no')

  // ===== fin_ap：QCAP、未来票、paid=0、due=+30 =====
  const aps = await findInPage('/finance/ap/page', {}, r => String(r.sourceBill || '').startsWith('QCAP-' + apPost.postNo))
  assert(aps.length === 1, `fin_ap 应 1 行，实际 ${aps.length}`)
  const a = aps[0]
  assert(Number(a.paidAmount) === 0 && Number(a.unpaidAmount) === Number(a.apAmount), 'AP 已付 0/未付=全额')
  assert((a.invoiceStatus || '未来票') === '未来票', `AP 票态未来票，实际 ${a.invoiceStatus}`)
  assert(String(a.dueDate).slice(0, 10) === due, `AP due_date +30，实际 ${a.dueDate}`)

  console.log(`过账核账通过：库存 QC ${stockPost.postNo} 46 个均价显示 10.48/实际金额 482.00 + 5@20(100)；AR 2 笔 800；AP 1 笔 400`)
  console.log('PRD-34 init M4 (post & verify) PASSED ✅')
}
main().catch(e => { console.error('init M4 FAILED ❌', e); process.exit(1) })
