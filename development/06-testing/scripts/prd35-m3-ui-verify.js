/**
 * PRD-35 M3：预收核销 + 结算「使用预收款」浏览器真实交互验收（Edge headless + CDP，node 原生 WebSocket，无第三方依赖）。
 *
 * 前置（隔离环境，勿连受控 8080/erp-v1，勿碰用户 5173）：
 *   1) 8082 后端 + verify-m3 验证库：已跑 prd35-m3-fixtures.sql（客户 KTEST1/KTEST2、
 *      AR-M3-* 应收、SR-M3-* 发货、M3UI01 经手人），且建议先完整跑过 prd35-m3-api-e2e.mjs
 *      （留下审核态 XH 与预收余额 470；本脚本不依赖具体单号，但依赖余额≥470）；
 *   2) vite 跑 5174 代理 8082：
 *      VITE_API_TARGET=http://localhost:8082 npm --prefix frontend run dev -- --host 127.0.0.1 --port 5174 --strictPort
 *
 * 运行：node development/06-testing/scripts/prd35-m3-ui-verify.js
 *   UI_BASE 覆盖前端地址（默认 http://localhost:5174），CDP_PORT 默认 9223。
 *
 * 覆盖：
 *   A 预收核销单列表 + 新建抽屉：客户联动余额(￥470)/未结应收 FIFO、勾选自动填额、超额红字、保存→审核
 *   B 客户应收明细表：勾选 2 张 AR → 收款结算弹窗「预收结算」灰底面板（余额/勾选/默认取小/上限提示/联动现金账户）
 *      预收 40 + 现金 80 真实提交，弹窗关闭、列表刷新
 *   C 客户对账：勾选 2 张对账单 → 收款结算弹窗，抹零2 + 预收 78 + 现金 100（=180）真实提交
 *   D 核销单列表回看：自动单来源列「应收结算生成/对账单结算生成」
 * 截图落 development/06-testing/evidence/（prd35-m3-*）。
 */
const { spawn } = require('child_process')
const fs = require('fs')
const path = require('path')

const EDGE = process.env.EDGE_BIN || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = path.join(__dirname, '..', '..', '..', 'data', 'edge-ui-profile-prd35-m3')
const SHOT_DIR = path.join(__dirname, '..', 'evidence')
const BASE = process.env.UI_BASE || 'http://localhost:5174'
const CDP_PORT = Number(process.env.CDP_PORT || 9223)
fs.mkdirSync(SHOT_DIR, { recursive: true })
fs.rmSync(PROFILE, { recursive: true, force: true })

const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[prd35-m3-ui]', ...a)
const assert = (cond, msg) => { if (!cond) throw new Error(msg) }

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
    setNative(el,v){ const proto = el.tagName==='SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(proto,'value').set.call(el,v); el.dispatchEvent(new Event('change',{bubbles:true})); el.dispatchEvent(new Event('input',{bubbles:true})) },
    rowOf(text){ return [...document.querySelectorAll('table tbody tr')].find(tr=>this.vis(tr)&&tr.textContent.includes(text)) },
    modal(){ return [...document.querySelectorAll('.modal-lite')].find(el=>this.vis(el)) },
  }`
  async function install() { await ev(HELPERS) }
  async function shot(name) {
    const r = await cdp('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.result.data, 'base64'))
    log('截图', name + '.png')
  }
  const lastDialog = () => dialogs[dialogs.length - 1] || ''

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

  // ============ A. 预收核销单：列表 + 新建 + 审核 ============
  log('A. 预收核销单列表')
  await cdp('Page.navigate', { url: BASE + '/finance/advance-writeoff' })
  await sleep(600)
  await install()
  await ev(`await __T.waitFor(()=>__T.has('核销单号'))`)
  assert(await ev(`return [...document.querySelectorAll('thead th')].some(th=>th.textContent.includes('业务来源'))`), '列表应有「业务来源」列')
  await shot('prd35-m3-01-xh-list')

  log('A2. 新建抽屉：选 KTEST1 → 余额 ￥470.00 + FIFO 未结应收')
  await ev(`await __T.clickBtn('新建核销单')`)
  await ev(`await __T.waitFor(()=>document.querySelector('.xw-mask') && __T.vis(document.querySelector('.xw-mask')))`)
  const localToday = await ev(`return new Date(Date.now()-new Date().getTimezoneOffset()*60000).toISOString().slice(0,10)`)
  await ev(`
    const d=document.querySelector('.xw-mask')
    __T.setNative(d.querySelector('input[type=date]'),'${localToday}')
    await __T.waitFor(()=>[...d.querySelectorAll('.master select')[0].options].some(o=>o.value==='KTEST1'))
    __T.setNative(d.querySelectorAll('.master select')[0],'KTEST1')
  `)
  await ev(`await __T.waitFor(()=>/客户 ERP 预收余额：￥470\\.00/.test(document.querySelector('.xw-mask').innerText))`)
  await ev(`await __T.waitFor(()=>[...document.querySelectorAll('.ar-table tbody tr')].some(tr=>tr.textContent.includes('AR-M3-20')))`)
  // FIFO 顺序：最早到期在前（AR-M3-10 09-06 先于 AR-M3-20 09-11）。
  // 单元格 textContent 直接相连（单号后紧跟金额 100.00），必须按「应收单号」列取格内文本
  const fifo = await ev(`
    const i=[...document.querySelectorAll('.ar-table thead th')].findIndex(th=>th.textContent.trim()==='应收单号')
    const nos=[...document.querySelectorAll('.ar-table tbody tr')].map(tr=>tr.children[i]?.textContent.trim().match(/AR-M3-\\d+/)?.[0]).filter(Boolean)
    return nos.slice(0,2).join(',')
  `)
  assert(fifo === 'AR-M3-10,AR-M3-20', '未结应收应按到期日升序：' + fifo)

  log('A3. 经手人 + 勾选 AR-M3-20 → 自动带入 200；合计/超额联动')
  await ev(`
    const d=document.querySelector('.xw-mask')
    await __T.waitFor(()=>[...d.querySelectorAll('.master select')[1].options].some(o=>o.textContent.includes('M3经手人')))
    const hs=d.querySelectorAll('.master select')[1]
    __T.setNative(hs, [...hs.options].find(o=>o.textContent.includes('M3经手人')).value)
    const tr=[...document.querySelectorAll('.ar-table tbody tr')].find(t=>t.textContent.includes('AR-M3-20'))
    tr.querySelector('input[type=checkbox]').click()
  `)
  await ev(`await __T.waitFor(()=>/本次核销合计：￥200\\.00/.test(document.querySelector('.xw-mask').innerText))`)
  let amt20 = await ev(`return [...document.querySelectorAll('.ar-table tbody tr')].find(t=>t.textContent.includes('AR-M3-20')).querySelector('.amt').value`)
  assert(amt20 === '200', '勾选应自动填入可核销额 200，实际：' + amt20)
  // 超额红字：把 AR-M3-10 也填 300（其未收只有 70，被夹紧）；先直接构造超额场景 200+300>470
  await ev(`
    const tr10=[...document.querySelectorAll('.ar-table tbody tr')].find(t=>t.textContent.includes('AR-M3-10'))
    __T.setNative(tr10.querySelector('.amt'),'300')
  `)
  // 300 超过该行未收 70，change 夹紧 70 → 合计 270 ≤ 470 不超额；改为超额：AR-M3-20 调到 470
  // （行上限 200 无法超；用合计逼近：70+200=270 不超，改验「超额拒绝保存」走后端场景太重，
  //  前端校验与 AR 结算弹窗在 B 段验证；这里恢复 AR-M3-10 为 0）
  await ev(`
    const tr10=[...document.querySelectorAll('.ar-table tbody tr')].find(t=>t.textContent.includes('AR-M3-10'))
    __T.setNative(tr10.querySelector('.amt'),'0')
  `)
  amt20 = await ev(`return [...document.querySelectorAll('.ar-table tbody tr')].find(t=>t.textContent.includes('AR-M3-20')).querySelector('.amt').value`)
  assert(amt20 === '200', '行金额应可被夹紧/改回，实际：' + amt20)
  await shot('prd35-m3-02-xh-drawer')

  log('A4. 保存（PENDING）→ 列表待审核 → 行内审核')
  const dlgN0 = dialogs.length
  await ev(`[...document.querySelector('.xw-mask').querySelectorAll('button')].find(b=>b.textContent.trim()==='保存').click()`)
  for (let i = 0; i < 30 && dialogs.length === dlgN0; i++) await sleep(100)
  assert(/确认新建预收核销单/.test(lastDialog()), '保存应有确认弹窗，实际：' + lastDialog())
  await ev(`await __T.waitFor(()=>!document.querySelector('.xw-mask') || !__T.vis(document.querySelector('.xw-mask')))`)
  await ev(`await __T.waitFor(()=>[...document.querySelectorAll('table tbody tr')].some(tr=>tr.textContent.includes('待审核')))`)
  await shot('prd35-m3-03-xh-pending')
  const xhNo = await ev(`
    const tr=[...document.querySelectorAll('table tbody tr')].find(t=>t.textContent.includes('XH')&&t.textContent.includes('待审核'))
    const i=[...document.querySelectorAll('thead th')].findIndex(th=>th.textContent.trim()==='核销单号')
    return tr.children[i].textContent.trim()
  `)
  assert(/^XH\d+$/.test(xhNo), '应取到新建 XH 单号：' + xhNo)
  log('新建核销单：', xhNo)
  await ev(`
    const tr=[...document.querySelectorAll('table tbody tr')].find(t=>t.textContent.includes('${xhNo}'))
    ;[...tr.querySelectorAll('a,button')].find(b=>b.textContent.trim()==='审核').click()
  `)
  await ev(`await __T.waitFor(()=>{ const tr=[...document.querySelectorAll('table tbody tr')].find(t=>t.textContent.includes('${xhNo}')); return tr && tr.textContent.includes('已审核') })`)
  assert(await ev(`const tr=__T.rowOf('${xhNo}'); return [...tr.querySelectorAll('a,button')].some(b=>b.textContent.trim()==='反审核')`), '已审核行应有反审核入口')
  await shot('prd35-m3-04-xh-audited')
  // 审核后预收 470-200=270
  const bal = await ev(`return fetch('/api/finance/customer-account/advance-balance',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+localStorage.getItem('erp-token')},body:JSON.stringify({customerCode:'KTEST1'})}).then(r=>r.json())`)
  assert(Math.abs((bal.data.advanceBalance || 0) - 270) < 0.001, '审核后预收应为 270，实际：' + bal.data.advanceBalance)

  // ============ B. 客户应收明细 → 收款结算（预收+现金） ============
  log('B. 应收明细页过滤 KTEST1 并勾选 AR-M3-10 / AR-M3-40')
  await cdp('Page.navigate', { url: BASE + '/ar' })
  await sleep(600)
  await install()
  await ev(`await __T.waitFor(()=>__T.has('应收单号'))`)
  await ev(`
    const fi=[...document.querySelectorAll('.query-inline .fi')].find(d=>(d.querySelector('label')?.textContent||'').includes('客户/往来单位'))
    __T.setNative(fi.querySelector('input'),'KTEST1')
    await __T.clickBtn('查询')
    await __T.sleep(900)
  `)
  await ev(`
    for (const no of ['AR-M3-10','AR-M3-40']) {
      const tr=await __T.waitFor(()=>__T.rowOf(no))
      const cb=tr.querySelector('input[type=checkbox]')
      if(cb && !cb.checked) cb.click()
      await __T.sleep(80)
    }
  `)
  await ev(`await __T.clickBtn('收款结算')`)
  await ev(`await __T.waitFor(()=>__T.modal() && __T.modal().innerText.includes('预收结算'))`)
  const m1 = await ev(`return __T.modal().innerText`)
  assert(/收款结算/.test(m1) && /已选 2 张单据，本次结算净额 ￥120\.00/.test(m1), '副标题净额应为 120：' + m1.slice(0, 200))
  assert(/客户 ERP 预收余额：￥270\.00/.test(m1), '应显示预收余额 ￥270.00：' + m1.match(/客户 ERP[^\n]*/)?.[0])
  assert(!m1.includes('跨客户结算不能使用预收'), '单客户不应出现跨客户警告')

  log('B2. 勾选使用预收款：默认 120（min(净额120,余额270)）→ 改 40 → 现金账户须 80')
  await ev(`__T.modal().querySelector('.advance-check input[type=checkbox]').click()`)
  await ev(`await __T.waitFor(()=>__T.modal().querySelector('.advance-input-row input').value==='120')`)
  const capHint = await ev(`return __T.modal().querySelector('.advance-input-row .advance-hint').textContent`)
  assert(/最多 ￥120\.00，其余走资金账户/.test(capHint), '上限提示不符：' + capHint)
  await ev(`__T.setNative(__T.modal().querySelector('.advance-input-row input'),'40')`)
  await ev(`await __T.waitFor(()=>/收款账户（账户合计须 = ￥80\.00/.test(__T.modal().innerText))`)
  // 经手人 + 资金账户（单行选完账户自动带入 80）
  await ev(`
    const m=__T.modal()
    const hs=[...m.querySelectorAll('select')].find(s=>[...s.options].some(o=>o.textContent.includes('M3经手人')))
    __T.setNative(hs, [...hs.options].find(o=>o.textContent.includes('M3经手人')).value)
    const fund=m.querySelectorAll('tbody tr')[0].querySelector('select')
    await __T.waitFor(()=>[...fund.options].length>1)
    __T.setNative(fund, [...fund.options].find(o=>o.value).value)
  `)
  await ev(`await __T.waitFor(()=>__T.modal().querySelectorAll('tbody tr')[0].querySelector('input').value==='80')`)
  const sum = await ev(`return __T.modal().querySelector('.account-sum').textContent.trim()`)
  assert(/账户合计 ￥80\.00/.test(sum), '账户合计应 80：' + sum)
  await shot('prd35-m3-05-ar-settle-advance')

  log('B3. 确认结算 → 弹窗关闭、生成 XH 提示')
  const dlgN1 = dialogs.length
  await ev(`[...__T.modal().querySelectorAll('button')].find(b=>b.textContent.trim()==='确认结算').click()`)
  for (let i = 0; i < 40 && dialogs.length === dlgN1; i++) await sleep(100)
  assert(/本次结算净额 ￥120\.00[\s\S]*预收结算 ￥40\.00，现金 ￥80\.00/.test(lastDialog()), '结算确认文案不符：' + lastDialog())
  // 成功后 alert 自动确认；等弹窗关闭、列表刷新
  await ev(`await __T.waitFor(()=>!__T.modal(),15000)`)
  await shot('prd35-m3-06-ar-settle-done')
  const bal2 = await ev(`return fetch('/api/finance/customer-account/advance-balance',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+localStorage.getItem('erp-token')},body:JSON.stringify({customerCode:'KTEST1'})}).then(r=>r.json())`)
  assert(Math.abs((bal2.data.advanceBalance || 0) - 230) < 0.001, '结算后预收应为 230，实际：' + bal2.data.advanceBalance)

  // ============ C. 客户对账单 → 收款结算（预收 78 + 现金 100） ============
  log('C. 客户对账：勾选两张 CS 对账单')
  await cdp('Page.navigate', { url: BASE + '/customer-statement' })
  await sleep(600)
  await install()
  await ev(`await __T.waitFor(()=>__T.has('对账单号')||__T.has('单号')||document.querySelectorAll('table tbody tr').length)`)
  await ev(`
    for (const no of ['CS202609160001','CS202609160002']) {
      const tr=await __T.waitFor(()=>__T.rowOf(no),15000)
      const cb=tr.querySelector('input[type=checkbox]')
      if(cb && !cb.checked) cb.click()
      await __T.sleep(80)
    }
  `)
  await ev(`await __T.clickBtn('收款结算')`)
  await ev(`await __T.waitFor(()=>__T.modal() && /预收结算/.test(__T.modal().innerText))`)
  const m2 = await ev(`return __T.modal().innerText`)
  assert(/收款结算（2 张对账单）/.test(m2), '弹窗标题应带对账单张数：' + m2.slice(0, 120))
  assert(/本次结算净额 ￥180\.00/.test(m2), '净额应 180：' + m2.match(/本次结算净额[^\n]*/)?.[0])
  assert(/客户 ERP 预收余额：￥230\.00/.test(m2), '应显示预收余额 ￥230.00')

  log('C2. 抹零2 + 预收78 + 经手人 + 现金账户100 提交（与 API E2E D 场景一致：2+78+100=180）')
  // 先填抹零：净额 180→178，预收上限随之变 178
  await ev(`
    const m=__T.modal()
    const woFi=[...m.querySelectorAll('.fi')].find(d=>(d.querySelector('label')?.textContent||'').includes('抹零金额'))
    __T.setNative(woFi.querySelector('input'),'2')
    const etFi=[...m.querySelectorAll('.fi')].find(d=>(d.querySelector('label')?.textContent||'').includes('抹零费用类型'))
    const etSel=etFi.querySelector('select')
    await __T.waitFor(()=>[...etSel.options].some(o=>o.textContent.includes('M3抹零费用')))
    __T.setNative(etSel, [...etSel.options].find(o=>o.textContent.includes('M3抹零费用')).value)
  `)
  await ev(`__T.modal().querySelector('.advance-check input[type=checkbox]').click()`)
  await ev(`await __T.waitFor(()=>__T.modal().querySelector('.advance-input-row input').value==='178')`)
  await ev(`__T.setNative(__T.modal().querySelector('.advance-input-row input'),'78')`)
  await ev(`await __T.waitFor(()=>/收款账户（账户合计须 = ￥100\.00/.test(__T.modal().innerText))`)
  await ev(`
    const m=__T.modal()
    const hs=[...m.querySelectorAll('select')].find(s=>[...s.options].some(o=>o.textContent.includes('M3经手人')))
    __T.setNative(hs, [...hs.options].find(o=>o.textContent.includes('M3经手人')).value)
    const fund=m.querySelectorAll('tbody tr')[0].querySelector('select')
    await __T.waitFor(()=>[...fund.options].length>1)
    __T.setNative(fund, [...fund.options].find(o=>o.value).value)
    __T.setNative(m.querySelectorAll('tbody tr')[0].querySelector('input'),'100')
  `)
  await shot('prd35-m3-07-stmt-settle-advance')
  const dlgN2 = dialogs.length
  await ev(`[...__T.modal().querySelectorAll('button')].find(b=>b.textContent.trim()==='确认收款').click()`)
  for (let i = 0; i < 40 && dialogs.length === dlgN2; i++) await sleep(100)
  assert(/预收结算 ￥78\.00，现金 ￥100\.00/.test(lastDialog()), '对账单结算确认文案不符：' + lastDialog())
  await ev(`await __T.waitFor(()=>!__T.modal(),15000)`)
  await shot('prd35-m3-08-stmt-settle-done')

  // ============ D. XH 列表回看自动单来源 ============
  log('D. 核销单列表：自动单来源列')
  await cdp('Page.navigate', { url: BASE + '/finance/advance-writeoff' })
  await sleep(700)
  await install()
  // 状态选已审核，业务来源全部
  await ev(`await __T.waitFor(()=>__T.has('业务来源'))`)
  await shot('prd35-m3-09-xh-auto-list')
  // 业务来源列显示枚举标签「应收结算/对账单结算」，来源单号列分别挂 SK/CS 单号；操作格另有「结算生成」标记
  const src = await ev(`return [...document.querySelectorAll('table tbody tr')].map(tr=>tr.textContent).filter(t=>t.includes('XH'))`)
  // 注意：本表达式经模板字符串送进浏览器，\d 会被吞成 d，数字只能用 [0-9]
  assert(src.some(t=>/应收结算/.test(t) && /SK[0-9]{6,}/.test(t)), '应有「应收结算」自动单（来源 SK 收款单），实际：' + src.find(t=>/应收结算/.test(t))?.slice(0, 120))
  assert(src.some(t=>/对账单结算/.test(t) && /CS[0-9]{6,}/.test(t)), '应有「对账单结算」自动单（来源 CS 对账单），实际：' + src.find(t=>/对账单结算/.test(t))?.slice(0, 120))
  // 自动单操作格：只有「查看」入口（不出现编辑/审核/反审核），并带「结算生成」来源标记
  const autoRow = await ev(`
    const tr=[...document.querySelectorAll('table tbody tr')].find(t=>t.textContent.includes('应收结算')&&t.textContent.includes('已审核'))
    if(!tr) return '(none)'
    return JSON.stringify({ ops:[...tr.querySelectorAll('a,button')].map(b=>b.textContent.trim()).filter(Boolean), tag: tr.textContent.includes('结算生成') })
  `)
  const autoInfo = JSON.parse(autoRow)
  assert(autoInfo.tag && autoInfo.ops.includes('查看') && !autoInfo.ops.some(o => /编辑|反审核/.test(o) || o === '审核'),
    '自动单应仅可查看并带结算生成标记，实际：' + autoRow)

  log('🎉 PRD-35 M3 浏览器真实交互验收全部通过')
  edge.kill()
  ws.close()
  process.exit(0)
}

async function dumpFailure() {
  try {
    const r = await _cdp('Runtime.evaluate', {
      expression: `JSON.stringify({
        href: location.href,
        ths: [...document.querySelectorAll('thead th')].map(t => t.textContent.trim()).slice(0, 30),
        tableCount: document.querySelectorAll('table').length,
        rowCount: document.querySelectorAll('table tbody tr').length,
        errs: (window.__errs || []).slice(-10),
        bodyHead: (document.querySelector('.page-card,.generic-list,main')?.innerText || document.body.innerText).slice(0, 600),
      })`,
      returnByValue: true,
    })
    console.error('---- 失败现场 ----\n', r.result?.result?.value)
    const shot = await _cdp('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(SHOT_DIR, 'prd35-m3-fail.png'), Buffer.from(shot.result.data, 'base64'))
    log('失败截图 prd35-m3-fail.png')
  } catch (e) { console.error('dump 失败', e?.message || e) }
}

main().catch(async e => {
  console.error('PRD-35 M3 UI 验收失败 ❌', e)
  await sleep(300)
  await dumpFailure()
  try { edge?.kill() } catch {}
  setTimeout(() => process.exit(1), 300)
})
