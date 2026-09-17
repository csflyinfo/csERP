/**
 * PRD-36 M4（V131）：供应商账户收尾 浏览器真实交互验收
 * （Edge headless + CDP，node 原生 WebSocket / fetch，无第三方依赖；克隆 prd36-m3-ui-verify.js 范式）。
 *
 * 前置（隔离环境，勿连受控 8080/5173）：
 *   1) 8082 后端使用 verify-prd36-m1 验证库，已跑通 prd36-m4-api-e2e.mjs（116 断言全绿后的库态）：
 *      AP 期初批 QC202609170002 POSTED（S-M4-01 AP100+PP40、S-M4-02 PP60、S-M4-03 AP70）；
 *      补录批 QC202609170003 POSTED 生效中（S-M4-01+25、S-M4-NEG+70、S-M4-B2+15，共 110）、
 *               QC202609170004 REVERSED（反建账原因「M4验证：撤销第二批」，留 B2 10 元暂存行）；
 *      账户余额：S-M4-01 ap100/pp65、S-M4-02 pp60、S-M4-03 ap70、S-M4-NEG ap-30/pp70、S-M4-B2 pp15；
 *      GL 已启用 202609；当日（2026-09-17）日结已 reopen（向导=未封账态）；
 *      字段权限角色 ROLE_M4_FIELD / 用户 m4field（M4test@123，三余额字段脱敏为 null）。
 *   2) vite 跑 5174 代理 8082：VITE_API_TARGET=http://localhost:8082 npm --prefix frontend run dev -- --port 5174 --strictPort。
 *
 * 运行：node development/06-testing/scripts/prd36-m4-ui-verify.js
 *
 * 动作（0 单据提交；唯一状态翻转由脚本在 node 侧直接调 API 完成且必复原：
 *   为出「已日结」态证据，场景 04 后 POST /finance/day-close/close（复刻 E2E 载荷），
 *   场景 05 打印取数后在 finally 中 POST /finance/day-close/reopen 复原，失败抛错）：
 *   01 供应商应付期初：九列/建账双合计/生成 AP 单号与 SAF 流水回写/「期初预付补录」入口
 *   02 补录弹窗：规则 banner/暂存 10 元行/批号历史 生效中+已反建账/负应付黄名单/各户累计/反建账内层弹窗
 *   03 业财对账：五组顺序、预付/厂家费用「提示性对账」徽章与说明、硬平衡总标志
 *   04 日结向导（未封账态）：应付滚存硬勾稽 0+190-0=190、1123/1221/2202 分列、NEG 重分类与双计红名单
 *   04b 日结（已封账态）+ 04c 定版台账 AP：三新列与 NEG 重分类值、纯预付户 billCount=0 仍出行
 *   05 RJ 打印页：AP 台账 10 列三新列 + 分列注释 + NEG 数据行
 *   06 供应商停用/删除五守卫拒绝（S-M4-01 多原因中文文案）：06a admin 档案应付余额取账户值；
 *      06b 真实停用入口=编辑抽屉状态下拉改停用→保存被拒 alert（/supplier/update 同守五守卫）；
 *      06c 行内删除→确认弹窗→/supplier/delete toast 拒绝；06d m4field 登录三余额脱敏
 * 截图落 development/06-testing/evidence/prd36-m4-*.png。
 */
const { spawn } = require('child_process')
const fs = require('fs')
const path = require('path')

const EDGE = process.env.EDGE_BIN || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = path.join(__dirname, '..', '..', '..', 'data', 'edge-ui-profile-prd36')
const SHOT_DIR = path.join(__dirname, '..', 'evidence')
const BASE = process.env.UI_BASE || 'http://localhost:5174'
const API = process.env.API_BASE || 'http://localhost:8082/api'
const CDP_PORT = Number(process.env.CDP_PORT || 9224)
const TODAY = '2026-09-17'
fs.mkdirSync(SHOT_DIR, { recursive: true })
fs.rmSync(PROFILE, { recursive: true, force: true })

const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[prd36-m4-ui]', ...a)
const assert = (cond, msg) => { if (!cond) throw new Error(msg) }

// ---------- node 侧 API（仅用于可逆的日结/反日结转态，复刻 E2E 载荷） ----------
let _token = null
async function apiLogin(username = 'admin', password = 'admin123') {
  const res = await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const j = await res.json()
  _token = j.data?.token || j.data?.accessToken
  assert(_token, '登录失败：' + JSON.stringify(j).slice(0, 200))
  return _token
}
async function apiPost(p, body) {
  const res = await fetch(API + p, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + _token },
    body: JSON.stringify(body || {}),
  })
  return res.json()
}

let edge
async function main() {
  edge = spawn(EDGE, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${PROFILE}`,
    '--remote-allow-origins=*', '--window-size=1680,1050', 'about:blank',
  ], { stdio: 'ignore' })

  async function getWsUrl() {
    for (let i = 0; i < 40; i++) {
      try {
        const res = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)
        const list = await res.json()
        const page = list.find(t => t.type === 'page')
        if (page?.webSocketDebuggerUrl) return page.webSocketDebuggerUrl
      } catch {}
      await sleep(500)
    }
    throw new Error('CDP endpoint not ready')
  }
  const ws = new WebSocket(await getWsUrl())
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j })
  let _id = 0
  const waits = new Map()
  let lastDialogMsg = null
  ws.onmessage = async e => {
    const m = JSON.parse(e.data)
    if (m.method === 'Page.javascriptDialogOpening') {
      lastDialogMsg = m.params.message
      await cdp('Page.handleJavaScriptDialog', { accept: true })
    }
    if (m.id && waits.has(m.id)) { waits.get(m.id)(m); waits.delete(m.id) }
  }
  function cdp(method, params = {}) {
    const id = ++_id
    return new Promise(resolve => { waits.set(id, resolve); ws.send(JSON.stringify({ id, method, params })) })
  }

  await cdp('Page.enable')
  await cdp('Runtime.enable')
  // 打印页 onMounted 后 400ms 自动 window.print()，headless 下 stub 掉避免卡住
  await cdp('Page.addScriptToEvaluateOnNewDocument', {
    source: 'window.print=function(){};',
  })

  async function ev(expression) {
    const r = await cdp('Runtime.evaluate', {
      expression: `(async()=>{ ${expression} })()`,
      awaitPromise: true, returnByValue: true, userGesture: true,
    })
    if (r.result?.exceptionDetails) {
      throw new Error('PAGE ERROR: ' + JSON.stringify(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text))
    }
    if (r.result?.result?.subtype === 'error') throw new Error('PAGE ERROR: ' + r.result.result.description)
    return r.result?.result?.value
  }

  const HELPERS = `window.__T = {
    sleep: m => new Promise(r=>setTimeout(r,m)),
    async waitFor(fn, ms=10000){ const t=Date.now(); let last; while(Date.now()-t<ms){ try{ const v=fn(); if(v) return v }catch(e){ last=e } await this.sleep(100) } throw new Error('waitFor超时: '+(last&&last.message||'')+' @ '+(fn.toString().slice(0,140))) },
    vis(el){ if(!el) return false; const r=el.getBoundingClientRect(); return r.width>0&&r.height>0 },
    findBtn(text, root){ return [...(root||document).querySelectorAll('button,a')].find(b=>this.vis(b)&&b.textContent.replace(/\\s+/g,'').includes(text)) },
    async clickBtn(text, root){ const b=await this.waitFor(()=>this.findBtn(text, root)); b.click() },
    rowByText(sel, txt){ return [...document.querySelectorAll(sel+' tbody tr')].find(tr=>tr.innerText.includes(txt)) || null },
    visibleMask(sel){ return [...document.querySelectorAll(sel)].find(m=>getComputedStyle(m).display!=='none' && this.vis(m)) || null },
    cell(scope, row, title){ const heads=[...document.querySelectorAll(scope+' thead th')].map(th=>th.textContent.trim());
      const i=heads.findIndex(t=>t.includes(title)); if(i<0) return null; const tds=row.querySelectorAll('td'); return tds[i] ? tds[i].textContent.trim() : null },
  }`
  async function install() { await ev(HELPERS) }
  async function shot(name) {
    const r = await cdp('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.result.data, 'base64'))
    log('截图', name + '.png')
  }
  async function goto(p) {
    await cdp('Page.navigate', { url: BASE + p })
    await sleep(500)
    await install()
  }
  const setNative = (elExpr, v) => `
    { const el=${elExpr}; el.value=${JSON.stringify(v)};
      el.dispatchEvent(new Event('change',{bubbles:true})); el.dispatchEvent(new Event('input',{bubbles:true})); }`

  // ---------- 1. 登录 ----------
  log('打开登录页（admin）')
  await cdp('Page.navigate', { url: BASE + '/login' })
  await sleep(400)
  await install()
  await ev(`await __T.waitFor(()=>document.querySelector('input[placeholder="请输入账号"]'))`)
  await ev(`
    const set=(el,v)=>{el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}))}
    set(document.querySelector('input[placeholder="请输入账号"]'),'admin')
    set(document.querySelector('input[placeholder="请输入密码"]'),'admin123')
    await __T.sleep(100)
    document.querySelector('.login-submit').click()
  `)
  await ev(`await __T.waitFor(()=>!location.pathname.startsWith('/login'),12000)`)
  log('登录成功', await ev(`return location.pathname`))

  // ---------- 2. 供应商应付期初 ----------
  log('01 InitAp：九列 / 建账双合计 / QCAP 批号列 / 补录入口')
  await goto('/finance/init-ap')
  await ev(`await __T.waitFor(()=>document.querySelector('.module-body table.data thead')
      && !document.querySelector('.module-body').innerText.includes('加载中'))`)
  const initText = await ev(`return document.querySelector('.module-body').innerText`)
  for (const h of ['供应商编码', '供应商名称', '原单据号', '原单据日期', '应付金额', '期初预付金额',
                   '备注', '应付单号', '预付流水号']) {
    assert(initText.includes(h), '期初页缺列：' + h)
  }
  // 已建账态双合计（QC202609170002：AP 170 / PP 100，3 行）
  assert(/建账行数\s*3/.test(initText.replace(/\s+/g, ' ')), '缺建账行数 3：' + initText.slice(0, 300))
  assert(initText.includes('￥170.00'), '应付合计应为 ￥170.00')
  assert(initText.includes('￥100.00'), '期初预付合计应为 ￥100.00')
  assert(initText.includes('QC202609170002'), '已建账批号应展示')
  // 三行暂存回写：应付单号列=生成的 AP 单号（AP20260917NNNN，源单 QCAP-批号-序号在 AP 台账内），预付流水号 SAF
  const apRows = await ev(`return [...document.querySelectorAll('.module-body table.data tbody tr')].map(tr=>tr.innerText.replace(/\\s+/g,' ').trim())`)
  log('  期初行：', JSON.stringify(apRows))
  assert(apRows.length === 3, '应展示 3 行建账回退行：' + apRows.length)
  assert(apRows.some(t => t.includes('S-M4-01') && /AP202609170001(?!\d)/.test(t) && /SAF/.test(t)), 'S-M4-01 行应有 AP 单号 + SAF 流水：' + apRows.join(' | '))
  // 注：行尾另有导入任务号 AP20260917100914274（17 位），AP 单号为 AP+日期+4 位序列，用 12 位数字+负向边界精确区分
  assert(apRows.some(t => t.includes('S-M4-02') && /SAF/.test(t) && !/AP\d{12}(?!\d)/.test(t)), '纯预付户 S-M4-02 无应付有流水：' + apRows.join(' | '))
  assert(apRows.some(t => t.includes('S-M4-03') && /AP202609170002(?!\d)/.test(t) && !/SAF/.test(t)), '纯应付户 S-M4-03 有 AP 单号无流水：' + apRows.join(' | '))
  // 补录入口存在
  assert(await ev(`return !!__T.findBtn('期初预付补录')`), '缺「期初预付补录」按钮')
  log('  九列/双合计 170+100/AP 单号·SAF 回写/补录入口 均正确')
  await shot('prd36-m4-01-init-ap-posted')

  // ---------- 3. 期初预付补录弹窗 ----------
  log('02 补录弹窗：规则条/暂存行/批号历史/负应付黄名单/累计/反建账弹窗')
  await ev(`__T.findBtn('期初预付补录').click()`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.modal-mask') && __T.visibleMask('.modal-mask').querySelector('.modal.w960'))`)
  await ev(`await __T.waitFor(()=>{ const m=document.querySelector('.modal.w960'); return m && m.innerText.includes('QC202609170003') })`)
  const modal = () => `document.querySelector('.modal-mask .modal.w960')`
  const mText = await ev(`return ${modal()}.innerText`)
  // 三态 banner：未封账 + GL 已启用无阻断 → 信息规则条
  assert(mText.includes('补录规则') && mText.includes('可分多批过账'), '缺补录规则 banner：' + mText.slice(0, 200))
  assert(!mText.includes('已做业务日结封账'), '当日未封账不应显示封账条')
  // 暂存合计：有效行 1 / ￥10.00（B2 反建账回退行）
  assert(/有效行\s*1/.test(mText.replace(/\s+/g, ' ')), '暂存有效行应为 1')
  assert(mText.includes('￥10.00'), '暂存预付合计应为 ￥10.00')
  // 批号历史：0003 生效中、0004 已反建账 + 反建账原因
  assert(mText.includes('QC202609170003') && mText.includes('生效中'), '0003 应生效中')
  assert(mText.includes('QC202609170004') && mText.includes('已反建账'), '0004 应已反建账')
  assert(mText.includes('M4验证：撤销第二批'), '缺反建账原因留痕')
  // 负应付黄名单
  assert(mText.includes('M4负应付户') && mText.includes('￥-30.00'), '缺负应付黄名单 NEG -30：' + mText.slice(-400))
  assert(mText.includes('不要'), '黄名单应有不要重复补录提示')
  // 反建账链接只在批号历史表的生效批上（暂存行另有「删除」，需按表头区分）
  const batchTable = () => `[...${modal()}.querySelectorAll('table.data')].find(t=>t.querySelector('th')&&[...t.querySelectorAll('th')].some(h=>h.textContent.trim()==='批号'))`
  const revLinks = await ev(`return [...${batchTable()}.querySelectorAll('a.link.danger')].map(a=>a.textContent.trim())`)
  assert(revLinks.length === 1 && revLinks[0] === '反建账', '仅生效批应有 1 个反建账入口：' + JSON.stringify(revLinks))
  // 各供应商累计 <details>
  await ev(`${modal()}.querySelector('details.opening-box').open = true`)
  await sleep(150)
  const openBox = await ev(`return ${modal()}.querySelector('details.opening-box').innerText`)
  assert(openBox.includes('共 4 户'), '期初预付累计应 4 户：' + openBox.slice(0, 120))
  for (const t of ['S-M4-01', '65.00', 'S-M4-02', 'S-M4-B2', '15.00', 'S-M4-NEG', '70.00']) {
    assert(openBox.includes(t), '累计明细缺：' + t)
  }
  await shot('prd36-m4-02a-supplement-panel')
  // 反建账内层弹窗（打开后只看不动，确认/取消都不点）
  await ev(`[...${batchTable()}.querySelectorAll('a.link.danger')].find(a=>a.textContent.trim()==='反建账').click()`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.modal-mask.inner'))`)
  const inner = await ev(`return document.querySelector('.modal-mask.inner .modal.r520').innerText`)
  assert(inner.includes('整批反建账') && inner.includes('QC202609170003'), '反建账弹窗应带默认生效批号：' + inner.slice(0, 150))
  assert(inner.includes('反建账原因（必填，留痕）'), '缺反建账原因必填项')
  assert(inner.includes('已被预付核销、退款或厂家费用划转占用时会被拒绝'), '缺占用守卫提示')
  const revOpts = await ev(`return [...document.querySelector('.modal-mask.inner select').options].map(o=>o.textContent.trim())`)
  log('  可反建账批号选项：', JSON.stringify(revOpts))
  assert(revOpts.length === 1 && revOpts[0].includes('QC202609170003'), '下拉应仅 0003 一生效批：' + JSON.stringify(revOpts))
  await shot('prd36-m4-02b-reverse-dialog')
  // 关内层 → 关外层（绝不提交）
  await ev(`document.querySelector('.modal-mask.inner .modal-h .x').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.modal-mask.inner'))`)
  await ev(`${modal()}.querySelector('.modal-h .x').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.modal-mask'))`)
  log('  规则条/暂存 10/批号 生效中+已反建账/黄名单/4 户累计/反建账弹窗 均正确（未提交）')

  // ---------- 4. 业财对账 ----------
  log('03 Reconcile：五组顺序 / 提示性对账徽章 / 总标志')
  await goto('/gl-reconcile')
  // SPA 内可能挂载多个隐藏抽屉的空 select，按 option 值精确定位会计期间选择器并显式选 202609
  const PERIOD = TODAY.replace(/-/g, '').slice(0, 6)
  await ev(`await __T.waitFor(()=>[...document.querySelectorAll('select')].some(s=>[...s.options].some(o=>o.value==='${PERIOD}')),20000)`)
  await ev(`
    const sel=[...document.querySelectorAll('select')].find(s=>[...s.options].some(o=>o.value==='${PERIOD}'));
    sel.value='${PERIOD}'; sel.dispatchEvent(new Event('change',{bubbles:true}));`)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.recon-card').length===5
      && document.querySelector('.page-ops .tag'),12000)`)
  const cardNames = await ev(`return [...document.querySelectorAll('.recon-card .recon-h b')].map(b=>b.textContent.trim())`)
  log('  对账组顺序：', JSON.stringify(cardNames))
  assert(cardNames.length === 5, '应五组对账：' + cardNames.length)
  assert(/应收账款（1122）/.test(cardNames[0]), '第 1 组应为应收：' + cardNames[0])
  assert(/应付账款（2202）/.test(cardNames[1]), '第 2 组应为应付：' + cardNames[1])
  assert(/预付账款（1123）/.test(cardNames[2]), '第 3 组应为预付：' + cardNames[2])
  assert(/厂家费用/.test(cardNames[3]) && /1221/.test(cardNames[3]), '第 4 组应为厂家费用 1221：' + cardNames[3])
  assert(/资金/.test(cardNames[4]), '第 5 组应为资金：' + cardNames[4])
  // 提示性徽章恰好 2 枚（预付 + 厂家费用）
  const advCards = await ev(`return [...document.querySelectorAll('.recon-card')].filter(c=>c.querySelector('.tag-advisory')).map(c=>c.querySelector('.recon-h b').textContent.trim())`)
  assert(advCards.length === 2 && /预付/.test(advCards[0]) && /厂家费用/.test(advCards[1]),
    '提示性对账应恰为预付/厂家费用两组：' + JSON.stringify(advCards))
  // advisory 说明文案（预付组 note 为本轮 BUG#2 修复点）
  const notes = await ev(`return [...document.querySelectorAll('.advisory-note')].map(n=>n.textContent.trim())`)
  assert(notes.some(t => t.includes('总账手工凭证') && t.includes('不阻断结账')), '预付组缺 advisory 说明：' + JSON.stringify(notes))
  // 顶部总标志存在（验证库有历史测试数据，硬平衡红/绿只校验徽章与口径文案存在）
  const topTag = await ev(`return document.querySelector('.page-ops .tag').textContent.trim()`)
  assert(/硬平衡/.test(topTag), '顶部应有硬平衡总标志：' + topTag)
  log('  五组顺序正确；预付/厂家费用提示性徽章×2 + 说明齐备；总标志：', topTag)
  await shot('prd36-m4-03-reconcile')

  // ---------- 5. 日结向导（未封账态） ----------
  log('04 DayClose 向导：AP 硬勾稽 + 1123/1221/2202 分列 + 重分类/双计提示')
  await goto('/finance/day-close')
  // 默认日期=昨天，改为 2026-09-17 后点查询（查询条件只在点查询时生效）
  await ev(`await __T.waitFor(()=>document.querySelector('input[type=date]'))`)
  await ev(setNative(`document.querySelector('.tabbar') ? document.querySelectorAll('input[type=date]')[0] : document.querySelector('input[type=date]')`, TODAY))
  await ev(`__T.findBtn('查询').click()`)
  await ev(`await __T.waitFor(()=>document.querySelector('.module-body').innerText.includes('供应商应付滚存')
      && document.querySelector('.module-body').innerText.includes('210.00'),12000)`)
  const wiz = await ev(`return document.querySelector('.module-body').innerText`)
  assert(wiz.includes('当前封单日：（无）'), 'reopen 后应无封单日：' + wiz.match(/当前封单日[^\n]*/)?.[0])
  // AP 滚存：0 + 190 - 0 = 190，差 0
  const apBlock = await ev(`return [...document.querySelectorAll('.tie-block')].find(b=>b.innerText.includes('供应商应付滚存')).innerText`)
  assert(apBlock.includes('190.00') && apBlock.includes('0.00'), 'AP 滚存应有 190 与 0.00：' + apBlock.slice(0, 200))
  // 分列三口径
  assert(/账户预付余额（1123）合计[\s\S]*?210\.00/.test(apBlock), '1123 账户预付合计应为 210.00：' + apBlock.slice(-500))
  assert(/费用余额（1221[\s\S]*?）合计[\s\S]*?0\.00/.test(apBlock), '1221 费用余额合计应为 0.00')
  assert(/2202 负应付重分类兜底合计[\s\S]*?30\.00/.test(apBlock), '2202 重分类合计应为 30.00')
  assert(apBlock.includes('两口径不要求相等'), '缺分列口径注释')
  // 黄提示：NEG 负应付 30
  const reclassRows = await ev(`return [...document.querySelectorAll('.reclass-row:not(.double-count)')].map(r=>r.textContent.replace(/\\s+/g,' ').trim())`)
  assert(reclassRows.some(t => t.includes('M4负应付户') && t.includes('30.00')), '缺 NEG 重分类黄行：' + JSON.stringify(reclassRows))
  // 双计红名单：NEG 30 + 70
  const doubles = await ev(`return [...document.querySelectorAll('.reclass-row.double-count')].map(r=>r.textContent.replace(/\\s+/g,' ').trim())`)
  assert(doubles.length === 1, '双计红名单应 1 户：' + doubles.length)
  assert(/M4负应付户/.test(doubles[0]) && /30\.00/.test(doubles[0]) && /70\.00/.test(doubles[0])
    && /重复挂账/.test(doubles[0]), '双计红名单文案/数值不对：' + doubles[0])
  log('  AP 硬勾稽 0+190-0=190；1123=210 / 1221=0 / 2202 重分类=30；NEG 黄行+双计红行（30/70）正确')
  await shot('prd36-m4-04-wizard-ap-tie')

  // ---------- 6. node 侧转已封账态（API 日结，必复原） ----------
  log('04b API 转已封账态（复刻 E2E close 载荷；finally 必 reopen 复原）')
  await apiLogin()
  const closeR = await apiPost('/finance/day-close/close', {
    date: TODAY, openingConfirmed: true, acknowledgeHanging: true,
    acknowledgeAnomaly: true, cashCounts: {},
  })
  assert(closeR.code === '0', '日结转态失败：' + JSON.stringify(closeR).slice(0, 300))
  log('  日结成功：', closeR.data?.closeNo || JSON.stringify(closeR.data).slice(0, 120))

  // 向导已封账态
  await ev(`__T.findBtn('查询').click()`)
  await ev(`await __T.waitFor(()=>document.querySelector('.module-body').innerText.includes('该日期已日结'),12000)`)
  const closedWiz = await ev(`return document.querySelector('.module-body').innerText`)
  assert(new RegExp(`当前封单日：${TODAY}`).test(closedWiz), '已封账态封单日应为当日：' + closedWiz.match(/当前封单日[^\n]*/)?.[0])
  assert(await ev(`return !!__T.findBtn('打印RJ单')`), '已封账态应有打印 RJ 单入口')
  assert(await ev(`return !!__T.findBtn('反日结')`), '已封账态应有反日结入口')
  log('  向导已封账态正确（打印/反日结入口齐备）')
  await shot('prd36-m4-04b-wizard-closed')

  // ---------- 7. 定版台账 AP 三新列 ----------
  log('04c 定版台账·供应商应付日余额：三新列 + NEG 重分类 + 纯预付户仍出行')
  await ev(`__T.findBtn('定版台账').click()`)
  await ev(`await __T.waitFor(()=>__T.findBtn('供应商应付日余额'))`)
  await ev(`__T.findBtn('供应商应付日余额').click()`)
  // 区间限定当日（查询条件只在点查询时生效）
  await ev(`
    const ins=document.querySelectorAll('.tabbar')[0] ? document.querySelectorAll('input[type=date]') : [];
  `)
  const dateInputs = await ev(`return document.querySelectorAll('input[type=date]').length`)
  assert(dateInputs >= 2, '台账区应有起止日期两个输入框：' + dateInputs)
  await ev(setNative(`document.querySelectorAll('input[type=date]')[0]`, TODAY))
  await ev(setNative(`document.querySelectorAll('input[type=date]')[1]`, TODAY))
  // 台账区查询按钮（向导隐藏后页面上唯一的「查询」）
  await ev(`[...document.querySelectorAll('button')].find(b=>__T.vis(b)&&b.textContent.trim()==='查询').click()`)
  await ev(`await __T.waitFor(()=>{ const trs=[...document.querySelectorAll('.tablebox table tbody tr, table.data tbody tr')];
      return trs.some(tr=>tr.innerText.includes('S-M4-NEG')) && trs.some(tr=>tr.innerText.includes('S-M4-02')) },12000)`)
  const ledgerHeads = await ev(`return [...document.querySelectorAll('table thead th')].length ? [...document.querySelectorAll('.tablebox table thead th, table.data thead th')].map(th=>th.textContent.trim()).filter(Boolean) : []`)
  log('  台账表头：', JSON.stringify(ledgerHeads))
  for (const h of ['预付(2202重分类)', '预付余额(1123账户)', '费用余额(1221)']) {
    assert(ledgerHeads.includes(h), 'AP 台账缺新列：' + h)
  }
  const ledgerRows = async () => ev(`
    const tbl=[...document.querySelectorAll('table')].find(t=>t.innerText.includes('预付(2202重分类)'));
    return [...tbl.querySelectorAll('tbody tr')].map(tr=>[...tr.querySelectorAll('td')].map(td=>td.textContent.trim()))`)
  const rows = await ledgerRows()
  log('  AP 台账行：', JSON.stringify(rows))
  const negRow = rows.find(r => r.some(c => c.includes('S-M4-NEG')))
  assert(negRow, '台账缺 NEG 行')
  const cellAt = (r, title) => {
    const i = ledgerHeads.indexOf(title)
    return i >= 0 ? r[i] : null
  }
  assert(cellAt(negRow, '预付(2202重分类)') === '30.00', 'NEG 重分类应为 30.00：' + cellAt(negRow, '预付(2202重分类)'))
  assert(cellAt(negRow, '预付余额(1123账户)') === '70.00', 'NEG 1123 账户应为 70.00')
  assert(cellAt(negRow, '费用余额(1221)') === '0.00', 'NEG 1221 费用应为 0.00')
  const s01 = rows.find(r => r.some(c => c.includes('S-M4-01')))
  assert(s01 && cellAt(s01, '预付余额(1123账户)') === '65.00', 'S-M4-01 1123 应为 65.00：' + JSON.stringify(s01))
  const s02 = rows.find(r => r.some(c => c.includes('S-M4-02')))
  assert(s02, '纯预付户 S-M4-02 billCount=0 仍应出行')
  assert(cellAt(s02, '预付余额(1123账户)') === '60.00', 'S-M4-02 1123 应为 60.00')
  assert(/^0(\.0+)?$/.test(cellAt(s02, '单据数')), '纯预付户单据数应为 0：' + cellAt(s02, '单据数'))
  log('  台账三新列齐备；NEG 30/70/0、S01 65、纯预付户 S02 出行 billCount=0')
  await shot('prd36-m4-04c-ap-ledger')

  // ---------- 8. RJ 打印页（已封账态） ----------
  log('05 DayClosePrint：AP 台账 10 列/三新列/注释/NEG 数据行')
  await goto('/finance/day-close-ticket?date=' + TODAY)
  await ev(`await __T.waitFor(()=>document.querySelector('.rj-sheet')
      && document.querySelector('.rj-sheet').innerText.includes('供应商应付定版台账'),12000)`)
  const sheet = await ev(`return document.querySelector('.rj-sheet').innerText`)
  assert(sheet.includes('业 务 日 结 单'), '打印标题不对')
  assert(sheet.includes('预付(2202重分类)') && sheet.includes('预付余额(1123账户)') && sheet.includes('费用余额(1221)'),
    '打印 AP 台账缺三新列表头')
  assert(sheet.includes('资产负债表预付账款=两者之和，分列不要求相等'), '缺分列注释')
  // 表头 10 列
  const apThCount = await ev(`
    const tbls=[...document.querySelectorAll('.rj-table')];
    const t=tbls.find(t=>t.innerText.includes('供应商应付定版台账') || t.querySelector('th')&&[...t.querySelectorAll('th')].some(h=>h.textContent.includes('费用余额(1221)')));
    return t ? t.querySelectorAll('thead th').length : 0`)
  assert(apThCount === 10, 'AP 打印台账应为 10 列：' + apThCount)
  // NEG 数据行带三值
  assert(/S-M4-NEG[\s\S]{0,260}?30\.00\s+70\.00\s+0\.00/.test(sheet), '打印 NEG 行缺 30/70/0：' + sheet.match(/S-M4-NEG[^\n]*/)?.[0])
  assert(sheet.includes('M4负应付户'), '打印缺 NEG 名称')
  log('  打印 AP 台账 10 列/三新列/分列注释/NEG(30/70/0) 正确')
  await shot('prd36-m4-05-day-close-ticket')

  // ---------- 9. 供应商停用五守卫拒绝 ----------
  log('06 供应商停用守卫：S-M4-01 多原因中文拒绝文案')
  await goto('/supplier')
  await ev(`await __T.waitFor(()=>document.querySelector('.tablebox table thead th'))`)
  await ev(setNative(`document.querySelector('input[placeholder="供应商编码/名称/联系人"]')`, 'S-M4-01'))
  await ev(`[...document.querySelectorAll('button')].find(b=>__T.vis(b)&&b.textContent.trim()==='查询').click()`)
  await ev(`await __T.waitFor(()=>{ const tr=[...document.querySelectorAll('.tablebox tbody tr')].find(t=>t.innerText.includes('S-M4-01')); return tr && tr.innerText.includes('M4期初混合户') })`)
  // admin 可见账户值：应付余额列=100（列值取供应商账户，M4 旧列停用）
  const apCell = await ev(`
    const tr=[...document.querySelectorAll('.tablebox tbody tr')].find(t=>t.innerText.includes('S-M4-01'));
    return __T.cell('.tablebox table', tr, '应付余额')`)
  log('  S-M4-01 应付余额列值：', apCell)
  assert(/^100(\.0+)?$/.test(apCell), '档案应付余额应取账户值 100：' + apCell)
  await shot('prd36-m4-06a-supplier-balance-admin')
  // 真实停用入口 = 行内「编辑」打开供应商抽屉 → 状态下拉改「停用」→ 保存（/base/supplier/update）
  await ev(`
    const tr=[...document.querySelectorAll('.tablebox tbody tr')].find(t=>t.innerText.includes('S-M4-01'));
    [...tr.querySelectorAll('button')].find(x=>x.textContent.trim()==='编辑').click()`)
  // 页面里挂了多个 BaseInfoDrawer（keep-alive），必须取当前可见的那个 overlay
  await ev(`await __T.waitFor(()=>{ const d=[...document.querySelectorAll('.drawer-overlay')].find(x=>getComputedStyle(x).display!=='none');
    return d && [...d.querySelectorAll('select')].some(s=>[...s.options].some(o=>o.value==='STOPPED')) })`)
  // 状态下拉选 STOPPED（v-model 监听 change）
  const selVal = await ev(`
    const d=[...document.querySelectorAll('.drawer-overlay')].find(x=>getComputedStyle(x).display!=='none');
    const sel=[...d.querySelectorAll('select')].find(s=>[...s.options].some(o=>o.value==='STOPPED'));
    const setter=Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype,'value').set;
    setter.call(sel,'STOPPED'); sel.dispatchEvent(new Event('change',{bubbles:true}));
    return sel.value`)
  assert(selVal === 'STOPPED', '抽屉状态下拉切到停用失败：' + selVal)
  await shot('prd36-m4-06b-stop-in-drawer')
  // 保存 → 后端 update 五守卫拒绝 → 抽屉 alert「保存失败：无法停用供应商…」（CDP 自动接收）
  lastDialogMsg = null
  await ev(`
    const d=[...document.querySelectorAll('.drawer-overlay')].find(x=>getComputedStyle(x).display!=='none');
    [...d.querySelectorAll('button')].find(b=>b.textContent.trim()==='保存').click()`)
  {
    const t0 = Date.now()
    while (!lastDialogMsg && Date.now() - t0 < 12000) await sleep(200)
  }
  const alertMsg = lastDialogMsg || ''
  log('  停用拒绝 alert：', alertMsg)
  assert(alertMsg.includes('保存失败') && alertMsg.includes('无法停用供应商「M4期初混合户」'), '守卫 alert 缺抬头：' + alertMsg)
  assert(alertMsg.includes('应付余额 100.00') && alertMsg.includes('预付余额 65.00'), '缺账户余额原因：' + alertMsg)
  assert(alertMsg.includes('未结清应付单'), '缺未结 AP 原因：' + alertMsg)
  assert(alertMsg.includes('预付核销单'), '缺未作废预付核销单原因：' + alertMsg)
  // 关闭抽屉（保存被拒，抽屉不自动关）
  await ev(`
    const d=[...document.querySelectorAll('.drawer-overlay')].find(x=>getComputedStyle(x).display!=='none');
    [...d.querySelectorAll('button')].find(b=>b.textContent.trim()==='取消').click()`)
  await ev(`await __T.waitFor(()=>![...document.querySelectorAll('.drawer-overlay')].some(x=>getComputedStyle(x).display!=='none'))`)
  // 确认未停用：重查行状态仍为正常
  await ev(`[...document.querySelectorAll('button')].find(b=>__T.vis(b)&&b.textContent.trim()==='查询').click()`)
  const stillNormal = await ev(`
    await __T.waitFor(()=>[...document.querySelectorAll('.tablebox tbody tr')].some(t=>t.innerText.includes('S-M4-01')))
    const tr=[...document.querySelectorAll('.tablebox tbody tr')].find(t=>t.innerText.includes('S-M4-01'));
    return tr.innerText.includes('正常')`)
  assert(stillNormal, '守卫拒绝后供应商应仍为正常状态')
  log('  编辑抽屉改停用：五守卫 alert 多原因拼接正确，供应商状态未变')

  // 行内「删除」→ lite 确认弹窗 → 确认 → /base/supplier/delete 同五守卫拒绝
  await ev(`
    const tr=[...document.querySelectorAll('.tablebox tbody tr')].find(t=>t.innerText.includes('S-M4-01'));
    [...tr.querySelectorAll('button')].find(x=>x.textContent.trim()==='删除').click()`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.modal-lite'))`)
  const dlgText = await ev(`return __T.visibleMask('.modal-lite').innerText`)
  assert(dlgText.includes('删除'), '确认弹窗应是删除动作：' + dlgText.slice(0, 100))
  await ev(`[...__T.visibleMask('.modal-lite').querySelectorAll('button')].find(b=>b.textContent.trim()==='确认').click()`)
  const toast = await ev(`return await __T.waitFor(()=>{ const t=document.querySelector('.toast-inline');
      return t && t.innerText.includes('删除失败') ? t.innerText : null },12000)`)
  log('  删除拒绝文案：', toast)
  assert(toast.includes('无法删除供应商「M4期初混合户」'), '删除守卫文案缺抬头：' + toast)
  assert(toast.includes('应付余额 100.00'), '删除守卫缺余额原因：' + toast)
  // 删除也被拒：行仍在
  await ev(`[...document.querySelectorAll('button')].find(b=>__T.vis(b)&&b.textContent.trim()==='查询').click()`)
  await ev(`await __T.waitFor(()=>[...document.querySelectorAll('.tablebox tbody tr')].some(t=>t.innerText.includes('S-M4-01')))`)
  log('  行内删除：五守卫 toast 拒绝正确，供应商仍在列表')
  await shot('prd36-m4-06c-delete-guard-toast')

  // ---------- 10. m4field 字段权限：三余额脱敏 ----------
  log('06d m4field 登录：VIEW_AP_BALANCE 无权限，应付余额列脱敏')
  await ev(`localStorage.clear()`)
  await goto('/login')
  await ev(`await __T.waitFor(()=>document.querySelector('input[placeholder="请输入账号"]'))`)
  await ev(`
    const set=(el,v)=>{el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}))}
    set(document.querySelector('input[placeholder="请输入账号"]'),'m4field')
    set(document.querySelector('input[placeholder="请输入密码"]'),'M4test@123')
    await __T.sleep(100)
    document.querySelector('.login-submit').click()`)
  await ev(`await __T.waitFor(()=>!location.pathname.startsWith('/login'),12000)`)
  // 静态 SPA 路由是 /supplier（菜单树 path /base/supplier 经 menu-map 映射）；
  // 路由守卫按授权菜单码 base.supplier 裁剪，已授权则放行
  await goto('/supplier')
  await ev(`await __T.waitFor(()=>document.querySelector('.tablebox table thead th'))`)
  await ev(setNative(`document.querySelector('input[placeholder="供应商编码/名称/联系人"]')`, 'S-M4-01'))
  await ev(`[...document.querySelectorAll('button')].find(b=>__T.vis(b)&&b.textContent.trim()==='查询').click()`)
  await ev(`await __T.waitFor(()=>{ const tr=[...document.querySelectorAll('.tablebox tbody tr')].find(t=>t.innerText.includes('S-M4-01')); return tr && tr.innerText.includes('M4期初混合户') })`)
  const maskedCell = await ev(`
    const tr=[...document.querySelectorAll('.tablebox tbody tr')].find(t=>t.innerText.includes('S-M4-01'));
    return __T.cell('.tablebox table', tr, '应付余额')`)
  log('  m4field 应付余额列值：', JSON.stringify(maskedCell))
  assert(!maskedCell || !/100/.test(maskedCell), '无 VIEW_AP_BALANCE 时应付余额应脱敏为空：' + maskedCell)
  const maskedRow = await ev(`return [...document.querySelectorAll('.tablebox tbody tr')].find(t=>t.innerText.includes('S-M4-01')).innerText`)
  assert(!maskedRow.includes('65.00') && !maskedRow.includes('100.00'), '行内不应出现任何账户余额数值')
  log('  m4field 三余额脱敏正确（后端置 null，列表列空白）')
  await shot('prd36-m4-06d-field-masked')

  log('🎉 PRD-36 M4（V131）浏览器真实交互验收全部通过（10 组场景，0 单据提交）')
  edge.kill()
  ws.close()
}

async function restore() {
  // 必复原：把当日日结 reopen 回 E2E 后的未封账态
  try {
    await apiLogin()
    const wiz = await apiPost('/finance/day-close/wizard', { date: TODAY })
    if (wiz.data?.closed) {
      const r = await apiPost('/finance/day-close/reopen', { date: TODAY, reason: 'M4 UI 验收取数后复原（脚本自动）' })
      if (r.code !== '0') throw new Error(JSON.stringify(r))
      log('已复原：当日日结已 reopen')
    } else {
      log('复原检查：当日本就未封账，无需处理')
    }
  } catch (e) {
    console.error('⚠ 复原失败，需手工反日结 2026-09-17：', e.message)
    process.exitCode = 2
  }
}

(async () => {
  let failed = null
  try {
    await main()
  } catch (e) {
    failed = e
    console.error('PRD-36 M4 UI 验收失败 ❌', e)
    try { edge?.kill() } catch {}
  } finally {
    await restore()
  }
  setTimeout(() => process.exit(failed ? 1 : (process.exitCode || 0)), 300)
})()
