<script setup>
/**
 * 在途监控（P2 配送核心流程）
 *
 * 接口：
 *   POST /tms/dispatch/monitor  在途调度单列表（含实时位置 + 进度）
 *   GET  /tms/dispatch/{id}/track  调度单在途轨迹
 *   GET  /tms/trip/{id}/sign-records  行程签收记录
 */
import { ref, onMounted, onUnmounted } from 'vue'
import { post, get } from '../../api/client.js'

const loading = ref(false)
const feedback = ref('')
const list = ref([])
const filterStatus = ref('')

// 轨迹抽屉
const trackOpen = ref(false)
const trackLoading = ref(false)
const trackRows = ref([])
const trackDispatchNo = ref('')

// 签收记录抽屉
const signOpen = ref(false)
const signLoading = ref(false)
const signRows = ref([])

// 自动刷新
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

async function showTrack(row) {
  trackDispatchNo.value = row.dispatchNo
  trackOpen.value = true
  trackLoading.value = true
  try {
    const res = await get(`/tms/dispatch/${row.dispatchId}/track`)
    trackRows.value = res.data || []
  } catch (e) {
    feedback.value = '轨迹加载失败：' + (e.message || '')
  } finally {
    trackLoading.value = false
  }
}

async function showSignRecords(row) {
  signOpen.value = true
  signLoading.value = true
  try {
    const res = await get(`/tms/trip/${row.tripId || row.dispatchId}/sign-records`)
    signRows.value = res.data || []
  } catch (e) {
    feedback.value = '签收记录加载失败：' + (e.message || '')
  } finally {
    signLoading.value = false
  }
}

function refresh() {
  loadList()
}

function statusTag(s) {
  const m = { LOADED: 'gray', DEPARTED: 'blue', DELIVERING: 'orange', COMPLETED: 'green' }
  return m[s] || 'gray'
}

onMounted(() => {
  loadList()
  if (autoRefresh.value) {
    timer = setInterval(loadList, 30000) // 30s 自动刷新
  }
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <div class="tms-page">
    <div class="page-head">
      <h2>在途监控</h2>
      <div class="head-actions">
        <select v-model="filterStatus" @change="loadList" class="sel">
          <option value="">全部状态</option>
          <option value="LOADED">已装车</option>
          <option value="DEPARTED">已发车</option>
          <option value="DELIVERING">配送中</option>
        </select>
        <label class="chk"><input type="checkbox" v-model="autoRefresh" /> 30s自动刷新</label>
        <button class="btn" @click="refresh">刷新</button>
      </div>
    </div>

    <div v-if="feedback" class="alert warn">{{ feedback }}</div>

    <!-- 统计卡片 -->
    <div class="stat-row" v-if="list.length">
      <div class="stat-card blue"><div class="num">{{ list.length }}</div><div class="lbl">在途调度单</div></div>
      <div class="stat-card orange"><div class="num">{{ list.filter(x => x.status === 'DELIVERING').length }}</div><div class="lbl">配送中</div></div>
      <div class="stat-card green"><div class="num">{{ list.filter(x => x.online).length }}</div><div class="lbl">在线车辆</div></div>
      <div class="stat-card gray"><div class="num">{{ list.filter(x => !x.online).length }}</div><div class="lbl">离线车辆</div></div>
    </div>

    <div v-if="loading" class="loading-box">加载中...</div>

    <!-- 在途列表 -->
    <div v-else class="tablebox">
      <table class="tms-table">
        <thead>
          <tr>
            <th>调度单号</th>
            <th>司机</th>
            <th>车牌</th>
            <th>线路</th>
            <th>状态</th>
            <th>进度</th>
            <th>实时位置</th>
            <th>发车时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in list" :key="r.dispatchId">
            <td><b>{{ r.dispatchNo }}</b></td>
            <td>{{ r.driverName }}</td>
            <td>{{ r.vehiclePlate || '—' }}</td>
            <td>{{ r.routeLine || '—' }}</td>
            <td><span :class="['tag', statusTag(r.status)]">{{ r.statusText }}</span></td>
            <td>
              <div class="progress-bar">
                <div class="progress-fill" :style="{ width: r.progress + '%' }"></div>
                <span class="progress-text">{{ r.deliveredCount }}/{{ r.totalReceipt }} ({{ r.progress }}%)</span>
              </div>
            </td>
            <td>
              <span v-if="r.online" class="loc-online">
                📍 {{ r.curLongitude }}, {{ r.curLatitude }}
                <small v-if="r.curSpeed > 0"> {{ r.curSpeed }}km/h</small>
              </span>
              <span v-else class="loc-offline">离线</span>
            </td>
            <td>{{ r.departTime ? r.departTime.substring(0, 16) : '—' }}</td>
            <td>
              <button class="link-btn" @click="showTrack(r)">轨迹</button>
              <button class="link-btn" @click="showSignRecords(r)">签收记录</button>
            </td>
          </tr>
          <tr v-if="!list.length"><td colspan="9" style="text-align:center;color:#98a2b3;padding:24px">暂无在途调度单</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 轨迹抽屉 -->
    <div v-if="trackOpen" class="drawer-overlay" @click.self="trackOpen = false">
      <div class="drawer-panel">
        <div class="drawer-head">
          <b>轨迹回放 — {{ trackDispatchNo }}</b>
          <button class="link-btn" @click="trackOpen = false">关闭</button>
        </div>
        <div class="drawer-body">
          <div v-if="trackLoading" class="loading-box">加载中...</div>
          <div v-else>
            <div class="tips-inline"><span>共 {{ trackRows.length }} 个定位点</span></div>
            <table class="tms-table compact">
              <thead>
                <tr><th>#</th><th>经度</th><th>纬度</th><th>速度</th><th>定位时间</th></tr>
              </thead>
              <tbody>
                <tr v-for="(p, i) in trackRows" :key="p.locId">
                  <td>{{ i + 1 }}</td>
                  <td>{{ p.longitude }}</td>
                  <td>{{ p.latitude }}</td>
                  <td>{{ p.speed }} km/h</td>
                  <td>{{ p.locTime }}</td>
                </tr>
                <tr v-if="!trackRows.length"><td colspan="5" style="text-align:center;color:#98a2b3;padding:16px">暂无轨迹数据</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- 签收记录抽屉 -->
    <div v-if="signOpen" class="drawer-overlay" @click.self="signOpen = false">
      <div class="drawer-panel">
        <div class="drawer-head">
          <b>签收记录</b>
          <button class="link-btn" @click="signOpen = false">关闭</button>
        </div>
        <div class="drawer-body">
          <div v-if="signLoading" class="loading-box">加载中...</div>
          <div v-else>
            <table class="tms-table compact">
              <thead>
                <tr><th>单据号</th><th>客户</th><th>类型</th><th>签收类型</th><th>实收</th><th>拒收</th><th>收款</th><th>签收人</th><th>时间</th><th>照片</th></tr>
              </thead>
              <tbody>
                <tr v-for="r in signRows" :key="r.signId">
                  <td>{{ r.sourceBillNo }}</td>
                  <td>{{ r.customerName }}</td>
                  <td>{{ r.billTypeText }}</td>
                  <td>{{ r.signTypeText }}</td>
                  <td>{{ r.signedQty }}</td>
                  <td>{{ r.rejectQty || 0 }}</td>
                  <td>{{ r.collectAmount || 0 }}</td>
                  <td>{{ r.customerSigner || '—' }}</td>
                  <td>{{ r.signTime ? r.signTime.substring(0, 16) : '—' }}</td>
                  <td>{{ r.photoCount || 0 }} 张</td>
                </tr>
                <tr v-if="!signRows.length"><td colspan="10" style="text-align:center;color:#98a2b3;padding:16px">暂无签收记录</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tms-page { padding: 16px; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 18px; }
.head-actions { display: flex; gap: 8px; align-items: center; }
.sel { padding: 4px 8px; border: 1px solid #d0d5dd; border-radius: 6px; }
.chk { font-size: 12px; color: #667085; }
.btn { padding: 4px 12px; border: 1px solid #d0d5dd; border-radius: 6px; cursor: pointer; background: #fff; }
.btn:hover { background: #f9fafb; }
.alert { padding: 8px 12px; border-radius: 8px; margin-bottom: 12px; font-size: 13px; }
.alert.warn { background: #fff7ed; color: #c2410c; }
.loading-box { text-align: center; padding: 40px; color: #98a2b3; }
.stat-row { display: flex; gap: 12px; margin-bottom: 16px; }
.stat-card { flex: 1; padding: 16px; border-radius: 10px; text-align: center; }
.stat-card .num { font-size: 24px; font-weight: 700; }
.stat-card .lbl { font-size: 12px; color: #667085; margin-top: 4px; }
.stat-card.blue { background: #eff6ff; } .stat-card.blue .num { color: #2563eb; }
.stat-card.orange { background: #fff7ed; } .stat-card.orange .num { color: #ea580c; }
.stat-card.green { background: #ecfdf5; } .stat-card.green .num { color: #059669; }
.stat-card.gray { background: #f3f4f6; } .stat-card.gray .num { color: #6b7280; }
.tablebox { background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.tms-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.tms-table th { background: #f9fafb; padding: 10px 12px; text-align: left; font-weight: 600; color: #475467; border-bottom: 1px solid #eaecf0; white-space: nowrap; }
.tms-table td { padding: 10px 12px; border-bottom: 1px solid #f2f4f7; color: #1d2939; }
.tms-table.compact th, .tms-table.compact td { padding: 6px 8px; font-size: 12px; }
.tag { padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: 600; }
.tag.blue { background: #dbeafe; color: #2563eb; }
.tag.orange { background: #fff7ed; color: #ea580c; }
.tag.green { background: #ecfdf5; color: #059669; }
.tag.gray { background: #f3f4f6; color: #6b7280; }
.progress-bar { position: relative; width: 120px; height: 20px; background: #f3f4f6; border-radius: 10px; overflow: hidden; }
.progress-fill { height: 100%; background: linear-gradient(90deg, #2563eb, #3b82f6); transition: width 0.3s; }
.progress-text { position: absolute; top: 0; left: 0; width: 100%; text-align: center; line-height: 20px; font-size: 10px; color: #fff; font-weight: 600; text-shadow: 0 0 2px rgba(0,0,0,0.3); }
.loc-online { color: #059669; font-size: 12px; }
.loc-online small { color: #667085; }
.loc-offline { color: #d92d20; font-size: 12px; }
.link-btn { background: none; border: none; color: #2563eb; cursor: pointer; font-size: 12px; padding: 2px 6px; }
.link-btn:hover { text-decoration: underline; }
.drawer-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 1000; display: flex; justify-content: flex-end; }
.drawer-panel { width: 700px; max-width: 90vw; background: #fff; height: 100%; overflow-y: auto; display: flex; flex-direction: column; }
.drawer-head { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; border-bottom: 1px solid #eaecf0; }
.drawer-body { padding: 20px; flex: 1; overflow-y: auto; }
.tips-inline { margin-bottom: 12px; padding: 8px 12px; background: #f9fafb; border-radius: 6px; font-size: 12px; color: #667085; }
</style>
