<script setup>
/**
 * 总账 M5——期末处理（结账向导）
 * 8 步：①事件检查 ②单据检查 ③折旧计提(M7跳过) ④自动转账 ⑤结转损益 ⑥结账检查 ⑦期末结账 ⑧业财对账(M6)
 * 自动转账/结转损益生成的均为「转」字草稿凭证，需到凭证管理审核、过账后才能结账。
 * 反结账：仅最后一个已结账期间、原因必填；年度结账（12 期）冻结不可反。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { post } from '../../api/client.js'

const router = useRouter()
const periods = ref([])
const period = ref('')
const wizard = ref(null)
const feedback = ref('')
const busy = ref(false)
const reopenModal = ref({ open: false, period: '', reason: '' })

const STEP_ICON = {
  pass: ['✓', 'st-pass'], done: ['✓', 'st-pass'], ready: ['✓', 'st-pass'],
  todo: ['●', 'st-todo'], warn: ['!', 'st-warn'], fail: ['✕', 'st-fail'],
  skip: ['—', 'st-skip'], frozen: ['❄', 'st-frozen'],
}

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 6000)
}
function money(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function step(no) { return (wizard.value?.steps || []).find(s => s.no === no) }
function icon(st) { return STEP_ICON[st] || ['?', 'st-skip'] }

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
    wizard.value = await post('/finance/gl/period/wizard', { period: period.value })
    period.value = wizard.value.period
  } catch (e) { show('向导加载失败：' + (e?.message || e), 'err') }
}
async function onPeriodChange() { await load() }

async function runTransfer() {
  if (!confirm('将按转账模板生成本期自动转账凭证（草稿，零金额/借贷不平的模板自动跳过）。确定？')) return
  busy.value = true
  try {
    const r = await post('/finance/gl/period/transfer-execute', { period: period.value })
    const created = (r.results || []).filter(x => x.status === 'created').length
    const skipped = (r.results || []).filter(x => x.status === 'skip').length
    const exists = (r.results || []).filter(x => x.status === 'exists').length
    show(`自动转账完成：生成 ${created} 张，跳过 ${skipped} 张，已存在 ${exists} 张。生成的草稿凭证请到「凭证管理」审核过账`)
  } catch (e) { show('自动转账失败：' + (e?.message || e), 'err') }
  finally { busy.value = false; await load() }
}

async function carryProfit() {
  if (!confirm('将把本期损益类科目净发生额结转至 3103 本年利润，生成「转」字草稿凭证。确定？')) return
  busy.value = true
  try {
    const r = await post('/finance/gl/period/profit-carry', { period: period.value })
    show(`结转损益凭证已生成（草稿）：收入 ${money(r.totalIncome)}，成本费用 ${money(r.totalExpense)}，净利润 ${money(r.netProfit)}。请到「凭证管理」审核过账`)
  } catch (e) { show('结转损益失败：' + (e?.message || e), 'err') }
  finally { busy.value = false; await load() }
}

async function closePeriod() {
  const s7 = step(7)
  if (s7?.status !== 'ready') return show('请先完成前面步骤并通过结账检查', 'err')
  if (!confirm(`确认结账 ${period.value}？结账后该期间不能再录入凭证；12 月结账为年度结账，结账后冻结不可反结。`)) return
  busy.value = true
  try {
    const r = await post('/finance/gl/period/close', { period: period.value })
    show(`${r.period} 已${r.status}，下一会计期间 ${r.nextPeriod} 已开账`)
  } catch (e) { show('结账失败：' + (e?.message || e), 'err') }
  finally { busy.value = false; await loadPeriods(); await load() }
}

function openReopen() {
  const lc = wizard.value?.lastClosed
  if (!lc) return
  if (lc.status === '已冻结') return show(`${lc.period} 为年度结账（已冻结），不能反结账`, 'err')
  reopenModal.value = { open: true, period: lc.period, reason: '' }
}
async function submitReopen() {
  const reason = reopenModal.value.reason.trim()
  if (reason.length < 2) return show('请填写反结账原因（至少 2 个字）', 'err')
  busy.value = true
  try {
    const r = await post('/finance/gl/period/reopen', { period: reopenModal.value.period, reason })
    show(`已反结账 ${r.period}：期间恢复为进行中，${r.revertedVouchers} 张结转/转账凭证回退为草稿，可修改或删除`)
    reopenModal.value.open = false
  } catch (e) { show('反结账失败：' + (e?.message || e), 'err') }
  finally { busy.value = false; await loadPeriods(); await load() }
}

onMounted(async () => { await loadPeriods(); await load() })
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <label>会计期间：</label>
      <select v-model="period" @change="onPeriodChange" style="width:140px">
        <option v-for="p in periods" :key="p.period" :value="p.period">
          {{ p.period }}（{{ p.status }}）
        </option>
      </select>
      <button class="btn" @click="load">刷新</button>
      <span style="flex:1"></span>
      <span v-if="wizard" class="period-end">期末日期：{{ wizard.periodEnd }}</span>
    </div>

    <div v-if="feedback" :class="['toast-inline', feedback.level]">{{ feedback.msg }}</div>

    <!-- ① 事件检查 -->
    <div class="card-box step-card">
      <div class="step-h">
        <span :class="['step-ic', icon(step(1)?.status)[1]]">{{ icon(step(1)?.status)[0] }}</span>
        <b>① 事件检查</b>
        <span class="step-detail">{{ step(1)?.detail }}</span>
        <a class="lk" @click="router.push('/gl/event')">去事件工作台 →</a>
      </div>
      <table v-if="(step(1)?.data || []).length" class="data">
        <thead><tr><th>事件</th><th>来源单据</th><th>状态</th><th>警告</th></tr></thead>
        <tbody>
          <tr v-for="(e, i) in step(1).data" :key="i">
            <td>{{ e.eventCode }}</td>
            <td>{{ e.sourceBillNo }}</td>
            <td><span class="tag tag-orange">{{ e.status }}</span></td>
            <td class="warn-text">{{ e.warnMsg }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ② 单据检查 -->
    <div class="card-box step-card">
      <div class="step-h">
        <span :class="['step-ic', icon(step(2)?.status)[1]]">{{ icon(step(2)?.status)[0] }}</span>
        <b>② 单据检查</b>
        <span class="step-detail">{{ step(2)?.detail }}</span>
      </div>
    </div>

    <!-- ③ 折旧计提 -->
    <div class="card-box step-card">
      <div class="step-h">
        <span :class="['step-ic', icon(step(3)?.status)[1]]">{{ icon(step(3)?.status)[0] }}</span>
        <b>③ 折旧计提</b>
        <span class="step-detail">{{ step(3)?.detail }}</span>
      </div>
    </div>

    <!-- ④ 自动转账 -->
    <div class="card-box step-card">
      <div class="step-h">
        <span :class="['step-ic', icon(step(4)?.status)[1]]">{{ icon(step(4)?.status)[0] }}</span>
        <b>④ 自动转账</b>
        <span class="step-detail">{{ step(4)?.detail }}</span>
        <span style="flex:1"></span>
        <button class="btn primary" v-permission="'finance.gl.period_close.audit'" :disabled="busy" @click="runTransfer">执行自动转账</button>
      </div>
      <table class="data">
        <thead><tr><th>模板</th><th>名称</th><th>状态</th><th>说明</th><th class="num">借方合计</th><th class="num">贷方合计</th></tr></thead>
        <tbody>
          <template v-for="t in step(4)?.data || []" :key="t.transferNo">
            <tr>
              <td>{{ t.transferNo }}</td>
              <td>{{ t.transferName }}<div v-if="t.remark" class="sub">{{ t.remark }}</div></td>
              <td>
                <span :class="['tag', t.status === 'done' ? 'tag-green' : t.status === 'todo' ? 'tag-blue' : 'tag-gray']">
                  {{ { done: '已生成', todo: '待执行', skip: '跳过' }[t.status] || t.status }}
                </span>
              </td>
              <td class="warn-text">{{ t.message }}</td>
              <td class="num">{{ money(t.debitTotal) }}</td>
              <td class="num">{{ money(t.creditTotal) }}</td>
            </tr>
            <tr v-if="t.entries && t.status === 'todo'">
              <td colspan="6" class="sub-entries">
                <span v-for="(en, i) in t.entries" :key="i" class="entry-chip">
                  {{ en.direction }} {{ en.accountCode }} {{ en.summary }} <b>{{ money(en.amount) }}</b>
                </span>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <!-- ⑤ 结转损益 -->
    <div class="card-box step-card">
      <div class="step-h">
        <span :class="['step-ic', icon(step(5)?.status)[1]]">{{ icon(step(5)?.status)[0] }}</span>
        <b>⑤ 结转损益</b>
        <span class="step-detail">{{ step(5)?.detail }}</span>
        <span style="flex:1"></span>
        <button v-if="step(5)?.status === 'todo'" v-permission="'finance.gl.period_close.audit'" class="btn primary" :disabled="busy" @click="carryProfit">
          生成结转损益凭证（草稿）
        </button>
      </div>
      <template v-if="step(5)?.status === 'todo' && step(5).data">
        <table class="data">
          <thead><tr><th>科目</th><th>名称</th><th>类别</th><th>辅助核算</th><th class="num">本期净发生</th></tr></thead>
          <tbody>
            <tr v-for="(l, i) in step(5).data.lines" :key="i">
              <td>{{ l.accountCode }}</td>
              <td>{{ l.accountName }}</td>
              <td>{{ l.direction }}</td>
              <td class="sub">{{ l.auxText || '—' }}</td>
              <td class="num">{{ money(l.netAmount) }}</td>
            </tr>
          </tbody>
          <tfoot>
            <tr><td colspan="4" class="num">收入合计</td><td class="num">{{ money(step(5).data.totalIncome) }}</td></tr>
            <tr><td colspan="4" class="num">成本费用合计</td><td class="num">{{ money(step(5).data.totalExpense) }}</td></tr>
            <tr><td colspan="4" class="num"><b>净利润</b></td><td class="num"><b>{{ money(step(5).data.netProfit) }}</b></td></tr>
          </tfoot>
        </table>
      </template>
    </div>

    <!-- ⑥ 结账检查 -->
    <div class="card-box step-card">
      <div class="step-h">
        <span :class="['step-ic', icon(step(6)?.status)[1]]">{{ icon(step(6)?.status)[0] }}</span>
        <b>⑥ 结账检查（四项硬检查）</b>
        <span class="step-detail">{{ step(6)?.detail }}</span>
      </div>
      <table class="data">
        <thead><tr><th style="width:120px">检查项</th><th style="width:80px">结果</th><th>说明</th></tr></thead>
        <tbody>
          <template v-for="(c, i) in step(6)?.data || []" :key="i">
            <tr>
              <td>{{ c.name }}</td>
              <td><span :class="['tag', c.passed ? 'tag-green' : 'tag-red']">{{ c.passed ? '通过' : '未通过' }}</span></td>
              <td>{{ c.detail }}</td>
            </tr>
            <tr v-if="!c.passed && c.data">
              <td colspan="3" class="sub-entries">
                <template v-if="Array.isArray(c.data)">
                  <span v-for="(v, j) in c.data" :key="j" class="entry-chip">
                    {{ v.voucherWord }}{{ v.voucherNo || '（草稿）' }} {{ v.summary }}
                    <span class="tag tag-red">{{ v.status }}</span>
                  </span>
                </template>
                <span v-else class="warn-text">差额：{{ money(c.data.diff) }}</span>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <!-- ⑦ 期末结账 -->
    <div class="card-box step-card">
      <div class="step-h">
        <span :class="['step-ic', icon(step(7)?.status)[1]]">{{ icon(step(7)?.status)[0] }}</span>
        <b>⑦ 期末结账</b>
        <span class="step-detail">{{ step(7)?.detail }}</span>
        <span style="flex:1"></span>
        <button class="btn primary" v-permission="'finance.gl.period_close.close'" :disabled="busy || step(7)?.status !== 'ready'" @click="closePeriod">
          {{ period.endsWith('12') ? '年度结账（冻结）' : '期末结账' }}
        </button>
      </div>
      <p class="tip">结账后本期间状态置为「已结账」并自动开启下一期间；12 月结账为年度结账，期间冻结、不可反结账，并自动生成下一年度 12 个会计期间。</p>
    </div>

    <!-- ⑧ 业财对账 -->
    <div class="card-box step-card">
      <div class="step-h">
        <span :class="['step-ic', icon(step(8)?.status)[1]]">{{ icon(step(8)?.status)[0] }}</span>
        <b>⑧ 业财对账</b>
        <span class="step-detail">{{ step(8)?.detail }}</span>
      </div>
    </div>

    <!-- 反结账 -->
    <div v-if="wizard?.lastClosed" class="card-box reopen-box">
      <div class="step-h">
        <b>反结账</b>
        <span class="step-detail">
          最后已结账期间：{{ wizard.lastClosed.period }}（{{ wizard.lastClosed.status }}）
          <span class="sub">结账人 {{ wizard.lastClosed.settleName || '—' }}</span>
        </span>
        <span style="flex:1"></span>
        <button class="btn" v-permission="'finance.gl.period_close.unaudit'" :disabled="busy || wizard.lastClosed.status === '已冻结'" @click="openReopen">反结账</button>
      </div>
      <p v-if="wizard.lastClosed.status === '已冻结'" class="tip">年度结账已冻结，不能反结账。</p>
      <p v-else class="tip">反结账后该期间恢复为「进行中」，本期结转损益/自动转账凭证回退为草稿（可修改或删除后重新结转）；操作人、时间与原因全部留痕。</p>
    </div>

    <!-- 反结账原因弹窗 -->
    <div v-if="reopenModal.open" class="modal-mask" @click.self="reopenModal.open = false">
      <div class="modal w520">
        <div class="modal-h">反结账 {{ reopenModal.period }}<span class="modal-x" @click="reopenModal.open = false">×</span></div>
        <div class="modal-b">
          <p class="tip">反结账原因（必填，将记入操作日志）：</p>
          <textarea v-model="reopenModal.reason" rows="4" style="width:100%" placeholder="例如：发现 6 月有一张费用凭证漏记，需补录后重新结账"></textarea>
          <div style="text-align:right;margin-top:12px">
            <button class="btn" @click="reopenModal.open = false">取消</button>
            <button class="btn primary" v-permission="'finance.gl.period_close.unaudit'" :disabled="busy" @click="submitReopen">确认反结账</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.period-end { color: #666; font-size: 13px; }
.step-card { margin-bottom: 12px; padding: 12px 16px; }
.step-h { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.step-h b { font-size: 14px; }
.step-detail { color: #555; font-size: 13px; }
.step-ic {
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border-radius: 50%; font-size: 13px; font-weight: 700; flex: none;
}
.st-pass { background: #e8f8ee; color: #1a9e54; }
.st-todo { background: #e8f1fd; color: #1d6fd1; }
.st-warn { background: #fdf2e3; color: #d08010; }
.st-fail { background: #fdeaea; color: #d33; }
.st-skip { background: #f0f0f0; color: #999; }
.st-frozen { background: #e8f4fd; color: #2b7ab8; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 12px; }
.tag-blue { background: #e8f1fd; color: #1d6fd1; }
.tag-green { background: #e8f8ee; color: #1a9e54; }
.tag-gray { background: #f0f0f0; color: #888; }
.tag-red { background: #fdeaea; color: #d33; }
.tag-orange { background: #fdf2e3; color: #d08010; }
.sub { color: #999; font-size: 12px; }
.warn-text { color: #b07010; font-size: 12px; word-break: break-all; }
.lk { color: #1d6fd1; cursor: pointer; white-space: nowrap; font-size: 13px; }
.num { text-align: right; }
.sub-entries { padding: 6px 12px; background: #fafbfc; }
.entry-chip { display: inline-block; margin: 2px 14px 2px 0; font-size: 12px; color: #444; }
.entry-chip b { color: #1d6fd1; margin-left: 4px; }
.tip { color: #888; font-size: 12px; margin: 6px 0 0; }
.reopen-box { border: 1px dashed #d08010; }
tfoot td { border-top: 2px solid #e5e7eb; font-size: 13px; }
</style>
