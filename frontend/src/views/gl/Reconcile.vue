<script setup>
/**
 * 总账 M6——业财对账（PRD-36 M4 起五组）
 * 硬平衡三组：应收账款 1122 vs 应收单未收、应付账款 2202 vs 应付单未付、资金科目 vs 资金账户余额；
 * 提示性两组：预付账款 1123 vs 供应商账户预付余额、1221 厂家费用（允许总账手工凭证解释差异，不翻总标志）。
 * 容差 0.02 元；差额超容差红色/橙色提示，可展开明细定位差异往来单位/账户。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const periods = ref([])
const period = ref('')
const data = ref(null)
const feedback = ref('')
const expanded = ref({ ar: true, ap: true, prepay: true, factoryExpense: true, cash: true })

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 6000)
}
function money(v) {
  if (v === null || v === undefined || v === '') return '—'
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function tagClass(g) {
  if (g.matched) return 'tag-green'
  return g.advisory ? 'tag-orange' : 'tag-red'
}
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
      <span v-if="data" :class="['tag', data.hardMatched ? 'tag-green' : 'tag-red']">
        {{ data.hardMatched ? '✓ 应收/应付/资金三组硬平衡' : '✕ 硬平衡组存在差额，请查明细' }}
      </span>
      <span v-if="data && !data.matched" class="tag tag-orange">
        ⚠ 提示性对账组（预付/厂家费用）存在差额，需可解释
      </span>
    </div>
    <div v-if="feedback" :class="['toast-inline', feedback.level]">{{ feedback.msg }}</div>

    <div v-for="g in (data?.groups || [])" :key="g.key" class="card-box recon-card">
      <div class="recon-h">
        <b>{{ g.name }}</b>
        <span v-if="g.advisory" class="tag tag-advisory">提示性对账</span>
        <span :class="['tag', tagClass(g)]">{{ g.matched ? '✓ 平衡' : '✕ 差额 ' + money(g.diff) }}</span>
        <a class="lk" @click="toggle(g.key)">{{ expanded[g.key] ? '收起明细' : '展开明细' }}</a>
      </div>
      <div v-if="g.advisory && g.note" class="advisory-note">{{ g.note }}</div>
      <div class="recon-sum">
        <div class="sum-cell"><span class="sum-label">{{ g.glLabel }}</span><b>{{ money(g.glAmount) }}</b></div>
        <div class="sum-op">vs</div>
        <div class="sum-cell"><span class="sum-label">{{ g.bizLabel }}</span><b>{{ money(g.bizAmount) }}</b></div>
        <div class="sum-op">=</div>
        <div class="sum-cell"><span class="sum-label">差额</span>
          <b :class="g.matched ? 'ok-text' : (g.advisory ? 'warn-text' : 'bad-text')">{{ money(g.diff) }}</b>
        </div>
      </div>
      <!-- 厂家费用：期末余额与本年兑现的辅助观察行 -->
      <div v-if="g.key === 'factoryExpense'" class="recon-extra">
        <span>总账 1221 供应商辅助期末余额 <b>{{ money(g.glOutstanding) }}</b></span>
        <span class="sep">|</span>
        <span>业务费用余额（未兑现） <b>{{ money(g.bizOutstanding) }}</b></span>
        <span class="sep">|</span>
        <span>余额差 <b :class="Math.abs(Number(g.outstandingDiff || 0)) < 0.02 ? 'ok-text' : 'warn-text'">{{ money(g.outstandingDiff) }}</b></span>
        <span class="sep">|</span>
        <span>本年贷方兑现（GL） <b>{{ money(g.glCreditYtd) }}</b></span>
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
              :class="(d.diff !== null && Math.abs(Number(d.diff || 0)) >= 0.02) ? (g.advisory ? 'row-warn' : 'row-diff') : ''">
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
.tag-orange { background: #fff7e6; color: #d46b08; }
.tag-advisory { background: #f0f5ff; color: #2f54eb; border: 1px solid #adc6ff; }
.advisory-note { font-size: 12px; color: #ad6800; background: #fffbe6; border: 1px solid #ffe58f;
  border-radius: 6px; padding: 6px 10px; margin-bottom: 8px; }
.lk { color: #1d6fd1; cursor: pointer; font-size: 13px; }
.recon-sum { display: flex; align-items: center; gap: 18px; background: #fafbfc; padding: 10px 14px; border-radius: 4px; }
.sum-cell { display: flex; flex-direction: column; gap: 2px; }
.sum-label { color: #888; font-size: 12px; }
.sum-cell b { font-size: 16px; }
.sum-op { color: #aaa; font-size: 13px; }
.ok-text { color: #1a9e54; }
.bad-text { color: #d33; }
.warn-text { color: #d46b08; }
.recon-extra { display: flex; flex-wrap: wrap; gap: 10px; font-size: 12px; color: #888; margin-top: 8px; }
.recon-extra b { color: #555; font-weight: 600; }
.recon-extra .sep { color: #e0e0e0; }
.recon-detail { margin-top: 10px; }
.recon-detail td, .recon-detail th { font-size: 13px; }
.row-diff td { background: #fff6f6; color: #d33; }
.row-warn td { background: #fff8ec; color: #ad6800; }
.num { text-align: right; }
.empty-tip { color: #888; font-size: 13px; padding: 10px 0; }
</style>
