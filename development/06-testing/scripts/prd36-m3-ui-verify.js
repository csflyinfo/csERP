/**
 * PRD-36 M3：厂家费用单 JF + 兑现单 DX 浏览器真实交互验收
 * （Edge headless + CDP，node 原生 WebSocket，无第三方依赖；克隆 prd36-m2-ui-verify.js 范式）。
 *
 * 前置（隔离环境，勿连受控 8080/5173）：
 *   1) 8082 后端使用 verify-prd36-m1 验证库，已跑通 prd36-m3-api-e2e.mjs（147 断言全绿后的期末态）：
 *      JF 8 张：JF...0001/0002/0003/0004/0006/0008 APPROVED；JF...0005 红字 -40 PENDING（原单 0001）；
 *               JF...0007 导入·其他 15 PENDING；
 *      DX 6 张全部 PENDING：DX...0001 CASH 80（现金50+银行卡30，兑现 JF...0002）、
 *               DX...0002/0003 OFFSET 60/40（AP-M2-10）、DX...0004 OTHER-1123 30、
 *               DX...0005 OTHER-5711 20、DX...0006 OTHER-560105 30；
 *      FE-M3-1~4 四张客户费用单已被 JF 整单占用（新建抽屉 FE 候选为空）。
 *   2) vite 跑 5174 代理 8082：VITE_API_TARGET=http://localhost:8082 npm --prefix frontend run dev -- --port 5174 --strictPort。
 *
 * 运行：node development/06-testing/scripts/prd36-m3-ui-verify.js
 *
 * 动作（不提交任何单据；唯一可逆动作：UI 上对 JF...0001 反审核→编辑看 FE 选择器→不保存关闭→重新审核复原，
 *   脚本末尾校验复原成功，失败会抛错；GL 事件池多出的反审核/重审事件由 prd36-m3-reset-v130.sql 清理）：
 *   01 JF 列表：8 行、红行/红标、状态/性质文案、导入来源
 *   02 JF0001 查看抽屉：FE 关联行 + 兑现记录（含 PENDING 计划数 DX0002/0003）
 *   03 红字 JF0005 查看：红字标签/原单指向/负金额
 *   04 新建 JF 抽屉：代垫 FE 行选择器（全占用空态）+ 费用合计联动
 *   05 JF0001 UI 反审核→编辑：FE 选择器 currentJfNo 视角可见 FE-M3-1 两行 → 关闭 → 重新审核复原
 *   06 导入弹窗：10 列表头/模板下载入口
 *   07 左下角批量条：勾选 PENDING 行才出现
 *   08 DX 列表：6 张三方式文案 + OTHER 对方科目列
 *   09 DX 新建·CASH：JF 候选 FIFO、勾选填满、资金不平置灰保存 → 填平后启用
 *   10 DX 新建·OFFSET：8 行未结应付、勾选 AP-M2-10 填未付额、勾稽
 *   11 DX 新建·OTHER：1123/1405/5601-5602/5711 四科目选项
 *   12 DX 查看：CASH 资金两行 / OFFSET 冲销行 / OTHER-1123 单选
 *   13 扣款通知单打印预览：JF 明细 + 资金到账
 * 截图落 development/06-testing/evidence/prd36-m3-*.png。
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
const log = (...a) => console.log('[prd36-m3-ui]', ...a)
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
    async waitFor(fn, ms=10000){ const t=Date.now(); let last; while(Date.now()-t<ms){ try{ const v=fn(); if(v) return v }catch(e){ last=e } await this.sleep(100) } throw new Error('waitFor超时: '+(last&&last.message||'')+' @ '+(fn.toString().slice(0,140))) },
    vis(el){ if(!el) return false; const r=el.getBoundingClientRect(); return r.width>0&&r.height>0 },
    findBtn(text, root){ return [...(root||document).querySelectorAll('button,a')].find(b=>this.vis(b)&&b.textContent.replace(/\\s+/g,'').includes(text)) },
    async clickBtn(text, root){ const b=await this.waitFor(()=>this.findBtn(text, root)); b.click() },
    rowByBill(sel, bill){ return [...document.querySelectorAll(sel+' tbody tr')].find(tr=>tr.innerText.includes(bill)) || null },
    clickLk(row, text){ const a=[...row.querySelectorAll('a.lk')].find(x=>x.textContent.trim()===text); if(!a) throw new Error('行内找不到操作：'+text); a.click() },
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
  // Vue 3 v-model 监听 input/change 事件读 DOM 值，直接赋值 + 派发事件即可（无需 React 式原生 setter）
  const setNative = (elExpr, v) => `
    { const el=${elExpr}; el.value=${JSON.stringify(v)};
      el.dispatchEvent(new Event('change',{bubbles:true})); el.dispatchEvent(new Event('input',{bubbles:true})); }`

  // ---------- 1. 登录 ----------
  log('打开登录页')
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

  // ---------- 2. JF 列表 ----------
  log('01 JF 列表：8 行 / 红行红标 / 状态性质文案 / 导入来源')
  await goto('/finance/factory-expense')
  await ev(`await __T.waitFor(()=>document.querySelectorAll('table.data tbody tr').length===8
      && !document.querySelector('.module-body').innerText.includes('加载中'))`)
  const jfText = await ev(`return document.querySelector('.module-body').innerText`)
  assert(jfText.includes('JF202609170001') && jfText.includes('JF202609170008'), '列表缺 JF 单号')
  assert(jfText.includes('-40.00'), '红字单金额应显示 -40.00：' + jfText.match(/JF202609170005[\\s\\S]{0,120}/)?.[0])
  for (const t of ['代垫', '其他', '已审核', '待审核']) {
    assert(jfText.includes(t), 'JF 列表缺少文案：' + t)
  }
  // 红行 class + 红标
  const redInfo = await ev(`
    const tr=__T.rowByBill('table.data','JF202609170005');
    return { cls: tr.className, flag: !!tr.querySelector('.red-flag'), op: tr.innerText.includes('红字') ? 'NO_RED_OP' : 'NO_RED_OP' }
  `)
  assert(redInfo.cls.includes('redrow') && redInfo.flag, '红字行应有 redrow class 与红标：' + JSON.stringify(redInfo))
  // 已审核普通单操作含 反审核/红字；PENDING 行操作含 编辑/删除/审核
  const apprRowOps = await ev(`return __T.rowByBill('table.data','JF202609170001').innerText`)
  assert(apprRowOps.includes('反审核') && apprRowOps.includes('红字'), '已审核普通单应有 反审核/红字：' + apprRowOps.slice(-80))
  const redRowOps = await ev(`return __T.rowByBill('table.data','JF202609170005').innerText`)
  assert(redRowOps.includes('编辑') && redRowOps.includes('审核') && !redRowOps.includes('红字'),
    'PENDING 红字行应有编辑/审核且无红字入口：' + redRowOps.slice(-80))
  // 来源方式：导入单 JF0006
  const impRow = await ev(`return __T.rowByBill('table.data','JF202609170006').innerText.replace(/\\s+/g,' ')`)
  assert(/导入/.test(impRow), 'JF0006 来源方式应含导入：' + impRow)
  log('  列表 8 行；红字行/红标/操作集/导入来源均正确')
  await shot('prd36-m3-01-jf-list')

  // ---------- 3. JF0001 查看抽屉：FE 行 + 兑现记录（含 PENDING 计划数） ----------
  log('02 JF0001 查看：FE 关联行 + 兑现记录（DX0002/0003 待审核计划数）')
  await ev(`__T.clickLk(__T.rowByBill('table.data','JF202609170001'),'查看')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.xw-mask') && __T.visibleMask('.xw-mask').querySelector('.line-table'))`)
  const viewText = await ev(`return document.querySelector('.xw-modal').innerText`)
  assert(viewText.includes('查看厂家费用单') && viewText.includes('JF202609170001'), '抽屉标题/单号不对')
  // FE 单号在禁用 input 的 value 里（innerText 不含输入框值）
  const feNos = await ev(`return [...document.querySelectorAll('.line-table .fe-no')].map(i=>i.value)`)
  assert(feNos.some(v => v.includes('FE-M3-1')), '代垫行应关联客户费用单 FE-M3-1：' + JSON.stringify(feNos))
  assert(viewText.includes('100.00'), '费用合计应为 100.00')
  // 兑现记录：两张 PENDING DX 计划数
  assert(viewText.includes('DX202609170002') && viewText.includes('DX202609170003'), '兑现记录缺 DX0002/0003')
  assert(/60\.00/.test(viewText) && /40\.00/.test(viewText), '兑现记录应有 60/40 计划数')
  const settleBoxText = await ev(`return document.querySelector('.settle-box').innerText.replace(/\\s+/g,' ')`)
  log('  兑现记录：', settleBoxText.slice(0, 200))
  assert(/待审核/.test(settleBoxText), 'PENDING DX 在兑现记录中应显示待审核')
  await shot('prd36-m3-02-jf-view-settles')
  await ev(`document.querySelector('.xw-modal .xw-h .x').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.xw-mask'))`)

  // ---------- 4. 红字单查看 ----------
  log('03 红字 JF0005 查看：红字标签/原单/负金额')
  await ev(`__T.clickLk(__T.rowByBill('table.data','JF202609170005'),'查看')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.xw-mask') && __T.visibleMask('.xw-mask').innerText.includes('红字单'))`)
  const redView = await ev(`return document.querySelector('.xw-modal').innerText`)
  assert(redView.includes('红字单（原单 JF202609170001）'), '红字标签应指向原单：' + redView.slice(0, 200))
  assert(redView.includes('-40.00'), '红字合计应为 -40.00')
  assert(redView.includes('红字单：明细金额必须为负，不可关联客户费用单、不可被兑现'), '缺红字提示')
  // 红字行无 FE 关联列（表头不应出现「关联客户费用单」）
  assert(!redView.includes('（代垫行可关联客户费用单行）'), '红字抽屉不应显示代垫关联列提示')
  await shot('prd36-m3-03-jf-red-view')
  await ev(`document.querySelector('.xw-modal .xw-h .x').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.xw-mask'))`)

  // ---------- 5. 新建 JF：FE 行选择器空态 + 合计联动 ----------
  log('04 新建 JF 抽屉：FE 选择器全占用空态')
  await ev(`await __T.clickBtn('新建费用单')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.xw-mask') && document.querySelector('.line-table'))`)
  // 默认代垫：关联列存在
  const advHead = await ev(`return document.querySelector('.detail-head').innerText`)
  assert(advHead.includes('代垫行可关联客户费用单行'), '代垫默认应提示可关联 FE')
  // 打开选择器（当前无单号视角；FE-M3-1~4 全被 APPROVED JF 占用 → 空态）
  await ev(`document.querySelector('.fe-cell button.btn.xs').click()`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.pk-mask'))`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.pk-mask').innerText.includes('加载中'))`)
  const pkText = await ev(`return document.querySelector('.pk-modal').innerText`)
  assert(pkText.includes('选择客户费用单明细行'), '选择器标题不对')
  assert(pkText.includes('没有可选的客户费用单行'), '全占用时应显示空态：' + pkText.slice(0, 200))
  log('  FE 选择器空态正确（4 张 FE-M3 均被整单占用）')
  await shot('prd36-m3-04-jf-picker-empty')
  await ev(`document.querySelector('.pk-modal .xw-h .x').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.pk-mask'))`)
  // 切「其他」性质：关联列消失
  await ev(`
    const sel=document.querySelector('.xw-modal .master select');
    sel.value='S001'; sel.dispatchEvent(new Event('change',{bubbles:true}));
    const claim=[...document.querySelectorAll('.xw-modal .master select')][1];
    claim.value='OTHER'; claim.dispatchEvent(new Event('change',{bubbles:true}));
  `)
  await sleep(200)
  const otherDrawer = await ev(`return document.querySelector('.xw-modal').innerText`)
  assert(!otherDrawer.includes('代垫行可关联客户费用单行'), '其他性质不应显示 FE 关联列')
  // 合计随金额联动：把第一行金额设 88.5
  await ev(setNative(`document.querySelector('.line-table input.amount')`, '88.5'))
  await ev(`await __T.waitFor(()=>document.querySelector('.balance-panel').innerText.includes('88.50'))`)
  log('  其他性质隐藏 FE 列；合计联动 88.50')
  await shot('prd36-m3-04b-jf-create-other')
  await ev(`document.querySelector('.xw-modal .xw-h .x').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.xw-mask'))`)

  // ---------- 6. JF0001 反审核 → 编辑看选择器 currentJfNo 视角 → 重新审核复原 ----------
  log('05 JF0001 UI 反审核 → 编辑抽屉 FE 选择器（currentJfNo 视角）')
  await ev(`__T.clickLk(__T.rowByBill('table.data','JF202609170001'),'反审核')`) // confirm 由 CDP 自动接受
  await ev(`await __T.waitFor(()=>{ const tr=__T.rowByBill('table.data','JF202609170001'); return tr && tr.innerText.includes('编辑') })`)
  log('  JF0001 已反审核为 PENDING')
  try {
    await ev(`__T.clickLk(__T.rowByBill('table.data','JF202609170001'),'编辑')`)
    await ev(`await __T.waitFor(()=>__T.visibleMask('.xw-mask') && __T.visibleMask('.xw-mask').innerText.includes('编辑厂家费用单'))`)
    // 明细行已带 FE-M3-1 关联（回指已在反审核时释放，但行上关联值仍在输入框 value 中）
    const editFeNos = await ev(`return [...document.querySelectorAll('.line-table .fe-no')].map(i=>i.value)`)
    assert(editFeNos.some(v => v.includes('FE-M3-1')), '编辑抽屉应保留行上 FE-M3-1 关联值：' + JSON.stringify(editFeNos))
    await ev(`document.querySelector('.fe-cell button.btn.xs').click()`)
    await ev(`await __T.waitFor(()=>__T.visibleMask('.pk-mask'))`)
    await ev(`await __T.waitFor(()=>!__T.visibleMask('.pk-mask').innerText.includes('加载中'))`)
    const pkRows = await ev(`return [...document.querySelectorAll('.pk-modal .ap-table tbody tr')].map(tr=>tr.innerText.replace(/\\s+/g,' ').trim())`)
    log('  currentJfNo 视角候选：', JSON.stringify(pkRows))
    assert(pkRows.length === 2, 'FE-M3-1 应给出 2 个明细行（60+40）：' + pkRows.length)
    assert(pkRows.some(t => t.includes('FE-M3-1') && t.includes('60.00')), '缺 FE-M3-1 60 行：' + pkRows.join(' | '))
    assert(pkRows.some(t => t.includes('FE-M3-1') && t.includes('40.00')), '缺 FE-M3-1 40 行：' + pkRows.join(' | '))
    await shot('prd36-m3-05-jf-picker-current')
    await ev(`document.querySelector('.pk-modal .xw-h .x').click()`)
    await ev(`await __T.waitFor(()=>!__T.visibleMask('.pk-mask'))`)
    await ev(`document.querySelector('.xw-modal .xw-h .x').click()`) // 取消，不保存
    await ev(`await __T.waitFor(()=>!__T.visibleMask('.xw-mask'))`)
  } finally {
    // 无论上面如何，必须重新审核复原
    const needAudit = await ev(`return !!__T.rowByBill('table.data','JF202609170001')?.innerText.includes('审核')`)
    if (needAudit) {
      const row = await ev(`return __T.rowByBill('table.data','JF202609170001') && true`)
      assert(row, 'JF0001 行丢失，无法复原')
      await ev(`__T.clickLk(__T.rowByBill('table.data','JF202609170001'),'审核')`)
      await ev(`await __T.waitFor(()=>{ const tr=__T.rowByBill('table.data','JF202609170001'); return tr && tr.innerText.includes('反审核') },12000)`)
      log('  JF0001 已重新审核复原 APPROVED')
    }
  }
  const restored = await ev(`return __T.rowByBill('table.data','JF202609170001').innerText`)
  assert(restored.includes('已审核') && restored.includes('反审核') && !restored.includes('编辑'),
    'JF0001 未复原为 APPROVED：' + restored.slice(-100))

  // ---------- 7. 导入弹窗 ----------
  log('06 导入弹窗：10 列表头/模板入口')
  await ev(`await __T.clickBtn('导入')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.import-mask'))`)
  const impText = await ev(`return document.querySelector('.import-box').innerText`)
  assert(impText.includes('厂家费用单导入'), '导入弹窗标题不对')
  for (const t of ['下载模板', '选择文件', '支持 .xls / .xlsx / csv，仅解析第一个页签']) {
    assert(impText.includes(t), '导入弹窗缺元素：' + t)
  }
  // 10 列表头未选文件前不渲染在正文，直接读 ImportDialog 实例 props（即模板下载列序）
  const impHeaders = await ev(`
    let n=document.querySelector('.import-box'), c=null;
    for(let i=0;i<8&&n;i++){ c=n.__vueParentComponent; if(c&&c.props&&Array.isArray(c.props.templateHeaders)) break; n=n.parentElement }
    return c ? c.props.templateHeaders : null`)
  assert(Array.isArray(impHeaders) && impHeaders.length === 10, '模板表头应为 10 列：' + JSON.stringify(impHeaders))
  for (const h of ['供应商编码', '费用类型', '费用性质', '垫付客户编码', '关联客户费用单号',
    '费用日期', '金额', '厂家协议号/票据号', '贷方科目', '备注']) {
    assert(impHeaders.includes(h), '模板缺表头：' + h)
  }
  log('  导入弹窗元素齐全；模板 10 列：' + impHeaders.join('/'))
  await shot('prd36-m3-06-import-dialog')
  // 点底部「取消」关闭（不点模板下载/确认导入，不落任何数据）
  await ev(`[...document.querySelectorAll('.import-foot button')].find(b=>b.textContent.trim()==='取消').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.import-mask'))`)

  // ---------- 8. 左下角批量条 ----------
  log('07 批量审核条：勾选 PENDING 行才出现')
  assert(!await ev(`return !!document.querySelector('.batch-bar')`), '初始不应有批量条')
  await ev(`__T.rowByBill('table.data','JF202609170007').querySelector('input[type=checkbox]').click()`)
  await ev(`await __T.waitFor(()=>document.querySelector('.batch-bar')
      && document.querySelector('.batch-bar').innerText.includes('已选 1 张待审核单'))`)
  log('  勾选 1 张 PENDING：批量条出现')
  await shot('prd36-m3-07-batch-bar')
  // APPROVED 行勾选框禁用
  const apprDisabled = await ev(`return __T.rowByBill('table.data','JF202609170001').querySelector('input[type=checkbox]').disabled`)
  assert(apprDisabled, '已审核行勾选框应禁用')
  // 绝不点批量审核，直接离开页面

  // ---------- 9. DX 列表 ----------
  log('08 DX 列表：6 张三方式 + OTHER 对方科目列')
  await goto('/finance/factory-settle')
  await ev(`await __T.waitFor(()=>document.querySelectorAll('table.data tbody tr').length===6
      && !document.querySelector('.module-body').innerText.includes('加载中'))`)
  const dxText = await ev(`return document.querySelector('.module-body').innerText`)
  for (const no of ['DX202609170001', 'DX202609170002', 'DX202609170003', 'DX202609170004', 'DX202609170005', 'DX202609170006']) {
    assert(dxText.includes(no), 'DX 列表缺单：' + no)
  }
  for (const t of ['现金结算', '冲应付', '其他核销']) {
    assert(dxText.includes(t), 'DX 列表缺方式文案：' + t)
  }
  for (const code of ['1123', '5711', '560105']) {
    const tr = await ev(`return [...document.querySelectorAll('table.data tbody tr')].find(x=>x.innerText.includes('${code}'))?.innerText || ''`)
    assert(tr.includes('其他核销'), '对方科目 ' + code + ' 行应是其他核销：' + tr.slice(0, 120))
  }
  const dx1123 = await ev(`return __T.rowByBill('table.data','DX202609170004').innerText.replace(/\\s+/g,' ')`)
  assert(dx1123.includes('YF-M3-1'), 'DX0004 应显示关联单据 YF-M3-1：' + dx1123)
  log('  6 张 DX 三方式/科目/关联单显示正确')
  await shot('prd36-m3-08-dx-list')

  // ---------- 10. DX 新建·CASH ----------
  log('09 DX 新建 CASH：FIFO 候选/勾选填满/资金不平置灰')
  await ev(`await __T.clickBtn('新建兑现单')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.xw-mask'))`)
  await ev(setNative(`document.querySelector('.head-form select')`, 'S001'))
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.two-pane .pane')[0].querySelectorAll('tbody tr').length===6,10000)`)
  const jfCand = await ev(`return [...document.querySelectorAll('.two-pane .pane')[0].querySelectorAll('tbody tr')].map(tr=>tr.innerText.replace(/\\s+/g,' ').trim())`)
  log('  JF 候选：', JSON.stringify(jfCand))
  // FIFO：首行 JF0001（费用日期最早），未兑 100
  assert(/JF202609170001/.test(jfCand[0]) && /100\.00/.test(jfCand[0]), '首行应为 JF0001 未兑 100：' + jfCand[0])
  // 经手人必选（选第一个员工）
  await ev(`
    const sels=document.querySelectorAll('.head-form select');
    const handler=sels[2]; if(handler.options.length>1){ handler.value=handler.options[1].value; handler.dispatchEvent(new Event('change',{bubbles:true})); }
  `)
  // 勾选首行 → 自动填 100
  await ev(`document.querySelectorAll('.two-pane .pane')[0].querySelector('tbody input[type=checkbox]').click()`)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.pane-head')[0].innerText.includes('100.00'))`)
  // 保存按钮默认置灰（资金 0 ≠ 100）
  let footBtns = await ev(`return [...document.querySelectorAll('.xw-f button')].map(b=>({t:b.textContent.trim(),d:b.disabled,title:b.title}))`)
  log('  底部按钮：', JSON.stringify(footBtns))
  const saveBtn = () => `[...document.querySelectorAll('.xw-f button')].find(b=>b.textContent.trim()==='保存')`
  assert((await ev(`return ${saveBtn()}.disabled`)), '资金不平，保存必须置灰')
  assert((await ev(`return ${saveBtn()}.title`)).includes('不一致'), '置灰 title 应提示合计不一致')
  // 填资金：现金 50 + 新增行 银行卡 30（80≠100 仍置灰）
  await ev(setNative(`document.querySelectorAll('.two-pane .pane')[1].querySelector('select')`, '现金'))
  await ev(setNative(`document.querySelectorAll('.two-pane .pane')[1].querySelector('input.amt')`, '50'))
  await ev(`__T.findBtn('新增行', document.querySelectorAll('.two-pane .pane')[1]).click()`)
  await ev(setNative(
    `document.querySelectorAll('.two-pane .pane')[1].querySelectorAll('tbody tr')[1].querySelector('select')`, '银行卡'))
  await ev(setNative(
    `document.querySelectorAll('.two-pane .pane')[1].querySelectorAll('tbody tr')[1].querySelector('input.amt')`, '30'))
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.pane-head')[1].innerText.includes('80.00'))`)
  assert(await ev(`return ${saveBtn()}.disabled`), '资金 80 ≠ 费用 100 仍应置灰')
  // 改成 70 → 70+30=100 启用
  await ev(setNative(
    `document.querySelectorAll('.two-pane .pane')[1].querySelectorAll('tbody tr')[0].querySelector('input.amt')`, '70'))
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.pane-head')[1].innerText.includes('100.00'))`)
  assert(!(await ev(`return ${saveBtn()}.disabled`)), '填平 70+30=100 后保存应启用')
  log('  CASH：不平置灰 → 70+30=100 勾稽启用，验证通过（不保存）')
  await shot('prd36-m3-09-dx-cash-balanced')
  await ev(`document.querySelector('.xw-modal .xw-h .x').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.xw-mask'))`)

  // ---------- 11. DX 新建·OFFSET ----------
  log('10 DX 新建 OFFSET：未结应付候选/勾选填未付额/勾稽')
  await ev(`await __T.clickBtn('新建兑现单')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.xw-mask'))`)
  await ev(setNative(`document.querySelector('.head-form select')`, 'S001'))
  // 方式切 OFFSET
  await ev(`
    const type=document.querySelectorAll('.head-form select')[1];
    type.value='OFFSET'; type.dispatchEvent(new Event('change',{bubbles:true}));
  `)
  await ev(`
    const sels=document.querySelectorAll('.head-form select');
    const handler=sels[2]; if(handler.options.length>1){ handler.value=handler.options[1].value; handler.dispatchEvent(new Event('change',{bubbles:true})); }
  `)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.two-pane .pane')[1].querySelectorAll('tbody tr').length===8,10000)`)
  const apCand = await ev(`return [...document.querySelectorAll('.two-pane .pane')[1].querySelectorAll('tbody tr')].map(tr=>tr.innerText.replace(/\\s+/g,' ').trim())`)
  log('  AP 候选：', JSON.stringify(apCand))
  assert(apCand.length === 8, 'S001 未结应付应为 8 行：' + apCand.length)
  // 到期日升序：首行 QCAP-TEST-1 2026-08-01
  assert(/QCAP-TEST-1/.test(apCand[0]) && /2026-08-01/.test(apCand[0]), '首行应为最早到期 QCAP-TEST-1：' + apCand[0])
  // 上半区勾 JF0001 → 100
  await ev(`document.querySelectorAll('.two-pane .pane')[0].querySelector('tbody input[type=checkbox]').click()`)
  // 下半区勾 AP-M2-10 → 自动填未付 100
  await ev(`
    const tr=[...document.querySelectorAll('.two-pane .pane')[1].querySelectorAll('tbody tr')].find(x=>x.innerText.includes('AP-M2-10'));
    tr.querySelector('input[type=checkbox]').click();
  `)
  await ev(`await __T.waitFor(()=>{
    const h=document.querySelectorAll('.pane-head')[1].innerText; return h.includes('100.00') && !h.includes('-')
  })`)
  const apAmt = await ev(`return [...document.querySelectorAll('.two-pane .pane')[1].querySelectorAll('tbody tr')].find(x=>x.innerText.includes('AP-M2-10')).querySelector('input.amt').value`)
  assert(apAmt === '100', '勾选 AP-M2-10 应自动填未付 100：' + apAmt)
  assert(!(await ev(`return ${saveBtn()}.disabled`)), '100=100 保存应启用')
  log('  OFFSET：JF100 = AP-M2-10 冲销 100 勾稽通过（不保存）')
  await shot('prd36-m3-10-dx-offset')

  // ---------- 12. DX 切 OTHER：四科目选项 ----------
  log('11 DX 切 OTHER：1123/1405/5601-5602/5711 科目选项')
  await ev(`
    const type=document.querySelectorAll('.head-form select')[1];
    type.value='OTHER'; type.dispatchEvent(new Event('change',{bubbles:true}));
  `)
  await ev(`await __T.waitFor(()=>document.querySelector('.contra-box'))`)
  const contraText = await ev(`return document.querySelector('.contra-box').innerText`)
  for (const t of ['1123 预付账款（转预付）', '1405 库存商品（厂家货补）', '5601/5602 费用减免', '5711 营业外支出（坏账，备注必填原因）']) {
    assert(contraText.includes(t), 'OTHER 缺科目选项：' + t)
  }
  // 选 5711
  await ev(`document.querySelector('.contra-box input[value="5711"]').click()`)
  await ev(`await __T.waitFor(()=>document.querySelector('.cb-cur') && document.querySelector('.cb-cur').innerText.includes('5711'))`)
  // 5601/5602 下拉有末级费用科目（560105 等）
  await ev(`document.querySelector('.contra-box input[value="FEE"]').click()`)
  const feeOpts = await ev(`return [...document.querySelector('.fee-sel').options].map(o=>o.value).filter(Boolean)`)
  log('  5601/5602 末级科目：', JSON.stringify(feeOpts))
  assert(feeOpts.some(v => /^560(1|2)/.test(v)), '费用科目下拉应有 5601/5602 末级：' + JSON.stringify(feeOpts))
  // 切回 5711 再截图
  await ev(`document.querySelector('.contra-box input[value="5711"]').click()`)
  await sleep(150)
  await shot('prd36-m3-11-dx-other-subjects')
  await ev(`document.querySelector('.xw-modal .xw-h .x').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.xw-mask'))`)

  // ---------- 13. DX 查看三方式 ----------
  log('12 DX 查看：CASH 资金两行')
  await ev(`__T.clickLk(__T.rowByBill('table.data','DX202609170001'),'查看')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.xw-mask') && __T.visibleMask('.xw-mask').innerText.includes('查看厂家费用兑现单'))`)
  const cashView = await ev(`return document.querySelector('.xw-modal').innerText`)
  assert(cashView.includes('JF202609170002') && cashView.includes('80.00'), 'CASH 抽屉应含 JF0002 80')
  // 资金账户/金额在 select/input 的 value 中
  const cashFund = await ev(`
    const pane=document.querySelectorAll('.two-pane .pane')[1];
    return { acc:[...pane.querySelectorAll('select')].map(s=>s.value),
             amt:[...pane.querySelectorAll('input.amt')].map(i=>i.value) }`)
  assert(cashFund.acc.includes('现金') && cashFund.acc.includes('银行卡'), 'CASH 应含现金/银行卡：' + JSON.stringify(cashFund))
  assert(cashFund.amt.includes('50') && cashFund.amt.includes('30'), '资金行应为 50/30：' + JSON.stringify(cashFund))
  await shot('prd36-m3-12a-dx-view-cash')
  await ev(`document.querySelector('.xw-modal .xw-h .x').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.xw-mask'))`)

  log('  DX 查看：OFFSET 冲销行')
  await ev(`__T.clickLk(__T.rowByBill('table.data','DX202609170003'),'查看')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.xw-mask'))`)
  await ev(`await __T.waitFor(()=>document.querySelectorAll('.two-pane .pane')[1].querySelectorAll('tbody tr').length>=1)`)
  const offView = await ev(`return document.querySelector('.xw-modal').innerText`)
  assert(offView.includes('AP-M2-10'), 'OFFSET 抽屉应含 AP-M2-10')
  const offAmt = await ev(`
    const tr=[...document.querySelectorAll('.two-pane .pane')[1].querySelectorAll('tbody tr')].find(x=>x.innerText.includes('AP-M2-10'));
    return tr.querySelector('input.amt').value`)
  assert(offAmt === '40', 'AP-M2-10 本次冲销应为 40：' + offAmt)
  await shot('prd36-m3-12b-dx-view-offset')
  await ev(`document.querySelector('.xw-modal .xw-h .x').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.xw-mask'))`)

  log('  DX 查看：OTHER-1123 科目单选')
  await ev(`__T.clickLk(__T.rowByBill('table.data','DX202609170004'),'查看')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.xw-mask') && document.querySelector('.contra-box'))`)
  const otherView = await ev(`return document.querySelector('.xw-modal').innerText`)
  const radio1123 = await ev(`return document.querySelector('.contra-box input[value="1123"]').checked`)
  assert(radio1123, '查看 DX0004 时 1123 radio 应选中')
  // 关联单据号在输入框 value 中
  const relBill = await ev(`return document.querySelector('.head-form input[placeholder*="预付付款单号"]').value`)
  assert(relBill === 'YF-M3-1', 'OTHER 应显示关联单据 YF-M3-1：' + relBill)
  assert(otherView.includes('30.00'), 'OTHER 抽屉应含本次兑现 30.00')
  await shot('prd36-m3-12c-dx-view-other1123')
  await ev(`document.querySelector('.xw-modal .xw-h .x').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.xw-mask'))`)

  // ---------- 14. 扣款通知单打印 ----------
  log('13 扣款通知单打印预览（DX0001 CASH）')
  await ev(`__T.clickLk(__T.rowByBill('table.data','DX202609170001'),'打印')`)
  await ev(`await __T.waitFor(()=>__T.visibleMask('.print-mask'))`)
  await ev(`await __T.waitFor(()=>document.querySelector('.print-sheet')
      && document.querySelector('.print-sheet').innerText.includes('JF202609170002'),10000)`)
  const sheet = await ev(`return document.querySelector('.print-sheet').innerText`)
  assert(sheet.includes('扣款通知单'), '打印标题不对')
  assert(sheet.includes('现金结算') && sheet.includes('￥80.00'), '抬头应含方式与合计 80.00')
  assert(sheet.includes('资金到账'), 'CASH 应有资金到账段')
  assert(sheet.includes('50.00') && sheet.includes('30.00'), '资金到账应列 50/30')
  assert(sheet.includes('厂家确认（签字/盖章）'), '缺签字栏')
  log('  扣款通知单：抬头/JF 明细/资金到账/签字栏齐全')
  await shot('prd36-m3-13-print-notice')
  await ev(`[...document.querySelectorAll('.print-modal button')].find(b=>b.textContent.trim()==='关闭').click()`)
  await ev(`await __T.waitFor(()=>!__T.visibleMask('.print-mask'))`)

  log('🎉 PRD-36 M3 浏览器真实交互验收全部通过（13 组场景，0 单据提交）')
  edge.kill()
  ws.close()
  process.exit(0)
}

main().catch(e => {
  console.error('PRD-36 M3 UI 验收失败 ❌', e)
  try { edge?.kill() } catch {}
  setTimeout(() => process.exit(1), 300)
})
