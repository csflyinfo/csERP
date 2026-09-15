/**
 * PRD-34 期初初始化验收脚本公共助手（init-m1~m6 共用）。
 *
 * 推荐跑在专用冒烟空库上（8081）：
 *   java -jar backend/target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8081 \
 *     --spring.datasource.url="jdbc:h2:file:E:/work/erp-wms-tms/data/erp-smoke-init;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
 *     --spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration
 *   node development/06-testing/scripts/init-m1-template-menu.js
 *
 * 连本机 8080 时：API_BASE=http://localhost:8080/api node ...（跑前务必备份 data/erp-v1.mv.db）
 */
const BASE = process.env.API_BASE || 'http://localhost:8081/api'

let authToken = 'demo-token'

async function post(path, body = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + authToken },
    body: JSON.stringify(body),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}
async function getRaw(path) {
  const res = await fetch(`${BASE}${path}`, { headers: { Authorization: 'Bearer ' + authToken } })
  return res
}
async function get(path) {
  const res = await fetch(`${BASE}${path}`, { headers: { Authorization: 'Bearer ' + authToken } })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}
async function login() {
  const res = await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`/auth/login failed: ${json.message}`)
  authToken = json.data.token
}
/** 期望业务校验拦截；keyword 为中文报错片段（可传数组，命中其一即可）。 */
async function expectFail(path, body, keyword) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + authToken },
    body: JSON.stringify(body),
  })
  const json = await res.json()
  if (json.code === '0') throw new Error(`${path} 应当失败但成功了：${JSON.stringify(body).slice(0, 200)}`)
  const words = Array.isArray(keyword) ? keyword : [keyword]
  if (keyword && !words.some(w => String(json.message).includes(w))) {
    throw new Error(`${path} 报错不含 ${JSON.stringify(words)}：${json.message}`)
  }
  return json.message
}
function assert(condition, message) {
  if (!condition) throw new Error(message)
}
function num(v) { return Number(v || 0) }
function todayStr() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
/** 在 N 天后的日期字符串（用于到期日断言）。 */
function plusDays(days) {
  const d = new Date()
  d.setDate(d.getDate() + days)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 幂等建档案：存在则跳过，不存在则建。返回 {goods, warehouse, customer, supplier, zone, binNormal, binDisabled}。 */
async function bootstrapMasters(sfx = 'INIT') {
  const out = {
    goods: { code: 'G' + sfx, name: '期初冒烟商品-' + sfx, price: 10 },
    goods2: { code: 'G2' + sfx, name: '期初冒烟商品乙-' + sfx, price: 20 },
    warehouse: { code: 'WH' + sfx, name: '期初冒烟仓-' + sfx },
    customer: { code: 'CUS' + sfx, name: '期初冒烟客户-' + sfx },
    supplier: { code: 'SUP' + sfx, name: '期初冒烟供应商-' + sfx },
    zone: { code: 'ZN' + sfx, name: '期初冒烟库区-' + sfx },
    bin: { code: 'BIN' + sfx, disabledCode: 'BINX' + sfx },
  }
  const exists = async (pagePath, codeField, code) => {
    const p = await post(pagePath, { pageNo: 1, pageSize: 200, filters: { keyword: code } })
    return (p.records || []).some(r => r[codeField] === code)
  }
  const ignoreDup = async fn => { try { await fn() } catch (e) { if (!String(e.message).includes('已存在') && !String(e.message).includes('重复')) throw e } }

  if (!await exists('/base/goods/page', 'goodsCode', out.goods.code)) {
    await ignoreDup(() => post('/base/goods/create', {
      goodsCode: out.goods.code, goodsName: out.goods.name, spec: '1*1', categoryName: '期初冒烟分类',
      baseUnit: '个', goodsType: '正常商品', taxRate: '13%', storageProperty: '常温',
      latestPurchasePrice: out.goods.price, shelfLifeDays: 365,
    }))
  }
  if (!await exists('/base/goods/page', 'goodsCode', out.goods2.code)) {
    await ignoreDup(() => post('/base/goods/create', {
      goodsCode: out.goods2.code, goodsName: out.goods2.name, spec: '1*1', categoryName: '期初冒烟分类',
      baseUnit: '个', goodsType: '正常商品', taxRate: '13%', storageProperty: '常温',
      latestPurchasePrice: out.goods2.price,
    }))
  }
  if (!await exists('/base/warehouse/page', 'warehouseCode', out.warehouse.code)) {
    await ignoreDup(() => post('/base/warehouse/create', {
      warehouseCode: out.warehouse.code, warehouseName: out.warehouse.name,
      warehouseType: '正常仓', inventoryType: '平台主仓', costGroup: 'CG01',
    }))
  }
  if (!await exists('/base/customer/page', 'customerCode', out.customer.code)) {
    await ignoreDup(() => post('/base/customer/create', {
      customerCode: out.customer.code, customerName: out.customer.name,
      channelType: '零售商超', salesman: '冒烟员', accountPeriodType: '月结30天',
    }))
  }
  if (!await exists('/base/supplier/page', 'supplierCode', out.supplier.code)) {
    await ignoreDup(() => post('/base/supplier/create', {
      supplierCode: out.supplier.code, supplierName: out.supplier.name,
      supplierType: '普通供应商', settlementMethod: '月结30天',
    }))
  }
  // WMS 库区 + 两个库位（正常/停用）
  const zonePage = await post('/wms/zone/page', { pageNo: 1, pageSize: 200, filters: { warehouse: out.warehouse.name } })
    .catch(() => ({ records: [] }))
  if (!(zonePage.records || []).some(z => z.zoneCode === out.zone.code)) {
    await post('/wms/zone/save', {
      zoneCode: out.zone.code, zoneName: out.zone.name, warehouse: out.warehouse.name,
      zoneType: 'STORAGE', storageProperty: '常温',
    })
  }
  const binPage = await post('/wms/bin/page', { pageNo: 1, pageSize: 200, filters: { warehouse: out.warehouse.name } })
  const bins = binPage.records || []
  const ensureBin = async (binCode) => {
    if (!bins.some(b => b.binCode === binCode)) {
      await post('/wms/bin/save', {
        binCode, binName: binCode, warehouse: out.warehouse.name, zoneCode: out.zone.code,
        binType: 'SHELF', capacityQty: 99999,
      })
    }
  }
  await ensureBin(out.bin.code)
  await ensureBin(out.bin.disabledCode)
  // 停用库位用于 BIN 导入拦截测试
  const disabled = (await post('/wms/bin/page', { pageNo: 1, pageSize: 200, filters: { warehouse: out.warehouse.name } }))
    .records.find(b => b.binCode === out.bin.disabledCode)
  if (disabled && disabled.status !== 'DISABLED') {
    await post('/wms/bin/toggle', { binId: disabled.binId })
  }
  return out
}

module.exports = { BASE, post, get, getRaw, login, expectFail, assert, num, todayStr, plusDays, bootstrapMasters }
