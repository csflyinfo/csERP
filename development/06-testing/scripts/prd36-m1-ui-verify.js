/**
 * PRD-36 M1：供应商账户页面浏览器真实交互验收（Edge headless + CDP，node 原生 WebSocket，无第三方依赖）。
 *
 * 前置（隔离环境，勿连受控 8080/erp-v1）：
 *   1) 8082 后端使用 verify-prd36-m1 验证库（夹具 prd36-m1-fixtures.sql，V128 已在真值上重跑）；
 *   2) vite 跑 5174 代理 8082：VITE_API_TARGET=http://localhost:8082 npm --prefix frontend run dev -- --port 5174 --strictPort。
 *
 * 运行：node development/06-testing/scripts/prd36-m1-ui-verify.js
 *
 * 动作：登录 → 供应商账户列表（S001/1100.00、档案快照、hideZero 过滤）→ 点应付余额
 *       → 往来流水抽屉（4 条流水、三 tab、结算徽标、未结算过滤、快捷期间、预付/费用空账）
 *       → 数据修复（confirm 自动确认、幂等反馈）。截图落 development/06-testing/evidence/。
 */
const { spawn } = require('child_process')
const fs = require('fs')
const path = require('path')

const EDGE = process.env.EDGE_BIN || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = path.join(__dirname, '..', '..', '..', 'data', 'edge-ui-profile-prd36')
const SHOT_DIR = path.join(__dirname, '..', 'evidence')
const BASE = process.env.UI_BASE || 'http://localhost:5174'
const CDP_PORT = Number(process.env.CDP_PORT || 9224)
fs.mkdirSync(SHOT_DIR, { recursive: true })
fs.rmSync(PROFILE, { recursive: true, force: true })

const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[prd36-ui]', ...a)

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

  // ---------- 2. 供应商账户列表 ----------
  await cdp('Page.navigate', { url: BASE + '/finance/supplier-account' })
  await sleep(500)
  await install()
  await ev(`await __T.waitFor(()=>__T.has('供应商编号'))`)
  log('列表：校验 S001 / 1,100.00 / 张采购 / 月结 / 30')
  await ev(`await __T.waitFor(()=>__T.has('S001'))`)
  for (const t of ['甲厂家', '1,100.00', '张采购', '月结', '费用余额']) {
    if (!(await ev(`return __T.has('${t}')`))) throw new Error('列表缺少文案：' + t)
  }
  await shot('prd36-m1-01-account-list')

  log('勾选 hideZero + 查询，S001 仍在、S002 被隐藏')
  await ev(`document.querySelector('.check-line input').checked = true; document.querySelector('.check-line input').dispatchEvent(new Event('change',{bubbles:true})); await __T.clickBtn('查询'); await __T.sleep(600)`)
  if (!(await ev(`return __T.has('S001')`))) throw new Error('有余额供应商被 hideZero 过滤掉')
  if (await ev(`return __T.has('S002')`) && /S002/.test(document?.body?.innerText || '')) throw new Error('零余额供应商未被 hideZero 隐藏')

  // ---------- 3. 应付流水抽屉 ----------
  log('点击应付余额 1,100.00 打开往来流水抽屉')
  // 复位 hideZero（S002 行回来与否不影响后续，只用 S001）
  await ev(`document.querySelector('.check-line input').checked = false; document.querySelector('.check-line input').dispatchEvent(new Event('change',{bubbles:true})); await __T.clickBtn('查询'); await __T.sleep(500)`)
  await ev(`[...document.querySelectorAll('a.link-num')].find(a=>a.textContent.trim()==='1,100.00').click()`)
  await ev(`await __T.waitFor(()=>__T.has('往来流水查询'))`)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.flow-table tbody tr').length===4,10000)`)
  const apText = await ev(`return document.querySelector('.flow-modal').innerText`)
  for (const t of ['甲厂家', '应付', '预付', '费用', 'CGSH-TEST-1', 'CGTH-TEST-1', 'QCAP-TEST-1',
                   'FK-TEST-1', '采购收货', '采购退货', '期初建账', '付款结算', '部分结算']) {
    if (!apText.includes(t)) throw new Error('应付抽屉缺少文案：' + t)
  }
  // 当前余额
  if (!apText.includes('1,100.00')) throw new Error('余额条当前余额未显示 1,100.00')
  await shot('prd36-m1-02-ap-flow')

  log('结算标志选「未结算」+ 查询，剩 2 行（退货红字 + 期初）')
  await ev(`const sel=document.querySelector('.flow-modal .cond select'); sel.value='未结算'; sel.dispatchEvent(new Event('change',{bubbles:true})); [...document.querySelectorAll('.flow-modal button')].find(b=>b.textContent.replace(/\\s+/g,'').includes('查询')).click()`)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.flow-table tbody tr').length===2)`)

  log('快捷期间「本月」：仅期初 1 行（期初流水 occurred_at 取迁移时刻）；「上月」：3 行')
  await ev(`const sel=document.querySelector('.flow-modal .cond select'); sel.value=''; sel.dispatchEvent(new Event('change',{bubbles:true}))`)
  await ev(`[...document.querySelectorAll('.quick a')].find(a=>a.textContent==='本月').click(); await __T.sleep(800)`)
  const monthRows = await ev(`return document.querySelectorAll('.flow-table tbody tr').length`)
  if (monthRows !== 1) throw new Error('本月窗口应为 1 行（期初），实际 ' + monthRows)
  await ev(`[...document.querySelectorAll('.quick a')].find(a=>a.textContent==='上月').click(); await __T.sleep(800)`)
  const lastMonthRows = await ev(`return document.querySelectorAll('.flow-table tbody tr').length`)
  if (lastMonthRows !== 3) throw new Error('上月窗口应为 3 行，实际 ' + lastMonthRows)

  // ---------- 4. 预付 / 费用 tab（空账） ----------
  log('切到预付 tab：暂无流水、当前预付余额 0.00')
  await ev(`[...document.querySelectorAll('.flow-modal .tab')].find(b=>b.textContent.trim()==='预付').click()`)
  await ev(`await __T.waitFor(()=>__T.has('暂无流水'))`)
  let bar = await ev(`return document.querySelector('.balance-bar').innerText`)
  if (!bar.includes('0.00')) throw new Error('预付当前余额应为 0.00：' + bar)
  log('切到费用 tab：暂无流水、当前费用余额 0.00')
  await ev(`[...document.querySelectorAll('.flow-modal .tab')].find(b=>b.textContent.trim()==='费用').click()`)
  await ev(`await __T.waitFor(()=>__T.has('暂无流水'))`)
  bar = await ev(`return document.querySelector('.balance-bar').innerText`)
  if (!bar.includes('0.00')) throw new Error('费用当前余额应为 0.00：' + bar)
  await shot('prd36-m1-03-expense-empty')
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
  await shot('prd36-m1-04-repair-feedback')

  log('🎉 PRD-36 M1 浏览器真实交互验收全部通过')
  edge.kill()
  ws.close()
  process.exit(0)
}

main().catch(e => {
  console.error('PRD-36 M1 UI 验收失败 ❌', e)
  try { edge?.kill() } catch {}
  setTimeout(() => process.exit(1), 300)
})
