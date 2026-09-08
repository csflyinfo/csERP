<script setup>
/**
 * 总账 M6——业财对账
 * 三组：应收账款 1122 vs 应收单未收（按客户明细）、应付账款 2202 vs 应付单未付（按供应商明细）、
 * 资金科目（is_cash）vs 资金账户余额（按 base_fund_account.gl_account_code 映射明细）。
 * 容差 0.02 元；差额超容差红色提示，可展开明细定位差异往来单位/账户。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const periods = ref([])
const period = ref('')
const data = ref(null)
const feedback = ref('')
const expanded = ref({ ar: true, ap: true, cash: true })

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 6000)
}
function money(v) {
  if (v === null || v === undefined || v === '') return '—'
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function diffClass(g) { return g.matched ? 'tag-green' : 'tag-red' }
function toggle(key) { expanded.value[key] = !expanded.value[key] }

async function loadPeriods() {
  try {
    periods.value = await post('/finance/gl/period/list', {})
    if (!period.value) {
      const cur = periods.value.find(p => p.status === '进行中')
      period.value = cur ? cur.period : (periods.value[periods.value.length - 1]?.period || '')
    }
  } catch (e) { show('期间加载失败：' + (e?.message || e), 'err') }
}
async function load() {
  try {
    data.value = await post('/finance/gl/report/reconcile', { period: period.value })
  } catch (e) { data.value = null; show('对账加载失败：' + (e?.message || e), 'err') }
}

onMounted(async () => {
  await loadPeriods()
  await load()
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <span class="page-title">业财对账</span>
      <label>会计期间
        <select v-model="period" @change="load">
          <option v-for="p in periods" :key="p.period" :value="p.period">{{ p.period }}（{{ p.status }}）</option>
        </select>
      </label>
      <button class="btn" @click="load">刷新</button>
      <span v-if="data" :class="['tag', data.matched ? 'tag-green' : 'tag-red']">
        {{ data.matched ? '✓ 三组全部平衡' : '✕ 存在差额，请查明细' }}
      </span>
    </div>
    <div v-if="feedback" :class="['toast-inline', feedback.level]">{{ feedback.msg }}</div>

    <div v-for="g in (data?.groups || [])" :key="g.key" class="card-box recon-card">
      <div class="recon-h">
        <b>{{ g.name }}</b>
        <span :class="['tag', diffClass(g)]">{{ g.matched ? '✓ 平衡' : '✕ 差额 ' + money(g.diff) }}</span>
        <a class="lk" @click="toggle(g.key)">{{ expanded[g.key] ? '收起明细' : '展开明细' }}</a>
      </div>
      <div class="recon-sum">
        <div class="sum-cell"><span class="sum-label">{{ g.glLabel }}</span><b>{{ money(g.glAmount) }}</b></div>
        <div class="sum-op">vs</div>
        <div class="sum-cell"><span class="sum-label">{{ g.bizLabel }}</span><b>{{ money(g.bizAmount) }}</b></div>
        <div class="sum-op">=</div>
        <div class="sum-cell"><span class="sum-label">差额</span>
          <b :class="g.matched ? 'ok-text' : 'bad-text'">{{ money(g.diff) }}</b>
        </div>
      </div>
      <table v-if="expanded[g.key] && (g.details || []).length" class="data recon-detail">
        <thead>
          <tr>
            <th>{{ g.key === 'cash' ? '资金账户 / 科目' : '往来单位' }}</th>
            <th v-if="g.key === 'cash'">映射科目</th>
            <th class="num">总账金额</th>
            <th class="num">业务金额</th>
            <th class="num">差额</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(d, i) in g.details" :key="i"
              :class="(d.diff !== null && Math.abs(Number(d.diff || 0)) >= 0.02) ? 'row-diff' : ''">
            <td>{{ d.party }}</td>
            <td v-if="g.key === 'cash'">{{ d.glAccountCode || '（未映射）' }}</td>
            <td class="num">{{ money(d.glAmount) }}</td>
            <td class="num">{{ money(d.bizAmount) }}</td>
            <td class="num">{{ money(d.diff) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="expanded[g.key] && !(g.details || []).length" class="empty-tip">无明细（双方均为 0）</div>
    </div>
  </div>
</template>

<style scoped>
.card-box { padding: 12px 16px; margin-bottom: 12px; }
.recon-h { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.recon-h .lk { margin-left: auto; }
.tag { display: inline-block; padding: 1px 10px; border-radius: 10px; font-size: 12px; }
.tag-green { background: #e8f8ee; color: #1a9e54; }
.tag-red { background: #fdeaea; color: #d33; }
.lk { color: #1d6fd1; cursor: pointer; font-size: 13px; }
.recon-sum { display: flex; align-items: center; gap: 18px; background: #fafbfc; padding: 10px 14px; border-radius: 4px; }
.sum-cell { display: flex; flex-direction: column; gap: 2px; }
.sum-label { color: #888; font-size: 12px; }
.sum-cell b { font-size: 16px; }
.sum-op { color: #aaa; font-size: 13px; }
.ok-text { color: #1a9e54; }
.bad-text { color: #d33; }
.recon-detail { margin-top: 10px; }
.recon-detail td, .recon-detail th { font-size: 13px; }
.row-diff td { background: #fff6f6; color: #d33; }
.num { text-align: right; }
.empty-tip { color: #888; font-size: 13px; padding: 10px 0; }
</style>
