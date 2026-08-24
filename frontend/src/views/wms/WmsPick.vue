<script setup>
/**
 * WMS V1.5 拣货任务（PRD-28）：我的库区 / 可支援库区任务池，抢单，查看任务明细，PC 一键完成。
 */
import { ref, computed, onMounted } from 'vue'
import { post, get } from '../../api/client.js'

const loading = ref(false)
const feedback = ref('')
const scope = ref('mine')
const tasks = ref([])
const detail = ref(null)
const operator = ref('张三')

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 3000) }

async function load() {
  loading.value = true
  try {
    tasks.value = await post('/wms/pick/task-page', { pageNo: 1, pageSize: 100, filters: { scope: scope.value, assignee: operator.value } })
      .then(r => r.records || [])
  } catch (e) { tasks.value = []; show('加载失败：' + (e?.message || e)) }
  finally { loading.value = false }
}

const modeText = { '0': '按单拣', '1': '汇总拣', '2': '边拣边分', '3': '分区接力', '4': '越库直发', ORDER: '按单拣', SUMMARY: '汇总拣', PICK_SORT: '边拣边分', ZONE_RELAY: '分区接力', CROSS_DOCK: '越库直发' }
const stText = { PENDING: '待领取', CLAIMED: '已领取', PICKING: '拣货中', PICKED: '已完成', CANCELLED: '已取消' }

async function openDetail(t) {
  try { detail.value = await get('/wms/pick/task-detail?taskId=' + t.taskId) }
  catch (e) { show('明细加载失败：' + (e?.message || e)) }
}
async function claim(t, help) {
  try {
    await post('/wms/pick/claim', { taskId: t.taskId, assignee: operator.value, help: help ? 'Y' : 'N' })
    show('已领取任务 ' + t.taskNo); load(); openDetail({ taskId: t.taskId })
  } catch (e) { show('领取失败：' + (e?.message || e)) }
}
async function complete(t) {
  if (!confirm('确认任务 ' + t.taskNo + ' 全部拣完并提交？')) return
  try {
    await post('/wms/pick/complete', { taskId: t.taskId, assignee: operator.value })
    show('任务已完成'); detail.value = null; load()
  } catch (e) { show('完成失败：' + (e?.message || e)) }
}

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="load">刷新</button>
      <div class="seg">
        <button :class="{ on: scope==='mine' }" @click="scope='mine'; load()">我的库区</button>
        <button :class="{ on: scope==='help' }" @click="scope='help'; load()">可支援库区</button>
        <button :class="{ on: scope==='all' }" @click="scope='all'; load()">全部</button>
      </div>
      <span class="muted">当前拣货员：{{ operator }}</span>
    </div>
    <div v-if="feedback" class="toast-inline">{{ feedback }}</div>

    <div class="pick-split">
      <div class="tablebox">
        <div class="toolbar"><b>拣货任务</b><span class="muted">{{ tasks.length }}</span></div>
        <div class="scroll">
          <table class="data">
            <thead><tr><th>任务号</th><th>波次</th><th>库区</th><th>模式</th><th class="num">行</th><th class="num">件数</th><th>负责人</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="t in tasks" :key="t.taskId" :class="{ sel: detail && detail.taskId===t.taskId }" @dblclick="openDetail(t)">
                <td>{{ t.taskNo }}</td><td>{{ t.waveNo }}</td><td>{{ t.zoneCode || '—' }}</td>
                <td>{{ modeText[t.pickMode] || t.pickMode }}</td>
                <td class="num">{{ t.lineCount }}</td><td class="num">{{ t.pickedQty }}/{{ t.totalQty }}</td>
                <td>{{ t.assignee || '—' }}<span v-if="t.helpTask==='Y'" class="tag hot">支援</span></td>
                <td><span class="tag" :class="t.status==='PICKED'?'ok':(t.status==='PENDING'?'wait':'')">{{ stText[t.status] || t.status }}</span></td>
                <td class="ops">
                  <a v-if="t.status==='PENDING'" @click="claim(t, scope==='help')">抢单</a>
                  <a v-else @click="openDetail(t)">明细</a>
                </td>
              </tr>
              <tr v-if="!tasks.length"><td colspan="9" class="empty">暂无任务</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="tablebox">
        <div class="toolbar">
          <b>任务明细</b>
          <div class="spacer"></div>
          <button v-if="detail && detail.status!=='PICKED'" class="btn primary" @click="complete(detail)">一键完成</button>
        </div>
        <div class="scroll">
          <template v-if="detail">
            <div class="kv">
              <span>任务 {{ detail.taskNo }}</span><span>库区 {{ detail.zoneCode || '—' }}</span>
              <span>模式 {{ modeText[detail.pickMode] }}</span><span>状态 {{ stText[detail.status] }}</span>
            </div>
            <table class="data">
              <thead><tr><th>序</th><th>库位</th><th>商品</th><th>批次</th><th class="num">应拣</th><th class="num">已拣</th><th>去向</th><th>状态</th></tr></thead>
              <tbody>
                <tr v-for="(l,i) in detail.lines" :key="l.detailId">
                  <td>{{ l.pickSeq || i+1 }}</td><td><b class="bin">{{ l.allocBinCode }}</b></td>
                  <td>{{ l.goodsName }}<div class="sub">{{ l.goodsCode }}</div></td>
                  <td>{{ l.allocBatchNo || '—' }}</td>
                  <td class="num">{{ l.requiredQty }}</td><td class="num">{{ l.pickedQty }}</td>
                  <td>{{ l.sortDestination || l.customerName }}<div class="sub">集货位 {{ l.collectionBinCode }}</div></td>
                  <td><span class="tag" :class="l.status==='PICKED'?'ok':(l.status==='SHORT'?'warn':'wait')">{{ l.status }}</span></td>
                </tr>
              </tbody>
            </table>
          </template>
          <div v-else class="empty">选择左侧任务查看拣货明细</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.seg { display: inline-flex; border: 1px solid #d9d9d9; border-radius: 6px; overflow: hidden; margin-left: 8px; }
.seg button { border: none; background: #fff; padding: 5px 14px; cursor: pointer; font-size: 13px; }
.seg button.on { background: #1677ff; color: #fff; }
.muted { color: #999; font-size: 12px; margin-left: 10px; }
.pick-split { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; height: calc(100vh - 170px); }
.kv { display: flex; gap: 18px; padding: 10px 12px; background: #fafafa; font-size: 13px; color: #555; border-bottom: 1px solid #f0f0f0; }
table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
table.data th, table.data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
table.data th { background: #fafafa; font-weight: 600; color: #555; }
.num { text-align: right; }
tr.sel { background: #e6f4ff; }
.bin { font-family: monospace; color: #1677ff; }
.sub { color: #aaa; font-size: 11px; }
.tag { display: inline-block; padding: 1px 7px; border-radius: 10px; font-size: 11px; background: #f0f0f0; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.warn { background: #fff1f0; color: #cf1322; }
.tag.wait { background: #fffbe6; color: #d48806; }
.tag.hot { background: #fff1f0; color: #cf1322; }
.ops a { color: #1677ff; cursor: pointer; }
.empty { text-align: center; color: #aaa; padding: 24px; }
</style>
