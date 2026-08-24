<script setup>
/**
 * WMS V1.5 预分配 / 下放（V83 重构，PRD-28）
 *
 * 交互：
 *   - 左侧：线路列表（按 base_customer.route_line 分组的待下放订单统计）
 *           含「未分配线路」行（客户未配置线路的订单）
 *   - 右侧：选中线路下的未分配销售订单（已审核、未出库、未进入任何波次）
 *   - 勾选右侧订单 → 下放组波（拣货策略/批次策略/集货区等可在弹窗内改，
 *     默认带入选中线路 + 该线路配置的司机）
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { post } from '../../api/client.js'

const route = useRoute()
const loading = ref(false)
const feedback = ref('')
const routes = ref([])
const orders = ref([])
const currentRouteLine = ref('')
const fWarehouse = ref('')
const fKeyword = ref('')
const checked = ref(new Set())

const PICK_MODES = [
  { v: '0', l: '按单拣' },
  { v: '1', l: '汇总拣(先拣后分)' },
  { v: '2', l: '边拣边分' },
  { v: '3', l: '分区接力' },
  { v: '4', l: '越库直发' },
]
const STRATEGIES = [
  { v: '0', l: '近效期先出 FEFO' },
  { v: '1', l: '先进先出 FIFO' },
  { v: '2', l: '指定批次' },
]

const wizardOpen = ref(false)
const form = ref(blankForm())
const releasing = ref(false)
const collectZones = ref([]) // 可用集货区（frozen=N），下拉动态加载
function blankForm() {
  return { pickMode: '0', batchStrategy: '0', routeLine: '', driver: '', vehiclePlate: '', collectionZone: '' }
}

async function loadCollectZones() {
  try {
    const res = await post('/wms/collection-zone/page', { pageNo: 1, pageSize: 500, filters: {} })
    // 仅可选用未停用的集货区
    collectZones.value = (res.records || []).filter(z => z.frozen !== 'Y')
  } catch (e) { collectZones.value = [] }
}

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 2500)
}

async function loadRoutes() {
  try {
    routes.value = await post('/wms/route-pool', { warehouse: fWarehouse.value })
    // 如果当前选中的线路已不在结果里（被清空），重置
    if (currentRouteLine.value
        && !routes.value.find(r => r.routeLine === currentRouteLine.value)) {
      currentRouteLine.value = routes.value[0]?.routeLine || ''
    } else if (!currentRouteLine.value && routes.value.length) {
      currentRouteLine.value = routes.value[0].routeLine
    }
  } catch (e) { show('线路统计加载失败：' + (e?.message || e), 'err') }
}
async function loadOrders() {
  loading.value = true
  checked.value = new Set()
  try {
    orders.value = await post('/wms/order-pool/by-route', {
      warehouse: fWarehouse.value,
      routeLine: currentRouteLine.value,
      keyword: fKeyword.value,
    })
  } catch (e) {
    orders.value = []
    show('订单加载失败：' + (e?.message || e), 'err')
  } finally {
    loading.value = false
  }
}

function selectRoute(rl) {
  currentRouteLine.value = rl
  loadOrders()
}
async function reloadAll() {
  await loadRoutes()
  await loadOrders()
}
function onSearch() { loadOrders() }
function onWarehouseChange() { reloadAll() }

function toggle(no) {
  const n = String(no)
  checked.value.has(n) ? checked.value.delete(n) : checked.value.add(n)
  checked.value = new Set(checked.value)
}
function toggleAll() {
  if (checked.value.size === orders.value.length) checked.value = new Set()
  else checked.value = new Set(orders.value.map(o => String(o.orderNo)))
}
const selectedOrders = computed(() => orders.value.filter(o => checked.value.has(String(o.orderNo))))
const totalPcs = computed(() => orders.value.reduce((s, o) => s + Number(o.pieceCount || 0), 0))
const totalAmount = computed(() => orders.value.reduce((s, o) => s + Number(o.amount || 0), 0))

const currentRouteObj = computed(() => routes.value.find(r => r.routeLine === currentRouteLine.value))

function openWizard() {
  if (!checked.value.size) return show('请先勾选要下放的订单', 'err')
  const r = currentRouteObj.value
  form.value = {
    ...blankForm(),
    routeLine: r?.assigned ? r.routeLine : '',
    driver: r?.driver || '',
    vehiclePlate: r?.vehiclePlate || '',
    collectionZone: collectZones.value[0]?.zoneCode || '',
  }
  wizardOpen.value = true
}

async function doRelease() {
  releasing.value = true
  try {
    const res = await post('/wms/wave/release', {
      orderNos: selectedOrders.value.map(o => o.orderNo),
      pickMode: form.value.pickMode,
      batchStrategy: form.value.batchStrategy,
      routeLine: form.value.routeLine,
      driver: form.value.driver,
      collectionZone: form.value.collectionZone,
    })
    wizardOpen.value = false
    show(`下放成功：波次 ${res.waveNo}，${res.orderCount} 单 / ${res.lineCount} 行 / ${res.totalQty} 件`)
    await reloadAll()
  } catch (e) {
    show('下放失败：' + (e?.message || e), 'err')
  } finally {
    releasing.value = false
  }
}

onMounted(() => { loadCollectZones(); reloadAll() })
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>预分配 / 下放</b>
      <span class="tip">左侧按线路看待下放订单，右侧勾选订单后下放组波；可跨线路合并到同一次组波。</span>
      <div class="spacer"></div>
      <label class="fi">仓库 <input v-model="fWarehouse" placeholder="留空=全部" @change="onWarehouseChange" /></label>
      <button class="btn" @click="reloadAll">刷新</button>
      <button class="btn primary" :disabled="!checked.size" @click="openWizard">下放组波（{{ checked.size }}）</button>
    </div>
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="tms-stat-row">
      <div class="tms-stat-card"><div class="num">{{ routes.reduce((s,r)=>s+Number(r.orderCount||0),0) }}</div><div class="lbl">待下放订单</div></div>
      <div class="tms-stat-card ok"><div class="num">{{ routes.reduce((s,r)=>s+Number(r.pieceCount||0),0) }}</div><div class="lbl">待拣总件数</div></div>
      <div class="tms-stat-card warn"><div class="num">¥{{ routes.reduce((s,r)=>s+Number(r.amount||0),0).toLocaleString() }}</div><div class="lbl">订单金额</div></div>
      <div class="tms-stat-card"><div class="num">{{ checked.size }}</div><div class="lbl">已勾选</div></div>
    </div>

    <div class="split">
      <!-- 左：线路列表 -->
      <aside class="left">
        <div class="left-h">线路列表（{{ routes.length }}）</div>
        <div class="left-scroll">
          <div v-for="r in routes" :key="r.routeLine || '__NONE__'"
               class="route-item" :class="{ on: r.routeLine === currentRouteLine }"
               @click="selectRoute(r.routeLine)">
            <div class="route-h">
              <span class="dot" :class="{ unassigned: !r.assigned }"></span>
              <b>{{ r.routeName }}</b>
              <span class="cnt">{{ r.orderCount }}</span>
            </div>
            <div class="route-meta">
              <span v-if="r.driver">司机：{{ r.driver }}</span>
              <span v-else class="muted">未指派司机</span>
              <span v-if="r.vehiclePlate"> · {{ r.vehiclePlate }}</span>
              <span v-if="r.vehicleType"> · {{ r.vehicleType }}</span>
            </div>
            <div class="route-meta">
              <span class="pcs">{{ r.pieceCount }} 件</span>
              <span class="amt">¥{{ Number(r.amount||0).toLocaleString() }}</span>
            </div>
          </div>
          <div v-if="!routes.length" class="empty">暂无待下放订单</div>
        </div>
      </aside>

      <!-- 右：订单列表 -->
      <section class="right">
        <div class="right-h">
          <span v-if="currentRouteObj">
            <b>{{ currentRouteObj.routeName }}</b>
            <span class="muted">· 共 {{ orders.length }} 张订单 / {{ totalPcs }} 件 / ¥{{ totalAmount.toLocaleString() }}</span>
          </span>
          <span v-else class="muted">请在左侧选择线路</span>
          <div class="spacer"></div>
          <input v-model="fKeyword" placeholder="订单号/客户" @keydown.enter="onSearch" />
          <button class="btn" @click="onSearch">查询</button>
        </div>
        <div class="right-scroll">
          <table class="data">
            <thead>
              <tr>
                <th class="ck"><input type="checkbox" :checked="orders.length && checked.size===orders.length" @change="toggleAll" /></th>
                <th>订单号</th><th>客户</th><th>业务员</th><th>线路</th><th>仓库</th>
                <th class="num">SKU</th><th class="num">件数</th><th class="num">金额</th><th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="o in orders" :key="o.orderNo" :class="{ sel: checked.has(String(o.orderNo)) }" @dblclick="toggle(o.orderNo)">
                <td class="ck"><input type="checkbox" :checked="checked.has(String(o.orderNo))" @change="toggle(o.orderNo)" /></td>
                <td>{{ o.orderNo }}</td><td>{{ o.customer }}</td><td>{{ o.salesman }}</td>
                <td><span v-if="o.routeLine">{{ o.routeLine }}</span><span v-else class="muted">未分配</span></td>
                <td>{{ o.warehouse }}</td>
                <td class="num">{{ o.skuCount }}</td><td class="num">{{ o.pieceCount }}</td>
                <td class="num">¥{{ Number(o.amount||0).toLocaleString() }}</td>
                <td><span class="tag ok">已审核</span></td>
              </tr>
              <tr v-if="!orders.length"><td colspan="10" class="empty">该线路下暂无待下放订单</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <!-- 下放向导弹窗 -->
    <div v-if="wizardOpen" class="modal-mask" @click.self="wizardOpen=false">
      <div class="modal w640">
        <div class="modal-h">下放组波<span class="x" @click="wizardOpen=false">×</span></div>
        <div class="modal-b">
          <div class="wiz-step">① 已选 {{ checked.size }} 张订单 / {{ selectedOrders.reduce((s,o)=>s+Number(o.pieceCount||0),0) }} 件</div>
          <div class="form-row">
            <label>拣货策略（本次可改）</label>
            <select v-model="form.pickMode">
              <option v-for="m in PICK_MODES" :key="m.v" :value="m.v">{{ m.l }}</option>
            </select>
          </div>
          <div class="form-row">
            <label>批次分配策略</label>
            <select v-model="form.batchStrategy">
              <option v-for="s in STRATEGIES" :key="s.v" :value="s.v">{{ s.l }}</option>
            </select>
          </div>
          <div class="form-row">
            <label>线路</label><input v-model="form.routeLine" placeholder="可留空，按订单/客户" />
          </div>
          <div class="form-row">
            <label>司机</label><input v-model="form.driver" placeholder="可留空，装车前指派" />
          </div>
          <div class="form-row">
            <label>集货区</label>
            <select v-model="form.collectionZone">
              <option v-for="z in collectZones" :key="z.zoneId" :value="z.zoneCode">
                {{ z.zoneName }}（{{ z.zoneCode }}）
              </option>
            </select>
            <span v-if="!collectZones.length" class="wiz-tip">暂无可用集货区，请到「集货区管理」新建并启用</span>
          </div>
          <div class="wiz-tip">系统将按策略自动分配批次（FEFO/FIFO）与拣货库位、预占批次锁，并按库区生成拣货任务。撤销下放会释放预占。</div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="wizardOpen=false">取消</button>
          <button class="btn primary" :disabled="releasing" @click="doRelease">{{ releasing ? '下放中...' : '确认下放' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tip { color: #874d00; background: #fffbe6; border: 1px solid #ffe58f; padding: 4px 10px; border-radius: 6px; font-size: 12px; margin-left: 10px; }
.spacer { flex: 1; }
.fi { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #555; }
.fi input { padding: 5px 10px; border: 1px solid #d9d9d9; border-radius: 6px; width: 140px; }
.tms-stat-row { display: grid; grid-template-columns: repeat(4,1fr); gap: 12px; margin: 12px 0; }
.tms-stat-card { background: #fff; border: 1px solid #eee; border-radius: 8px; padding: 14px 16px; }
.tms-stat-card.ok { border-color: #b7eb8f; background: #f6ffed; }
.tms-stat-card.warn { border-color: #ffe58f; background: #fffbe6; }
.tms-stat-card .num { font-size: 22px; font-weight: 700; }
.tms-stat-card .lbl { color: #888; font-size: 12px; margin-top: 4px; }
.split { display: grid; grid-template-columns: 320px 1fr; gap: 12px; height: calc(100vh - 260px); min-height: 480px; }
.left, .right { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; display: flex; flex-direction: column; overflow: hidden; }
.left-h, .right-h { padding: 10px 14px; border-bottom: 1px solid #f0f0f0; background: #fafafa; font-size: 13px; display: flex; align-items: center; gap: 10px; }
.left-scroll, .right-scroll { overflow: auto; flex: 1; }
.right-h input { padding: 5px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; width: 180px; }
.route-item { padding: 10px 14px; border-bottom: 1px solid #f5f5f5; cursor: pointer; }
.route-item:hover { background: #fafcff; }
.route-item.on { background: #e6f4ff; border-left: 3px solid #1677ff; padding-left: 11px; }
.route-h { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.route-h .dot { width: 8px; height: 8px; border-radius: 50%; background: #52c41a; display: inline-block; }
.route-h .dot.unassigned { background: #faad14; }
.route-h .cnt { margin-left: auto; background: #1677ff; color: #fff; font-size: 11px; padding: 1px 8px; border-radius: 10px; }
.route-meta { display: flex; gap: 10px; color: #888; font-size: 12px; margin-top: 4px; }
.route-meta .pcs { color: #1677ff; }
.route-meta .amt { color: #389e0d; }
.muted { color: #999; font-size: 12px; }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
.data th { background: #fafafa; color: #555; font-weight: 600; }
.data td.num, .data th.num { text-align: right; }
.data tr.sel { background: #e6f4ff; }
.data tr:hover { background: #fafcff; cursor: pointer; }
.ck { width: 36px; }
.empty { text-align: center; color: #aaa; padding: 24px; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; }
.tag.ok { background: #f6ffed; color: #389e0d; border: 1px solid #b7eb8f; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 560px; max-width: 92vw; max-height: 90vh; overflow: auto; }
.modal.w640 { width: 640px; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
.wiz-step { background: #f0f7ff; color: #1677ff; padding: 8px 12px; border-radius: 6px; margin-bottom: 14px; font-size: 13px; }
.form-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.form-row label { width: 130px; color: #555; font-size: 13px; }
.form-row input, .form-row select { flex: 1; padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; }
.wiz-tip { background: #fffbe6; border: 1px solid #ffe58f; color: #874d00; padding: 8px 12px; border-radius: 6px; font-size: 12px; line-height: 1.6; margin-top: 8px; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-bottom: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
</style>
