/**
 * PRD-35 M4：浏览器真实交互验收（Edge headless + CDP，node 原生 WebSocket，无第三方依赖）。
 *
 * 覆盖 M4 三处前端面：
 *   A 客户期初预收页（/finance/init-adv）：未建账空态/必填校验/手工新增暂存行/合计栏、
 *     导入弹窗（模板与表头）、期初建账（批号 QC、建账横幅、面板锁定）、反建账（原因必填、
 *     反建后暂存行恢复）、重新建账；
 *   B 日结向导（/finance/day-close）：第⑤步「预收定版取客户账户」勾稽提示含账户预收余额合计；
 *     日结后定版台账 AR 表新增「预收余额/预收(重分类)」两列且数值正确；RJ 打印单同步新列；
 *   C 系统参数 P0189（/param）：tms.settle.overpay-to-advance「门店结算溢收自动转预收」可检索、当前值 Y。
 *
 * 前置（隔离环境，勿连受控 8080/erp-v1，勿碰用户 5173）：
 *   1) 8082 后端 + verify-m4 验证库（启动参数见 prd35-m4-api-e2e.mjs 头注释）；
 *   2) vite 跑 5174 代理 8082：
 *      VITE_API_TARGET=http://localhost:8082 npm --prefix frontend run dev -- --host 127.0.0.1 --port 5174 --strictPort
 *
 * 运行：node development/06-testing/scripts/prd35-m4-ui-verify.js
 *   UI_BASE 覆盖前端地址（默认 http://localhost:5174），CDP_PORT 默认 9224。
 *
 * 脚本复用 prd35-m4-fixtures.sql 做数据复位（H2 RunScript 经 AUTO_SERVER 并发直连），
 * 先调反日结 API 清掉上轮封单（BizDayCloseGuard 封单日是 JVM 缓存，直删表无法 evict），
 * 可任意重跑。截图落 development/06-testing/evidence/（prd35-m4-*）。
 */
const { spawn } = require('child_process')
const { execFileSync } = require('child_process')
const fs = require('fs')
const os = require('os')
const path = require('path')

const EDGE = process.env.EDGE_BIN || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = path.join(__dirname, '..', '..', '..', 'data', 'edge-ui-profile-prd35-m4')
const SHOT_DIR = path.join(__dirname, '..', 'evidence')
const BASE = process.env.UI_BASE || 'http://localhost:5174'
const CDP_PORT = Number(process.env.CDP_PORT || 9224)
const H2_JAR = process.env.H2_JAR
  || 'C:/Users/Administrator/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar'
const JAVA = process.env.JAVA_BIN || 'C:/Users/Administrator/jdk21/bin/java.exe'
const H2_URL = process.env.H2_URL
  || 'jdbc:h2:file:E:/work/erp-wms-tms/backend/data/verify-m4;AUTO_SERVER=TRUE;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE'
const FIXTURE = path.resolve('development/06-testing/scripts/prd35-m4-fixtures.sql')

const TODAY = (() => {
  const d = new Date(Date.now() - new Date().getTimezoneOffset() * 60000)
  return d.toISOString().slice(0, 10)
})()
const C_O1 = 'M4ADV1'
const C_O2 = 'M4ADV2'

fs.mkdirSync(SHOT_DIR, { recursive: true })
fs.rmSync(PROFILE, { recursive: true, force: true })

const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[prd35-m4-ui]', ...a)
const assert = (cond, msg) => { if (!cond) throw new Error(msg) }
let passed = 0
function ok(cond, msg) { assert(cond, msg); passed++; console.log('  ✅ ' + msg) }

let edge
let _cdp
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
  const dialogs = []
  ws.onmessage = async e => {
    const m = JSON.parse(e.data)
    if (m.method === 'Page.javascriptDialogOpening') {
      dialogs.push(m.params.message)
      await cdp('Page.handleJavaScriptDialog', { accept: true })
    }
    if (m.id && waits.has(m.id)) { waits.get(m.id)(m); waits.delete(m.id) }
  }
  function cdp(method, params = {}) {
    const id = ++_id
    return new Promise(resolve => { waits.set(id, resolve); ws.send(JSON.stringify({ id, method, params })) })
  }
  _cdp = cdp
  await cdp('Page.enable')
  await cdp('Runtime.enable')

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

  const HELPERS = `window.__errs = window.__errs || [];
    window.addEventListener('error', e => window.__errs.push('error: ' + (e.message || '')));
    window.addEventListener('unhandledrejection', e => window.__errs.push('reject: ' + (e.reason && (e.reason.message || e.reason) || '')));
  window.__T = {
    sleep: m => new Promise(r=>setTimeout(r,m)),
    async waitFor(fn, ms=10000){ const t=Date.now(); let last; while(Date.now()-t<ms){ try{ const v=fn(); if(v) return v }catch(e){ last=e } await this.sleep(100) } throw new Error('waitFor超时: '+(last&&last.message||'')+' @ '+(fn.toString().slice(0,140))) },
    vis(el){ if(!el) return false; const r=el.getBoundingClientRect(); return r.width>0&&r.height>0 },
    findBtn(text){ return [...document.querySelectorAll('button')].find(b=>this.vis(b)&&b.textContent.replace(/\\s+/g,'').includes(text)) },
    async clickBtn(text){ const b=await this.waitFor(()=>this.findBtn(text)); b.click() },
    has(text){ return document.body.innerText.includes(text) },
    setNative(el,v){ const proto = el.tagName==='SELECT' ? HTMLSelectElement.prototype : (el.tagName==='TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype); Object.getOwnPropertyDescriptor(proto,'value').set.call(el,v); el.dispatchEvent(new Event('change',{bubbles:true})); el.dispatchEvent(new Event('input',{bubbles:true})) },
  }
  window.__api = async (p, body) => {
    const res = await fetch('/api' + p, { method:'POST', headers:{'Content-Type':'application/json', Authorization:'Bearer '+localStorage.getItem('erp-token')}, body: JSON.stringify(body||{}) })
    const j = await res.json()
    if (j.code !== '0') throw new Error('API ' + p + ' → ' + (j.message || JSON.stringify(j)))
    return j.data
  }`
  async function install() { await ev(HELPERS) }
  async function shot(name) {
    const r = await cdp('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.result.data, 'base64'))
    log('截图', name + '.png')
  }
  const api = async (p, body) => ev(`return await __api(${JSON.stringify(p)}, ${JSON.stringify(body || {})})`)
  const applyFixtures = (fundName, fundCode) => {
    let sql = fs.readFileSync(FIXTURE, 'utf8')
    sql = sql.replaceAll('__FUND__', fundName).replaceAll('__FUND_CODE__', fundCode)
    const tmp = path.join(os.tmpdir(), 'prd35-m4-ui-fixtures.applied.sql')
    fs.writeFileSync(tmp, sql, 'utf8')
    execFileSync(JAVA, ['-Dfile.encoding=UTF-8', '-cp', H2_JAR, 'org.h2.tools.RunScript',
      '-url', H2_URL, '-user', 'sa', '-script', tmp], { stdio: 'inherit' })
  }

  // ---------- 登录 ----------
  log('打开登录页')
  await cdp('Page.navigate', { url: BASE + '/login' })
  await sleep(400)
  await install()
  await ev(`await __T.waitFor(()=>document.querySelector('input[placeholder="请输入账号"]'))`)
  await ev(`
    const set=(el,v)=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}))}
    set(document.querySelector('input[placeholder="请输入账号"]'),'admin')
    set(document.querySelector('input[placeholder="请输入密码"]'),'admin123')
    await __T.sleep(100)
    document.querySelector('.login-submit').click()
  `)
  await ev(`await __T.waitFor(()=>!location.pathname.startsWith('/login'),12000)`)
  log('登录成功', await ev(`return location.pathname`))

  // ---------- 数据复位：先 API 反日结（evict 封单日 JVM 缓存）→ H2 套夹具 → 参数缓存 evict ----------
  try {
    await api('/finance/day-close/reopen', { date: TODAY, reason: 'PRD-35 M4 UI 验收重跑复位' })
    log('检测到上轮日结，已走反日结链复位 ' + TODAY)
  } catch (e) {
    if (!String(e.message).includes('未日结')) throw e
  }
  const funds = await api('/base/master/fund-account/page', { pageNo: 1, pageSize: 50 })
  const fund = (funds.records || []).find(f => f.status === 'NORMAL' || !f.status) || funds.records[0]
  assert(fund, '资金账户至少存在 1 个')
  log('套用夹具（资金账户：' + fund.fundAccountName + '）')
  applyFixtures(fund.fundAccountName, fund.fundAccountCode || fund.fundAccountName)
  await api('/system/param/update', { paramKey: 'tms.settle.overpay-to-advance', paramValue: 'Y' })
  for (const [code, name] of [[C_O1, 'M4期初客户甲'], [C_O2, 'M4期初客户乙']]) {
    try {
      await api('/base/customer/create', {
        customerCode: code, customerName: name,
        channelType: '零售商超', accountPeriodType: '月结30天',
      })
    } catch (e) {
      if (!String(e.message).includes('已存在') && !String(e.message).includes('重复')) throw e
    }
  }

  // ================= A. 客户期初预收页 =================
  console.log('\n=== A. 客户期初预收：暂存 → 建账 → 锁定 → 反建账 → 重建账 ===')
  await cdp('Page.navigate', { url: BASE + '/finance/init-adv' })
  await sleep(600)
  await install()
  await ev(`await __T.waitFor(()=>__T.has('客户期初预收期初初始化'))`)
  ok(await ev(`return __T.has('未建账（暂存核对中）')`), '初始状态：未建账（暂存核对中）')
  ok(await ev(`
    const b=[...document.querySelectorAll('button')].find(x=>x.textContent.trim()==='期初建账')
    return b && b.disabled
  `), '无暂存行时期初建账按钮禁用')
  await shot('prd35-m4-01-initadv-empty')

  log('A1. 手工新增 2 行暂存（SearchSelect 选客户）')
  async function addAdvRow(customerText, amount, billNo, billDate) {
    await ev(`await __T.clickBtn('新增行')`)
    await ev(`await __T.waitFor(()=>[...document.querySelectorAll('.modal-h')].some(h=>__T.vis(h.parentElement)&&h.textContent.includes('新增客户期初预收期初行')))`)
    // 客户编码是自定义 SearchSelect：点开 → 选项
    await ev(`
      const d=document.querySelector('.modal-mask .modal')
      d.querySelector('.ss-control').click()
      await __T.waitFor(()=>[...document.querySelectorAll('.ss-option')].some(o=>__T.vis(o)&&o.textContent.includes(${JSON.stringify(customerText)})))
      ;[...document.querySelectorAll('.ss-option')].find(o=>__T.vis(o)&&o.textContent.includes(${JSON.stringify(customerText)})).click()
    `)
    // 按表单项标题取控件（客户名称是 SearchSelect 渲染的搜索框，不能靠 text input 顺序猜）
    const fieldInput = (labelPart) => `
      [...d.querySelectorAll('label.field')].find(l=>l.querySelector('.flabel').textContent.includes(${JSON.stringify(labelPart)}))?.querySelector('input,textarea')`
    const setter = `
      const d=document.querySelector('.modal-mask .modal')
      __T.setNative(${fieldInput('预收金额')}, ${JSON.stringify(amount)})
      __T.setNative(${fieldInput('原单据号')}, ${JSON.stringify(billNo)})
      ${billDate ? `__T.setNative(${fieldInput('原单据日期')}, ${JSON.stringify(billDate)})` : ''}
    `
    await ev(setter)
    await shot('prd35-m4-02-initadv-form')
    await ev(`[...document.querySelectorAll('.modal-mask .modal button')].find(b=>b.textContent.trim()==='保存').click()`)
    await ev(`await __T.waitFor(()=>![...document.querySelectorAll('.modal-mask')].some(m=>__T.vis(m)))`)
  }
  await addAdvRow('M4期初客户甲', '120', 'M4-UI-1', '2026-08-31')
  await ev(`await __T.waitFor(()=>__T.has('M4-UI-1') && __T.has('120.00'))`)
  ok(await ev(`return __T.has('暂存有效行') && /预收合计/.test(document.body.innerText)`), '新增第 1 行：列表回显 + 合计栏刷新')
  await addAdvRow('M4期初客户乙', '80', 'M4-UI-2', '')
  await ev(`await __T.waitFor(()=>__T.has('M4-UI-2') && __T.has('￥200.00'))`)
  ok(await ev(`return /暂存有效行\\s*2/.test(document.body.innerText.replace(/\\n/g,' '))`), '第 2 行后：暂存有效行 2')
  ok(await ev(`return /￥200\\.00/.test(document.body.innerText)`), '预收合计 ￥200.00')
  await shot('prd35-m4-03-initadv-lines')

  log('A2. 必填校验：空表单保存被拦')
  await ev(`await __T.clickBtn('新增行')`)
  await ev(`await __T.waitFor(()=>document.querySelector('.modal-mask .modal'))`)
  await ev(`[...document.querySelectorAll('.modal-mask .modal button')].find(b=>b.textContent.trim()==='保存').click()`)
  await ev(`await __T.waitFor(()=>/请填写必填项/.test(document.body.innerText))`)
  ok(await ev(`return /客户编码/.test(document.body.innerText) && /预收金额/.test(document.body.innerText)`), '缺客户/金额 → 文案列出必填项')
  await ev(`[...document.querySelectorAll('.modal-mask .modal button')].find(b=>b.textContent.trim()==='取消').click()`)

  log('A3. 导入弹窗：模板表头/下载入口')
  await ev(`await __T.clickBtn('导入')`)
  await ev(`await __T.waitFor(()=>__T.vis(document.querySelector('.import-mask')))`)
  ok(await ev(`return __T.has('客户期初预收导入')`), '导入弹窗标题')
  ok(await ev(`return /下载模板/.test(document.querySelector('.import-mask').innerText) && __T.has('选择文件')`), '弹窗提供模板下载与文件选择入口')
  ok(await ev(`return [...document.querySelectorAll('.import-mask button,a')].some(b=>/\\.xlsx/.test(b.textContent))`), '提供「下载模板（.xlsx）」入口')
  await shot('prd35-m4-04-initadv-import')
  await ev(`document.querySelector('.import-mask .close-btn').click()`)
  await ev(`await __T.waitFor(()=>!__T.vis(document.querySelector('.import-mask')))`)

  log('A4. 期初建账 → 状态卡/面板锁定')
  await ev(`await __T.clickBtn('期初建账')`) // window.confirm 已由 CDP 自动接受
  await ev(`await __T.waitFor(()=>/已建账\\s·\\s批号\\sQC\\d+/.test(document.body.innerText))`)
  ok(await ev(`return /共\\s*2\\s*行/.test(document.body.innerText)`), '建账横幅：批号 QC + 共 2 行')
  ok(await ev(`
    return __T.has('反建账')
      && ![...document.querySelectorAll('button')].some(b=>__T.vis(b)&&/新增行|导入|清空暂存/.test(b.textContent))
      && __T.has('M4-UI-1')
  `), '建账后：反建账按钮在，导入/新增/清空按钮消失，行只读保留')
  ok(await ev(`
    const b=[...document.querySelectorAll('button')].find(x=>x.textContent.trim()==='期初建账')
    return !b
  `), '建账后「期初建账」按钮消失')
  await shot('prd35-m4-05-initadv-posted')

  log('A5. 反建账：原因必填 → 填因成功，暂存行恢复')
  await ev(`await __T.clickBtn('反建账')`)
  await ev(`await __T.waitFor(()=>[...document.querySelectorAll('.modal-h')].some(h=>h.textContent.includes('反建账')))`)
  await ev(`[...document.querySelectorAll('.modal-mask .modal button')].find(b=>b.textContent.trim()==='确认反建账').click()`)
  await ev(`await __T.waitFor(()=>__T.has('请填写反建账原因'))`)
  ok(true, '反建账原因必填拦截')
  await ev(`
    const ta=document.querySelector('.modal-mask textarea')
    __T.setNative(ta,'M4 UI 验收：反建账后重新建账')
  `)
  await ev(`[...document.querySelectorAll('.modal-mask .modal button')].find(b=>b.textContent.trim()==='确认反建账').click()`)
  await ev(`await __T.waitFor(()=>__T.has('未建账（暂存核对中）') && __T.has('M4-UI-1'))`)
  ok(await ev(`return __T.has('已反建账，暂存行恢复可编辑') || /暂存有效行\\s*2/.test(document.body.innerText.replace(/\\n/g,' '))`), '反建账成功：暂存 2 行恢复可编辑')
  await shot('prd35-m4-06-initadv-reversed')

  log('A6. 重新建账（供日结验证）')
  await ev(`await __T.clickBtn('期初建账')`)
  await ev(`await __T.waitFor(()=>/已建账\\s·\\s批号\\sQC\\d+/.test(document.body.innerText))`)
  ok(true, '重新建账成功')

  // ---------- 交账审核 ×3（API 备数）：JZ1/JZ2 参数Y 溢收自动 100/40；JZ3 参数关仅留日志不转预收。
  //            向导账户预收合计 = 200 期初 + 100 + 40 = 340 ----------
  for (const jz of ['JZID-M4-1', 'JZID-M4-2']) {
    const r = await api('/tms/settlement/' + jz + '/audit', {})
    assert(r.status === 'APPROVED', jz + ' 审核失败：' + JSON.stringify(r))
  }
  await api('/system/param/update', { paramKey: 'tms.settle.overpay-to-advance', paramValue: 'N' })
  const r3 = await api('/tms/settlement/JZID-M4-3/audit', {})
  assert(r3.status === 'APPROVED', 'JZID-M4-3 审核失败：' + JSON.stringify(r3))
  await api('/system/param/update', { paramKey: 'tms.settle.overpay-to-advance', paramValue: 'Y' })
  log('三张交账单已审核（自动预收单 100/40；JZ3 参数关溢 50 不转）')

  // ================= B. 日结向导 / 定版台账 / 打印单 =================
  console.log('\n=== B. 日结向导：预收勾稽提示 + 定版新列 + 打印单 ===')
  await cdp('Page.navigate', { url: BASE + '/finance/day-close' })
  await sleep(600)
  await install()
  await ev(`await __T.waitFor(()=>__T.findBtn('查询'))`)
  await ev(`
    const dateInput=document.querySelector('.tabbar')?.parentElement?.parentElement?.querySelector('input[type=date]')
    const root=[...document.querySelectorAll('input[type=date]')].find(i=>__T.vis(i) && i.closest('.module-body'))
    __T.setNative(root, ${JSON.stringify(TODAY)})
    ;[...document.querySelectorAll('button')].find(b=>b.textContent.trim()==='查询' && __T.vis(b)).click()
  `)
  await ev(`await __T.waitFor(()=>document.querySelector('.tie-advance'))`)
  ok(await ev(`return document.querySelector('.tie-advance').innerText.includes('账户预收余额合计')`), '向导⑤：预收定版取客户账户提示存在')
  const advTotal = await ev(`return (document.querySelector('.tie-advance b.money')||{}).textContent || ''`)
  ok(/340\.00/.test(advTotal), '账户预收余额合计=340.00（期初200+溢收100+40，实际 ' + advTotal + '）')
  ok(await ev(`return document.querySelector('.tie-advance').innerText.includes('负应收重分类兜底合计')`), '向导⑤：负应收重分类兜底提示保留')
  await shot('prd35-m4-07-dayclose-wizard')

  log('B2. 执行日结（API 走正式链）→ 定版台账新列')
  await api('/finance/day-close/close', {
    date: TODAY, openingConfirmed: true, acknowledgeHanging: true,
    acknowledgeAnomaly: true, cashCounts: {},
  })
  // 切「定版台账」页签，封单日期=今天 → 查询
  await ev(`await __T.clickBtn('定版台账')`)
  await ev(`
    const dates=[...document.querySelectorAll('input[type=date]')].filter(i=>__T.vis(i))
    // 定版台账区两个 date（起/止）都填今天
    dates.slice(-2).forEach(i=>__T.setNative(i, ${JSON.stringify(TODAY)}))
    const kw=[...document.querySelectorAll('input')].find(i=>i.placeholder==='编码/名称关键字')
    ;[...document.querySelectorAll('button')].find(b=>b.textContent.trim()==='查询' && __T.vis(b) && b.closest('.module-body')).click()
  `)
  await ev(`await __T.waitFor(()=>[...document.querySelectorAll('thead th')].some(th=>th.textContent.trim()==='预收余额'))`)
  ok(await ev(`return [...document.querySelectorAll('thead th')].some(th=>th.textContent.trim()==='预收(重分类)')`), 'AR 定版表头：预收(重分类) 列保留')
  const cell = await ev(`
    const hs=[...document.querySelectorAll('thead th')].map(th=>th.textContent.trim())
    const iCode=hs.indexOf('客户编码'), iAdv=hs.indexOf('预收余额')
    const tr=[...document.querySelectorAll('table.data tbody tr')].find(t=>t.textContent.includes('${C_O1}'))
    return tr ? tr.children[iAdv].textContent.trim() + '|' + tr.children[iCode].textContent.trim() : ''
  `)
  ok(/^120\.00\|/.test(cell), '定版台账 M4ADV1 预收余额=120.00（实际 ' + cell + '）')
  await shot('prd35-m4-08-dayclose-ledger')

  log('B3. RJ 打印单：预收余额列同步')
  await cdp('Page.navigate', { url: BASE + '/finance/day-close-ticket?date=' + TODAY })
  await sleep(700)
  await install()
  await ev(`await __T.waitFor(()=>__T.has('预收余额'))`)
  ok(await ev(`return __T.has('预收(重分类)') && __T.has('客户应收定版台账')`), '打印单：应收定版表含两列预收')
  ok(await ev(`
    const tbl=[...document.querySelectorAll('table')].find(t=>t.textContent.includes('客户应收定版台账') || [...t.querySelectorAll('th')].some(h=>h.textContent.trim()==='预收余额'))
    const ths=[...tbl.querySelectorAll('th')].map(th=>th.textContent.trim())
    const i=ths.indexOf('预收余额')
    const tr=[...tbl.querySelectorAll('tbody tr')].find(t=>t.textContent.includes('${C_O1}'))
    return tr && tr.children[i].textContent.trim()==='120.00'
  `), '打印单：M4ADV1 行预收余额 120.00')
  await shot('prd35-m4-09-dayclose-ticket')

  // ================= C. 系统参数 P0189 =================
  console.log('\n=== C. 系统参数 P0189：门店结算溢收自动转预收 ===')
  await cdp('Page.navigate', { url: BASE + '/param' })
  await sleep(700)
  await install()
  await ev(`await __T.waitFor(()=>document.querySelector('.query-inline input'))`)
  await ev(`
    const inp=document.querySelector('.query-inline input')
    __T.setNative(inp,'溢收')
    document.querySelector('.query-inline').querySelectorAll('button')[0].click()
  `)
  await ev(`await __T.waitFor(()=>document.body.innerText.includes('tms.settle.overpay-to-advance'))`)
  ok(await ev(`return __T.has('门店结算溢收自动转预收') && __T.has('TMS配送')`), '参数行：名称/分组可见（参数编号 P0189 见 V127 种子与用户手册）')
  ok(await ev(`
    const tr=[...document.querySelectorAll('table tbody tr')].find(t=>t.textContent.includes('tms.settle.overpay-to-advance'))
    return tr && /\\bY\\b/.test(tr.textContent) && /未匹配/.test(tr.textContent)
  `), '当前值 Y，备注说明未匹配多收款自动转预收语义')
  await shot('prd35-m4-10-param-p0189')

  const errs = await ev(`return window.__errs || []`)
  assert(errs.length === 0, '页面运行时错误：' + JSON.stringify(errs))

  console.log(`\n🎉 PRD-35 M4 UI 全部通过：${passed} 项断言；截图见 development/06-testing/evidence/prd35-m4-*`)
}

main().catch(async e => {
  console.error('❌ UI 验收失败：', e.message)
  try {
    const r = await _cdp('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(SHOT_DIR, 'prd35-m4-error.png'), Buffer.from(r.result.data, 'base64'))
    const errs = await _cdp('Runtime.evaluate', { expression: 'JSON.stringify(window.__errs || [])', returnByValue: true })
    console.error('页面错误：', errs.result?.result?.value)
  } catch {}
  process.exitCode = 1
}).finally(() => {
  if (edge) edge.kill()
})
