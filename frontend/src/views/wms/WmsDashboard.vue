<script setup>
/**
 * WMS V1.5 作业看板（PRD-28）。
 * 实时聚合入库/上架/波次/复检/发运/异常/冻结/效期预警指标。
 */
import { ref, onMounted } from 'vue'
import { get } from '../../api/client.js'

const data = ref({})
const loading = ref(false)
const feedback = ref('')
function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 3000) }

async function load() {
  loading.value = true
  try { data.value = await get(`/wms/internal/dashboard?warehouse=${encodeURIComponent('总仓')}`) }
  catch (e) { show(e?.message || '加载失败') }
  finally { loading.value = false }
}
onMounted(load)

const cards = [
  { k: 'pendingReceive', label: '待收货', color: '#1677ff' },
  { k: 'receivedToday', label: '今日收货', color: '#13c2c2' },
  { k: 'pendingPutaway', label: '待上架', color: '#722ed1' },
  { k: 'activeWaves', label: '进行中波次', color: '#fa8c16' },
  { k: 'pendingCheck', label: '待复检', color: '#eb2f96' },
  { k: 'shippedToday', label: '今日发运', color: '#52c41a' },
  { k: 'openExceptions', label: '未处理异常', color: '#cf1322' },
  { k: 'openFreezes', label: '冻结中库位', color: '#08979c' },
  { k: 'expiryCritical', label: '紧急临期', color: '#a8071a' },
]
</script>

<template>
  <div class="dashboard">
    <div class="page-ops">
      <button class="btn" @click="load">刷新</button>
      <span class="tip">仓库：总仓</span>
    </div>
    <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
    <div class="kpis">
      <div v-for="c in cards" :key="c.k" class="kpi" :style="{ borderTopColor: c.color }">
        <div class="label">{{ c.label }}</div>
        <div class="value" :style="{ color: c.color }">{{ data[c.k] ?? '—' }}</div>
      </div>
    </div>
    <div class="hint">
      <p>指标口径：</p>
      <ul>
        <li>待收货 = PENDING/RECEIVING 状态的入库任务</li>
        <li>进行中波次 = RELEASED/PICKING/CHECKING</li>
        <li>紧急临期 = CRITICAL/EXPIRED 且未处理的效期预警，需先在「效期管理」点刷新</li>
        <li>异常/冻结数据反映当前未关闭工单，处理后点刷新同步</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.page-ops { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.btn { height: 30px; padding: 0 14px; border: 1px solid #d9d9d9; background: #fff; border-radius: 4px; cursor: pointer; }
.tip { color: #666; font-size: 13px; }
.kpis { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 14px; }
.kpi { background: #fff; border: 1px solid #f0f0f0; border-top: 3px solid #1677ff; border-radius: 6px; padding: 18px; box-shadow: 0 1px 2px rgba(0,0,0,0.03); }
.label { color: #666; font-size: 13px; margin-bottom: 8px; }
.value { font-size: 30px; font-weight: 700; }
.hint { margin-top: 28px; background: #fafafa; border: 1px solid #f0f0f0; border-radius: 6px; padding: 14px 18px; color: #555; font-size: 13px; }
.hint ul { margin: 6px 0 0 18px; padding: 0; }
.toast-inline { background: #e6f4ff; border: 1px solid #91caff; padding: 6px 12px; border-radius: 4px; margin-bottom: 10px; }
</style>
