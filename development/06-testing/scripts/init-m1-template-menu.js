/**
 * PRD-34 期初 M1：菜单授权 + 四个模板下载 + 初始 status。
 * 空库/干净冒烟库执行：node development/06-testing/scripts/init-m1-template-menu.js
 */
const { get, getRaw, post, login, assert } = require('./init-common.js')

async function expectXlsx(path, keyword) {
  const res = await getRaw(path)
  assert(res.status === 200, `${path} 应 200，实际 ${res.status}`)
  const buf = Buffer.from(await res.arrayBuffer())
  // xlsx 是 zip：PK\x03\x04 开头
  assert(buf.length > 1000 && buf[0] === 0x50 && buf[1] === 0x4b, `${path} 应返回 xlsx zip，实际 ${buf.length}B`)
  console.log(`  模板 ${keyword || path}：${buf.length} bytes`)
}

async function main() {
  await login()

  // 1. 菜单树含三个新页面
  const menus = await get('/system/menu/user-tree')
  const flat = JSON.stringify(menus)
  for (const code of ['inv.init_stock', 'fin.init_ar', 'fin.init_ap']) {
    assert(flat.includes(code), `菜单树应包含 ${code}`)
  }
  console.log('菜单：库存期初/应收期初/应付期初 均已注册并授权超管')

  // 2. 四个模板
  await expectXlsx('/init/stock/import-template?type=batch', '库存-按批次')
  await expectXlsx('/init/stock/import-template?type=bin', '库存-按库位')
  await expectXlsx('/init/ar/import-template', '应收')
  await expectXlsx('/init/ap/import-template', '应付')

  // 3. 初始状态：未建账、未锁定、空数据不可过账、不可反建账
  for (const [api, label] of [['/init/stock', '库存'], ['/init/ar', '应收'], ['/init/ap', '应付']]) {
    const s = await post(api + '/status')
    assert(s.posted === false, `${label} 初始 posted=false`)
    assert(s.dayCloseLocked === false, `${label} 初始 dayCloseLocked=false`)
    assert(s.glInitialized === false, `${label} 初始 glInitialized=false`)
    assert(s.canPost === false, `${label} 无有效行时 canPost=false`)
    assert(s.canReverse === false, `${label} 初始 canReverse=false`)
  }
  console.log('初始状态：三模块未建账/未锁定/空数据不可过账')

  console.log('PRD-34 init M1 (template + menu + status) PASSED ✅')
}
main().catch(e => { console.error('init M1 FAILED ❌', e); process.exit(1) })
