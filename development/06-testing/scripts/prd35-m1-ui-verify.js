/**
 * PRD-35 M1：客户账户页面浏览器真实交互验收（Edge headless + CDP，node 原生 WebSocket，无第三方依赖）。
 *
 * 前置（隔离环境，勿连受控 8080/erp-v1）：
 *   1) 8082 后端使用已含 PRD-35 测试真值的验证库（KTEST1：2 笔应收 100/200、1 笔收款核销 50），
 *      且 /finance/customer-account/repair 已跑过一次；
 *   2) vite 跑 5174 代理 8082：VITE_API_TARGET=http://localhost:8082 npm --prefix frontend run dev -- --port 5174 --strictPort。
 *
 * 运行：node development/06-testing/scripts/prd35-m1-ui-verify.js
 *   UI_BASE 覆盖前端地址（默认 http://localhost:5174），CDP_PORT 默认 9222。
 *
 * 动作：登录 → 客户账户列表（KTEST1/250.00、账期快照、余额为0过滤）→ 点应收余额
 *       → 往来流水抽屉（3 条流水、滚存 [250,300,100]、结算徽标、未结算过滤、预收空账、快捷期间）
 *       → 数据修复（confirm 自动确认、幂等反馈）。截图落 development/06-testing/evidence/。
 */
const { spawn } = require('child_process')
const fs = require('fs')
const path = require('path')

const EDGE = process.env.EDGE_BIN || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = path.join(__dirname, '..', '..', '..', 'data', 'edge-ui-profile-prd35')
const SHOT_DIR = path.join(__dirname, '..', 'evidence')
const BASE = process.env.UI_BASE || 'http://localhost:5174'
const CDP_PORT = Number(process.env.CDP_PORT || 9222)
fs.mkdirSync(SHOT_DIR, { recursive: true })
fs.rmSync(PROFILE, { recursive: true, force: true })

const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[prd35-ui]', ...a)

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
  ws.onmessage = async e => {
    const m = JSON.parse(e.data)
    if (m.method === 'Page.javascriptDialogOpening') {
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
    async waitFor(fn, ms=10000){ const t=Date.now(); let last; while(Date.now()-t<ms){ try{ const v=fn(); if(v) return v }catch(e){ last=e } await this.sleep(100) } throw new Error('waitFor超时: '+(last&&last.message||'')+' @ '+(fn.toString().slice(0,100))) },
    vis(el){ if(!el) return false; const r=el.getBoundingClientRect(); return r.width>0&&r.height>0 },
    findBtn(text){ return [...document.querySelectorAll('button')].find(b=>this.vis(b)&&b.textContent.replace(/\\s+/g,'').includes(text)) },
    async clickBtn(text){ const b=await this.waitFor(()=>this.findBtn(text)); b.click() },
    has(text){ return document.body.innerText.includes(text) },
  }`
  async function install() { await ev(HELPERS) }
  async function shot(name) {
    const r = await cdp('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.result.data, 'base64'))
    log('截图', name + '.png')
  }

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

  // ---------- 2. 客户账户列表 ----------
  await cdp('Page.navigate', { url: BASE + '/finance/customer-account' })
  await sleep(500)
  await install()
  await ev(`await __T.waitFor(()=>__T.has('客户编号'))`)
  log('列表：校验 KTEST1 / 250.00 / 月结')
  await ev(`await __T.waitFor(()=>__T.has('KTEST1'))`)
  if (!(await ev(`return __T.has('250.00')`))) throw new Error('应收余额 250.00 未显示')
  if (!(await ev(`return __T.has('测试客户甲')`))) throw new Error('客户名称未显示')
  if (!(await ev(`return __T.has('月结')`))) throw new Error('账期快照未显示')
  await shot('prd35-01-account-list')

  log('勾选「本页不体现余额为 0」+ 查询，KTEST1 仍在')
  await ev(`document.querySelector('.check-line input').checked = true; document.querySelector('.check-line input').dispatchEvent(new Event('change',{bubbles:true})); await __T.clickBtn('查询'); await __T.sleep(600)`)
  if (!(await ev(`return __T.has('KTEST1')`))) throw new Error('有余额客户被 hideZero 过滤掉')

  // ---------- 3. 应收流水抽屉 ----------
  log('点击应收余额 250.00 打开往来流水抽屉')
  await ev(`[...document.querySelectorAll('a.link-num')].find(a=>a.textContent.trim()==='250.00').click()`)
  await ev(`await __T.waitFor(()=>__T.has('往来流水查询'))`)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.flow-table tbody tr').length===3,10000)`)
  const arText = await ev(`return document.querySelector('.flow-modal').innerText`)
  for (const t of ['测试客户甲', '应收', '预收', 'XSFHTEST001', 'XSFHTEST002', 'SKTEST001', '收款结算', '部分结算', '未结算', 'YHTEST001']) {
    if (!arText.includes(t)) throw new Error('应收抽屉缺少文案：' + t)
  }
  // 滚存列：DESC 顺序 250 / 300 / 100
  const bals = await ev(`return [...document.querySelectorAll('.flow-table tbody tr')].map(tr=>tr.children[6].textContent.trim())`)
  if (JSON.stringify(bals) !== JSON.stringify(['250.00', '300.00', '100.00'])) {
    throw new Error('滚存账户金额列顺序错误：' + JSON.stringify(bals))
  }
  log('滚存账户金额列', JSON.stringify(bals))
  if (!arText.includes('250.00')) throw new Error('余额条当前余额未显示 250.00')
  await shot('prd35-02-ar-flow')

  log('结算标志选「未结算」+ 查询，仅 XSFHTEST001（AR001）一行')
  await ev(`const sel=document.querySelector('.flow-modal .cond select'); sel.value='未结算'; sel.dispatchEvent(new Event('change',{bubbles:true})); [...document.querySelectorAll('.flow-modal button')].find(b=>b.textContent.replace(/\\s+/g,'').includes('查询')).click()`)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.flow-table tbody tr').length===1)`)
  const unsetText = await ev(`return document.querySelector('.flow-table tbody tr').innerText`)
  if (!unsetText.includes('XSFHTEST001') || unsetText.includes('XSFHTEST002')) {
    throw new Error('未结算过滤结果错误（应仅 XSFHTEST001）：' + unsetText)
  }

  log('快捷期间「本月」3 行、「上月」0 行且此前余额 250.00')
  // 先把结算标志复位为「全部」（快捷期间不清结算标志）
  await ev(`const sel=document.querySelector('.flow-modal .cond select'); sel.value=''; sel.dispatchEvent(new Event('change',{bubbles:true}))`)
  await ev(`[...document.querySelectorAll('.quick a')].find(a=>a.textContent==='本月').click(); await __T.sleep(800)`)
  const monthRows = await ev(`return document.querySelectorAll('.flow-table tbody tr').length`)
  if (monthRows !== 3) throw new Error('本月窗口应为 3 行，实际 ' + monthRows)
  await ev(`[...document.querySelectorAll('.quick a')].find(a=>a.textContent==='上月').click(); await __T.sleep(800)`)
  const lastMonthRows = await ev(`return document.querySelectorAll('.flow-table tbody tr').length`)
  if (lastMonthRows !== 1 || !await ev(`return __T.has('暂无流水')`)) {
    throw new Error('上月窗口应为 0 行空态，实际行数 ' + lastMonthRows)
  }
  if (!(await ev(`return document.querySelector('.balance-bar').innerText.includes('250.00')`))) {
    throw new Error('上月窗口此前/当前余额应展示 250.00')
  }

  // ---------- 4. 预收 tab（空账） ----------
  log('切到预收 tab：暂无流水、当前预收余额 0.00')
  await ev(`[...document.querySelectorAll('.flow-modal .tab')].find(b=>b.textContent.trim()==='预收').click()`)
  await ev(`await __T.waitFor(()=>__T.has('暂无流水'))`)
  const advText = await ev(`return document.querySelector('.flow-modal .balance-bar').innerText`)
  if (!advText.includes('0.00')) throw new Error('预收当前余额应为 0.00：' + advText)
  await shot('prd35-03-advance-empty')
  await ev(`document.querySelector('.flow-h .x').click()`)
  await ev(`await __T.waitFor(()=>!document.querySelector('.flow-modal'))`)

  // ---------- 5. 数据修复（幂等） ----------
  log('点数据修复（confirm 自动确认），等待完成反馈')
  const btn = await ev(`const b=__T.findBtn('数据修复'); return b ? b.textContent.trim() : null`)
  if (!btn) throw new Error('SYS_ADMIN 看不到数据修复按钮（权限指令异常）')
  await ev(`await __T.clickBtn('数据修复')`)
  await ev(`await __T.waitFor(()=>/修复完成/.test(document.body.innerText),10000)`)
  const fb = await ev(`return [...document.querySelectorAll('.toast-inline')].map(e=>e.textContent).find(t=>t.includes('修复完成'))`)
  log('修复反馈：', fb)
  if (!/补形成流水 0 条/.test(fb) || !/结算流水 0 条/.test(fb)) throw new Error('二次修复应补缺 0：' + fb)
  await shot('prd35-04-repair-feedback')

  log('🎉 PRD-35 M1 浏览器真实交互验收全部通过')
  edge.kill()
  ws.close()
  process.exit(0)
}

main().catch(e => {
  console.error('PRD-35 M1 UI 验收失败 ❌', e)
  try { edge?.kill() } catch {}
  setTimeout(() => process.exit(1), 300)
})
