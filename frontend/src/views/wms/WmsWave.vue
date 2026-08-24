<script setup>
/**
 * WMS V1.5 波次管理（PRD-28）：左波次列表 / 右选中波次的订单明细；整波/单订单加急、撤销下放。
 */
import { ref, onMounted } from 'vue'
import { post, get } from '../../api/client.js'

const loading = ref(false)
const feedback = ref('')
const waves = ref([])
const current = ref(null)
const detail = ref(null)
const fStatus = ref('')
const supervisor = ref(false)

const STEPS = ['已下放', '拣货中', '拣货完成', '复核中', '已出库', '已发运']
const STATUS_ORDER = { RELEASED: 0, PICKING: 1, PICKED: 2, CHECKING: 3, CHECKED: 4, SHIPPED: 5, SUSPENDED: 1, CANCELLED: -1 }
const pickModeText = (m) => ({ '0': '按单拣', '1': '汇总拣', '2': '边拣边分', '3': '分区接力', '4': '越库直发', ORDER: '按单拣', SUMMARY: '汇总拣', PICK_SORT: '边拣边分', ZONE_RELAY: '分区接力', CROSS_DOCK: '越库直发' }[m] || m || '—')

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 3000) }

async function load() {
  loading.value = true
  try {
    const res = await post('/wms/wave/page', { pageNo: 1, pageSize: 100, filters: { status: fStatus.value } })
    waves.value = res.records || []
    if (waves.value.length && !current.value) selectWave(waves.value[0])
    else if (current.value) selectWave(waves.value.find(w => w.waveId === current.value.waveId) || null)
  } catch (e) {
    waves.value = []; show('加载失败：' + (e?.message || e))
  } finally { loading.value = false }
}

async function selectWave(w) {
  current.value = w
  if (!w) { detail.value = null; return }
  try {
    detail.value = await get('/wms/wave/detail?waveId=' + w.waveId)
  } catch (e) {
    detail.value = null; show('明细加载失败：' + (e?.message || e))
  }
}

function stepIndex(status) { return STATUS_ORDER[status] ?? -1 }

async function expediteOrder(orderNo, on) {
  try {
    await post('/wms/wave/order-expedite', { waveId: current.value.waveId, orderNo, expedited: on ? 'Y' : 'N' })
    show((on ? '已加急：' : '已取消加急：') + orderNo); selectWave(current.value)
  } catch (e) { show('操作失败：' + (e?.message || e)) }
}
async function expediteWave(on) {
  try {
    await post('/wms/wave/expedite', { waveId: current.value.waveId, expedited: on ? 'N' : 'Y' })
    show(on ? '已取消整波加急' : '整波加急'); load()
  } catch (e) { show('操作失败：' + (e?.message || e)) }
}
async function cancelOrder(orderNo) {
  if (!confirm(`撤销订单 ${orderNo} 的下放？未拣货将释放批次锁与库位预占。`)) return
  try {
    await post('/wms/wave/cancel', { waveId: current.value.waveId, orderNo, supervisor: supervisor.value ? 'Y' : 'N' })
    show('已撤销：' + orderNo); load()
  } catch (e) {
    if (/主管/.test(String(e?.message || e))) {
      if (confirm('该订单已有拣货记录，需要主管权限。以主管身份继续？')) {
        supervisor.value = true
        await post('/wms/wave/cancel', { waveId: current.value.waveId, orderNo, supervisor: 'Y' })
        show('已撤销：' + orderNo); load()
      }
    } else show('撤销失败：' + (e?.message || e))
  }
}
async function cancelWave() {
  if (!confirm('整波撤销？将释放所有未拣货的批次锁并删除波次。')) return
  try {
    await post('/wms/wave/cancel', { waveId: current.value.waveId, supervisor: supervisor.value ? 'Y' : 'N' })
    show('整波已撤销'); current.value = null; load()
  } catch (e) { show('撤销失败：' + (e?.message || e)) }
}

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="load">刷新</button>
      <select v-model="fStatus" @change="load" class="sel">
        <option value="">全部状态</option>
        <option value="RELEASED">已下放</option>
        <option value="PICKING">拣货中</option>
        <option value="PICKED">拣货完成</option>
        <option value="CHECKING">复核中</option>
        <option value="CHECKED">复核完成</option>
        <option value="SHIPPED">已发运</option>
        <option value="SUSPENDED">差异挂起</option>
        <option value="CANCELLED">已撤销</option>
      </select>
    </div>
    <div v-if="feedback" class="toast-inline">{{ feedback }}</div>

    <div class="wave-split">
      <div class="wave-left tablebox">
        <div class="toolbar"><b>波次</b><span class="muted">{{ waves.length }}</span></div>
        <div class="scroll">
          <div v-for="w in waves" :key="w.waveId" class="wave-card" :class="{ on: current && current.waveId===w.waveId }" @click="selectWave(w)">
            <div class="wc-h">
              <b>{{ w.waveNo }}</b>
              <span class="st" :class="'st-'+w.status.toLowerCase()">{{ w.statusText }}</span>
              <span v-if="w.expedited==='Y'" class="tag hot">加急</span>
            </div>
            <div class="wc-m">{{ pickModeText(w.pickMode) }} · {{ w.orderCount }}单 · {{ w.lineCount }}行 · {{ w.totalQty }}件</div>
            <div class="wc-b">
              <span v-for="(s,i) in STEPS" :key="s" class="node" :class="{ done: stepIndex(w.status) > i, cur: stepIndex(w.status)===i }">{{ s }}</span>
            </div>
          </div>
          <div v-if="!waves.length" class="empty">暂无波次</div>
        </div>
      </div>

      <div class="wave-right tablebox">
        <div class="toolbar">
          <b>{{ detail ? detail.waveNo : '订单明细' }}</b>
          <div class="spacer"></div>
          <template v-if="detail">
            <button class="btn" @click="expediteWave(detail.expedited==='Y')">{{ detail.expedited==='Y' ? '取消整波加急' : '整波加急' }}</button>
            <button class="btn danger" @click="cancelWave">整波撤销</button>
          </template>
        </div>
        <div class="scroll">
          <table class="data" v-if="detail">
            <thead><tr><th>订单号</th><th>客户</th><th class="num">应拣</th><th class="num">已拣</th><th>状态</th><th>集货位</th><th>出库单</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="o in detail.orders" :key="o.sourceOrderNo">
                <td>{{ o.sourceOrderNo }}</td>
                <td>{{ o.customerName }}</td>
                <td class="num">{{ o.requiredQty }}</td>
                <td class="num">{{ o.pickedQty }}</td>
                <td><span class="tag" :class="o.status==='PICKED'?'ok':(o.status==='SHORT'?'warn':'')">{{ o.status }}</span></td>
                <td>{{ o.lines[0]?.collectionBinCode }}</td>
                <td>{{ o.lines[0]?.generatedOutboundNo || '—' }}</td>
                <td class="ops">
                  <a v-if="o.lines[0]?.expedited!=='Y'" @click="expediteOrder(o.sourceOrderNo, true)">加急</a>
                  <a v-else @click="expediteOrder(o.sourceOrderNo, false)">取消加急</a>
                  <a class="danger" @click="cancelOrder(o.sourceOrderNo)">撤销下放</a>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty">选择左侧波次查看订单</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sel { padding: 5px 8px; border: 1px solid #d9d9d9; border-radius: 6px; }
.wave-split { display: grid; grid-template-columns: 360px 1fr; gap: 12px; height: calc(100vh - 180px); }
.wave-left .scroll { max-height: none; }
.wave-card { border: 1px solid #eee; border-radius: 8px; padding: 10px 12px; margin: 8px; cursor: pointer; }
.wave-card.on { border-color: #1677ff; box-shadow: 0 0 0 2px rgba(22,119,255,.12); }
.wc-h { display: flex; align-items: center; gap: 8px; }
.wc-m { color: #888; font-size: 12px; margin: 4px 0 8px; }
.wc-b { display: flex; gap: 4px; flex-wrap: wrap; }
.node { font-size: 10px; color: #bbb; }
.node:not(:last-child)::after { content: '›'; margin: 0 3px; color: #ddd; }
.node.done { color: #389e0d; }
.node.cur { color: #1677ff; font-weight: 700; }
.st { font-size: 11px; padding: 1px 7px; border-radius: 10px; background: #f0f0f0; color: #555; }
.st-picked, .st-checked { background: #f6ffed; color: #389e0d; }
.st-picking, .st-checking { background: #e6f4ff; color: #1677ff; }
.st-shipped { background: #f9f0ff; color: #722ed1; }
.st-suspended { background: #fff1f0; color: #cf1322; }
.st-cancelled { background: #f5f5f5; color: #999; }
.tag { display: inline-block; padding: 1px 7px; border-radius: 10px; font-size: 11px; background: #f0f0f0; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.warn { background: #fff1f0; color: #cf1322; }
.tag.hot { background: #fff1f0; color: #cf1322; border: 1px solid #ffa39e; }
table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
table.data th, table.data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
table.data th { background: #fafafa; font-weight: 600; color: #555; }
.num { text-align: right; }
.ops a { color: #1677ff; cursor: pointer; margin-right: 10px; }
.ops a.danger { color: #cf1322; }
.empty { text-align: center; color: #aaa; padding: 24px; }
.muted { color: #999; font-size: 12px; margin-left: 6px; }
</style>
