// PRD-31 铺开核验：基础资料（商品）改前改后 diff + 敏感字段 + 状态流转（停用）。
// 用法：API_BASE=http://localhost:8090/api node prd31-rollout-verify.js
const BASE = process.env.API_BASE || 'http://localhost:8090/api'
let token
async function call(method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} -> code=${json.code} msg=${json.message}`)
  return json.data
}
const assert = (c, m) => { if (!c) throw new Error('断言失败: ' + m) }
const tag = 'LT' + Date.now().toString().slice(-6)

async function main() {
  const login = await fetch(`${BASE}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  }).then(r => r.json())
  assert(login.code === '0' && login.data?.token, '登录失败')
  token = login.data.token
  console.log('1) 登录成功（admin）')

  // ---- 新增商品（含敏感价 standardPrice）----
  const code = 'SP' + tag
  const payload = {
    goodsCode: code, goodsName: '日志核验商品', goodsType: '正常商品', spec: '500ml',
    categoryName: '饮用水', baseUnit: '箱', barcode: '69' + tag,
    standardPrice: 100, latestPurchasePrice: 80, minSalePrice: 90, suggestedRetailPrice: 110,
    storageProperty: '常温', remark: '初始备注',
  }
  await call('POST', '/base/goods/create', payload)
  console.log('2) 新增商品', code)

  // ---- 编辑：改关键字段 goodsName + 非关键字段 remark + 敏感价 standardPrice 100→120 ----
  await call('POST', '/base/goods/update', {
    ...payload, goodsId: code, goodsName: '日志核验商品-改', standardPrice: 120, remark: '改了备注',
  })
  console.log('3) 编辑商品（名称/备注/标准售价）')

  // ---- 停用：状态流转 DISABLE ----
  await call('POST', '/base/goods/stop', { goodsCode: code }).catch(e => ({ __fail: e.message }))
  console.log('4) 停用商品')

  // ---- 时间线（base_goods）----
  const tl = await call('POST', '/operation-log/bill-timeline', { bizType: 'base_goods', bizNo: code })
  console.log('5) 商品时间线节点数:', tl.length)
  for (const n of tl) {
    const main = n.afterValue?.main || []
    console.log(`   - [${n.actionName}] sensitive=${n.sensitive} ${n.operationContent || ''}`)
    if (main.length) console.log(`       diff: ${main.map(c => `${c.label}:${c.old}→${c.new}`).join(' | ')}`)
  }
  assert(tl.some(n => n.action === 'CREATE'), '应含 新增')
  const upd = tl.find(n => n.action === 'UPDATE')
  assert(upd, '应含 修改')
  assert((upd.afterValue?.main || []).some(c => c.field === 'goods_name'), '修改 diff 应含商品名称')
  assert(upd.operationContent && upd.operationContent.includes('非关键字段'), '仅备注等非关键字段变化应标注「非关键字段有修改」')
  assert(upd.sensitive === 'Y', '标准售价(敏感)变化应置 sensitive=Y')
  assert(tl.some(n => n.action === 'DISABLE'), '应含 停用')

  // ---- 管理端：敏感日志可被 sensitive 过滤到 ----
  const sensPage = await call('POST', '/system/operation-log/page',
    { pageNo: 1, pageSize: 20, filters: { bizNo: code, sensitive: 'Y' } })
  assert((sensPage.records || []).some(r => r.sensitive === 'Y'), '管理端应能按敏感=Y 过滤到该商品日志')
  console.log('6) 敏感日志管理端可过滤，sensitive=Y 共', (sensPage.records || []).filter(r => r.sensitive === 'Y').length, '条')

  console.log('\n✅ PRD-31 铺开（基础资料改前改后/敏感/停用）核验通过')
}
main().catch(e => { console.error('\n❌', e.message); process.exit(1) })
