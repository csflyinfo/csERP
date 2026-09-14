/**
 * PRD-33 业务日结（按日封单）验收脚本。
 *
 * ⚠️ 独立脚本，不进 CI；必须对【专用冒烟空库】运行（与 gl-m5-period.js 同款启动方式）：
 *   java -jar backend/target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8081 \
 *     --spring.datasource.url="jdbc:h2:file:./data/erp-smoke-dayclose;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     --spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
 *   node development/06-testing/scripts/v1-biz-day-close-test.js
 *
 * 脚本会真正执行/反执行【今天】的日结，并自建一张其他入库单（结束前恢复为已日结状态）。
 * 切勿对含手工业务数据的库运行（默认 8081 端口即隔离约定）。
 *
 * 覆盖验收用例（方案 §10 编号）：
 *  1  菜单/路由功能点（finance.day_close、system.schedule_task）
 *  6  跳日日结被拒、重复日结幂等（并发双结由唯一索引保证，属 SQL 层用例）
 *  7  反日结原因空/过短被拒、单日反结、重新日结、日志 CLOSE/REOPEN 链、批量反结（含原因留痕）
 *  9  定时任务：分页/改 cron 校验+未来 5 次/非法 cron 拒绝/停用再启用/立即执行跳过/执行日志
 * 11  功能点存在性（无功能点 403 用例需低权账号，见文末手工用例清单）
 * 13  首次日结：未审核期初入库单硬阻断（脚本自建后删除）；未勾选期初确认被拒
 * 14  资金备注不阻断：fundRemark 随 RJ 单持久化；step6 档案差异/现金实盘为备注项
 * 17  RJ-yyyyMMdd 单号 + ticket 可取（合计/定版台账数组）
 * 18  工作台提醒接口返回结构（show/late/yesterday/lastClosed/lastFail/lateHour）
 * 3/16 守卫链路：其他入库单审核回填 bill_date=审核日；封单后反审核被拒「已日结封单」；反结后恢复
 *
 * 未自动化（方案 §10 用例 2/4/5/8/10/12/15/16 部分），需造跨阶段单据链/GL 启用/
 * 绕过服务直改 SQL/账户改名等，列在脚本结尾 console 提示中，属手工验收项。
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
/** 不抛错的 POST，返回 {ok, status, message, data}。 */
async function postRaw(path, body = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + authToken },
    body: JSON.stringify(body),
  })
  const json = await res.json()
  return { ok: json.code === '0', status: res.status, message: json.message, data: json.data }
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
// 按本机日期（后端 H2 CURRENT_DATE 同样取本机时区），不能用 toISOString（UTC，凌晨会差一天）
function fmtLocal(d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
function today() { return fmtLocal(new Date()) }
function yesterday() {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  return fmtLocal(d)
}
const day = v => String(v || '').slice(0, 10)
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

/** 建一张 PENDING 其他入库单（不指定 billDate，默认今天）。 */
async function createOtherInbound(inboundType, label) {
  return post('/inventory/other-inbound/create', {
    warehouse: '总仓',
    inboundType,
    supplier: '验收供应商',
    remark: `PRD33验收-${label}`,
    details: [{ goodsCode: 'SP001', goodsName: '农夫山泉500ml*24', unitName: '箱', qty: 1, price: 10, costPrice: 10 }],
  })
}

async function main() {
  await login()
  const D = today()
  const Dm1 = yesterday()
  const RJ_NO = 'RJ-' + D.replace(/-/g, '')

  // ===== 用例 1：菜单含日结管理 / 定时任务（页面码在用户菜单树，按钮功能点在功能点表） =====
  const menus = await get('/system/menu/user-tree')
  const menuJson = JSON.stringify(menus)
  assert(menuJson.includes('finance.day_close'), '菜单树应含页面 finance.day_close')
  assert(menuJson.includes('system.schedule_task'), '菜单树应含页面 system.schedule_task')
  const closeFuncs = await get('/system/func/list?menuId=M_finance_day_close')
  const closeFuncCodes = closeFuncs.map(f => f.funcCode)
  assert(closeFuncCodes.includes('finance.day_close.view'), '日结功能点缺 view')
  assert(closeFuncCodes.includes('finance.day_close.audit'), '日结功能点缺 audit')
  assert(closeFuncCodes.includes('finance.day_close.unaudit'), '日结功能点缺 unaudit（v1.3 纯 RBAC 反日结）')
  const taskFuncs = await get('/system/func/list?menuId=M_system_schedule_task')
  const taskFuncCodes = taskFuncs.map(f => f.funcCode)
  for (const c of ['system.schedule_task.view', 'system.schedule_task.edit', 'system.schedule_task.execute']) {
    assert(taskFuncCodes.includes(c), `定时任务功能点缺 ${c}`)
  }

  // 干净起点：专用空库不应有日结记录；若有残留（重复跑）先批量反到今天，保证从首次日结开始
  const pre = await post('/finance/day-close/page', { pageNo: 1, pageSize: 10, filters: {} })
  if ((pre.total || 0) > 0) {
    const oldest = day((pre.records || []).map(r => r.closeDate).sort()[0])
    await post('/finance/day-close/reopen-batch', { toDate: oldest, reason: '验收脚本重复执行：重置冒烟库日结状态' })
  }

  // 商品档案：专用空库无商品档案（其他入库允许手输商品）。补建 SP001 档案以验证
  // 商品定版档案列在结账时随 rpt_dim_goods 维度刷新并冻结（规格/条码/基本单位/品牌/温区）
  const goodsPage = await post('/base/goods/page', { pageNo: 1, pageSize: 20, filters: { keyword: 'SP001' } })
  if (!(goodsPage.records || []).some(r => r.goodsCode === 'SP001')) {
    await post('/base/goods/create', {
      goodsCode: 'SP001', goodsName: '农夫山泉500ml*24', spec: '500ml*24',
      categoryName: '饮用水', brandName: '农夫山泉', baseUnit: '瓶',
      barcode: '6901234500011', standardPrice: 12, goodsType: '正常商品',
      storageProperty: '常温', defaultWarehouse: '总仓',
    })
  }

  // ===== 用例 13a：首次日结存在未审核期初入库单 → 硬阻断，随后删除该单 =====
  const opening = await createOtherInbound('0', '期初待审')
  let wz = await post('/finance/day-close/wizard', { date: D })
  assert(wz.firstClose === true, '空库首跑向导 firstClose 应为 true')
  assert(wz.step1.passed === false, '存在未审核期初入库单时 step1 应不通过')
  assert(JSON.stringify(wz.step1.errors).includes('期初入库单'), 'step1 错误应提示期初入库单')
  await expectFail(() => post('/finance/day-close/close',
    { date: D, openingConfirmed: true }), '期初入库单', '有未审核期初单时日结')
  await post('/inventory/other-inbound/delete', { bizId: opening.inboundNo })

  // ===== 用例 3/16：一般单据审核回填生效日=审核日 =====
  const inb = await createOtherInbound('1', '样品入库')
  await post('/inventory/other-inbound/audit', { bizId: inb.inboundNo })
  const inbDetail = await get(`/inventory/other-inbound/detail?inboundId=${inb.inboundNo}`)
  assert(inbDetail.status === 'APPROVED', '其他入库单应已审核')
  assert(day(inbDetail.billDate) === D, `审核后 bill_date 应回填为审核日 ${D}，实际 ${day(inbDetail.billDate)}`)

  // 向导七步结构齐
  wz = await post('/finance/day-close/wizard', { date: D })
  for (const k of ['step1', 'step2', 'step3', 'step4', 'step5', 'step6', 'step7', 'totals', 'canClose']) {
    assert(k in wz, `向导缺字段 ${k}`)
  }
  assert(Array.isArray(wz.step2.items), 'step2.items 应为数组（跨阶挂账清单）')
  assert(wz.step1.firstClose === true && wz.step1.passed === true, '删除期初单后 step1 应通过且仍为首次日结')

  // ===== 用例 13b：首次日结未勾选期初确认 → 拒 =====
  await expectFail(() => post('/finance/day-close/close',
    { date: D, openingConfirmed: false }), '期初', '首次日结未勾选期初确认')

  // ===== 用例 14：资金备注/知晓项随单提交（不阻断），执行日结 =====
  const closeRes = await post('/finance/day-close/close', {
    date: D,
    openingConfirmed: true,
    acknowledgeHanging: true,
    acknowledgeAnomaly: true,
    cashCounts: {},
    fundRemark: '验收脚本：资金情况无异常',
  })
  assert(closeRes.idempotent === false, '首次日结返回 idempotent=false')
  assert(closeRes.closeNo === RJ_NO, `日结单号应为 ${RJ_NO}，实际 ${closeRes.closeNo}`)

  // ===== 用例 3：封单后反审核已结日期单据 → 拒（守卫全链中文案） =====
  const rev = await postRaw('/inventory/other-inbound/reverse-audit', { bizId: inb.inboundNo })
  assert(rev.ok === false, '封单后反审核应被拒')
  assert(String(rev.message).includes('已日结封单'), `应提示已日结封单，实际：${rev.message}`)

  // ===== 用例 6：重复日结幂等；未来/跳日被拒 =====
  const again = await post('/finance/day-close/close',
    { date: D, openingConfirmed: true, acknowledgeHanging: true, acknowledgeAnomaly: true })
  assert(again.idempotent === true && again.closeNo === RJ_NO, '重复日结应幂等回显同单号')
  // 已结 D 后改结 D-1（非未来日期）→ 连续性要求只能结 D+1；未来日期另有硬拦
  await expectFail(() => post('/finance/day-close/close',
    { date: Dm1, openingConfirmed: true }), '必须连续日结', '跳日日结（结昨日）')

  // ===== 用例 17：RJ 单详情（合计 + 三套定版数组 + 备注） =====
  const ticket = await post('/finance/day-close/ticket', { date: D })
  assert(ticket.closeNo === RJ_NO, 'ticket 应返回 RJ 单')
  assert(Array.isArray(ticket.arDaily) && Array.isArray(ticket.apDaily) && Array.isArray(ticket.fundDaily),
    'ticket 应含 arDaily/apDaily/fundDaily 数组')
  assert(ticket.fundRemark === '验收脚本：资金情况无异常', '资金情况说明应随 RJ 单持久化')
  assert(ticket.dwsBalanced === 'Y' && ticket.tieBalanced === 'Y', '空库日结 DWS/滚存应平衡')
  for (const k of ['salesSignedAmount', 'receiptAmount', 'purchaseAmount', 'paymentAmount', 'stockInAmount', 'stockOutAmount']) {
    assert(typeof num(ticket[k]) === 'number', `ticket 合计缺 ${k}`)
  }

  // 三张定版台账分页可取
  const arP = await post('/finance/day-close/ar-daily/page', { pageNo: 1, pageSize: 10, filters: {} })
  const apP = await post('/finance/day-close/ap-daily/page', { pageNo: 1, pageSize: 10, filters: {} })
  const fundP = await post('/finance/day-close/fund-daily/page', { pageNo: 1, pageSize: 10, filters: {} })
  assert('records' in arP && 'total' in arP, '应收定版分页结构应为 records/total')
  assert('records' in apP && 'records' in fundP, '应付/资金定版分页应可取')

  // ===== V121 商品收发存定版（第四类定版，日+商品+仓） =====
  assert(Array.isArray(ticket.goodsDaily), 'ticket 应含 goodsDaily 数组')
  assert(ticket.goodsSummary && num(ticket.goodsSummary.rowCount) >= 1, 'goodsSummary 应有汇总行')
  const g = ticket.goodsDaily.find(r => r.goodsCode === 'SP001' && r.warehouse === '总仓')
  assert(g, '商品定版应有 SP001/总仓 行')
  assert(Math.abs(num(g.openingQty)) < 1e-6, `首日期初数量应为 0，实际 ${g.openingQty}`)
  assert(Math.abs(num(g.otherInQty) - 1) < 1e-6, `其他入库数量应为 1，实际 ${g.otherInQty}`)
  assert(Math.abs(num(g.inQty) - 1) < 1e-6, `收入合计应为 1，实际 ${g.inQty}`)
  assert(Math.abs(num(g.outQty)) < 1e-6, `发出合计应为 0，实际 ${g.outQty}`)
  assert(Math.abs(num(g.endingQty) - 1) < 1e-6, `期末数量应为 1，实际 ${g.endingQty}`)
  assert(Math.abs(num(g.qtyDiff)) < 1e-6, `数量勾稽差异应为 0，实际 ${g.qtyDiff}`)
  assert(Math.abs(num(g.amountDiff)) < 0.01, `金额勾稽差异应为 0，实际 ${g.amountDiff}`)
  assert(g.tieFlag === 'Y', 'tieFlag 应为 Y')
  assert(Math.abs(num(g.inAmount) - 10) < 0.01, `收入金额应为 10，实际 ${g.inAmount}`)
  assert(Math.abs(num(g.endingAmount) - 10) < 0.01, `期末金额应为 10，实际 ${g.endingAmount}`)
  assert(g.negativeFlag === 'N', '期末不应负库存')
  assert(Math.abs(num(ticket.goodsSummary.inAmount) - num(ticket.stockInAmount)) < 0.01,
    `商品定版收入金额合计 ${ticket.goodsSummary.inAmount} 应=主表入库成本 ${ticket.stockInAmount}`)
  assert(Math.abs(num(ticket.goodsSummary.outAmount) - num(ticket.stockOutAmount)) < 0.01,
    '商品定版发出金额合计应=主表出库成本')
  // 档案快照列随定版冻结（SP001 已建档：名称/规格/条码/基本单位取自 rpt_dim_goods）
  assert(g.goodsName === '农夫山泉500ml*24', `商品名称应为档案快照，实际 ${g.goodsName}`)
  assert(g.baseUnit === '瓶', `基本单位应为档案快照「瓶」，实际 ${g.baseUnit}`)
  assert(g.spec === '500ml*24' && g.barcode === '6901234500011',
    `规格/条码应为档案快照，实际 ${g.spec}/${g.barcode}`)
  // 商品定版台账区间滚算（单日）
  const goodsP = await post('/finance/day-close/goods-daily/page',
    { pageNo: 1, pageSize: 50, filters: { from: D, to: D } })
  assert('records' in goodsP && goodsP.total >= 1, '商品定版分页应可取且 ≥1 行')
  const gp = goodsP.records.find(r => r.goodsCode === 'SP001' && r.warehouse === '总仓')
  assert(gp && day(gp.fromDate) === D && day(gp.toDate) === D, '滚算行应带区间首日/末日')
  assert(Math.abs(num(gp.inQty) - 1) < 1e-6 && Math.abs(num(gp.endingQty) - 1) < 1e-6,
    '滚算行收入/期末数量应正确')
  assert(gp.brandName === '农夫山泉' && gp.categoryName === '饮用水' && gp.storageProperty === '常温',
    `滚算行应带档案快照品牌/分类/温区，实际 ${gp.brandName}/${gp.categoryName}/${gp.storageProperty}`)
  // 关键字（条码/名称/编码）
  const goodsKw = await post('/finance/day-close/goods-daily/page',
    { pageNo: 1, pageSize: 50, filters: { from: D, to: D, keyword: 'SP001' } })
  assert(goodsKw.records.every(r => String(r.goodsCode).includes('SP001')), '关键字过滤应只回匹配商品')

  // ===== 用例 7：反日结校验 + 恢复链路 =====
  await expectFail(() => post('/finance/day-close/reopen', { date: D, reason: '' }),
    '原因', '反日结原因空')
  await expectFail(() => post('/finance/day-close/reopen', { date: D, reason: 'x' }),
    '原因', '反日结原因 1 字')
  const ro = await post('/finance/day-close/reopen', { date: D, reason: '验收脚本：验证守卫后重新日结' })
  assert(ro.reopened === true, '单日反日结应成功')
  // 定版随之删除
  const fundAfter = await post('/finance/day-close/fund-daily/page', { pageNo: 1, pageSize: 10, filters: { from: D, to: D } })
  assert(fundAfter.total === 0, '反日结后当日资金定版应删除')
  const goodsAfter = await post('/finance/day-close/goods-daily/page', { pageNo: 1, pageSize: 10, filters: { from: D, to: D } })
  assert(goodsAfter.total === 0, '反日结后当日商品收发存定版应物理删除')
  // 守卫放行：反审核成功，再次审核（回填今天），再日结
  await post('/inventory/other-inbound/reverse-audit', { bizId: inb.inboundNo })
  await post('/inventory/other-inbound/audit', { bizId: inb.inboundNo })
  const reclose = await post('/finance/day-close/close', {
    date: D, openingConfirmed: true, acknowledgeHanging: true, acknowledgeAnomaly: true,
  })
  assert(reclose.closeNo === RJ_NO && reclose.idempotent === false, '反结后重新日结应再次成功')
  // 商品定版恢复，恒等式重新冻结为平衡
  const ticket2 = await post('/finance/day-close/ticket', { date: D })
  const g2 = ticket2.goodsDaily.find(r => r.goodsCode === 'SP001' && r.warehouse === '总仓')
  assert(g2 && Math.abs(num(g2.endingQty) - 1) < 1e-6 && g2.tieFlag === 'Y',
    '重结后 SP001 商品定版应恢复且勾稽平衡')
  assert(g2.goodsName && g2.baseUnit, '重结后商品档案快照（名称/基本单位）应完整（维度已随结账刷新）')

  // 日志链：CLOSE 成功 ≥2 次、REOPEN 1 次，且按时间倒序
  const logs = await post('/finance/day-close/log-page',
    { pageNo: 1, pageSize: 50, filters: { from: D, to: D } })
  const closes = logs.records.filter(r => r.action === 'CLOSE' && r.result === 'SUCCESS')
  const reopens = logs.records.filter(r => r.action === 'REOPEN' && r.result === 'SUCCESS')
  assert(closes.length >= 2, `CLOSE 成功日志应 ≥2，实际 ${closes.length}`)
  assert(reopens.length >= 1, `REOPEN 成功日志应 ≥1（含重跑重置批次），实际 ${reopens.length}`)
  assert(reopens[0].reason === '验收脚本：验证守卫后重新日结', '最新 REOPEN 日志应带本次原因')
  // 时间链：CLOSE/REOPEN/CLOSE 交错且全部按 operate_time 留痕
  assert(logs.records.every(r => r.operateTime), '每条日志应有 operateTime')
  assert(logs.records.some(r => r.action === 'CLOSE') && logs.records.some(r => r.action === 'REOPEN'),
    '日志应同时含 CLOSE/REOPEN 时间链')

  // ===== 用例 7b：批量反结 + 重结（单天批次，days=1） =====
  const batch = await post('/finance/day-close/reopen-batch', { toDate: D, reason: '验收脚本：批量反结验证' })
  assert(batch.days === 1 && batch.batchNo, '批量反结应 days=1 且返回批次号')
  const blog = (await post('/finance/day-close/log-page',
    { pageNo: 1, pageSize: 5, filters: { action: 'REOPEN' } })).records[0]
  assert(blog.batchNo === batch.batchNo, 'REOPEN 日志应记录同批次号')
  await post('/inventory/other-inbound/reverse-audit', { bizId: inb.inboundNo })
  await post('/inventory/other-inbound/audit', { bizId: inb.inboundNo })
  await post('/finance/day-close/close', {
    date: D, openingConfirmed: true, acknowledgeHanging: true, acknowledgeAnomaly: true,
  })

  // ===== 用例 18：工作台红色提醒结构 =====
  const reminder = await post('/finance/day-close/reminder', {})
  for (const k of ['show', 'late', 'yesterday', 'lastClosed', 'lastFail', 'lateHour']) {
    assert(k in reminder, `提醒接口缺字段 ${k}`)
  }
  assert(typeof reminder.show === 'boolean', 'show 应为布尔')
  assert(reminder.yesterday !== D, 'yesterday 应为昨天')

  // ===== 用例 9：动态定时任务管理 =====
  const tasks = await post('/system/schedule-task/page', { pageNo: 1, pageSize: 100, filters: {} })
  const dayTask = (tasks.records || []).find(t => t.taskCode === 'BIZ_DAY_CLOSE')
  assert(dayTask, '任务列表应含 BIZ_DAY_CLOSE')
  assert(dayTask.handlerBean, '任务应注册 handler Bean（白名单）')

  // 改 cron：合法 → 返回未来 5 次；非法 → 拒
  const upd = await post('/system/schedule-task/update',
    { taskCode: 'BIZ_DAY_CLOSE', cronExpr: '0 50 2 * * ?', remark: '验收脚本改计划' })
  assert(Array.isArray(upd.nextFireTimes) && upd.nextFireTimes.length === 5,
    `合法 cron 应返回未来 5 次执行时间，实际 ${JSON.stringify(upd.nextFireTimes)}`)
  assert(upd.nextFireTimes.every(t => String(t).slice(11, 19) === '02:50:00'),
    '未来 5 次均应为 02:50:00')
  await expectFail(() => post('/system/schedule-task/update',
    { taskCode: 'BIZ_DAY_CLOSE', cronExpr: 'not a cron' }), 'cron', '非法 cron')
  const preview = await post('/system/schedule-task/next5', { cronExpr: '0 0 3 * * ?' })
  assert(preview.length === 5 && String(preview[0]).slice(11, 19) === '03:00:00', 'next5 预览应正确')

  // 停用 → 启用
  const off = await post('/system/schedule-task/toggle', { taskCode: 'BIZ_DAY_CLOSE', enabled: false })
  assert(off.enabled === 'N', '停用后 enabled 应为 N')
  const on = await post('/system/schedule-task/toggle', { taskCode: 'BIZ_DAY_CLOSE', enabled: true })
  assert(on.enabled === 'Y', '启用后 enabled 应为 Y')

  // 立即执行：今天已结 → 无待日结日期，方式=手动仍写执行日志
  const run = await post('/system/schedule-task/run-once', { taskCode: 'BIZ_DAY_CLOSE' })
  assert(String(run.message).includes('无待日结日期'), `已结时手动执行应提示无待结日期，实际：${run.message}`)
  const tlogs = await post('/system/schedule-task/log-page',
    { pageNo: 1, pageSize: 10, filters: { taskCode: 'BIZ_DAY_CLOSE' } })
  assert((tlogs.total || 0) >= 1, '执行日志应至少 1 条')
  assert(tlogs.records[0].triggerType === 'MANUAL', '首条日志触发方式应为 MANUAL')

  // cron 恢复默认（02:40），避免冒烟库残留影响下次
  await post('/system/schedule-task/update',
    { taskCode: 'BIZ_DAY_CLOSE', cronExpr: '0 40 2 * * ?', remark: '验收脚本恢复默认 02:40' })

  console.log('✅ v1-biz-day-close 验收全部通过：')
  console.log('   菜单功能点 / 首次期初阻断 / 审核回填 / 日结封单守卫 / 幂等跳日 / RJ 单与定版台账')
  console.log('   / 反日结校验留痕与批量批次 / 提醒结构 / 定时任务 cron·启停·立即执行·日志')
  console.log('')
  console.log('📋 手工验收项（需完整单据链或 SQL 夹具，见方案 §10）：')
  console.log('   2 跨阶挂账三类清单与挂起天数；4 采购发票不封；5 四定版表签收/拒收/红负/核销金额一致；')
  console.log('   8 月结未日结硬拦（P0186=N 仅警告）；10 自动补结跨两天+中途不平止于失败日；')
  console.log('   11 无 audit/unaudit/execute 功能点直调 403、P0184=N 守卫放行；12 SQL 直改库存致第5步差异；')
  console.log('   15 账户改名后历史快照名不变；16 盘点日不回填、收付款/费用记账日期不回填、02:30 截止。')
}

main().catch(e => { console.error('❌ 验收失败：', e.message); process.exit(1) })
