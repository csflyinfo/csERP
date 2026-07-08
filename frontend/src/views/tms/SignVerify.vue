<script setup>
/**
 * 签收核销（P2 配送核心流程）
 *
 * 接口：
 *   POST /tms/trip/page              配送行程列表
 *   GET  /tms/trip/{id}/sign-records 行程签收记录
 *   POST /tms/sign/verify            审核签收（单条）
 *   POST /tms/sign/batch-verify      批量核销
 */
import { ref, onMounted } from 'vue'
import { post, get } from '../../api/client.js'

const loading = ref(false)
const feedback = ref('')
const trips = ref([])
const selectedTrip = ref(null)
const signRows = ref([])
const signLoading = ref(false)
const checkedSigns = ref(new Set())

// 筛选
const fStatus = ref('COMPLETED')
const fDriverName = ref('')

async function loadTrips() {
  loading.value = true
  try {
    const res = await post('/tms/trip/page', {
      pageNo: 1, pageSize: 100,
      filters: { status: fStatus.value, driverName: fDriverName.value }
    })
    trips.value = res.data?.records || []
  } catch (e) {
    feedback.value = '加载失败：' + (e.message || '')
  } finally {
    loading.value = false
  }
}

async function selectTrip(trip) {
  selectedTrip.value = trip
  checkedSigns.value.clear()
  await loadSignRecords(trip.tripId)
}

async function loadSignRecords(tripId) {
  signLoading.value = true
  try {
    const res = await get(`/tms/trip/${tripId}/sign-records`)
    signRows.value = res.data || []
  } catch (e) {
    feedback.value = '签收记录加载失败：' + (e.message || '')
  } finally {
    signLoading.value = false
  }
}

async function verifyOne(signId, action) {
  try {
    await post('/tms/sign/verify', { signId, action })
    feedback.value = '核销成功'
    if (selectedTrip.value) await loadSignRecords(selectedTrip.value.tripId)
  } catch (e) {
    feedback.value = '核销失败：' + (e.message || '')
  }
}

async function batchVerify() {
  if (checkedSigns.value.size === 0) {
    feedback.value = '请勾选要核销的签收记录'
    return
  }
  try {
    const res = await post('/tms/sign/batch-verify', { signIds: [...checkedSigns.value], action: 'PASS' })
    feedback.value = `批量核销 ${res.data?.verified || 0} 条`
    checkedSigns.value.clear()
    if (selectedTrip.value) await loadSignRecords(selectedTrip.value.tripId)
  } catch (e) {
    feedback.value = '批量核销失败：' + (e.message || '')
  }
}

function toggleCheck(signId) {
  if (checkedSigns.value.has(signId)) checkedSigns.value.delete(signId)
  else checkedSigns.value.add(signId)
}

function signTypeTag(t) {
  const m = { NORMAL: 'green', PARTIAL: 'orange', REJECT: 'red' }
  return m[t] || 'gray'
}

onMounted(loadTrips)
</script>

<template>
  <div class="tms-page">
    <div class="page-head">
      <h2>签收核销</h2>
      <div class="head-actions">
        <input v-model="fDriverName" placeholder="司机姓名" class="inp" @keyup.enter="loadTrips" />
        <select v-model="fStatus" @change="loadTrips" class="sel">
          <option value="">全部状态</option>
          <option value="DELIVERING">配送中</option>
          <option value="COMPLETED">已完成</option>
        </select>
        <button class="btn" @click="loadTrips">查询</button>
      </div>
    </div>

    <div v-if="feedback" class="alert warn">{{ feedback }}</div>

    <div class="split-layout">
      <!-- 左侧行程列表 -->
      <div class="left-panel">
        <div class="panel-head"><b>配送行程</b></div>
        <div v-if="loading" class="loading-box">加载中...</div>
        <div v-else class="trip-list">
          <div v-for="t in trips" :key="t.tripId"
               :class="['trip-item', { active: selectedTrip?.tripId === t.tripId }]"
               @click="selectTrip(t)">
            <div class="trip-row">
              <b>{{ t.tripNo }}</b>
              <span :class="['tag', t.status === 'COMPLETED' ? 'green' : 'orange']">{{ t.statusText }}</span>
            </div>
            <div class="trip-meta">
              {{ t.driverName }} · {{ t.routeLine || '—' }} · {{ t.tripDate }}
            </div>
            <div class="trip-progress">
              <div class="progress-bar">
                <div class="progress-fill" :style="{ width: t.progress + '%' }"></div>
              </div>
              <span class="progress-label">{{ t.deliveredStore }}/{{ t.totalStore }}门店 ({{ t.progress }}%)</span>
            </div>
          </div>
          <div v-if="!trips.length" class="empty">暂无行程</div>
        </div>
      </div>

      <!-- 右侧签收记录 -->
      <div class="right-panel">
        <div class="panel-head">
          <b>签收记录 <small v-if="selectedTrip">— {{ selectedTrip.tripNo }}</small></b>
          <div v-if="signRows.length" class="head-actions">
            <button class="btn primary" @click="batchVerify">批量核销 ({{ checkedSigns.size }})</button>
          </div>
        </div>
        <div v-if="signLoading" class="loading-box">加载中...</div>
        <div v-else-if="!selectedTrip" class="empty">请选择左侧行程查看签收记录</div>
        <div v-else class="tablebox">
          <table class="tms-table">
            <thead>
              <tr>
                <th v-if="signRows.length"><input type="checkbox" @click="signRows.forEach(r => toggleCheck(r.signId))" /></th>
                <th>单据号</th>
                <th>类型</th>
                <th>客户</th>
                <th>签收类型</th>
                <th>实收</th>
                <th>拒收</th>
                <th>收款</th>
                <th>支付方式</th>
                <th>签收人</th>
                <th>司机</th>
                <th>时间</th>
                <th>照片</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in signRows" :key="r.signId">
                <td><input type="checkbox" :checked="checkedSigns.has(r.signId)" @change="toggleCheck(r.signId)" /></td>
                <td><b>{{ r.sourceBillNo }}</b></td>
                <td>{{ r.billTypeText }}</td>
                <td>{{ r.customerName }}</td>
                <td><span :class="['tag', signTypeTag(r.signType)]">{{ r.signTypeText }}</span></td>
                <td>{{ r.signedQty }}</td>
                <td>{{ r.rejectQty || 0 }}</td>
                <td>{{ r.collectAmount || 0 }}</td>
                <td>{{ r.payMethod || '—' }}</td>
                <td>{{ r.customerSigner || '—' }}</td>
                <td>{{ r.signUser }}</td>
                <td>{{ r.signTime ? r.signTime.substring(0, 16) : '—' }}</td>
                <td>{{ r.photoCount || 0 }} 张</td>
                <td>
                  <button class="link-btn" @click="verifyOne(r.signId, 'PASS')">通过</button>
                </td>
              </tr>
              <tr v-if="!signRows.length"><td :colspan="14" style="text-align:center;color:#98a2b3;padding:24px">暂无签收记录</td></tr>
            </tbody>
          </table>
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
.inp { padding: 4px 8px; border: 1px solid #d0d5dd; border-radius: 6px; width: 120px; }
.sel { padding: 4px 8px; border: 1px solid #d0d5dd; border-radius: 6px; }
.btn { padding: 4px 12px; border: 1px solid #d0d5dd; border-radius: 6px; cursor: pointer; background: #fff; font-size: 13px; }
.btn:hover { background: #f9fafb; }
.btn.primary { background: #2563eb; color: #fff; border-color: #2563eb; }
.btn.primary:hover { background: #1d4ed8; }
.alert { padding: 8px 12px; border-radius: 8px; margin-bottom: 12px; font-size: 13px; }
.alert.warn { background: #fff7ed; color: #c2410c; }
.loading-box { text-align: center; padding: 40px; color: #98a2b3; }
.split-layout { display: flex; gap: 16px; min-height: 600px; }
.left-panel { width: 360px; flex-shrink: 0; background: #fff; border-radius: 10px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); overflow: hidden; display: flex; flex-direction: column; }
.right-panel { flex: 1; background: #fff; border-radius: 10px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); overflow: hidden; display: flex; flex-direction: column; }
.panel-head { padding: 12px 16px; border-bottom: 1px solid #eaecf0; display: flex; justify-content: space-between; align-items: center; }
.panel-head small { color: #667085; font-weight: 400; }
.trip-list { flex: 1; overflow-y: auto; }
.trip-item { padding: 12px 16px; border-bottom: 1px solid #f2f4f7; cursor: pointer; transition: background 0.15s; }
.trip-item:hover { background: #f9fafb; }
.trip-item.active { background: #eff6ff; border-left: 3px solid #2563eb; }
.trip-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.trip-meta { font-size: 12px; color: #667085; margin-bottom: 6px; }
.trip-progress { display: flex; align-items: center; gap: 8px; }
.progress-bar { flex: 1; height: 6px; background: #f3f4f6; border-radius: 3px; overflow: hidden; }
.progress-fill { height: 100%; background: linear-gradient(90deg, #2563eb, #3b82f6); }
.progress-label { font-size: 11px; color: #667085; white-space: nowrap; }
.empty { text-align: center; padding: 40px; color: #98a2b3; font-size: 13px; }
.tablebox { flex: 1; overflow: auto; }
.tms-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.tms-table th { background: #f9fafb; padding: 8px 10px; text-align: left; font-weight: 600; color: #475467; border-bottom: 1px solid #eaecf0; white-space: nowrap; }
.tms-table td { padding: 8px 10px; border-bottom: 1px solid #f2f4f7; color: #1d2939; }
.tag { padding: 2px 8px; border-radius: 10px; font-size: 10px; font-weight: 600; }
.tag.green { background: #ecfdf5; color: #059669; }
.tag.orange { background: #fff7ed; color: #ea580c; }
.tag.red { background: #fee2e2; color: #d92d20; }
.tag.gray { background: #f3f4f6; color: #6b7280; }
.link-btn { background: none; border: none; color: #2563eb; cursor: pointer; font-size: 12px; padding: 2px 6px; }
.link-btn:hover { text-decoration: underline; }
</style>
