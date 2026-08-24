<script setup>
/**
 * WMS V1.5 复核 / 打包（PRD-28）：列出拣货完成/复核中的波次订单，集齐后一键复检通过 → 触发扣库存链。
 */
import { ref, onMounted } from 'vue'
import { post, get } from '../../api/client.js'

const loading = ref(false)
const feedback = ref('')
const waves = ref([])
const current = ref(null)
const orders = ref([])
const confirmOpen = ref(false)
const target = ref(null)
const checking = ref(false)

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 3200) }

async function load() {
  loading.value = true
  try {
    const res = await post('/wms/wave/page', { pageNo: 1, pageSize: 100, filters: { status: '' } })
    waves.value = (res.records || []).filter(w => ['PICKED', 'CHECKING', 'CHECKED'].includes(w.status))
    if (waves.value.length && !current.value) selectWave(waves.value[0])
  } catch (e) { waves.value = []; show('加载失败：' + (e?.message || e)) }
  finally { loading.value = false }
}

async function selectWave(w) {
  current.value = w
  try {
    const d = await get('/wms/wave/detail?waveId=' + w.waveId)
    // 只展示有已拣记录的订单
    orders.value = (d.orders || []).filter(o => Number(o.pickedQty) > 0 || !o.lines.every(l => l.status==='PENDING'))
  } catch (e) { orders.value = [] }
}

function gatherPct(o) {
  const r = Number(o.requiredQty) || 1, p = Number(o.pickedQty) || 0
  return Math.min(100, Math.round(p / r * 100))
}
const allGathered = o => Number(o.pickedQty) >= Number(o.requiredQty)

function openConfirm(o) {
  if (!allGathered(o)) return show('订单未集齐，不能复检通过（少货需走异常/补货）')
  target.value = o; confirmOpen.value = true
}

async function pass() {
  checking.value = true
  try {
    const res = await post('/wms/recheck/pass', { waveId: current.value.waveId, orderNo: target.value.sourceOrderNo, checkScope: '0' })
    confirmOpen.value = false
    show(`复检通过：出库单 ${res.outboundNo} → 已扣库存，发货单 ${res.receiptNo}`)
    load(); if (current.value) selectWave(current.value)
  } catch (e) { show('复检失败：' + (e?.message || e)) }
  finally { checking.value = false }
}

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="load">刷新</button>
      <span class="alert">复检策略：开关/抽核比例/高值阈值/少货是否放行均由 WMS_CHECK_* 参数控制；通过即生成出库单并扣库存</span>
    </div>
    <div v-if="feedback" class="toast-inline">{{ feedback }}</div>

    <div class="chk-split">
      <div class="tablebox">
        <div class="toolbar"><b>待复核波次</b></div>
        <div class="scroll">
          <div v-for="w in waves" :key="w.waveId" class="wcard" :class="{ on: current && current.waveId===w.waveId }" @click="selectWave(w)">
            <b>{{ w.waveNo }}</b>
            <span class="st">{{ w.statusText }}</span>
            <div class="sub">{{ w.orderCount }}单 · 已拣 {{ w.pickedQty }}/{{ w.totalQty }}</div>
          </div>
          <div v-if="!waves.length" class="empty">暂无待复核波次</div>
        </div>
      </div>

      <div class="tablebox">
        <div class="toolbar"><b>{{ current ? current.waveNo + ' · 订单集齐进度' : '订单明细' }}</b></div>
        <div class="scroll">
          <div v-for="o in orders" :key="o.sourceOrderNo" class="order-card">
            <div class="oc-h">
              <b>{{ o.sourceOrderNo }}</b><span class="cust">{{ o.customerName }}</span>
              <div class="spacer"></div>
              <span class="tag" :class="o.lines[0]?.generatedOutboundNo ? 'ok' : (allGathered(o)?'wait':'')">
                {{ o.lines[0]?.generatedOutboundNo ? '已出库' : (allGathered(o) ? '待复检' : '集货中') }}
              </span>
            </div>
            <div class="prog"><div class="bar" :style="{ width: gatherPct(o)+'%' }"></div><span>{{ o.pickedQty }}/{{ o.requiredQty }}</span></div>
            <div class="oc-f">
              <span v-if="o.lines[0]?.generatedOutboundNo">出库单：{{ o.lines[0].generatedOutboundNo }}</span>
              <button v-else class="btn primary sm" :disabled="!allGathered(o)" @click="openConfirm(o)">一键复检通过</button>
            </div>
          </div>
          <div v-if="current && !orders.length" class="empty">该波次暂无已拣订单</div>
        </div>
      </div>
    </div>

    <div v-if="confirmOpen" class="modal-mask" @click.self="confirmOpen=false">
      <div class="modal">
        <div class="modal-h">复检通过确认<span class="x" @click="confirmOpen=false">×</span></div>
        <div class="modal-b">
          <p>订单 <b>{{ target?.sourceOrderNo }}</b> 已集齐，确认复检通过？</p>
          <div class="chain">
            <div>① 生成销售出库单</div>
            <div>② 审核即扣库存（InventoryCostService.salesOutbound）</div>
            <div>③ 生成销售发货单 sales_receipt</div>
            <div>④ 等待装车发运（装车不扣库存）</div>
          </div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="confirmOpen=false">取消</button>
          <button class="btn primary" :disabled="checking" @click="pass">{{ checking ? '处理中...' : '确认通过并扣库存' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.alert { color: #874d00; background: #fffbe6; border: 1px solid #ffe58f; padding: 4px 10px; border-radius: 6px; font-size: 12px; margin-left: 10px; }
.chk-split { display: grid; grid-template-columns: 320px 1fr; gap: 12px; height: calc(100vh - 170px); }
.wcard { border: 1px solid #eee; border-radius: 8px; padding: 10px 12px; margin: 8px; cursor: pointer; }
.wcard.on { border-color: #1677ff; box-shadow: 0 0 0 2px rgba(22,119,255,.12); }
.wcard .sub { color: #999; font-size: 12px; margin-top: 4px; }
.st { float: right; font-size: 11px; color: #389e0d; }
.order-card { border: 1px solid #f0f0f0; border-radius: 8px; padding: 12px; margin: 10px; }
.oc-h { display: flex; align-items: center; gap: 10px; }
.oc-h .cust { color: #888; font-size: 13px; }
.spacer { flex: 1; }
.prog { position: relative; height: 18px; background: #f0f0f0; border-radius: 9px; margin: 10px 0; overflow: hidden; }
.prog .bar { position: absolute; left: 0; top: 0; bottom: 0; background: linear-gradient(90deg,#52c41a,#389e0d); border-radius: 9px; }
.prog span { position: relative; font-size: 11px; color: #333; padding-left: 8px; line-height: 18px; }
.oc-f { text-align: right; color: #1677ff; font-size: 12px; }
.btn.sm { padding: 3px 12px; font-size: 12px; }
.tag { padding: 1px 8px; border-radius: 10px; font-size: 11px; background: #f0f0f0; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.wait { background: #e6f4ff; color: #1677ff; }
.empty { text-align: center; color: #aaa; padding: 24px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 460px; max-width: 92vw; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.chain { background: #f6f8fa; border-radius: 8px; padding: 12px; margin-top: 10px; font-size: 13px; line-height: 2; color: #444; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
</style>
