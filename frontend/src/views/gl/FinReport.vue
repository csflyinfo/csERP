<script setup>
/**
 * 总账 M6——总账报表：资产负债表 / 利润表 / 现金流量表
 * 数据来自 fin_report_item 公式种子，由后端 GlReportCalc 实时取数。
 * BS 期末/年初两列并做平衡校验；IS 本月/本年累计；CF 本月/本年累计。
 * CF 页签内嵌「现金流量项目补录」：已过账凭证的现金类分录未指定流量项目的，可批量补录。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const TABS = [
  { code: 'BS', name: '资产负债表', cur: '期末数', begin: '年初数' },
  { code: 'IS', name: '利润表', cur: '本月数', begin: '本年累计' },
  { code: 'CF', name: '现金流量表', cur: '本月金额', begin: '本年累计' },
]
const tab = ref('BS')
const periods = ref([])
const period = ref('')
const report = ref(null)
const feedback = ref('')
const busy = ref(false)

// 现金流量补录
const pending = ref([])
const cfItems = ref([])
const picks = ref({})

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 6000)
}
function money(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function curTab() { return TABS.find(t => t.code === tab.value) }
function rowClass(r) {
  return r.rowType === '合计' ? 'row-total' : r.rowType === '小计' ? 'row-subtotal' : ''
}

async function loadPeriods() {
  try {
    periods.value = await post('/finance/gl/period/list', {})
    if (!period.value) {
      const cur = periods.value.find(p => p.status === '进行中')
      period.value = cur ? cur.period : (periods.value[periods.value.length - 1]?.period || '')
    }
  } catch (e) { show('期间加载失败：' + (e?.message || e), 'err') }
}
async function loadReport() {
  try {
    report.value = await post('/finance/gl/report/data', { reportCode: tab.value, period: period.value })
  } catch (e) { report.value = null; show('报表加载失败：' + (e?.message || e), 'err') }
}
async function switchTab(code) {
  tab.value = code
  await loadReport()
  if (code === 'CF') await loadPending()
}
async function onPeriodChange() {
  await loadReport()
  if (tab.value === 'CF') await loadPending()
}

async function loadPending() {
  try {
    const [rows, items] = await Promise.all([
      post('/finance/gl/report/cf-pending', { period: period.value }),
      post('/finance/gl/report/cf-items', {}),
    ])
    pending.value = rows
    cfItems.value = items
    const p = {}
    for (const r of rows) if (!p[r.entryId]) p[r.entryId] = ''
    picks.value = p
  } catch (e) { show('补录列表加载失败：' + (e?.message || e), 'err') }
}
async function saveFill() {
  const items = Object.entries(picks.value)
    .filter(([, cf]) => cf)
    .map(([entryId, cashFlowItem]) => ({ entryId, cashFlowItem }))
  if (!items.length) { show('请先为分录选择现金流量项目', 'err'); return }
  busy.value = true
  try {
    const r = await post('/finance/gl/report/cf-fill', { items })
    show(`已补录 ${r.updated} 条现金流量项目`)
    await loadPending()
    await loadReport()
  } catch (e) { show('补录失败：' + (e?.message || e), 'err') } finally { busy.value = false }
}

// ================= 公式编辑（M9） =================
const formulaDlg = ref({ open: false, busy: false, rows: [] })
async function openFormula() {
  try {
    const rows = await post('/finance/gl/report/formula-list', { reportCode: tab.value })
    // 拷贝成可编辑副本，记录改动
    formulaDlg.value.rows = rows.map(r => ({
      id: r.id, lineNo: r.lineNo, itemName: r.itemName, rowType: r.rowType,
      formula: r.formula || '', formulaBegin: r.formulaBegin || '',
      isSystem: r.isSystem,
      origItemName: r.itemName, origFormula: r.formula || '', origFormulaBegin: r.formulaBegin || '',
    }))
    formulaDlg.value.open = true
  } catch (e) { show('公式列表加载失败：' + (e?.message || e), 'err') }
}
function rowDirty(r) {
  return r.itemName !== r.origItemName || r.formula !== r.origFormula || r.formulaBegin !== r.origFormulaBegin
}
async function saveFormulaRow(r) {
  formulaDlg.value.busy = true
  try {
    await post('/finance/gl/report/formula-save', {
      id: r.id, itemName: r.itemName, formula: r.formula, formulaBegin: r.formulaBegin,
    })
    r.origItemName = r.itemName; r.origFormula = r.formula; r.origFormulaBegin = r.formulaBegin
    show(`第 ${r.lineNo} 行「${r.itemName}」公式已保存`)
    await loadReport()
  } catch (e) { show('公式保存失败：' + (e?.message || e), 'err') }
  finally { formulaDlg.value.busy = false }
}

onMounted(async () => {
  await loadPeriods()
  await loadReport()
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <span class="page-title">总账报表</span>
      <label>会计期间
        <select v-model="period" @change="onPeriodChange">
          <option v-for="p in periods" :key="p.period" :value="p.period">{{ p.period }}（{{ p.status }}）</option>
        </select>
      </label>
      <button class="btn" @click="loadReport">刷新</button>
      <div class="tabs">
        <button v-for="t in TABS" :key="t.code"
                :class="['tab-btn', tab === t.code ? 'active' : '']"
                @click="switchTab(t.code)">{{ t.name }}</button>
      </div>
      <div class="spacer"></div>
      <button class="btn" v-permission="'finance.gl.report.edit'" @click="openFormula">公式编辑</button>
    </div>
    <div v-if="feedback" :class="['toast-inline', feedback.level]">{{ feedback.msg }}</div>

    <div v-if="report" class="card-box">
      <div v-if="report.balance" :class="['balance-banner', report.balance.balanced ? 'ok' : 'bad']">
        <span>{{ report.balance.balanced ? '✓' : '✕' }}</span>
        <span>{{ report.balance.message }}</span>
        <span class="bal-nums">
          资产总计 {{ money(report.balance.assetsAmount) }} ｜
          负债和所有者权益总计 {{ money(report.balance.liabEquityAmount) }} ｜
          差额 {{ money(report.balance.diff) }}
        </span>
      </div>
      <table class="data report-table">
        <thead>
          <tr>
            <th>项目</th>
            <th class="num">{{ curTab().cur }}</th>
            <th class="num">{{ curTab().begin }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in report.rows" :key="r.lineNo" :class="rowClass(r)">
            <td :style="{ paddingLeft: (12 + (r.indentLevel || 0) * 18) + 'px' }">{{ r.itemName }}</td>
            <td class="num">{{ money(r.amount) }}</td>
            <td class="num">{{ money(r.beginAmount) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="tab === 'CF'" class="card-box">
      <div class="fill-h">
        <b>现金流量项目补录</b>
        <span class="sub">已过账凭证中现金类分录未指定流量项目的，可在此批量补录（仅影响现金流量表列报，不影响余额）</span>
        <button class="btn primary" v-permission="'finance.gl.report.edit'" :disabled="busy" @click="saveFill">保存补录</button>
      </div>
      <table v-if="pending.length" class="data">
        <thead>
          <tr>
            <th>凭证号</th><th>期间</th><th>摘要</th><th>科目</th>
            <th class="num">金额</th><th>方向</th><th>现金流量项目</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in pending" :key="r.entryId">
            <td>{{ r.voucherNo }}</td>
            <td>{{ r.period }}</td>
            <td>{{ r.summary }}</td>
            <td>{{ r.accountCode }} {{ r.accountName }}</td>
            <td class="num">{{ money(r.amount) }}</td>
            <td><span :class="['tag', r.direction.includes('流入') ? 'tag-blue' : 'tag-orange']">{{ r.direction }}</span></td>
            <td>
              <select v-model="picks[r.entryId]">
                <option value="">— 请选择 —</option>
                <option v-for="it in cfItems" :key="it.itemCode" :value="it.itemCode">
                  {{ it.itemCode }} {{ it.itemName }}（{{ it.direction }}）
                </option>
              </select>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-tip">本期没有待补录的现金分录 ✓</div>
    </div>

    <!-- 公式编辑抽屉（M9） -->
    <div v-if="formulaDlg.open" class="drawer-mask" @click.self="formulaDlg.open=false">
      <div class="drawer">
        <div class="drawer-h">
          <b>{{ curTab().name }}——报表项目公式编辑</b>
          <span class="x" @click="formulaDlg.open=false">×</span>
        </div>
        <div class="drawer-body">
          <div class="formula-hint">
            取数函数：<code>QM('科目')</code> 期末余额、<code>QC</code> 期初余额、<code>FSD/FSC</code> 本期借/贷发生、
            <code>LJFS/LJFSC</code> 本年累计借/贷发生、<code>CF('项目')</code> 现金流量项目金额；
            支持 <code>+ - * / ( )</code>，如 <code>QM('1001')+QM('1002')</code>。保存时按当前期间试算，公式有误将拒绝保存。
          </div>
          <table class="data formula-table">
            <thead>
              <tr>
                <th style="width:50px">行次</th><th style="width:180px">项目名称</th>
                <th>{{ curTab().cur }}公式</th><th>{{ curTab().begin }}公式</th>
                <th style="width:70px"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in formulaDlg.rows" :key="r.id" :class="{ changed: rowDirty(r) }">
                <td class="muted">{{ r.lineNo }}</td>
                <td><input v-model="r.itemName" :placeholder="r.rowType" /></td>
                <td><input v-model="r.formula" placeholder="（空=该行不取数）" /></td>
                <td><input v-model="r.formulaBegin" placeholder="（空=该行不取数）" /></td>
                <td>
                  <a v-if="rowDirty(r)" v-permission="'finance.gl.report.edit'" class="save-link" :class="{ disabled: formulaDlg.busy }" @click="saveFormulaRow(r)">保存</a>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="drawer-f">
          <button class="btn" @click="formulaDlg.open=false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tabs { display: inline-flex; gap: 4px; margin-left: 12px; }
.tab-btn {
  border: 1px solid #d9dde3; background: #fff; padding: 4px 14px; border-radius: 4px;
  cursor: pointer; font-size: 13px; color: #555;
}
.tab-btn.active { background: #1d6fd1; border-color: #1d6fd1; color: #fff; }
.card-box { padding: 12px 16px; margin-bottom: 12px; }
.balance-banner {
  display: flex; align-items: center; gap: 10px; padding: 8px 12px; border-radius: 4px;
  margin-bottom: 10px; font-size: 13px;
}
.balance-banner.ok { background: #e8f8ee; color: #1a9e54; }
.balance-banner.bad { background: #fdeaea; color: #d33; }
.bal-nums { margin-left: auto; color: #555; font-size: 12px; }
.report-table td, .report-table th { font-size: 13px; }
.row-subtotal td { font-weight: 700; background: #fafbfc; }
.row-total td { font-weight: 700; background: #f3f6fa; border-top: 2px solid #dfe4ea; }
.fill-h { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.fill-h .sub { color: #888; font-size: 12px; font-weight: 400; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 12px; }
.tag-blue { background: #e8f1fd; color: #1d6fd1; }
.tag-orange { background: #fdf2e3; color: #d08010; }
.num { text-align: right; }
.empty-tip { color: #888; font-size: 13px; padding: 12px 0; }
.spacer { flex: 1; }
.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,.35); z-index: 100; display: flex; justify-content: flex-end; }
.drawer { width: 900px; max-width: 96vw; background: #fff; height: 100%; display: flex; flex-direction: column; }
.drawer-h { padding: 14px 18px; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; align-items: center; }
.x { cursor: pointer; font-size: 20px; color: #999; }
.drawer-body { flex: 1; overflow: auto; padding: 14px 18px; }
.drawer-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; display: flex; justify-content: flex-end; }
.formula-hint { font-size: 12px; color: #777; background: #f7f9fc; border: 1px solid #eef1f5; border-radius: 6px; padding: 8px 12px; margin-bottom: 12px; line-height: 1.8; }
.formula-hint code { background: #eef3fa; color: #1d6fd1; padding: 0 4px; border-radius: 3px; font-family: monospace; }
.formula-table td { padding: 4px 6px; vertical-align: middle; }
.formula-table input { width: 100%; box-sizing: border-box; padding: 5px 8px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 12px; font-family: monospace; }
.formula-table tr.changed { background: #fffbe6; }
.save-link { color: #1d6fd1; cursor: pointer; font-size: 12px; }
.save-link.disabled { color: #bbb; cursor: wait; }
.muted { color: #999; }
</style>
