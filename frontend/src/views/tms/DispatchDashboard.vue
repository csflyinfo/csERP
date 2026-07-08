<script setup>
/**
 * 调度看板大屏（P4-2）
 *
 * 可视化展示今日配送全貌：
 *   - 顶部统计卡片：调度单数、在途车辆、已签收门店、待签收、异常
 *   - 在途车辆卡片墙：每个调度单的司机、车牌、进度、实时位置
 *   - 异常告警列表：离线车辆、长时间未签收
 *
 * 接口：POST /tms/dispatch/monitor  在途调度单列表（含实时位置 + 进度）
 * 复用 DeliveryMonitor.vue 的后端接口
 */
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { post } from '../../api/client.js'

const loading = ref(false)
const feedback = ref('')
const list = ref([])
const filterStatus = ref('')
const autoRefresh = ref(true)
let timer = null

async function loadList() {
  loading.value = true
  try {
    const res = await post('/tms/dispatch/monitor', { status: filterStatus.value })
    list.value = res.data || []
  } catch (e) {
    feedback.value = '加载失败：' + (e.message || '')
  } finally {
    loading.value = false
  }
}

// 统计数据
const stats = computed(() => {
  const total = list.value.length
  const delivering = list.value.filter(x => x.status === 'DELIVERING').length
  const departed = list.value.filter(x => x.status === 'DEPARTED').length
  const online = list.value.filter(x => x.online).length
  const offline = total - online
  const totalReceipts = list.value.reduce((s, x) => s + (x.totalReceipt || 0), 0)
  const deliveredReceipts = list.value.reduce((s, x) => s + (x.deliveredCount || 0), 0)
  const pendingReceipts = totalReceipts - deliveredReceipts
  return { total, delivering, departed, online, offline, totalReceipts, deliveredReceipts, pendingReceipts }
})

// 异常告警列表
const alerts = computed(() => {
  const result = []
  for (const r of list.value) {
    // 离线车辆
    if (!r.online && (r.status === 'DEPARTED' || r.status === 'DELIVERING')) {
      result.push({ type: 'offline', text: `${r.driverName}（${r.dispatchNo}）车辆离线`, severity: 'high' })
    }
    // 发车超过 2 小时未签收任何门店
    if (r.departTime && r.deliveredCount === 0 && (r.status === 'DEPARTED' || r.status === 'DELIVERING')) {
      const departDate = new Date(r.departTime.replace(' ', 'T'))
      const hours = (Date.now() - departDate.getTime()) / 3600000
      if (hours > 2) {
        result.push({ type: 'timeout', text: `${r.driverName}（${r.dispatchNo}）发车 ${hours.toFixed(1)} 小时未签收`, severity: 'medium' })
      }
    }
  }
  return result
})

function statusTag(s) {
  const m = { LOADED: { text: '已装车', cls: 's-loaded' }, DEPARTED: { text: '已发车', cls: 's-departed' }, DELIVERING: { text: '配送中', cls: 's-delivering' }, COMPLETED: { text: '已完成', cls: 's-completed' } }
  return m[s] || { text: s, cls: 's-loaded' }
}

function progressColor(pct) {
  if (pct >= 100) return '#67c23a'
  if (pct >= 50) return '#409eff'
  if (pct > 0) return '#e6a23c'
  return '#dcdfe6'
}

onMounted(() => {
  loadList()
  if (autoRefresh.value) timer = setInterval(loadList, 30000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <div class="dashboard">
    <div class="dash-head">
      <h2>调度看板</h2>
      <div class="head-actions">
        <select v-model="filterStatus" @change="loadList" class="sel">
          <option value="">全部状态</option>
          <option value="LOADED">已装车</option>
          <option value="DEPARTED">已发车</option>
          <option value="DELIVERING">配送中</option>
        </select>
        <label class="chk"><input type="checkbox" v-model="autoRefresh" /> 30s自动刷新</label>
        <button class="btn" @click="loadList">刷新</button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-row">
      <div class="stat-card s-blue">
        <div class="stat-icon">📋</div>
        <div class="stat-body"><div class="stat-num">{{ stats.total }}</div><div class="stat-lbl">在途调度单</div></div>
      </div>
      <div class="stat-card s-orange">
        <div class="stat-icon">🚚</div>
        <div class="stat-body"><div class="stat-num">{{ stats.delivering }}</div><div class="stat-lbl">配送中</div></div>
      </div>
      <div class="stat-card s-green">
        <div class="stat-icon">✅</div>
        <div class="stat-body"><div class="stat-num">{{ stats.deliveredReceipts }}</div><div class="stat-lbl">已签收门店</div></div>
      </div>
      <div class="stat-card s-cyan">
        <div class="stat-icon">⏳</div>
        <div class="stat-body"><div class="stat-num">{{ stats.pendingReceipts }}</div><div class="stat-lbl">待签收门店</div></div>
      </div>
      <div class="stat-card s-red" :class="{ 'has-alert': alerts.length > 0 }">
        <div class="stat-icon">⚠️</div>
        <div class="stat-body"><div class="stat-num">{{ alerts.length }}</div><div class="stat-lbl">异常告警</div></div>
      </div>
    </div>

    <div v-if="feedback" class="alert warn">{{ feedback }}</div>

    <!-- 异常告警 -->
    <div v-if="alerts.length" class="alert-section">
      <div class="alert-title">⚠️ 异常告警（{{ alerts.length }}）</div>
      <div class="alert-list">
        <div v-for="(a, i) in alerts" :key="i" :class="['alert-item', a.severity]">
          <span class="alert-dot"></span>{{ a.text }}
        </div>
      </div>
    </div>

    <div v-if="loading && !list.length" class="loading-box">加载中...</div>

    <!-- 在途车辆卡片墙 -->
    <div v-else class="card-wall">
      <div v-for="r in list" :key="r.dispatchId" :class="['dispatch-card', !r.online ? 'offline' : '']">
        <div class="card-head">
          <div class="card-title">
            <span :class="['status-dot', statusTag(r.status).cls]"></span>
            <b>{{ r.driverName }}</b>
            <span class="card-no">{{ r.dispatchNo }}</span>
          </div>
          <span :class="['status-tag', statusTag(r.status).cls]">{{ statusTag(r.status).text }}</span>
        </div>
        <div class="card-info">
          <span class="info-item">🚐 {{ r.vehiclePlate || '未分配' }}</span>
          <span class="info-item">🛣️ {{ r.routeLine || '-' }}</span>
        </div>
        <!-- 进度条 -->
        <div class="progress-section">
          <div class="progress-bar">
            <div class="progress-fill" :style="{ width: r.progress + '%', background: progressColor(r.progress) }"></div>
          </div>
          <div class="progress-text">
            <span>{{ r.deliveredCount }}/{{ r.totalReceipt }} 门店</span>
            <span>{{ r.progress }}%</span>
          </div>
        </div>
        <!-- 实时位置 -->
        <div class="card-loc">
          <span v-if="r.online" class="loc-online">
            📍 {{ r.curLongitude ? r.curLongitude.toFixed(4) + ', ' + r.curLatitude.toFixed(4) : '定位中...' }}
            <small v-if="r.curSpeed > 0">{{ r.curSpeed }}km/h</small>
          </span>
          <span v-else class="loc-offline">⛔ 离线</span>
          <span class="loc-time" v-if="r.curLocTime">{{ r.curLocTime.substring(11, 16) }}</span>
        </div>
      </div>
      <div v-if="!list.length" class="empty">暂无在途调度单</div>
    </div>
  </div>
</template>

<style scoped>
.dashboard { padding: 16px; }
.dash-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.dash-head h2 { margin: 0; font-size: 20px; }
.head-actions { display: flex; gap: 8px; align-items: center; }
.sel { padding: 4px 8px; border: 1px solid #d0d5dd; border-radius: 6px; }
.chk { font-size: 12px; color: #667085; }
.btn { padding: 4px 12px; border: 1px solid #d0d5dd; border-radius: 6px; cursor: pointer; background: #fff; }

/* 统计卡片 */
.stat-row { display: flex; gap: 12px; margin-bottom: 16px; }
.stat-card { flex: 1; display: flex; align-items: center; gap: 12px; padding: 16px; border-radius: 10px; transition: transform 0.2s; }
.stat-card:hover { transform: translateY(-2px); }
.stat-card.has-alert { animation: pulse 2s infinite; }
@keyframes pulse { 0%,100% { box-shadow: 0 0 0 0 rgba(245,108,108,0.3); } 50% { box-shadow: 0 0 0 8px rgba(245,108,108,0); } }
.stat-icon { font-size: 28px; }
.stat-num { font-size: 28px; font-weight: 700; line-height: 1; }
.stat-lbl { font-size: 12px; color: #667085; margin-top: 4px; }
.s-blue { background: #eff6ff; } .s-blue .stat-num { color: #2563eb; }
.s-orange { background: #fff7ed; } .s-orange .stat-num { color: #ea580c; }
.s-green { background: #ecfdf5; } .s-green .stat-num { color: #059669; }
.s-cyan { background: #ecfeff; } .s-cyan .stat-num { color: #0891b2; }
.s-red { background: #fef0f0; } .s-red .stat-num { color: #f56c6c; }

/* 异常告警 */
.alert-section { background: #fff; border-radius: 10px; padding: 12px 16px; margin-bottom: 16px; border-left: 4px solid #f56c6c; }
.alert-title { font-size: 14px; font-weight: 700; color: #f56c6c; margin-bottom: 8px; }
.alert-list { display: flex; flex-direction: column; gap: 4px; }
.alert-item { font-size: 13px; color: #606266; display: flex; align-items: center; gap: 8px; }
.alert-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.alert-item.high .alert-dot { background: #f56c6c; }
.alert-item.medium .alert-dot { background: #e6a23c; }

/* 在途车辆卡片墙 */
.card-wall { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 12px; }
.dispatch-card { background: #fff; border-radius: 10px; padding: 14px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); border: 1px solid #f2f4f7; transition: box-shadow 0.2s; }
.dispatch-card:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.08); }
.dispatch-card.offline { border-color: #fde2e2; background: #fffbfb; }
.card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.card-title { display: flex; align-items: center; gap: 6px; font-size: 15px; }
.card-no { font-size: 11px; color: #98a2b3; font-weight: 400; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; }
.status-tag { padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: 600; }
.s-loaded .status-dot, .s-loaded.status-tag { background: #f3f4f6; color: #6b7280; }
.s-departed .status-dot, .s-departed.status-tag { background: #dbeafe; color: #2563eb; }
.s-delivering .status-dot, .s-delivering.status-tag { background: #fff7ed; color: #ea580c; }
.s-completed .status-dot, .s-completed.status-tag { background: #ecfdf5; color: #059669; }
.card-info { display: flex; gap: 12px; font-size: 12px; color: #667085; margin-bottom: 10px; }
.progress-section { margin-bottom: 8px; }
.progress-bar { height: 8px; background: #f3f4f6; border-radius: 4px; overflow: hidden; }
.progress-fill { height: 100%; border-radius: 4px; transition: width 0.4s; }
.progress-text { display: flex; justify-content: space-between; font-size: 11px; color: #98a2b3; margin-top: 4px; }
.card-loc { display: flex; justify-content: space-between; align-items: center; font-size: 12px; }
.loc-online { color: #059669; }
.loc-online small { color: #98a2b3; margin-left: 4px; }
.loc-offline { color: #f56c6c; }
.loc-time { color: #98a2b3; font-size: 11px; }

.loading-box { text-align: center; padding: 40px; color: #98a2b3; }
.empty { grid-column: 1 / -1; text-align: center; padding: 40px; color: #98a2b3; }
.alert { padding: 8px 12px; border-radius: 8px; margin-bottom: 12px; font-size: 13px; }
.alert.warn { background: #fff7ed; color: #c2410c; }
</style>
