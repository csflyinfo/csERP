/**
 * 总账 M1（PRD-31）冒烟测试：会计科目 + 初始化 + 核算项目
 *
 * 用法：
 *   1. 后端用空库启动（Flyway 自动建表）：
 *      java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8081 \
 *        --spring.datasource.url="jdbc:h2:file:./data/erp-smoke-gl1;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *        --spring.autoconfigure.exclude="org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"
 *   2. node development/06-testing/scripts/gl-m1-account-init.js
 *      （默认连 http://localhost:8080/api，可用 API_BASE 覆盖）
 *
 * 注意：本脚本会把空库总账启用至 202609，必须跑在专用冒烟库上，不要连生产/开发库。
 */
const BASE = process.env.API_BASE || 'http://localhost:8081/api'

let authToken = 'demo-token'   // 登录前占位；登录后替换为真实 JWT

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
/** 期望失败（业务校验拦截），返回错误信息。 */
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

async function main() {
  await login()

  // ===== 1. 菜单：总账管理组与三个 M1 菜单 =====
  const menus = await get('/system/menu/user-tree')
  const glGroup = menus.find(m => m.name === '总账管理' || m.menuName === '总账管理')
  assert(glGroup, '菜单树应包含「总账管理」分组')
  const glCodes = JSON.stringify(glGroup)
  assert(glCodes.includes('glAccount') && glCodes.includes('glInitBalance') && glCodes.includes('glAuxProject'),
    '总账菜单应含 glAccount/glInitBalance/glAuxProject')

  // ===== 2. 科目种子：小企业会计准则体系 =====
  const accounts = await post('/finance/gl/account/list')
  assert(accounts.length >= 60, `预置科目应不少于 60 个，实际 ${accounts.length}`)
  const byCode = Object.fromEntries(accounts.map(a => [a.accountCode, a]))
  assert(byCode['1001']?.isCash === true && byCode['1001']?.balanceDirection === '借', '1001 库存现金应为现金类、借方')
  assert(byCode['1122']?.auxDimensions === 'customer', '1122 应收账款应挂客户辅助核算')
  assert(byCode['2202']?.auxDimensions === 'supplier', '2202 应付账款应挂供应商辅助核算')
  assert(byCode['1405']?.isQty === true && byCode['1405']?.auxDimensions === 'goods', '1405 库存商品应数量核算+商品辅助')
  assert(byCode['4001']?.accountType === '成本' && byCode['4001']?.balanceDirection === '借', '4001 生产成本应为成本类借方')
  assert(byCode['3103']?.accountName === '本年利润', '3103 本年利润应存在')
  assert(byCode['1002']?.isLeaf === false && byCode['100201']?.isLeaf === true, '1002 银行存款应为非末级、100201 末级')
  // 2221 应交税费下所有明细方向一律贷（含进项税额）
  const taxDetails = accounts.filter(a => a.accountCode.startsWith('2221') && a.accountCode.length > 4)
  assert(taxDetails.length >= 10, `2221 应交税费明细应不少于 10 个，实际 ${taxDetails.length}`)
  assert(taxDetails.every(a => a.balanceDirection === '贷'), '2221 下所有明细（含进项税额）方向必须为贷')

  // ===== 3. 科目新增/编码规则/停用 =====
  const created = await post('/finance/gl/account/create', {
    parentCode: '1801', accountName: '装修费摊销', auxDimensions: 'department',
  })
  assert(created.accountCode === '180101', `1801 下自动编码应为 180101，实际 ${created.accountCode}`)
  assert(created.accountType === '资产' && created.balanceDirection === '借', '子科目应继承父科目类别/方向')
  const parent = (await post('/finance/gl/account/list')).find(a => a.accountCode === '1801')
  assert(parent.isLeaf === false, '新增子科目后父科目应变为非末级')

  await expectFail('/finance/gl/account/create', { accountCode: '1001', accountName: '重复' }, '已存在')
  await expectFail('/finance/gl/account/create', { accountCode: '9999', accountName: '首位非法' }, '1~5')
  await expectFail('/finance/gl/init/balance-save', { rows: [{ accountCode: '1002', openDebit: 1 }] }, '末级')

  // 有启用子级时父级不可停用（此时 180101 为启用状态）
  await expectFail('/finance/gl/account/toggle-status', { id: parent.id }, '下级')

  // 停用后 leaf-options 不返回；停用后再启用
  await post('/finance/gl/account/toggle-status', { id: created.id })
  const optsWhileDisabled = await post('/finance/gl/account/leaf-options', { keyword: '180101' })
  assert(optsWhileDisabled.length === 0, '停用科目不应出现在末级下拉中')
  await post('/finance/gl/account/toggle-status', { id: created.id }) // 重新启用

  const cashOpts = await post('/finance/gl/account/leaf-options', { cashOnly: true })
  assert(cashOpts.every(o => o.isCash === true), 'cashOnly 下拉应只含现金类科目')
  assert(cashOpts.some(o => o.accountCode === '1001'), '现金类下拉应含 1001')

  // ===== 4. 期初余额：主科目行 + 辅助明细行 + 互斥校验 =====
  const status0 = await post('/finance/gl/init/status')
  assert(status0.initialized === false, '新库总账应为未启用')

  await post('/finance/gl/init/balance-save', { rows: [
    { accountCode: '1001', openDebit: 10000 },
    { accountCode: '3001', openCredit: 15000 },
  ] })
  // 1122 挂客户辅助：期初必须走辅助明细
  await post('/finance/gl/init/balance-save', { rows: [
    { accountCode: '1122', auxCustomer: 'C001', openDebit: 5000 },
  ] })
  // 辅助行已存在，主科目行必须被拒
  await expectFail('/finance/gl/init/balance-save', { rows: [{ accountCode: '1122', openDebit: 1 }] }, '辅助')
  // 未配置该辅助维度的科目传辅助值必须被拒
  await expectFail('/finance/gl/init/balance-save',
    { rows: [{ accountCode: '1001', auxSupplier: 'S001', openDebit: 1 }] }, '辅助核算维度')

  const auxRows = await post('/finance/gl/init/aux-balance-list', { accountCode: '1122' })
  assert(auxRows.length === 1 && Number(auxRows[0].openDebit) === 5000, '1122 辅助明细应有 1 行借方 5000')

  const trial = await post('/finance/gl/init/trial-balance')
  assert(Number(trial.totalDebit) === 15000 && Number(trial.totalCredit) === 15000,
    `试算应 15000/15000 平衡，实际 ${trial.totalDebit}/${trial.totalCredit}`)
  assert(trial.balanced === true, '试算结果应为平衡')

  // ===== 5. 一键引入业务期初（空库：全 0 但不报错）=====
  const imp = await post('/finance/gl/init/business-import')
  assert(imp.arCount === 0 && imp.apCount === 0 && imp.goodsCount === 0, '空库业务期初引入应为 0')

  // 核算项目 CRUD
  const proj = await post('/finance/gl/aux-project/create', {
    projectCode: 'P001', projectName: '冒烟测试专项', projectType: '测试',
    ownerName: '张三', budgetAmount: 8888, status: '启用',
  })
  const projList = await post('/finance/gl/aux-project/list', {})
  assert(projList.some(p => p.projectCode === 'P001' && Number(p.budget) === 8888), '项目列表应含 P001 且预算 8888')
  await post('/finance/gl/aux-project/update', { id: proj.id, projectCode: 'P001', projectName: '冒烟测试专项',
    projectType: '测试', ownerName: '张三', budgetAmount: 9999, status: '启用' })
  const projList2 = await post('/finance/gl/aux-project/list', {})
  assert(Number(projList2.find(p => p.projectCode === 'P001').budget) === 9999, '项目预算更新应为 9999')

  // ===== 6. 启用总账：期间生成 + 期初锁定 =====
  await expectFail('/finance/gl/init/enable', { startPeriod: '2026' }, 'yyyyMM')
  await post('/finance/gl/init/enable', { startPeriod: '202609' })

  const status1 = await post('/finance/gl/init/status')
  assert(status1.initialized === true && status1.startPeriod === '202609', '启用后状态应为已启用/启用期间 202609')
  assert(status1.currentPeriod === '202609', '当前期间应为 202609')

  const periods = await post('/finance/gl/init/period-list')
  assert(periods.length === 12, `应生成 12 个期间，实际 ${periods.length}`)
  const st = Object.fromEntries(periods.map(p => [p.period, p.status]))
  assert(st['202608'] === '已结账' && st['202609'] === '进行中' && st['202610'] === '未开始',
    '启用前期间应已结账、启用月进行中、之后未开始')

  await expectFail('/finance/gl/init/balance-save', { rows: [{ accountCode: '1001', openDebit: 1 }] }, '锁定')
  await expectFail('/finance/gl/init/enable', { startPeriod: '202610' }, '已启用')

  console.log('GL M1 (account + init + aux-project) smoke test PASSED ✅')
}

main().catch(error => {
  console.error('GL M1 smoke test FAILED ❌', error)
  process.exit(1)
})
