<script setup>
/**
 * WMS V1.5 装车 / 发运（PRD-28）：仅列出复核完成(CHECKED)的波次，查看可装车订单，确认发运（写交接，对接 TMS）。
 * 装车不扣库存——扣账在复核通过时已完成。
 */
import { ref, onMounted } from 'vue'
import { post, get } from '../../api/client.js'

const loading = ref(false)
const feedback = ref('')
const waves = ref([])
const current = ref(null)
const loadable = ref([])
const shipOpen = ref(false)
const vehiclePlate = ref('')
const shipping = ref(false)

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 3000) }

async function load() {
  loading.value = true
  try {
    const res = await post('/wms/wave/page', { pageNo: 1, pageSize: 100, filters: { status: 'CHECKED' } })
    waves.value = res.records || []
    if (waves.value.length && !current.value) select(waves.value[0])
  } catch (e) { waves.value = []; show('加载失败：' + (e?.message || e)) }
  finally { loading.value = false }
}

async function select(w) {
  current.value = w
  try { loadable.value = await get('/wms/load/loadable?waveId=' + w.waveId) }
  catch (e) { loadable.value = [] }
}

function openShip() {
  if (!loadable.value.length) return show('暂无可发运订单')
  vehiclePlate.value = ''; shipOpen.value = true
}
async function doShip() {
  if (!vehiclePlate.value.trim()) return show('请输入车牌号')
  shipping.value = true
  try {
    const res = await post('/wms/load/ship', { waveId: current.value.waveId, vehiclePlate: vehiclePlate.value.trim() })
    shipOpen.value = false
    show(`已发运：${res.shippedOrders} 张订单，车牌 ${vehiclePlate.value}（已写交接并标记发货单发运）`)
    current.value = null; load()
  } catch (e) { show('发运失败：' + (e?.message || e)) }
  finally { shipping.value = false }
}

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="load">刷新</button>
      <span class="tip">仅「复核完成（已扣库存）」的波次可装车发运；装车不再扣库存</span>
    </div>
    <div v-if="feedback" class="toast-inline">{{ feedback }}</div>

    <div class="load-split">
      <div class="tablebox">
        <div class="toolbar"><b>待发运波次</b></div>
        <div class="scroll">
          <div v-for="w in waves" :key="w.waveId" class="wcard" :class="{ on: current && current.waveId===w.waveId }" @click="select(w)">
            <b>{{ w.waveNo }}</b>
            <div class="sub">{{ w.orderCount }}单 · {{ w.totalQty }}件 · 司机 {{ w.driver || '—' }}</div>
            <div class="sub">线路 {{ w.routeLine || '—' }}</div>
          </div>
          <div v-if="!waves.length" class="empty">暂无待发运波次</div>
        </div>
      </div>

      <div class="tablebox">
        <div class="toolbar">
          <b>{{ current ? current.waveNo + ' · 可装车订单' : '装车清单' }}</b>
          <div class="spacer"></div>
          <button v-if="current" class="btn primary" @click="openShip">确认装车发运</button>
        </div>
        <div class="scroll">
          <table class="data" v-if="current">
            <thead><tr><th>订单号</th><th>客户</th><th>出库单</th><th>发货单</th><th>集货位</th></tr></thead>
            <tbody>
              <tr v-for="o in loadable" :key="o.sourceOrderNo">
                <td>{{ o.sourceOrderNo }}</td><td>{{ o.customerName }}</td>
                <td>{{ o.generatedOutboundNo }}</td><td>{{ o.receiptNo || '—' }}</td>
                <td>{{ o.collectionBinCode }}</td>
              </tr>
              <tr v-if="!loadable.length"><td colspan="5" class="empty">暂无可装车订单</td></tr>
            </tbody>
          </table>
          <div v-else class="empty">选择左侧波次</div>
        </div>
      </div>
    </div>

    <div v-if="shipOpen" class="modal-mask" @click.self="shipOpen=false">
      <div class="modal">
        <div class="modal-h">装车发运确认<span class="x" @click="shipOpen=false">×</span></div>
        <div class="modal-b">
          <div class="form-row"><label>车牌号</label><input v-model="vehiclePlate" placeholder="如 京A12345" /></div>
          <div class="chain">将记录装车交接（wms_handover），并把相关销售发货单置为已发运；WMS↔TMS 以销售订单号 source_order_no 关联。</div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="shipOpen=false">取消</button>
          <button class="btn primary" :disabled="shipping" @click="doShip">{{ shipping ? '发运中...' : '确认发运' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tip { color: #874d00; background: #fffbe6; border: 1px solid #ffe58f; padding: 4px 10px; border-radius: 6px; font-size: 12px; margin-left: 10px; }
.load-split { display: grid; grid-template-columns: 320px 1fr; gap: 12px; height: calc(100vh - 170px); }
.wcard { border: 1px solid #eee; border-radius: 8px; padding: 10px 12px; margin: 8px; cursor: pointer; }
.wcard.on { border-color: #1677ff; box-shadow: 0 0 0 2px rgba(22,119,255,.12); }
.wcard .sub { color: #999; font-size: 12px; margin-top: 4px; }
table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
table.data th, table.data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
table.data th { background: #fafafa; font-weight: 600; color: #555; }
.empty { text-align: center; color: #aaa; padding: 24px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 460px; max-width: 92vw; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.form-row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.form-row label { width: 70px; color: #555; }
.form-row input { flex: 1; padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; }
.chain { background: #f6f8fa; border-radius: 8px; padding: 12px; margin-top: 10px; font-size: 12px; color: #555; line-height: 1.7; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
</style>
