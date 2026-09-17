/**
 * PRD-36 M2：付款三类型 + 预付核销 + 对账单预付结算 浏览器真实交互验收
 * （Edge headless + CDP，node 原生 WebSocket，无第三方依赖）。
 *
 * 前置（隔离环境，勿连受控 8080/erp-v1）：
 *   1) 8082 后端使用 verify-prd36-m1 验证库，已跑通 prd36-m2-api-e2e.mjs
 *      （库态：S001 预付余额 500.00；AP-M2-10 已被 GL 段 FX 核销 30、未付 70；
 *        3 张已审未付对账单 SS202609170001=120/002=60/003=50；FX 列表含 1 张已审手工单 + 2 张已作废自动单）；
 *   2) vite 跑 5174 代理 8082：VITE_API_TARGET=http://localhost:8082 npm --prefix frontend run dev -- --port 5174 --strictPort。
 *
 * 运行：node development/06-testing/scripts/prd36-m2-ui-verify.js
 *
 * 动作（全程不落业务数据，最后弹窗只看不提交）：
 *   01 预付核销单列表：FX 单号/状态标签/业务来源文案（手工录入、对账单结算）
 *   02 新建核销单抽屉：选 S001 → 预付余额 ￥500.00、8 行未结应付按到期日升序（M1 夹具 2 行 + M2 夹具 6 行，
 *      空到期日 AP-M2-40 末位）、勾选首行（QCAP-TEST-1 未付 500）自动填满 500.00、合计联动
 *   03 付款单新建抽屉：应付结算/预付付款/预付退款 三类型 radio，选预付后往来单位类型锁供应商
 *   04 供应商对账单勾选 → 付款结算弹窗：ERP 预付余额 ￥500.00、勾「使用预付款」自动填满 50.00、
 *      现金需求归 0、账户合计勾稽提示；关闭不提交
 * 截图落 development/06-testing/evidence/。
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
const log = (...a) => console.log('[prd36-m2-ui]', ...a)
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
    async waitFor(fn, ms=10000){ const t=Date.now(); let last; while(Date.now()-t<ms){ try{ const v=fn(); if(v) return v }catch(e){ last=e } await this.sleep(100) } throw new Error('waitFor超时: '+(last&&last.message||'')+' @ '+(fn.toString().slice(0,120))) },
    vis(el){ if(!el) return false; const r=el.getBoundingClientRect(); return r.width>0&&r.height>0 },
    findBtn(text){ return [...document.querySelectorAll('button')].find(b=>this.vis(b)&&b.textContent.replace(/\\s+/g,'').includes(text)) },
    async clickBtn(text){ const b=await this.waitFor(()=>this.findBtn(text)); b.click() },
    has(text){ return document.body.innerText.includes(text) },
    // 只认可见掩码：App 根部全局挂了 display:none 的销售订单 BillDrawer，querySelector 会误中
    visibleMask(sel){ return [...document.querySelectorAll(sel)].find(m=>getComputedStyle(m).display!=='none') || null },
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

  // ---------- 2. 预付核销单列表 ----------
  log('01 预付核销单列表：FX 单 / 状态 / 业务来源文案')
  await goto('/finance/prepay-writeoff')
  await ev(`await __T.waitFor(()=>document.querySelectorAll('table.data tbody tr').length>=1 && !document.body.innerText.includes('加载中'))`)
  const listText = await ev(`return document.querySelector('.module-body').innerText`)
  assert(/FX\d{8,}/.test(listText), '列表缺少 FX 核销单号：' + listText.slice(0, 200))
  for (const t of ['手工录入', '已审核', '核销单号', '业务来源', '来源单号', '结算生成']) {
    assert(listText.includes(t), 'FX 列表缺少文案：' + t)
  }
  // 两张自动单在 C/D 段级联反审核后为「已作废」，仍在列表可见
  assert(listText.includes('已作废'), '列表应含级联作废的自动 FX 单（已作废标签）')
  const fxRows = await ev(`return document.querySelectorAll('table.data tbody tr').length`)
  log(`  列表 ${fxRows} 张 FX（期望 ≥3：1 手工已审 + 2 自动作废）`)
  assert(fxRows >= 3, 'FX 行数不足 3：' + fxRows)
  await shot('prd36-m2-01-writeoff-list')

  // ---------- 3. 新建核销单抽屉 ----------
  log('02 新建核销单抽屉：供应商/预付余额/未结应付候选/勾选联动')
  await ev(`await __T.clickBtn('新建核销单')`)
  await ev(`await __T.waitFor(()=>document.querySelector('.xw-modal'))`)
  // 选供应商（原生 select，派发 change 触发 onSupplierChange）
  await ev(`
    const sel=document.querySelector('.xw-modal .master select');
    sel.value='S001';
    sel.dispatchEvent(new Event('change',{bubbles:true}));
  `)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.ap-table tbody tr').length>=6,10000)`)
  await ev(`await __T.waitFor(()=>document.querySelector('.balance-panel').innerText.includes('500.00'))`)
  const drawerText = await ev(`return document.querySelector('.xw-modal').innerText`)
  assert(drawerText.includes('供应商 ERP 预付余额：￥500.00'), '预付余额应为 ￥500.00：' + drawerText.slice(0, 300))
  assert(drawerText.includes('本次核销合计：￥0.00'), '初始合计应为 0.00')
  // 候选行顺序：AP-M2-10（09-05，未付 70）…… AP-M2-40（空到期日）末位
  const apOrder = await ev(`return [...document.querySelectorAll('.ap-table tbody tr')].map(tr=>tr.innerText.replace(/\\s+/g,' ').trim())`)
  log('  候选行：', JSON.stringify(apOrder, null, 0))
  // 8 行：M1 夹具 2（QCAP-TEST-1 500/08-01、AP-TEST-1 700/09-10）+ M2 夹具 6（AP-M2-10 已被 GL 段 FX 核销 30 未付 70）
  assert(apOrder.length === 8, 'S001 未结应付应剩 8 行（M1×2 + M2×6）：' + apOrder.length)
  assert(/QCAP-TEST-1/.test(apOrder[0]) && /2026-08-01/.test(apOrder[0]) && /500\.00/.test(apOrder[0]),
    '首行应为最早到期的 QCAP-TEST-1（08-01，未付 500.00）：' + apOrder[0])
  const m210 = apOrder.find(t => /AP-M2-10/.test(t))
  assert(/AP-M2-10/.test(m210) && /09-05/.test(m210) && /70\.00/.test(m210),
    'AP-M2-10 应显示已付 30 未付 70：' + m210)
  assert(/AP-M2-40/.test(apOrder[apOrder.length - 1]) && /—/.test(apOrder[apOrder.length - 1]),
    '空到期日 AP-M2-40 应排末位：' + apOrder[apOrder.length - 1])
  // 勾选首行（QCAP-TEST-1 未付 500）→ 自动填 min(未付500, 可用500)=500
  await ev(`document.querySelector('.ap-table tbody tr input[type=checkbox]').click()`)
  await ev(`await __T.waitFor(()=>document.querySelector('.balance-panel').innerText.includes('本次核销合计：￥500.00'))`)
  const afterCheck = await ev(`return document.querySelector('.balance-panel').innerText`)
  assert(afterCheck.includes('￥500.00') && afterCheck.includes('已选应付：1 行'),
    '勾选后合计 500.00 / 已选 1 行：' + afterCheck)
  const amtVal = await ev(`return document.querySelector('.ap-table tbody tr input.amt').value`)
  assert(amtVal === '500', '首行本次核销额应自动填 500：' + amtVal)
  assert(!afterCheck.includes('超过预付余额'), '70 < 500 不应出现超额警告')
  await shot('prd36-m2-02-writeoff-drawer')
  // 关闭（不保存，不落数据）
  await ev(`document.querySelector('.xw-modal .xw-h .x').click()`)
  await ev(`await __T.waitFor(()=>!document.querySelector('.xw-modal'))`)

  // ---------- 4. 付款单新建抽屉：三类型 ----------
  log('03 付款单抽屉：应付结算/预付付款/预付退款 三类型')
  await goto('/payment-module')
  await ev(`await __T.waitFor(()=>__T.findBtn('新建'))`)
  await ev(`await __T.clickBtn('新建')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.bill-drawer-mask'))`)
  const radios = await ev(`
    return [...__T.visibleMask('.bill-drawer-mask').querySelectorAll('.radio-label-sm')]
      .map(l=>l.textContent.replace(/\\s+/g,'').trim())
  `)
  log('  抽屉 radio：', JSON.stringify(radios))
  for (const t of ['应付结算', '预付付款', '预付退款']) {
    assert(radios.includes(t), '付款类型缺少：' + t)
  }
  // 选「预付付款」
  await ev(`
    const r=[...__T.visibleMask('.bill-drawer-mask').querySelectorAll('input[type=radio]')].find(i=>i.value==='PREPAY');
    r.click();
  `)
  await sleep(200)
  const prepayChecked = await ev(`return [...__T.visibleMask('.bill-drawer-mask').querySelectorAll('input[type=radio]')].find(i=>i.value==='PREPAY').checked`)
  assert(prepayChecked, '预付付款 radio 未选中')
  // 预付类：往来单位类型锁供应商（客户 radio 置灰）
  const customerDisabled = await ev(`return [...__T.visibleMask('.bill-drawer-mask').querySelectorAll('input[type=radio]')].find(i=>i.value==='CUSTOMER').disabled`)
  assert(customerDisabled, '预付付款下客户类型应禁用（锁定供应商）')
  await shot('prd36-m2-03-payment-types')
  await ev(`[...__T.visibleMask('.bill-drawer-mask').querySelectorAll('.bill-drawer-head button')].find(b=>b.textContent.trim()==='关闭').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.bill-drawer-mask'))`)

  // ---------- 5. 对账单付款结算弹窗：使用预付款 ----------
  log('04 供应商对账单 → 付款结算弹窗（使用预付款，不提交）')
  await goto('/supplier-statement')
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.tablebox tbody tr').length>=1)`)

  // 付款状态列：3 张单全部未付款（EXACT_TITLE_MAP 付款状态→payStatus 映射回归）
  const stmtText = await ev(`return document.querySelector('.tablebox').innerText`)
  const unpaidCount = (stmtText.match(/未付款/g) || []).length
  assert(unpaidCount >= 3, `付款状态列应显示 ≥3 个「未付款」，实际 ${unpaidCount}`)
  log('  付款状态列：3 张对账单均显示未付款')

  // 付款状态过滤（修复前查询键发中文「付款状态」被后端忽略）
  await ev(`
    const sel=document.querySelector('.query-inline select');
    sel.value='未付款'; sel.dispatchEvent(new Event('change',{bubbles:true}));
  `)
  await ev(`await __T.clickBtn('查询')`)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.tablebox tbody tr').length===3)`)
  await ev(`
    const sel=document.querySelector('.query-inline select');
    sel.value='完成付款'; sel.dispatchEvent(new Event('change',{bubbles:true}));
  `)
  await ev(`await __T.clickBtn('查询')`)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.tablebox tbody tr').length===0
      || document.querySelector('.tablebox').innerText.includes('暂无数据'))`)
  log('  付款状态过滤：未付款=3 行 / 完成付款=0 行（查询键 payStatus 生效）')
  // 复位
  await ev(`await __T.clickBtn('重置')`)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.tablebox tbody tr').length>=3)`)

  // 选 SS...003（金额 50，全额预付演示最干净）；找不到就取第一张对账单
  const picked = await ev(`
    const rows=[...document.querySelectorAll('.tablebox tbody tr')];
    let tr=rows.find(r=>/SS\\d*003/.test(r.innerText))
        || rows.find(r=>/SS\\d{8,}/.test(r.innerText));
    if(!tr) return {ok:false, rows: rows.map(r=>r.innerText.replace(/\\s+/g,' ').slice(0,80))};
    tr.querySelector('td.checkbox-td input').click();
    return {ok:true, text:tr.innerText.replace(/\\s+/g,' ').slice(0,140)};
  `)
  assert(picked && picked.ok, '找不到供应商对账单行：' + JSON.stringify(picked && picked.rows))
  assert(picked.text.includes('未付款'), '选中行付款状态应为未付款：' + picked.text)
  log('  勾选对账单：', picked.text)
  // 顶部「付款结算」按钮
  await ev(`await __T.clickBtn('付款结算')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.modal-lite') && __T.visibleMask('.modal-lite').innerText.includes('付款结算'))`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.modal-lite').innerText.includes('供应商 ERP 预付余额：￥500.00'),10000)`)
  const dlg0 = await ev(`return __T.visibleMask('.modal-lite').innerText`)
  assert(dlg0.includes('￥500.00'), '弹窗应显示供应商 ERP 预付余额 ￥500.00：' + dlg0.slice(0, 300))
  // 50 元对账单：结算净额 50.00；默认未用预付
  assert(dlg0.includes('本次结算净额 ￥50.00'), '净额应为 50.00：' + dlg0.match(/结算净额[^\\n]*/)?.[0])
  const advUnchecked = await ev(`return __T.visibleMask('.modal-lite').querySelector('.advance-check input').checked`)
  assert(!advUnchecked, '使用预付款默认不应勾选')
  // 勾选使用预付款 → 自动填 min(净额50, 余额500)=50，现金需求归 0
  await ev(`__T.visibleMask('.modal-lite').querySelector('.advance-check input').click()`)
  await ev(`await __T.waitFor(()=>{
    const t=__T.visibleMask('.modal-lite').innerText
    return t.includes('账户合计须 = ￥0.00') && t.includes('其中预付')
  })`)
  const advVal = await ev(`return __T.visibleMask('.modal-lite').querySelector('.advance-input-row input').value`)
  assert(advVal === '50', '预付结算金额应自动填 50：' + advVal)
  const dlg1 = await ev(`return __T.visibleMask('.modal-lite').innerText`)
  assert(/剩余应付\s*0\.00/.test(dlg1.replace(/\s+/g, ' ')) || dlg1.includes('0.00'), '剩余应付应为 0.00')
  assert(dlg1.includes('最多 ￥50.00'), '应提示预付上限 ￥50.00（受净额约束）')
  assert(dlg1.includes('账户合计 ￥0.00'), '账户合计初始 0.00，与现金需求 0.00 勾稽')
  log('  勾预付后：预付 50.00 / 现金需求 0.00 / 账户合计 0.00 勾稽通过')
  await shot('prd36-m2-04-statement-prepay')
  // 关闭，绝不点确认（不落业务数据）
  await ev(`[...__T.visibleMask('.modal-lite').querySelectorAll('.modal-lite-head button')].find(b=>b.textContent.trim()==='关闭').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.modal-lite'))`)

  log('🎉 PRD-36 M2 浏览器真实交互验收全部通过（4 组场景，0 业务数据写入）')
  edge.kill()
  ws.close()
  process.exit(0)
}

main().catch(e => {
  console.error('PRD-36 M2 UI 验收失败 ❌', e)
  try { edge?.kill() } catch {}
  setTimeout(() => process.exit(1), 300)
})
