<script setup>
/**
 * WMS V1.5 分拣指令查询（PRD-28）：按库区汇总的分拣指令，查看明细，一键复检完成订单。
 */
import { ref, onMounted } from 'vue'
import { post, get } from '../../api/client.js'

const loading = ref(false)
const feedback = ref('')
const rows = ref([])
const fZone = ref('')
const drawer = ref(null)
const confirmOpen = ref(false)
const target = ref(null)
const checking = ref(false)

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 3000) }

const pickModeText = (m) => ({ '0': '按单拣', '1': '汇总拣', '2': '边拣边分', '3': '分区接力', '4': '越库直发', ORDER: '按单拣', SUMMARY: '汇总拣', PICK_SORT: '边拣边分', ZONE_RELAY: '分区接力', CROSS_DOCK: '越库直发' }[m] || m || '—')

async function load() {
  loading.value = true
  try {
    const res = await post('/wms/sort/instruction-page', { pageNo: 1, pageSize: 100, filters: { zoneCode: fZone.value } })
    rows.value = res.records || []
  } catch (e) { rows.value = []; show('加载失败：' + (e?.message || e)) }
  finally { loading.value = false }
}

async function openDetail(r) {
  // 用波次详情拉商品明细 + 该库区涉及的订单
  try {
    const d = await get('/wms/wave/detail?waveId=' + (r.waveId || r.waveNo))
    const lines = (d.details || []).filter(l => (l.allocZoneCode || '') === (r.zoneCode || ''))
    const orderNos = new Set(lines.map(l => l.sourceOrderNo))
    const orders = (d.orders || []).filter(o => orderNos.has(o.sourceOrderNo))
    drawer.value = { ...r, lines, orders }
  } catch (e) { show('明细加载失败：' + (e?.message || e)) }
}

function recheckOrder(o) { target.value = o; confirmOpen.value = true }
async function doRecheck() {
  checking.value = true
  try {
    const res = await post('/wms/recheck/pass', { waveId: drawer.value.waveId, orderNo: target.value.sourceOrderNo, checkScope: '0' })
    confirmOpen.value = false
    show(`复检完成：出库单 ${res.outboundNo}，已扣库存并生成发货单 ${res.receiptNo}`)
    drawer.value = null; load()
  } catch (e) { show('复检失败：' + (e?.message || e)) }
  finally { checking.value = false }
}
const gathered = o => Number(o.pickedQty) >= Number(o.requiredQty)

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="load">刷新</button>
      <input v-model="fZone" placeholder="按库区筛选（如 A）" class="inp" @keydown.enter="load" />
      <button class="btn primary" @click="load">查询</button>
    </div>
    <div v-if="feedback" class="toast-inline">{{ feedback }}</div>

    <div class="tablebox">
      <div class="toolbar"><b>分拣指令单</b><span class="muted">库区视图</span></div>
      <div class="scroll">
        <table class="data">
          <thead><tr><th>波次</th><th>库区</th><th>拣货模式</th><th class="num">订单/门店</th><th class="num">行数</th><th class="num">件数</th><th class="num">已拣</th><th>集货位</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.waveNo + r.zoneCode" @dblclick="openDetail(r)">
              <td>{{ r.waveNo }}</td><td><b class="zone">{{ r.zoneCode || '—' }}</b></td><td>{{ pickModeText(r.pickMode) }}</td>
              <td class="num">{{ r.orderCount }}/{{ r.storeCount }}</td><td class="num">{{ r.lineCount }}</td>
              <td class="num">{{ r.requiredQty }}</td><td class="num">{{ r.pickedQty }}</td>
              <td>{{ r.collectionBinCode }}</td>
              <td><span class="tag" :class="r.status==='PICKED'?'ok':''">{{ r.statusText || r.status }}</span></td>
              <td class="ops"><a @click="openDetail(r)">明细</a></td>
            </tr>
            <tr v-if="!rows.length"><td colspan="10" class="empty">暂无分拣指令</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 明细抽屉 -->
    <div v-if="drawer" class="drawer-mask" @click.self="drawer=null">
      <div class="drawer">
        <div class="dr-h">{{ drawer.waveNo }} · 库区 {{ drawer.zoneCode }} 分拣明细<span class="x" @click="drawer=null">×</span></div>
        <div class="dr-b">
          <div class="order-list">
            <div v-for="o in drawer.orders" :key="o.sourceOrderNo" class="o-row">
              <b>{{ o.sourceOrderNo }}</b><span class="cust">{{ o.customerName }}</span>
              <span class="cnt">{{ o.pickedQty }}/{{ o.requiredQty }}</span>
              <div class="spacer"></div>
              <button v-if="!o.lines[0]?.generatedOutboundNo" class="btn primary sm" :disabled="!gathered(o)" @click="recheckOrder(o)">一键复检完成</button>
              <span v-else class="tag ok">已出库 {{ o.lines[0].generatedOutboundNo }}</span>
            </div>
          </div>
          <table class="data">
            <thead><tr><th>序</th><th>库位</th><th>商品</th><th>批次</th><th class="num">应拣</th><th class="num">已拣</th><th>分播去向</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="(l,i) in drawer.lines" :key="l.detailId">
                <td>{{ l.pickSeq || i+1 }}</td><td class="bin">{{ l.allocBinCode }}</td>
                <td>{{ l.goodsName }}<div class="sub">{{ l.goodsCode }}</div></td>
                <td>{{ l.allocBatchNo || '—' }}</td>
                <td class="num">{{ l.requiredQty }}</td><td class="num">{{ l.pickedQty }}</td>
                <td>{{ l.sortDestination || l.customerName }}<div class="sub">集货位 {{ l.collectionBinCode }}</div></td>
                <td><span class="tag" :class="l.status==='PICKED'?'ok':''">{{ l.status }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div v-if="confirmOpen" class="modal-mask" @click.self="confirmOpen=false">
      <div class="modal">
        <div class="modal-h">一键复检完成订单<span class="x" @click="confirmOpen=false">×</span></div>
        <div class="modal-b">
          <p>确认对波次 <b>{{ target?.waveNo }}</b> 执行一键复检？通过后将：</p>
          <div class="chain">
            <div>① 生成销售出库单 → ② 审核扣库存 → ③ 生成销售发货单</div>
          </div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="confirmOpen=false">取消</button>
          <button class="btn primary" :disabled="checking" @click="doRecheck">{{ checking ? '处理中...' : '确认复检并扣库存' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.inp { padding: 5px 10px; border: 1px solid #d9d9d9; border-radius: 6px; width: 200px; }
.muted { color: #999; font-size: 12px; margin-left: 6px; }
table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
table.data th, table.data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
table.data th { background: #fafafa; font-weight: 600; color: #555; }
.num { text-align: right; }
.zone { color: #1677ff; }
.bin { font-family: monospace; color: #1677ff; }
.sub { color: #aaa; font-size: 11px; }
.tag { padding: 1px 7px; border-radius: 10px; font-size: 11px; background: #f0f0f0; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.ops a { color: #1677ff; cursor: pointer; }
.empty { text-align: center; color: #aaa; padding: 24px; }
.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,.35); z-index: 90; }
.drawer { position: absolute; right: 0; top: 0; bottom: 0; width: 760px; max-width: 92vw; background: #fff; display: flex; flex-direction: column; box-shadow: -4px 0 16px rgba(0,0,0,.12); }
.dr-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.dr-h .x { cursor: pointer; color: #999; }
.dr-b { padding: 14px 18px; overflow: auto; }
.order-list { margin-bottom: 14px; }
.o-row { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid #f0f0f0; border-radius: 6px; margin-bottom: 6px; }
.o-row .cust { color: #888; font-size: 13px; }
.o-row .cnt { color: #1677ff; font-size: 13px; }
.spacer { flex: 1; }
.btn.sm { padding: 3px 12px; font-size: 12px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 460px; max-width: 92vw; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.chain { background: #f6f8fa; border-radius: 8px; padding: 12px; margin-top: 10px; font-size: 13px; color: #444; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
</style>
