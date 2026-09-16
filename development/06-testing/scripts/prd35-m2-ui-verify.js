/**
 * PRD-35 M2：收款单「预收收款 / 预收退款」浏览器真实交互验收（Edge headless + CDP，node 原生 WebSocket，无第三方依赖）。
 *
 * 前置（隔离环境，勿连受控 8080/erp-v1）：
 *   1) 8082 后端使用 verify-m2 验证库（fixture：客户 KTEST1 测试客户甲、资金账户 01 现金挂 1001、
 *      2026 会计期间、fin.gl.initialized=Y），setup-m2.sql 已执行且后端已重启（参数缓存）；
 *   2) vite 跑 5174 代理 8082：
 *      VITE_API_TARGET=http://localhost:8082 npm --prefix frontend run dev -- --port 5174 --strictPort
 *
 * 运行：node development/06-testing/scripts/prd35-m2-ui-verify.js
 *   UI_BASE 覆盖前端地址（默认 http://localhost:5174），CDP_PORT 默认 9222。
 *
 * 覆盖（M1 脚本不动，本脚本独立）：
 *   A 收款类型 3 个单选项，默认应收结算；预收类强制客户（供应商/往来单位禁用置灰）
 *   B 预收收款：金额列表头「预收金额」+ 不核销应收提示；保存→列表收款类型列/核销金额「—」→审核
 *   C 客户账户页预收余额 300.00
 *   D 预收退款：余额提示 ￥300.00；超额 400 前端拦截（与后端同文案、橙色警告态）
 *   E 退款 120 保存审核 → 预收收款单反审核被守卫（已被退款使用）
 *   F 退款反审核 → 收款单反审核 → 编辑改 260 再审核（反审核→修改→再审核轮次链路 UI）
 *   G 列表过滤：收款类型=预收退款 只剩退款行；核销状态=未核销 预收行全部不出现
 * 截图落 development/06-testing/evidence/（prd35-m2-*）。
 */
const { spawn } = require('child_process')
const fs = require('fs')
const path = require('path')

const EDGE = process.env.EDGE_BIN || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = path.join(__dirname, '..', '..', '..', 'data', 'edge-ui-profile-prd35-m2')
const SHOT_DIR = path.join(__dirname, '..', 'evidence')
const BASE = process.env.UI_BASE || 'http://localhost:5174'
const CDP_PORT = Number(process.env.CDP_PORT || 9222)
fs.mkdirSync(SHOT_DIR, { recursive: true })
fs.rmSync(PROFILE, { recursive: true, force: true })

const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[prd35-m2-ui]', ...a)
const assert = (cond, msg) => { if (!cond) throw new Error(msg) }

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
  const dialogs = []
  ws.onmessage = async e => {
    const m = JSON.parse(e.data)
    if (m.method === 'Page.javascriptDialogOpening') {
      dialogs.push(m.params.message)
      const hr = await cdp('Page.handleJavaScriptDialog', { accept: true })
      if (hr.error) console.log('[dialog handle error]', m.params.type, JSON.stringify(hr.error))
    }
    if (m.id && waits.has(m.id)) { waits.get(m.id)(m); waits.delete(m.id) }
  }
  function cdp(method, params = {}) {
    const id = ++_id
    return new Promise(resolve => { waits.set(id, resolve); ws.send(JSON.stringify({ id, method, params })) })
  }

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

  const HELPERS = `window.__T = {
    sleep: m => new Promise(r=>setTimeout(r,m)),
    async waitFor(fn, ms=10000){ const t=Date.now(); let last; while(Date.now()-t<ms){ try{ const v=fn(); if(v) return v }catch(e){ last=e } await this.sleep(100) } throw new Error('waitFor超时: '+(last&&last.message||'')+' @ '+(fn.toString().slice(0,120))) },
    vis(el){ if(!el) return false; const r=el.getBoundingClientRect(); return r.width>0&&r.height>0 },
    findBtn(text){ return [...document.querySelectorAll('button')].find(b=>this.vis(b)&&b.textContent.replace(/\\s+/g,'').includes(text)) },
    async clickBtn(text){ const b=await this.waitFor(()=>this.findBtn(text)); b.click() },
    has(text){ return document.body.innerText.includes(text) },
    // 页面里 FundBillDrawer 用 v-show 常驻一个隐藏 .bill-drawer-mask，只认真正可见的那个
    drawer(){ return [...document.querySelectorAll('.bill-drawer-mask')].find(el=>{const r=el.getBoundingClientRect();return r.width>0&&r.height>0}) },
    setNative(el,v){ const proto = el.tagName==='SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(proto,'value').set.call(el,v); el.dispatchEvent(new Event('change',{bubbles:true})); el.dispatchEvent(new Event('input',{bubbles:true})) },
    // 收款明细第一行：[资金账户select, 金额input, 备注input]
    detailRow(i=0){ return [...document.querySelectorAll('.detail-scroll tbody tr')][i] },
    // 按单号找列表行
    rowOf(no){ return [...document.querySelectorAll('.pro-table tbody tr, .table-wrap tbody tr, table tbody tr')].find(tr=>tr.textContent.includes(no)) },
  }`
  async function install() { await ev(HELPERS) }
  async function shot(name) {
    const r = await cdp('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.result.data, 'base64'))
    log('截图', name + '.png')
  }
  const lastDialog = () => dialogs[dialogs.length - 1] || ''

  // ---------- 1. 登录 ----------
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

  // ---------- 2. 收款单列表：先把范围收敛到 KTEST1 ----------
  await cdp('Page.navigate', { url: BASE + '/receipt-payment' })
  await sleep(500)
  await install()
  await ev(`await __T.waitFor(()=>__T.has('收款单号'))`)
  log('列表过滤 往来单位=KTEST1（隔离夹具客户）')
  await ev(`
    const fi = [...document.querySelectorAll('.query-inline .fi')].find(d=>d.querySelector('label')?.textContent.trim()==='往来单位')
    __T.setNative(fi.querySelector('input'),'KTEST1')
    await __T.clickBtn('查询')
    await __T.sleep(700)
  `)
  // 全新验证库 KTEST1 没有任何收款单
  const body0 = await ev(`return document.querySelector('.pro-table,.table-wrap,main').innerText`)
  assert(/共\s*0\s*条|暂无数据/.test(body0), 'KTEST1 初始不应有收款单：' + body0.slice(0, 200))
  // 收款类型列头存在
  assert(await ev(`return [...document.querySelectorAll('thead th')].some(th=>th.textContent.trim()==='收款类型')`), '列表缺少「收款类型」列头')
  assert(await ev(`return [...document.querySelectorAll('.query-inline label')].some(l=>l.textContent.trim()==='收款类型')`), '查询区缺少「收款类型」过滤')

  // ---------- 3. 新建预收收款 300 ----------
  log('新建抽屉：3 个收款类型单选项，默认应收结算')
  await ev(`await __T.clickBtn('新建')`)
  await ev(`await __T.waitFor(()=>__T.drawer() && __T.drawer().innerText.includes('收款类型'))`)
  const radios = await ev(`return [...document.querySelectorAll('.bill-drawer-mask input[type=radio][value=SETTLE],.bill-drawer-mask input[type=radio][value=ADVANCE],.bill-drawer-mask input[type=radio][value=ADVANCE_REFUND]')].map(r=>r.value)`)
  assert(JSON.stringify(radios) === JSON.stringify(['SETTLE', 'ADVANCE', 'ADVANCE_REFUND']), '收款类型选项异常：' + JSON.stringify(radios))
  assert(await ev(`return document.querySelector('.bill-drawer-mask input[value=SETTLE]').checked`), '默认应选中应收结算')

  log('切到预收收款：往来单位类型强制客户，供应商/往来单位禁用置灰')
  await ev(`document.querySelector('.bill-drawer-mask input[value=ADVANCE]').click()`)
  await ev(`await __T.waitFor(()=>document.querySelector('.bill-drawer-mask input[value=SUPPLIER]').disabled)`)
  assert(await ev(`return document.querySelector('.bill-drawer-mask input[value=CUSTOMER]').checked`), '预收类应强制选中客户')
  assert(await ev(`return document.querySelector('.bill-drawer-mask input[value=SUPPLIER]').disabled && document.querySelector('.bill-drawer-mask input[value=COUNTERPARTY]').disabled`), '供应商/往来单位单选应禁用')
  assert((await ev(`return document.querySelectorAll('.bill-drawer-mask .radio-disabled').length`)) === 2, '应有 2 个置灰标签')

  log('选客户 KTEST1 + 资金账户 + 金额 300，表头/提示文案校验')
  await ev(`
    const d = __T.drawer()
    // 抽屉默认日期取 toISOString() 是 UTC 日期，凌晨会落到昨天（昨日在种子库已日结封单），显式指定本地今天
    const localToday=new Date(Date.now()-new Date().getTimezoneOffset()*60000).toISOString().slice(0,10)
    __T.setNative(d.querySelector('input[type=date]'),localToday)
    await __T.waitFor(()=>[...__T.drawer().querySelectorAll('select')].some(s=>[...s.options].some(o=>o.value==='KTEST1')))
    const partner = [...d.querySelectorAll('select')].find(s=>[...s.options].some(o=>o.value==='KTEST1'))
    __T.setNative(partner,'KTEST1')
    const fund = __T.detailRow().querySelector('select')
    await __T.waitFor(()=>[...fund.options].some(o=>o.value))
    const firstFund = [...fund.options].find(o=>o.value)
    __T.setNative(fund, firstFund.value)
    const amt = __T.detailRow().querySelector('input[type=number]')
    __T.setNative(amt,'300')
    await __T.sleep(150)
  `)
  assert(await ev(`return [...__T.detailRow().closest('table').querySelectorAll('th')].some(th=>th.textContent.includes('预收金额'))`), '金额列头应为「预收金额」')
  assert(await ev(`return __T.drawer().innerText.includes('预收款计入客户预收余额，不核销应收')`), '缺少预收收款说明文案')
  assert(!(await ev(`return !!__T.drawer().querySelector('.advance-hint')`)), '预收收款不应显示余额提示')
  await shot('prd35-m2-01-advance-drawer')

  // ---------- 4. 切预收退款：超额 400 前端拦截 ----------
  log('切预收退款：余额提示 ￥0.00，超额退款被前端拦截')
  await ev(`
    document.querySelector('.bill-drawer-mask input[value=ADVANCE_REFUND]').click()
    // 切换预收类型会清空往来单位，重新选 KTEST1 触发余额查询（客户下拉异步加载，先等选项）
    await __T.waitFor(()=>[...__T.drawer().querySelectorAll('select')].some(s=>[...s.options].some(o=>o.value==='KTEST1')))
    const partner=[...__T.drawer().querySelectorAll('select')].find(s=>[...s.options].some(o=>o.value==='KTEST1'))
    __T.setNative(partner,'KTEST1')
  `)
  await ev(`await __T.waitFor(()=>{const h=__T.drawer().querySelector('.advance-hint'); return h && h.textContent.includes('当前预收余额')})`)
  // 此时尚未审核预收收款，余额应为 0
  let hint = await ev(`return __T.drawer().querySelector('.advance-hint').textContent.trim()`)
  assert(/￥0\.00/.test(hint), '审核前预收余额提示应为 ￥0.00：' + hint)
  assert(await ev(`return [...__T.detailRow().closest('table').querySelectorAll('th')].some(th=>th.textContent.includes('退款金额'))`), '金额列头应为「退款金额」')
  await ev(`__T.setNative(__T.detailRow().querySelector('input[type=number]'),'400'); await __T.sleep(150)`)
  assert(await ev(`return __T.drawer().querySelector('.advance-hint').classList.contains('advance-hint-warn')`), '超额时提示应为橙色警告态')
  await shot('prd35-m2-02-overrefund-block')
  await ev(`[...__T.drawer().querySelectorAll('button')].find(b=>b.textContent.trim()==='保存').click()`)
  // CDP 已自动确认弹窗，消息收集在 node 侧 dialogs，轮询等待
  for (let i = 0; i < 30 && dialogs.length === 0; i++) await sleep(100)
  assert(/预收余额不足/.test(lastDialog()), '超额退款应被前端拦截并提示预收余额不足，实际弹窗：' + lastDialog())
  log('前端拦截文案：', lastDialog())
  assert(await ev(`return !!__T.drawer()`), '拦截后抽屉不应关闭')

  // ---------- 5. 切回预收收款保存并审核 ----------
  log('切回预收收款（金额改 300）保存')
  await ev(`
    document.querySelector('.bill-drawer-mask input[value=ADVANCE]').click()
    await __T.waitFor(()=>[...__T.drawer().querySelectorAll('select')].some(s=>[...s.options].some(o=>o.value==='KTEST1')))
    const partner=[...__T.drawer().querySelectorAll('select')].find(s=>[...s.options].some(o=>o.value==='KTEST1'))
    __T.setNative(partner,'KTEST1')
    __T.setNative(__T.detailRow().querySelector('input[type=number]'),'300')
    ;[...__T.drawer().querySelectorAll('button')].find(b=>b.textContent.trim()==='保存').click()
  `)
  // 诊断：未关闭时打印弹窗与抽屉内错误文案
  for (let i = 0; i < 15; i++) {
    await sleep(200)
    if (!(await ev(`return !!__T.drawer()`))) break
  }
  if (await ev(`return !!__T.drawer()`)) {
    log('DEBUG 最后弹窗：', lastDialog())
    log('DEBUG 抽屉错误：', await ev(`return [...__T.drawer().querySelectorAll('*')].map(e=>e.textContent).filter(t=>t&&t.length<60&&/失败|错误|请|不足/.test(t)).slice(0,5)`))
  }
  await ev(`await __T.waitFor(()=>!__T.drawer())`)
  await ev(`await __T.waitFor(()=>/成功/.test(document.querySelector('.toast-inline')?.textContent||''))`)
  log('保存反馈：', await ev(`return document.querySelector('.toast-inline').textContent`))
  // 直接读「收款单号」单元格（跨行文本会与相邻日期列粘连，不能整行正则）
  const noAdv = await ev(`
    await __T.waitFor(()=>{ const i=[...document.querySelectorAll('thead th')].findIndex(th=>th.textContent.trim()==='收款单号'); const tr=[...document.querySelectorAll('table tbody tr')].find(t=>/SK/.test(t.children[i]?.textContent||'')); return tr })
    const i=[...document.querySelectorAll('thead th')].findIndex(th=>th.textContent.trim()==='收款单号')
    return [...document.querySelectorAll('table tbody tr')].find(t=>/SK/.test(t.children[i]?.textContent||'')).children[i].textContent.trim()
  `)
  log('预收收款单号：', noAdv)
  assert(await ev(`const tr=__T.rowOf('${noAdv}'); return tr.textContent.includes('预收收款')`), '收款类型列应显示「预收收款」')
  assert(await ev(`const tr=__T.rowOf('${noAdv}'); return tr.textContent.includes('待审核')`), '新单应为待审核')
  // 核销金额列显示「—」（预收类不参与核销）
  {
    const cells = await ev(`return [...__T.rowOf('${noAdv}').children].map(td=>td.textContent.trim())`)
    const headers = await ev(`return [...document.querySelectorAll('thead th')].map(th=>th.textContent.trim())`)
    const ix = headers.indexOf('核销金额')
    assert(ix >= 0 && cells[ix] === '—', `预收行核销金额应为「—」，实际：${ix >= 0 ? cells[ix] : '无核销金额列'}`)
  }

  log('行内审核')
  const auditDlg = dialogs.length
  await ev(`
    if(!window.__confirmWrapped){ window.__confirmResults=[]; const __origConfirm=window.confirm; window.confirm=(m)=>{const r=__origConfirm(m); window.__confirmResults.push(String(r)); return r}; window.__confirmWrapped=true }
    const b=[...__T.rowOf('${noAdv}').querySelectorAll('button')].find(b=>b.textContent.trim()==='审核')
    if(!b) throw new Error('行内没有审核按钮：'+__T.rowOf('${noAdv}').textContent.slice(0,200))
    b.click()
  `)
  // toast 仅存活 2s，轮询时把文案快照到 node 侧，成功/失败都能拿到。
  // 冷启后审核含 GL 事件/凭证可能数秒，窗口放到 15s；toast 错过时以行状态「已审核」为准
  let auditToast = ''
  let audited = false
  for (let i = 0; i < 100; i++) {
    await sleep(150)
    auditToast = await ev(`return document.querySelector('.toast-inline')?.textContent||''`) || auditToast
    audited = await ev(`return __T.rowOf('${noAdv}').textContent.includes('已审核')`)
    if (/审核成功|审核失败/.test(auditToast) || audited) break
  }
  log('DEBUG confirm 返回：', await ev(`return window.__confirmResults`), '| toast：', auditToast || '(无)', '| 已审核：', audited)
  assert(audited || /审核成功/.test(auditToast), '预收收款审核未成功：' + auditToast)
  await ev(`await __T.waitFor(()=>__T.rowOf('${noAdv}').textContent.includes('已审核'))`)
  assert(await ev(`return [...__T.rowOf('${noAdv}').querySelectorAll('button')].some(b=>b.textContent.trim()==='取消审核')`), '已审核行应出现「取消审核」')
  await shot('prd35-m2-03-advance-audited')

  // ---------- 6. 客户账户：预收余额 300.00 ----------
  log('客户账户页校验预收余额 300.00')
  await cdp('Page.navigate', { url: BASE + '/finance/customer-account' })
  await sleep(500)
  await install()
  await ev(`await __T.waitFor(()=>__T.has('KTEST1'))`)
  {
    const headers = await ev(`return [...document.querySelectorAll('thead th')].map(th=>th.textContent.trim())`)
    const cells = await ev(`return [...document.querySelectorAll('table tbody tr')].find(tr=>tr.textContent.includes('KTEST1')) && [...[...document.querySelectorAll('table tbody tr')].find(tr=>tr.textContent.includes('KTEST1')).children].map(td=>td.textContent.trim())`)
    const ix = headers.findIndex(h=>h.includes('预收余额'))
    assert(ix >= 0, '客户账户列表缺少预收余额列')
    assert(cells[ix] === '300.00', `预收余额应为 300.00，实际 ${cells[ix]}`)
    log('预收余额单元格：', cells[ix])
  }
  await shot('prd35-m2-04-account-advance300')

  // ---------- 7. 预收退款 120 ----------
  log('回收款单列表：新建预收退款，余额提示 ￥300.00')
  await cdp('Page.navigate', { url: BASE + '/receipt-payment' })
  await sleep(500)
  await install()
  await ev(`await __T.waitFor(()=>__T.rowOf('${noAdv}'))`)
  await ev(`await __T.clickBtn('新建')`)
  await ev(`await __T.waitFor(()=>__T.drawer())`)
  await ev(`
    document.querySelector('.bill-drawer-mask input[value=ADVANCE_REFUND]').click()
    const d=__T.drawer()
    // 显式指定本地今天（默认 UTC 日期在凌晨会落到昨天，昨日已日结封单）
    const localToday=new Date(Date.now()-new Date().getTimezoneOffset()*60000).toISOString().slice(0,10)
    __T.setNative(d.querySelector('input[type=date]'),localToday)
    // 客户下拉异步加载，等 KTEST1 选项出现再选
    await __T.waitFor(()=>[...__T.drawer().querySelectorAll('select')].some(s=>[...s.options].some(o=>o.value==='KTEST1')))
    const partner=[...d.querySelectorAll('select')].find(s=>[...s.options].some(o=>o.value==='KTEST1'))
    __T.setNative(partner,'KTEST1')
    await __T.waitFor(()=>{const h=__T.drawer().querySelector('.advance-hint');return h && /￥300\\.00/.test(h.textContent)})
    const fund=__T.detailRow().querySelector('select')
    __T.setNative(fund,[...fund.options].find(o=>o.value).value)
  `)
  hint = await ev(`return __T.drawer().querySelector('.advance-hint').textContent.trim()`)
  assert(/￥300\.00/.test(hint), '退款抽屉余额提示应为 ￥300.00：' + hint)
  await shot('prd35-m2-05-refund-hint')
  await ev(`
    __T.setNative(__T.detailRow().querySelector('input[type=number]'),'120')
    ;[...__T.drawer().querySelectorAll('button')].find(b=>b.textContent.trim()==='保存').click()
  `)
  await ev(`await __T.waitFor(()=>!__T.drawer())`)
  const noRef = await ev(`
    await __T.waitFor(()=>[...document.querySelectorAll('table tbody tr')].some(t=>t.textContent.includes('预收退款')))
    const i=[...document.querySelectorAll('thead th')].findIndex(th=>th.textContent.trim()==='收款单号')
    const tr=[...document.querySelectorAll('table tbody tr')].find(t=>t.textContent.includes('预收退款'))
    return tr.children[i].textContent.trim()
  `)
  log('预收退款单号：', noRef)
  await ev(`[...__T.rowOf('${noRef}').querySelectorAll('button')].find(b=>b.textContent.trim()==='审核').click()`)
  // toast 只活 2s 可能错过，以行状态为准；顺带快照样账文案
  await ev(`await __T.waitFor(()=>__T.rowOf('${noRef}').textContent.includes('已审核'))`)
  const refundAuditToast = await ev(`return document.querySelector('.toast-inline')?.textContent||''`)
  log('退款审核反馈：', refundAuditToast || '(toast 已消失，行状态已审核)')

  // ---------- 8. 守卫：预收收款被退款占用，禁止反审核 ----------
  log('预收收款单反审核应被守卫（已被退款使用）')
  const dlgBefore = dialogs.length
  await ev(`[...__T.rowOf('${noAdv}').querySelectorAll('button')].find(b=>b.textContent.trim()==='取消审核').click()`)
  for (let i = 0; i < 50; i++) { if (dialogs.length > dlgBefore) break; await sleep(100) }
  // confirm 已被 CDP 自动确认；等错误 toast
  await ev(`await __T.waitFor(()=>/已被预收核销或退款使用/.test(document.body.innerText),10000)`)
  const toastGuard = await ev(`return document.querySelector('.toast-inline').textContent`)
  assert(/已被预收核销或退款使用/.test(toastGuard), '守卫提示文案不符：' + toastGuard)
  log('守卫提示：', toastGuard)
  assert(await ev(`return __T.rowOf('${noAdv}').textContent.includes('已审核')`), '被守卫后收款单应仍为已审核')
  await shot('prd35-m2-06-reverse-guard')

  // ---------- 9. 退款反审核 → 收款单反审核 ----------
  log('退款单反审核（释放占用）')
  await ev(`[...__T.rowOf('${noRef}').querySelectorAll('button')].find(b=>b.textContent.trim()==='取消审核').click()`)
  await ev(`await __T.waitFor(()=>__T.rowOf('${noRef}').textContent.includes('待审核'))`)
  log('收款单反审核')
  await ev(`[...__T.rowOf('${noAdv}').querySelectorAll('button')].find(b=>b.textContent.trim()==='取消审核').click()`)
  await ev(`await __T.waitFor(()=>__T.rowOf('${noAdv}').textContent.includes('待审核'))`)

  // ---------- 10. 编辑 300 → 260，再审核（再审核轮次链路） ----------
  log('编辑收款单 300 → 260 后重新审核')
  await ev(`[...__T.rowOf('${noAdv}').querySelectorAll('button')].find(b=>b.textContent.trim()==='编辑').click()`)
  await ev(`await __T.waitFor(()=>__T.drawer() && __T.drawer().innerText.includes('${noAdv}'))`)
  assert(await ev(`return __T.detailRow().querySelector('input[type=number]').value === '300'`), '编辑回填金额应为 300')
  await ev(`
    __T.setNative(__T.detailRow().querySelector('input[type=number]'),'260')
    ;[...__T.drawer().querySelectorAll('button')].find(b=>b.textContent.trim()==='保存').click()
  `)
  await ev(`await __T.waitFor(()=>!__T.drawer())`)
  // 通用列表金额列按原值渲染（后端给数字 260 就显示 260，全模块既有行为，非 M2 回归）
  await ev(`await __T.waitFor(()=>{ const i=[...document.querySelectorAll('thead th')].findIndex(th=>th.textContent.trim()==='收款金额'); return __T.rowOf('${noAdv}').children[i]?.textContent.trim()==='260' })`)
  await ev(`[...__T.rowOf('${noAdv}').querySelectorAll('button')].find(b=>b.textContent.trim()==='审核').click()`)
  await ev(`await __T.waitFor(()=>__T.rowOf('${noAdv}').textContent.includes('已审核'))`)

  // ---------- 11. 列表过滤：收款类型=预收退款 ----------
  log('过滤 收款类型=预收退款：只剩退款行')
  await ev(`
    const fi=[...document.querySelectorAll('.query-inline .fi')].find(d=>d.querySelector('label')?.textContent.trim()==='收款类型')
    __T.setNative(fi.querySelector('select'),'预收退款')
    await __T.clickBtn('查询')
    await __T.sleep(800)
  `)
  {
    const rows = await ev(`return [...document.querySelectorAll('table tbody tr')].filter(tr=>/SK/.test(tr.textContent)).map(tr=>tr.textContent)`)
    assert(rows.length === 1 && rows[0].includes('预收退款') && rows[0].includes(noRef), '预收退款过滤结果异常：' + JSON.stringify(rows))
  }
  await shot('prd35-m2-07-filter-refund')

  log('核销状态=未核销：后端自动排除预收类（API 校验；该筛选位在「展开更多」内）')
  // 现状：QueryBar 首屏只露前 4 个筛选，超出的（含核销状态）收进「展开更多」，
  // 而该弹窗仅文案罗列、不能录入（全模块既有缺陷，已记入 M2 优化记录待统一改造）。
  // 截图留证展开入口，筛选语义走页面内真实 HTTP 调用校验。
  await ev(`await __T.clickBtn('重置'); await __T.sleep(300)`)
  await ev(`await __T.clickBtn('展开更多'); await __T.sleep(200)`)
  const moreText = await ev(`return document.querySelector('.modal-lite')?.innerText || '(无 modal-lite)'`)
  log('DEBUG 展开更多弹窗：', moreText.slice(0, 200))
  assert(moreText.includes('核销状态'), '展开更多应列出核销状态：' + moreText.slice(0, 200))
  await shot('prd35-m2-08-more-filters')
  await ev(`document.querySelector('.modal-lite .btn')?.click(); await __T.sleep(100)`)
  {
    const resp = await ev(`
      const r = await fetch('/api/finance/receipt/page', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + localStorage.getItem('erp-token') },
        body: JSON.stringify({ pageNo: 1, pageSize: 200, filters: { reconcileStatus: '未核销' } }),
      })
      return await r.json()
    `)
    const rows = resp?.data?.records || []
    const bad = rows.filter(r => r.receiptType !== 'SETTLE')
    assert(bad.length === 0, '核销状态筛选应只返回应收结算单，发现预收类：' + bad.map(r => r.receiptNo).join(','))
    assert(!rows.some(r => r.receiptNo === '${noAdv}' || r.receiptNo === '${noRef}'), '预收类单据不应出现在核销状态过滤结果中')
    log('核销状态=未核销 返回', rows.length, '条，全部 SETTLE')
  }

  log('🎉 PRD-35 M2 浏览器真实交互验收全部通过')
  edge.kill()
  ws.close()
  process.exit(0)
}

main().catch(e => {
  console.error('PRD-35 M2 UI 验收失败 ❌', e)
  try { edge?.kill() } catch {}
  setTimeout(() => process.exit(1), 300)
})
