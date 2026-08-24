<script setup>
/**
 * WMS V1.5 拣货位商品绑定（V83 新增，PRD-28）
 *
 * 交互：
 *   - 顶部筛选：仓库 + 库区 + 库位 + 商品关键字 + 仅看生效
 *   - 主表：库位 → 商品绑定关系（含绑定类型、阈值、在库量、状态）
 *   - 操作：
 *       ① 绑定：选库位+商品，建立/激活绑定
 *       ② 改阈值：min/max/replenishTo
 *       ③ 转移绑定：把某商品从当前库位移到另一库位（旧记录置 N，新记录激活）
 *       ④ 解绑：active_flag=N（保留审计）
 *   - 底部抽屉：最近变更日志（PC/PDA 都写同一张日志表）
 *   - PDA 端走 /wms/app/binding/{bind,unbind,transfer,lookup}，支持扫码快速改绑。
 */
import { ref, computed, onMounted } from 'vue'
import { get, post } from '../../api/client.js'

const list = ref([])
const logs = ref([])
const warehouses = ref([])
const zones = ref([])
const loading = ref(false)
const feedback = ref('')

const fWarehouse = ref('')
const fZone = ref('')
const fBin = ref('')
const fKeyword = ref('')
const fActiveOnly = ref(true)

const BINDING_TYPES = [
  { v: 'PICK', l: '拣货位' },
  { v: 'RESERVE', l: '备货位' },
  { v: 'BULK', l: '整托位' },
]
const bindingText = Object.fromEntries(BINDING_TYPES.map(t => [t.v, t.l]))
const changeText = { BIND: '绑定', UNBIND: '解绑', TRANSFER: '转移', UPDATE: '阈值调整' }
const sourceText = { PC: 'PC', PDA: 'PDA' }

// 弹窗
const bindOpen = ref(false)
const bindForm = ref(blankBind())
const thresholdOpen = ref(false)
const thresholdForm = ref({})
const transferOpen = ref(false)
const transferForm = ref({})
const logOpen = ref(false)

function blankBind() {
  return { warehouse: '', zoneCode: '', binCode: '', goodsCode: '', goodsName: '',
           bindingType: 'PICK', minQty: 0, maxQty: 0, replenishToQty: 0, remark: '' }
}
function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 2500)
}

async function loadWarehouses() {
  // 仓库列表复用 WMS 自己维护的仓库清单（base_warehouse）
  const res = await get('/wms/warehouse/list')
  warehouses.value = res || []
  if (!fWarehouse.value && warehouses.value[0]) fWarehouse.value = warehouses.value[0].warehouseName
}
async function loadZones() {
  if (!fWarehouse.value) { zones.value = []; return }
  const res = await post('/wms/zone/page', { pageNo: 1, pageSize: 500, filters: { warehouse: fWarehouse.value } })
  zones.value = res.records || []
}
async function load() {
  loading.value = true
  try {
    const res = await post('/wms/binding/page', {
      pageNo: 1, pageSize: 500,
      filters: {
        warehouse: fWarehouse.value, zoneCode: fZone.value, binCode: fBin.value,
        keyword: fKeyword.value, activeFlag: fActiveOnly.value ? 'Y' : '',
      },
    })
    list.value = res.records || []
  } catch (e) { show('加载失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

function onWarehouseChange() { fZone.value = ''; loadZones() }

function openBind() {
  if (!fWarehouse.value) return show('请先选择仓库', 'err')
  bindForm.value = { ...blankBind(), warehouse: fWarehouse.value, zoneCode: fZone.value || '' }
  bindOpen.value = true
}
async function doBind() {
  const f = bindForm.value
  if (!f.binCode || !f.goodsCode) return show('库位和商品编码必填', 'err')
  try {
    await post('/wms/binding/bind', f)
    show('绑定成功')
    bindOpen.value = false
    load()
  } catch (e) { show('绑定失败：' + (e?.message || e), 'err') }
}
function openThreshold(row) {
  thresholdForm.value = {
    bindingId: row.bindingId, goodsCode: row.goodsCode, binCode: row.binCode,
    minQty: row.minQty, maxQty: row.maxQty, replenishToQty: row.replenishToQty,
    remark: row.remark || '',
  }
  thresholdOpen.value = true
}
async function doThreshold() {
  try {
    await post('/wms/binding/thresholds', thresholdForm.value)
    show('阈值已更新')
    thresholdOpen.value = false
    load()
  } catch (e) { show('更新失败：' + (e?.message || e), 'err') }
}
function openTransfer(row) {
  transferForm.value = {
    warehouse: row.warehouse, goodsCode: row.goodsCode, goodsName: row.goodsName,
    fromBin: row.binCode, toBin: '', toZoneCode: '', bindingType: row.bindingType || 'PICK',
  }
  transferOpen.value = true
}
async function doTransfer() {
  const f = transferForm.value
  if (!f.toBin) return show('请扫/输入目标库位', 'err')
  try {
    await post('/wms/binding/transfer', f)
    show('已转移到 ' + f.toBin)
    transferOpen.value = false
    load()
  } catch (e) { show('转移失败：' + (e?.message || e), 'err') }
}
async function doUnbind(row) {
  if (!confirm(`确认解除 ${row.binCode} → ${row.goodsCode} 的绑定？历史可在「变更日志」查看。`)) return
  try {
    await post('/wms/binding/unbind', { bindingId: row.bindingId })
    show('已解绑')
    load()
  } catch (e) { show('解绑失败：' + (e?.message || e), 'err') }
}
async function openLog() {
  logOpen.value = true
  try {
    const res = await post('/wms/binding/log-page', { pageNo: 1, pageSize: 200, filters: {} })
    logs.value = res.records || []
  } catch (e) { logs.value = [] }
}

const pdaHint = 'PDA 端：扫库位 → 扫商品，调用 /wms/app/binding/bind 建立绑定；'
  + '需要换库位时用 /wms/app/binding/transfer（扫旧库位、扫商品、扫新库位一步完成）；'
  + '所有动作均写 wms_binding_change_log，source=PDA 可在变更日志筛选。'

onMounted(async () => {
  await loadWarehouses()
  await loadZones()
  await load()
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>拣货位商品绑定</b>
      <span class="tip">把商品固定绑到拣货位/备货位，用于补货推荐与 PDA 扫码定位；同仓同品同类型唯一。</span>
      <div class="spacer"></div>
      <button class="btn" @click="openLog">变更日志</button>
      <button class="btn" @click="load">刷新</button>
      <button class="btn primary" @click="openBind">＋ 新建绑定</button>
    </div>
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="query">
      <div class="fi"><label>仓库</label>
        <select v-model="fWarehouse" @change="onWarehouseChange">
          <option value="">全部</option>
          <option v-for="w in warehouses" :key="w.warehouseId" :value="w.warehouseName">{{ w.warehouseName }}</option>
        </select></div>
      <div class="fi"><label>库区</label>
        <select v-model="fZone">
          <option value="">全部</option>
          <option v-for="z in zones" :key="z.zoneId" :value="z.zoneCode">{{ z.zoneCode }} · {{ z.zoneName }}</option>
        </select></div>
      <div class="fi"><label>库位</label><input v-model="fBin" placeholder="精确库位编码" @keydown.enter="load" /></div>
      <div class="fi"><label>商品</label><input v-model="fKeyword" placeholder="编码/名称" @keydown.enter="load" /></div>
      <label class="ck"><input type="checkbox" v-model="fActiveOnly" @change="load" /> 仅看生效</label>
      <button class="btn primary" @click="load">查询</button>
      <button class="btn" @click="fBin=''; fKeyword=''; fZone=''; load()">重置</button>
    </div>

    <div class="tablebox">
      <div class="toolbar">
        <b>绑定列表（{{ list.length }}）</b>
        <div class="spacer"></div>
        <span class="muted">{{ pdaHint }}</span>
      </div>
      <div class="scroll">
        <table class="data">
          <thead>
            <tr>
              <th>仓库</th><th>库区</th><th>库位</th><th>商品</th><th>类型</th>
              <th class="num">在库</th><th class="num">补货阈值</th><th class="num">容量上限</th><th class="num">补到</th>
              <th>状态</th><th class="ops">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in list" :key="r.bindingId">
              <td>{{ r.warehouse }}</td>
              <td>{{ r.zoneCode }}</td>
              <td class="bin">{{ r.binCode }}</td>
              <td>{{ r.goodsName }}<div class="sub">{{ r.goodsCode }}</div></td>
              <td>{{ bindingText[r.bindingType] || r.bindingType }}</td>
              <td class="num" :class="{ low: Number(r.minQty)>0 && Number(r.onHandQty) < Number(r.minQty) }">{{ r.onHandQty }}</td>
              <td class="num">{{ r.minQty }}</td>
              <td class="num">{{ r.maxQty }}</td>
              <td class="num">{{ r.replenishToQty }}</td>
              <td><span class="tag" :class="r.activeFlag==='Y'?'ok':'warn'">
                {{ r.activeFlag==='Y' ? '生效' : '已停用' }}</span></td>
              <td class="ops">
                <a v-if="r.activeFlag==='Y'" @click="openThreshold(r)">阈值</a>
                <a v-if="r.activeFlag==='Y'" @click="openTransfer(r)">转移</a>
                <a v-if="r.activeFlag==='Y'" class="danger" @click="doUnbind(r)">解绑</a>
              </td>
            </tr>
            <tr v-if="!list.length"><td colspan="11" class="empty">暂无绑定，点右上角「新建绑定」或用 PDA 扫码绑定</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 新建绑定 -->
    <div v-if="bindOpen" class="modal-mask" @click.self="bindOpen=false">
      <div class="modal w560">
        <div class="modal-h">新建拣货位绑定<span class="x" @click="bindOpen=false">×</span></div>
        <div class="modal-b">
          <div class="form-row"><label>仓库 *</label><input :value="bindForm.warehouse" disabled /></div>
          <div class="form-row"><label>库区 *</label>
            <select v-model="bindForm.zoneCode">
              <option value="">请选择</option>
              <option v-for="z in zones" :key="z.zoneId" :value="z.zoneCode">{{ z.zoneCode }} · {{ z.zoneName }}</option>
            </select></div>
          <div class="form-row"><label>库位编码 *</label><input v-model="bindForm.binCode" placeholder="如 B-01-01" /></div>
          <div class="form-row"><label>商品编码 *</label><input v-model="bindForm.goodsCode" placeholder="扫描或输入" /></div>
          <div class="form-row"><label>商品名称</label><input v-model="bindForm.goodsName" /></div>
          <div class="form-row"><label>绑定类型</label>
            <select v-model="bindForm.bindingType">
              <option v-for="t in BINDING_TYPES" :key="t.v" :value="t.v">{{ t.l }}</option>
            </select></div>
          <div class="form-row"><label>补货阈值</label><input v-model.number="bindForm.minQty" type="number" min="0" /></div>
          <div class="form-row"><label>容量上限</label><input v-model.number="bindForm.maxQty" type="number" min="0" /></div>
          <div class="form-row"><label>补货补到</label><input v-model.number="bindForm.replenishToQty" type="number" min="0" /></div>
          <div class="form-row"><label>备注</label><input v-model="bindForm.remark" /></div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="bindOpen=false">取消</button>
          <button class="btn primary" @click="doBind">保存</button>
        </div>
      </div>
    </div>

    <!-- 阈值调整 -->
    <div v-if="thresholdOpen" class="modal-mask" @click.self="thresholdOpen=false">
      <div class="modal w480">
        <div class="modal-h">阈值调整：{{ thresholdForm.binCode }} · {{ thresholdForm.goodsCode }}<span class="x" @click="thresholdOpen=false">×</span></div>
        <div class="modal-b">
          <div class="form-row"><label>补货阈值</label><input v-model.number="thresholdForm.minQty" type="number" min="0" /></div>
          <div class="form-row"><label>容量上限</label><input v-model.number="thresholdForm.maxQty" type="number" min="0" /></div>
          <div class="form-row"><label>补货补到</label><input v-model.number="thresholdForm.replenishToQty" type="number" min="0" /></div>
          <div class="form-row"><label>备注</label><input v-model="thresholdForm.remark" /></div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="thresholdOpen=false">取消</button>
          <button class="btn primary" @click="doThreshold">保存</button>
        </div>
      </div>
    </div>

    <!-- 转移绑定 -->
    <div v-if="transferOpen" class="modal-mask" @click.self="transferOpen=false">
      <div class="modal w520">
        <div class="modal-h">转移绑定<span class="x" @click="transferOpen=false">×</span></div>
        <div class="modal-b">
          <div class="wiz-step">将商品 <b>{{ transferForm.goodsCode }}</b>（{{ transferForm.goodsName }}）从
            库位 <b class="bin">{{ transferForm.fromBin }}</b> 转移到新库位</div>
          <div class="form-row"><label>新库区</label>
            <select v-model="transferForm.toZoneCode">
              <option value="">请选择</option>
              <option v-for="z in zones" :key="z.zoneId" :value="z.zoneCode">{{ z.zoneCode }} · {{ z.zoneName }}</option>
            </select></div>
          <div class="form-row"><label>新库位 *</label><input v-model="transferForm.toBin" placeholder="扫描或输入新库位" /></div>
          <div class="wiz-tip">旧库位上的绑定记录会保留（active_flag=N），并写一条 TRANSFER 日志；PDA 同样支持此操作。</div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="transferOpen=false">取消</button>
          <button class="btn primary" @click="doTransfer">确认转移</button>
        </div>
      </div>
    </div>

    <!-- 变更日志抽屉 -->
    <div v-if="logOpen" class="drawer-mask" @click.self="logOpen=false">
      <div class="drawer">
        <div class="drawer-h">绑定变更日志<span class="x" @click="logOpen=false">×</span></div>
        <div class="drawer-b">
          <table class="data">
            <thead><tr><th>时间</th><th>来源</th><th>动作</th><th>仓库</th><th>商品</th><th>源库位</th><th>目标库位</th><th>操作人</th><th>备注</th></tr></thead>
            <tbody>
              <tr v-for="l in logs" :key="l.logId">
                <td>{{ l.occurredAt }}</td>
                <td><span class="tag" :class="l.source==='PDA'?'pda':'pc'">{{ sourceText[l.source] || l.source }}</span></td>
                <td>{{ changeText[l.changeType] || l.changeType }}</td>
                <td>{{ l.warehouse }}</td>
                <td>{{ l.goodsCode }}</td>
                <td class="bin">{{ l.fromBin || '—' }}</td>
                <td class="bin">{{ l.toBin || '—' }}</td>
                <td>{{ l.operator }}</td>
                <td class="muted">{{ l.remark || '—' }}</td>
              </tr>
              <tr v-if="!logs.length"><td colspan="9" class="empty">暂无日志</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tip { color: #874d00; background: #fffbe6; border: 1px solid #ffe58f; padding: 4px 10px; border-radius: 6px; font-size: 12px; margin-left: 10px; }
.spacer { flex: 1; }
.query { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; background: #fff; padding: 10px 12px; border: 1px solid #f0f0f0; border-radius: 8px; margin: 10px 0; }
.fi { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.fi label { color: #555; }
.fi input, .fi select { padding: 5px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; min-width: 140px; }
.ck { font-size: 13px; color: #555; display: flex; align-items: center; gap: 4px; }
.tablebox { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; overflow: hidden; }
.toolbar { display: flex; align-items: center; padding: 10px 12px; border-bottom: 1px solid #f0f0f0; background: #fafafa; }
.scroll { overflow: auto; max-height: calc(100vh - 320px); }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
.data th { background: #fafafa; font-weight: 600; color: #555; }
.num { text-align: right; }
.num.low { color: #cf1322; font-weight: 600; }
.bin { font-family: monospace; color: #1677ff; }
.sub { color: #aaa; font-size: 11px; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.warn { background: #fff1f0; color: #cf1322; }
.tag.pda { background: #fff7e6; color: #d46b08; }
.tag.pc { background: #f0f5ff; color: #2f54eb; }
.ops { white-space: nowrap; text-align: right; }
.ops a { color: #1677ff; cursor: pointer; margin-left: 10px; font-size: 12px; }
.ops a.danger { color: #cf1322; }
.empty { text-align: center; color: #bbb; padding: 24px; }
.muted { color: #999; font-size: 12px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 560px; max-width: 92vw; max-height: 90vh; overflow: auto; }
.modal.w560 { width: 560px; } .modal.w480 { width: 480px; } .modal.w520 { width: 520px; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
.form-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.form-row label { width: 90px; color: #555; font-size: 13px; }
.form-row input, .form-row select { flex: 1; padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; }
.wiz-step { background: #f0f7ff; color: #1677ff; padding: 8px 12px; border-radius: 6px; margin-bottom: 14px; font-size: 13px; line-height: 1.8; }
.wiz-tip { background: #fffbe6; border: 1px solid #ffe58f; color: #874d00; padding: 8px 12px; border-radius: 6px; font-size: 12px; line-height: 1.6; margin-top: 8px; }
.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,.3); z-index: 100; display: flex; justify-content: flex-end; }
.drawer { background: #fff; width: 900px; max-width: 95vw; height: 100%; display: flex; flex-direction: column; box-shadow: -2px 0 8px rgba(0,0,0,.1); }
.drawer-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.drawer-h .x { cursor: pointer; color: #999; }
.drawer-b { flex: 1; overflow: auto; padding: 12px; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-bottom: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
</style>
