<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">报表运维（日结补算）</div>
    </div>

    <div class="admin-card">
      <div class="admin-card-title">采购域 DWS + 维度表手工重算</div>
      <p class="admin-desc">
        正常情况每日日结任务自动刷新。发现某日单据补审核/反审核后报表数不对时，可在此按区间补算：
        先全量刷新商品/往来单位维度，再按「删除分区 + INSERT...SELECT」幂等重算采购日汇总，并自动对账。
        单次区间最长 31 天。
      </p>
      <div class="admin-form">
        <div class="ff">
          <label>开始日期</label>
          <input type="date" v-model="recompute.startDate">
        </div>
        <div class="ff">
          <label>截止日期</label>
          <input type="date" v-model="recompute.endDate">
        </div>
        <button class="btn-primary" :disabled="busy.recompute" @click="doRecompute">
          {{ busy.recompute ? '重算中…' : '重算并对账' }}
        </button>
      </div>
      <div v-if="recomputeResult" class="admin-result">
        <div :class="recomputeResult.balanced ? 'ok-text' : 'warn-text'" style="font-weight:700;margin-bottom:6px;">
          {{ recomputeResult.balanced ? '✓ 对账平衡' : '✗ 对账不平，请检查后端日志' }}
        </div>
        <div>区间：{{ recomputeResult.start }} ~ {{ recomputeResult.end }}，重算分区行数：{{ recomputeResult.recomputedRows }}</div>
        <div class="kv">DWS 净额：{{ fmt2(recomputeResult.dwsAmount) }} ｜ 实时明细净额：{{ fmt2(recomputeResult.liveAmount) }} ｜ 差额：{{ fmt2(recomputeResult.diffAmount) }}</div>
        <div class="kv">DWS 净量：{{ fmt2(recomputeResult.dwsQty) }} ｜ 实时明细净量：{{ fmt2(recomputeResult.liveQty) }} ｜ 差额：{{ fmt2(recomputeResult.diffQty) }}</div>
      </div>
    </div>

    <div class="admin-card">
      <div class="admin-card-title">库存日快照重建</div>
      <p class="admin-desc">
        按指定日期重建库存日快照（用于库存类报表/趋势的日结底座，正常每日日结自动生成）。
        测试环境同样按日结生成快照。
      </p>
      <div class="admin-form">
        <div class="ff">
          <label>快照日期</label>
          <input type="date" v-model="snapshot.date">
        </div>
        <button class="btn-primary" :disabled="busy.snapshot" @click="doSnapshot">
          {{ busy.snapshot ? '重建中…' : '重建快照' }}
        </button>
      </div>
      <div v-if="snapshotResult" class="admin-result ok-text">
        ✓ {{ snapshotResult.date }} 库存日快照已重建，写入 {{ snapshotResult.rows }} 行。
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { recomputePurchaseDws, rebuildStockSnapshot } from '@/api/report-center.js'

function localDate(d) {
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
const today = new Date()
const twoDaysAgo = new Date(today.getTime() - 2 * 86400000)
const yesterday = new Date(today.getTime() - 86400000)

const recompute = reactive({ startDate: localDate(twoDaysAgo), endDate: localDate(today) })
const snapshot = reactive({ date: localDate(yesterday) })
const busy = reactive({ recompute: false, snapshot: false })
const recomputeResult = ref(null)
const snapshotResult = ref(null)

function fmt2(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : String(v)
}

async function doRecompute() {
  if (!recompute.startDate || !recompute.endDate) { alert('请选择起止日期'); return }
  busy.recompute = true
  recomputeResult.value = null
  try {
    recomputeResult.value = await recomputePurchaseDws({
      startDate: recompute.startDate,
      endDate: recompute.endDate,
    })
  } catch (e) {
    alert('重算失败：' + (e.message || '未知错误'))
  } finally {
    busy.recompute = false
  }
}

async function doSnapshot() {
  if (!snapshot.date) { alert('请选择快照日期'); return }
  busy.snapshot = true
  snapshotResult.value = null
  try {
    snapshotResult.value = await rebuildStockSnapshot({ date: snapshot.date })
  } catch (e) {
    alert('重建失败：' + (e.message || '未知错误'))
  } finally {
    busy.snapshot = false
  }
}
</script>

<style scoped>
@import './report-page.css';

.admin-card {
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .06);
  padding: 14px 18px;
  margin-bottom: 12px;
}
.admin-card-title {
  font-size: 14px;
  font-weight: 700;
  color: #303133;
  margin-bottom: 8px;
}
.admin-desc {
  font-size: 12.5px;
  color: #909399;
  line-height: 1.7;
  margin: 0 0 12px;
  max-width: 820px;
}
.admin-form {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  flex-wrap: wrap;
}
.admin-form .ff input {
  height: 30px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 0 8px;
  font-size: 13px;
}
.admin-result {
  margin-top: 12px;
  padding: 10px 12px;
  background: #fafafa;
  border-radius: 4px;
  font-size: 12.5px;
  color: #606266;
  line-height: 1.9;
}
.kv { font-variant-numeric: tabular-nums; }
</style>
