/**
 * PRD-34 期初 M7：三页面浏览器真实交互验收（Edge headless + CDP，node 原生 WebSocket，无第三方依赖）。
 *
 * 前置（隔离环境，勿连受控 8080/erp-v1）：
 *   1) 全新冒烟库后端跑 8081（package + java -jar，URL 指向 data/erp-smoke-init，排除 Redis 自动配置）；
 *   2) vite 跑 5174 代理 8081：VITE_API_TARGET=http://localhost:8081 npm --prefix frontend run dev -- --port 5174 --strictPort；
 *   3) 先对该库执行 bootstrapMasters('INIT')（任意 init-m*.js 跑一遍登录即建档，或 node -e 调 init-common）。
 *
 * 运行：node development/06-testing/scripts/init-m7-ui-verify.js
 *   UI_BASE 覆盖前端地址（默认 http://localhost:5174），CDP_PORT 默认 9222。
 *
 * 动作：登录 → 库存（手工批次行/编辑/库位级联行/导入弹窗/建账/反建账）→ 应收 → 应付；
 * 8 张截图落 development/06-testing/evidence/。每次运行重建 data/edge-ui-profile。
 */
const { spawn } = require('child_process')
const fs = require('fs')
const path = require('path')

const EDGE = process.env.EDGE_BIN || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = path.join(__dirname, '..', '..', '..', 'data', 'edge-ui-profile')
const SHOT_DIR = path.join(__dirname, '..', 'evidence')
const BASE = process.env.UI_BASE || 'http://localhost:5174'
const CDP_PORT = Number(process.env.CDP_PORT || 9222)
fs.mkdirSync(SHOT_DIR, { recursive: true })
fs.rmSync(PROFILE, { recursive: true, force: true })

const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[ui]', ...a)

let edge
async function main() {
  edge = spawn(EDGE, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${PROFILE}`,
    '--remote-allow-origins=*', '--window-size=1680,1050', 'about:blank',
  ], { stdio: 'ignore' })

  // ---------- CDP ----------
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

  // 页面内公共助手（每次整页导航后需重新注入）
  const HELPERS = `window.__T = {
    sleep: m => new Promise(r=>setTimeout(r,m)),
    async waitFor(fn, ms=10000){ const t=Date.now(); let last; while(Date.now()-t<ms){ try{ const v=fn(); if(v) return v }catch(e){ last=e } await this.sleep(100) } throw new Error('waitFor超时: '+(last&&last.message||'')+' @ '+(fn.toString().slice(0,80))) },
    vis(el){ if(!el) return false; const r=el.getBoundingClientRect(); return r.width>0&&r.height>0 },
    findBtn(text){ return [...document.querySelectorAll('button')].find(b=>this.vis(b)&&b.textContent.replace(/\\s+/g,'').includes(text)) },
    async clickBtn(text){ const b=await this.waitFor(()=>this.findBtn(text)); b.click() },
    field(label){ const lab=[...document.querySelectorAll('.flabel')].find(s=>s.textContent.replace(/\\*/g,'').replace(/\\s+/g,'').startsWith(label)); return lab?lab.closest('.field'):null },
    setNative(el,v){ const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(proto,'value').set.call(el,String(v)); el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})) },
    async setText(label,v){ const f=await this.waitFor(()=>this.field(label)); const el=f.querySelector('input,textarea'); el.focus(); this.setNative(el,v); await this.sleep(60) },
    async setSelect(label,kw){ const f=await this.waitFor(()=>this.field(label)); f.querySelector('.ss-control').click(); await this.sleep(200); const panel=await this.waitFor(()=>document.querySelector('.ss-panel')); const inp=panel.querySelector('input'); inp.focus(); this.setNative(inp,kw); await this.sleep(400); let opt; try{ opt=await this.waitFor(()=>panel.querySelector('.ss-option:not(.ss-option-disabled)'),4000) }catch(e){ const all=[...document.querySelectorAll('.ss-option')].length; throw new Error('下拉无选项 label='+label+' kw='+kw+' panelText='+panel.innerText.slice(0,200)+' allOpts='+all) } opt.click(); await this.sleep(120); return f.querySelector('.ss-value').textContent.trim() },
    async save(){ await this.clickBtn('保存'); await this.sleep(400) },
    has(text){ return document.body.innerText.includes(text) },
  }`
  async function install() { await ev(HELPERS) }

  async function shot(name) {
    const r = await cdp('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.result.data, 'base64'))
    log('截图', name + '.png')
  }
  async function goto(url) {
    await cdp('Page.navigate', { url: BASE + url })
    await sleep(400)
    await install()
    await ev(`await __T.waitFor(()=>document.querySelector('.module-body'))`)
    await sleep(600)
  }
  async function assertPage(text) {
    const ok = await ev(`return __T.has(${JSON.stringify(text)})`)
    if (!ok) throw new Error(`页面应含「${text}」，实际：` + (await ev(`return document.querySelector('.page-ops b')?.textContent||document.title`)))
    log('页面校验通过：' + text)
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

  // ---------- 2. 库存期初 ----------
  await goto('/inventory/init-stock')
  await assertPage('库存期初初始化')
  log('新增 BATCH 手工行：GINIT/WHINIT 10@8 批号 UIB1')
  await ev(`await __T.clickBtn('新增行')`)
  await ev(`await __T.waitFor(()=>__T.field('商品编码'))`)
  await ev(`const v=await __T.setSelect('商品编码','GINIT'); if(!v.includes('GINIT')) throw new Error('商品选中异常 '+v)`)
  await ev(`const v=await __T.setSelect('仓库编码','WHINIT'); if(!v.includes('WHINIT')) throw new Error('仓库选中异常 '+v)`)
  await ev(`await __T.setText('批号','UIB1'); await __T.setText('数量','10'); await __T.setText('成本单价','8')`)
  await ev(`await __T.save()`)
  await ev(`await __T.waitFor(()=>__T.has('UIB1'))`)
  log('编辑该行：数量 10 → 12')
  await ev(`[...document.querySelectorAll('a.link')].find(a=>a.textContent==='编辑').click()`)
  await ev(`await __T.waitFor(()=>__T.field('商品编码')); await __T.setText('数量','12')`)
  await ev(`await __T.save(); await __T.waitFor(()=>__T.has('期初行已更新'))`)
  if (!(await ev(`return __T.has('12.0000')`))) throw new Error('编辑后数量未生效')
  log('切到按库位页签，新增 BIN 行：BININIT 6@7 批号 UIBIN1')
  await ev(`await __T.clickBtn('按库位'); await __T.sleep(300); await __T.clickBtn('新增行'); await __T.waitFor(()=>__T.field('库位编码'))`)
  await ev(`await __T.setSelect('商品编码','GINIT'); await __T.setSelect('仓库编码','WHINIT'); await __T.sleep(800); const v=await __T.setSelect('库位编码','BININIT'); if(!v.includes('BININIT')) throw new Error('库位选中异常 '+v)`)
  await ev(`await __T.setText('批号','UIBIN1'); await __T.setText('数量','6'); await __T.setText('成本单价','7')`)
  await ev(`await __T.save(); await __T.waitFor(()=>__T.has('UIBIN1'))`)
  log('打开导入弹窗校验真实模板入口')
  await ev(`await __T.clickBtn('导入'); await __T.waitFor(()=>__T.has('下载模板'))`)
  await shot('01-stock-import-dialog')
  await ev(`document.querySelector('.close-btn')?.click(); await __T.sleep(300)`)
  log('期初建账（自动确认 confirm）')
  await ev(`await __T.clickBtn('期初建账')`)
  await ev(`await __T.waitFor(()=>__T.has('已建账'),12000)`)
  await sleep(800)
  await shot('02-stock-posted')
  log('反建账弹窗 + 原因')
  await ev(`await __T.clickBtn('反建账'); await __T.waitFor(()=>__T.has('反建账原因'))`)
  await ev(`const ta=document.querySelector('.reason-label textarea'); ta.focus(); __T.setNative(ta,'UI 验收：浏览器真实操作反建账')`)
  await shot('03-stock-reverse-dialog')
  await ev(`await __T.clickBtn('确认反建账')`)
  await ev(`await __T.waitFor(()=>__T.has('已反建账'),12000)`)
  await sleep(500)
  await shot('04-stock-reversed')
  log('库存页面交互全过')

  // ---------- 3. 应收期初 ----------
  await goto('/finance/init-ar')
  await assertPage('应收期初初始化')
  await ev(`await __T.clickBtn('新增行'); await __T.waitFor(()=>__T.field('客户编码'))`)
  await ev(`const v=await __T.setSelect('客户编码','CUSINIT'); if(!v.includes('CUSINIT')) throw new Error('客户选中异常 '+v)`)
  await ev(`await __T.setText('原单据号','UIAR1'); await __T.setText('应收金额','500')`)
  await ev(`await __T.save(); await __T.waitFor(()=>__T.has('UIAR1'))`)
  await shot('05-ar-line')
  await ev(`await __T.clickBtn('期初建账'); await __T.waitFor(()=>__T.has('已建账'),12000)`)
  await sleep(600)
  await shot('06-ar-posted')
  await ev(`await __T.clickBtn('反建账'); await __T.waitFor(()=>__T.has('反建账原因')); const ta=document.querySelector('.reason-label textarea'); ta.focus(); __T.setNative(ta,'UI 验收反建账'); await __T.clickBtn('确认反建账')`)
  await ev(`await __T.waitFor(()=>__T.has('已反建账'),12000)`)
  log('应收页面交互全过')

  // ---------- 4. 应付期初 ----------
  await goto('/finance/init-ap')
  await assertPage('应付期初初始化')
  await ev(`await __T.clickBtn('新增行'); await __T.waitFor(()=>__T.field('供应商编码'))`)
  await ev(`const v=await __T.setSelect('供应商编码','SUPINIT'); if(!v.includes('SUPINIT')) throw new Error('供应商选中异常 '+v)`)
  await ev(`await __T.setText('原单据号','UIAP1'); await __T.setText('应付金额','300')`)
  await ev(`await __T.save(); await __T.waitFor(()=>__T.has('UIAP1'))`)
  await shot('07-ap-line')
  await ev(`await __T.clickBtn('期初建账'); await __T.waitFor(()=>__T.has('已建账'),12000)`)
  await sleep(600)
  await shot('08-ap-posted')
  await ev(`await __T.clickBtn('反建账'); await __T.waitFor(()=>__T.has('反建账原因')); const ta=document.querySelector('.reason-label textarea'); ta.focus(); __T.setNative(ta,'UI 验收反建账'); await __T.clickBtn('确认反建账')`)
  await ev(`await __T.waitFor(()=>__T.has('已反建账'),12000)`)
  log('应付页面交互全过')

  log('🎉 浏览器真实交互验收全部通过')
  edge.kill()
  ws.close()
  process.exit(0)
}

main().catch(e => {
  console.error('UI 验收失败 ❌', e)
  try { edge?.kill() } catch {}
  setTimeout(() => process.exit(1), 300)
})
